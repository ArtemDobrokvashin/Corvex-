<# Prepare immutable James AV artifacts and save current mailet config. No deployment. #>
[CmdletBinding()]
param([string]$ComposeDir = (Get-Location).Path, [switch]$ConfirmLaptop)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$stage = 'initialization'
$work = Join-Path ([IO.Path]::GetTempPath()) ('corvex-james-av-' + [guid]::NewGuid().ToString('N'))
try {
    foreach ($name in @('git', 'tar', 'docker', 'mvn')) {
        if (-not (Get-Command $name -ErrorAction SilentlyContinue)) { throw "Required command missing: $name" }
    }
    Push-Location -LiteralPath $ComposeDir
    try {
        $stage = 'container'
        $ids = @(& docker compose --profile james-transition ps --status running -q james 2>$null)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or $ids[0] -notmatch '^[a-f0-9]{12,64}$') { throw 'Expected one running James container.' }
        $container = $ids[0]
        $image = @(& docker inspect --format '{{.Image}}' $container 2>$null)
        if ($LASTEXITCODE -ne 0 -or $image.Count -ne 1 -or $image[0] -notmatch '^sha256:[a-f0-9]{64}$') { throw 'Cannot identify James image.' }
        $reference = @(& docker inspect --format '{{.Config.Image}}' $container 2>$null)
        if ($LASTEXITCODE -ne 0 -or $reference.Count -ne 1 -or $reference[0] -cne 'apache/james:postgres-3.9.1') { throw 'Unexpected James image reference.' }
        $mountJson = @(& docker inspect --format '{{json .Mounts}}' $container 2>$null)
        if ($LASTEXITCODE -ne 0 -or $mountJson.Count -ne 1) { throw 'Cannot inspect mounts.' }
        $mounts = @(ConvertFrom-Json -InputObject $mountJson[0])
        if ($mounts.Count -eq 1 -and $mounts[0] -is [array]) { $mounts = @($mounts[0]) }
        foreach ($mount in $mounts) {
            if ($mount.Destination -ceq '/root' -or $mount.Destination -ceq '/root/conf' -or
                $mount.Destination -ceq '/root/conf/mailetcontainer.xml' -or $mount.Destination -like '/root/extensions-jars*') {
                throw 'Custom mailet config or extension mount already exists; inspect before enabling.'
            }
        }
        $stage = 'archive'
        if (Test-Path -LiteralPath $work) { throw 'Workspace already exists.' }
        New-Item -ItemType Directory -Path $work | Out-Null
        $commit = @(& git rev-parse '9e33cfa^{commit}' 2>$null)
        if ($LASTEXITCODE -ne 0 -or $commit.Count -ne 1 -or $commit[0] -notmatch '^[a-f0-9]{40}$') { throw 'Candidate commit unavailable.' }
        $fullCommit = $commit[0]
        $archive = Join-Path $work 'candidate.tar'
        & git archive '--format=tar' "--output=$archive" $fullCommit 'experiments/james-av-gate'
        if ($LASTEXITCODE -ne 0) { throw 'Archive creation failed.' }
        & tar -xf $archive -C $work
        if ($LASTEXITCODE -ne 0) { throw 'Archive extraction failed.' }
        $module = Join-Path $work 'experiments/james-av-gate'
        $stage = 'save-running-config'
        $backup = Join-Path $work 'original-mailetcontainer.xml'
        $savedPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & docker cp "${container}:/root/conf/mailetcontainer.xml" $backup
            $code = $LASTEXITCODE
        } finally { $ErrorActionPreference = $savedPreference }
        if ($code -ne 0 -or -not (Test-Path -LiteralPath $backup -PathType Leaf)) { throw 'Could not save running mailet configuration.' }
        $before = (Get-FileHash -LiteralPath $backup -Algorithm SHA256).Hash.ToLowerInvariant()
        $stage = 'maven-package'
        & (Join-Path $module 'test-real-dependencies.ps1')
        $jar = Join-Path $module 'target/james-av-gate-0.0.1-SNAPSHOT.jar'
        $parent = Join-Path $module 'target/extension-deps/apache-james-clamav-3.9.1.jar'
        $xml = Join-Path $module 'mailetcontainer.candidate.xml'
        foreach ($path in @($jar, $parent, $xml)) {
            if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Candidate artifact missing.' }
        }
        $stage = 'recheck'
        $idsAfter = @(& docker compose --profile james-transition ps --status running -q james 2>$null)
        if ($LASTEXITCODE -ne 0 -or $idsAfter.Count -ne 1 -or $idsAfter[0] -cne $container) { throw 'James container changed during preparation.' }
        $confirm = Join-Path $work 'original-mailetcontainer-check.xml'
        $savedPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & docker cp "${container}:/root/conf/mailetcontainer.xml" $confirm
            $code = $LASTEXITCODE
        } finally { $ErrorActionPreference = $savedPreference }
        if ($code -ne 0 -or (Get-FileHash -LiteralPath $confirm -Algorithm SHA256).Hash.ToLowerInvariant() -cne $before) { throw 'Running mailet config changed during preparation.' }
        $state = [ordered]@{
            candidateCommit = $fullCommit; composeDir = (Get-Location).Path; jamesContainerId = $container; jamesImageId = $image[0]
            originalConfig = $backup; originalConfigSha256 = $before
            candidateXml = $xml; candidateXmlSha256 = (Get-FileHash -LiteralPath $xml -Algorithm SHA256).Hash.ToLowerInvariant()
            candidateJar = $jar; candidateJarSha256 = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant()
            parentJar = $parent; parentJarSha256 = (Get-FileHash -LiteralPath $parent -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        $manifest = Join-Path $work 'preparation-manifest.json'
        [IO.File]::WriteAllText($manifest, ($state | ConvertTo-Json), [Text.Encoding]::UTF8)
        Write-Host "James AV preparation manifest: $manifest"
        Write-Host "Original mailet configuration SHA256: $before"
        $upstreamSample = '2158891b662abcd527f56f24a92b10d5404d875fad6e83ed3d57135a3386884f'
        if ($before -ceq $upstreamSample) { Write-Host 'Running config matches upstream 3.9.1 sample byte-for-byte.' }
        else { Write-Host 'Running config differs from upstream 3.9.1 sample; inspect differences before replacement.' }
        Write-Host 'Package and backup prepared. James, ClamAV, networks, queues and mail were not changed.'
        Write-Host 'Not live AV: classloading, gate, incomplete scans, quarantine and rollback remain unverified.'
    } finally { Pop-Location }
} catch {
    [Console]::Error.WriteLine("ERROR: James AV preparation failed at $stage. Workspace: $work")
    throw
}
