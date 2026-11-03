<#
  Manage Corvex Alertmanager notification policy without stopping monitoring.

  Examples (run from the repository root):
    powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 status
    powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 silence -Minutes 60
    powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 silence -Minutes 120 -Service chat-service:8088
    powershell -ExecutionPolicy Bypass -File scripts\alerts.ps1 unsilence -SilenceId <uuid-from-status>

  Silence suppresses notifications in BOTH internal and external channels. Alerts remain
  visible in Alertmanager/Grafana and delivery resumes automatically when the silence ends.
#>
[CmdletBinding()]
param(
  [Parameter(Position = 0, Mandatory = $true)]
  [ValidateSet("status", "silence", "unsilence")]
  [string]$Action,

  [ValidateRange(1, 43200)]
  [int]$Minutes = 60,

  # Exact Prometheus instance label, for example chat-service:8088.
  [ValidatePattern('^[A-Za-z0-9_.-]+(:[0-9]{1,5})?$')]
  [string]$Service,

  [ValidatePattern('^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$')]
  [string]$SilenceId,

  [ValidatePattern('^[a-z0-9][a-z0-9_-]*$')]
  [string]$ProjectName = "corvex"
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

function Invoke-Alertmanager {
  param([Parameter(Mandatory = $true)][string[]]$ToolArguments)
  & docker compose -p $ProjectName exec -T alertmanager @ToolArguments
  if ($LASTEXITCODE -ne 0) {
    throw "Alertmanager command failed with exit code $LASTEXITCODE"
  }
}

$AmTool = @("amtool", "--alertmanager.url=http://127.0.0.1:9093")

switch ($Action) {
  "status" {
    $Repeat = & docker compose -p $ProjectName exec -T alertmanager printenv CORVEX_ALERT_REPEAT_INTERVAL
    if ($LASTEXITCODE -ne 0) { throw "Cannot read CORVEX_ALERT_REPEAT_INTERVAL" }
    $CriticalRepeat = & docker compose -p $ProjectName exec -T alertmanager printenv CORVEX_ALERT_CRITICAL_REPEAT_INTERVAL
    if ($LASTEXITCODE -ne 0) { throw "Cannot read CORVEX_ALERT_CRITICAL_REPEAT_INTERVAL" }
    Write-Host "Repeat interval (normal): $Repeat"
    Write-Host "Repeat interval (critical): $CriticalRepeat"
    Write-Host "Active and pending silences:"
    Invoke-Alertmanager ($AmTool + @("silence", "query"))
  }
  "silence" {
    $Matcher = if ($Service) { "instance=$Service" } else { "alertname=~.+" }
    $Scope = if ($Service) { $Service } else { "all Corvex alerts" }
    Write-Host "Creating a $Minutes-minute silence for $Scope"
    Invoke-Alertmanager ($AmTool + @(
      "silence", "add",
      "--duration", "${Minutes}m",
      "--author", "corvex-admin",
      "--comment", "Temporary notification pause from scripts/alerts.ps1",
      $Matcher
    ))
    Write-Host "Silence created. Save the printed UUID if you want to end it early."
  }
  "unsilence" {
    if (-not $SilenceId) {
      throw "unsilence requires -SilenceId <uuid-from-status>"
    }
    Invoke-Alertmanager ($AmTool + @("silence", "expire", $SilenceId))
    Write-Host "Silence expired; matching notifications are active again."
  }
}
