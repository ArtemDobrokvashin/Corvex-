# Как внести вклад / Contributing to Corvex

## Русский

Corvex — бесплатный open-source проект под **Apache-2.0**. Спасибо, что
рассматриваете вклад: сейчас проекту одинаково важны код, первый честный
прогон на настоящей машине, почтовый клиент из реального парка, понятная
документация и перевод.

### Самая нужная помощь сейчас

1. **Первый внешний прогон G1/G2.** Запустить `mvn -B clean verify`, затем
   Docker Compose на отдельной VM и прислать либо подтверждение успеха,
   либо первую ошибку сборки/старта без секретов. Подробный маршрут —
   [`docs/TESTING-ON-YOUR-PC.md`](docs/TESTING-ON-YOUR-PC.md).
2. **Функциональная приёмка G3.** Сквозная почта и webmail, Thunderbird/Outlook
   для уже написанных IMAP/POP3 Stage 3, двухпользовательский persistent-чат
   Stage 4, backup/restore и проверка на Windows 7 + Chrome 109.
3. **Тесты вокруг первого runtime.** Интеграционные тесты SMTP/IMAP/POP3,
   E2E-сценарии identity/webmail/chat и регрессионные тесты на каждую
   исправленную ошибку первой сборки.
4. **G4 после зелёного baseline.** Отдельные, не смешанные апгрейды Spring
   Boot 4.1/Spring Cloud/Jackson 3, Apache James 3.9.x и Promtail → Grafana/Alloy.
5. **Stage 5 и hardening.** AD/LDAP, мгновенный отзыв JWT, строгая CSP,
   transactional outbox, персистентные LSUB, распределённый IMAP IDLE,
   keyword-флаги/ACL, CI и нагрузочные профили.
6. **Документация и доступность входа.** Инструкции установки, переводы,
   актуальные скриншоты, клиентские матрицы и отчёты об успешных стендах.

Не тестируйте Corvex на рабочих ящиках и не публикуйте почтовые порты, пока
не пройдены ворота из [`docs/READINESS.md`](docs/READINESS.md) и
[`docs/RELEASE-DEPENDENCIES.md`](docs/RELEASE-DEPENDENCIES.md).

### Принципы

- Java 17 и Spring Boot; каждый сервис автономен (свой `pom.xml`, Dockerfile и
  схема данных). Полный реактор собирается одной командой из корня.
- Авторизация проверяется на сервере на каждое действие; скрытие кнопки в UI —
  не защита. Внутренние `/internal/**` никогда не публикуются через gateway и
  работают fail-closed.
- Интерфейс и публичные ошибки API поставляются одновременно на RU/EN; адаптивность
  для планшетов и телефонов обязательна.
- Почтовые протоколы не пишутся с нуля: SMTP — поддерживаемый форк SubEtha
  (`com.github.davidmoten:subethasmtp` 7.2.2) и dnsjava; IMAP/POP3 —
  Apache James 3.8.2 как baseline первой компиляции.
- Тестовые библиотеки добавляются только вместе с реальным тестом. Поэтому
  неиспользуемый GreenMail сознательно не держится в POM: если ваш PR приносит
  первый GreenMail-тест, верните зависимость тем же PR и объясните сценарий.
- Chrome 109 на Windows 7 — жёсткое требование. Нельзя молча заменять
  Tailwind 3/Babel 7 на Tailwind 4/Babel 8: нужен отдельный план, замена
  legacy-цепочки и доказательство на реальном Chrome 109.
- Не коммитить `.env`, секреты, приватные ключи, internal token, настоящие
  письма и персональные данные. Для примера правьте `.env.example`.
- Документация и код не расходятся: изменение поведения, версий или команд
  проверки обновляется в том же PR.
- Major-зависимости не смешиваются с patch/minor. Каждая крупная миграция —
  отдельный PR, отдельная сверка API и повторение затронутых ворот G0–G5.

### Перед созданием PR

Выполните проверки, которые относятся к вашему изменению, и честно напишите
в PR, что реально запускалось, а что **NOT RUN**:

```bash
# Frontend (current Node.js patch lines: 22.22.2+, 24.15+, or 26+)
cd frontend
npm ci
npm test
npm run build
npm audit --omit=dev --audit-level=moderate

# Backend, preferred full reactor gate (JDK 17, Maven 3.9+)
mvn -B clean verify

# Targeted module example while iterating
mvn -B -pl :mail-imap-service -am test
```

Если вы правите только документацию, проверьте локальные Markdown-ссылки и
`git diff --check`. Если правите POM, Dockerfile или Compose, приложите вывод
соответствующей сборки или прямо укажите, что Docker/Maven-гейт не запускался.
Не называйте статический разбор «компиляцией», а открытый dev-сервер —
runtime-приёмкой полного стека.

В описании PR укажите:

- что изменено и зачем;
- какие ворота G0–G5 затронуты;
- точные команды и результат;
- что не запускалось и почему;
- скриншоты для UI или очищенные логи для runtime-ошибок;
- известные риски и следующий шаг приёмки.

Если вы не готовы к PR, issue с SHA коммита, версиями JDK/Maven/Node/Docker,
выполненными командами и первой ошибкой уже является полноценным вкладом.

Вклад принимается под Apache-2.0 (см. [`LICENSE`](LICENSE) и [`NOTICE`](NOTICE)).

---

## English

Corvex is a free open-source project under **Apache-2.0**. Thank you for
considering a contribution: code, a first honest run on a real machine, a mail
client from a real fleet, clear documentation, and translations are all
valuable at this stage.

### Help most needed now

1. **The first outside G1/G2 run.** Run `mvn -B clean verify`, then Docker
   Compose on a separate VM, and report either success or the first sanitized
   build/startup error. The detailed Russian route is in
   [`docs/TESTING-ON-YOUR-PC.md`](docs/TESTING-ON-YOUR-PC.md).
2. **G3 functional acceptance.** End-to-end mail and webmail, Thunderbird/
   Outlook against the already-written Stage 3 IMAP/POP3 code, two-user
   Stage 4 persistent chat, backup/restore, and Windows 7 + Chrome 109.
3. **Tests around the first runtime.** SMTP/IMAP/POP3 integration tests,
   identity/webmail/chat E2E scenarios, and a regression test for every
   first-build failure that gets fixed.
4. **G4 after a green baseline.** Separate, unmixed upgrades to Spring Boot
   4.1/Spring Cloud/Jackson 3, Apache James 3.9.x, and Promtail → Grafana/Alloy.
5. **Stage 5 and hardening.** AD/LDAP, instant JWT revocation, strict CSP, a
   transactional outbox, persistent LSUB, distributed IMAP IDLE, keyword
   flags/ACL, CI, and load profiles.
6. **Documentation and onboarding.** Installation guides, translations, fresh
   screenshots, mail-client matrices, and successful bench reports.

Do not test Corvex with production mailboxes or publish mail ports until the
gates in [`docs/READINESS.md`](docs/READINESS.md) and
[`docs/RELEASE-DEPENDENCIES.md`](docs/RELEASE-DEPENDENCIES.md) pass.

### Principles

- Java 17 and Spring Boot; every service is autonomous (its own `pom.xml`,
  Dockerfile, and data schema). The full reactor builds from the repository root.
- Authorization is enforced server-side on every action; hiding a UI button is
  not security. Internal `/internal/**` APIs are never exposed through the
  gateway and fail closed.
- The UI and public API errors ship in RU/EN together; phone and tablet layouts
  are mandatory.
- Mail protocols are not written from scratch: SMTP uses the maintained
  SubEtha fork (`com.github.davidmoten:subethasmtp` 7.2.2) and dnsjava;
  IMAP/POP3 use Apache James 3.8.2 as the first-compilation baseline.
- Test libraries are added only with a real test. Unused GreenMail is
  therefore intentionally absent from the POMs: if your PR adds the first
  GreenMail test, restore the dependency in that PR and explain the scenario.
- Chrome 109 on Windows 7 is a hard requirement. Do not silently replace
  Tailwind 3/Babel 7 with Tailwind 4/Babel 8: that needs a separate plan, a
  replacement legacy chain, and proof on real Chrome 109.
- Never commit `.env`, secrets, private keys, internal tokens, real mail, or
  personal data. Update `.env.example` instead.
- Documentation and code must not drift: behavior, version, and verification
  command changes belong in the same PR.
- Major dependency upgrades are never mixed with patch/minor work. Each major
  migration gets its own PR, API re-verification, and repetition of the
  affected G0–G5 gates.

### Before opening a PR

Run the checks relevant to your change and state plainly what actually ran
and what was **NOT RUN**:

```bash
# Frontend (current Node.js patch lines: 22.22.2+, 24.15+, or 26+)
cd frontend
npm ci
npm test
npm run build
npm audit --omit=dev --audit-level=moderate

# Backend, preferred full reactor gate (JDK 17, Maven 3.9+)
mvn -B clean verify

# Targeted module example while iterating
mvn -B -pl :mail-imap-service -am test
```

For documentation-only changes, check local Markdown links and run
`git diff --check`. For POM, Dockerfile, or Compose changes, attach the
relevant build output or say explicitly that the Maven/Docker gate was not
run. Do not call a static parse “compilation” or a running frontend dev
server “full-stack runtime acceptance.”

Your PR description should include:

- what changed and why;
- which G0–G5 gates are affected;
- exact commands and results;
- what was not run and why;
- screenshots for UI changes or redacted logs for runtime failures;
- known risks and the next acceptance step.

If you are not ready for a PR, an issue containing the commit SHA,
JDK/Maven/Node/Docker versions, commands, and first error is already a real
contribution.

Contributions are accepted under Apache-2.0 (see [`LICENSE`](LICENSE) and
[`NOTICE`](NOTICE)).
