# Corvex screenshots / Скриншоты Corvex

## English

The images in this directory are **UI evidence only**. They show real captures of the
built Corvex frontend with clearly labelled demo data. They do not, by themselves,
prove Java compilation, Docker startup, SMTP/IMAP/POP3 delivery, chat realtime, or
production readiness.

As of 28 October 2026, the full G1/G2 PC evidence applies only to baseline
`b37db56`; current `main` needs a repeat after the Boot change. Part of G3 has
separate topical runtime evidence. See [`../READINESS.md`](../READINESS.md), and do
not infer any of these results from a screenshot.

### Capture rules

- Use synthetic/demo identities, addresses, rooms, and messages.
- Never expose `.env`, app passwords, JWT/internal tokens, private keys, real customer
  addresses, or working correspondence.
- Keep RU/EN filenames and alt text consistent with the visible language.
- Prefer a clean browser profile and hide bookmarks, notifications, and personal tabs.
- Runtime/deployment captures may be added only for a scenario that actually passed;
  use synthetic data and sanitize logs. A healthy container screenshot is not a
  substitute for the functional acceptance checklist.

The current set contains sign-in, mail, chat/emoji, and administration screens in RU/EN.
They are linked from the root [`README.md`](../../README.md).

## Русский

Изображения в этом каталоге — **доказательство только отрисовки UI**. Это реальные
кадры собранного frontend Corvex с явно обозначенными демонстрационными данными.
Сами по себе они не доказывают компиляцию Java, запуск Docker, доставку
SMTP/IMAP/POP3, realtime-чат или production-готовность.

На 28 октября 2026 года полный PC-pass G1/G2 относится только к `b37db56`;
на текущем `main` после смены Boot его нужно повторить. Часть G3 подтверждена
отдельными тематическими проверками. См. [`../READINESS.md`](../READINESS.md):
не выводите эти результаты из скриншота и не заявляйте, что все функции приняты.

### Правила съёмки

- Используйте только synthetic/demo-пользователей, адреса, комнаты и сообщения.
- Не показывайте `.env`, app-password, JWT/internal token, приватные ключи, настоящие
  адреса клиентов или рабочую переписку.
- Сохраняйте соответствие RU/EN имени файла, alt-текста и языка интерфейса.
- Используйте чистый браузерный профиль без личных вкладок, закладок и уведомлений.
- Runtime/deployment-кадры добавляйте только для фактически пройденного сценария,
  с synthetic-данными и очищенными логами. Скриншот здорового контейнера не заменяет
  функциональный чек-лист.

Текущий набор содержит вход, почту, чат/emoji и админ-панель на RU/EN. Изображения
подключены в корневом [`README.md`](../../README.md).
