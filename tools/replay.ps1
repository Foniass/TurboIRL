<#
  Rejoue un dump brut (dumps\dump-*.ts) vers OBS en temps réel, avec exactement le pipeline de recv.ps1.
  Sert à reproduire sur le PC ce que le viewer a vu, et à valider une correction du récepteur sans sortie terrain.

  replay.py renvoie le dump en UDP local à la cadence des PTS audio (fidèle au direct, y compris pendant les
  trous vidéo — « ffmpeg -re » s'endormirait sur les sauts vidéo), et ffmpeg le réencode vers OBS comme en direct.
  Nécessite python (3.x) dans le PATH.

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

# ffmpeg d'abord (il doit écouter avant que les paquets partent), fin automatique 5 s après le dernier paquet
$src = "udp://127.0.0.1:${LoopPort}?timeout=5000000&fifo_size=100000"
$ff = Start-Process ffmpeg -NoNewWindow -PassThru -ArgumentList (
    @("-hide_banner", "-loglevel", "warning", "-stats", "-stats_period", "5") + $InputArgs + @("-i", $src) + (ObsOutputArgs $ObsPort)
)
Start-Sleep -Milliseconds 800

& python (Join-Path $PSScriptRoot "replay.py") $Dump --start $StartSec --duration $DurationSec --port $LoopPort
$ff.WaitForExit()
