# Три папки на ноутбуке: что где лежит

У тебя три клона одного репозитория. Это нормально, но у каждой папки своя роль,
и путаница между ними — единственный реальный риск потерять данные или сломать
приёмку. Здесь зафиксировано, что где.

| Папка | Роль | Можно ли обновлять вслепую |
|---|---|---|
| `C:\corvex` | **нет этой папки** (проверено 25.11) — не создавать, чтобы не завести четвёртую копию | — |
| `C:\corvex-clean` | **рабочий клон и стенд приёмки одновременно** «с нуля» (P4). Здесь `.env`, поднятые контейнеры проекта `corvex-fresh`, данные. Документы ссылаются на этот путь как на доказательство приёмки | **это и есть рабочая папка**: `git status` → `git pull --ff-only` → `docker compose build frontend && up -d`; `.env` не трогать |
| `C:\Corvex-test` | **старая** копия, ей больше ничего не нужно | не обновлять; либо удалить, либо оставить как архив |
| `C:\corvex-android` | **только Android Studio**: открывать `C:\corvex-android\android` | да, отдельно от остальных. Там 4 изменённых файла — см. §5 |

Правило, которое снимает почти все проблемы: **три папки — это три независимых
клона, а не ссылки друг на друга.** `git pull` в одной не меняет код в двух других.

## 1. Проверка: где вы отстали

Выполнить в каждой папке:

```powershell
foreach ($dir in 'C:\corvex-clean','C:\Corvex-test','C:\corvex-android') {
  if (-not (Test-Path $dir)) { Write-Host "$dir - нет"; continue }
  Push-Location $dir
  git fetch origin --quiet
  $head   = git rev-parse --short HEAD
  $behind = git rev-list --count HEAD..origin/main
  $dirty  = (git status --porcelain | Measure-Object -Line).Lines
  Write-Host ("{0,-20} HEAD={1}  отстаёт на {2}  изменённых файлов: {3}" -f $dir, $head, $behind, $dirty)
  Pop-Location
}
```

Как читать результат:

- `отстаёт на 0` — актуально;
- `изменённых файлов: 0` — можно спокойно делать `git pull --ff-only`;
- **если изменённых файлов больше нуля — не делай `git reset --hard`.** Сначала
  `git status` и `git stash list`: там может лежать твоя правка или чужой стенд.

## 2. Актуальная точка

`main` = **`88fcb24`** (25.11): влиты 23 пункта бэклога, отложенная отправка,
цепочки, настройки, мобильный интерфейс в палитре веба и ресурсы Android.

## 3. Состояние на 25.11

| Папка | HEAD | Отстаёт | Изменённых файлов |
|---|---|---|---|
| `C:\corvex-clean` | `8efadb5` | 5 | **2** |
| `C:\Corvex-test` | `534172d` | 128 | 12 |
| `C:\corvex-android` | `4f9e321` | 80 | **4** |

Актуальная точка — `d659c64` (после `WORKSPACES.md`).

**Изменённые файлы важнее отставания.** Пока в папке есть незакоммиченные
правки, `git pull --ff-only` может отказаться, а `reset --hard` их уничтожит.
Сначала выяснить, что это:

```powershell
git -C C:\corvex-clean status --short
git -C C:\corvex-clean stash list
```

Ожидаемо и безопасно, если это локальные артефакты (`frontend\dist`, `*.log`,
`local.properties`) — их можно оставить, они не мешают `pull`. Опасно, если это
правки в `src/`, `pom.xml` или `.env`: тогда разбираем до обновления.

## 4. `C:\corvex-clean` — рабочая папка и стенд приёмки

Раз `C:\corvex` нет, это одновременно рабочий клон и стенд приёмки «с нуля»:
здесь доказана установка (`first-run.ps1` → `[OK]`, 20 контейнеров `healthy`) и
здесь же будет фронт для проверки.

```powershell
cd C:\corvex-clean
git status --short                 # сначала посмотреть, что изменено
git pull --ff-only
docker compose build frontend
docker compose up -d frontend
docker compose ps
```

Затем **Ctrl + F5** в браузере.

Что не делать: `docker compose down -v` (удаляет тома с письмами и базами),
удаление `.env`, `git reset --hard` без просмотра `git status` и `git stash list`.
Миграции Flyway применятся сами при перезапуске контейнеров.

## 5. `C:\Corvex-test` — старая копия

Отстаёт на 128 коммитов, 12 изменённых файлов. Ничего не обновляй. Варианты:

- оставить как архив — она ничего не стоит, кроме диска;
- удалить, если она не содержит единственных копий чего-либо:
  `Remove-Item -Recurse -Force C:\Corvex-test` — но **сначала** проверь, нет ли
  там `.env` или логов, которых больше нигде нет.

## 6. `C:\corvex-android` — Android Studio

Открывать в Android Studio нужно `C:\corvex-android\android` (папку с
`settings.gradle.kts`), а не корень репозитория: иначе Studio попытается
импортировать Maven-модули из `services/`.

```powershell
cd C:\corvex-android
git pull --ff-only
```

`.idea/`, `local.properties`, `build/` — локальные, в репозиторий не попадают.

### 6.1 «Invalid Gradle JDK configuration found» — это настройка Studio, а не проект

Ошибка появляется до сборки и коду отношения не имеет: в `~/.gradle/gradle.properties`
(или в настройках проекта) записан путь к JDK, которого на этой машине нет —
обычно остаток от другой версии Studio или от распакованного JDK во временной папке.
Проект требует **JDK 17** (`compileOptions` и `jvmTarget = "17"` в
`app/build.gradle.kts`), и Embedded JDK самой Studio — это ровно JDK 17, так что
безопасный ответ — первая кнопка в диалоге: **Use Embedded JDK**.

Если хочется явно, а не через диалог:

1. `File → Settings → Build, Execution, Deployment → Build Tools → Gradle`.
2. **Gradle JDK** → выбрать `jbr-17` из списка (путь вида
   `C:\Program Files\Android\Android Studio\jbr`). Если в списке его нет —
   `Add JDK…` и указать эту папку.
3. Проверить, что старый битый путь не остался в `%USERPROFILE%\.gradle\gradle.properties`
   в строке `org.gradle.java.home` — её лучше просто удалить, тогда Studio
   перестанет спорить с собой.
4. `File → Invalidate Caches… → Invalidate and Restart`, если диалог возвращается.

Ту же строку `org.gradle.java.home` в `android/gradle.properties` писать **не надо**:
файл лежит в git, и жёсткий путь `C:\Program Files\...` сломает сборку на CI и на
любой другой машине. JDK — локальная настройка, её место в `local.properties` или
в настройках Studio.

Проверить, что дело было именно в этом, а не в коде: после смены JDK нажмите
`Sync Project with Gradle Files`. Если синк прошёл — ошибка была настройкой.

### 6.2 Gradle wrapper уже в репозитории

`android/gradlew`, `android/gradlew.bat` и `gradle/wrapper/gradle-wrapper.jar`
лежат в git (Gradle 8.10.2), поэтому создавать wrapper руками больше не нужно.
Первый клон и первая сборка:

```powershell
cd C:\corvex-android
git pull --ff-only
cd android
.\gradlew.bat testDebugUnitTest assembleDebug
```

`gradlew.bat` скачает Gradle 8.10.2 при первом запуске (нужна сеть) и соберёт
проект. APK появится в `android\app\build\outputs\apk\debug\`.

Если Android Studio не видит wrapper: `File → Sync Project with Gradle Files`.
Studio использует `gradlew` из проекта, а не установленный в системе Gradle —
поэтому отдельно ставить Gradle не требуется, достаточно JDK 17 (§6.1).

**Чего wrapper не решает.** Он не заменяет JDK и Android SDK. Если запустить
`gradlew.bat` из PowerShell до первого открытия проекта в Studio, будет
`SDK location not found` — потому что `local.properties` создаёт Studio, а не Gradle.

### 6.3 «SDK location not found» — один раз, перед первой сборкой

Проверить, установлен ли вообще Android SDK:

```powershell
Test-Path "$env:LOCALAPPDATA\Android\Sdk\platforms"
```

- **`True`** — SDK есть. Создать `android\local.properties` с одной строкой
  (второй слэш в `C:\\` обязателен: в `.properties` обратный слэш — спецсимвол):

  ```powershell
  Set-Content C:\corvex-android\android\local.properties "sdk.dir=C:\\Users\\$env:USERNAME\\AppData\\Local\\Android\\Sdk"
  ```

- **`False`** — SDK не установлен. Открыть `C:\corvex-android\android` в Android Studio
  и пройти `Tools → SDK Manager`: Android SDK Platform 35 (проект: `compileSdk = 35`),
  Android SDK Build-Tools, Android SDK Platform-Tools, эмулятор. Studio сама создаст
  `local.properties` при синхронизации.

`local.properties` — локальный файл, он в `.gitignore`. Коммитить его нельзя: путь
`C:\Users\...` верен только на одной машине.

### 6.4 «No connected devices!» — это не ошибка сборки

`installDebug` — отдельная задача, которая ставит **уже собранный** APK на телефон или
эмулятор. Если устройства нет, падает только она; `assembleDebug` к этому моменту давно
отработал, и APK лежит в `android\app\build\outputs\apk\debug\app-debug.apk`.

### Сначала ответить на два вопроса — GTK и adb

Оба ответа получаются двумя командами. Первая — **создан ли вообще эмулятор или
подключён ли телефон**; вторая — **видит ли его adb**.

```powershell
adb devices
```

Если `adb` не найден в `PATH` (PowerShell пишет «не распознано как имя командлета»),
вызвать его напрямую — это обычная ситуация, `platform-tools` в `PATH` не добавляются:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
```

Список эмуляторов, которые уже созданы в Studio (даже выключенных) — отдельная команда:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -list-avds
```

Возможны четыре состояния, и лечатся они по-разному:

| `adb devices` | `emulator -list-avds` | Что это значит | Что делать |
|---|---|---|---|
| пусто | пусто | устройства нет вообще | создать эмулятор (ниже) |
| пусто | есть имя | эмулятор создан, но не запущен | запустить (ниже) |
| `unauthorized` | — | телефон не разрешил отладку | подтвердить запрос на экране телефона |
| `offline` | — | эмулятор ещё грузится | подождать до `device` |

**Запустить уже созданный эмулятор можно без мыши:**

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd ИМЯ_ИЗ_СПИСКА
```

Окно эмулятора откроется отдельно, а `adb devices` покажет `emulator-5554`. Из него
`installDebug` уже ставит приложение. Через Studio то же самое: `Tools → Device Manager →
Create Device` (если списка нет) или кнопка `Start` у существующего.

### Если устройства нет совсем

- **Эмулятор:** `Tools → Device Manager` в Studio, `Create Device`, образ с API 35
  (или 26, это `minSdk`), `Start`. Первый запуск скачивает образ — гигабайты и несколько
  минут.
- **Телефон:** включить «Для разработчиков» (7 тапов по «Номер сборки» в
  «О телефоне») → «Отладка по USB», подключить кабелем и подтвердить запрос
  «Разрешить отладку по USB» на экране телефона.
- **Устройство есть, но `unauthorized`** — не подтверждён запрос на телефоне. Нажать
  «Разрешить»; если запрос не появляется — `adb kill-server`, выдернуть и вставить кабель.
- **`offline`** — эмулятор ещё загружается. Подождать, пока `adb devices` не покажет `device`.

Потом:

```powershell
cd C:\corvex-android\android
.\gradlew.bat installDebug
```

### Вариант без adb: отдать APK и поставить его руками

`installDebug` — единственная задача, которой нужен adb. Если эмулятор не нужен,
а нужен телефон, второе окно PowerShell **не требуется**: `assembleDebug` уже
собрал APK, а ставится он по-разному для эмулятора и для телефона.

```powershell
cd C:\corvex-android\android
.\gradlew.bat assembleDebug
```

APK: `android\app\build\outputs\apk\debug\app-debug.apk` (≈15–25 МБ).

- **Эмулятор на том же ноутбуке** — можно просто перетащить файл в окно эмулятора.
- **Телефон** — нужен `adb`, потому что эмулятор и телефон не видят друг друга:
  `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk`
  (adb берёт устройство по USB, эмулятор для этого не нужен).
- **Через файл** — скопировать APK в облако/мессенджер, открыть на телефоне и
  разрешить «Установка из неизвестных источников». Chrome это разрешает, Telegram тоже.
  Это единственный путь, где ADB не нужен вообще.

**APK не подписан для постоянного использования.** Debug-сборка подписана
автоматическим debug-ключом: она ставится и работает, но обновиться поверх
release-сборки не сможет, а на некоторых телефонах Play Protect спросит
подтверждение. Для тестирования этого достаточно; для раздачи коллегам нужен
`signingConfig` с настоящим ключом.

### 6.5 Куда приложение будет ходить без сервера

Демо-режима у Android-клиента нет (в вебе есть). Первый экран — вход, и ему нужен живой
бэкенд. Варианты адреса, который вводится в поле сервера:

- **эмулятор → Docker на этом же ноутбуке**: `http://10.0.2.2:8080`. `10.0.2.2` — это
  `localhost` машины изнутри эмулятора; `127.0.0.1` внутри эмулятора — сам эмулятор.
- **телефон по USB**: `adb reverse tcp:8080 tcp:8080`, затем в приложении `http://localhost:8080`.
  Через Wi-Fi — IP ноутбука в локальной сети.

**HTTP может не пройти, и это не ошибка клиента.** В `AndroidManifest.xml` стоит
`android:usesCleartextTraffic="false"`, а `res/xml/network_security_config.xml` разрешает
открытый HTTP только для `localhost` и `10.0.2.2` (проверить файл перед запуском). Если
адрес другой, по HTTP будет отказ — нужен HTTPS или явное исключение в этом файле.
Боевой сценарий — HTTPS, и отключать `usesCleartextTraffic` ради отладки не нужно.

## 7. Чего нельзя делать ни в одной папке

- коммитить `.env` (он в `.gitignore`, но `git add -f` его обойдёт);
- `git push --force` в `main`;
- `git reset --hard`, если не смотрел `git status` и `git stash list`;
- `docker compose down -v` на стенде с нужными письмами — это удаление томов.
