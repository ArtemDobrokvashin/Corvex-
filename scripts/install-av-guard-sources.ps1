<# Install only known-base AV source changes after backup. No containers or images are changed. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Manifest,
    [string]$ComposeDir = (Get-Location).Path,
    [switch]$ConfirmLaptop,
    [switch]$Apply
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLaptop) { throw 'FAIL: -ConfirmLaptop required.' }
$base = '3e208a549fbfd7bb02f7d3ee8d5fc4489f3dd134'
$candidate = 'eda8c7d'
$prefix = 'services/mail-antivirus-service/'
$srcPrefix = $prefix + 'src/'
$tag = [guid]::NewGuid().ToString('N')
$work = Join-Path ([IO.Path]::GetTempPath()) ('corvex-av-source-backup-' + $tag)
$stage = 'preflight'
$startedWrites = $false

function Read-Tree([string]$Commit) {
    $entries = @(& git ls-tree -r $Commit -- $srcPrefix)
    if ($LASTEXITCODE -ne 0 -or $entries.Count -eq 0) { throw 'Cannot read source tree.' }
    $result = @{}
    foreach ($entry in $entries) {
        if ($entry -notmatch '^(100644|100755) blob ([a-f0-9]{40})\t(services/mail-antivirus-service/src/[A-Za-z0-9._/-]+)$') {
            throw 'Unexpected source tree entry.'
        }
        $oid = $Matches[2]
        $path = $Matches[3]
        if (($path -split '/') -contains '..' -or $result.ContainsKey($path)) { throw 'Unsafe source tree path.' }
        $result[$path] = $oid
    }
    return $result
}

function File-Oid([string]$FilePath) {
    $oid = @(& git hash-object -- $FilePath)
    if ($LASTEXITCODE -ne 0 -or $oid.Count -ne 1 -or $oid[0] -notmatch '^[a-f0-9]{40}$') {
        throw 'Cannot hash source file.'
    }
    return $oid[0]
}

try {
    $stage = 'manifest'
    $m = Get-Content -Raw -LiteralPath $Manifest | ConvertFrom-Json
    if ($m.candidateTag -notmatch '^corvex-av-guard:candidate-[a-f0-9]{32}$' -or
        $m.candidateImageId -notmatch '^sha256:[a-f0-9]{64}$' -or
        $m.previousImageId -notmatch '^sha256:[a-f0-9]{64}$' -or
        $m.previousContainerId -notmatch '^[a-f0-9]{12,64}$') { throw 'Invalid manifest.' }
    Push-Location -LiteralPath $ComposeDir
    try {
        $repo = (Get-Location).Path.TrimEnd([char[]]@('\', '/'))
        $stage = 'candidate'
        $commit = @(& git rev-parse "${candidate}^{commit}")
        if ($LASTEXITCODE -ne 0 -or $commit.Count -ne 1 -or $commit[0] -cne $m.candidateCommit) { throw 'Candidate mismatch.' }
        $fullCommit = $commit[0]
        $image = @(& docker image inspect --format '{{.Id}}' $m.candidateTag 2>$null)
        if ($LASTEXITCODE -ne 0 -or $image.Count -ne 1 -or $image[0] -cne $m.candidateImageId) { throw 'Candidate image unavailable.' }
        $oldImage = @(& docker image inspect --format '{{.Id}}' $m.previousImageId 2>$null)
        if ($LASTEXITCODE -ne 0 -or $oldImage.Count -ne 1 -or $oldImage[0] -cne $m.previousImageId) { throw 'Previous image unavailable.' }
        $ids = @(& docker compose ps --status running -q mail-antivirus-service 2>$null)
        if ($LASTEXITCODE -ne 0 -or $ids.Count -ne 1 -or $ids[0] -cne $m.previousContainerId) { throw 'AV container changed.' }
        $stage = 'source-validation'
        foreach ($relative in @(($prefix + 'Dockerfile'), ($prefix + 'pom.xml'), ($prefix + 'maven-settings.xml'))) {
            if (-not (Test-Path -LiteralPath $relative -PathType Leaf)) { throw "Missing required file: $relative" }
            $wanted = @(& git rev-parse "${candidate}:${relative}")
            if ($LASTEXITCODE -ne 0 -or $wanted.Count -ne 1 -or (File-Oid $relative) -cne $wanted[0]) {
                throw "Build file differs; preserve and inspect: $relative"
            }
        }
        $wantedTree = Read-Tree $candidate
        $baseTree = Read-Tree $base
        $srcRoot = Join-Path $repo ($srcPrefix.TrimEnd('/'))
        if (-not (Test-Path -LiteralPath $srcRoot -PathType Container)) { throw 'Source directory missing.' }
        $items = @((Get-Item -LiteralPath $srcRoot -Force)) + @(Get-ChildItem -LiteralPath $srcRoot -Recurse -Force)
        foreach ($item in $items) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Reparse points are not supported.' }
            if (-not $item.PSIsContainer) {
                $relative = $item.FullName.Substring($repo.Length + 1).Replace('\', '/')
                if (-not $wantedTree.ContainsKey($relative)) { throw "Extra source file; preserve and inspect: $relative" }
            }
        }
        $plan = @()
        foreach ($path in @($wantedTree.Keys | Sort-Object)) {
            $local = Join-Path $repo $path
            $exists = Test-Path -LiteralPath $local -PathType Leaf
            if ($exists) {
                $current = File-Oid $local
                if ($current -ceq $wantedTree[$path]) { continue }
                if (-not $baseTree.ContainsKey($path) -or $current -cne $baseTree[$path]) {
                    throw "Source has independent edits; preserve and inspect: $path"
                }
            } else {
                if ($baseTree.ContainsKey($path)) { throw "Existing source missing; preserve and inspect: $path" }
                $current = $null
            }
            $plan += [pscustomobject]@{ path = $path; before = $current; after = $wantedTree[$path] }
        }
        if (-not $Apply) {
            Write-Host "Dry run: $($plan.Count) AV source files would be updated after backup."
            foreach ($entry in $plan) { Write-Host "Would update: $($entry.path)" }
            Write-Host 'No files or containers changed.'
            return
        }
        $stage = 'backup-and-stage'
        if (Test-Path -LiteralPath $work) { throw 'Backup directory already exists.' }
        New-Item -ItemType Directory -Path $work | Out-Null
        $archive = Join-Path $work 'candidate-src.tar'
        & git archive '--format=tar' "--output=$archive" $fullCommit $srcPrefix
        if ($LASTEXITCODE -ne 0) { throw 'Cannot archive candidate sources.' }
        & tar -xf $archive -C $work
        if ($LASTEXITCODE -ne 0) { throw 'Cannot extract candidate sources.' }
        foreach ($entry in $plan) {
            $staged = Join-Path $work $entry.path
            if (-not (Test-Path -LiteralPath $staged -PathType Leaf) -or (File-Oid $staged) -cne $entry.after) {
                throw "Staged source mismatch: $($entry.path)"
            }
            if ($null -ne $entry.before) {
                $source = Join-Path $repo $entry.path
                if ((File-Oid $source) -cne $entry.before) { throw "Source changed during preflight: $($entry.path)" }
                $backup = Join-Path (Join-Path $work 'originals') $entry.path
                $parent = Split-Path -Parent $backup
                New-Item -ItemType Directory -Path $parent -Force | Out-Null
                Copy-Item -LiteralPath $source -Destination $backup
                if ((File-Oid $backup) -cne $entry.before) { throw "Source backup mismatch: $($entry.path)" }
            }
        }
        $record = [ordered]@{ candidateCommit = $commit[0]; baseCommit = $base; files = @($plan); composeDir = $repo }
        $recordPath = Join-Path $work 'source-backup-manifest.json'
        [IO.File]::WriteAllText($recordPath, ($record | ConvertTo-Json -Depth 5), [Text.Encoding]::UTF8)
        $stage = 'source-install'
        foreach ($entry in $plan) {
            $local = Join-Path $repo $entry.path
            if ($null -eq $entry.before) {
                if (Test-Path -LiteralPath $local) { throw "New file appeared; preserve it: $($entry.path)" }
            } elseif ((File-Oid $local) -cne $entry.before) { throw "Source changed; preserve it: $($entry.path)" }
            $parent = Split-Path -Parent $local
            New-Item -ItemType Directory -Path $parent -Force | Out-Null
            $startedWrites = $true
            Copy-Item -LiteralPath (Join-Path $work $entry.path) -Destination $local -Force
            if ((File-Oid $local) -cne $entry.after) { throw "Installed source mismatch: $($entry.path)" }
            Write-Host "Updated: $($entry.path)"
        }
        Write-Host "Source files updated: $($plan.Count)"
        Write-Host "Backup and manifest: $recordPath"
        Write-Host 'No container, image tag, queue, configuration or frontend was changed.'
        Write-Host 'Candidate AV image is not deployed; retain both images and the backup.'
    } finally { Pop-Location }
} catch {
    Write-Host "ERROR at $stage; backup directory (if created): $work"
    if ($startedWrites) { Write-Host 'Some source files may have changed. Keep the backup; do not retry blindly.' }
    throw
}
