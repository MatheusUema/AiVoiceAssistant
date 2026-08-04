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
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

#include "common.h"
#include "sampling.h"
#include "chat.h"
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

// Aplica o chat template do GGUF (se houver) a uma única mensagem de usuário.
//
// Jinja primeiro, de propósito: renderiza o template embutido no próprio GGUF, que é o
// que o `llama-server` também faz por padrão. Sem isso, device e servidor formatariam o
// mesmo prompt de formas diferentes e a comparação entre os tiers perderia o sentido.
// O caminho legado (lista de templates conhecidos, compilada) só existe como rede de
// segurança — modelos novos como o Gemma 4 podem não estar nessa lista.
std::string format_prompt(llama_session * s, const std::string & prompt, bool * out_has_template) {
    const bool has_template = s->templates && common_chat_templates_was_explicit(s->templates.get());
    *out_has_template = has_template;
    if (!has_template) {
        return prompt;
    }

    common_chat_msg msg;
    msg.role    = "user";
    msg.content = prompt;
    const std::vector<common_chat_msg> no_history;

    for (const bool use_jinja : { true, false }) {
        try {
            return common_chat_format_single(s->templates.get(), no_history, msg,
                                             /* add_ass */ true, use_jinja);
        } catch (const std::exception & e) {
            LOGw("chat template (jinja=%d) falhou: %s", (int) use_jinja, e.what());
        }
    }

    LOGw("nenhum chat template aplicável; usando o prompt cru");
    *out_has_template = false;
    return prompt;
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
        jstring jpath, jint n_ctx, jint n_threads, jint n_batch) {
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

    LOGi("modelo pronto (n_ctx=%d, n_batch=%d, threads=%d)", n_ctx_eff, batch, threads);
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
        jint max_tokens, jfloat temperature, jint top_k, jfloat top_p, jint seed) {
    clear_error();

    auto * s = as_session(handle);
    if (s == nullptr || s->ctx == nullptr || s->model == nullptr) {
        set_error("sessão não carregada");
        return nullptr;
    }

    s->ttft_ms = s->prefill_ms = s->decode_ms = s->total_ms = kUnavailable;
    s->n_prompt_tokens = s->n_gen_tokens = 0;

    const double t_start = now_ms();

    llama_memory_clear(llama_get_memory(s->ctx), true);
    llama_perf_context_reset(s->ctx);

    bool              has_template = false;
    const std::string raw_prompt   = jstring_to_std(env, jprompt);
    const std::string formatted    = format_prompt(s, raw_prompt, &has_template);

    auto tokens = common_tokenize(s->ctx, formatted, has_template, has_template);
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
    bool                failed = false;

    for (int i = 0; i < n_predict; i++) {
        const llama_token tok = common_sampler_sample(sampler, s->ctx, -1);
        common_sampler_accept(sampler, tok, true);

        if (i == 0) {
            s->ttft_ms = now_ms() - t_start;
        }
        if (llama_vocab_is_eog(vocab, tok)) {
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

    const std::string safe = trim_to_valid_utf8(out);
    return env->NewStringUTF(safe.c_str());
}

/**
 * Métricas da última geração. Layout (espelhado em LlamaStats.fromArray):
 *  [0] ttftMs  [1] prefillMs  [2] decodeMs  [3] totalMs  [4] promptTokens  [5] generatedTokens
 * -1 = indisponível (mesma convenção do resto do projeto).
 */
JNIEXPORT jdoubleArray JNICALL
Java_com_voiceassistant_llama_LlamaBridge_nativeLastStats(JNIEnv * env, jobject /*thiz*/, jlong handle) {
    jdoubleArray result = env->NewDoubleArray(6);
    if (result == nullptr) {
        return nullptr;
    }
    auto * s = as_session(handle);
    double values[6] = { -1, -1, -1, -1, -1, -1 };
    if (s != nullptr) {
        values[0] = s->ttft_ms;
        values[1] = s->prefill_ms;
        values[2] = s->decode_ms;
        values[3] = s->total_ms;
        values[4] = s->n_prompt_tokens;
        values[5] = s->n_gen_tokens;
    }
    env->SetDoubleArrayRegion(result, 0, 6, values);
    return result;
}

} // extern "C"
