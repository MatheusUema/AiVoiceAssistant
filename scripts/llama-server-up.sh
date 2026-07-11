#!/usr/bin/env bash
#
# Sobe o llama-server (llama.cpp) para o tier servidor do app voiceassistant.
# Expõe /health e /completion; logprobs vêm por requisição via n_probs (o app envia).
#
# Uso:
#   MODEL=/caminho/modelo.gguf \
#   LLAMA_SERVER_BIN=/caminho/llama.cpp/build/bin/llama-server \
#   scripts/llama-server-up.sh
#
# Variáveis (com defaults):
#   MODEL             (obrigatório) caminho do .gguf
#   LLAMA_SERVER_BIN  binário llama-server (default: procura no PATH)
#   HOST              default 0.0.0.0 (aceita conexões da LAN)
#   PORT              default 8080
#   CTX               tamanho do contexto (default 2048)
#   THREADS           threads (default: nproc/2)
#
set -euo pipefail

MODEL="${MODEL:-}"
LLAMA_SERVER_BIN="${LLAMA_SERVER_BIN:-llama-server}"
HOST="${HOST:-0.0.0.0}"
PORT="${PORT:-8080}"
CTX="${CTX:-2048}"
THREADS="${THREADS:-}"

if [[ -z "$MODEL" ]]; then
  echo "ERRO: defina MODEL=/caminho/modelo.gguf" >&2
  exit 1
fi
if [[ ! -f "$MODEL" ]]; then
  echo "ERRO: modelo não encontrado: $MODEL" >&2
  exit 1
fi
if ! command -v "$LLAMA_SERVER_BIN" >/dev/null 2>&1 && [[ ! -x "$LLAMA_SERVER_BIN" ]]; then
  echo "ERRO: llama-server não encontrado (LLAMA_SERVER_BIN=$LLAMA_SERVER_BIN)." >&2
  echo "      Compile o llama.cpp ou aponte LLAMA_SERVER_BIN para o binário." >&2
  exit 1
fi

# Threads: metade dos núcleos por padrão (bom equilíbrio p/ latência).
if [[ -z "$THREADS" ]]; then
  if command -v nproc >/dev/null 2>&1; then
    THREADS=$(( $(nproc) / 2 ))
    [[ "$THREADS" -lt 1 ]] && THREADS=1
  else
    THREADS=4
  fi
fi

echo "== llama-server =="
echo "  modelo : $MODEL"
echo "  host   : $HOST"
echo "  porta  : $PORT"
echo "  ctx    : $CTX"
echo "  threads: $THREADS"
echo "  (logprobs: pedidos por requisição via n_probs; o app já envia)"
echo
echo "IP(s) LAN desta máquina (use http://<IP>:$PORT no app):"
( ip -4 addr show 2>/dev/null | grep -oE 'inet (192|10|172)\.[0-9.]+' | awk '{print "  " $2}' ) \
  || ( ifconfig 2>/dev/null | grep -oE 'inet (192|10|172)\.[0-9.]+' | awk '{print "  " $2}' ) \
  || echo "  (descubra com: ip addr / ifconfig)"
echo
echo "Lembre de liberar a porta $PORT/tcp no firewall."
echo

exec "$LLAMA_SERVER_BIN" \
  -m "$MODEL" \
  --host "$HOST" \
  --port "$PORT" \
  -c "$CTX" \
  -t "$THREADS"
