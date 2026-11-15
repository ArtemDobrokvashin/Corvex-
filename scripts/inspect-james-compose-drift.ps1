<# Read-only comparison; secret values, Compose JSON and Docker inspect data never printed. #>
[CmdletBinding()]
param([string]$ComposeDir = (Get-Location).Path)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$stage = 'container'
try {
    Push-Location -LiteralPath $ComposeDir
    try {
        $ids = @(& docker compose --profile james-transition ps --status running -q james 2>$null)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1) { throw 'Running James unavailable.' }
        $raw = @(& docker inspect --format '{{json .}}' $ids[0] 2>$null)
        if ($LASTEXITCODE -ne 0 -or $raw.Count -ne 1) { throw 'Container metadata unavailable.' }
        $info = $raw[0] | ConvertFrom-Json
        $stage = 'compose'
        $saved = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $cfg = @(& docker compose --profile james-transition config --format json james 2>$null)
            $code = $LASTEXITCODE
        } finally { $ErrorActionPreference = $saved }
        if ($code -ne 0 -or $cfg.Count -eq 0) { throw 'Compose JSON unavailable.' }
        # Config and inspect can contain secrets. Only fixed labels and aggregate differences are printed.
        $project = ($cfg -join "`n") | ConvertFrom-Json
        $svc = $project.services.PSObject.Properties['james']
        if ($null -eq $svc) { throw 'James missing from configuration.' }
        $svc = $svc.Value
        $labels = $info.Config.Labels.PSObject.Properties['com.docker.compose.config-hash']
        $desired = @(& docker compose --profile james-transition config --hash james 2>$null)
        if ($LASTEXITCODE -ne 0 -or $null -eq $labels -or $labels.Value -notmatch '^[a-f0-9]{64}$' -or
            $desired.Count -ne 1 -or $desired[0] -notmatch '^james\s+([a-f0-9]{64})$') { throw 'Service hash unavailable.' }
        $expectedHash = $Matches[1]
        Write-Host '=== James Compose drift (no secret values) ==='
        if ($expectedHash -ceq $labels.Value) { Write-Host 'Compose service hash: MATCH' }
        else { Write-Host 'Compose service hash: DIFFERENT' }
        if ($svc.image -ceq $info.Config.Image) { Write-Host 'Image reference: MATCH' } else { Write-Host 'Image reference: DIFFERENT' }
        $stage = 'environment'
        $actualEnv = @{}
        foreach ($item in @($info.Config.Env)) {
            if ($item -is [string] -and $item.Contains('=')) {
                $index = $item.IndexOf('=')
                $actualEnv[$item.Substring(0, $index)] = $item.Substring($index + 1)
            }
        }
        $envMissing = 0; $envChanged = 0; $envCount = 0
        $envProp = $svc.PSObject.Properties['environment']
        if ($null -ne $envProp -and $null -ne $envProp.Value) {
            foreach ($p in $envProp.Value.PSObject.Properties) {
                $envCount++
                if (-not $actualEnv.ContainsKey($p.Name)) { $envMissing++ }
                elseif ($null -eq $p.Value -or [string]$p.Value -cne $actualEnv[$p.Name]) { $envChanged++ }
            }
        }
        Write-Host "Compose environment keys checked=$envCount missing=$envMissing different=$envChanged (values withheld)"
        $stage = 'mounts'
        $desiredMounts = @{}
        $volumeProp = $svc.PSObject.Properties['volumes']
        if ($null -ne $volumeProp -and $null -ne $volumeProp.Value) {
            foreach ($v in @($volumeProp.Value)) {
                if ($null -eq $v -or $v.PSObject.Properties.Name -notcontains 'target' -or $v.target -isnot [string] -or $desiredMounts.ContainsKey($v.target)) { throw 'Unexpected Compose volume metadata.' }
                $desiredMounts[$v.target] = $v
            }
        }
        $actualMounts = @{}
        foreach ($v in @($info.Mounts)) {
            if ($null -eq $v -or $actualMounts.ContainsKey($v.Destination)) { throw 'Unexpected container mount metadata.' }
            $actualMounts[$v.Destination] = $v
        }
        $missing = 0; $different = 0; $same = 0; $unverified = 0
        $knownTargets = @('/root/var','/root/conf/imapserver.xml','/root/conf/smtpserver.xml',
                          '/root/conf/pop3server.xml','/root/conf/lmtpserver.xml','/root/conf/managesieveserver.xml',
                          '/root/conf/postgres.properties','/root/conf/jmap.properties','/root/conf/search.properties',
                          '/root/conf/blob.properties','/root/conf/webadmin.properties','/root/conf/jwt_publickey')
        foreach ($target in $desiredMounts.Keys) {
            $wanted = $desiredMounts[$target]
            if (-not $actualMounts.ContainsKey($target)) {
                $missing++; if ($knownTargets -ccontains $target) { Write-Host "Mount missing: $target" }; continue
            }
            $actual = $actualMounts[$target]
            if ($wanted.type -cne $actual.Type) {
                $different++; if ($knownTargets -ccontains $target) { Write-Host "Mount type differs: $target" }; continue
            }
            $wantedRo = $false
            if ($wanted.PSObject.Properties.Name -contains 'read_only') { $wantedRo = [bool]$wanted.read_only }
            if ($wantedRo -eq [bool]$actual.RW) {
                $different++; if ($knownTargets -ccontains $target) { Write-Host "Mount access differs: $target" }; continue
            }
            if ($wanted.type -ceq 'bind') {
                if ($wanted.PSObject.Properties.Name -notcontains 'source' -or -not (Test-Path -LiteralPath $wanted.source -PathType Leaf)) { $unverified++; continue }
                $hostHash = (Get-FileHash -LiteralPath $wanted.source -Algorithm SHA256).Hash.ToLowerInvariant()
                $remoteHash = @(& docker exec $ids[0] sha256sum $target 2>$null)
                if ($LASTEXITCODE -ne 0 -or $remoteHash.Count -ne 1 -or $remoteHash[0] -notmatch '^([a-f0-9]{64})\s') { $unverified++; continue }
                if ($hostHash -ceq $Matches[1]) { $same++ }
                else { $different++; if ($knownTargets -ccontains $target) { Write-Host "Mount content differs: $target" } }
            } elseif ($wanted.type -ceq 'volume') {
                if ($wanted.PSObject.Properties.Name -notcontains 'source') { $unverified++; continue }
                $vp = $project.volumes.PSObject.Properties[$wanted.source]
                if ($null -eq $vp -or $null -eq $vp.Value.PSObject.Properties['name']) { $unverified++; continue }
                if ($vp.Value.name -ceq $actual.Name) { $same++ }
                else { $different++; if ($knownTargets -ccontains $target) { Write-Host "Volume identity differs: $target" } }
            } else { $unverified++ }
        }
        $extra = 0
        foreach ($target in $actualMounts.Keys) {
            # These volumes are declared by the image itself; they are not configured service mounts.
            if (-not $desiredMounts.ContainsKey($target) -and @('/root/conf','/root/extensions-jars','/logs') -cnotcontains $target) { $extra++ }
        }
        Write-Host "Compose mounts checked=$($desiredMounts.Count) match=$same missing=$missing different=$different unverified=$unverified extra=$extra"
        $stage = 'networks'
        $actualNetworks = @($info.NetworkSettings.Networks.PSObject.Properties.Name)
        $networkProp = $svc.PSObject.Properties['networks']
        $missingNet = 0; $desiredNetCount = 0
        if ($null -ne $networkProp -and $null -ne $networkProp.Value) {
            foreach ($network in $networkProp.Value.PSObject.Properties) {
                $desiredNetCount++
                $definition = $project.networks.PSObject.Properties[$network.Name]
                if ($null -eq $definition -or $null -eq $definition.Value.PSObject.Properties['name']) { throw 'Network name unavailable.' }
                if ($actualNetworks -cnotcontains $definition.Value.name) { $missingNet++ }
            }
        }
        Write-Host "Compose networks checked=$desiredNetCount missing=$missingNet actual=$($actualNetworks.Count)"
        Write-Host 'No secret values printed; this is a partial comparison, not proof that recreation is safe.'
        Write-Host 'No files, networks, containers, queues or mail changed.'
    } finally { Pop-Location }
} catch {
    [Console]::Error.WriteLine("ERROR: James Compose drift inspection failed at $stage; no changes made.")
    exit 2
}
