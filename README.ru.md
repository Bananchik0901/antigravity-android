<div align="center">

  <p>
    <a href="README.md">English</a> &nbsp;&bull;&nbsp; <b>Русский</b>
  </p>

  <br/>

  <img src="docs/assets/logo.png" alt="Antigravity" width="88" height="88" style="border-radius: 20px;" />

  # Antigravity для Android

**Автономная среда разработки на базе ИИ, работающая нативно на Android и ARM64 (Ядро `2.34.0`).**

  [![Android](https://img.shields.io/badge/Android-7.0--17-3DDC84?style=flat-square&logo=android&logoColor=white)](https://android.com)
  [![Arch](https://img.shields.io/badge/ARM64-v8.0--A%2B-blue?style=flat-square)](https://arm.com)
  [![Release](https://img.shields.io/badge/Версия-2.35.1.0-blueviolet?style=flat-square)](https://github.com/Bananchik0901/antigravity-android/releases)
  [![License](https://img.shields.io/badge/Лицензия-Apache_2.0-orange?style=flat-square)](LICENSE)

  <br/>

  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/hero_dark.jpg">
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/hero_light.jpg">
    <img alt="Интерфейс Antigravity" src="docs/assets/hero_dark.jpg" width="340" style="border-radius: 16px; border: 1px solid rgba(128,128,128,0.2);" />
  </picture>

</div>

---

### Обзор

**Antigravity для Android** переносит автономное агентное программирование прямо на мобильное устройство.

Никаких PRoot, chroot или накладных расходов виртуализации. Antigravity исполняется **нативно в пространстве пользователя Android (Bionic)**, раскрывая полную скорость физических ядер CPU и накопителей UFS. Агент на базе моделей Gemini автономно анализирует кодовую базу, редактирует файлы проекта, компилирует бинарники и запускает тесты полностью локально.

---

### Главное в v2.35.1.0

* **Ядро `2.35.1` + Web UI `2.35.1`:** Обновлены нативное ядро ARM64 (`language_server` `2.35.1`) и встроенный Web UI (`main.js`, `compiled_tailwind.css`, `jetbox.css`).
* **Интеграция Shizuku:** `moe.shizuku.client.V3_SUPPORT`, системный диалог `API_V23` и авто-распаковка `rish_shizuku.dex`.
* **Обход `confeden/Antigravity` v2.35.0:** Встроенная версия `2.35.0` и поддержка `MAJOR.MINOR` (`2.35`) ключей.
* **Две редакции APK:**
  * `Antigravity-2.35.1.0-mobile.apk` — редакция с обходом (`@nova_txt` AGU2 локальный CONNECT-прокси `127.0.0.1:45162`, проверка лицензионного ключа, патчи `cli_gate` / `enterprise_auth` / `ineligible`).
  * `Antigravity-2.35.1.0-vanilla.apk` — чистый порт (100% оригинальный `liblanguage_server.so`, прямое подключение без прокси).
* **Встроенный автоапдейтер с пересборкой на устройстве:** Проверяет обновления Ядра, Обхода (`confeden/Antigravity`) и APK на GitHub Releases, пересобирает и переподписывает APK прямо на телефоне (с пометкой `-autoup`), поддерживает откат версии.
* **Доработки Web UI:** Вкладки *App* и *Browser* в настройках, мобильные *Projects* и *Not in Project: Conversations*, удержание WebView-потоков в фоне и автоматический OAuth для плагинов.
* **Модульная архитектура и ARMv8.0-A:** Разделение на 6 подсистем (`modules/*`), эмуляция атомиков `liblse_compat.so` + `sigill_handler`, микро-демон `agy-daemon`.

---

### Ключевые возможности

* **Автономный агент:** Планирует задачи, правит код по всему проекту, вызывает консольные инструменты и устраняет ошибки сборки.
* **Привилегии Shizuku:** Прямой доступ к ADB-оболочке (`uid=2000`) без root для установки пакетов и чтения системных логов.
* **Встроенный менеджер `pkg`:** Быстрая установка инструментариев (`git`, `python`, `node`, `clang`) из репозиториев Termux.
* **Адаптивный интерфейс:** Удобное сенсорное управление на телефоне с возможностью переключения в десктопный вид.
* **Бесшовные обновления:** Единый ключ подписи гарантирует сохранение сессий и проектов при обновлении APK.

---

### Быстрый старт

1. **Скачайте APK** со страницы [Releases](https://github.com/Bananchik0901/antigravity-android/releases):
   * `Antigravity-2.35.1.0-mobile.apk` — версия с обходом (AGU2-прокси + патчи ограничений).
   * `Antigravity-2.35.1.0-vanilla.apk` — чистая версия (оригинальное ядро без прокси).
2. **Предоставьте доступ к памяти** (`Все файлы`). *(Опционально: подключите Shizuku или Root для привилегированной оболочки)*.
3. **Установите рабочее окружение** в терминале или через агента:
   ```bash
   pkg update && pkg install git python nodejs
   ```

---

### Сборка и тестирование

```bash
# Сборка Bypass APK (требуется базовый рантайм и опционально tools/.bypass_secrets.json):
python tools/build_final_apk.py

# Сборка чистого Vanilla APK:
python tools/build_vanilla_apk.py

# Или через модульные shell-скрипты:
bash build.sh                 # Стандартный релиз
bash build.sh --region-bypass # Релиз с обходом проверок

# Запуск полного набора тестов:
bash test_all_modules.sh
```

> [!NOTE]
> Как и в upstream (`confeden/Antigravity`), список релейных узлов и секрет развёртывания ключа `agk1.` не коммитятся в открытый репозиторий. При самостоятельной сборке версии с обходом укажите их в локальном `tools/.bypass_secrets.json` (в `.gitignore`) или через `AGY_AGU2_T_HEX` и `AGY_PROXY_SERVERS`.

---

### Структура проекта

```text
antigravity/
├── config/             # Константы приложения, пути окружения и правила агента
├── modules/            # Модульная архитектура ядра (v2.4+)
│   ├── core/           # Распаковка, glibc-линковщик, системные шимы, SHA-256
│   ├── patches/        # Движок бинарного патчинга (системные вызовы, DNS, SIGILL)
│   ├── io/             # Шина ввода-вывода (I/O Bus): event broker, FIFO, сокеты
│   ├── env/            # Резолвер путей, диагностика, логи Flight Recorder, CLI
│   ├── builder/        # Сборщик APK без Gradle (javac, D8, aapt, zipalign, apksigner)
│   └── runner/         # Подготовка рантайма, health probe, E2E тесты
├── app/                # Android-хост (нативный сплэш, WebView, мосты)
├── web/                # Веб-интерфейс, переключатель desktop mode, темы
├── env/                # Bionic userspace, оверлей Termux, менеджер pkg, liblse_compat
├── docs/assets/        # Скриншоты, баннеры и диаграммы
├── build.sh            # Корневой скрипт сборки APK
└── test_all_modules.sh # Полный набор тестов для всех 6 модулей
```

> [!TIP]
> Каждый модуль в `modules/`, `app/`, `web/` и `env/` содержит собственный файл `README.md` с глубоким описанием архитектуры и компонентов.

---

<div align="center">
  <sub>Распространяется под лицензией <a href="LICENSE">Apache 2.0</a></sub>
</div>
