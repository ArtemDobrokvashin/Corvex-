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

**Важно:** пока в репозитории нет Gradle wrapper, Studio предложит «Gradle
wrapper не найден». Один раз создай его в этой же папке:

```powershell
cd C:\corvex-android\android
gradle wrapper --gradle-version 8.10.2 --distribution-type bin
git add gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar
git commit -m "android: gradle wrapper"
git push
```

После этого `.\gradlew.bat testDebugUnitTest assembleDebug` и кнопка Run в Studio
начнут работать, а APK появится в `android\app\build\outputs\apk\debug\`.

## 7. Чего нельзя делать ни в одной папке

- коммитить `.env` (он в `.gitignore`, но `git add -f` его обойдёт);
- `git push --force` в `main`;
- `git reset --hard`, если не смотрел `git status` и `git stash list`;
- `docker compose down -v` на стенде с нужными письмами — это удаление томов.
