<# Read only: selected clamd.conf values from the existing Compose container.
   This does not inspect the running daemon or validate antivirus coverage. #>
[CmdletBinding()]
param(
    [string]$ComposeDir = (Get-Location).Path,
    [string]$Project = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$booleanKeys = @('AlertExceedsMax', 'ScanMail')
$numberKeys = @('MaxScanSize', 'MaxFileSize', 'MaxRecursion', 'MaxFiles', 'MaxScanTime', 'StreamMaxLength')
$keys = @($booleanKeys + $numberKeys)
$values = @{}
$seen = @{}
foreach ($key in $keys) { $values[$key] = $null }

try {
    if (-not (Test-Path -LiteralPath $ComposeDir -PathType Container)) {
        throw 'Каталог Compose не найден.'
    }
    Push-Location -LiteralPath $ComposeDir
    try {
        $composeArgs = @('compose')
        if (-not [string]::IsNullOrWhiteSpace($Project)) { $composeArgs += @('-p', $Project) }
        $composeArgs += @('exec', '-T', 'clamav', 'cat', '/etc/clamav/clamd.conf')
        # Windows PowerShell 5 can treat native stderr as an exception. Do not
        # print Docker stderr, which might contain deployment-specific details.
        $oldPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $lines = @(& docker @composeArgs 2>$null)
            $exitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $oldPreference
        }
    } finally {
        Pop-Location
    }
    if ($exitCode -ne 0) { throw "Не удалось прочитать конфигурацию (Docker exit $exitCode)." }
    $totalLength = 0
    foreach ($item in $lines) {
        $line = [string]$item
        $totalLength += $line.Length
        if ($totalLength -gt 262144) { throw 'Конфигурация слишком велика.' }
        $line = ($line -split '#', 2)[0].Trim()
        if ($line.Length -eq 0) { continue }
        $parts = @($line -split '\s+')
        $key = $parts[0]
        if (-not ($keys -ccontains $key)) { continue }
        if ($seen.ContainsKey($key) -or $parts.Count -ne 2) {
            throw "Повторная или некорректная настройка $key."
        }
        $seen[$key] = $true
        $value = $parts[1]
        if ($booleanKeys -ccontains $key) {
            if ($value -notmatch '^(?i:yes|no|true|false)$') { throw "Неизвестное значение настройки $key." }
        } elseif ($value -notmatch '^[0-9]+(?:[kKmMgG](?:[bB])?)?$') {
            throw "Неизвестное значение настройки $key."
        }
        $values[$key] = $value
    }
    Write-Host 'Только явно заданные значения в /etc/clamav/clamd.conf:'
    foreach ($key in $keys) {
        if ($null -eq $values[$key]) { $value = 'не задано явно' } else { $value = $values[$key] }
        Write-Host "${key}: $value"
    }
    Write-Host 'Отсутствие строки НЕ означает отключение: применяются значения по умолчанию.'
    Write-Host 'Файл не подтверждает runtime-настройки, полное сканирование или готовность AV.'
    Write-Host 'Ничего не изменено; почта и содержимое очередей не читались.'
} catch {
    # Only our own validated messages or a generic startup error are emitted.
    $message = $_.Exception.Message
    if ($message -match '^(Каталог Compose не найден\.|Не удалось прочитать конфигурацию \(Docker exit [0-9]+\)\.|Конфигурация слишком велика\.|Повторная или некорректная настройка (AlertExceedsMax|ScanMail|MaxScanSize|MaxFileSize|MaxRecursion|MaxFiles|MaxScanTime|StreamMaxLength)\.|Неизвестное значение настройки (AlertExceedsMax|ScanMail|MaxScanSize|MaxFileSize|MaxRecursion|MaxFiles|MaxScanTime|StreamMaxLength)\.)$') {
        [Console]::Error.WriteLine("ERROR: $message")
    } else {
        [Console]::Error.WriteLine('ERROR: невозможно выполнить проверку конфигурации.')
    }
    exit 2
}
