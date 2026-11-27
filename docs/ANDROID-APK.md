# Сборка APK для телефона

## Как это работает

Отладочный APK подписывается отладочным ключом и ставится на телефон без Play
Market. Собирать его нужно там, где есть JDK 17 и Android SDK: на машине
разработчика или в CI. В песочнице, где ведётся работа над кодом, этих
инструментов нет — поэтому здесь APK не появляется.

## Вариант 1: у себя на компьютере (Windows, PowerShell)

Нужен Android Studio (он ставит JDK и SDK) либо JDK 17 + Android SDK отдельно.

```powershell
cd C:\corvex-android
git pull --ff-only
cd android
.\gradlew.bat testDebugUnitTest --console=plain
.\gradlew.bat assembleDebug --console=plain
```

Готовый файл:

```
android\app\build\outputs\apk\debug\app-debug.apk
```

Скопировать на телефон и открыть. Android спросит разрешение на установку из
неизвестного источника — это нормально для APK не из магазина.

Если `testDebugUnitTest` или `assembleDebug` упали, в логе будет строка `e:` —
это ошибка компиляции Kotlin. Пришлите её целиком, правка занимает минуты.

## Вариант 2: CI (когда у GitHub-приложения появятся права `workflows`)

Готовый workflow лежит в `.github/workflows-disabled/build-apk.yml`. Он делает
то же самое и, кроме APK, прогоняет Kotlin-тесты. Включение одной командой:

```powershell
cd C:\corvex-android
git pull --ff-only
New-Item -ItemType Directory -Force .github\workflows | Out-Null
Move-Item .github\workflows-disabled\build-apk.yml .github\workflows\build-apk.yml -Force
git add . ; git commit -m "ci: сборка APK и Kotlin-тесты" ; git push
```

Дальше при каждом изменении в `android/**` в разделе **Actions** появится
запуск `Android APK`, а внизу страницы запуска — артефакт
`corvex-debug-NNN` с `app-debug.apk`.

## Почему файл disabled

У GitHub-приложения, которым ведётся работа, нет права `workflows`:
`git push` файла `.github/workflows/*` отклоняется с
«refusing to allow a GitHub App to create or update workflow». Права выдаются
владельцем репозитория в настройках приложения; это единственная причина, по
которой сборка не включена сразу.

## Что проверить на телефоне

1. Вход по адресу сервера и учётке.
2. Почта: список, открытие письма, вложение, перенос в папку.
3. Чат: комнаты, отправка, «На связи» в шапке комнаты.
4. Календарь: месяц, создание события, повтор «по будням».
5. Файлы: загрузка, скачивание в «Загрузки», корзина.
6. Админка: раздел откроется только у администратора; иначе «нет прав».
