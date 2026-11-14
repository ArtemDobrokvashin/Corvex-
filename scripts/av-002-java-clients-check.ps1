#===============================================================================
# AV-002: Проверка трёх Java-клиентов Corvex ClamAV с настоящим clamd
#
# Вызывает scan(InputStream) каждого из трёх изменённых ClamAvClient:
 *   1. ru.corvex.mailav.ClamAvClient
 *   2. ru.corvex.mailstorage.av.ClamAvClient
 *   3. ru.corvex.chatfile.av.ClamAvClient
 *
# Сценарии для КАЖДОГО класса (всего 9 результатов):
 *   - синтетический чистый поток     -> Result.CLEAN
 *   - стандартный EICAR test string  -> Result.INFECTED
 *   - контролируемый fake endpoint   -> Result.ERROR (имитация сбоя сканера)
#
# Требования:
 *   - Docker Compose уже запущен (сервисы в сети app) — docker compose up НЕ запускается
 *   - Java, Maven, javac доступны на хосте (Windows)
 *   - mail-storage-service содержит JRE, не JDK — компиляция на хосте, не в контейнере
 *   - Не пересоздаёт контейнеры, не публикует порты, не отправляет тест в почту
 *   - Использует уникальный временный каталог с очисткой в finally
 *   - Не копирует ничего в контейнер clamav
 *   - Порт 13310 НЕ используется — используется контролируемый fake endpoint
#===============================================================================
$ErrorActionPreference = "Stop"

# --- Переменные области скрипта ---
$script:scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$script:projectRoot = Resolve-Path (Join-Path $script:scriptDir "..")
$script:checkId = "av002-" + (Get-Date -Format "yyyyMMdd-HHmmss-fff")
$script:tempHostDir = Join-Path $env:TEMP $script:checkId
$script:containerTempDir = "/tmp/" + $script:checkId
$script:containerId = $null
$script:fakeEndpointPort = 19999
$script:exitCode = 0

Write-Host "============================================================"
Write-Host "AV-002: Проверка трёх Java-клиентов ClamAV с настоящим clamd"
Write-Host "============================================================"
Write-Host "  Check ID: $($script:checkId)"
Write-Host "  Temp dir: $($script:tempHostDir)"
Write-Host ""

#===============================================================================
# Очистка временного каталога (выполняется всегда в finally)
#===============================================================================
function Cleanup-Temp {
    param(
        [string]$ContainerId,
        [string]$ContainerTempDir
    )
    Write-Host ""
    Write-Host "Очистка временных файлов..."

    # Удалить каталог внутри контейнера (только созданный этой проверкой)
    if ($ContainerId -and $ContainerTempDir) {
        try {
            docker exec $ContainerId rm -rf $ContainerTempDir 2>$null | Out-Null
            Write-Host "  Удалён каталог в контейнере: $ContainerTempDir"
        } catch {
            Write-Host "  Предупреждение: не удалось удалить каталог в контейнере: $($_.Exception.Message)"
        }
    }

    # Удалить каталог на хосте
    if ($script:tempHostDir -and (Test-Path $script:tempHostDir)) {
        try {
            Remove-Item -Path $script:tempHostDir -Recurse -Force -ErrorAction SilentlyContinue
            Write-Host "  Удалён каталог на хосте: $($script:tempHostDir)"
        } catch {
            Write-Host "  Предупреждение: не удалось удалить каталог на хосте: $($_.Exception.Message)"
        }
    }
}

#===============================================================================
# Проверка prerequisites
#===============================================================================
function Check-Prerequisites {
    Write-Host "Проверка prerequisites..."
    $missing = @()
    if (!(Get-Command docker -ErrorAction SilentlyContinue)) { $missing += "docker" }
    if (!(Get-Command java -ErrorAction SilentlyContinue)) { $missing += "java" }
    if (!(Get-Command mvn -ErrorAction SilentlyContinue)) { $missing += "mvn" }
    if (!(Get-Command javac -ErrorAction SilentlyContinue) -and -not $env:JAVA_HOME) {
        $missing += "javac (JDK)"
    }
    if ($missing.Count -gt 0) {
        Write-Host ("FAIL: отсутствуют инструменты: " + ($missing -join ", "))
        return $false
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
            else {
                Write-Host "  FAIL: javac не найден (нужен JDK, не только JRE)"
                return $false
            }
        }
    }
    Write-Host "  docker:   $(docker --version 2>$null)"
    Write-Host "  java:     $(java -version 2>&1 | Select-Object -First 1)"
    Write-Host "  mvn:      $(mvn -version 2>&1 | Select-Object -First 1)"
    Write-Host "  javac:    $($script:javacCmd)"
    Write-Host "  Prerequisites OK"
    Write-Host ""
    return $true
}

#===============================================================================
# Найти запущенный Java-контейнер в сети app
#===============================================================================
function Find-AppContainer {
    Write-Host "Поиск Java-контейнера в сети app..."
    $services = @("mail-antivirus-service", "mail-storage-service", "chat-file-service")
    foreach ($svc in $services) {
        $id = docker compose -f "$script:projectRoot/docker-compose.yml" ps -q $svc 2>$null
        if ($id) {
            $status = docker compose -f "$script:projectRoot/docker-compose.yml" ps $svc 2>$null
            if ($status -match "running|healthy|Up") {
                Write-Host "  Найден контейнер $svc: $id"
                $script:containerId = $id.Trim()
                return $true
            }
        }
    }
    Write-Host "  FAIL: не найден запущенный Java-контейнер в сети app"
    Write-Host "  Убедитесь, что docker compose запущен (сервисы уже развёрнуты)."
    return $false
}

#===============================================================================
# Собрать три модуля локально (Maven на хосте)
#===============================================================================
function Build-Modules {
    Write-Host "Сборка трёх модулей локально (mvn package -DskipTests)..."
    $modules = @("services/mail-antivirus-service", "services/mail-storage-service", "services/chat-file-service")
    foreach ($mod in $modules) {
        $modPath = Join-Path $script:projectRoot $mod
        $pomPath = Join-Path $modPath "pom.xml"
        if (-not (Test-Path $pomPath)) {
            Write-Host "  FAIL: не найден pom.xml: $pomPath"
            return $false
        }
        Write-Host "  Сборка $mod..."
        $output = mvn -B -f $pomPath package -DskipTests -q 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  FAIL: сборка $mod не удалась"
            Write-Host $output
            return $false
        }
        Write-Host "  OK: $mod"
    }
    Write-Host ""
    return $true
}

#===============================================================================
# Скомпилировать Av002ClientCheck.java
#===============================================================================
function Compile-CheckClass {
    Write-Host "Компиляция Av002ClientCheck.java с classpath из трёх модулей..."
    $classesDirs = @(
        Join-Path $script:projectRoot "services/mail-antivirus-service/target/classes",
        Join-Path $script:projectRoot "services/mail-storage-service/target/classes",
        Join-Path $script:projectRoot "services/chat-file-service/target/classes"
    )
    # Проверяем наличие целевых директорий
    foreach ($d in $classesDirs) {
        if (-not (Test-Path $d)) {
            Write-Host "  FAIL: не найдены скомпилированные классы: $d"
            return $false
        }
    }
    $classpath = ($classesDirs -join ";")
    $srcPath = Join-Path $script:scriptDir "src/Av002ClientCheck.java"
    if (-not (Test-Path $srcPath)) {
        Write-Host "  FAIL: не найден исходник: $srcPath"
        return $false
    }
    New-Item -ItemType Directory -Path $script:tempHostDir -Force | Out-Null
    $outDir = Join-Path $script:tempHostDir "classes"
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null

    $output = & $script:javacCmd -cp $classpath -d $outDir $srcPath 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  FAIL: компиляция Av002ClientCheck.java не удалась"
        Write-Host $output
        return $false
    }
    Write-Host "  OK: Av002ClientCheck.class скомпилирован в $outDir"
    Write-Host ""
    return $true
}

#===============================================================================
# Скопировать классы во временную директорию контейнера mail-storage-service
#===============================================================================
function Copy-To-Container {
    Write-Host "Копирование классов во временную директорию контейнера..."
    # Создаём временную директорию в контейнере (только если не существует)
    $checkExists = docker exec $script:containerId test -d $script:containerTempDir 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  FAIL: временный каталог уже существует в контейнере: $($script:containerTempDir)"
        return $false
    }
    $createOut = docker exec $script:containerId mkdir -p $script:containerTempDir 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  FAIL: не удалось создать временный каталог в контейнере"
        Write-Host $createOut
        return $false
    }

    # Копируем классы всех трёх модулей
    $moduleMappings = @(
        @{ src = Join-Path $script:projectRoot "services/mail-antivirus-service/target/classes"; dst = "mail-av" },
        @{ src = Join-Path $script:projectRoot "services/mail-storage-service/target/classes";   dst = "mail-storage" },
        @{ src = Join-Path $script:projectRoot "services/chat-file-service/target/classes";     dst = "chat-file" }
    )
    foreach ($mapping in $moduleMappings) {
        $dst = "$($script:containerTempDir)/$($mapping.dst)"
        $createDst = docker exec $script:containerId mkdir -p $dst 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  FAIL: не удалось создать $dst в контейнере"
            Write-Host $createDst
            return $false
        }
        docker cp $($mapping.src)/. "$($script:containerId):$dst" 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  FAIL: не удалось скопировать $($mapping.src) в контейнер"
            return $false
        }
        Write-Host "  OK: $($mapping.dst) скопировано"
    }

    # Копируем скомпилированный Av002ClientCheck.class
    $outDir = Join-Path $script:tempHostDir "classes"
    docker cp (Join-Path $outDir "Av002ClientCheck.class") "$($script:containerId):$($script:containerTempDir)/" 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "  FAIL: не удалось скопировать Av002ClientCheck.class в контейнер"
        return $false
    }
    Write-Host "  OK: Av002ClientCheck.class скопировано"
    Write-Host ""
    return $true
}

#===============================================================================
# Запустить проверку внутри контейнера
#===============================================================================
function Run-Check {
    Write-Host "Запуск проверки в контейнере $($script:containerId)..."
    Write-Host ""

    # Classpath внутри контейнера: разделитель : (Linux)
    $cp = "$($script:containerTempDir)/mail-av:" +
          "$($script:containerTempDir)/mail-storage:" +
          "$($script:containerTempDir)/chat-file:" +
          "$($script:containerTempDir)"

    # Команда для sh (Alpine не имеет bash)
    $bashCmd = "java -cp `"$cp`" Av002ClientCheck clamav 3310 $($script:fakeEndpointPort)"
    Write-Host "  Команда: docker exec $($script:containerId) sh -c `"$bashCmd`""
    Write-Host ""

    $output = docker exec $script:containerId sh -c "$bashCmd" 2>&1
    $script:exitCode = $LASTEXITCODE

    Write-Host $output
    Write-Host ""

    if ($script:exitCode -eq 0) {
        Write-Host "ИТОГ: PASS — все 9 проверок AV-002 пройдены с настоящим ClamAV"
    } else {
        Write-Host "ИТОГ: FAIL — есть непройденные проверки AV-002"
    }
}

#===============================================================================
# Основной поток с очисткой в finally
#===============================================================================
try {
    $preOk = Check-Prerequisites
    if (-not $preOk) { exit 2 }

    $containerOk = Find-AppContainer
    if (-not $containerOk) { exit 1 }

    $buildOk = Build-Modules
    if (-not $buildOk) { exit 1 }

    $compileOk = Compile-CheckClass
    if (-not $compileOk) { exit 1 }

    $copyOk = Copy-To-Container
    if (-not $copyOk) { exit 1 }

    Run-Check
}
catch {
    Write-Host ""
    Write-Host "FAIL: необработанная ошибка: $($_.Exception.Message)"
    $script:exitCode = 1
}
finally {
    Cleanup-Temp $script:containerId $script:containerTempDir
}

exit $script:exitCode
