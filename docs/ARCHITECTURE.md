# Архитектура и пофайловая карта репозитория Help-CSL

Локальный подбор решений: `domain/LocalRecommendations.kt` (ранжирование по подтверждённым примерам и отзывам), `shared/LocalRecommendationsView.kt` (карточка), Android SettingsRepository и отдельное Windows AES-GCM хранилище. Без синхронизации; область разделена по Jira и логину.

Этот документ описывает полную структуру исходного кода **Help-CSL**, механизм переиспользования общего Kotlin-ядра между **Android** и **Windows Desktop**, потоки состояния и локальные хранилища.

---

## 1. Схема сборки двух платформ из единого ядра

Проект состоит из двух независимых Gradle-сборок, использующих общее бизнес-ядро без дублирования логики:

1. **Корневой проект (`.` + `:app`)** — Android-приложение (`com.android.application`, Kotlin 2.0.21, Compose Compiler, AGP 8.7).
2. **Подпроект `windows-desktop/`** — автономный Gradle-проект (`kotlin("jvm")`, `org.jetbrains.compose` 1.7.1, JDK 17 `jpackage`), который в `windows-desktop/build.gradle.kts` подключает общие пакеты из `../app/src/main/java`:

```text
app/src/main/java/com/helpdesk/jiraagent/
├── model/      ──► [ОБЩЕЕ ЯДРО: компилируется и в Android APK, и в Windows setup.exe]
├── domain/     ──► [ОБЩЕЕ ЯДРО: кроме SettingsTransferCodec.kt (на ПК свой кодек в DesktopStorage.kt)]
├── data/       ──► [ЧАСТИЧНО ОБЩЕЕ ЯДРО: 6 сетевых/парсерных клиентов общие; SettingsRepository.kt — только Android]
├── service/    ──► [ТОЛЬКО ANDROID: Foreground Service, Caller ID, Tile, Alarm/Boot receivers]
├── notification/─► [ТОЛЬКО ANDROID: пуш-каналы, двойной вывод звука BT+динамик, Android TTS]
├── update/     ──► [ТОЛЬКО ANDROID: скачивание APK, проверка SHA-256, PackageInstaller]
├── util/       ──► [ТОЛЬКО ANDROID: звонилка, мессенджеры Android, навигатор, Android Canvas PDF]
├── widget/     ──► [ТОЛЬКО ANDROID: виджет рабочего стола 4×2]
└── ui/         ──► [ТОЛЬКО ANDROID: MainViewModel + экраны Jetpack Compose]

windows-desktop/src/main/kotlin/com/helpdesk/desktop/
└── *           ──► [ТОЛЬКО WINDOWS: Compose Desktop UI, трей, попапы, PDFBox A4, AnyDesk/RDP/SIP/мессенджеры]
```

> **Важно для агентов:** любая правка в `model/**`, `domain/**` или в общих файлах `data/**` мгновенно влияет и на Android, и на Windows Desktop. Не добавляй туда зависимости `android.*`!

---

## 2. Полная пофайловая карта модулей

### 2.1. Пакет `model/` — Модели данных и JSON-кодеки комнат синхронизации
Расположение: `app/src/main/java/com/helpdesk/jiraagent/model/`

| Файл | Назначение |
|---|---|
| `AppSettings.kt` | Все настройки приложения: URL и тип Jira (`AUTO` / `SERVER_DC` / `CLOUD`), тип авторизации (`BASIC` / `CLOUD_TOKEN` / `PAT_BEARER`), SSL-доверие, интервалы опроса (днём и вне смены РБ), график смены (`09:00–17:30`), шаблоны ответов, настройки Zabbix (`DEFAULT_ZABBIX_MIN_SEVERITY = 5` Disaster), настройки Confluence Wiki, биометрия и тема оформления. |
| `FilterSettings.kt` | Настройки разделения клиентских тикетов и сервисного шума: ключи проектов (`projectKeys`), системные стоп-слова мониторинга (`zabbix`, `prometheus`, `nagios` и др.), игнорируемые авторы/типы задач. |
| `HelpdeskTicket.kt` | Главная модель заявки Jira (`key`, `summary`, `description`, `status`, `statusCategoryKey`, `stage: TicketStage [NEW, IN_PROGRESS, CLOSED]`, `reporterName`, `assigneeName`, `createdEpochMillis`, `phoneNumbers`, `comments`, `attachments`, `isClientTicket`, `filterReason`) + проверка принадлежности тикета текущему инженеру (`isAssignedToAgent`). |
| `PostponedTicket.kt` | Модель отложенной заявки (хранит все поля закрытого исходного тикета и `scheduledAtMillis` для автоматического пересоздания в назначенный час). |
| `OfflineQueuedAction.kt` | Элемент офлайн-очереди действий на Android (переходы статуса, комментарии, списание `Worklog`, отправка сообщений в чат при отсутствии сети). |
| `RepairEquipment.kt` | Модели журнала мастерской **«В ремонт»** (`REM-1001+`, этапы `RepairStatus`, комплектация, полка, мягкое удаление с аудитом `deletedBy`/`deletedAt`/`deleteReason`), справочника клиентов (`ClientDirectoryEntry`) и их сериализаторы `RepairEquipmentCodec` / `ClientDirectoryCodec` для синхронизации через Jira (`[HELPCSL_REPAIR_REGISTRY_ROOM]`). |
| `AdminChatMessage.kt` | Модели и кодек `AdminChatCodec` внутреннего чата инженеров (`HELPCSL_ADMIN_CHAT_ROOM`), закреплённой заметки смены (`pinnedShiftNote`), цитат, вложений-скриншотов и `@упоминаний`. |
| `ClientTechPassport.kt` | Модель и кодек `ClientTechPassportCodec` облачного **«🏢 Техпаспорта клиента»** (адреса, провайдер/шлюз, VPN/AnyDesk/RDP, серверы/1С/принтеры, контакты). |
| `ZabbixModels.kt` | Модели мониторинга Zabbix: `ZabbixSeverity` (`0..5`), `ZabbixProblem`, `ZabbixMutedRule` (правила вечного глушения нерешаемых триггеров), `ZabbixDashboardSnapshot`, логика `isHiddenFromActiveFeed()` и `shouldTriggerBanner()`. |

---

### 2.2. Пакет `domain/` — Чистая кроссплатформенная бизнес-логика
Расположение: `app/src/main/java/com/helpdesk/jiraagent/domain/`

| Файл | Назначение |
|---|---|
| `TicketFreshnessClassifier.kt` | Делит очереди «Новые» и «В работе» на **«Свежие»** (`FRESH_TODAY` — созданные с `00:00` текущих суток по `Europe/Minsk`) и **«Прошлые»** (`PAST_HANGING` — зависшие с прошлых дней), считает компактные бейджи возраста (`Сегодня 10:20`, `Вчера`, `Висит 7 дн.`) и уровень критичности зависания (`TicketHangingSeverity`). |
| `TicketFilterEngine.kt` | Классификатор `Клиентская заявка JSM` vs `Сервисная / Мониторинг`: распознаёт живые портальные типы обращений (`Service Request`, *«Проблемы с ПК»* и т.п.) и отсеивает автоматический шум (`zabbix`, `backup`, боты) во вкладку «Сервисные». |
| `BelarusWorkCalendar.kt` | Производственный календарь Республики Беларусь (`Europe/Minsk`): государственные праздники РБ, расчёт православной Пасхи и Радуницы (+9 дней), официальные переносы рабочих суббот Совмина РБ, пользовательские выходные/рабочие даты и утреннее окно показа GIF (`shouldShowMorningGif`). |
| `PhoneNumberNormalizer.kt` | Извлечение и приведение к `E.164` телефонных номеров Беларуси (`+375`) и России (`+7`) из произвольного текста заявок (`8 029 ...`, `8017...`, `+7 (916)...`). |
| `SysadminEntityExtractor.kt` | Автораспознавание в тексте и комментариях тикета ID `AnyDesk` / `RustDesk`, IPv4-адресов, корпоративных доменов, серийных номеров (`S/N`, `INV`), MAC-адресов (с офлайн-базой OUI вендоров) и физических уличных адресов для навигатора. |
| `DomainDiagnostics.kt` и `shared/DomainDiagnosticCardView.kt` | Офлайн-карточка AD с ролью, точкой запуска, безопасными командами и отдельно подтверждаемым копированием скрипта восстановления участника домена. |
| `DiagnosticScenarios.kt` и `shared/DiagnosticScenariosView.kt` | Общие справочные сценарии, ручные статусы и заметки; копируемый отчёт без выполнения команд или отправки в Jira. |
| `DnsLookupTool.kt` | Ручной DNS к явно указанному IPv4 серверу: A/AAAA/MX/TXT/NS/AD SRV, UDP с TCP fallback; без автоматического публичного DNS. |
| `NetworkDiagnosticsTool.kt` | Сетевой комбайн: ICMP/TCP Ping, проверка портов (`22, 80, 443, 445, 3389, 8291` и расширенный список), независимые HTTP/HTTPS HEAD-зонды на 80/443, TLS с проверкой доверия и имени и остатка дней сертификата (`X509Certificate`), разрешение адресов системным резолвером (без MX/TXT/NS и автоматических публичных DoH-запросов). |
| `SysadminToolboxEngine.kt` | Инженерные утилиты: отправка `Wake-on-LAN` Magic Packet (UDP), IPv4/CIDR калькулятор подсетей, криптостойкий генератор паролей и карточек доступа, а также движок проверки `Watchlist`-узлов (онлайн/офлайн + срок SSL). |
| `SlaCalculator.kt` | Расчёт времени ожидания реакции и цветовой срочности SLA (`< 15 мин` зелёный, `15–45 мин` янтарный, `> 45 мин` красный). |
| `PostponeDateTimeParser.kt` | Парсинг быстрых пресетов и ручного ввода даты/времени отсрочки заявки («Через 1 час», «Завтра в 09:30», «В понедельник в 10:00»). |
| `ClientRequestAnalysis.kt` и `shared/ClientRequestAnalysisView.kt` | Справочные категории по заголовкам, выбор автора или организации JSM и переход к заявкам; данные ограничены правами и выборкой. |
| `ShiftAnalyticsCalculator.kt` | Подсчёт KPI смены за Сегодня / 7 / 30 дней (принято, закрыто, % SLA, среднее время реакции, нагрузка по инженерам, топ организаций) и экспорт сводки в CSV (`UTF-8 BOM`). |
| `EngineerNameResolver.kt` | Преобразование технических логинов/e-mail в читаемые **Имя и Фамилию** сотрудника для карточек ремонта, чата и шапки PDF-актов. |
| `QuickReplyTemplates.kt` | Шаблоны быстрых ответов клиенту с подстановкой переменных `{клиент}`, `{заявка}`, `{инженер}`. |
| `TicketStopwatchTracker.kt` | Секундомер фактического времени работы над заявкой (`старт / пауза / сброс`) с округлением минут для списания в `Worklog` Jira. |
| `SettingsTransferCodec.kt` | Кодек зашифрованного QR-переноса настроек `HELPCSL1:` (`GZIP` + `PBKDF2WithHmacSHA256` + `AES-256-GCM`) для Android *(на Windows идентичный протокол реализован в `DesktopStorage.kt`)*. |

---

### 2.3. Пакет `data/` — Сетевые клиенты и хранилища
Расположение: `app/src/main/java/com/helpdesk/jiraagent/data/`

| Файл | Платформа | Назначение |
|---|---|---|
| `JiraApiClient.kt` | Android + Windows | Полный клиент Jira REST API v2 (Server/DC) и v3 (Cloud): многозапросная загрузка очередей, определение числовых ID статусов (совместимость с русской локалью!), переходы статусов, назначение исполнителя, комментарии (публичные + внутренние заметки JSM `sd.public.comment`), `Worklog`, вложения, создание/пересоздание тикетов, поиск по `S/N` и архиву решений, синхронизация комнат `В ремонт`, `Чат` и `Техпаспортов`. |
| `JiraJsonParser.kt` | Android + Windows | Устойчивый парсинг ответов Jira v2 и Atlassian Document Format (ADF v3), JSM Customer Request Types, комментариев, истории клиента и организаций. |
| `ConfluenceWikiClient.kt` | Android + Windows | Клиент базы знаний Confluence (`https://w.csl.by`): авторизация по PAT / Basic / `dologin.action` / WebView cookies (2FA), загрузка пространств (`Spaces`), дерева дочерних страниц, алфавитного каталога клиентов, CQL-поиск, парсинг таблиц/кода/вложений Confluence Storage XHTML и полная выгрузка в офлайн-БД. |
| `ConfluenceOfflineStore.kt` | Android + Windows | Файловое хранилище полной офлайн-копии `w.csl.by` с мгновенным локальным поиском без сети и защитной очисткой при блокировке аккаунта сотрудника. |
| `ZabbixApiClient.kt` | Android + Windows | Клиент Zabbix JSON-RPC (`https://z.csl.by`): автоопределение версии API (`apiinfo.version` без заголовка авторизации!), вход `user.login` (совместимость с Zabbix 5.x/6.x/7.x), выгрузка триггеров/проблем/хостов, подтверждение событий `event.acknowledge`. |
| `HabrNewsRepository.kt` | Android + Windows | Загрузка RSS-лент `habr.com/ru` (*Новости, Сисадмин, Инфобез, Лучшие*) с 10-минутным кэшем в памяти и встроенный парсер полного текста статей (абзацы, заголовки, код, цитаты, иллюстрации). |
| `SettingsRepository.kt` | Только Android | Криптохранилище Android (`EncryptedSharedPreferences` AES-256-GCM с fallback): хранит настройки, кэш заявок, отложенные тикеты, офлайн-очередь, журнал ремонта, чат, досье клиентов, правила глушения Zabbix и миграции версий настроек. |

---

### 2.4. Android-специфичные пакеты (`app/src/main/java/com/helpdesk/jiraagent/`)

- **Точки входа**:
  - `HelpdeskApp.kt` — `Application`: инициализация каналов уведомлений и фоновых служб.
  - `MainActivity.kt` — корневой `Scaffold` с верхней панелью, нижней навигацией (`Заявки`, `Zabbix`, `Ремонт`, `Wiki`, `Чат`, `Настройки`), биометрической блокировкой, плавающими in-app баннерами и глобальным `BackHandler` возврата на экран **«Свежие»**.
  - `ui/MainViewModel.kt` — единый координатор состояния Android-приложения (`StateFlow`), фоновых опросов, действий по заявкам, чата, мастерской, Wiki, Zabbix и экспорта отчётов.
- **Экраны (`ui/screens/`)**:
  - `TicketsScreen.kt` — главный экран очередей (`Новые` / `В работе` / `Закрытые` / `Сервисные`), подфильтры `Все | ● Свежие | ◷ Прошлые` (по умолчанию **«Свежие»** при старте и по кнопке «Назад»), свайпы карточек, массовый выбор, сканер `S/N`.
  - `TicketDetailScreen.kt` — карточка заявки: действия workflow, комментарии/внутренние заметки JSM, секундомер, `Worklog`, вложения с зумом, сетевой комбайн, `🏢 Техпаспорт`, история клиента и генерация PDF-акта с подписью.
  - `ZabbixMonitorScreen.kt` — панель мониторинга `z.csl.by` (активные проблемы, скрытые, подтверждение `Ack`, вечное глушение триггеров).
  - `RepairRegistryScreen.kt` — мастерская «В ремонт» (`REM-1001+`), автоподстановка клиентов Helpdesk, печать PDF-квитанций и QR-этикеток `58×40 мм`, вкладка аудита «🗑 Удалённые».
  - `ConfluenceWikiScreen.kt` — клиент `w.csl.by` (нативный режим чтения + офлайн-синхронизация + встроенный `WebView` для 2FA).
  - `AdminChatScreen.kt` — внутренний мессенджер смены с закрепом, фото и `@упоминаниями`.
  - `ServerSettingsScreen.kt` и `FilterSettingsScreen.kt` — настройки сервера, графика РБ, звука, интервала ночного опроса и правил фильтрации.
- **Компоненты (`ui/components/`)**:
  - `SysadminEmptyGifCard.kt` — утренние GIF Роя и Мосса + встроенная лента и читалка статей Habr.com.
  - `SysadminTicketTools.kt` — панель распознанных AnyDesk/RustDesk/IP/MAC/адресов и карточка диагностики хоста.
  - `SysadminToolboxAndKbDialogs.kt` — диалоги «🛠 Инструменты» (WoL, CIDR, генератор паролей, Watchlist) и «📚 База решений».
  - `FieldEngineerTools.kt` — холст подписи клиента пальцем на экране, диалог послезвонкового `Worklog`, QR-перенос настроек и результаты поиска по `S/N`.
  - `ShiftAnalyticsAndGuideDialogs.kt` — «📊 Статистика смены» и встроенный «📖 Полный гайд».
  - `ClickablePhoneText.kt` и `MessengerIcons.kt` — кликабельные телефоны и векторные кнопки звонка / Telegram / Viber / WhatsApp.
- **Фоновые службы, уведомления и утилиты**:
  - `service/JiraMonitorService.kt` — Foreground Service постоянного мониторинга Jira, Zabbix, отложенных заявок, ежедневной офлайн-синхронизации Wiki и 5-минутной проверки Watchlist-узлов.
  - `service/HelpCslCallScreeningService.kt` — системный АОН (Caller ID) входящих звонков клиентов без чтения журнала вызовов.
  - `service/HelpCslDutyTileService.kt` — плитка быстрых настроек в шторке Android.
  - `service/PostponeScheduler.kt`, `PostponeAlarmReceiver.kt`, `BootReceiver.kt`, `OfflineQueueProcessor.kt` — точные будильники пересоздания отложенных тикетов, автозапуск после перезагрузки и досылка офлайн-очереди.
  - `notification/NotificationHelper.kt`, `AlertSoundPlayer.kt`, `TicketSpeechAnnouncer.kt`, `NotificationActionReceiver.kt` — Heads-Up баннеры с кнопкой «В работу», двойной вывод звука (динамик + Bluetooth на MIUI/HyperOS) и голосовое чтение TTS.
  - `util/PdfServiceActGenerator.kt`, `DialerLauncher.kt`, `MessengerLauncher.kt`, `NavigatorLauncher.kt` — генерация A4 PDF на Android, набор номера, мессенджеры и запуск навигатора.
  - `update/AppUpdateManager.kt` и `widget/HelpCslAppWidgetProvider.kt` — автообновление APK через Atom-ленту GitHub Releases и виджет `4×2`.

---

### 2.5. Windows Desktop (`windows-desktop/src/main/kotlin/com/helpdesk/desktop/`)

| Файл | Назначение |
|---|---|
| `Main.kt` | Точка входа `main()`, главное окно Compose Desktop, системный трей Windows с динамическим бейджем числа новых заявок, Drag-and-Drop файлов в тикет, горячие клавиши (`F5`, `Alt+←`, `Esc`), перехват боковой кнопки мыши `Back` (`MouseEvent.BUTTON4`) и верхняя навигационная шапка с кнопкой `← Назад`. |
| `DesktopController.kt` | Центральный контроллер состояния `DesktopUiState` (`StateFlow`): фоновые корутины опроса Jira / Zabbix / Чата / Ремонта / Watchlist / ежедневной офлайн-базы Wiki, навигация `navigateBackToFreshHome()` (основной экран **«Свежие»**), действия по заявкам и автообновление `setup.exe`. |
| `DesktopTicketViews.kt` | Двухпанельное рабочее место «Заявки»: левая очередь (4 симметричные вкладки, подфильтры `Все | ● Свежие | ◷ Прошлые`, однострочные разделители секций, лента Habr) + правая панель (стартовый дашборд инженера, полный инспектор тикета с `FlowRow`-панелями кнопок и `← Назад`, встроенная читалка статей Habr). |
| `DesktopZabbixViews.kt` | Рабочая вкладка мониторинга Zabbix (`z.csl.by`): настройка подключения, KPI-бейджи, подтверждение `Ack`, скрытие и вечное глушение триггеров, диагностика узла. |
| `DesktopWikiAndToolsViews.kt` | Вкладка Confluence Wiki (`w.csl.by` с алфавитным указателем клиентов, деревом страниц, прогресс-баром офлайн-синхронизации и копированием ячеек таблиц) + окно «🛠 Инструменты сисадмина» (`WoL`, `CIDR`, пароли, карточка узла `Watchlist`). |
| `DesktopRepairAndChatViews.kt` | Вкладка мастерской «В ремонт» (`REM-1001+`, автоподстановка клиентов Helpdesk, экспорт в Excel `.csv`, печать A4 PDF и QR-этикеток, аудит «🗑 Удалённые») и вкладка внутреннего «Чата» инженеров (закреп смены, вставка скриншотов `Ctrl+V`, `@упоминания`). |
| `DesktopSettingsAndDialogsViews.kt` | Вкладки «Фильтр» и «Настройки», диалоги «📊 Статистика смены», «⊞ QR» переноса настроек и встроенный справочник «📖 Гайд». |
| `DesktopSystemIntegrations.kt` | Нативные интеграции Windows: прямой запуск `AnyDesk.exe`, `rustdesk.exe`, `mstsc.exe` (RDP), `ssh`, SIP `tel:`, прямой запуск десктопных `Telegram.exe` / `Viber.exe` / `WhatsApp.exe` (без браузера!), чтение скриншотов `Win+Shift+S` из буфера обмена, генерация A4 PDF на Apache PDFBox с кириллическим шрифтом Arial/Segoe UI, всплывающие поверх окон баннеры `DesktopNativePopupManager` и двухрежимный звук `DesktopSoundPlayer`. |
| `DesktopStorage.kt` | Локальное шифрование состояния `AES-256-GCM` в `%APPDATA%\Help-CSL\state.enc`, управление автозапуском в реестре Windows (`HKCU\...\Run`) и десктопный кодек `HELPCSL1:`. |
| `DesktopTheme.kt` | Светлая и тёмная темы `HelpCslDesktopTheme`, палитра `DesktopColors` и адаптивный компонент `DesktopHorizontalScrollRow`. |

---

## 3. Где хранятся локальные данные на устройствах

- **Android**:
  - `EncryptedSharedPreferences` (`helpdesk_secure_prefs` / fallback `helpdesk_plain_prefs`) — учётные данные, настройки, кэш тикетов, журнал ремонта, чат, техпаспорта и досье клиентов;
  - `context.filesDir/confluence_offline/` — сжатая локальная офлайн-копия страниц Confluence Wiki (`w.csl.by`).
- **Windows 10/11**:
  - `%APPDATA%\Help-CSL\state.enc` — зашифрованное состояние `AES-256-GCM` (настройки, пароли/токены, кэш тикетов, отложенные тикеты, досье, секундомеры, правила Zabbix);
  - `%APPDATA%\Help-CSL\confluence_offline\` — локальная офлайн-база знаний Confluence Wiki.

---

## 4. Покрытие модульными тестами

| Тестовый набор | Что проверяет |
|---|---|
| `app/src/test/.../TicketFreshnessClassifierTest.kt` | Точное разделение заявок на «Свежие» (`FRESH_TODAY` с `00:00` по Минску) и «Прошлые» (`PAST_HANGING`), расчёт дней зависания и бейджей. |
| `app/src/test/.../TicketFilterAndParserTest.kt` | Отделение живых клиентов JSM от сервисных алертов Zabbix/ботов, парсинг JSON Jira v2/v3, поиск активных заявок в обход лавины мониторинга (mock HTTP-сервер). |
| `app/src/test/.../BelarusWorkCalendarTest.kt` | Расчёт рабочего времени РБ, праздников, Радуницы, рабочих суббот и утреннего показа GIF. |
| `app/src/test/.../PhoneNumberNormalizerTest.kt` | Извлечение и нормализация номеров `+375` и `+7` из сложных форматов записи. |
| `app/src/test/.../ZabbixIntegrationTest.kt` | Полный цикл JSON-RPC клиента Zabbix на mock-сервере (`apiinfo.version` без auth, `user.login`, `problem.get`, `event.acknowledge`, скрытие и вечное глушение, порог `Disaster = 5`). |
| `app/src/test/.../ConfluenceWikiAndNineFeaturesTest.kt` | Парсинг Confluence XHTML, офлайн-хранилище Wiki, секундомер тикетов, техпаспорт клиента, `SysadminToolboxEngine` (CIDR, пароли). |
| `app/src/test/.../SysadminImprovementsTest.kt` | Извлечение AnyDesk/RustDesk/IP/MAC/S/N, кодеки «В ремонт» (вкл. аудит «Удалённые»), справочник клиентов и аналитика смены. |
| `app/src/test/.../AppUpdateAndGifCatalogTest.kt` | Парсинг версий из Atom-ленты GitHub Releases и проверка ресурсов GIF. |
| `windows-desktop/src/test/.../DesktopPdfGeneratorTest.kt` | Генерация кириллических A4 PDF-актов и квитанций ремонта на Windows через Apache PDFBox. |
