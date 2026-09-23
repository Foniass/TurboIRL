<#
  Récepteur SRT de test pour TurboIRL.
  - écoute le SRT du téléphone (mode listener)
  - enregistre le flux brut dans dumps\dump-<date>.ts (pour analyse)
  - le renvoie en local à OBS en cadence constante, sans jamais de trou : udp://127.0.0.1:9001 (mpegts)
    (tools\receiver.py : décodeur ffmpeg -> répéteur -> encodeur ffmpeg ; nécessite python 3)
  Le décodeur est relancé à chaque reconnexion du téléphone ; OBS garde la dernière image et du silence pendant
  ce temps. Ctrl+C pour arrêter.

  Usage :  powershell -ExecutionPolicy Bypass -File tools\recv.ps1 [-Port 9000] [-LatencyMs 12000]
#>
param(
    [int]$Port = 9000,
    [int]$LatencyMs = 12000,
    [int]$ObsPort = 9001
)

# Tout ce qui s'affiche est aussi ecrit dans dumps/recv-<date>.log (pour Claude)
Start-Transcript -Path (Join-Path $PSScriptRoot "..\dumps\recv-$(Get-Date -Format yyyyMMdd-HHmmss).log") -Append | Out-Null

$ErrorActionPreference = "Stop"
$dumps = Join-Path $PSScriptRoot "..\dumps"
New-Item -ItemType Directory -Force $dumps | Out-Null

# Règle pare-feu (une seule fois, demande l'élévation)
$rule = Get-NetFirewallRule -DisplayName "TurboIRL SRT" -ErrorAction SilentlyContinue
if (-not $rule) {
    Write-Host "Création de la règle pare-feu UDP $Port (fenêtre admin)..."
    Start-Process powershell -Verb RunAs -Wait -ArgumentList "-Command", "New-NetFirewallRule -DisplayName 'TurboIRL SRT' -Direction Inbound -Protocol UDP -LocalPort $Port -Action Allow | Out-Null"
}

$src = "srt://0.0.0.0:${Port}?mode=listener&latency=$($LatencyMs * 1000)"
Write-Host "En écoute sur $src"
Write-Host "OBS : source multimédia -> udp://127.0.0.1:$ObsPort (format mpegts)"

$env:PYTHONIOENCODING = "utf-8"
& python (Join-Path $PSScriptRoot "receiver.py") --source $src --dump-dir $dumps --obs-port $ObsPort
