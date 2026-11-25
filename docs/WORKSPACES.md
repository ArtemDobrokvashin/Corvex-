# Три папки на ноутбуке: что где лежит

У тебя три клона одного репозитория. Это нормально, но у каждой папки своя роль,
и путаница между ними — единственный реальный риск потерять данные или сломать
приёмку. Здесь зафиксировано, что где.

| Папка | Роль | Можно ли обновлять вслепую |
|---|---|---|
| `C:\corvex` | **рабочий клон**: сюда `git pull`, здесь фронт в Docker | да, если `git status` чистый |
| `C:\corvex-clean` | **стенд приёмки** «с нуля» (P4). Здесь `.env`, поднятые контейнеры проекта `corvex-fresh`, данные. Документы ссылаются на этот путь как на доказательство приёмки | **осторожно**: обновление кода может потребовать миграций и перезапуска; `.env` не трогать |
| `C:\Corvex-test` | **старая** копия, ей больше ничего не нужно | не обновлять; либо удалить, либо оставить как архив |
| `C:\corvex-android` | **только Android Studio**: открывать `C:\corvex-android\android` | да, отдельно от остальных |

Правило, которое снимает почти все проблемы: **три папки — это три независимых
клона, а не ссылки друг на друга.** `git pull` в одной не меняет код в двух других.

## 1. Проверка: где вы отстали

Выполнить в каждой папке:

```powershell
foreach ($dir in 'C:\corvex','C:\corvex-clean','C:\Corvex-test','C:\corvex-android') {
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

## 3. `C:\corvex` — рабочий клон

```powershell
cd C:\corvex
git pull --ff-only
docker compose build frontend
docker compose up -d frontend
```

Затем **Ctrl + F5** в браузере.

## 4. `C:\corvex-clean` — стенд приёмки, не трогать без нужды

Здесь доказана установка с нуля: `first-run.ps1` → `[OK]`, 20 контейнеров
`healthy`, вход администратором. Пока приёмка не переехала на боевой сервер,
этот стенд — доказательство, а не «ещё одна копия кода».

Что можно делать: смотреть, проверять руками, гонять приёмочные сценарии.
Что не делать: `docker compose down -v`, удаление `.env`, `git reset --hard`.
Если нужно подтянуть код — только `git pull --ff-only` после `git status`, а
затем `docker compose up -d --build` (миграции Flyway применятся сами).

## 5. `C:\Corvex-test` — старая копия

Ничего не обновляй. Варианты:

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
