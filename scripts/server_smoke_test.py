#!/usr/bin/env python3
"""
Teste de fumaça do tier servidor (llama.cpp llama-server) — sem depender do app.

Valida:
  1. GET  /health      -> {"status": "ok"}
  2. POST /completion  (com n_probs) -> texto + completion_probabilities
  3. Detecta a VARIANTE do schema de completion_probabilities (ponto crítico da Fase 5)
  4. Calcula a confiança pelo MESMO método do app (média da prob do token top-1)

Modos:
  padrão        health + uma completion + confiança + schema
  --raw         imprime o JSON cru da resposta (para inspeção do schema)
  --calibrate F  roda prompts de um arquivo (1 por linha, prefixo opcional easy:/hard:)
                 e sugere thresholds de confiança por percentis

Uso:
  python scripts/server_smoke_test.py --url http://192.168.1.100:8080
  python scripts/server_smoke_test.py --url http://... --raw
  python scripts/server_smoke_test.py --url http://... --calibrate prompts.txt

Apenas biblioteca padrão (urllib, json). Compatível com Python 3.7+.
"""
import argparse
import json
import math
import statistics
import sys
import urllib.error
import urllib.request

DEFAULT_PROMPT = "O que é fotossíntese?"


def http_get_json(url, timeout):
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def http_post_json(url, payload, timeout):
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, method="POST",
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def completion(base_url, prompt, n_predict, n_probs, timeout):
    """Requisição idêntica à do app (CompletionRequest)."""
    payload = {
        "prompt": prompt,
        "n_predict": n_predict,
        "temperature": 0.7,
        "top_p": 0.9,
        "top_k": 40,
        "n_probs": n_probs,
    }
    return http_post_json(base_url.rstrip("/") + "/completion", payload, timeout)


def detect_and_extract(cp):
    """
    Retorna (top1_probs, variante_str).

    Variante A (antiga, esperada pelos DTOs do app):
        [{"content": "A", "probs": [{"tok_str": "A", "prob": 0.92}, ...]}, ...]
    Variante B (nova):
        [{"id":.., "token":"A", "logprob": -0.08, "top_logprobs":[...]}, ...]
    """
    if not cp:
        return [], "vazio/ausente"
    first = cp[0]
    if isinstance(first, dict) and "probs" in first:
        probs = []
        for tp in cp:
            entry = (tp.get("probs") or [])
            if entry:
                p = entry[0].get("prob")
                if p is not None:
                    probs.append(float(p))
        return probs, "probs/tok_str (compativel com o app)"
    if isinstance(first, dict) and ("logprob" in first or "top_logprobs" in first):
        probs = []
        for tp in cp:
            lp = tp.get("logprob")
            if lp is not None:
                probs.append(math.exp(float(lp)))
        return probs, "logprob/top_logprobs (NOVO - app retorna -1; DTO precisa de update)"
    return [], "desconhecida"


def mean_confidence(top1_probs):
    """Método do app: média das probs do token top-1 (-1 se indisponível)."""
    if not top1_probs:
        return -1.0
    return sum(top1_probs) / len(top1_probs)


def run_health(base_url, timeout):
    url = base_url.rstrip("/") + "/health"
    try:
        body = http_get_json(url, timeout)
        status = body.get("status", "")
        ok = str(status).lower() == "ok"
        print(f"[health]     GET /health -> status={status!r} {'OK' if ok else 'INESPERADO'}")
        return ok
    except Exception as e:
        print(f"[health]     GET /health FALHOU: {e}")
        return False


def run_once(base_url, prompt, n_predict, n_probs, timeout, raw=False):
    resp = completion(base_url, prompt, n_predict, n_probs, timeout)
    if raw:
        print(json.dumps(resp, ensure_ascii=False, indent=2))
        print("-" * 60)
    tokens = resp.get("tokens_predicted", 0)
    content = resp.get("content", "")
    cp = resp.get("completion_probabilities")
    top1, variant = detect_and_extract(cp)
    conf = mean_confidence(top1)
    return content, tokens, conf, variant, len(top1)


def cmd_default(args):
    base = args.url
    print(f"Servidor: {base}")
    health_ok = run_health(base, args.timeout)
    try:
        content, tokens, conf, variant, n = run_once(
            base, args.prompt, args.n_predict, args.n_probs, args.timeout, raw=args.raw
        )
    except urllib.error.URLError as e:
        print(f"[completion] FALHOU: {e}")
        return 1
    print(f"[completion] POST /completion (n_probs={args.n_probs}) -> {tokens} tokens")
    print(f"[schema]     variante detectada: {variant}  ({n} tokens com prob)")
    if conf < 0:
        print("[confidence] INDISPONIVEL (-1). Veja a secao 5 do docs/05-tier-servidor-setup.md")
    else:
        print(f"[confidence] media das probs top-1 = {conf:.3f}  (metodo do app: calculateConfidence)")
    preview = content.strip().replace("\n", " ")
    print(f"[resposta]   {preview[:120]}{'...' if len(preview) > 120 else ''}")
    return 0 if health_ok else 2


def parse_calibration_file(path):
    """Linhas: 'easy: <prompt>' | 'hard: <prompt>' | '<prompt>' (sem grupo)."""
    items = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            group = "sem_grupo"
            text = line
            for g in ("easy", "hard"):
                if line.lower().startswith(g + ":"):
                    group = g
                    text = line[len(g) + 1:].strip()
                    break
            items.append((group, text))
    return items


def percentile(values, pct):
    if not values:
        return None
    s = sorted(values)
    k = (len(s) - 1) * (pct / 100.0)
    lo = math.floor(k)
    hi = math.ceil(k)
    if lo == hi:
        return s[int(k)]
    return s[lo] * (hi - k) + s[hi] * (k - lo)


def cmd_calibrate(args):
    base = args.url
    print(f"Servidor: {base}")
    if not run_health(base, args.timeout):
        print("Abortando calibracao: servidor nao saudavel.")
        return 2
    items = parse_calibration_file(args.calibrate)
    if not items:
        print("Arquivo de calibracao vazio.")
        return 1

    by_group = {}
    print("\n%-6s %-8s  %s" % ("grupo", "conf", "prompt"))
    print("-" * 60)
    for group, prompt in items:
        try:
            _, _, conf, variant, _ = run_once(
                base, prompt, args.n_predict, args.n_probs, args.timeout
            )
        except Exception as e:
            print(f"{group:<6} ERRO      {prompt[:40]} ({e})")
            continue
        if conf < 0:
            print(f"{group:<6} -1        {prompt[:40]}  [{variant}]")
            continue
        by_group.setdefault(group, []).append(conf)
        print(f"{group:<6} {conf:<8.3f}  {prompt[:40]}")

    print("\n== Estatisticas por grupo ==")
    for group, vals in by_group.items():
        if not vals:
            continue
        med = statistics.median(vals)
        print(f"  {group:<8} n={len(vals):<3} min={min(vals):.3f} "
              f"mediana={med:.3f} max={max(vals):.3f}")

    easy = by_group.get("easy", [])
    hard = by_group.get("hard", [])
    if easy and hard:
        high = percentile(easy, 25)   # maioria dos faceis fica acima
        low = percentile(hard, 75)    # maioria dos dificeis fica abaixo
        print("\n== Sugestao de thresholds (heuristica de percentis) ==")
        print(f"  confidenceThresholdHigh ~= {high:.2f}  (p25 dos 'easy')")
        print(f"  confidenceThresholdLow  ~= {low:.2f}  (p75 dos 'hard')")
        if low >= high:
            print("  AVISO: low >= high — os grupos se sobrepoem; o modelo talvez nao "
                  "separe bem facil/dificil por confianca (esperado com modelos pequenos).")
        print("  Ajuste em ServiceModule.provideServerConfig() (ServerConfig).")
    else:
        print("\n(Para sugestao de thresholds, rotule prompts com 'easy:' e 'hard:'.)")
    return 0


def main():
    ap = argparse.ArgumentParser(description="Smoke test do llama-server (tier servidor).")
    ap.add_argument("--url", required=True, help="ex.: http://192.168.1.100:8080")
    ap.add_argument("--prompt", default=DEFAULT_PROMPT)
    ap.add_argument("--n-predict", type=int, default=64)
    ap.add_argument("--n-probs", type=int, default=5)
    ap.add_argument("--timeout", type=float, default=30.0)
    ap.add_argument("--raw", action="store_true", help="imprime o JSON cru da completion")
    ap.add_argument("--calibrate", metavar="ARQUIVO",
                    help="arquivo de prompts (easy:/hard:) p/ calibrar thresholds")
    args = ap.parse_args()

    if args.calibrate:
        return cmd_calibrate(args)
    return cmd_default(args)


if __name__ == "__main__":
    sys.exit(main())
