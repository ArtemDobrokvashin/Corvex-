# Screenshots / Скриншоты Corvex

These PNG files are **real captures of the Corvex frontend in Chromium**, not
design mock-ups. Mail and Messages use the clearly labelled demo data set;
Sign-in and Administration show the real Stage 1 screens. A screenshot proves
that the interface renders—it does **not** prove that Java, Docker, SMTP,
IMAP/POP3, or the persistent chat have passed runtime acceptance.

Эти PNG — **настоящие кадры фронтенда Corvex в Chromium**, а не дизайн-макеты.
Почта и сообщения используют честно помеченные демо-данные; вход и
администрирование — реальные экраны Stage 1. Скриншот доказывает только то,
что интерфейс отрисовывается. Он **не доказывает**, что Java, Docker, SMTP,
IMAP/POP3 или persistent-чат уже прошли runtime-приёмку.

| File | Screen |
|---|---|
| `login-ru.png`, `login-en.png` | Sign-in / Вход |
| `mail-ru.png`, `mail-en.png` | Mail / Почта |
| `chat-ru.png`, `chat-en.png` | Messages with the emoji picker open / Сообщения с открытым emoji-picker |
| `admin-ru.png`, `admin-en.png` | Administration / Администрирование |
| `mail-mobile-ru.png` | Mobile mail / Мобильная почта |

## Keeping the set current / Поддержание набора в актуальном состоянии

To keep the captures accurate and current:

- recapture the set after visible UI changes;
- after the G1–G3 runtime gates pass, add captures from a real test
  deployment with **synthetic users and mail only**;
- add a real Windows 7 + Chrome 109 capture when that environment is tested;
- never publish real correspondence, names, addresses, hostnames, tokens,
  passwords, or unredacted infrastructure details.

Чтобы кадры оставались точными и актуальными:

- переснимайте набор после видимых изменений интерфейса;
- после прохождения runtime-ворот G1–G3 добавляйте кадры настоящего тестового
  развёртывания только с **синтетическими пользователями и письмами**;
- добавьте настоящий кадр Windows 7 + Chrome 109, когда эта среда будет
  проверена;
- никогда не публикуйте настоящую переписку, имена, адреса, hostnames,
  токены, пароли и детали инфраструктуры без редактирования.

## Reproduce / Как переснять

Requirements: Node.js 22.22.2+, 24.15+, or 26+ (see `frontend/package.json` engines), Playwright
Chromium (or any Chromium via `CHROMIUM_PATH` with `playwright-core`), and a
color emoji font (Noto Color Emoji on Linux; Segoe UI Emoji on Windows; Apple
Color Emoji on macOS).

The frontend baseline is React 19 + Vite 8. The capture flow below builds the
current production bundle, serves it locally, opens RU and EN demo sessions,
inserts 🚀 through the picker, sends the message, and captures desktop and
mobile frames. After a toolchain-only upgrade, rerun the flow and compare the
pixels: keep the checked-in set only if the current bundle still produces the
same intentional layout, and mention that comparison in the PR.

```bash
cd frontend
npm ci
npm install --no-save --package-lock=false playwright@1.49.1
npx playwright install chromium
npm run build
npm run preview -- --host 127.0.0.1 --port 4173 --strictPort
# second terminal / второй терминал:
SCREENSHOT_DIR=../docs/screenshots node scripts/capture-screenshots.mjs
```

If emojis render as empty boxes, the operating system has no color emoji
font—install Noto Color Emoji and rerun. If a capture differs because the UI
changed, that is useful information: include the commit SHA and explain the
visual change in the pull request instead of silently replacing every frame.

Before opening a screenshot PR, also run the frontend gates:

```bash
npm test
npm run build
npm audit --omit=dev --audit-level=moderate
```

For a layout problem at a phone/tablet width, a missing translation, or a
broken emoji, a useful issue includes the browser, OS, viewport, locale, and
a redacted screenshot.
