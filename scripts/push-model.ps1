<#
.SYNOPSIS
    Envia um GGUF para o diretório externo do app, de onde o llama.cpp faz mmap direto.

.DESCRIPTION
    O modelo do estudo (gemma-4-E2B-it-Q4_K_M.gguf) tem 3,43 GB. Empacotar isso no APK
    e ainda copiar para filesDir gastaria ~7 GB num aparelho de 64 GB. O destino aqui
    — /sdcard/Android/data/<pkg>/files/models/ — não exige permissão nenhuma, é lido
    direto pelo app (getExternalFilesDir) e some quando o app é desinstalado.

    O LocalModelManager procura o modelo nesta ordem:
      1. filesDir/<nome>
      2. externalFilesDir/models/<nome>   <-- é aqui que este script põe
      3. assets/models/<nome>             (copiado para filesDir)

.PARAMETER ModelPath
    Caminho local do .gguf.

.PARAMETER Package
    Application ID. Default: com.voiceassistant.debug (o build debug tem o sufixo .debug).

.PARAMETER Serial
    Serial do aparelho (adb -s). Necessário com mais de um device conectado.

.EXAMPLE
    .\scripts\push-model.ps1 -ModelPath C:\models\gemma-4-E2B-it-Q4_K_M.gguf

.EXAMPLE
    .\scripts\push-model.ps1 -ModelPath .\gemma-3-1b-it-Q4_K_M.gguf -Serial R58M12345 -Package com.voiceassistant
#>
param(
    [Parameter(Mandatory = $true)][string]$ModelPath,
    [string]$Package = "com.voiceassistant.debug",
    [string]$Serial
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $ModelPath)) {
    throw "Arquivo não encontrado: $ModelPath"
}
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw "adb não está no PATH. Instale o platform-tools do Android SDK."
}

$adbArgs = @()
if ($Serial) { $adbArgs += @("-s", $Serial) }

$file = Get-Item $ModelPath
$sizeGb = [math]::Round($file.Length / 1GB, 2)
$destDir = "/sdcard/Android/data/$Package/files/models"

Write-Output "Modelo : $($file.Name) ($sizeGb GB)"
Write-Output "Destino: $destDir"
Write-Output ""

# O app precisa ter rodado ao menos uma vez para o diretório existir; mkdir -p resolve.
& adb @adbArgs shell mkdir -p $destDir
if ($LASTEXITCODE -ne 0) { throw "Falha ao criar $destDir — o app $Package está instalado?" }

Write-Output "Enviando (leva alguns minutos em USB 2.0)..."
& adb @adbArgs push $file.FullName "$destDir/$($file.Name)"
if ($LASTEXITCODE -ne 0) { throw "adb push falhou" }

Write-Output ""
& adb @adbArgs shell ls -l $destDir

Write-Output ""
Write-Output "Pronto. Reinicie o app e acompanhe o carregamento com:"
Write-Output "  adb $($adbArgs -join ' ') logcat -s LocalModelManager LlamaCppLLM LlamaEngine LlamaBridge"
