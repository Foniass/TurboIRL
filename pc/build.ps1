<#
  Construit le logiciel PC : pc\dist\TurboIRL\TurboIRL.exe (PyInstaller, un dossier) + ffmpeg.exe à côté,
  puis l'archive dist\TurboIRL-PC-<version>.zip (dossier TurboIRL\ à la racine, attendu par la mise à jour auto).
  La version est lue dans pc\VERSION. Nécessite : python 3 avec tkinter, pip install pyinstaller, ffmpeg dans le PATH.

  Usage : powershell -ExecutionPolicy Bypass -File pc\build.ps1
#>
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$version = (Get-Content (Join-Path $PSScriptRoot "VERSION") -Raw).Trim()
$ffmpeg = (Get-Command ffmpeg).Source
Write-Host "TurboIRL PC $version (ffmpeg : $ffmpeg)"

Push-Location $root
try {
    Remove-Item -Recurse -Force (Join-Path $PSScriptRoot "dist"), (Join-Path $PSScriptRoot "build") -ErrorAction SilentlyContinue
    & pyinstaller --noconfirm --clean --windowed --name TurboIRL `
        --paths (Join-Path $root "tools") `
        --add-data "$(Join-Path $PSScriptRoot 'VERSION');." `
        --distpath (Join-Path $PSScriptRoot "dist") --workpath (Join-Path $PSScriptRoot "build") --specpath (Join-Path $PSScriptRoot "build") `
        (Join-Path $PSScriptRoot "turboirl_pc.py")
    if ($LASTEXITCODE -ne 0) { throw "pyinstaller a échoué ($LASTEXITCODE)" }
    $out = Join-Path $PSScriptRoot "dist\TurboIRL"
    Copy-Item $ffmpeg (Join-Path $out "ffmpeg.exe")
    $zip = Join-Path $root "dist\TurboIRL-PC-$version.zip"
    Remove-Item $zip -ErrorAction SilentlyContinue
    Compress-Archive -Path $out -DestinationPath $zip -CompressionLevel Optimal
    Write-Host "OK : $zip ($([math]::Round((Get-Item $zip).Length / 1MB)) Mo)"
} finally {
    Pop-Location
}
