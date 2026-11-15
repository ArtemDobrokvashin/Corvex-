<# One confirmed run: dry-run, apply, then the synthetic archive probe.

   Writes one durable combined log, prints it at the end and returns a
   distinguishable exit code (0 pass, 1 dry-run, 2 apply, 3 probe). It does not
   retry, does not delete anything and never touches mail. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$SourceRoot,
    [string]$ComposeDir = (Get-Location).Path,
    # After a successful apply the configuration must not be reapplied; this
    # mode only runs the synthetic probe.
    [switch]$ProbeOnly,
    [switch]$ConfirmLaptop
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$logDir = Join-Path ([IO.Path]::GetTempPath()) ('corvex-clamav-run-' + [guid]::NewGuid().ToString('N'))
$log = Join-Path $logDir 'run.log'
function Add-Log([string]$Text) {
    [IO.File]::AppendAllText($log, $Text + "`r`n", [Text.Encoding]::UTF8)
}
function Invoke-Step([string]$Name, [string]$Script, [string[]]$Arguments) {
    if (-not (Test-Path -LiteralPath $Script -PathType Leaf)) { throw "Script missing: $Script" }
    $out = Join-Path $logDir ($Name + '.out.txt')
    $err = Join-Path $logDir ($Name + '.err.txt')
    $quoted = @($Arguments | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } })
    $all = @('-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $Script + '"')) + $quoted
    Add-Log ("=== " + $Name + " START ===")
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $proc = Start-Process -FilePath 'powershell.exe' -ArgumentList $all -Wait -PassThru -NoNewWindow `
            -RedirectStandardOutput $out -RedirectStandardError $err
    } finally { $ErrorActionPreference = $saved }
    foreach ($file in @($out, $err)) {
        if (Test-Path -LiteralPath $file) {
            foreach ($line in [IO.File]::ReadAllLines($file)) { Add-Log $line }
        }
    }
    Add-Log ("=== " + $Name + " EXIT=" + $proc.ExitCode + " ===")
    return $proc.ExitCode
}
function Write-Result([string]$Text) {
    Add-Log $Text
    Write-Host $Text
}
New-Item -ItemType Directory -Path $logDir -ErrorAction Stop | Out-Null
$code = 4
try {
    foreach ($name in @('docker', 'powershell.exe')) {
        if (-not (Get-Command $name -ErrorAction SilentlyContinue)) { throw "Required command missing: $name" }
    }
    $enable = Join-Path $SourceRoot 'scripts/enable-clamav-limit-alert.ps1'
    $probeScript = Join-Path $SourceRoot 'scripts/test-av-nested-archive.ps1'
    if (-not (Test-Path -LiteralPath (Join-Path $ComposeDir 'docker-compose.yml') -PathType Leaf)) {
        throw 'Compose directory has no docker-compose.yml.'
    }
    # Both steps need their own inputs; check them before changing anything.
    $prerequisites = @(
        (Join-Path $SourceRoot 'scripts/src/AvNestedArchiveProbe.java'),
        (Join-Path $SourceRoot 'services/mail-storage-service/pom.xml'))
    if (-not $ProbeOnly) { $prerequisites += $enable }
    foreach ($required in $prerequisites) {
        if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
            throw ('Source archive is incomplete, missing: ' + $required + '. Export scripts AND services/mail-storage-service.')
        }
    }
    if (-not (Test-Path -LiteralPath $probeScript -PathType Leaf)) { throw ('Missing probe script: ' + $probeScript) }
    Write-Result ("Log: " + $log)
    if (-not $ProbeOnly) {
        $dry = Invoke-Step 'dry' $enable @('-ComposeDir', $ComposeDir, '-ConfirmLaptop')
        if ($dry -ne 0) {
            $code = 1
            Write-Result 'RESULT: dry-run failed; nothing was changed.'
            throw 'Dry-run failed; see the log above.'
        }
        $apply = Invoke-Step 'apply' $enable @('-ComposeDir', $ComposeDir, '-ConfirmLaptop', '-Apply')
        if ($apply -ne 0) {
            $code = 2
            Write-Result 'RESULT: apply failed; ClamAV may be recreated without the setting. Inspect before any retry.'
            throw 'Apply failed; see the log above.'
        }
        # The gate caches ClamAV addresses for 30 seconds.
        Start-Sleep -Seconds 45
    } else {
        Write-Result 'Probe-only mode: configuration was not changed.'
    }
    $probe = Invoke-Step 'probe' $probeScript @('-SourceRoot', $SourceRoot, '-ComposeDir', $ComposeDir, '-ConfirmLaptop')
    if ($probe -ne 0) {
        $code = 3
        Write-Result 'RESULT: probe failed; limit alerts are not proven. See the log above.'
        throw 'Probe failed; see the log above.'
    }
    $code = 0
    Write-Result 'RESULT: PASS - limit alert configured and the deep synthetic samples were no longer reported CLEAN.'
} finally {
    Add-Log ("Overall exit: " + $code)
    Write-Host ("LOG=" + $log)
    Write-Host ("EXIT=" + $code)
    exit $code
}
