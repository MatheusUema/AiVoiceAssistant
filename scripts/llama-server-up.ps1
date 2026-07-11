<#
.SYNOPSIS
  Sobe o llama-server (llama.cpp) para o tier servidor do app voiceassistant (Windows).
  Expõe /health e /completion; logprobs vêm por requisição via n_probs (o app envia).

.EXAMPLE
  $env:MODEL="C:\models\gemma-3-1b-it-Q4_K_M.gguf"
  $env:LLAMA_SERVER_BIN="C:\llama.cpp\build\bin\Release\llama-server.exe"
  scripts\llama-server-up.ps1

.NOTES
  Variáveis de ambiente (com defaults):
    MODEL             (obrigatório) caminho do .gguf
    LLAMA_SERVER_BIN  binário llama-server.exe (default: procura no PATH)
    HOST              default 0.0.0.0 (aceita conexões da LAN)
    PORT              default 8080
    CTX               contexto (default 2048)
    THREADS           threads (default: núcleos/2)
#>
$ErrorActionPreference = "Stop"

$Model  = $env:MODEL
$Bin    = if ($env:LLAMA_SERVER_BIN) { $env:LLAMA_SERVER_BIN } else { "llama-server" }
$HostIp = if ($env:HOST) { $env:HOST } else { "0.0.0.0" }
$Port   = if ($env:PORT) { $env:PORT } else { "8080" }
$Ctx    = if ($env:CTX)  { $env:CTX }  else { "2048" }

if (-not $Model) { Write-Error "Defina `$env:MODEL com o caminho do .gguf"; exit 1 }
if (-not (Test-Path $Model)) { Write-Error "Modelo não encontrado: $Model"; exit 1 }

$Threads = $env:THREADS
if (-not $Threads) {
  $cores = (Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors
  $Threads = [Math]::Max(1, [Math]::Floor($cores / 2))
}

Write-Host "== llama-server =="
Write-Host "  modelo : $Model"
Write-Host "  host   : $HostIp"
Write-Host "  porta  : $Port"
Write-Host "  ctx    : $Ctx"
Write-Host "  threads: $Threads"
Write-Host "  (logprobs: pedidos por requisicao via n_probs; o app ja envia)"
Write-Host ""
Write-Host "IP(s) LAN desta maquina (use http://<IP>:$Port no app):"
Get-NetIPAddress -AddressFamily IPv4 |
  Where-Object { $_.IPAddress -match '^(192|10|172)\.' } |
  ForEach-Object { Write-Host "  $($_.IPAddress)" }
Write-Host ""
Write-Host "Libere a porta $Port/tcp no firewall (Windows Defender Firewall)."
Write-Host ""

& $Bin -m $Model --host $HostIp --port $Port -c $Ctx -t $Threads
