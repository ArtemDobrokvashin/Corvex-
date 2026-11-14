<# Build a candidate AV image from an immutable Git commit, without deploying it. #>
[CmdletBinding()]
param(
    [string]$ComposeDir = (Get-Location).Path,
    [switch]$ConfirmLaptop
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop is required.' }
$commit = 'eda8c7d'
$tagSuffix = [guid]::NewGuid().ToString('N')
$tag = 'corvex-av-guard:candidate-' + $tagSuffix
$work = Join-Path ([IO.Path]::GetTempPath()) ('corvex-av-image-' + $tagSuffix)
$archive = Join-Path $work 'sources.tar'
try {
    foreach ($cmd in @('docker', 'git', 'tar')) {
        if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { throw "FAIL: $cmd not found." }
    }
    Push-Location -LiteralPath $ComposeDir
    try {
        $commitIds = @(& git rev-parse "${commit}^{commit}")
        if ($LASTEXITCODE -ne 0 -or $commitIds.Count -ne 1 -or $commitIds[0] -notmatch '^[a-f0-9]{40}$') {
            throw 'FAIL: candidate commit is unavailable; fetch it first.'
        }
        $fullCommit = $commitIds[0]
        $ids = @(& docker compose ps --status running -q mail-antivirus-service)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or $ids[0] -notmatch '^[a-f0-9]{12,64}$') {
            throw 'FAIL: expected one running mail-antivirus container.'
        }
        $originalContainer = $ids[0]
        $oldImageOutput = @(& docker inspect --format '{{.Image}}' $originalContainer)
        if ($LASTEXITCODE -ne 0 -or $oldImageOutput.Count -ne 1 -or $oldImageOutput[0] -notmatch '^sha256:[a-f0-9]{64}$') {
            throw 'FAIL: unable to identify current AV image.'
        }
        $oldImage = $oldImageOutput[0]
        if (Test-Path -LiteralPath $work) { throw 'FAIL: temporary work path already exists.' }
        New-Item -ItemType Directory -Path $work -ErrorAction Stop | Out-Null
        & git archive '--format=tar' "--output=$archive" $fullCommit 'services/mail-antivirus-service'
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: candidate source archive.' }
        & tar -xf $archive -C $work
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: candidate source extraction.' }
        $context = Join-Path $work 'services/mail-antivirus-service'
        if (-not (Test-Path -LiteralPath (Join-Path $context 'Dockerfile') -PathType Leaf)) {
            throw 'FAIL: candidate Dockerfile missing.'
        }
        # Check the randomly generated tag is not already in use.
        $existing = @(& docker image ls -q --filter "reference=$tag")
        if ($LASTEXITCODE -ne 0 -or $existing.Count -ne 0) { throw 'FAIL: candidate tag is unavailable.' }
        # Unique local tag: no Compose build, deployment, container or volume mutation.
        & docker build --label "org.corvex.av-candidate.commit=$($commitIds[0])" --tag $tag $context
        if ($LASTEXITCODE -ne 0) { throw "FAIL: candidate build. Work directory: $work" }
        $newOutput = @(& docker image inspect --format '{{.Id}}' $tag)
        if ($LASTEXITCODE -ne 0 -or $newOutput.Count -ne 1 -or $newOutput[0] -notmatch '^sha256:[a-f0-9]{64}$') {
            throw 'FAIL: unable to identify candidate image.'
        }
        $oldOutput = @(& docker image inspect --format '{{.Id}}' $oldImage)
        if ($LASTEXITCODE -ne 0 -or $oldOutput.Count -ne 1 -or $oldOutput[0] -cne $oldImage) {
            throw 'FAIL: original AV image no longer visible; do not deploy.'
        }
        $current = @(& docker compose ps --status running -q mail-antivirus-service)
        if ($LASTEXITCODE -ne 0 -or $current.Count -ne 1 -or $current[0] -cne $originalContainer) {
            throw 'FAIL: AV container changed during build; candidate not deployed.'
        }
        $manifest = [ordered]@{
            candidateCommit = $commitIds[0]
            candidateTag = $tag
            candidateImageId = $newOutput[0]
            previousImageId = $oldImage
            previousContainerId = $originalContainer
            composeDir = (Get-Location).Path
        }
        $manifestPath = Join-Path $work 'candidate-manifest.json'
        [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json), [Text.Encoding]::UTF8)
        Write-Host 'Candidate image built locally; no service was deployed or restarted.'
        Write-Host "Candidate tag: $tag"
        Write-Host "Candidate image: $($newOutput[0])"
        Write-Host "Previous AV image: $oldImage"
        Write-Host "Manifest: $manifestPath"
        Write-Host 'Keep both images and the manifest; this is not live AV, rollback or cutover acceptance.'
    } finally { Pop-Location }
} catch {
    Write-Host "Build did not deploy changes. Work directory (if created): $work"
    throw
}
