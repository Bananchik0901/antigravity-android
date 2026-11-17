# Общее обновление и самоапдейтер (Antigravity Android)

Этот документ описывает архитектуру встроенного самоапдейтера (`com.agy.updater.*`), источники обновлений Ядра, Обхода и готовых APK на GitHub Releases, а также механизм отката.

---

## 1. Источники обновлений (ссылки)

1. **Ядро (`language_server-linux-arm64`) — официальный Google Antigravity Hub:**
   - Список релизов (JSON, отсортирован по убыванию версии):
     `https://antigravity-hub-auto-updater-974169037036.us-central1.run.app/releases`
   - Прямая загрузка бинарника:
     `https://storage.googleapis.com/antigravity-public/antigravity-hub/<version>-<execution_id>/unsigned/language_server-linux-arm64`
2. **Обход (`confeden/Antigravity`):**
   - Последний тег релиза (HTTP 302 `Location: .../tag/vX.Y.Z`):
     `https://github.com/confeden/Antigravity/releases/latest`
   - Исходники конфигурации (`PROXY_SERVERS`, `gate_hosts`, `alpn`):
     - `https://raw.githubusercontent.com/confeden/Antigravity/main/src/proxy.rs`
     - `https://raw.githubusercontent.com/confeden/Antigravity/main/src/group.rs`
     - `https://raw.githubusercontent.com/confeden/Antigravity/main/src/resolvers.rs`
3. **Готовые APK с полным набором фиксов (GitHub Releases):**
   - Последний релиз (`Location: .../tag/vX.Y.Z`):
     `https://github.com/Bananchik09/antigravity-android/releases/latest`
   - Список ассетов без лимитов API:
     `https://github.com/Bananchik09/antigravity-android/releases/expanded_assets/<tag>`
   - Fallback через GitHub API:
     `https://api.github.com/repos/Bananchik09/antigravity-android/releases/latest`

---

## 2. Структура самоапдейтера (`app/src/main/java/com/agy/updater/`)

- `UpdatePrefs.java` — хранение настроек (`do_not_remind`, пропущенные версии `skipped_core_version`, `skipped_bypass_version`, `skipped_drive_version`), метаданных бэкапа, чтение актуальной установленной версии из `assets/updater/version_meta.json`, `assets/bypass_config.json` и `PackageManager`.
- `UpstreamChecker.java` — асинхронная проверка всех трёх источников (в ванильной сборке `isVanillaBuild()` пропускает проверку Обхода). Компоненты, для которых нет новой версии, **не предлагаются** в UI. Выбор готового APK с GitHub взаимоисключающий с локальной пересборкой (готовый APK уже содержит актуальные компоненты и Java/UI-фиксы).
- `CoreBinaryPatcher.java` — потоковый/файловый (`RandomAccessFile`, буфер 4 МБ без загрузки 226 МБ в кучу Java) патчер `language_server` на самом телефоне. Применяет все 9 бинарных патчей из `modules/patches/` (`ineligible` → `inexigible`, `https_proxy` → `AG_LS_PROXY`, `cli_gate`, `enterprise_auth`, `faccessat`, `fchmodat`, `sigill_handler`, `resolv_redirect`, `crash_suppressor` с масками `.text`, `pty_shell`, `disable_sandbox` через Go `pclntab`) и пересчитывает SHA-256.
- `AxmlPatcher.java` — редактор бинарного `AndroidManifest.xml` (AXML): обновляет `android:versionCode` (`+1` на каждом обновлении/откате) и `android:versionName` (`<core_version>-mobile` или `<core_version>-vanilla`) с расширением `StringPool`.
- `ApkRepackagerAndSigner.java` — перед обновлением сохраняет текущий установленный APK (`applicationInfo.sourceDir`) в `files/updater_backups/prev_backup.apk`, пересобирает ZIP с 4-байтным выравниванием `STORED`-ресурсов (`resources.arsc`), обновляет `assets/runtime/manifest.json`, `assets/updater/version_meta.json`, `assets/bypass_config.json`, подписывает APK по **APK Signature Scheme v2** ключом `assets/updater/debug.p12` (PKCS12) и запускает стандартный установщик Android (`AppFileProvider` + `REQUEST_INSTALL_PACKAGES`).
- `RollbackManagerCompat.java` — откат обновления:
  - при наличии Root (`su`) или Shizuku (`rish`) выполняет нативный даунгрейд Android (`pm install -r -d <prev_backup.apk>`);
  - без Root/Shizuku перепаковывает `prev_backup.apk` с `versionCode = current + 1`, подписывает v2 и запускает установщик;
  - удаляет `files/bypass_config.json`, чтобы не перекрывать конфиг откаченной версии.
- `UpdateUiHelper.java` — общий UI-хелпер:
  - при старте (`MainActivity.java`): кнопки **«Скачать»**, **«Обновить позже»**, **«Пропустить версию»**, **«Не напоминать больше»**;
  - в настройках (`ProxySettingsActivity.java` рядом с прокси и `SettingsActivity.java` в ванильной сборке): ручная проверка обновлений и кнопка **«Откатить обновление»**.

---

## 3. Динамическая версия после пересборки

Чтобы после самопересборки APK на устройстве новая версия корректно отображалась и в нативных настройках, и в веб-интерфейсе (вкладка **App / Модель**), и в аргументах ядра:
- `EngineConfigJson.java` передаёт в `window.__APP_CONFIG__` (`appVersion`, `appVersionCode`, `coreVersion`) значения из `UpdatePrefs.getCurrentVersionName(...)`, `UpdatePrefs.getCurrentVersionCode(...)` и `UpdatePrefs.getInstalledCoreVersion(...)`, а не статический `BuildConfig`.
- `RuntimeManager.java` передаёт в `--override_ide_version` значение `UpdatePrefs.getInstalledCoreVersion(appContext)`.
- `CoreInstaller.java` автоматически перераспаковывает `assets/runtime/` при изменении хэша `assets/runtime/manifest.json`.
