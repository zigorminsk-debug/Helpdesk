# CLAUDE.md — Точка входа для ИИ-агента

Вся архитектурная документация, инварианты кроссплатформенной сборки (Android + Windows Desktop), протоколы интеграций (`Jira`, `Confluence w.csl.by`, `Zabbix z.csl.by`) и правила работы с репозиторием собраны в:

1. **[`AGENTS.md`](AGENTS.md)** — **обязательное руководство для любого ИИ-агента** (прочитай первым делом!).
2. **[`docs/INDEX.md`](docs/INDEX.md)** — оглавление всех технических разделов:
   - [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — пофайловая карта модулей `app/`, общего ядра и `windows-desktop/`;
   - [`docs/INTEGRATIONS_AND_PROTOCOLS.md`](docs/INTEGRATIONS_AND_PROTOCOLS.md) — спецификация API Jira v2/v3, Confluence, Zabbix, комнат синхронизации и QR-кодека `HELPCSL1:`;
   - [`docs/UI_AND_NAVIGATION.md`](docs/UI_AND_NAVIGATION.md) — дизайн-система `csl.by`, главный экран **«Свежие»** при старте и по кнопке **«Назад»**, адаптивность UI;
   - [`docs/CI_CD_AND_RELEASE.md`](docs/CI_CD_AND_RELEASE.md) — конвейер GitHub Actions, подпись APK, сборка `setup.exe` и модульные тесты;
   - [`docs/CHANGELOG.md`](docs/CHANGELOG.md) — хронология всех версий и изменений.
3. **[`GUIDE.md`](GUIDE.md)** — полное пользовательское руководство инженера.
