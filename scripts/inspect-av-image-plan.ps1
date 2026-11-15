<# Read-only image and Compose comparison; does not deploy or change tags. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Manifest,
    [string]$ComposeDir = (Get-Location).Path
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$stage = 'manifest'
try {
    if (-not (Test-Path -LiteralPath $Manifest -PathType Leaf)) { throw 'Manifest unavailable.' }
    $m = Get-Content -Raw -LiteralPath $Manifest | ConvertFrom-Json
    if ($m.candidateTag -notmatch '^corvex-av-guard:candidate-[a-f0-9]{32}$' -or
        $m.candidateImageId -notmatch '^sha256:[a-f0-9]{64}$' -or
        $m.previousImageId -notmatch '^sha256:[a-f0-9]{64}$' -or
        $m.previousContainerId -notmatch '^[a-f0-9]{12,64}$' -or
        $m.candidateCommit -notmatch '^[a-f0-9]{40}$') { throw 'Invalid manifest.' }
    Push-Location -LiteralPath $ComposeDir
    try {
        $stage = 'candidate-image'
        $expectedCommit = @(& git rev-parse 'eda8c7d^{commit}' 2>$null)
        if ($LASTEXITCODE -ne 0 -or $expectedCommit.Count -ne 1 -or $expectedCommit[0] -cne $m.candidateCommit) {
            throw 'Candidate commit mismatch.'
        }
        $newImage = @(& docker image inspect --format '{{.Id}}' $m.candidateTag 2>$null)
        if ($LASTEXITCODE -ne 0 -or $newImage.Count -ne 1 -or $newImage[0] -cne $m.candidateImageId) {
            throw 'Candidate image mismatch.'
        }
        $candidateLabelsJson = @(& docker image inspect --format '{{json .Config.Labels}}' $m.candidateTag 2>$null)
        if ($LASTEXITCODE -ne 0 -or $candidateLabelsJson.Count -ne 1) { throw 'Candidate label unavailable.' }
        $candidateLabels = $candidateLabelsJson[0] | ConvertFrom-Json
        $commitLabel = $candidateLabels.PSObject.Properties['org.corvex.av-candidate.commit']
        if ($null -eq $commitLabel -or $commitLabel.Value -cne $m.candidateCommit) { throw 'Candidate label mismatch.' }
        $oldImage = @(& docker image inspect --format '{{.Id}}' $m.previousImageId 2>$null)
        if ($LASTEXITCODE -ne 0 -or $oldImage.Count -ne 1 -or $oldImage[0] -cne $m.previousImageId) {
            throw 'Previous image unavailable.'
        }
        $stage = 'container-identity'
        $ids = @(& docker compose ps --status running -q mail-antivirus-service 2>$null)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or $ids[0] -cne $m.previousContainerId) {
            throw 'AV container changed.'
        }
        $image = @(& docker inspect --format '{{.Image}}' $ids[0] 2>$null)
        if ($LASTEXITCODE -ne 0 -or $image.Count -ne 1 -or $image[0] -cne $m.previousImageId) {
            throw 'AV image changed.'
        }
        $ref = @(& docker inspect --format '{{.Config.Image}}' $ids[0] 2>$null)
        if ($LASTEXITCODE -ne 0 -or $ref.Count -ne 1 -or $ref[0] -notmatch '^[a-z0-9][a-z0-9._/-]*(?::[a-zA-Z0-9_.-]+)?$') {
            throw 'Unsupported image reference.'
        }
        $labelsJson = @(& docker inspect --format '{{json .Config.Labels}}' $ids[0] 2>$null)
        if ($LASTEXITCODE -ne 0 -or $labelsJson.Count -ne 1) { throw 'Container labels unavailable.' }
        $labels = $labelsJson[0] | ConvertFrom-Json
        $hashProp = $labels.PSObject.Properties['com.docker.compose.config-hash']
        $projectProp = $labels.PSObject.Properties['com.docker.compose.project']
        if ($null -eq $hashProp -or $null -eq $projectProp -or $hashProp.Value -notmatch '^[a-f0-9]{64}$' -or
            $projectProp.Value -notmatch '^[a-z0-9][a-z0-9_-]*$') { throw 'Missing Compose labels.' }
        $stage = 'compose-image'
        $configuredImages = @(& docker compose config --images mail-antivirus-service 2>$null)
        if ($LASTEXITCODE -ne 0 -or $configuredImages.Count -eq 0) {
            throw 'Compose image references unavailable.'
        }
        # Compose includes dependency images for a selected service. Compare
        # membership without printing unrelated service image references.
        $imagePresent = $false
        foreach ($configured in $configuredImages) {
            if ($configured -ceq $ref[0] -or ($configured + ':latest') -ceq $ref[0] -or
                $configured -ceq ($ref[0] + ':latest')) { $imagePresent = $true }
        }
        $stage = 'compose-hash'
        $configuredHash = @(& docker compose config --hash mail-antivirus-service 2>$null)
        if ($LASTEXITCODE -ne 0 -or $configuredHash.Count -ne 1 -or
            $configuredHash[0] -notmatch '^mail-antivirus-service\s+([a-f0-9]{64})$') {
            throw 'Compose hash unavailable.'
        }
        $expectedHash = $Matches[1]
        $projectImage = $projectProp.Value + '-mail-antivirus-service'
        Write-Host '=== AV image and Compose preflight (read only) ==='
        Write-Host 'Candidate image and previous image: available and match manifest'
        Write-Host 'Running container: unchanged since build'
        Write-Host "Running image reference: $($ref[0])"
        if ($imagePresent) { Write-Host 'Running image reference appears in selected Compose images: YES' }
        else { Write-Host 'Running image reference appears in selected Compose images: NO' }
        if ($hashProp.Value -ceq $expectedHash) { Write-Host 'Compose config hash: MATCH' } else { Write-Host 'Compose config hash: DIFFERENT' }
        if ($ref[0] -ceq $projectImage -or $ref[0] -ceq ($projectImage + ':latest')) {
            Write-Host 'Image reference is the default project-local service tag.'
        } else { Write-Host 'Image reference is not the default project-local service tag.' }
        Write-Host 'No tags or containers changed. This is not a deploy, live AV test or rollback proof.'
        Write-Host 'Queue state can change at any time; obtain a new snapshot before any deployment.'
    } finally { Pop-Location }
} catch {
    [Console]::Error.WriteLine("ERROR: image preflight failed at $stage; no changes made.")
    exit 2
}
