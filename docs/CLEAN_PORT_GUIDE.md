# Antigravity Mobile: Руководство по чистому порту (Vanilla / Clean Port Guide)

Данное руководство предназначено для разработчиков и ИИ-агентов. Оно описывает архитектуру, отличия, сборку и процедуру обновления **чистого ванильного порта Antigravity для Android 16 (SDK 36)** без обхода региональных ограничений и без локального проксирования.

---

## 1. Концепция и философия чистого порта

Чистый порт (**Vanilla / Clean Edition**) создан для пользователей, находящихся в регионах без блокировок сервисов Google, для корпоративных сред, а также для пользователей с собственным системным VPN.

### Главные отличия Vanilla Edition от Bypass Edition:
| Параметр | Bypass Edition (с обходом) | Vanilla Edition (чистый порт) |
| :--- | :--- | :--- |
| **Бинарник ядра `language_server`** | Пропатчен (`ineligible` $\rightarrow$ `inexigible`, `https_proxy` $\rightarrow$ `AG_LS_PROXY`) | **100% оригинальный Google binary** (без модификаций байтов, SHA-256 совпадает с официальным) |
| **Маршрутизация трафика** | Перехватывается локальным HTTP/CONNECT прокси `127.0.0.1:45162` $\rightarrow$ AGU2 TLS туннель | **Прямое интернет-соединение** напрямую к API серверам Google (`*.googleapis.com`) |
| **Пакет `com.agy.proxy.*`** | Включён (`BypassProxyServer.java`, AGU2 протокол, ChaCha20/HMAC-SHA256) | **Полностью исключён** из сборки `classes.dex` |
| **Интерфейс настроек** | `ProxySettingsActivity` (ключи группы, тумблер AGU2, статус туннеля) | `SettingsActivity` (только статус Shizuku, проверка Root `su`, параметры ядра) |
| **Зависимость от внешних прокси** | Зависит от внешних AGU2-серверов обхода | **Полная автономность**, никаких сторонних серверов |

---

## 2. Адаптации под Android 16/17 (API 36+) в чистом порте

Все системные доработки, обеспечивающие стабильную работу Antigravity на Android 15–17, сохранены в чистом виде:

1. **Обход ограничения Android 10+ W^X (Write XOR Execute):**
   * В `AndroidManifest.xml` параметр `android:targetSdkVersion` строго равен `"28"`.
   * Это позволяет приложению запускать ELF-бинарник `liblanguage_server.so` напрямую из файловой системы приложения (`/data/user/0/com.antigravity.mobile/files/runtime/bin/`).
2. **Android 16 Bionic Linker Fix:**
   * В `CoreEnvironment.java` каталог `$PREFIX/lib` экспортируется в переменную `LD_LIBRARY_PATH`. Это предотвращает ошибку линковки Termux-утилит (bash, python3, git) с `libandroid-support.so`.
   * Переменная `LD_PRELOAD` изолирована и **не** передаётся в дочерние процессы (чтобы не ломать системный `/system/bin/sh`).
3. **Android 14+ Foreground Service:**
   * В манифесте указан `android:foregroundServiceType="specialUse"` и разрешение `POST_NOTIFICATIONS`.
   * Все `PendingIntent` снабжены обязательным флагом `PendingIntent.FLAG_IMMUTABLE`.
4. **Rootless Shell (Shizuku & Root):**
   * Встроена поддержка `moe.shizuku.privileged.api` через утилиту `rish` для выполнения терминальных команд без прав суперпользователя.
   * При наличии Root (`su`) приложение автоматически детектирует суперпользователя и сообщает, что Shizuku опционален.

---

## 3. Сборка Vanilla APK

Сборка чистого порта выполняется одной командой:

```powershell
python tools/build_vanilla_apk.py
```

### Конвейер сборки:
1. **Генерация исходного кода Vanilla:**
   * Классы `com.agy.proxy.*` и `ProxySettingsActivity.java` исключаются.
   * В `CoreEnvironment.java` удаляются переменные локального прокси `AG_LS_PROXY` и `https_proxy`.
   * В `CoreServerService.java` отключается запуск `BypassProxyServer`.
   * В `ForegroundNotification.java` клик по уведомлению привязывается к `SettingsActivity.class`.
2. **Компиляция Java:**
   * `javac` компилирует чистый исходный код с целевой версией Java 8 (`-source 1.8 -target 1.8`).
3. **Dexing:**
   * `D8` (`r8.jar`) трансформирует скомпилированные классы в `classes.dex` с `--min-api 24`.
4. **Упаковка ресурсов:**
   * `aapt.exe` компилирует ресурсы и `AndroidManifest.xml` в `resources.apk`.
5. **Сборка APK со STOCK ядром:**
   * Извлекается официальный, немодифицированный бинарник `liblanguage_server.so` и оригинальный `manifest.json`.
   * Все компоненты упаковываются в архив APK.
6. **Zipalign и подпись:**
   * `zipalign.exe` выравнивает данные по границе 4 байт.
   * `apksigner.bat` подписывает APK схемами v2/v3.

Готовый APK сохраняется по пути:
`release/Antigravity-<version>-vanilla.apk`

---

## 4. Инструкция для ИИ-агента: Как обновлять ядро в Vanilla-порте

Когда выходит новая версия официального ядра Antigravity Manager (например, `2.32.0`, `2.33.0`):

### Шаг 1. Получить новый официальный бинарник
Извлеките файл `liblanguage_server.so` (архитектура `arm64-v8a`) из официального APK или дистрибутива Antigravity:
```
assets/runtime/bin/liblanguage_server.so
```

### Шаг 2. ВНИМАНИЕ: НЕ ПАТЧИТЬ БИНАРНИК!
> [!IMPORTANT]
> В отличие от версии с обходом (Bypass Edition), в Vanilla-порте бинарник `liblanguage_server.so` **НЕЛЬЗЯ** патчить байтами! Он должен оставаться оригинальным. Никаких замен `ineligible` или `https_proxy` производить не нужно.

### Шаг 3. Рассчитать SHA-256 и обновить `manifest.json`
Посчитайте SHA-256 хэш нового официального бинарника и запишите его в `assets/runtime/manifest.json`:
```json
{
  "files": {
    "bin/liblanguage_server.so": "<sha256_официального_бинарника>"
  }
}
```

### Шаг 4. Обновить версии в проекте
1. В `config/app.env`:
   ```bash
   APP_CORE_VERSION="2.32.0"
   APP_VERSION_NAME="2.32.0-vanilla"
   ```
2. В `app/src/main/AndroidManifest.xml`:
   * Увеличьте `android:versionCode` на 1.
   * Укажите `android:versionName="2.32.0-vanilla"`.

### Шаг 5. Запустить сборку
```powershell
python tools/build_vanilla_apk.py
```

### Шаг 6. Верификация на устройстве
```powershell
adb install -r release/Antigravity-2.32.0-vanilla.apk
adb logcat -s MainActivity:V CoreServerService:V
```
Убедитесь, что:
1. `CoreServerService` успешно запустился без ошибок бинарника.
2. WebUI открывается по адресу `http://127.0.0.1:45161`.
3. Запросы к Google API выполняются напрямую без прокси.

---

## 5. Ветвление в Git

В репозитории поддерживаются две параллельные ветки:
* **`main`**: Основная рабочая ветка с поддержкой Bypass Edition (обход AGU2) и скриптами сборки обоих вариантов.
* **`vanilla`**: Полностью чистая ветка без какого-либо упоминания AGU2, ключей Telegram, прокси-серверов или байт-патчей.

Для переключения в чистое окружение:
```bash
git checkout vanilla
```
