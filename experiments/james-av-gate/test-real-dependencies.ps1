<# Isolated Maven unit tests only. No James configuration, containers or real mail. #>
$ErrorActionPreference = 'Stop'
$candidates = @()
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$javac = Get-Command javac -ErrorAction SilentlyContinue
if ($javac) { $candidates += (Split-Path (Split-Path $javac.Source)) }
foreach ($base in @("$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Microsoft")) {
    if (Test-Path -LiteralPath $base) {
        $candidates += @(Get-ChildItem -LiteralPath $base -Directory | ForEach-Object { $_.FullName })
    }
}
$jdk = $null
foreach ($candidate in $candidates) {
    $compiler = Join-Path $candidate 'bin\javac.exe'
    if (Test-Path -LiteralPath $compiler -PathType Leaf) {
        $version = & $compiler --version
        if ($LASTEXITCODE -eq 0 -and $version -match '^javac\s+(\d+)' -and [int]$Matches[1] -ge 21) {
            $jdk = $candidate
            break
        }
    }
}
if (-not $jdk) { throw 'FAIL: JDK 21+ not found.' }
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'FAIL: Maven not found.' }
$oldJavaHome = $env:JAVA_HOME
$oldPath = $env:PATH
try {
    $env:JAVA_HOME = $jdk
    $env:PATH = "$(Join-Path $jdk 'bin');$oldPath"
    Write-Host "JDK: $jdk"
    & javac --version
    if ($LASTEXITCODE -ne 0) { throw 'FAIL: javac version.' }
    & mvn -B -f (Join-Path $PSScriptRoot 'pom.xml') test
    if ($LASTEXITCODE -ne 0) { throw 'FAIL: Maven compile or unit tests.' }
    Write-Host 'PASS: Maven real James dependencies and synthetic unit tests; no James runtime, no real ClamAV.'
} finally {
    $env:JAVA_HOME = $oldJavaHome
    $env:PATH = $oldPath
}
