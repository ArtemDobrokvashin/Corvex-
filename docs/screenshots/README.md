# Corvex screenshots / Скриншоты Corvex

## English

The images in this directory are **UI evidence only**. They show real captures of the
built Corvex frontend with clearly labelled demo data. They do not, by themselves,
prove Java compilation, Docker startup, SMTP/IMAP/POP3 delivery, chat realtime, or
production readiness.

As of 19 October 2026, `main` (`ad42ff2`, Spring Boot 3.5.16) builds and starts on the
laptop bench with `admin-service`, `mail-storage-service`, `notification-service`,
`web-gateway`, frontend, migrations `V6`/`V8` and `healthy` containers. The full
12-service reactor and the clean-profile install are repeated separately. See
[`../READINESS.md`](../READINESS.md), and do not infer any of these results from a
screenshot.

### What the current set shows, and what it does not

The frames in this folder were captured before the calendar was connected to the
backend. The screens themselves are still real, but the sidebar in those frames does
not yet show the calendar widget that ships today, and there is **no calendar frame
at all**. Re-capturing the set is one command (below); until it is run, the images
must not be used as evidence of the current UI.

### Capture rules

- Use synthetic/demo identities, addresses, rooms, and messages.
- Never expose `.env`, app passwords, JWT/internal tokens, private keys, real customer
  addresses, or working correspondence.
- Keep RU/EN filenames and alt text consistent with the visible language.
- Prefer a clean browser profile and hide bookmarks, notifications, and personal tabs.
- Runtime/deployment captures may be added only for a scenario that actually passed;
  use synthetic data and sanitize logs. A healthy container screenshot is not a
  substitute for the functional acceptance checklist.

### Reproducing the set

Demo mode makes the captures independent of the backend, accounts, and secrets: the
page opens with labelled sample data.

1. Start the frontend (first run: `npm ci`):

   ```powershell
   cd frontend
   npm run dev            # http://localhost:5173
   ```

2. Capture every frame with the installed Chrome or Edge:

   ```powershell
   # from the repository root, second terminal
   .\scripts\capture-screenshots.ps1
   ```

   The script uses the browser's own `--headless=new` mode, so nothing extra is
   downloaded. It opens `/login`, `/mail?demo=1`, `/chat?demo=1`, `/calendar?demo=1`,
   `/admin?demo=1` in RU and EN plus a 430×932 mobile frame, waits for the lazy chunk,
   and writes `login-{ru,en}.png`, `mail-{ru,en}.png`, `chat-{ru,en}.png`,
   `calendar-{ru,en}.png`, `admin-{ru,en}.png`, `mail-mobile-ru.png`.

   Options: `-BaseUrl`, `-OutDir`, `-WaitMs`, `-Browser <path to chrome.exe>`. The
   script has not been executed in an environment without a browser; on a PC with
   Chrome it is a plain invocation of `chrome --headless --screenshot`.

3. Review the diff before committing: a frame with a spinner, an empty list, or a
   login redirect is a failed capture, not a screenshot.

   ```powershell
   git status --short docs/screenshots
   ```

4. Only after the calendar frames exist, add them to the tables in
   [`../../README.md`](../../README.md) — the rows are already listed in the same
   order and with the same captions.

Manual capture is equally valid: open the URL, press `F5`-free of devtools overlays,
and take a window screenshot at 1440×900. Never retouch the UI or redraw it in an
editor: a screenshot must remain a photograph of the running application.

### `?demo=1` deep link

`sessionStorage['corvex.demo'] = '1'` survives navigation but not a fresh browser
process, which is why headless capture needs a URL. `?demo=1` enables demo mode only
when session restore by refresh cookie failed, so it never throws a signed-in user out
of their account. The button "View the demo without a server" on the login page does
the same thing interactively.

## Русский

Изображения в этом каталоге — **доказательство только отрисовки UI**. Это реальные
кадры собранного frontend Corvex с явно обозначенными демонстрационными данными.
Сами по себе они не доказывают компиляцию Java, запуск Docker, доставку
SMTP/IMAP/POP3, realtime-чат или production-готовность.

На 19 октября 2026 года `main` (`ad42ff2`, Spring Boot 3.5.16) собирается и
запускается на ноутбуке-стенде: `admin-service`, `mail-storage-service`,
`notification-service`, `web-gateway`, frontend, миграции `V6`/`V8`, контейнеры
`healthy`. Полный reactor по 12 сервисам и чистая установка повторяются отдельно.
См. [`../READINESS.md`](../READINESS.md): не выводите эти результаты из скриншота.

### Что показывает текущий набор и чего в нём нет

Кадры в этом каталоге сняты до того, как календарь подключили к backend. Сами экраны
остаются настоящими, но в боковой панели этих кадров ещё нет виджета календаря,
который есть в текущей сборке, и **кадра календаря нет вообще**. Переснять набор —
одна команда (ниже); до этого изображения нельзя использовать как доказательство
текущего интерфейса.

### Правила съёмки

- Используйте только synthetic/demo-пользователей, адреса, комнаты и сообщения.
- Не показывайте `.env`, app-password, JWT/internal token, приватные ключи, настоящие
  адреса клиентов или рабочую переписку.
- Сохраняйте соответствие RU/EN имени файла, alt-текста и языка интерфейса.
- Используйте чистый браузерный профиль без личных вкладок, закладок и уведомлений.
- Runtime/deployment-кадры добавляйте только для фактически пройденного сценария,
  с synthetic-данными и очищенными логами. Скриншот здорового контейнера не заменяет
  функциональный чек-лист.

### Как переснять набор

Демо-режим делает съёмку независимой от backend, аккаунтов и секретов: страница
открывается с подписанными демонстрационными данными.

1. Запустите frontend (первый запуск — `npm ci`):

   ```powershell
   cd frontend
   npm run dev            # http://localhost:5173
   ```

2. Снимите все кадры установленным Chrome или Edge:

   ```powershell
   # из корня репозитория, во втором терминале
   .\scripts\capture-screenshots.ps1
   ```

   Скрипт использует собственный `--headless=new` браузера, поэтому ничего не
   скачивает. Он открывает `/login`, `/mail?demo=1`, `/chat?demo=1`,
   `/calendar?demo=1`, `/admin?demo=1` на RU и EN плюс мобильный кадр 430×932,
   дожидается ленивой загрузки и пишет `login-{ru,en}.png`, `mail-{ru,en}.png`,
   `chat-{ru,en}.png`, `calendar-{ru,en}.png`, `admin-{ru,en}.png`,
   `mail-mobile-ru.png`.

   Параметры: `-BaseUrl`, `-OutDir`, `-WaitMs`, `-Browser <путь к chrome.exe>`.
   В среде без браузера скрипт не запускался; на ПК с Chrome это обычный вызов
   `chrome --headless --screenshot`.

3. Проверьте diff перед коммитом: кадр со спиннером, пустым списком или редиректом
   на вход — неудачная съёмка, а не скриншот.

   ```powershell
   git status --short docs/screenshots
   ```

4. Только после появления кадров календаря добавьте их в таблицы
   [`../../README.md`](../../README.md) — строки уже перечислены в том же порядке и
   с теми же подписями.

Ручная съёмка тоже допустима: откройте URL, уберите оверлеи devtools и снимите окно
1440×900. Не дорисовывайте UI в редакторе: скриншот должен оставаться снимком
работающего приложения.

### Ссылка `?demo=1`

`sessionStorage['corvex.demo'] = '1'` переживает навигацию, но не новый процесс
браузера — поэтому для headless-съёмки нужен URL. `?demo=1` включает демо только
тогда, когда восстановление сессии по refresh-cookie не удалось, и никогда не
выкидывает вошедшего пользователя из аккаунта. Кнопка «Посмотреть демо без сервера»
на странице входа делает то же самое интерактивно.
