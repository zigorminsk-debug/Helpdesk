# Сборка, модульные тесты и CI/CD релизы Help-CSL

В этом документе описан автоматизированный конвейер сборки, подписи и публикации релизов **Help-CSL** для **Android** и **Windows 10/11**.

---

## 1. Конвейер GitHub Actions (`.github/workflows/build-apk.yml`)

Каждый `git push` в любую ветку репозитория (а также ручной запуск `workflow_dispatch`) запускает 3 связанные задачи:

### Задача 1: `build-apk` (`ubuntu-latest`, JDK 17)
1. Вычисляет единый сквозной номер релиза на основе `GITHUB_RUN_NUMBER`:
   - `VERSION_CODE = 1000 + GITHUB_RUN_NUMBER`
   - `VERSION_NAME = 1.0.${GITHUB_RUN_NUMBER}`
2. Проверяет целостность постоянного ключа подписи `keystore/helpdesk-release.p12`.
3. Запускает модульные тесты и релизную сборку Android:
   ```bash
   ./gradlew testReleaseUnitTest assembleRelease \
     -PversionCode="$VERSION_CODE" \
     -PversionName="$VERSION_NAME"
   ```
4. Проверяет криптографическую подпись готового APK утилитой `apksigner verify --verbose --print-certs` и считает `SHA-256`.

### Задача 2: `build-windows` (`windows-latest`, JDK 17 + `jpackage` + Inno Setup 6)
1. Прогоняет тесты настольного модуля и собирает автономный образ приложения со встроенной средой исполнения Java (`Help-CSL.exe` + `runtime/`):
   ```powershell
   .\gradlew.bat -p windows-desktop test createDistributable `
     "-PversionCode=$versionCode" `
     "-PversionName=$versionName"
   ```
2. Компилирует скрипт `windows-desktop/installer/help-csl.iss` через **Inno Setup 6 (`ISCC.exe`)** с русской локализацией `Russian.isl`.
3. Формирует два идентичных бинарных файла установщика (`PrivilegesRequired=lowest` — установка в профиль пользователя без запроса прав администратора UAC):
   - `setup.exe` *(постоянное имя для прямой ссылки `/releases/latest/download/setup.exe`)*;
   - `Help-CSL-Setup-v1.0.XX.exe` *(архивная копия с номером версии в имени)*.

### Задача 3: `publish-release` (`ubuntu-latest`)
- Собирает артефакты обеих платформ и публикует единый релиз **GitHub Releases** с тегом `v1.0.${GITHUB_RUN_NUMBER}` и контрольными суммами `SHA-256`.

---

## 2. Постоянный ключ подписи Android (`keystore/`)

В папке `keystore/` хранятся:
- `keystore/helpdesk-release.p12` — постоянный сертификат подписи релизных сборок `com.helpdesk.jiraagent`;
- `keystore/signing.properties` — параметры подключения ключа в `app/build.gradle.kts`.

> **Критически важно:** не заменяй `helpdesk-release.p12`. Единая подпись гарантирует, что Android позволяет обновлять приложение поверх установленной версии (в т.ч. через встроенный автоапдейтер) без ошибки конфликта пакетов и без потери зашифрованных настроек.

---

## 3. Механизм встроенного автообновления (Android + Windows)

Обе версии проверяют выход новых релизов через публичную **Atom-ленту GitHub Releases** (`https://github.com/zigorminsk-debug/Helpdesk/releases.atom`), а не через JSON REST API GitHub:
- Это полностью исключает ошибку `HTTP 403 rate limit exceeded` на офисных NAT-адресах, где много сотрудников выходят в интернет с одного внешнего IP.
- **На Android** (`AppUpdateManager.kt`): скачивает `help-csl-v1.0.XX.apk`, сверяет размер/целостность и открывает системный установщик обновления.
- **На Windows** (`DesktopController.downloadAndRunWindowsSetup`): скачивает свежий `setup.exe` во временный каталог, запускает установщик и корректно завершает текущий процесс для бесшовного обновления файлов.

---

## 4. Локальные команды сборки (при наличии JDK 17)

```bash
# Прогон модульных тестов и сборка подписанного Android APK:
./gradlew testReleaseUnitTest assembleRelease -PversionCode=1070 -PversionName=1.0.70

# Прогон тестов и запуск настольного клиента Windows/Linux/macOS из исходников:
./gradlew -p windows-desktop test run
```
