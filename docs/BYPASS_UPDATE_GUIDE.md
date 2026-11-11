# Руководство для ИИ-агента: Обновление механизма обхода блокировок (Bypass / AGU2 / AGU3)

В данном документе описано устройство механизма обхода региональных блокировок Google CloudCode в проекте Antigravity для Android, эталонный upstream-проект [confeden/Antigravity](https://github.com/confeden/Antigravity), а также пошаговый алгоритм для ИИ-агента по обновлению протокола, эндпоинтов, секретов и логики обхода при выходе новых версий.

---

## 1. Обзор архитектуры обхода

В мобильном приложении Antigravity (`com.antigravity.mobile`) обход блокировок реализован автономно прямо внутри Android без необходимости сторонних VPN:

```
[ language_server (Core) ]
        │
        │ HTTP CONNECT (через AG_LS_PROXY="http://127.0.0.1:45162")
        ▼
[ BypassProxyServer.java (Локальный прокси на 127.0.0.1:45162) ]
        │
        ├── Обычный трафик ──> Direct connect (напрямую)
        │
        └── Google Gate Hosts ──> TLS (ALPN "agu/2") ──> [ <relay-host>:443 ]
            • daily-cloudcode-pa.googleapis.com                 │
            • cloudcode-pa.googleapis.com                       ▼
            • aicode.googleapis.com               Google CloudCode Servers (Европа)
```

### Ключевые файлы реализации:
- **`app/src/main/java/com/agy/proxy/BypassProxyServer.java`**:
  Слушает на `127.0.0.1:45162`. Расшифровывает ключи `agk1...`, формирует AGU2-рукопожатие и осуществляет двустороннюю передачу данных.
- **`app/src/main/java/com/agy/core/CoreEnvironment.java`**:
  Экспортирует переменную окружения `AG_LS_PROXY=http://127.0.0.1:45162` в процесс ядра.
- **`app/src/main/java/com/agy/ui/ProxySettingsActivity.java`**:
  Пользовательский интерфейс: тумблер вкл/выкл, генерация команды для группы `@nova_txt`, сохранение ключа, статус.

---

## 2. Источник эталонной логики (Upstream)

Эталоном механизма обхода является репозиторий:
- **URL**: `https://github.com/confeden/Antigravity` (разработчик Brent / Telegram `@nova_txt`).
- **Язык**: Rust.
- **Ключевые файлы upstream**:
  - `src/group.rs` — реализация AGU2 TLS-соединения, ALPN, управление пулом серверов.
  - `src/group_key.rs` — спецификация формата ключа `agk1...`, unwrap-алгоритм, вычисление HMAC для `Proxy-Authorization`.
  - `src/proxy.rs` — функция `is_gate_host` (список хостов Google, требующих обхода).
  - `src/hwid.rs` — генерация идентификатора оборудования (HWID) и кодирование Crockford Base32.
  - `src/resolvers.rs` — список резервных адресов и серверов `<relay-hosts>`.

---

## 3. Что проверять при выходе новой версии Upstream

Если в репозитории `confeden/Antigravity` выходит обновление (или изменяется протокол на AGU3):

### Шаг 1: Проверка списка хостов (`src/proxy.rs`)
Найдите функцию:
```rust
fn is_gate_host(host: &str) -> bool
```
Если Google добавил новые эндпоинты (например, `gemini.googleapis.com` или внутренние API), добавьте их в метод `isGoogleGateHost(String host)` в `BypassProxyServer.java`:
```java
private boolean isGoogleGateHost(String host) {
    if (host == null) return false;
    String h = host.toLowerCase().trim();
    while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
    return h.equals("cloudcode-pa.googleapis.com")
            || h.equals("daily-cloudcode-pa.googleapis.com")
            || h.equals("aicode.googleapis.com")
            || h.equals("<новый_эндпоинт>");
}
```

### Шаг 2: Проверка списка серверов прокси (`src/group.rs` / `src/resolvers.rs`)
В `BypassProxyServer.java` массив серверов:
```java
private static final String[] PROXY_SERVERS = new String[] {
        "<relay-host>",
        "<relay-host-2>",
        "<relay-host-3>"
};
```
Если upstream добавил новые узлы или сменил домен — обновите этот массив.

### Шаг 3: Проверка ALPN и протокола рукопожатия (`src/group.rs`)
В `open_agu2` проверьте:
- Значение ALPN (сейчас `"agu/2"`, при обновлении может стать `"agu/3"`).
- Метод конфигурирования сокета. В Java:
```java
configureAlpn(sslSocket, "agu/2");
```

---

## 4. Спецификация протокола AGU2 (Как это работает внутри)

### 4.1. Формат и расшифровка ключа `agk1.<base64url>`

Пользователь получает от группы Telegram строку вида `agk1.<base64url>`.

1. Отрезается префикс `agk1.`.
2. Тело декодируется из Base64-URL (без padding):
   - `nonce`: первые 16 байт.
   - `ciphertext`: от 16 байта до `len - 16`.
   - `tag`: последние 16 байт.
3. Используется запечённый 32-байтный секрет $T$:
   `BAKED_T_HEX = ""`
4. Вычисляются ключи:
   - `mac_key = HMAC-SHA256(T, "mac")`
   - `enc_key = HMAC-SHA256(T, "enc")`
5. Проверяется подпись:
   `HMAC-SHA256(mac_key, nonce + ciphertext)[0..16] == tag`
6. Расшифровывается шифротекст через XOR с потоком (keystream):
   `stream_block_i = HMAC-SHA256(enc_key, nonce + be32(i))`
7. Расшифрованный текст содержит 7 строк:
   - Строка 0: `AGU2`
   - Строка 1: хост прокси (например, `<relay-host>`)
   - Строка 2: порт (`443`)
   - Строка 3: Telegram ID пользователя (например, `123456789`)
   - Строка 4: код устройства (`code`, например `<device-code>` или `<device-code-2>`)
   - Строка 5: timestamp выпуска ключа (`issued`)
   - Строка 6: `user_key` (32 байта в hex, используется для HMAC-подписи запросов)

### 4.2. Рукопожатие CONNECT и каноническая строка HMAC

При каждом исходящем подключении ядра к Google CloudCode:
1. Открывается TLS-соединение к `<relay-host>:443` с ALPN `agu/2`.
2. Генерируется свежий 16-байтный случайный нонс: `nonceHex = bytesToHex(nonce)` (32 hex-символа).
3. Текущее время в секундах: `ts = System.currentTimeMillis() / 1000L`.
4. **Каноническая строка** (строго LF-разделитель):
   ```
   AGU2
   <tg>
   <code>
   <issued>
   <ts>
   <nonceHex>
   <targetHost>:443
   ```
   > [!IMPORTANT]
   > В строке обязательно должны быть 7 параметров, разделённых `\n`, и порт `:443` в конце целевого хоста!
5. Вычисляется подпись:
   `mac = hex(HMAC-SHA256(userKey, canonical))`
6. Формируется заголовок:
   `Proxy-Authorization: AGU2 <tg>.<code>.<issued>.<ts>.<nonceHex>.<mac>`
7. Отправляется HTTP CONNECT:
   ```http
   CONNECT <targetHost>:443 HTTP/1.1\r\n
   Host: <targetHost>:443\r\n
   Proxy-Authorization: AGU2 <tg>.<code>.<issued>.<ts>.<nonceHex>.<mac>\r\n\r\n
   ```
8. При получении `HTTP/1.1 200 Connection Established`:
   - Снимаются таймауты: `client.setSoTimeout(0); sslSocket.setSoTimeout(0);`
   - Запускается двунаправленный байтовый пайп `pipeBidirectional(client, sslSocket)`.

---

## 5. Как извлечь секрет $T$, если он изменится в upstream

В upstream репозитории `confeden/Antigravity` секрет $T$ генерируется в `build.rs` и шифруется через AES-256-GCM в `src/group_gen.rs`. Если автор обновил лицензионный ключ или секрет $T$:

### Скрипт расшифровки $T$ из бинарника (`decrypt_t.py`):
```python
import hashlib, ctypes
from ctypes import wintypes

# Лицензионный ключ из репозитория (например, UPULACBFNOXR94F87F42F6E4)
license_key = "UPULACBFNOXR94F87F42F6E4"

# В скомпилированном Antigravity.exe найдите константы GROUP_KEY, GROUP_NONCE, GROUP_CT
# Либо запустите этот скрипт с извлеченными байтами:
key_bytes = bytes.fromhex('<32-byte-aes-key>')
nonce_bytes = bytes.fromhex('<12-byte-nonce>')
ct_tag_bytes = bytes.fromhex('<encrypted-ct-tag>')

# Расшифровка AES-256-GCM дает строку t_hex
# Полученный 64-символьный hex вставляется в BAKED_T_HEX в BypassProxyServer.java
```

---

## 6. Автономный Python-тест для валидации перед сборкой

Перед тем как пересобирать Android APK, работу прокси и ключа можно мгновенно проверить на компьютере этим скриптом:

```python
import socket, ssl, time, os, hmac, hashlib

proxy_host = '<relay-host>'
proxy_port = 443
tg = 123456789
code = '<device-code>'
issued = 1760000000
user_key = bytes.fromhex('<64-hex-user-key>')

ctx = ssl.create_default_context()
ctx.set_alpn_protocols(['agu/2'])

s = socket.create_connection((proxy_host, proxy_port), timeout=10)
ss = ctx.wrap_socket(s, server_hostname=proxy_host)

ts = int(time.time())
nonce_hex = os.urandom(16).hex()
target_host = 'daily-cloudcode-pa.googleapis.com'
msg = f'AGU2\n{tg}\n{code}\n{issued}\n{ts}\n{nonce_hex}\n{target_host}:443'
mac = hmac.new(user_key, msg.encode(), hashlib.sha256).hexdigest()
auth = f'AGU2 {tg}.{code}.{issued}.{ts}.{nonce_hex}.{mac}'

req = f'CONNECT {target_host}:443 HTTP/1.1\r\nHost: {target_host}:443\r\nProxy-Authorization: {auth}\r\n\r\n'
ss.sendall(req.encode())

resp = ss.recv(4096).decode(errors='replace')
print('Ответ прокси:', resp)
assert '200 Connection Established' in resp, 'Ошибка рукопожатия AGU2!'
print('Туннель успешно установлен!')
```

---

## 7. Проверка на телефоне

После внесения изменений в `BypassProxyServer.java`:
1. Запустите компиляцию и сборку:
   ```bash
   python tools/build_final_apk.py
   ```
2. Установите APK:
   ```bash
   adb install -r Antigravity-2.31.0-mobile.apk
   ```
3. Проверьте соединение:
   ```bash
   adb shell "logcat -d | grep -iE 'BypassProxy|CoreServer' | tail -n 20"
   ```
   Должна появиться запись:
   `I BypassProxy: AGU2 tunnel established via <relay-host> -> daily-cloudcode-pa.googleapis.com`
