<#
  Récepteur SRT de test pour TurboIRL.
  - écoute le SRT du téléphone (mode listener)
  - enregistre le flux brut dans dumps\dump-<date>.ts (pour analyse)
  - le renvoie en local à OBS (réencodé en cadence constante, sans trou) : udp://127.0.0.1:9001 (mpegts)
  Relance ffmpeg automatiquement quand le téléphone se déconnecte. Ctrl+C pour arrêter.

  Usage :  powershell -ExecutionPolicy Bypass -File tools\recv.ps1 [-Port 9000] [-LatencyMs 2000]
#>
param(
    [int]$Port = 9000,
    [int]$LatencyMs = 12000,
    [int]$ObsPort = 9001
)

# Tout ce qui s'affiche est aussi ecrit dans dumps/recv-<date>.log (pour Claude)
Start-Transcript -Path (Join-Path $PSScriptRoot "..\dumps\recv-$(Get-Date -Format yyyyMMdd-HHmmss).log") -Append | Out-Null

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "obs-pipeline.ps1")
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

while ($true) {
    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $dump = Join-Path $dumps "dump-$stamp.ts"
    Write-Host "`n[$(Get-Date -Format HH:mm:ss)] Attente du téléphone... (dump : $dump)"
    # Vers OBS : réencodage en cadence constante (pipeline partagé avec replay.ps1, voir obs-pipeline.ps1).
    # Le dump reste une copie brute du flux reçu.
    & ffmpeg -hide_banner -loglevel warning -stats -stats_period 5 `
        @InputArgs -i $src `
        @(ObsOutputArgs $ObsPort) `
        -map 0 -c copy -max_interleave_delta 200000 -f mpegts $dump
    if ((Test-Path $dump) -and (Get-Item $dump).Length -lt 100000) { Remove-Item $dump }  # connexion sans flux
    Write-Host "[$(Get-Date -Format HH:mm:ss)] Téléphone déconnecté, redémarrage dans 1 s"
    Start-Sleep 1
}
