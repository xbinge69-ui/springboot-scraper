# =============================================================================
# install-gpl-ffmpeg.ps1
# -----------------------------------------------------------------------------
# Downloads BtbN's GPL ffmpeg build (includes NVENC, QSV, AMF, libx265) and
# puts ffmpeg.exe + ffprobe.exe in C:\ffmpeg\, then prepends that to the
# user PATH. Run as Administrator if you want it to be system-wide; without
# elevation it just affects your user account (which is usually enough).
#
# Usage (PowerShell):
#   irm https://.../install-gpl-ffmpeg.ps1 | iex
# or just double-click the file.
# =============================================================================

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'SilentlyContinue'

# 1) Pick the latest win64 GPL build.
$release = Invoke-RestMethod 'https://api.github.com/repos/BtbN/FFmpeg-Builds/releases/latest'
$asset   = $release.assets | Where-Object { $_.name -match 'win64-gpl\.zip$' } | Select-Object -First 1
if (-not $asset) { throw 'Could not find a win64-gpl build in the latest BtbN release.' }

$zipUrl  = $asset.browser_download_url
$zipName = $asset.name
Write-Host "Downloading $zipName ..." -ForegroundColor Cyan

# 2) Download to a temp dir.
$tmp     = Join-Path $env:TEMP ("ffmpeg-install-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$zipPath = Join-Path $tmp $zipName
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
Invoke-WebRequest -Uri $zipUrl -OutFile $zipPath -UseBasicParsing

# 3) Extract just the bin/ contents into C:\ffmpeg.
$dest = 'C:\ffmpeg'
if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
New-Item -ItemType Directory -Force -Path $dest | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $tmp)

# BtbN's zip contains a single top-level dir like 'ffmpeg-N-118805-gcf9b6b379b-win64-gpl/'.
$inner = Get-ChildItem -Directory $tmp | Where-Object { $_.Name -like 'ffmpeg-*' } | Select-Object -First 1
if (-not $inner) { throw "Unexpected zip layout — no 'ffmpeg-*' dir under $tmp" }
$binSrc = Join-Path $inner.FullName 'bin'
if (-not (Test-Path $binSrc)) { throw "No 'bin' dir under $($inner.FullName)" }

# Move every .exe in bin\ to C:\ffmpeg (flat layout).
Get-ChildItem $binSrc -Filter '*.exe' | ForEach-Object {
    Move-Item $_.FullName $dest -Force
}

# 4) Add C:\ffmpeg to the FRONT of the user PATH (so this ffmpeg wins
#    over any older one already on PATH).
$currentUserPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$machinePath     = [Environment]::GetEnvironmentVariable('Path', 'Machine')
# Use the user-level PATH (no admin required).
if ($currentUserPath -notlike "*$dest*") {
    [Environment]::SetEnvironmentVariable('Path', "$dest;$currentUserPath", 'User')
    # Also update the current process so this very session sees the new PATH.
    $env:Path = "$dest;$env:Path"
    Write-Host "Added $dest to your user PATH." -ForegroundColor Green
} else {
    Write-Host "$dest is already on your PATH." -ForegroundColor DarkGreen
}

# 5) Verify.
$ff = Get-Command ffmpeg -ErrorAction SilentlyContinue
if (-not $ff) {
    Write-Host "ffmpeg not found on PATH yet. Restart your shell, then run 'ffmpeg -version'." -ForegroundColor Yellow
    exit 0
}
Write-Host ""
Write-Host "ffmpeg path:  $($ff.Path)" -ForegroundColor Cyan
Write-Host "ffmpeg version: $((& ffmpeg -version) | Select-Object -First 1)" -ForegroundColor Cyan
Write-Host ""
Write-Host "Checking for GPU encoders ..." -ForegroundColor Cyan
$encs = & ffmpeg -hide_banner -encoders 2>$null | Select-String -Pattern 'h264_(nvenc|qsv|amf)|hevc_(nvenc|qsv|amf)' -SimpleMatch
if ($encs) {
    $encs | ForEach-Object { Write-Host "  $_" -ForegroundColor Green }
    Write-Host ""
    Write-Host "DONE. Restart the Spring Boot app and re-probe the GPU panel." -ForegroundColor Green
} else {
    Write-Host "  (none found — your machine may not have a supported GPU/driver, or this build still doesn't include GPU support)" -ForegroundColor Yellow
    Write-Host "  Run 'ffmpeg -encoders' to see the full list." -ForegroundColor Yellow
}
