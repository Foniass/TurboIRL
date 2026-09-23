<#
  Récepteur SRT de test pour TurboIRL.
  - écoute le SRT du téléphone (mode listener)
  - enregistre le flux brut dans dumps\dump-<date>.ts (pour analyse)
  - le renvoie en local à OBS en cadence constante, sans jamais de trou : udp://127.0.0.1:9001 (mpegts)
    (décodeur ffmpeg -> repeater.py -> encodeur ffmpeg, voir obs-pipeline.ps1 ; nécessite python 3)
  Relance le décodeur automatiquement quand le téléphone se déconnecte ; le répéteur et l'encodeur ne
  s'arrêtent jamais, OBS garde la dernière image et du silence pendant ce temps. Ctrl+C pour arrêter.

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

$repeater = Start-Repeater
Start-Sleep -Milliseconds 500
$encoder = Start-Encoder $ObsPort
try {
    while ($true) {
        if ($repeater.HasExited -or $encoder.HasExited) { throw "répéteur ou encodeur arrêté, relancer recv.ps1" }
        $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $dump = Join-Path $dumps "dump-$stamp.ts"
        Write-Host "`n[$(Get-Date -Format HH:mm:ss)] Attente du téléphone... (dump : $dump)"
        & ffmpeg -hide_banner -loglevel warning -stats -stats_period 5 `
            @InputArgs -i $src `
            @(DecoderOutputArgs) `
            -map 0 -c copy -max_interleave_delta 200000 -f mpegts $dump
        if ((Test-Path $dump) -and (Get-Item $dump).Length -lt 100000) { Remove-Item $dump }  # connexion sans flux
        Write-Host "[$(Get-Date -Format HH:mm:ss)] Téléphone déconnecté, redémarrage dans 1 s"
        Start-Sleep 1
    }
} finally {
    foreach ($p in @($encoder, $repeater)) { if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } }
}
