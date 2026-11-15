<# Read-only preflight diagnosis for the James AV rollback drill.

   Prints one PASS/FAIL line per check with the reason. It changes no files,
   containers, networks, mail or queue state, and never prints secrets. Use it
   when the drill exits non-zero; its output identifies the failing condition. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DeploymentManifest,
    [string]$ExpectedComposeDir = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$failures = 0
function Report([string]$Name, [bool]$Ok, [string]$Detail) {
    if ($Ok) { Write-Host ("PASS " + $Name + $(if ($Detail) { ": " + $Detail } else { '' })) }
    else {
        Write-Host ("FAIL " + $Name + $(if ($Detail) { ": " + $Detail } else { '' }))
        $script:failures++
    }
}
function Try-Step([string]$Name, [scriptblock]$Body) {
    try {
        $result = & $Body
        Report $Name $true ([string]$result)
    } catch {
        Report $Name $false ($_.Exception.Message)
    }
}
function Get-Sha([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'missing file' }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Invoke-Docker([string[]]$Arguments) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& docker @Arguments 2>$null)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $saved }
    return @{ Output = $output; Code = $code }
}
foreach ($name in @('docker', 'git', 'tar', 'mvn', 'powershell.exe')) {
    $found = @(Get-Command $name -ErrorAction SilentlyContinue)
    Report "command $name" ($found.Count -ge 1) ($(if ($found.Count -ge 1) { 'available' } else { 'not found in PATH' }))
}
$m = $null
Try-Step 'manifest readable' {
    $script:m = Get-Content -Raw -LiteralPath $DeploymentManifest | ConvertFrom-Json
    'parsed'
}
if ($null -ne $m) {
    Try-Step 'manifest composeDir' {
        if ($m.composeDir -isnot [string]) { throw 'composeDir is not a string' }
        if (-not (Test-Path -LiteralPath $m.composeDir -PathType Container)) { throw 'composeDir does not exist' }
        $m.composeDir
    }
    Try-Step 'compose file hash' {
        $current = Get-Sha (Join-Path $m.composeDir 'docker-compose.yml')
        if ($current -cne $m.composeAfterSha256) { throw "differs from the deployed Compose (now $current)" }
        'matches deployment'
    }
    Try-Step 'compose backup' {
        if ((Get-Sha $m.composeBackup) -cne $m.composeBeforeSha256) { throw 'backup hash differs' }
        'present and unchanged'
    }
    Try-Step 'rollback compose' {
        if ((Get-Sha $m.rollbackCompose) -cne $m.rollbackComposeSha256) { throw 'rollback file hash differs' }
        'present and unchanged'
    }
    if ($m.webAdminPinned) {
        Try-Step 'WebAdmin source' {
            $webFile = Join-Path $m.composeDir $m.webAdminRelative
            if ((Get-Sha $webFile) -cne $m.webAdminSha256) { throw 'WebAdmin source hash differs' }
            'present and unchanged'
        }
    }
    Try-Step 'preparation manifest' {
        if (-not (Test-Path -LiteralPath $m.preparationManifest -PathType Leaf)) { throw 'file missing' }
        $script:prep = Get-Content -Raw -LiteralPath $m.preparationManifest | ConvertFrom-Json
        if ($prep.composeDir -cne $m.composeDir) {
            throw ("composeDir differs: preparation '" + $prep.composeDir + "', deployment '" + $m.composeDir + "'")
        }
        if ($prep.originalConfigSha256 -notmatch '^[a-f0-9]{64}$' -or $prep.candidateXmlSha256 -notmatch '^[a-f0-9]{64}$') {
            throw 'artifact hashes are malformed'
        }
        'consistent with the deployment'
    }
    Try-Step 'running James' {
        Push-Location -LiteralPath $m.composeDir
        try {
            $ids = (Invoke-Docker @('compose', '--profile', 'james-transition', 'ps', '--status', 'running', '-q', 'james')).Output
        } finally { Pop-Location }
        if (@($ids).Count -ne 1) { throw ('expected one running container, found ' + @($ids).Count) }
        $script:james = $ids[0]
        $info = ((Invoke-Docker @('inspect', '--format', '{{json .}}', $james)).Output[0]) | ConvertFrom-Json
        if ($info.Image -cne $m.jamesImageId) { throw 'running James image differs from the manifest' }
        $james
    }
    Try-Step 'gate mailet configuration' {
        $result = Invoke-Docker @('exec', $james, 'sha256sum', '/root/conf/mailetcontainer.xml')
        if ($result.Code -ne 0 -or $result.Output.Count -ne 1) { throw 'cannot read the file' }
        if ($result.Output[0] -notmatch '^([a-f0-9]{64})\s') { throw 'unexpected output' }
        if ($Matches[1] -cne $prep.candidateXmlSha256) { throw ('mounted XML differs from the candidate (now ' + $Matches[1] + ')') }
        'candidate XML is mounted'
    }
    Try-Step 'gate extension jars' {
        $result = Invoke-Docker @('exec', $james, 'find', '/root/extensions-jars', '-maxdepth', '1', '-type', 'f', '-name', '*.jar', '-printf', '%s %f\n')
        if ($result.Code -ne 0) { throw 'cannot list the directory' }
        $real = @(); $stubs = @()
        foreach ($line in $result.Output) {
            if ($line -notmatch '^([0-9]+) ([A-Za-z0-9._-]+)$') { throw 'unexpected entry' }
            if ([long]$Matches[1] -eq 0) { $stubs += $Matches[2] } else { $real += ($Matches[2] + ' (' + $Matches[1] + ' bytes)') }
        }
        if ($stubs.Count -gt 0) { Write-Host ('INFO zero-byte mount-point stubs: ' + ($stubs -join ', ')) }
        if ($real.Count -lt 1) { throw 'no non-empty extension jar found' }
        ($real -join '; ')
    }
    Try-Step 'running ClamAV' {
        Push-Location -LiteralPath $m.composeDir
        try {
            $ids = (Invoke-Docker @('compose', 'ps', '--status', 'running', '-q', 'clamav')).Output
        } finally { Pop-Location }
        if (@($ids).Count -ne 1) { throw ('expected one running container, found ' + @($ids).Count) }
        $status = (Invoke-Docker @('inspect', '--format', '{{.State.Status}} {{.State.Health.Status}}', $ids[0])).Output
        if ($status.Count -ne 1 -or $status[0] -cne 'running healthy') { throw ('state is ' + ($status -join ' ')) }
        'running healthy'
    }
    Try-Step 'James CLI' {
        $result = Invoke-Docker @('exec', $james, 'james-cli', 'ListDomains')
        if ($result.Code -ne 0) { throw ('exit ' + $result.Code) }
        ('' + @($result.Output).Count + ' line(s)')
    }
    if ($ExpectedComposeDir) {
        Report 'expected ComposeDir' ($m.composeDir.TrimEnd('\', '/') -ceq $ExpectedComposeDir.TrimEnd('\', '/')) ("manifest " + $m.composeDir)
    }
}
Write-Host ("RESULT failures=" + $failures)
if ($failures -gt 0) { exit 1 }
Write-Host 'All read-only preflight checks passed; the drill should pass the same stage.'
Write-Host 'No files, containers, networks or mail were changed.'
