# Corvex UI screenshots (headless Chrome, no extra dependencies)
#
# Usage (PowerShell, repository root, desktop not required):
#   .\scripts\capture-screenshots.ps1
#   .\scripts\capture-screenshots.ps1 -BaseUrl http://localhost:5173 -OutDir docs\screenshots
#
# Requirements:
#   - Google Chrome or Microsoft Edge installed (the script only uses its --headless mode).
#   - A dev server started from .\frontend:  npm run dev
#     The demo deep link (?demo=1) is enough; no backend, no account and no secrets.
#
# What it does: opens each route with ?demo=1 so the page shows labelled sample data,
# waits for the lazy chunk to render, and writes PNG files with the names the README
# already references. Files are overwritten; review the diff before committing.
#
# This script cannot be run on a build machine without a browser: it deliberately
# starts the user's own Chrome instead of downloading one.

[CmdletBinding()]
param(
  [string]$BaseUrl = 'http://localhost:5173',
  [string]$OutDir = 'docs\screenshots',
  [int]$WaitMs = 9000,
  [string]$Browser = ''
)

$ErrorActionPreference = 'Stop'

function Find-Browser {
  $candidates = @(
    "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
    "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
    "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe",
    "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
    "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
  )
  foreach ($path in $candidates) {
    if ($path -and (Test-Path -LiteralPath $path)) { return $path }
  }
  throw "Chrome or Edge not found. Install Chrome or pass a browser via -Browser <path>."
}

$browser = if ($Browser) { $Browser } else { Find-Browser }
if (-not (Test-Path -LiteralPath $browser)) { throw "Browser executable not found: $browser" }
Write-Host "Browser: $browser"

if (-not (Test-Path -LiteralPath $OutDir)) { New-Item -ItemType Directory -Path $OutDir | Out-Null }
$OutDir = (Resolve-Path -LiteralPath $OutDir).Path

try {
  $probe = Invoke-WebRequest -Uri $BaseUrl -UseBasicParsing -TimeoutSec 10
  Write-Host "Dev server: $BaseUrl (HTTP $($probe.StatusCode))"
} catch {
  throw "Cannot reach $BaseUrl. Start the frontend first: cd frontend; npm run dev"
}

# route, file suffix, viewport width, height, language tag
$shots = @(
  @{ Path = '/login';      Name = 'login';       W = 1440; H = 900;  Lang = 'ru-RU' },
  @{ Path = '/login';      Name = 'login';       W = 1440; H = 900;  Lang = 'en-US' },
  @{ Path = '/mail?demo=1';    Name = 'mail';        W = 1440; H = 900;  Lang = 'ru-RU' },
  @{ Path = '/mail?demo=1';    Name = 'mail';        W = 1440; H = 900;  Lang = 'en-US' },
  @{ Path = '/chat?demo=1';    Name = 'chat';        W = 1440; H = 900;  Lang = 'ru-RU' },
  @{ Path = '/chat?demo=1';    Name = 'chat';        W = 1440; H = 900;  Lang = 'en-US' },
  @{ Path = '/calendar?demo=1';Name = 'calendar';    W = 1440; H = 900;  Lang = 'ru-RU' },
  @{ Path = '/calendar?demo=1';Name = 'calendar';    W = 1440; H = 900;  Lang = 'en-US' },
  @{ Path = '/admin?demo=1';   Name = 'admin';       W = 1440; H = 900;  Lang = 'ru-RU' },
  @{ Path = '/admin?demo=1';   Name = 'admin';       W = 1440; H = 900;  Lang = 'en-US' },
  @{ Path = '/mail?demo=1';    Name = 'mail-mobile'; W = 430;  H = 932;  Lang = 'ru-RU' }
)

foreach ($shot in $shots) {
  # Locale suffix: ru for ru-RU, en for en-US. Mobile keeps the ru suffix.
  $suffix = if ($shot.Lang -like 'en*') { 'en' } else { 'ru' }
  $file = Join-Path $OutDir ("{0}-{1}.png" -f $shot.Name, $suffix)
  $url = "$BaseUrl$($shot.Path)"
  $profile = Join-Path $env:TEMP ("corvex-shot-" + [guid]::NewGuid().ToString('N'))

  $arguments = @(
    '--headless=new',
    '--disable-gpu',
    '--hide-scrollbars',
    '--no-first-run',
    '--no-default-browser-check',
    '--force-device-scale-factor=1',
    "--user-data-dir=$profile",
    "--lang=$($shot.Lang)",
    "--window-size=$($shot.W),$($shot.H)",
    '--run-all-compositor-stages-before-draw',
    "--virtual-time-budget=$WaitMs",
    "--screenshot=$file",
    $url
  )

  Write-Host ("Capturing {0} -> {1}" -f $url, (Split-Path -Leaf $file))
  $process = Start-Process -FilePath $browser -ArgumentList $arguments -Wait -PassThru -WindowStyle Hidden
  if (-not (Test-Path -LiteralPath $file)) {
    throw "Screenshot was not created: $file (browser exit $($process.ExitCode))"
  }
  $size = (Get-Item -LiteralPath $file).Length
  if ($size -lt 20000) {
    Write-Warning "File looks too small ($size bytes) - the page may not have rendered: $file"
  }
  Remove-Item -LiteralPath $profile -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host "Done. Files in $OutDir"
Write-Host 'Check the diff, then commit only images you reviewed:'
Write-Host '  git status --short docs/screenshots'
