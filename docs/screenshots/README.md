# Screenshots / Скриншоты Corvex

These PNG files are **real captures of the production frontend build** in Chromium. Mail and Chat use the clearly labelled demo data set; Sign-in and Administration are the real Stage 1 screens.

Эти PNG — **настоящие кадры production-сборки фронтенда** в Chromium. Почта и чат используют честно помеченные демо-данные; вход и администрирование — реальные экраны Stage 1.

| File | Screen |
|---|---|
| `login-ru.png`, `login-en.png` | Sign-in / Вход |
| `mail-ru.png`, `mail-en.png` | Mail / Почта |
| `chat-ru.png`, `chat-en.png` | Messages with the emoji picker open / Сообщения с открытым emoji-picker |
| `admin-ru.png`, `admin-en.png` | Administration / Администрирование |
| `mail-mobile-ru.png` | Mobile mail / Мобильная почта |

## Reproduce / Как переснять

Requirements: Node.js 22.12+ (see `frontend/package.json` engines), Playwright Chromium (or any Chromium via `CHROMIUM_PATH` with `playwright-core`), and a color emoji font (Noto Color Emoji on Linux; Segoe UI Emoji on Windows; Apple Color Emoji on macOS).

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

The script opens RU and EN demo sessions, inserts 🚀 through the picker, sends the message, and captures desktop and mobile frames. If emojis render as empty boxes, the operating system has no color emoji font — install Noto Color Emoji and rerun.
