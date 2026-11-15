<# Remove stopped synthetic James lab containers and their unused networks only.
   Never removes volumes, images, data directories, keys, or Corvex services.
   Review the printed plan; execution requires -Execute. #>
param([switch]$Execute)
$ErrorActionPreference = 'Stop'

function Is-SyntheticProject([string]$Name) {
    return $Name -cmatch '^corvex-jwt-lab-[0-9a-f]{12}$' -or
           $Name -ceq 'corvex-james-pg-lab' -or $Name -ceq 'corvex-james-lab'
}

$containerIds = @(& docker ps -aq --no-trunc)
if ($LASTEXITCODE -ne 0) { throw 'FAIL: cannot list containers' }
$networkIds = @(& docker network ls -q --no-trunc)
if ($LASTEXITCODE -ne 0) { throw 'FAIL: cannot list networks' }
$containers = @()
foreach ($id in $containerIds) {
    $raw = & docker inspect $id
    if ($LASTEXITCODE -ne 0) { throw "FAIL: cannot inspect container $id" }
    $item = ($raw | Out-String | ConvertFrom-Json)[0]
    $project = $item.Config.Labels.'com.docker.compose.project'
    if (Is-SyntheticProject $project) {
        if ($item.State.Status -notin @('created', 'exited', 'dead')) {
            throw "FAIL: synthetic container $($item.Name) is $($item.State.Status); nothing removed"
        }
        $containers += $item
    }
}
$selectedIds = @{}
foreach ($c in $containers) { $selectedIds[$c.Id] = $true }
$networks = @()
foreach ($id in $networkIds) {
    $raw = & docker network inspect $id
    if ($LASTEXITCODE -ne 0) { throw "FAIL: cannot inspect network $id" }
    $n = ($raw | Out-String | ConvertFrom-Json)[0]
    $project = $n.Labels.'com.docker.compose.project'
    if (Is-SyntheticProject $project) {
        foreach ($member in @($n.Containers.PSObject.Properties)) {
            if (-not $selectedIds.ContainsKey($member.Name)) {
                throw "FAIL: network $($n.Name) has a non-selected endpoint; nothing removed"
            }
        }
        $networks += $n
    }
}
Write-Host 'Stopped synthetic containers:'
foreach ($c in $containers) { Write-Host "  $($c.Name) [$($c.State.Status)]" }
Write-Host 'Synthetic networks:'
foreach ($n in $networks) { Write-Host "  $($n.Name)" }
Write-Host 'Volumes, images, local keys and Corvex services are NOT removed.'
if (-not $Execute) { Write-Host 'Dry run only. Use -Execute after reviewing the plan.'; exit 0 }
foreach ($c in $containers) {
    & docker rm $c.Id
    if ($LASTEXITCODE -ne 0) { throw "FAIL: removing container $($c.Name); some earlier removals may have completed" }
}
foreach ($n in $networks) {
    & docker network rm $n.Id
    if ($LASTEXITCODE -ne 0) { throw "FAIL: removing network $($n.Name); some earlier removals may have completed" }
}
Write-Host 'PASS: selected stopped synthetic containers and networks removed; volumes preserved.'
