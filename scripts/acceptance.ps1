<#
.SYNOPSIS
    One Corvex acceptance run on the server: step by step, PASS/FAIL, one log.

.DESCRIPTION
    Instead of a manual checklist this script stops at the first failed required
    step and writes a single report to artifacts\smoke\. It never changes
    settings, never deletes data and never recreates volumes.

    Steps:
      0. toolchain and current git SHA
      1. compose config and runtime state (runtime_smoke.py)
      2. full Maven reactor - only with -Maven (slow, this is G1)
      3. mail end-to-end SMTP -> INBOX -> Sent with an attachment (mail_e2e.py)
      4. calendar: event, 409 on stale revision, reminder in INBOX, delete (calendar_smoke.py)
      5. synthetic alert -> admin email (alert_e2e.py, with -Alert)

    Passwords are never passed as arguments: the helper scripts ask for them
    with a hidden prompt or read CORVEX_TEST_* environment variables. The log
    contains step names, durations and error text without secrets.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\acceptance.ps1 `
        -Sender i.ivanov@company.local -Recipient a.petrova@company.local `
        -CalendarUser i.ivanov@company.local

.EXAMPLE
    # full run including Maven and the alert chain
    powershell -ExecutionPolicy Bypass -File scripts\acceptance.ps1 -Maven -Alert `
        -Sender i.ivanov@company.local -Recipient a.petrova@company.local `
        -CalendarUser i.ivanov@company.local -AlertUser admin@company.local
#>
param(
  [string]$BaseUrl = "https://mail.company.local",
  [string]$CaFile = "",
  [string]$Sender = "",
  [string]$Recipient = "",
  [string]$CalendarUser = "",
  [string]$AlertUser = "",
  [string]$ReportDir = "artifacts\smoke",
  [switch]$Maven,
  [switch]$Alert,
  [switch]$SkipMail,
  [switch]$SkipCalendar,
  [int]$MavenTimeoutMinutes = 60
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$python = "py"
try { & $python -3 --version | Out-Null } catch { $python = "python" }

$stamp = Get-Date -Format "yyyy-MM-dd_HHmmss"
if (-not (Test-Path -LiteralPath $ReportDir)) {
  New-Item -ItemType Directory -Path $ReportDir -Force | Out-Null
}
$ReportDir = (Resolve-Path -LiteralPath $ReportDir).Path
$log = Join-Path $ReportDir "acceptance-$stamp.log"
$results = New-Object System.Collections.Generic.List[object]

function Write-Log([string]$Text, [string]$Color = "Gray") {
  $line = "[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $Text
  Write-Host $line -ForegroundColor $Color
  Add-Content -LiteralPath $log -Value $line -Encoding UTF8
}

<#
  Each step block returns an exit code through $script:StepCode:
    0 = pass, 2 = fail, 3 = skipped/incomplete.
  "exit" is NOT used inside a block: in PowerShell it terminates the whole
  script instead of the block.
#>
function Invoke-Step {
  param(
    [string]$Name,
    [scriptblock]$Action,
    [bool]$Required = $true
  )
  Write-Log "START $Name" "Cyan"
  $started = Get-Date
  $script:StepCode = 0
  $detail = ""
  try {
    $output = & $Action 2>&1
    $text = ($output | Out-String).Trim()
    if ($text) {
      Add-Content -LiteralPath $log -Value $text -Encoding UTF8
      Write-Host $text
    }
  } catch {
    $script:StepCode = 2
    $detail = $_.Exception.Message
    Write-Log "ERROR ${Name}: $detail" "Red"
  }
  $status = switch ($script:StepCode) {
    0 { "PASS" }
    3 { "SKIP" }
    default { "FAIL" }
  }
  if ($status -eq "SKIP" -and -not $detail) { $detail = "incomplete (exit 3)" }
  if ($status -eq "FAIL" -and -not $detail) { $detail = "exit $script:StepCode" }
  $seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
  $results.Add([pscustomobject]@{ step = $Name; status = $status; seconds = $seconds; detail = $detail }) | Out-Null
  $color = switch ($status) { "PASS" { "Green" } "SKIP" { "Yellow" } default { "Red" } }
  Write-Log ("{0} {1} ({2}s){3}" -f $status, $Name, $seconds, $(if ($detail) { " - $detail" } else { "" })) $color
  if ($status -eq "FAIL" -and $Required) {
    throw "Required step failed: $Name ($detail). See $log"
  }
}

function Get-Sha {
  try { return (git rev-parse --short HEAD).Trim() } catch { return "unknown" }
}

function Invoke-PythonSmoke {
  param([string]$Script, [string[]]$Arguments)
  & $python -3 (Join-Path "scripts" $Script) @Arguments
  if ($LASTEXITCODE -eq 0) { $script:StepCode = 0 }
  elseif ($LASTEXITCODE -eq 3) { $script:StepCode = 3 }
  else { $script:StepCode = 2 }
}

function New-ReportPath([string]$Prefix) {
  return (Join-Path $ReportDir "$Prefix-$stamp.json")
}

$hostName = ([uri]$BaseUrl).Host
Write-Log "Corvex acceptance, SHA $(Get-Sha), base URL $BaseUrl" "White"
Write-Log "Log: $log" "White"

Invoke-Step "toolchain" {
  & docker --version
  if ($LASTEXITCODE -ne 0) { $script:StepCode = 2; return }
  & docker compose version
  if ($LASTEXITCODE -ne 0) { $script:StepCode = 2; return }
  & $python -3 --version
  if ($LASTEXITCODE -ne 0) { $script:StepCode = 2; return }
  $script:StepCode = 0
}

Invoke-Step "compose and runtime" {
  & docker compose config -q
  if ($LASTEXITCODE -ne 0) { $script:StepCode = 2; return }
  $arguments = @("--url", $BaseUrl, "--report", (New-ReportPath "runtime"))
  if ($CaFile) { $arguments += @("--ca-file", $CaFile) }
  Invoke-PythonSmoke "runtime_smoke.py" $arguments
}

if ($Maven) {
  Invoke-Step "maven full reactor" {
    $job = Start-Job -ScriptBlock { param($dir) Set-Location $dir; & mvn -B clean verify } -ArgumentList (Get-Location).Path
    if (-not (Wait-Job $job -Timeout ($MavenTimeoutMinutes * 60))) {
      Stop-Job $job -ErrorAction SilentlyContinue
      Remove-Job $job -Force -ErrorAction SilentlyContinue
      $script:StepCode = 2
      return
    }
    Receive-Job $job | Out-String | Write-Output
    $state = $job.State
    Remove-Job $job -Force -ErrorAction SilentlyContinue
    if ($state -eq "Completed") { $script:StepCode = 0 } else { $script:StepCode = 2 }
  }
}

if (-not $SkipMail) {
  if (-not $Sender -or -not $Recipient) {
    Write-Log "Skipping mail e2e: pass -Sender and -Recipient" "Yellow"
  } else {
    Invoke-Step "mail e2e smtp, inbox, sent, attachment" {
      $arguments = @(
        "--host", $hostName,
        "--sender", $Sender,
        "--recipient", $Recipient,
        "--report", (New-ReportPath "mail-e2e")
      )
      if ($CaFile) { $arguments += @("--ca-file", $CaFile) }
      Invoke-PythonSmoke "mail_e2e.py" $arguments
    }
  }
}

if (-not $SkipCalendar) {
  if (-not $CalendarUser) {
    Write-Log "Skipping calendar: pass -CalendarUser" "Yellow"
  } else {
    Invoke-Step "calendar and reminder" {
      $arguments = @(
        "--base-url", $BaseUrl,
        "--username", $CalendarUser,
        "--report", (New-ReportPath "calendar")
      )
      if ($CaFile) { $arguments += @("--ca-file", $CaFile) }
      Invoke-PythonSmoke "calendar_smoke.py" $arguments
    }
  }
}

if ($Alert) {
  if (-not $AlertUser) {
    Write-Log "Skipping alert e2e: pass -AlertUser" "Yellow"
  } else {
    Invoke-Step "alertmanager to mail" {
      $arguments = @(
        "--host", $hostName,
        "--username", $AlertUser,
        "--report", (New-ReportPath "alert-e2e")
      )
      if ($CaFile) { $arguments += @("--ca-file", $CaFile) }
      Invoke-PythonSmoke "alert_e2e.py" $arguments
    }
  }
}

$summaryPath = Join-Path $ReportDir "acceptance-$stamp.json"
[pscustomobject]@{
  suite     = "acceptance"
  created_at = (Get-Date).ToUniversalTime().ToString("o")
  git_sha   = (Get-Sha)
  base_url  = $BaseUrl
  log       = $log
  steps     = $results
} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $summaryPath -Encoding UTF8

Write-Log "" "White"
Write-Log "Summary:" "White"
foreach ($result in $results) {
  $color = switch ($result.status) { "PASS" { "Green" } "SKIP" { "Yellow" } default { "Red" } }
  Write-Log ("  {0,-4} {1,-45} {2,6}s {3}" -f $result.status, $result.step, $result.seconds, $result.detail) $color
}
Write-Log "JSON: $summaryPath" "White"

$failed = @($results | Where-Object { $_.status -eq "FAIL" }).Count
if ($failed -gt 0) { exit 1 }
$skipped = @($results | Where-Object { $_.status -eq "SKIP" }).Count
if ($skipped -gt 0) { exit 3 }
exit 0
