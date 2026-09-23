<#
  Rejoue un dump brut (dumps\dump-*.ts) vers OBS en temps réel, avec exactement le pipeline de recv.ps1.
  Sert à reproduire sur le PC ce que le viewer a vu, et à valider une correction du récepteur sans sortie terrain.

  replay.py renvoie le dump en UDP local à la cadence des PTS audio (fidèle au direct, y compris pendant les
  trous vidéo — « ffmpeg -re » s'endormirait sur les sauts vidéo), puis décodeur -> repeater.py -> encodeur
  comme en direct. Nécessite python (3.x) dans le PATH.

  Usage :  powershell -ExecutionPolicy Bypass -File tools\replay.ps1 -Dump dumps\dump-20260923-181204.ts [-StartSec 440] [-DurationSec 60] [-ObsPort 9001]
#>
param(
    [Parameter(Mandatory = $true)][string]$Dump,
    [double]$StartSec = 0,
    [double]$DurationSec = 0,
    [int]$ObsPort = 9001,
    [int]$LoopPort = 9003
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "obs-pipeline.ps1")

if (-not (Test-Path $Dump)) { throw "Dump introuvable : $Dump" }
$Dump = (Resolve-Path $Dump).Path

Write-Host "Relecture de $Dump (début $StartSec s, durée $(if ($DurationSec -gt 0) { "$DurationSec s" } else { 'totale' })) -> udp://127.0.0.1:$ObsPort"

$repeater = Start-Repeater
Start-Sleep -Milliseconds 500
$encoder = Start-Encoder $ObsPort
# décodeur : lit l'UDP local, fin automatique 5 s après le dernier paquet
$src = "udp://127.0.0.1:${LoopPort}?timeout=5000000&fifo_size=100000"
$decoder = Start-Process ffmpeg -NoNewWindow -PassThru -ArgumentList (
    @("-hide_banner", "-loglevel", "warning", "-stats", "-stats_period", "5") + $InputArgs + @("-i", $src) + (DecoderOutputArgs)
)
Start-Sleep -Milliseconds 800
try {
    & python (Join-Path $PSScriptRoot "replay.py") $Dump --start $StartSec --duration $DurationSec --port $LoopPort
    $decoder.WaitForExit()
    Start-Sleep 2  # laisse l'encodeur écouler la fin
} finally {
    foreach ($p in @($decoder, $encoder, $repeater)) { if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } }
}
