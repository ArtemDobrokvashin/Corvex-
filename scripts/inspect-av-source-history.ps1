<# Read-only AV source inventory. Prints file paths and git blob hashes, never contents. #>
[CmdletBinding()]
param([string]$ComposeDir = (Get-Location).Path)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$stage = 'initialization'
try {
    Push-Location -LiteralPath $ComposeDir
    try {
        $stage = 'candidate-tree'
        $root = (Get-Location).Path.TrimEnd([char[]]@('\', '/'))
        $prefix = 'services/mail-antivirus-service/src/'
        $tree = @(& git ls-tree -r eda8c7d -- $prefix)
        if ($LASTEXITCODE -ne 0 -or $tree.Count -eq 0) { throw 'Candidate tree unavailable.' }
        $candidate = @{}
        foreach ($line in $tree) {
            if ($line -notmatch '^(100644|100755) blob ([a-f0-9]{40})\t(services/mail-antivirus-service/src/[A-Za-z0-9._/-]+)$') { throw 'Invalid tree entry.' }
            $candidate[$Matches[3]] = $Matches[2]
        }
        $src = Join-Path $root ($prefix.TrimEnd('/'))
        if (-not (Test-Path -LiteralPath $src -PathType Container)) { throw 'Source directory unavailable.' }
        $stage = 'local-tree'
        $local = @{}
        $items = @((Get-Item -LiteralPath $src -Force)) + @(Get-ChildItem -LiteralPath $src -Recurse -Force)
        foreach ($item in $items) {
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Reparse point detected; stop.' }
            if (-not $item.PSIsContainer) {
                $path = $item.FullName.Substring($root.Length + 1).Replace('\', '/')
                if ($path -notmatch '^services/mail-antivirus-service/src/[A-Za-z0-9._/-]+$') { throw 'Unsupported local path.' }
                $hash = @(& git hash-object -- $item.FullName)
                if ($LASTEXITCODE -ne 0 -or $hash.Count -ne 1 -or $hash[0] -notmatch '^[a-f0-9]{40}$') { throw 'Cannot hash source.' }
                $local[$path] = $hash[0]
            }
        }
        Write-Host '=== AV source history inventory (read only) ==='
        foreach ($path in @($candidate.Keys | Sort-Object)) {
            if (-not $local.ContainsKey($path)) {
                Write-Host "MISSING $path"
                continue
            }
            $oid = $local[$path]
            if ($oid -ceq $candidate[$path]) { continue }
            $stage = 'history'
            # History for this file on the candidate's ancestry, including baseline.
            $commits = @(& git log '--format=%H' eda8c7d -- $path)
            if ($LASTEXITCODE -ne 0) { throw 'Cannot read source history.' }
            $match = $null
            foreach ($commit in $commits) {
                if ($commit -notmatch '^[a-f0-9]{40}$') { throw 'Invalid history entry.' }
                $entry = @(& git ls-tree $commit -- $path)
                if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect history.' }
                if ($entry.Count -eq 1 -and $entry[0] -match '^\d+ blob ([a-f0-9]{40})\t' -and $Matches[1] -ceq $oid) {
                    $match = $commit.Substring(0, 12)
                    break
                }
            }
            if ($null -eq $match) { Write-Host "UNKNOWN $path blob=$oid" }
            else { Write-Host "HISTORY $path blob=$oid commit=$match" }
        }
        foreach ($path in @($local.Keys | Sort-Object)) {
            if (-not $candidate.ContainsKey($path)) { Write-Host "EXTRA $path blob=$($local[$path])" }
        }
        Write-Host 'Unlisted candidate source files match. No source contents, environment or message data printed.'
        Write-Host 'Read-only inventory; no files, tags, containers or queues changed.'
    } finally { Pop-Location }
} catch {
    [Console]::Error.WriteLine("ERROR: source history inventory failed at $stage; no changes made.")
    exit 2
}
