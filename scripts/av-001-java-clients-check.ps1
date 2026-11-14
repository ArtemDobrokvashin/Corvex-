#===============================================================================
# AV-001: Проверка трёх Java-клиентов ClamAV с настоящим clamd
#
# Вызывает каждый из трёх изменённых ClamAvClient (mail-antivirus-service,
# mail-storage-service, chat-file-service) через настоящий clamd в сети app.
# Szenarien:
#   1. Чистый поток     -> CLEAN
#   2. EICAR test string -> INFECTED
#   3. Недоступный сканер (контролируемый порт 13310) -> ERROR
#
# Требования:
#   - Docker Compose уже запущен (сервисы в сети app) — docker compose up НЕ запускается
#   - Java, Maven доступны на хосте (Windows)
#   - Не пересоздаёт контейнеры, не публикует порты, не отправляет тест в почту
#   - Использует уникальный временный путь (без перезаписи чужих файлов)
#===============================================================================
$ErrorActionPreference = "Stop"
$script:scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$script:projectRoot = Resolve-Path (Join-Path $script:scriptDir "..")
$script:checkId = "av001-" + (Get-Date -Format "yyyyMMdd-HHmmss")
$script:tempBase = Join-Path $env:TEMP $script:checkId
$script:outDir = $null

Write-Host "============================================================"
Write-Host "AV-001: Проверка трёх Java-клиентов ClamAV с настоящим clamd"
Write-Host "============================================================"
Write-Host ""

function Check-Prerequisites {
    Write-Host "Проверка prerequisites..."
    $missing = @()
    if (!(Get-Command docker -ErrorAction SilentlyContinue)) { $missing += "docker" }
    if (!(Get-Command java -ErrorAction SilentlyContinue)) { $missing += "java" }
    if (!(Get-Command mvn -ErrorAction SilentlyContinue)) { $missing += "mvn" }
    if ($missing.Count -gt 0) {
        Write-Host ("FAIL: отсутствуют инструменты: " + ($missing -join ", "))
        exit 2
    }
    # Ищем javac
    if (Get-Command javac -ErrorAction SilentlyContinue) {
        $script:javacCmd = "javac"
    } elseif ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin/javac.exe"
        if (Test-Path $candidate) { $script:javacCmd = $candidate }
        else {
            $candidate = Join-Path $env:JAVA_HOME "bin/javac"
            if (Test-Path $candidate) { $script:javacCmd = $candidate }
        }
    }
    if (-not $script:javacCmd) {
        Write-Host "  FAIL: javac не найден (нужен JDK, не только JRE)"
        exit 2
    }
    Write-Host "  javac: $($script:javacCmd)"
    Write-Host "  Prerequisites OK"
    Write-Host ""
}

function Find-AppContainer {
    Write-Host "Поиск Java-контейнера в сети app..."
    $services = @("mail-antivirus-service", "mail-storage-service", "chat-file-service")
    foreach ($svc in $services) {
        $id = docker compose -f "$script:projectRoot/docker-compose.yml" ps -q $svc 2>$null
        if ($id) {
            $status = docker compose -f "$script:projectRoot/docker-compose.yml" ps $svc 2>$null
            if ($status -match "running|healthy|Up") {
                Write-Host "  Найден контейнер $svc: $id"
                return $id
            }
        }
    }
    Write-Host "  FAIL: не найден запущенный Java-контейнер в сети app"
    Write-Host "  Убедитесь, что docker compose запущен (сервисы уже развёрнуты)."
    exit 1
}

function Build-Modules {
    Write-Host "Сборка трёх модулей локально (mvn package -DskipTests)..."
    $modules = @("services/mail-antivirus-service", "services/mail-storage-service", "services/chat-file-service")
    foreach ($mod in $modules) {
        $modPath = Join-Path $script:projectRoot $mod
        Write-Host "  Сборка $mod..."
        $output = mvn -B -f "$modPath/pom.xml" package -DskipTests -q 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  FAIL: сборка $mod не удалась"
            Write-Host $output
            exit 1
        }
        Write-Host "  OK: $mod"
    }
    Write-Host ""
}

function Compile-CheckClass {
    Write-Host "Компиляция Av001ClientCheck.java с classpath из трёх модулей..."
    $classesDirs = @(
        Join-Path $script:projectRoot "services/mail-antivirus-service/target/classes",
        Join-Path $script:projectRoot "services/mail-storage-service/target/classes",
        Join-Path $script:projectRoot "services/chat-file-service/target/classes"
    )
    $classpath = ($classesDirs -join ";")
    $srcPath = Join-Path $script:scriptDir "src/Av001ClientCheck.java"
    $script:outDir = Join-Path $script:tempBase "classes"
    New-Item -ItemType Directory -Path $script:outDir -Force | Out-Null

    $output = & $script:javacCmd -cp $classpath -d $script:outDir $srcPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  FAIL: компиляция Av001ClientCheck.java не удалась"
        Write-Host $output
        exit 1
    }
    Write-Host "  OK: Av001ClientCheck.class скомпилирован в $($script:outDir)"
    Write-Host ""
}

function Copy-To-Container {
    param(
        [string]$ContainerId
    )
    Write-Host "Копирование классов во временную директорию контейнера..."
    $containerTemp = "/tmp/" + $script:checkId

    # Создаём временную директорию в контейнере
    docker exec $ContainerId mkdir -p $containerTemp 2>$null | Out-Null

    # Копируем классы всех трёх модулей
    $moduleDirs = @(
        "services/mail-antivirus-service/target/classes",
        "services/mail-storage-service/target/classes",
        "services/chat-file-service/target/classes"
    )
    foreach ($modDir in $moduleDirs) {
        $localPath = Join-Path $script:projectRoot $modDir
        docker cp $localPath/. "$ContainerId:$containerTemp/$modDir" 2>$null
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  FAIL: не удалось скопировать $modDir в контейнер"
            exit 1
        }
    }

    # Копируем скомпилированный Av001ClientCheck.class
    docker cp (Join-Path $script:outDir "Av001ClientCheck.class") "$ContainerId:$containerTemp/" 2>$null
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  FAIL: не удалось скопировать Av001ClientCheck.class в контейнер"
        exit 1
    }

    Write-Host "  OK: классы скопированы в $containerTemp"
    return $containerTemp
}

function Run-Check {
    param(
        [string]$ContainerId,
        [string]$ContainerTempDir
    )
    Write-Host ""
    Write-Host "Запуск проверки в контейнере $ContainerId..."
    Write-Host ""

    # Собираем classpath из трёх модулей + временная директория
    $cp = "$ContainerTempDir/services/mail-antivirus-service/target/classes`;" +
          "$ContainerTempDir/services/mail-storage-service/target/classes`;" +
          "$ContainerTempDir/services/chat-file-service/target/classes`;" +
          "$ContainerTempDir"

    # Экранируем кавычки для bash внутри docker exec
    $bashCmd = "java -cp `"$cp`" Av001ClientCheck clamav 3310 13310"
    Write-Host "  Команда: docker exec $ContainerId bash -c `"$bashCmd`""
    Write-Host ""

    $output = docker exec $ContainerId bash -c "$bashCmd" 2>&1
    $exitCode = $LASTEXITCODE

    Write-Host $output
    Write-Host ""

    if ($exitCode -eq 0) {
        Write-Host "ИТОГ: PASS — все 9 проверок AV-001 пройдены с настоящим ClamAV"
        exit 0
    } else {
        Write-Host "ИТОГ: FAIL — есть непройденные проверки AV-001"
        exit 1
    }
}

#===============================================================================
# Основной поток
#===============================================================================
Check-Prerequisites
$containerId = Find-AppContainer
Build-Modules
Compile-CheckClass
$containerTempDir = Copy-To-Container $containerId
Run-Check $containerId $containerTempDir
