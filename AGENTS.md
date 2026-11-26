# AGENTS.md — Руководство для ИИ-агентов и разработчиков Help-CSL

Этот документ — **главная точка входа для любого ИИ-агента**, продолжающего разработку проекта **Help-CSL** (`zigorminsk-debug/Helpdesk`).  
Перед внесением изменений обязательно ознакомься с этим файлом и профильными разделами в папке [`docs/`](docs/INDEX.md).

---

## 1. Краткая суть проекта

**Help-CSL** — кроссплатформенное рабочее место системного администратора, диспетчера и выездного инженера службы технической поддержки ООО «Компьютерная безопасность» (`csl.by`, г. Минск):
- **Android-приложение (`app/`)** — Kotlin + Jetpack Compose + Material 3 (`minSdk = 26`, `targetSdk = 35`), фоновый `JiraMonitorService`, `CallScreeningService` (Caller ID), камерный сканер штрихкодов/QR, подпись PDF пальцем, офлайн-очередь, виджет `4×2`.
- **Windows Desktop-приложение (`windows-desktop/`)** — Kotlin/JVM 17 + Compose Multiplatform Desktop + автономный инсталлятор **`setup.exe`** (`jpackage` со встроенной JRE 17 + Inno Setup 6, без необходимости устанавливать Java и без прав администратора).
- **Без собственного промежуточного бэкенда** — обе платформы работают напрямую с корпоративными сервисами:
  1. **Jira Server / Data Center** (REST API v2, в т.ч. русская локаль и JSM) и **Jira Cloud** (REST API v3);
  2. **Confluence Wiki** (`https://w.csl.by`, REST API + HTML fallback + полная локальная офлайн-БД);
  3. **Zabbix Monitoring** (`https://z.csl.by`, JSON-RPC 5.x / 6.x / 7.x);
  4. **Командная синхронизация через служебные тикеты-комнаты Jira** (`[HELPCSL_REPAIR_REGISTRY_ROOM]`, `HELPCSL_ADMIN_CHAT_ROOM`, техпаспорта клиентов);
  5. **Habr.com RSS + встроенный парсер статей** (чтение внутри приложения без браузера).

---

## 2. Навигация по документации («разложено по полочкам»)

| Файл | Для чего читать |
|---|---|
| [`AGENTS.md`](AGENTS.md) *(этот файл)* | Главные архитектурные инварианты, критические подводные камни и чек-лист выпуска релиза |
| [`docs/INDEX.md`](docs/INDEX.md) | Полный каталог инженерной документации проекта |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Пофайловая карта всего репозитория (`app/`, общие пакеты `model`/`domain`/`data`, `windows-desktop/`, хранилища и тесты) |
| [`docs/INTEGRATIONS_AND_PROTOCOLS.md`](docs/INTEGRATIONS_AND_PROTOCOLS.md) | Протоколы Jira v2/v3 (вкл. русскую локаль JQL!), Confluence `w.csl.by`, Zabbix `z.csl.by`, комнаты синхронизации и QR `HELPCSL1:` |
| [`docs/UI_AND_NAVIGATION.md`](docs/UI_AND_NAVIGATION.md) | Дизайн-система `csl.by`, контракт главного экрана **«Свежие»** и кнопки **«Назад»**, адаптивность под крупный шрифт Android и узкие окна Windows |
| [`docs/CI_CD_AND_RELEASE.md`](docs/CI_CD_AND_RELEASE.md) | Сборка и тесты через GitHub Actions, постоянный ключ подписи APK, упаковка `setup.exe` и автообновление |
| [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | Структурированная история релизов и архитектурных решений по версиям (`v1.0.1` → `v1.0.70+`) |
| [`README.md`](README.md) | Публичная витрина репозитория, схема архитектуры и ссылки на скачивание |
| [`GUIDE.md`](GUIDE.md) | Полное пользовательское руководство инженера (синхронизировано со встроенной кнопкой `📖 Гайд`) |

---

## 3. КРИТИЧЕСКИЕ ИНВАРИАНТЫ И ПРАВИЛА (НЕ НАРУШАТЬ!)

### Правило №1: Общий исходный код между Android и Windows Desktop
Открой `windows-desktop/build.gradle.kts` — настольный проект напрямую компилирует общие файлы из `../app/src/main/java`:
- `com/helpdesk/jiraagent/model/**`
- `com/helpdesk/jiraagent/domain/**` *(кроме исключённого `SettingsTransferCodec.kt`, у которого на Desktop своя реализация в `DesktopStorage.kt`)*
- 6 файлов из `data/`: `JiraApiClient.kt`, `JiraJsonParser.kt`, `HabrNewsRepository.kt`, `ConfluenceWikiClient.kt`, `ConfluenceOfflineStore.kt`, `ZabbixApiClient.kt`.

**Строгий запрет:** в этих общих файлах **нельзя использовать `android.*` импорты** (`android.util.Base64`, `android.util.Log`, `android.net.Uri`, `android.content.Context` и т.п.) — иначе сломается сборка `windows-desktop`! Используй только стандартный JDK 17 (`java.util.Base64`, `java.net.URI`, `java.time.*`, OkHttp 4, Gson).

---

### Правило №2: Основной экран «Свежие» при старте и по кнопке «Назад» (Android + Windows)
- При запуске приложения (и на Android, и на Windows Desktop) всегда открывается экран **«Заявки» → вкладка «Новые» → подфильтр «● Свежие»** (`TicketAgeFilter.FRESH_TODAY`, заявки за текущие сутки по `Europe/Minsk`).
- Нажатие **«Назад»** из любого места возвращает пользователя на основной экран **«Заявки → Новые → ● Свежие»**:
  - **Android**: системный жест/кнопка `Back` из `TicketDetailScreen` (`viewModel.closeTicketDetail()`), из любой другой нижней вкладки (`Zabbix`, `Ремонт`, `Wiki`, `Чат`, `Настройки`), а также из других вкладок/подфильтров внутри `TicketsScreen` (`Прошлые`, `Все`, `В работе`, `Закрытые`, `Сервисные`, активный поиск) возвращает на `Новые → Свежие`. Повторное нажатие `Back` уже на экране `Свежие` штатно сворачивает приложение.
  - **Windows Desktop**: кнопка `← Назад` в верхней шапке и в карточке тикета, закрытие статьи Habr, боковая кнопка мыши `Back` (`MouseEvent.BUTTON4`), `Alt + ←` и `Esc` вызывают `controller.navigateBackToFreshHome()`.

---

### Правило №3: Русская локаль Jira Server / Data Center — НИКОГДА не пиши `statusCategory = Done`
На корпоративных серверах Jira с русской локалью строковый JQL `statusCategory = Done` или `statusCategory != Done` возвращает **HTTP 400** (`The value 'Done' does not exist for the field 'statusCategory'`).
- Всегда определяй числовые ID статусов через `/rest/api/2/status` (`statusCategory.key == "done"` / `"indeterminate"` / `"new"`), используй числовые `statusCategory = 3` / `statusCategory != 3` или `resolution is EMPTY / is not EMPTY` с автоматическим fallback в `JiraApiClient.kt`.
- Не полагайся на одну общую выборку `ORDER BY updated DESC`: лавина технических тикетов Zabbix/бэкапов вытесняет живые тикеты «В работе» за лимит. Сохраняй многозапросную стратегию `JiraApiClient.fetchTickets()` (основная лента + прицельная подгрузка активных `IN_PROGRESS` и человеческих очередей JSM).

---

### Правило №4: Запуск мессенджеров и протоколов на Windows — НЕ вызывай `Desktop.browse()` для мессенджеров
В OpenJDK 17 на Windows `Desktop.getDesktop().browse(uri)` вызывает `ShellExecute("open", uri)` и на `https://t.me/...`, `https://wa.me/...` или незарегистрированных URI открывает браузер вместо приложения.
- В `DesktopSystemIntegrations.kt` (`WindowsExternalLaunchers`) сначала ищутся установленные исполняемые файлы (`Telegram.exe`, `Viber.exe`, `WhatsApp.exe` и обработчики реестра `HKCU\Software\Classes\<scheme>`), и только они запускаются напрямую через `ProcessBuilder`. Не заменяй эту логику обратно на `openBrowser()`.

---

### Правило №5: Zabbix (`z.csl.by`) — звук и срочные баннеры СТРОГО только на `Disaster (5)`
- По требованию эксплуатации звуковой сигнал и всплывающий баннер Zabbix срабатывают **только на уровень `Disaster` (`severity == 5`, `DEFAULT_ZABBIX_MIN_SEVERITY = 5`)**.
- В Zabbix JSON-RPC метод `apiinfo.version` **обязан вызываться БЕЗ заголовка `Authorization` и без поля `auth`** (Zabbix 6.4+/7.0 возвращает ошибку при передаче токена в `apiinfo.version`).
- У `JiraApiClient` и `ZabbixApiClient` — **изолированные `OkHttpClient` пулы** с HTTP/2 ping и автоповтором при смене сети (`Wi-Fi ↔ LTE`).

---

### Правило №6: Confluence Wiki (`w.csl.by`) — Server/DC + 2FA + Офлайн-база + Защита при увольнении
- На `w.csl.by` (Confluence Server/DC) эндпоинт `/rest/api/user/current` отсутствует (404 — это Cloud API). Проверка доступа выполняется через `/rest/api/space?limit=1` с HTML fallback.
- Поддерживается вход по PAT, Basic + `dologin.action` и cookie-мост из встроенного `WebView` (для прохождения корпоративной 2FA).
- При ответе `401 / 403` с признаком заблокированной/удалённой учётной записи сотрудника в Jira/Confluence срабатывает автоочистка локального офлайн-хранилища (`ConfluenceOfflineStore.clearAll()`).

---

### Правило №7: UI/UX — крупный системный шрифт Android и отсутствие лишних скроллбаров на Windows
- **Android**: пользователи часто включают увеличенный масштаб шрифта (`fontScale = 1.25–1.35`). Во всех компактных вкладках, пилюлях фильтров и заголовках карточек используй `with(LocalDensity.current) { X.dp.toSp() }`, `maxLines = 1`, `softWrap = false`, `IntrinsicSize.Min` + `fillMaxHeight()`, чтобы текст никогда не рвался по буквам и плашки имели одинаковую высоту.
- **Windows Desktop**:
  - Для групп кнопок действий и чипов фильтров используй `FlowRow` (органичный перенос без горизонтальных скроллбаров), а не `DesktopHorizontalScrollRow(showScrollbar = true)`.
  - Кнопка сворачивания окна (`_`) должна штатно сворачивать окно в **панель задач Windows (Taskbar)**; скрытие в системный трей происходит **только при закрытии окна (`✕`)**, когда включена настройка `minimizeToTray`.

---

### Правило №8: Постоянная подпись Android APK (`keystore/helpdesk-release.p12`)
- Файлы `keystore/helpdesk-release.p12` и `keystore/signing.properties` намеренно хранятся в репозитории (см. `.gitignore`).
- **Никогда не удаляй и не меняй ключ подписи** — благодаря единому сертификату все новые версии `help-csl-v1.0.XX.apk` устанавливаются на телефоны инженеров **поверх текущей версии** без удаления приложения и без потери настроек!

---

### Правило №9: СТРОГИЙ ЗАПРЕТ на автосоздание служебных тикетов `[HELPCSL_*_ROOM]` в Jira
- **Никогда автоматически не вызывай `POST /rest/api/2/issue`** для создания служебных задач `[HELPCSL_REPAIR_REGISTRY_ROOM]` или `[HELPCSL_ADMIN_CHAT_ROOM]`!
- На серверах JSM с русским Lucene-анализатором квадратные скобки `[HELPCSL_...]` не находятся запросом `summary ~ "HELPCSL_..."`, поле `labels` может отклоняться схемой экрана создания (HTTP 400), а прямой переход в `Done` из «Ожидание поддержки» недоступен — из-за чего автосоздание приводило к бесконечному размножению открытых служебных заявок в живой очереди Jira.
- Синхронизация чата/ремонта через Jira работает **только** если пользователь вручную указал конкретный номер задачи в `AppSettings.adminChatIssueKey` (иначе работает локальное зашифрованное хранилище). Для удаления/закрытия старых дублей используется `JiraApiClient.cleanupSystemRoomIssues(...)` (выполняется автоматически один раз при старте `v1.0.70+` и доступна по кнопке `🧹 Убрать дубли [HELPCSL_*_ROOM] из Jira` в Настройках).

---

## 4. Особенности среды разработки и CI/CD для агентов

1. **Локальный запуск Gradle в облачной песочнице**:
   - В базовом контейнере песочницы может отсутствовать JDK и Android SDK (`JAVA_HOME is not set`).
   - Вся полная компиляция Kotlin, прогон модульных JUnit-тестов (`testReleaseUnitTest` + `:windows-desktop:test`), подпись APK (`apksigner`) и сборка `setup.exe` (`jpackage` + Inno Setup 6) выполняются автоматически в **GitHub Actions** (`.github/workflows/build-apk.yml`) при каждом `git push` в текущую рабочую ветку (~3.5–4 минуты).
2. **Проверка локального `git` перед коммитом в песочнице**:
   - После холодного перезапуска песочницы `.git/HEAD` может указывать на начальный коммит `32615ef`, тогда как файлы в рабочей директории соответствуют последнему коммиту ветки.
   - Перед `git status` / `git commit` всегда проверь `git log -n 2 --oneline`. Если там только `32615ef`, выполни неразрушающую синхронизацию индекса:
     ```bash
     git fetch origin <текущая-ветка> && git reset --mixed origin/<текущая-ветка>
     ```
3. **Чек-лист перед каждым пушем**:
   - Проверь баланс скобок `{}` / `()` / `[]` и наличие всех `import` во всех изменённых `.kt` файлах;
   - Убедись, что в общие пакеты (`model/`, `domain/`, общие файлы `data/`) не попали `android.*` импорты;
   - При изменении пользовательских функций синхронно обнови встроенный справочник (`ShiftAnalyticsAndGuideDialogs.kt` и `DesktopSettingsAndDialogsViews.kt`) и документацию (`GUIDE.md` / `docs/CHANGELOG.md`);
   - После `git push` обязательно дождись завершения workflow через `gh run watch <run_id> --exit-status` и проверь опубликованный релиз через `gh release view`.
