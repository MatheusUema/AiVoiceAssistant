// Ponte JNI mínima entre o app e o llama.cpp.
//
// Contrato de projeto:
//  - NENHUMA função aborta o processo por falha esperada. Carga que não cabe na RAM,
//    arquivo GGUF ausente/corrompido e falha de decode retornam código de erro +
//    mensagem legível (`lastError`) — a falha é RESULTADO de pesquisa, não crash.
//    (Device 2, 4 GB, é justamente o caso que precisamos registrar.)
//  - `generate` é bloqueante e devolve o texto completo, para preservar o contrato
//    `LocalInferenceService.generate(prompt): String`. As métricas por-inferência
//    (TTFT, tokens de prompt/geração, prefill vs decode) ficam em `lastStats`.
//  - A sessão NÃO é thread-safe. O lado Kotlin serializa tudo num único thread.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

#include <nlohmann/json.hpp>

#include "common.h"
#include "sampling.h"
#include "chat.h"
#include "peg-parser.h"
#include "llama.h"
#include "ggml-backend.h"

#define TAG "LlamaBridge"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGw(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int   kDefaultBatch     = 256;
constexpr int   kContextHeadroom  = 8;
constexpr float kUnavailable      = -1.0f;

std::mutex  g_err_mutex;
std::string g_last_error;
// Última linha de ERRO emitida pelo próprio llama.cpp — costuma ser a causa real
// de uma falha de carga (ex.: "unable to allocate ... buffer").
std::string g_last_native_error;

void set_error(const std::string & msg) {
    std::lock_guard<std::mutex> lock(g_err_mutex);
    g_last_error = msg;
    if (!g_last_native_error.empty()) {
        g_last_error += " | llama.cpp: " + g_last_native_error;
    }
    LOGe("%s", g_last_error.c_str());
}

void clear_error() {
    std::lock_guard<std::mutex> lock(g_err_mutex);
    g_last_error.clear();
    g_last_native_error.clear();
}

void log_callback(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) {
        return;
    }
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: {
            std::lock_guard<std::mutex> lock(g_err_mutex);
            g_last_native_error.assign(text);
            // llama.cpp emite com \n no fim; tira para caber numa linha de log/CSV.
            while (!g_last_native_error.empty() &&
                   (g_last_native_error.back() == '\n' || g_last_native_error.back() == '\r')) {
                g_last_native_error.pop_back();
            }
            __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
            break;
        }
        case GGML_LOG_LEVEL_WARN:  __android_log_print(ANDROID_LOG_WARN,  TAG, "%s", text); break;
        case GGML_LOG_LEVEL_INFO:  __android_log_print(ANDROID_LOG_INFO,  TAG, "%s", text); break;
        default:                   __android_log_print(ANDROID_LOG_DEBUG, TAG, "%s", text); break;
    }
}

struct llama_session {
    llama_model   * model = nullptr;
    llama_context * ctx   = nullptr;
    common_chat_templates_ptr templates;
    llama_batch     batch{};
    bool            batch_ready = false;
    int             n_batch     = kDefaultBatch;
    int             n_ctx       = 0;
    int             n_threads   = 0;

    // Estatísticas da última chamada a generate() — índices espelhados em LlamaStats.kt.
    double  ttft_ms          = kUnavailable;
    double  prefill_ms       = kUnavailable;
    double  decode_ms        = kUnavailable;
    double  total_ms         = kUnavailable;
    int32_t n_prompt_tokens  = 0;
    int32_t n_gen_tokens     = 0;
    int32_t n_reasoning_tokens = 0;

    /**
     * Texto do canal de raciocínio da última geração, separado da resposta.
     * O Gemma 4 raciocina antes de responder; sem separar, o "pensamento" chega ao aluno
     * como se fosse a resposta e os tokens dele se misturam aos da resposta em H3.
     */
    std::string last_reasoning;

    /** True se o template do modelo declara suporte a raciocínio. */
    bool supports_thinking = false;

    /**
     * True se a geração parou no token de fim; false se bateu no teto de tokens.
     * Uma resposta truncada é um dado de natureza diferente — precisa ser filtrável.
     */
    bool stopped_at_eog = false;

    /**
     * Pedido de cancelamento, vindo de outra thread (o watchdog de timeout).
     * Atômico porque o laço de geração roda na thread de inferência e quem cancela
     * é outra. O laço checa a cada token: abandonar a chamada JNI não é opção, ela
     * é bloqueante e continuaria queimando CPU e segurando a thread.
     */
    std::atomic<bool> cancel_requested{false};

    /** True se a última geração terminou por cancelamento (timeout). */
    bool was_cancelled = false;

    ~llama_session() {
        if (batch_ready) {
            llama_batch_free(batch);
        }
        templates.reset();
        if (ctx)   { llama_free(ctx); }
        if (model) { llama_model_free(model); }
    }
};

inline llama_session * as_session(jlong handle) {
    return reinterpret_cast<llama_session *>(handle);
}

double now_ms() {
    using clock = std::chrono::steady_clock;
    return std::chrono::duration<double, std::milli>(clock::now().time_since_epoch()).count();
}

// NewStringUTF espera "modified UTF-8"; sequências truncadas (que acontecem quando a
// geração para no meio de um caractere multi-byte) produzem lixo. Corta o rabo inválido.
std::string trim_to_valid_utf8(const std::string & in) {
    size_t end = in.size();
    size_t i   = 0;
    while (i < in.size()) {
        const auto c = static_cast<unsigned char>(in[i]);
        size_t len;
        if      ((c & 0x80) == 0x00) { len = 1; }
        else if ((c & 0xE0) == 0xC0) { len = 2; }
        else if ((c & 0xF0) == 0xE0) { len = 3; }
        else if ((c & 0xF8) == 0xF0) { len = 4; }
        else { end = i; break; }

        if (i + len > in.size()) { end = i; break; }
        bool ok = true;
        for (size_t k = 1; k < len; ++k) {
            if ((static_cast<unsigned char>(in[i + k]) & 0xC0) != 0x80) { ok = false; break; }
        }
        if (!ok) { end = i; break; }
        i += len;
        end = i;
    }
    return in.substr(0, end);
}

std::string jstring_to_std(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars == nullptr ? "" : chars);
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return out;
}

// Resultado da formatação: o prompt pronto e o que é preciso guardar para depois
// separar raciocínio de resposta na saída.
struct formatted_prompt {
    std::string        text;
    bool               has_template = false;
    common_chat_params chat_params;
};

// Aplica o chat template do GGUF a uma única mensagem de usuário.
//
// Usa `common_chat_templates_apply` (e não o atalho `common_chat_format_single`) porque
// só ele devolve o `common_chat_params` — que carrega o *formato* detectado e é o que
// permite depois chamar `common_chat_parse` e separar `reasoning_content` de `content`.
// É exatamente o caminho do `llama-server`: mesma formatação e mesma separação nos dois
// tiers, que é a condição para os números serem comparáveis.
formatted_prompt format_prompt(llama_session * s, const std::string & prompt, bool enable_thinking) {
    formatted_prompt out;
    out.has_template = s->templates && common_chat_templates_was_explicit(s->templates.get());
    if (!out.has_template) {
        out.text = prompt;
        return out;
    }

    common_chat_msg msg;
    msg.role    = "user";
    msg.content = prompt;

    common_chat_templates_inputs inputs;
    inputs.messages.push_back(msg);
    inputs.add_generation_prompt = true;
    inputs.use_jinja             = true;
    // Raciocínio ligado é o comportamento real do modelo; desligar é uma *condição
    // experimental*, não o default. Quem decide é o protocolo, não a ponte.
    inputs.enable_thinking       = enable_thinking;
    inputs.reasoning_format      = COMMON_REASONING_FORMAT_AUTO;

    try {
        out.chat_params        = common_chat_templates_apply(s->templates.get(), inputs);
        out.text               = out.chat_params.prompt;
        s->supports_thinking   = out.chat_params.supports_thinking;

        // Diagnóstico do que o llama.cpp detectou: é isto que decide se
        // common_chat_parse consegue separar raciocínio de resposta.
        std::string end_tags;
        for (const auto & t : out.chat_params.thinking_end_tags) {
            if (!end_tags.empty()) { end_tags += "|"; }
            end_tags += t;
        }
        LOGi("chat: format=%s supports_thinking=%d start_tag='%s' end_tags='%s' parser='%s'",
             common_chat_format_name(out.chat_params.format),
             (int) out.chat_params.supports_thinking,
             out.chat_params.thinking_start_tag.c_str(),
             end_tags.c_str(),
             out.chat_params.parser.c_str());
        return out;
    } catch (const std::exception & e) {
        LOGw("chat template (jinja) falhou: %s", e.what());
    }

    // Rede de segurança: o caminho legado usa a lista de templates compilada, que pode
    // não conhecer modelos novos. Sem separação de raciocínio, mas melhor que nada.
    try {
        const std::vector<common_chat_msg> no_history;
        out.text = common_chat_format_single(s->templates.get(), no_history, msg,
                                             /* add_ass */ true, /* use_jinja */ false);
        return out;
    } catch (const std::exception & e) {
        LOGw("chat template (legado) falhou: %s", e.what());
    }

    LOGw("nenhum chat template aplicável; usando o prompt cru");
    out.has_template = false;
    out.text         = prompt;
    return out;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_voiceassistant_llama_LlamaBridge_nativeInitBackend(JNIEnv * /*env*/, jobject /*thiz*/) {
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
    LOGi("backend inicializado");
}

JNIEXPORT void JNICALL
Java_com_voiceassistant_llama_LlamaBridge_nativeShutdownBackend(JNIEnv * /*env*/, jobject /*thiz*/) {
    llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_com_voiceassistant_llama_LlamaBridge_nativeSystemInfo(JNIEnv * env, jobject /*thiz*/) {
    return env->NewStringUTF(llama_print_system_info());
}

/** Backends ggml registrados (ex.: "CPU" no baseline; "Vulkan,CPU" com aceleração). */
JNIEXPORT jstring JNICALL
Java_com_voiceassistant_llama_LlamaBridge_nativeBackends(JNIEnv * env, jobject /*thiz*/) {
    std::string out;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        if (!out.empty()) { out += ","; }
        out += ggml_backend_reg_name(ggml_backend_reg_get(i));
    }
    return env->NewStringUTF(out.empty() ? "none" : out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_voiceassistant_llama_LlamaBridge_nativeLastError(JNIEnv * env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_err_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

/**
 * Carrega modelo + cria contexto. Retorna o handle da sessão, ou 0 em falha
 * (motivo em nativeLastError). Não lança e não aborta.
 */
JNIEXPORT jlong JNICALL
Java_com_voiceassistant_llama_LlamaBridge_nativeLoadModel(
        JNIEnv * env, jobject /*thiz*/,
        jstring jpath, jint n_ctx, jint n_threads, jint n_batch, jboolean flash_attn) {
    clear_error();

    const std::string path = jstring_to_std(env, jpath);
    if (path.empty()) {
        set_error("caminho do modelo vazio");
        return 0;
    }

    auto * s = new llama_session();

    llama_model_params model_params = llama_model_default_params();
    // mmap (sem mlock) mantém os pesos fora do heap do processo e deixa o kernel
    // despejar páginas sob pressão — decisivo no aparelho de 4 GB.
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;

    LOGi("carregando modelo: %s", path.c_str());
    s->model = llama_model_load_from_file(path.c_str(), model_params);
    if (s->model == nullptr) {
        set_error("llama_model_load_from_file falhou para " + path);
        delete s;
        return 0;
    }

    const int n_ctx_train = llama_model_n_ctx_train(s->model);
    int       n_ctx_eff   = n_ctx > 0 ? n_ctx : 2048;
    if (n_ctx_eff > n_ctx_train) {
        LOGw("n_ctx pedido (%d) > n_ctx_train (%d); usando %d", n_ctx_eff, n_ctx_train, n_ctx_train);
        n_ctx_eff = n_ctx_train;
    }

    const int threads = n_threads > 0 ? n_threads : 4;
    const int batch   = n_batch   > 0 ? n_batch   : kDefaultBatch;

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = static_cast<uint32_t>(n_ctx_eff);
    ctx_params.n_batch         = static_cast<uint32_t>(batch);
    ctx_params.n_ubatch        = static_cast<uint32_t>(batch);
    ctx_params.n_threads       = threads;
    ctx_params.n_threads_batch = threads;
    // Default do llama.cpp é no_perf=true (timings desligados). Sem isto,
    // llama_perf_context devolve t_p_eval_ms/t_eval_ms zerados e H2/H3 morrem.
    ctx_params.no_perf         = false;
    // Flash Attention explícito, nunca AUTO: no modo automático o llama.cpp decide em
    // runtime, e uma decisão diferente entre aparelhos ou execuções quebraria a
    // comparabilidade que o protocolo de medição exige (doc 04 §8).
    ctx_params.flash_attn_type = flash_attn
        ? LLAMA_FLASH_ATTN_TYPE_ENABLED
        : LLAMA_FLASH_ATTN_TYPE_DISABLED;

    s->ctx = llama_init_from_model(s->model, ctx_params);
    if (s->ctx == nullptr) {
        // Caminho típico de falta de RAM: os pesos entram via mmap mas o KV-cache não cabe.
        set_error("llama_init_from_model falhou (n_ctx=" + std::to_string(n_ctx_eff) +
                  ", provável falta de memória para o KV-cache)");
        delete s;
        return 0;
    }

    s->batch       = llama_batch_init(batch, 0, 1);
    s->batch_ready = true;
    s->templates   = common_chat_templates_init(s->model, "");
    s->n_batch     = batch;
    s->n_ctx       = n_ctx_eff;
    s->n_threads   = threads;

    LOGi("modelo pronto (n_ctx=%d, n_batch=%d, threads=%d, flash_attn=%s)",
         n_ctx_eff, batch, threads, flash_attn ? "on" : "off");
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_voiceassistant_llama_LlamaBridge_nativeFreeSession(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * s = as_session(handle);
    if (s != nullptr) {
        delete s;
    }
}

JNIEXPORT jstring JNICALL
Java_com_voiceassistant_llama_LlamaBridge_nativeModelDescription(JNIEnv * env, jobject /*thiz*/, jlong handle) {
    auto * s = as_session(handle);
    if (s == nullptr || s->model == nullptr) {
        return env->NewStringUTF("");
    }
    char desc[256] = {0};
    llama_model_desc(s->model, desc, sizeof(desc));
    return env->NewStringUTF(desc);
}

JNIEXPORT jlong JNICALL
Java_com_voiceassistant_llama_LlamaBridge_nativeModelSizeBytes(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * s = as_session(handle);
    return (s != nullptr && s->model != nullptr) ? static_cast<jlong>(llama_model_size(s->model)) : 0;
}

JNIEXPORT jint JNICALL
Java_com_voiceassistant_llama_LlamaBridge_nativeContextSize(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * s = as_session(handle);
    return (s != nullptr) ? s->n_ctx : 0;
}

/** Conta tokens de um texto já formatado — usado para checagem de orçamento de contexto. */
JNIEXPORT jint JNICALL
Java_com_voiceassistant_llama_LlamaBridge_nativeCountTokens(JNIEnv * env, jobject /*thiz*/, jlong handle, jstring jtext) {
    auto * s = as_session(handle);
    if (s == nullptr || s->ctx == nullptr) {
        return -1;
    }
    const auto tokens = common_tokenize(s->ctx, jstring_to_std(env, jtext), true, true);
    return static_cast<jint>(tokens.size());
}

/**
 * Geração completa (bloqueante) para um prompt único.
 * Cada chamada é independente: o KV-cache é limpo no início, espelhando a semântica
 * de sessão efêmera que o MediaPipe tinha, para não misturar histórico entre questões.
 *
 * Retorna o texto gerado, ou null em falha (motivo em nativeLastError).
 */
JNIEXPORT jstring JNICALL
Java_com_voiceassistant_llama_LlamaBridge_nativeGenerate(
        JNIEnv * env, jobject /*thiz*/, jlong handle, jstring jprompt,
        jint max_tokens, jfloat temperature, jint top_k, jfloat top_p, jint seed,
        jboolean enable_thinking) {
    clear_error();

    auto * s = as_session(handle);
    if (s == nullptr || s->ctx == nullptr || s->model == nullptr) {
        set_error("sessão não carregada");
        return nullptr;
    }

    s->ttft_ms = s->prefill_ms = s->decode_ms = s->total_ms = kUnavailable;
    s->n_prompt_tokens = s->n_gen_tokens = s->n_reasoning_tokens = 0;
    s->last_reasoning.clear();
    s->was_cancelled = false;
    s->cancel_requested.store(false, std::memory_order_relaxed);

    const double t_start = now_ms();

    // Fecha qualquer decode pendente da chamada anterior ANTES de zerar os contadores.
    // O llama.cpp contabiliza de forma preguiçosa, dentro do synchronize(): se a geração
    // anterior terminou logo após um decode (fim por EOG ou por teto de tokens), sobra uma
    // janela aberta que seria atribuída ao prefill desta chamada — foi o que fez o
    // prefill medido sair MAIOR que o TTFT.
    llama_synchronize(s->ctx);
    llama_memory_clear(llama_get_memory(s->ctx), true);
    llama_perf_context_reset(s->ctx);

    const std::string      raw_prompt = jstring_to_std(env, jprompt);
    const formatted_prompt fmt        = format_prompt(s, raw_prompt, enable_thinking);

    auto tokens = common_tokenize(s->ctx, fmt.text, fmt.has_template, fmt.has_template);
    if (tokens.empty()) {
        set_error("prompt tokenizou para 0 tokens");
        return nullptr;
    }

    const int n_predict = max_tokens > 0 ? max_tokens : 256;
    const int max_prompt = s->n_ctx - kContextHeadroom - n_predict;
    if (max_prompt <= 0) {
        set_error("n_ctx=" + std::to_string(s->n_ctx) + " insuficiente para maxTokens=" +
                  std::to_string(n_predict));
        return nullptr;
    }
    if (static_cast<int>(tokens.size()) > max_prompt) {
        LOGw("prompt com %d tokens excede %d; truncando a cauda", (int) tokens.size(), max_prompt);
        tokens.resize(max_prompt);
    }
    s->n_prompt_tokens = static_cast<int32_t>(tokens.size());

    // ── Prefill (ingestão do prompt) ────────────────────────────────────────
    llama_pos pos = 0;
    for (int i = 0; i < (int) tokens.size(); i += s->n_batch) {
        // O prefill também precisa ser interrompível: com prompts longos (questões do
        // ENEM) em aparelhos lentos, só a ingestão pode passar do limite de tempo, e o
        // timeout só faria efeito depois — atrasando o corte em vários segundos.
        if (s->cancel_requested.load(std::memory_order_relaxed)) {
            LOGw("cancelado durante o prefill, no offset %d de %d", i, (int) tokens.size());
            s->was_cancelled = true;
            s->total_ms      = now_ms() - t_start;
            return env->NewStringUTF("");
        }

        const int chunk = std::min((int) tokens.size() - i, s->n_batch);
        common_batch_clear(s->batch);
        for (int j = 0; j < chunk; j++) {
            const bool want_logits = (i + j == (int) tokens.size() - 1);
            common_batch_add(s->batch, tokens[i + j], pos++, {0}, want_logits);
        }
        if (llama_decode(s->ctx, s->batch) != 0) {
            set_error("llama_decode falhou no prefill (offset " + std::to_string(i) + ")");
            return nullptr;
        }
    }

    // ── Decode (geração) ────────────────────────────────────────────────────
    common_params_sampling sparams;
    sparams.temp  = temperature;
    sparams.top_k = top_k;
    sparams.top_p = top_p;
    sparams.seed  = (seed == 0) ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed);

    common_sampler * sampler = common_sampler_init(s->model, sparams);
    if (sampler == nullptr) {
        set_error("common_sampler_init falhou");
        return nullptr;
    }

    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    std::string         out;
    bool                failed         = false;
    bool                stopped_at_eog = false;
    bool                cancelled      = false;

    for (int i = 0; i < n_predict; i++) {
        // Checagem por token: é o grão mais fino possível sem interromper um decode
        // no meio. No pior caso o cancelamento custa o tempo de um token.
        if (s->cancel_requested.load(std::memory_order_relaxed)) {
            LOGw("geração cancelada após %d tokens", i);
            cancelled = true;
            break;
        }

        const llama_token tok = common_sampler_sample(sampler, s->ctx, -1);
        common_sampler_accept(sampler, tok, true);

        if (i == 0) {
            s->ttft_ms = now_ms() - t_start;
        }
        if (llama_vocab_is_eog(vocab, tok)) {
            stopped_at_eog = true;
            break;
        }

        out += common_token_to_piece(s->ctx, tok);
        s->n_gen_tokens++;

        if (pos >= s->n_ctx - kContextHeadroom) {
            LOGw("contexto cheio em %d tokens; encerrando a geração", pos);
            break;
        }

        common_batch_clear(s->batch);
        common_batch_add(s->batch, tok, pos++, {0}, true);
        if (llama_decode(s->ctx, s->batch) != 0) {
            set_error("llama_decode falhou na geração (token " + std::to_string(i) + ")");
            failed = true;
            break;
        }
    }

    common_sampler_free(sampler);

    // Fecha a janela do último decode antes de ler os contadores; sem isto o tempo do
    // token final ficaria de fora (e vazaria para a próxima chamada).
    llama_synchronize(s->ctx);

    if (failed && out.empty()) {
        return nullptr;
    }

    // Timings do próprio llama.cpp: prefill (t_p_eval) e decode (t_eval) separados —
    // a mesma semântica que o llama-server reporta, o que torna device e servidor comparáveis.
    const llama_perf_context_data perf = llama_perf_context(s->ctx);
    s->prefill_ms = perf.t_p_eval_ms;
    s->decode_ms  = perf.t_eval_ms;
    s->total_ms   = now_ms() - t_start;
    if (perf.n_p_eval > 0) { s->n_prompt_tokens = perf.n_p_eval; }
    if (perf.n_eval   > 0) { s->n_gen_tokens    = perf.n_eval;   }

    // Separa raciocínio de resposta. Sem isto o "Thinking Process:" do Gemma 4 chega ao
    // aluno como se fosse a resposta, e os tokens de pensamento entram em H3 misturados
    // aos de resposta — tornando tokens/s incomparável com um modelo que não raciocina.
    std::string answer = trim_to_valid_utf8(out);
    if (fmt.has_template) {
        try {
            common_chat_parser_params pparams(fmt.chat_params);
            pparams.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
            pparams.parse_tool_calls = false;

            // O construtor de common_chat_parser_params copia só `format` e
            // `generation_prompt` — **não** o parser PEG. Sem carregá-lo, formatos
            // "peg-*" (como o peg-gemma4 do Gemma 4) caem num caminho genérico que
            // devolve tudo como conteúdo e nunca extrai o raciocínio.
            if (!fmt.chat_params.parser.empty()) {
                pparams.parser = common_peg_arena::from_json(
                    nlohmann::json::parse(fmt.chat_params.parser));
            }

            // is_partial quando a geração parou no teto de tokens em vez de no EOG: sem
            // isso, uma geração cortada no meio do pensamento não fecha a regra do PEG,
            // o raciocínio não é extraído e o "Thinking Process" vaza como resposta.
            // Truncamento vai acontecer na coleta, então tem que ser tratado, não evitado.
            const bool is_partial = !stopped_at_eog;

            // A gramática PEG do modelo começa pelo generation prompt (para o Gemma 4,
            // o literal "<|turn>model\n"), que o modelo não regenera na saída. Sem
            // recolocá-lo, a regra não casa, o raciocínio não é extraído e o parser
            // ainda devolve o prompt colado no início do conteúdo.
            const std::string & gen_prompt = fmt.chat_params.generation_prompt;
            const common_chat_msg parsed =
                common_chat_parse(gen_prompt + answer, is_partial, pparams);

            // Cinto e suspensório: se o prompt sobreviver no conteúdo, tira daqui.
            std::string content = parsed.content;
            if (!gen_prompt.empty() && content.rfind(gen_prompt, 0) == 0) {
                content.erase(0, gen_prompt.size());
            }

            LOGi("parse: is_partial=%d reasoning=%zu chars content=%zu chars content='%.60s'",
                 (int) is_partial, parsed.reasoning_content.size(), content.size(), content.c_str());

            if (!parsed.reasoning_content.empty()) {
                s->last_reasoning     = parsed.reasoning_content;
                s->n_reasoning_tokens =
                    (int32_t) common_tokenize(s->ctx, parsed.reasoning_content, false, false).size();
            }

            // Só troca pela resposta quando o parser separou raciocínio E achou resposta.
            // Sem essa guarda, uma geração cortada no meio do pensamento devolveria
            // conteúdo vazio ou o próprio prompt como "resposta" — regressão vista no
            // Device 1. Truncamento fica visível via `stoppedAtEog`, não escondido.
            if (!parsed.reasoning_content.empty() && !content.empty()) {
                answer = content;
            }
        } catch (const std::exception & e) {
            LOGw("common_chat_parse falhou (%s); devolvendo o texto cru", e.what());
        }
    }

    s->stopped_at_eog = stopped_at_eog;
    s->was_cancelled  = cancelled;
    return env->NewStringUTF(answer.c_str());
}

/**
 * Métricas da última geração. Layout (espelhado em LlamaStats.fromArray):
 *  [0] ttftMs  [1] prefillMs  [2] decodeMs  [3] totalMs  [4] promptTokens
 *  [5] generatedTokens  [6] reasoningTokens  [7] supportsThinking (1/0)
 *  [8] stoppedAtEog (1/0 — 0 significa resposta truncada no teto de tokens)
 *  [9] cancelled (1/0 — geração interrompida por timeout)
 * -1 = indisponível (mesma convenção do resto do projeto).
 */
JNIEXPORT jdoubleArray JNICALL
Java_com_voiceassistant_llama_LlamaBridge_nativeLastStats(JNIEnv * env, jobject /*thiz*/, jlong handle) {
    constexpr int kStatsLen = 10;
    jdoubleArray result = env->NewDoubleArray(kStatsLen);
    if (result == nullptr) {
        return nullptr;
    }
    auto * s = as_session(handle);
    double values[kStatsLen] = { -1, -1, -1, -1, -1, -1, -1, -1, -1, -1 };
    if (s != nullptr) {
        values[0] = s->ttft_ms;
        values[1] = s->prefill_ms;
        values[2] = s->decode_ms;
        values[3] = s->total_ms;
        values[4] = s->n_prompt_tokens;
        values[5] = s->n_gen_tokens;
        values[6] = s->n_reasoning_tokens;
        values[7] = s->supports_thinking ? 1 : 0;
        values[8] = s->stopped_at_eog ? 1 : 0;
        values[9] = s->was_cancelled ? 1 : 0;
    }
    env->SetDoubleArrayRegion(result, 0, kStatsLen, values);
    return result;
}

/**
 * Pede o cancelamento da geração em curso. Chamável de qualquer thread — é o watchdog
 * de timeout que chama, enquanto a thread de inferência está bloqueada no laço.
 * Não bloqueia: apenas sinaliza; o laço encerra no próximo token.
 */
JNIEXPORT void JNICALL
Java_com_voiceassistant_llama_LlamaBridge_nativeRequestCancel(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * s = as_session(handle);
    if (s != nullptr) {
        s->cancel_requested.store(true, std::memory_order_relaxed);
    }
}

/** Texto do canal de raciocínio da última geração ("" se não houve). */
JNIEXPORT jstring JNICALL
Java_com_voiceassistant_llama_LlamaBridge_nativeLastReasoning(JNIEnv * env, jobject /*thiz*/, jlong handle) {
    auto * s = as_session(handle);
    return env->NewStringUTF(s != nullptr ? s->last_reasoning.c_str() : "");
}

} // extern "C"
