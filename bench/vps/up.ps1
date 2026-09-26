<#
  Construit et lance la réplique locale du VPS (MediaMTX + bond) pour le banc.
    powershell -ExecutionPolicy Bypass -File bench\vps\up.ps1          # (re)construit et lance
    powershell -ExecutionPolicy Bypass -File bench\vps\up.ps1 -Down    # arrête
  Le bond est copié depuis ..\turboirl-api\bond\bond.py à chaque construction : toujours le code du VPS.
  Ports sur le PC : 8890/udp (relais SRT), 8891/udp (bond), 9997/tcp (API MediaMTX).
#>
param([switch]$Down, [string]$Name = "turboirl-vps")
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$api = Join-Path (Split-Path (Split-Path $here -Parent) -Parent) "..\turboirl-api\bond\bond.py"
if ($Down) { docker rm -f $Name 2>$null | Out-Null; Write-Host "arrêté"; exit 0 }
Copy-Item (Resolve-Path $api) (Join-Path $here "bond.py") -Force
$env = @{}
Get-Content (Join-Path $HOME ".turboirl-vps.env") | ForEach-Object { if ($_ -match "^([A-Z_]+)=(.*)$") { $env[$matches[1]] = $matches[2] } }
docker build -q -t turboirl-vps $here | Out-Null
docker rm -f $Name 2>$null | Out-Null
docker run -d --name $Name --sysctl net.core.rmem_max=16777216 --sysctl net.core.rmem_default=4194304 `
    -e WRITE_TOKEN=$($env["WRITE_TOKEN"]) -e READ_TOKEN=$($env["READ_TOKEN"]) `
    -p 8890:8890/udp -p 8891:8891/udp -p 127.0.0.1:9997:9997/tcp turboirl-vps | Out-Null
Start-Sleep -Seconds 2
docker logs $Name 2>&1 | Select-Object -Last 3
