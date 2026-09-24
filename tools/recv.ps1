<#
  Récepteur TurboIRL (PC avec OBS).
  - lit le flux du téléphone sur le relais SRT du VPS (MediaMTX) avec le jeton de lecture de ~/.turboirl-vps.env ;
    aucune redirection de port sur la box. -Direct : écoute le téléphone directement (ancien mode, port UDP ouvert)
  - enregistre le flux brut dans dumps\dump-<date>.ts (pour analyse)
  - le renvoie en local à OBS en cadence constante, sans jamais de trou : udp://127.0.0.1:9001 (mpegts)
    (tools\receiver.py : décodeur ffmpeg -> répéteur -> encodeur ffmpeg ; nécessite python 3)
  Le décodeur est relancé à chaque reconnexion du téléphone ; OBS garde la dernière image et du silence pendant
  ce temps. Ctrl+C pour arrêter.

  Usage :  powershell -ExecutionPolicy Bypass -File tools\recv.ps1 [-Direct] [-Port 9000] [-LatencyMs 12000]
#>
param(
    [switch]$Direct,
    [int]$Port = 9000,
    [int]$LatencyMs = 12000,
    [int]$ObsPort = 9001
)

# Tout ce qui s'affiche est aussi ecrit dans dumps/recv-<date>.log (pour Claude)
Start-Transcript -Path (Join-Path $PSScriptRoot "..\dumps\recv-$(Get-Date -Format yyyyMMdd-HHmmss).log") -Append | Out-Null

$ErrorActionPreference = "Stop"
$dumps = Join-Path $PSScriptRoot "..\dumps"
New-Item -ItemType Directory -Force $dumps | Out-Null
$env:PYTHONIOENCODING = "utf-8"
Write-Host "OBS : source multimédia -> udp://127.0.0.1:$ObsPort (format mpegts)"

if ($Direct) {
    # Règle pare-feu (une seule fois, demande l'élévation)
    $rule = Get-NetFirewallRule -DisplayName "TurboIRL SRT" -ErrorAction SilentlyContinue
    if (-not $rule) {
        Write-Host "Création de la règle pare-feu UDP $Port (fenêtre admin)..."
        Start-Process powershell -Verb RunAs -Wait -ArgumentList "-Command", "New-NetFirewallRule -DisplayName 'TurboIRL SRT' -Direction Inbound -Protocol UDP -LocalPort $Port -Action Allow | Out-Null"
    }
    $src = "srt://0.0.0.0:${Port}?mode=listener&latency=$($LatencyMs * 1000)"
    Write-Host "En écoute directe sur $src"
    & python (Join-Path $PSScriptRoot "receiver.py") --source $src --dump-dir $dumps --obs-port $ObsPort
} else {
    & python (Join-Path $PSScriptRoot "receiver.py") --dump-dir $dumps --obs-port $ObsPort
}
