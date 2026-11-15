<# Enable the experimental James spool gate on the existing laptop. #>
[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$PreparationManifest, [switch]$ConfirmLaptop, [switch]$Apply)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$stage = 'manifest'
$statePath = $null
$runtimeChanged = $false
$oldWebAdmin = $env:CORVEX_JAMES_WEBADMIN_FILE
$webOverride = $false
$pinWebAdmin = $false
$webAdminSha = $null
$webAdminRelative = './infra/secrets/james-provision/webadmin.properties'
$webAdminExpression = '${CORVEX_JAMES_WEBADMIN_FILE:-${CORVEX_JAMES_CONFIG_DIR:-./infra/secrets/james}/webadmin.properties}'
try {
    $m = Get-Content -Raw -LiteralPath $PreparationManifest | ConvertFrom-Json
    if ($m.jamesContainerId -notmatch '^[a-f0-9]{12,64}$' -or $m.jamesImageId -notmatch '^sha256:[a-f0-9]{64}$' -or
        $m.originalConfigSha256 -cne '2158891b662abcd527f56f24a92b10d5404d875fad6e83ed3d57135a3386884f') { throw 'Unexpected preparation manifest or mailet configuration.' }
    foreach ($pair in @(@($m.originalConfig,$m.originalConfigSha256), @($m.candidateXml,$m.candidateXmlSha256), @($m.candidateJar,$m.candidateJarSha256), @($m.parentJar,$m.parentJarSha256))) {
        if ($pair[1] -notmatch '^[a-f0-9]{64}$' -or -not (Test-Path -LiteralPath $pair[0] -PathType Leaf) -or
            (Get-FileHash -LiteralPath $pair[0] -Algorithm SHA256).Hash.ToLowerInvariant() -cne $pair[1]) { throw 'Prepared artifact missing or changed.' }
    }
    Push-Location -LiteralPath $m.composeDir
    try {
        $root = (Get-Location).Path
        $stage = 'container-check'
        $jamesIds = @(& docker compose --profile james-transition ps --status running -q james 2>$null)
        if ($LASTEXITCODE -ne 0 -or $jamesIds.Count -ne 1 -or $jamesIds[0] -cne $m.jamesContainerId) { throw 'James container changed since preparation.' }
        $clamIds = @(& docker compose ps --status running -q clamav 2>$null)
        if ($LASTEXITCODE -ne 0 -or $clamIds.Count -ne 1 -or $clamIds[0] -notmatch '^[a-f0-9]{12,64}$') { throw 'Expected one ClamAV container.' }
        $clamId = $clamIds[0]
        $infoJson = @(& docker inspect --format '{{json .}}' $m.jamesContainerId 2>$null)
        if ($LASTEXITCODE -ne 0 -or $infoJson.Count -ne 1) { throw 'Cannot inspect James.' }
        # Full inspect stays in memory: it can include environment values.
        $info = $infoJson[0] | ConvertFrom-Json
        if ($info.Image -cne $m.jamesImageId -or $info.State.Status -cne 'running') { throw 'James image or state changed.' }
        $volumes = @{}
        foreach ($mount in $info.Mounts) {
            if (@('/root/conf','/root/extensions-jars','/root/var') -ccontains $mount.Destination) {
                if ($mount.Type -cne 'volume' -or [string]::IsNullOrWhiteSpace($mount.Name)) { throw 'Unexpected James mount type.' }
                $volumes[$mount.Destination] = $mount.Name
            }
        }
        foreach ($dst in @('/root/conf','/root/extensions-jars','/root/var')) {
            if (-not $volumes.ContainsKey($dst)) { throw "Required James volume missing: $dst" }
        }
        $status = @(& docker inspect --format '{{.State.Status}} {{.State.Health.Status}}' $clamId 2>$null)
        if ($LASTEXITCODE -ne 0 -or $status.Count -ne 1 -or $status[0] -cne 'running healthy') { throw 'ClamAV not healthy.' }
        $extensionEntries = @(& docker exec $m.jamesContainerId find /root/extensions-jars -maxdepth 1 -type f -name '*.jar' -printf '%s %f\n' 2>$null)
        if ($LASTEXITCODE -ne 0) { throw 'Extension directory not inspectable.' }
        $stubs = @()
        foreach ($entry in $extensionEntries) {
            if ($entry -notmatch '^([0-9]+) ([A-Za-z0-9._-]+)$') { throw 'Unexpected extension entry; do not mask it.' }
            # A file bind mount under a volume leaves a zero-byte mount-point
            # stub after the container is recreated. It cannot be a loaded jar.
            if ([long]$Matches[1] -eq 0 -and $Matches[2] -match '^corvex-(av-gate|av-parent)-[a-f0-9]{32}\.jar$') {
                $stubs += $Matches[2]
                continue
            }
            throw 'Existing extension jars found or not inspectable; do not mask them.'
        }
        if ($stubs.Count -gt 0) { Write-Host ("Zero-byte mount-point stubs from earlier runs: " + ($stubs -join ', ')) }
        $liveHash = @(& docker exec $m.jamesContainerId sha256sum /root/conf/mailetcontainer.xml 2>$null)
        if ($LASTEXITCODE -ne 0 -or $liveHash.Count -ne 1 -or $liveHash[0] -notmatch '^([a-f0-9]{64})\s' -or $Matches[1] -cne $m.originalConfigSha256) { throw 'Live mailet config changed.' }
        $originalHashes = @{}
        foreach ($service in @('james','clamav')) {
            $id = if ($service -eq 'james') { $m.jamesContainerId } else { $clamId }
            $labelsJson = @(& docker inspect --format '{{json .Config.Labels}}' $id 2>$null)
            if ($LASTEXITCODE -ne 0 -or $labelsJson.Count -ne 1) { throw 'Container labels unavailable.' }
            $labels = $labelsJson[0] | ConvertFrom-Json
            $actual = $labels.PSObject.Properties['com.docker.compose.config-hash']
            if ($null -eq $actual -or $actual.Value -notmatch '^[a-f0-9]{64}$') { throw "Container hash unavailable: $service." }
            $desired = @(& docker compose --profile james-transition config --hash $service 2>$null)
            if ($LASTEXITCODE -ne 0 -or $desired.Count -ne 1 -or $desired[0] -notmatch ("^" + $service + '\s+([a-f0-9]{64})$')) { throw "Compose hash unavailable: $service." }
            $desiredHash = $Matches[1]
            if ($service -ceq 'james' -and $desiredHash -cne $actual.Value) {
                # Prior provisioning ran James with this temporary Compose override.
                # Accept it only when both file content and full service hash match.
                $webAdminFile = Join-Path $root 'infra/secrets/james-provision/webadmin.properties'
                if (-not (Test-Path -LiteralPath $webAdminFile -PathType Leaf)) { throw 'Running WebAdmin source unavailable; preserve and inspect.' }
                $webAdminSha = (Get-FileHash -LiteralPath $webAdminFile -Algorithm SHA256).Hash.ToLowerInvariant()
                $webRunning = @(& docker exec $m.jamesContainerId sha256sum /root/conf/webadmin.properties 2>$null)
                if ($LASTEXITCODE -ne 0 -or $webRunning.Count -ne 1 -or $webRunning[0] -notmatch '^([a-f0-9]{64})\s' -or $Matches[1] -cne $webAdminSha) {
                    throw 'Running WebAdmin differs from known source; preserve and inspect.'
                }
                $env:CORVEX_JAMES_WEBADMIN_FILE = $webAdminRelative
                $webOverride = $true
                $retryHash = @(& docker compose --profile james-transition config --hash james 2>$null)
                if ($LASTEXITCODE -ne 0 -or $retryHash.Count -ne 1 -or $retryHash[0] -notmatch '^james\s+([a-f0-9]{64})$' -or $Matches[1] -cne $actual.Value) {
                    throw 'Compose still differs with known WebAdmin source; preserve and inspect.'
                }
                $pinWebAdmin = $true
                Write-Host 'Verified existing WebAdmin source and complete James service hash; preserve that source.'
            } elseif ($desiredHash -cne $actual.Value) { throw "Compose differs from running $service." }
            $originalHashes[$service] = $actual.Value
        }
        $stage = 'compose-check'
        $composePath = Join-Path $root 'docker-compose.yml'
        if (-not (Test-Path -LiteralPath $composePath -PathType Leaf)) { throw 'Compose file missing.' }
        $bytes = [IO.File]::ReadAllBytes($composePath)
        $bom = $bytes.Length -ge 3 -and $bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191
        $utf8 = New-Object System.Text.UTF8Encoding($bom, $true)
        $text = [IO.File]::ReadAllText($composePath, $utf8)
        $nl = if ($text.Contains("`r`n")) { "`r`n" } else { "`n" }
        $pattern = '(?ms)^  (james|clamav):[ \t]*(?:#.*)?\r?\n.*?(?=^  [A-Za-z0-9_-]+:[ \t]*(?:#.*)?\r?$|^[A-Za-z0-9_-]+:|\z)'
        $blocks = @([regex]::Matches($text, $pattern))
        $jamesBlock = @($blocks | Where-Object { $_.Groups[1].Value -ceq 'james' })
        $clamBlock = @($blocks | Where-Object { $_.Groups[1].Value -ceq 'clamav' })
        if ($jamesBlock.Count -ne 1 -or $clamBlock.Count -ne 1 -or
            [regex]::Matches($jamesBlock[0].Value, '(?m)^    networks: \[james-internal\]\r?$').Count -ne 1 -or
            [regex]::Matches($jamesBlock[0].Value, '(?m)^    volumes:[ \t]*\r?\n(?:(?!^    [A-Za-z0-9_-]+:).*\r?\n)*    networks: \[james-internal\]').Count -ne 1 -or
            [regex]::Matches($clamBlock[0].Value, '(?m)^    networks: \[app\]\r?$').Count -ne 1 -or
            [regex]::Matches($text, '(?m)^networks:[ \t]*\r?$').Count -ne 1 -or $text.Contains('corvex-james-av-live')) {
            throw 'Compose layout differs or James AV already configured; preserve and inspect.'
        }
        $webAdminLine = '        source: ' + $webAdminExpression
        $webAdminPinnedLine = '        source: ' + $webAdminRelative
        if ($pinWebAdmin -and [regex]::Matches($jamesBlock[0].Value, [regex]::Escape($webAdminLine)).Count -ne 1) {
            throw 'WebAdmin Compose source layout differs; preserve and inspect.'
        }
        if (-not $Apply) {
            Write-Host 'Dry-run PASS: artifacts, original config, existing volumes, ClamAV and Compose verified.'
            Write-Host 'No files, networks, containers or mail changed.'
            return
        }
        $stage = 'stage-backup'
        $suffix = [guid]::NewGuid().ToString('N')
        $network = 'corvex-james-av-' + $suffix
        $relDir = 'infra/james/artifacts/av-live-' + $suffix
        $dir = Join-Path $root $relDir
        if (Test-Path -LiteralPath $dir) { throw 'Artifact directory exists.' }
        $backupDir = Join-Path ([IO.Path]::GetTempPath()) ('corvex-james-av-deploy-' + $suffix)
        New-Item -ItemType Directory -Path $backupDir | Out-Null
        $backup = Join-Path $backupDir 'docker-compose.yml.backup'
        [IO.File]::WriteAllBytes($backup, $bytes)
        if ((Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash -cne (Get-FileHash -LiteralPath $composePath -Algorithm SHA256).Hash) { throw 'Compose backup mismatch.' }
        New-Item -ItemType Directory -Path $dir | Out-Null
        foreach ($file in @(@($m.candidateJar,'gate.jar',$m.candidateJarSha256), @($m.parentJar,'clamav-parent.jar',$m.parentJarSha256), @($m.candidateXml,'mailetcontainer.xml',$m.candidateXmlSha256))) {
            $dst = Join-Path $dir $file[1]
            Copy-Item -LiteralPath $file[0] -Destination $dst
            if ((Get-FileHash -LiteralPath $dst -Algorithm SHA256).Hash.ToLowerInvariant() -cne $file[2]) { throw 'Staged artifact mismatch.' }
        }
        $rollbackCompose = Join-Path $backupDir 'rollback-compose.yml'
        $rollbackText = $text
        if ($pinWebAdmin) {
            if ((Get-FileHash -LiteralPath $webAdminFile -Algorithm SHA256).Hash.ToLowerInvariant() -cne $webAdminSha) { throw 'WebAdmin source changed during preflight.' }
            $rollbackText = $rollbackText.Replace($webAdminLine, $webAdminPinnedLine)
        }
        [IO.File]::WriteAllText($rollbackCompose, $rollbackText, $utf8)
        $statePath = Join-Path $backupDir 'deployment-manifest.json'
        $state = [ordered]@{ composeDir=$root; composeBackup=$backup; composeBeforeSha256=(Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash.ToLowerInvariant(); rollbackCompose=$rollbackCompose; rollbackComposeSha256=(Get-FileHash -LiteralPath $rollbackCompose -Algorithm SHA256).Hash.ToLowerInvariant(); webAdminPinned=$pinWebAdmin; webAdminRelative=$webAdminRelative; webAdminSha256=$webAdminSha; preparationManifest=$PreparationManifest; originalJamesId=$m.jamesContainerId; jamesImageId=$m.jamesImageId; clamavId=$clamId; network=$network; artifacts=$dir; originalVolumes=$volumes }
        [IO.File]::WriteAllText($statePath, ($state | ConvertTo-Json -Depth 6), [Text.Encoding]::UTF8)
        Write-Host "James AV deployment manifest: $statePath"
        Write-Host "Original volume names saved in manifest; original Compose backup: $backup"
        $newJames = $jamesBlock[0].Value.Replace('    networks: [james-internal]', ('      - type: bind' + $nl + '        source: ./' + $relDir + '/gate.jar' + $nl + '        target: /root/extensions-jars/corvex-av-gate-' + $suffix + '.jar' + $nl + '        read_only: true' + $nl + '        bind: { create_host_path: false }' + $nl + '      - type: bind' + $nl + '        source: ./' + $relDir + '/clamav-parent.jar' + $nl + '        target: /root/extensions-jars/corvex-av-parent-' + $suffix + '.jar' + $nl + '        read_only: true' + $nl + '        bind: { create_host_path: false }' + $nl + '      - type: bind' + $nl + '        source: ./' + $relDir + '/mailetcontainer.xml' + $nl + '        target: /root/conf/mailetcontainer.xml' + $nl + '        read_only: true' + $nl + '        bind: { create_host_path: false }' + $nl + '    networks: [james-internal, corvex-james-av-live]'))
        if ($pinWebAdmin) { $newJames = $newJames.Replace($webAdminLine, $webAdminPinnedLine) }
        $newClam = $clamBlock[0].Value.Replace('    networks: [app]', '    networks: [app, corvex-james-av-live]')
        $modified = $text.Replace($jamesBlock[0].Value, $newJames).Replace($clamBlock[0].Value, $newClam)
        $modified = [regex]::Replace($modified, '(?m)^networks:[ \t]*\r?$', ('networks:' + $nl + '  corvex-james-av-live:' + $nl + '    external: true' + $nl + '    name: ' + $network), 1)
        $stage = 'network'
        $runtimeChanged = $true
        & docker network create --internal --label 'org.corvex.james-av=experimental' $network
        if ($LASTEXITCODE -ne 0) { throw 'Could not create James AV network.' }
        & docker network connect --alias clamav $network $clamId
        if ($LASTEXITCODE -ne 0) { throw 'Could not connect ClamAV to James AV network.' }
        $stage = 'compose-update'
        if ((Get-FileHash -LiteralPath $composePath -Algorithm SHA256).Hash -cne (Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash) { throw 'Compose changed since backup.' }
        [IO.File]::WriteAllText($composePath, $modified, $utf8)
        $state['composeAfterSha256'] = (Get-FileHash -LiteralPath $composePath -Algorithm SHA256).Hash.ToLowerInvariant()
        [IO.File]::WriteAllText($statePath, ($state | ConvertTo-Json -Depth 6), [Text.Encoding]::UTF8)
        $saved = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { $null = @(& docker compose --profile james-transition config -q 2>$null); $code = $LASTEXITCODE } finally { $ErrorActionPreference = $saved }
        if ($code -ne 0) { throw 'Updated Compose invalid.' }
        foreach ($service in @('james','clamav')) {
            $changedHash = @(& docker compose --profile james-transition config --hash $service 2>$null)
            if ($LASTEXITCODE -ne 0 -or $changedHash.Count -ne 1 -or
                $changedHash[0] -notmatch ("^" + $service + '\s+([a-f0-9]{64})$') -or $Matches[1] -ceq $originalHashes[$service]) {
                throw "Updated Compose is not active for $service."
            }
        }
        $stage = 'recreate-james'
        if ($pinWebAdmin -and (Get-FileHash -LiteralPath $webAdminFile -Algorithm SHA256).Hash.ToLowerInvariant() -cne $webAdminSha) { throw 'WebAdmin source changed before recreation.' }
        $saved = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { & docker compose --profile james-transition up -d --no-deps --no-build --pull never --force-recreate james; $code = $LASTEXITCODE } finally { $ErrorActionPreference = $saved }
        if ($code -ne 0) { throw 'James recreation failed.' }
        $newIds = @(& docker compose --profile james-transition ps --status running -q james 2>$null)
        if ($LASTEXITCODE -ne 0 -or $newIds.Count -ne 1) { throw 'James container not running.' }
        $newJson = @(& docker inspect --format '{{json .}}' $newIds[0] 2>$null)
        if ($LASTEXITCODE -ne 0 -or $newJson.Count -ne 1) { throw 'Cannot inspect new James.' }
        $newInfo = $newJson[0] | ConvertFrom-Json
        if ($newInfo.Image -cne $m.jamesImageId) { throw 'Unexpected James image.' }
        foreach ($dst in $volumes.Keys) {
            $found = @($newInfo.Mounts | Where-Object { $_.Destination -ceq $dst -and $_.Type -ceq 'volume' -and $_.Name -ceq $volumes[$dst] })
            if ($found.Count -ne 1) { throw "Original volume not attached: $dst" }
        }
        $stage = 'startup-observation'
        Start-Sleep -Seconds 20
        $running = @(& docker inspect --format '{{.State.Status}}' $newIds[0] 2>$null)
        if ($LASTEXITCODE -ne 0 -or $running.Count -ne 1 -or $running[0] -cne 'running') { throw 'James stopped during startup.' }
        $xmlHash = @(& docker exec $newIds[0] sha256sum /root/conf/mailetcontainer.xml 2>$null)
        if ($LASTEXITCODE -ne 0 -or $xmlHash.Count -ne 1 -or $xmlHash[0] -notmatch '^([a-f0-9]{64})\s' -or $Matches[1] -cne $m.candidateXmlSha256) { throw 'Mounted mailet config mismatch.' }
        if ($pinWebAdmin) {
            $mountedWeb = @(& docker exec $newIds[0] sha256sum /root/conf/webadmin.properties 2>$null)
            if ($LASTEXITCODE -ne 0 -or $mountedWeb.Count -ne 1 -or $mountedWeb[0] -notmatch '^([a-f0-9]{64})\s' -or $Matches[1] -cne $webAdminSha) { throw 'Mounted WebAdmin config mismatch.' }
            Write-Host 'Running WebAdmin source preserved and checked.'
        }
        Write-Host 'James container running with candidate config; original James volumes preserved.'
        Write-Host 'Startup is not proof that the gate loaded, scanned, quarantined or protected JMAP/APPEND.'
        Write-Host 'Do not transfer real mail or delete backup, artifacts, volumes or network.'
    } finally {
        if ($webOverride) { [Environment]::SetEnvironmentVariable('CORVEX_JAMES_WEBADMIN_FILE', $oldWebAdmin, 'Process') }
        Pop-Location
    }
} catch {
    [Console]::Error.WriteLine("ERROR at $stage. Deployment manifest: $statePath. Runtime or configuration may have changed: $runtimeChanged")
    [Console]::Error.WriteLine('Do not retry or restore automatically; preserve all files, volumes and mail.')
    throw
}
