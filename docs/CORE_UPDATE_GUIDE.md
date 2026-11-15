# Инструкция для ИИ-агента: Обновление ядра Antigravity на Android (2.31.0 -> 2.32.0+)

Данный документ — полное руководство для ИИ-агента (или разработчика) по обновлению ядра Antigravity (`language_server`), бинарному патчингу, обновлению метаданных и пересборке APK для Android (включая Android 14, 15, 16 / SDK 34-36).

---

## 1. Архитектура ядра на Android

Ядро Antigravity (`language_server`) представляет собой 64-битный исполняемый ELF-файл (Linux `aarch64` / `arm64`), написанный на Go. На Android оно работает внутри легковесного glibc-окружения:

1. **Бинарник ядра**: `assets/runtime/bin/liblanguage_server.so` (расширение `.so` используется для совместимости с упаковщиком APK, фактически это исполняемый бинарник).
2. **Glibc-загрузчик**: `assets/runtime/bin/libloader.so` (динамический компоновщик `ld-linux-aarch64.so.1`).
3. **Хук совместимости**: `assets/runtime/lib/liblse_compat.so` (перехватывает вызовы ядра, специфичные для Linux, транслируя их в Android Bionic).
4. **Контрольный манифест**: `assets/runtime/manifest.json` — содержит контрольные суммы SHA-256 всех файлов рантайма. При старте `RuntimeManager.java` валидирует хэши; если хэш не совпадает, бинарник перезаписывается или падает с ошибкой.
5. **Прокси обхода блокировок**: `com.agy.proxy.BypassProxyServer.java` слушает на `127.0.0.1:45162` и туннелирует запросы к `daily-cloudcode-pa.googleapis.com` через европейский сервер `<relay-host>:443` по протоколу AGU2.
6. **Окружение ядра**: `com.agy.core.CoreEnvironment.java` передает переменные окружения, включая `AG_LS_PROXY=http://127.0.0.1:45162`.

---

## 2. Где брать новое ядро

При выходе новой версии Antigravity (например, `2.32.0`):
1. **Из официального дистрибутива**:
   - Скачайте архив для Linux ARM64: `antigravity-linux-arm64-2.32.0.tar.gz` (или извлеките из deb/rpm пакета).
   - Извлеките исполняемый файл `language_server` (он находится в `resources/app/bin/language_server` или корне архива).
2. **Либо из базового APK**:
   - Если выходит официальный APK `phone_base.apk`, извлеките `assets/runtime/bin/liblanguage_server.so`.

---

## 3. Обязательные бинарные патчи (Byte Patches)

В оригинальном `language_server` необходимо применить два байтовых патча:

### Патч 1: Отключение региональной проверки (Region Precondition)
- **Что искать**: строку `ineligible` (10 байт: `69 6e 65 6c 69 67 69 62 6c 65`)
- **На что заменять**: `inexigible` (10 байт: `69 6e 65 78 69 67 69 62 6c 65`)
- **Зачем**: Google CloudCode проверяет статус региона пользователя. Патч выключает проверку на стороне клиента без смещения адресов и изменения размера бинарника.

### Патч 2: Изоляция переменной прокси (Proxy Hook)
- **Что искать**: строку `https_proxy` (11 байт: `68 74 74 70 73 5f 70 72 6f 78 79`)
- **На что заменять**: `AG_LS_PROXY` (11 байт: `41 47 5f 4c 53 5f 50 52 4f 58 59`)
- **Зачем**: Если передать в процесс обычный `https_proxy`, дочерние утилиты (curl, git, bash) попытаются идти через локальный порт, что вызовет сбои. С патчем `AG_LS_PROXY` проксируется только ядро `language_server`.

### Python-код для патчинга:
```python
def patch_language_server(raw_bytes: bytes) -> bytes:
    # 1. Bypass region lock
    patched = raw_bytes.replace(b"ineligible", b"inexigible")
    # 2. Proxy variable redirection
    patched = patched.replace(b"https_proxy", b"AG_LS_PROXY")
    return patched
```

---

## 4. Обновление хэша в `manifest.json`

После патчинга нужно обновить SHA-256 в `assets/runtime/manifest.json`:

```python
import hashlib, json

sha256 = hashlib.sha256(patched_bytes).hexdigest()

with open("manifest.json", "r", encoding="utf-8") as f:
    manifest = json.load(f)

manifest["files"]["bin/liblanguage_server.so"] = sha256

with open("manifest.json", "w", encoding="utf-8") as f:
    json.dump(manifest, f, indent=2)
```

---

## 5. Обновление конфигурации версий

При переходе на `2.32.0`:

1. **`config/app.env`**:
   ```ini
   CORE_VERSION=2.32.0
   APP_VERSION_NAME=2.32.0-mobile
   APP_VERSION_CODE=19
   ```

2. **`app/src/main/AndroidManifest.xml`**:
   ```xml
   <manifest xmlns:android="http://schemas.android.com/apk/res/android"
       package="com.antigravity.mobile"
       android:versionCode="19"
       android:versionName="2.32.0-mobile">
   ```
   > **КРИТИЧНО**: Всегда сохраняйте `<uses-sdk android:minSdkVersion="24" android:targetSdkVersion="28" />`!
   > На Android 10+ (API 29+) включен W^X (Write XOR Execute). Если `targetSdkVersion` будет > 28, запуск бинарника ядра из директории приложения вызовет `SecurityException`!

3. **`app/src/main/res/values/gen.xml`**:
   ```xml
   <string name="app_version_name">2.32.0-mobile</string>
   <integer name="app_version_code">19</integer>
   <string name="core_version">2.32.0</string>
   ```

---

## 6. Сборка, D8, упаковка и подпись (Скрипт `build_final_apk.py`)

Для сборки на хосте требуются:
- **Java OpenJDK 11+**
- **Android Build-Tools** (`aapt.exe`, `zipalign.exe`, `apksigner.bat`)
- **`r8.jar`** (для D8 dexing, находится в `tools/r8.jar`)
- **`android.jar`** (Android SDK platform API, в `tools/android.jar`)
- **`debug.keystore`** (в `tools/debug.keystore`)

### Конвейер сборки:

```
[1] javac (Java 8/1.8) -> build/obj/*.class
[2] d8 (tools/r8.jar)  -> build/dex/classes.dex
[3] aapt package       -> build/base_resources.apk
[4] zipfile assembly   -> assets (patched liblanguage_server.so + new manifest.json)
[5] zipalign -f 4      -> final_aligned.apk
[6] apksigner sign     -> Antigravity-2.32.0-mobile.apk
```

### Полный скрипт автоматической сборки (`build_final_apk.py`):
```python
import os, zipfile, hashlib, json, subprocess

WORK_DIR = r"<WORK_DIR>"
BASE_APK = os.path.join(WORK_DIR, "phone_base.apk")
APP_DIR = os.path.join(WORK_DIR, "antigravity-android")
RESOURCES_APK = os.path.join(APP_DIR, r"build\base_resources.apk")
OBJ_DIR = os.path.join(APP_DIR, r"build\obj")
DEX_DIR = os.path.join(APP_DIR, r"build\dex")
CLASSES_JAR = os.path.join(APP_DIR, r"build\classes.jar")
CLASSES_DEX = os.path.join(DEX_DIR, "classes.dex")
OUTPUT_UNALIGNED = os.path.join(WORK_DIR, "final_unaligned.apk")
OUTPUT_ALIGNED = os.path.join(WORK_DIR, "final_aligned.apk")
FINAL_APK = os.path.join(WORK_DIR, "Antigravity-2.32.0-mobile.apk")

BUILD_TOOLS = r"C:\Program Files\Unity\Hub\Editor\2022.3.22f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\build-tools\32.0.0"
AAPT = os.path.join(BUILD_TOOLS, "aapt.exe")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe")
APKSIGNER = os.path.join(BUILD_TOOLS, "apksigner.bat")
KEYSTORE = os.path.join(APP_DIR, r"tools\debug.keystore")
R8_JAR = os.path.join(APP_DIR, r"tools\r8.jar")
ANDROID_JAR = os.path.join(APP_DIR, r"tools\android.jar")
JAVA_HOME = r"C:\Program Files\Unity\Hub\Editor\2022.3.22f1\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK"
JAVA_EXE = os.path.join(JAVA_HOME, "bin", "java.exe")

# 1. Dexing
with zipfile.ZipFile(CLASSES_JAR, "w", compression=zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(OBJ_DIR):
        for f in files:
            if f.endswith(".class"):
                full = os.path.join(root, f)
                z.write(full, os.path.relpath(full, OBJ_DIR))

cmd_d8 = [JAVA_EXE, "-cp", R8_JAR, "com.android.tools.r8.D8", "--min-api", "24", "--output", DEX_DIR, "--lib", ANDROID_JAR, CLASSES_JAR]
subprocess.run(cmd_d8, check=True)

# 2. Resources (aapt)
manifest_src = os.path.join(APP_DIR, r"app\src\main\AndroidManifest.xml")
res_dir = os.path.join(APP_DIR, r"app\src\main\res")
if os.path.exists(RESOURCES_APK): os.remove(RESOURCES_APK)
cmd_aapt = [AAPT, "package", "-f", "-M", manifest_src, "-S", res_dir, "-I", ANDROID_JAR, "-F", RESOURCES_APK, "--auto-add-overlay"]
subprocess.run(cmd_aapt, check=True)

# 3. Patching and Packaging
with zipfile.ZipFile(BASE_APK, "r") as zin:
    ls_bytes = zin.read("assets/runtime/bin/liblanguage_server.so")
    ls_bytes = ls_bytes.replace(b"ineligible", b"inexigible").replace(b"https_proxy", b"AG_LS_PROXY")

    m = json.loads(zin.read("assets/runtime/manifest.json").decode())
    m["files"]["bin/liblanguage_server.so"] = hashlib.sha256(ls_bytes).hexdigest()
    new_manifest_bytes = json.dumps(m, indent=2).encode()

if os.path.exists(OUTPUT_UNALIGNED): os.remove(OUTPUT_UNALIGNED)
with zipfile.ZipFile(OUTPUT_UNALIGNED, "w") as zout:
    with zipfile.ZipFile(RESOURCES_APK, "r") as zres:
        for item in zres.infolist():
            zout.writestr(item, zres.read(item.filename))

    zout.writestr("classes.dex", open(CLASSES_DEX, "rb").read(), compress_type=zipfile.ZIP_DEFLATED)

    with zipfile.ZipFile(BASE_APK, "r") as zin:
        for item in zin.infolist():
            if item.filename.startswith("META-INF/") or item.filename in ("AndroidManifest.xml", "resources.arsc", "classes.dex") or item.filename.startswith("res/"):
                continue
            if item.filename == "assets/runtime/bin/liblanguage_server.so":
                zout.writestr(item, ls_bytes)
            elif item.filename == "assets/runtime/manifest.json":
                zout.writestr(item, new_manifest_bytes)
            else:
                zout.writestr(item, zin.read(item.filename))

# 4. ZipAlign
if os.path.exists(OUTPUT_ALIGNED): os.remove(OUTPUT_ALIGNED)
subprocess.run([ZIPALIGN, "-f", "4", OUTPUT_UNALIGNED, OUTPUT_ALIGNED], check=True)

# 5. Apksigner
if os.path.exists(FINAL_APK): os.remove(FINAL_APK)
env = os.environ.copy()
env["JAVA_HOME"] = JAVA_HOME
env["PATH"] = os.path.join(JAVA_HOME, "bin") + ";" + env["PATH"]
cmd_sign = [APKSIGNER, "sign", "--ks", KEYSTORE, "--ks-pass", "pass:android", "--key-pass", "pass:android", "--ks-key-alias", "androiddebugkey", "--out", FINAL_APK, OUTPUT_ALIGNED]
subprocess.run(cmd_sign, check=True, env=env)
print(f"SUCCESS: {FINAL_APK}")
```

---

## 7. Проверка и валидация на устройстве через ADB

После сборки выполните установку и проверку логов:

```bash
# 1. Установка обновленного APK без удаления данных пользователя
adb install -r Antigravity-2.32.0-mobile.apk

# 2. Запуск основного экрана
adb shell am start -n com.antigravity.mobile/com.agy.ui.launcher.LauncherDarkActivity

# 3. Проверка установления туннелей AGU2 Proxy
adb shell "logcat -d | grep -iE 'BypassProxy|CoreServer' | tail -n 30"
```

Ожидаемый успешный вывод:
```
I BypassProxy: Bypass proxy listening on 127.0.0.1:45162
I BypassProxy: AGU2 tunnel established via <relay-host> -> daily-cloudcode-pa.googleapis.com
I BypassProxy: AGU2 tunnel established via <relay-host> -> aicode.googleapis.com
```

---

## 8. Чек-лист возможных ошибок

| Симптом / Ошибка | Причина | Способ решения |
|---|---|---|
| `Agent execution terminated due to error` | Ошибка региональной блокировки или прокси вернул 404 | Проверить формулу подписи AGU2 в `BypassProxyServer.java` (канонический формат: `AGU2\n<tg>\n<code>\n<issued>\n<ts>\n<nonceHex>\n<targetHost>:443`, 16-байтный нонс). |
| `Hash mismatch / files corrupted` | Изменили бинарник, но не обновили `manifest.json` | Пересчитать SHA-256 патченного файла и вставить в `assets/runtime/manifest.json`. |
| `Permission denied` при запуске `language_server` | В манифесте `targetSdkVersion` установлен в 29 или выше | Оставить строго `targetSdkVersion="28"`. |
| Белый экран при переходе в настройки | WebView рендерер выгружен системой Xiaomi HyperOS/MIUI | Убедиться, что в `AgyWebViewClient.java` метод `onRenderProcessGone` возвращает `true`. |
| Уведомление смахивается | MIUI классифицирует канал как беззвучный | Использовать канал с `IMPORTANCE_DEFAULT` и флаги `FLAG_ONGOING_EVENT \| FLAG_NO_CLEAR`. |

---

## 9. Обновления патчера для `2.32.x` и Android-ядер без user/pid namespaces

- `modules/patches/groups/failfast/crash_suppressor.py`: переведён на масочный поиск инструкций внутри `.text` (`CPU_PAT`, `ATOMIC_PAT`), чтобы корректно находить и патчить оба сайта на `2.11`, `2.31.2`, `2.32.0`, `2.32.1`+.
- `modules/patches/groups/sandbox/disable_sandbox.py`: патчит `(*RunCommandHandler).isSandboxEnabled` (`mov w0, #0; ret`), устраняя ошибку `fork/exec .../libloader.so: invalid argument` при `BypassSandbox: false` на ядрах Android без `CONFIG_USER_NS`/`CONFIG_PID_NS`.
- `tools/build_final_apk.py`: автоматически прогоняет все активные модули `modules/patches/pipeline.py` при сборке APK.
