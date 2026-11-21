import os
import shutil
import zipfile
import hashlib
import json
import subprocess
import sys

APP_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORK_DIR = os.environ.get("AGY_WORK_DIR", os.path.dirname(APP_DIR))
BASE_APK = os.environ.get("AGY_BASE_APK", os.path.join(WORK_DIR, "phone_base.apk"))
STOCK_LS = os.environ.get("AGY_STOCK_LS", os.path.join(WORK_DIR, "language_server_2.34.0.so"))
SRC_DIR = os.path.join(APP_DIR, r"app\src\main\java")
GEN_DIR = os.path.join(APP_DIR, r"build\gen")
RES_DIR = os.path.join(APP_DIR, r"app\src\main\res")
MANIFEST_SRC = os.path.join(APP_DIR, r"app\src\main\AndroidManifest.xml")

BUILD_DIR = os.path.join(APP_DIR, r"build\vanilla")
OBJ_DIR = os.path.join(BUILD_DIR, "obj")
DEX_DIR = os.path.join(BUILD_DIR, "dex")
CLASSES_JAR = os.path.join(BUILD_DIR, "classes.jar")
CLASSES_DEX = os.path.join(DEX_DIR, "classes.dex")
RESOURCES_APK = os.path.join(BUILD_DIR, "resources.apk")
OUTPUT_UNALIGNED = os.path.join(BUILD_DIR, "vanilla_unaligned.apk")
OUTPUT_ALIGNED = os.path.join(BUILD_DIR, "vanilla_aligned.apk")
FINAL_APK = os.path.join(WORK_DIR, r"release\Antigravity-2.34.0.1-vanilla.apk")

BUILD_TOOLS = os.environ.get("ANDROID_BUILD_TOOLS", r"C:\Program Files\Unity\Hub\Editor\2022.3.22f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\build-tools\32.0.0")
AAPT = os.path.join(BUILD_TOOLS, "aapt.exe")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe")
APKSIGNER = os.path.join(BUILD_TOOLS, "apksigner.bat")
KEYSTORE = os.path.join(APP_DIR, r"tools\debug.keystore")
KEYSTORE_P12 = os.path.join(APP_DIR, r"tools\debug.p12")
KEYSTORE_PASS = "android"
KEYSTORE_ALIAS = "androiddebugkey"
RELEASE_SIGNING_FILE = os.path.join(APP_DIR, r"tools\.release_signing.json")
if os.path.isfile(RELEASE_SIGNING_FILE):
    with open(RELEASE_SIGNING_FILE, "r", encoding="utf-8") as _rsf:
        _rs = json.load(_rsf)
    _rel_ks = os.path.join(APP_DIR, "tools", _rs.get("keystore", "release.p12"))
    if os.path.isfile(_rel_ks):
        KEYSTORE = _rel_ks
        KEYSTORE_P12 = _rel_ks
        KEYSTORE_PASS = str(_rs.get("password", "")).strip() or KEYSTORE_PASS
        KEYSTORE_ALIAS = str(_rs.get("alias", "")).strip() or KEYSTORE_ALIAS
R8_JAR = os.path.join(APP_DIR, r"tools\r8.jar")
ANDROID_JAR = os.path.join(APP_DIR, r"tools\android.jar")
JAVA_HOME = os.environ.get("JAVA_HOME", r"C:\Program Files\Unity\Hub\Editor\2022.3.22f1\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK")
JAVAC_EXE = os.path.join(JAVA_HOME, "bin", "javac.exe")
JAVA_EXE = os.path.join(JAVA_HOME, "bin", "java.exe")

os.makedirs(OBJ_DIR, exist_ok=True)
os.makedirs(DEX_DIR, exist_ok=True)
os.makedirs(os.path.dirname(FINAL_APK), exist_ok=True)

print("=== BUILDING ANTIGRAVITY VANILLA (CLEAN PORT) ===")

# 1. Prepare Vanilla Sources
# We copy all java files except BypassProxyServer and ProxySettingsActivity.
# For CoreEnvironment, CoreServerService, ForegroundNotification, we generate vanilla versions.
TEMP_SRC = os.path.join(BUILD_DIR, "src")
if os.path.exists(TEMP_SRC):
    shutil.rmtree(TEMP_SRC)
shutil.copytree(SRC_DIR, TEMP_SRC)

# Remove bypass classes
proxy_server = os.path.join(TEMP_SRC, "com", "agy", "proxy", "BypassProxyServer.java")
if os.path.exists(proxy_server):
    os.remove(proxy_server)
proxy_settings = os.path.join(TEMP_SRC, "com", "agy", "ui", "ProxySettingsActivity.java")
if os.path.exists(proxy_settings):
    os.remove(proxy_settings)

# Vanilla CoreEnvironment: Direct connection (no AG_LS_PROXY, no https_proxy hijack)
core_env = os.path.join(TEMP_SRC, "com", "agy", "core", "CoreEnvironment.java")
with open(core_env, "r", encoding="utf-8") as f:
    ce_code = f.read()
# Replace proxy injections with clean comments
old_proxy_block = """        // Configure proxy for language_server to route Google gate hosts through our local proxy
        env.put("AG_LS_PROXY", "http://127.0.0.1:45162");
        env.put("https_proxy", "http://127.0.0.1:45162");
        env.put("HTTPS_PROXY", "http://127.0.0.1:45162");"""
new_clean_block = """        // Vanilla port: Direct connection to Google APIs without local proxy interception."""
ce_code = ce_code.replace(old_proxy_block, new_clean_block)
with open(core_env, "w", encoding="utf-8") as f:
    f.write(ce_code)

# Vanilla CoreServerService: No BypassProxyServer start/stop
core_svc = os.path.join(TEMP_SRC, "com", "agy", "core", "CoreServerService.java")
with open(core_svc, "r", encoding="utf-8") as f:
    cs_code = f.read()
cs_code = cs_code.replace("        // Start local AGU2 bypass proxy for Google gate hosts\n        com.agy.proxy.BypassProxyServer.start(this);\n", "")
cs_code = cs_code.replace("        com.agy.proxy.BypassProxyServer.stop();\n", "")
with open(core_svc, "w", encoding="utf-8") as f:
    f.write(cs_code)

# Vanilla ForegroundNotification: Click opens SettingsActivity
fn_file = os.path.join(TEMP_SRC, "com", "agy", "core", "ForegroundNotification.java")
with open(fn_file, "r", encoding="utf-8") as f:
    fn_code = f.read()
fn_code = fn_code.replace("com.agy.ui.ProxySettingsActivity.class", "com.agy.ui.SettingsActivity.class")
with open(fn_file, "w", encoding="utf-8") as f:
    f.write(fn_code)

print("[1/5] Compiling Vanilla Java classes with javac...")
VANILLA_GEN_DIR = os.path.join(BUILD_DIR, "gen")
if os.path.exists(VANILLA_GEN_DIR):
    shutil.rmtree(VANILLA_GEN_DIR)
if os.path.isdir(GEN_DIR):
    shutil.copytree(GEN_DIR, VANILLA_GEN_DIR)
vanilla_cfg_path = os.path.join(VANILLA_GEN_DIR, "com", "agy", "BuildConfig.java")
os.makedirs(os.path.dirname(vanilla_cfg_path), exist_ok=True)
with open(vanilla_cfg_path, "w", encoding="utf-8") as cf:
    cf.write('''package com.agy;

public final class BuildConfig {
    private BuildConfig() {}

    public static final String APPLICATION_ID = "com.antigravity.mobile";
    public static final int PORT = 45157;
    public static final int PORT_DEV = 45157;
    public static final int GATEWAY_PORT = 45160;
    public static final String CORE_VERSION = "2.34.0";
    public static final String APP_VERSION_NAME = "2.34.0.1-vanilla";
    public static final int APP_VERSION_CODE = 28;
    public static final String OAUTH_SCHEME = "antigravity";
    public static final String APP_NAME = "Antigravity";
    public static final int MIN_API = 24;
    public static final int TARGET_SDK = 28;
    public static final boolean DEBUG = false;
    public static final String AGU2_WRAP_KEY = "";
    public static final String[] AGU2_PROXY_SERVERS = new String[0];
    public static final String UPDATER_KEYSTORE_PASS = "''' + KEYSTORE_PASS.replace('\\', '\\\\').replace('"', '\\"') + '''";
    public static final String UPDATER_KEYSTORE_ALIAS = "''' + KEYSTORE_ALIAS.replace('\\', '\\\\').replace('"', '\\"') + '''";
}
''')

java_files = []
for d in [TEMP_SRC, VANILLA_GEN_DIR]:
    for root, _, files in os.walk(d):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f).replace("\\", "/"))

sources_file = os.path.join(BUILD_DIR, "vanilla_sources.txt")
with open(sources_file, "w", encoding="utf-8") as f:
    for jf in java_files:
        f.write(f'"{jf}"\n')

cmd_javac = [
    JAVAC_EXE,
    "-encoding", "UTF-8",
    "-source", "1.8",
    "-target", "1.8",
    "-d", OBJ_DIR,
    "-cp", ANDROID_JAR,
    f"@{sources_file}"
]
subprocess.run(cmd_javac, check=True)
print("  javac compiled vanilla classes successfully.")

print("[2/5] Dexing classes into classes.dex...")
with zipfile.ZipFile(CLASSES_JAR, "w", compression=zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(OBJ_DIR):
        for f in files:
            if f.endswith(".class"):
                full = os.path.join(root, f)
                rel = os.path.relpath(full, OBJ_DIR)
                z.write(full, rel)

cmd_d8 = [JAVA_EXE, "-cp", R8_JAR, "com.android.tools.r8.D8", "--min-api", "24", "--output", DEX_DIR, "--lib", ANDROID_JAR, CLASSES_JAR]
subprocess.run(cmd_d8, check=True)
print(f"  D8 generated vanilla classes.dex ({os.path.getsize(CLASSES_DEX)} bytes)")

print("[3/5] Packaging base resources with aapt...")
VANILLA_MANIFEST = os.path.join(BUILD_DIR, "AndroidManifest.xml")
with open(MANIFEST_SRC, "r", encoding="utf-8") as f:
    manifest_text = f.read().replace('-mobile"', '-vanilla"')
with open(VANILLA_MANIFEST, "w", encoding="utf-8") as f:
    f.write(manifest_text)
if os.path.exists(RESOURCES_APK):
    os.remove(RESOURCES_APK)
cmd_aapt = [AAPT, "package", "-f", "-M", VANILLA_MANIFEST, "-S", RES_DIR, "-I", ANDROID_JAR, "-F", RESOURCES_APK, "--auto-add-overlay"]
subprocess.run(cmd_aapt, check=True)
print(f"  aapt generated resources.apk ({os.path.getsize(RESOURCES_APK)} bytes)")

print("[4/5] Packaging unaligned Vanilla APK with STOCK liblanguage_server.so...")
# Read stock binary and manifest directly from phone_base.apk
with zipfile.ZipFile(BASE_APK, "r") as zin:
    if os.path.isfile(STOCK_LS):
        print(f"  Using external stock core: {STOCK_LS}")
        with open(STOCK_LS, "rb") as sf:
            stock_ls_bytes = sf.read()
        m = json.loads(zin.read("assets/runtime/manifest.json").decode())
        m["files"]["bin/liblanguage_server.so"] = hashlib.sha256(stock_ls_bytes).hexdigest()
        stock_manifest_bytes = json.dumps(m, indent=2).encode()
    else:
        stock_ls_bytes = zin.read("assets/runtime/bin/liblanguage_server.so")
        stock_manifest_bytes = zin.read("assets/runtime/manifest.json")

print(f"  Stock liblanguage_server.so size: {len(stock_ls_bytes)} bytes, SHA-256: {hashlib.sha256(stock_ls_bytes).hexdigest()}")

with open(CLASSES_DEX, "rb") as f:
    dex_bytes = f.read()

BRIDGE_CORE_JS = os.path.join(APP_DIR, r"web\generated\bridge.core.js")
with open(BRIDGE_CORE_JS, "r", encoding="utf-8") as f:
    bridge_core_bytes = f.read().replace("\r\n", "\n").encode("utf-8")

MAIN_SRC_JS = os.path.join(APP_DIR, r"web\main.js")
MAIN_GEN_JS = os.path.join(APP_DIR, r"web\generated\main.js")
PATCH_MAIN_PY = os.path.join(APP_DIR, r"web\patch_main.py")
shutil.copy2(MAIN_SRC_JS, MAIN_GEN_JS)
subprocess.run([sys.executable, PATCH_MAIN_PY, MAIN_GEN_JS], check=True)
with open(MAIN_GEN_JS, "r", encoding="utf-8") as f:
    main_js_clean = f.read().replace("\r\n", "\n")
with open(MAIN_GEN_JS, "w", encoding="utf-8", newline="\n") as f:
    f.write(main_js_clean)
main_js_bytes = main_js_clean.encode("utf-8")

if os.path.exists(OUTPUT_UNALIGNED):
    os.remove(OUTPUT_UNALIGNED)

with zipfile.ZipFile(OUTPUT_UNALIGNED, "w") as zout:
    with zipfile.ZipFile(RESOURCES_APK, "r") as zres:
        for item in zres.infolist():
            zout.writestr(item, zres.read(item.filename))

    zout.writestr("classes.dex", dex_bytes, compress_type=zipfile.ZIP_DEFLATED)

    with open(KEYSTORE_P12, "rb") as f:
        zout.writestr("assets/updater/debug.p12", f.read(), compress_type=zipfile.ZIP_DEFLATED)
    version_meta_bytes = json.dumps({"core_version": "2.34.0", "bypass_version": "none", "drive_version": "2.34.0.1"}, indent=2).encode("utf-8")
    zout.writestr("assets/updater/version_meta.json", version_meta_bytes, compress_type=zipfile.ZIP_DEFLATED)

    with open(os.path.join(APP_DIR, r"web\generated\patches\theme_sync.js"), "r", encoding="utf-8") as f:
        theme_sync_bytes = f.read().replace("\r\n", "\n").encode("utf-8")

    with zipfile.ZipFile(BASE_APK, "r") as zin:
        for item in zin.infolist():
            if item.filename.startswith("META-INF/") or item.filename in ("AndroidManifest.xml", "resources.arsc", "classes.dex", "assets/updater/debug.p12", "assets/updater/version_meta.json") or item.filename.startswith("res/"):
                continue
            # Keep 100% stock files for runtime
            if item.filename == "assets/runtime/bin/liblanguage_server.so":
                zout.writestr(item, stock_ls_bytes)
            elif item.filename == "assets/runtime/manifest.json":
                zout.writestr(item, stock_manifest_bytes)
            elif item.filename == "assets/web/generated/bridge.core.js":
                zout.writestr(item, bridge_core_bytes)
            elif item.filename == "assets/web/generated/main.js":
                zout.writestr(item, main_js_bytes)
            elif item.filename == "assets/web/generated/patches/theme_sync.js":
                zout.writestr(item, theme_sync_bytes)
            else:
                zout.writestr(item, zin.read(item.filename))

print(f"  Unaligned APK created: {os.path.getsize(OUTPUT_UNALIGNED)} bytes")

print("[5/5] Aligning and signing final Vanilla APK...")
if os.path.exists(OUTPUT_ALIGNED):
    os.remove(OUTPUT_ALIGNED)
subprocess.run([ZIPALIGN, "-f", "4", OUTPUT_UNALIGNED, OUTPUT_ALIGNED], check=True)

if os.path.exists(FINAL_APK):
    os.remove(FINAL_APK)

env = os.environ.copy()
env["JAVA_HOME"] = JAVA_HOME
env["PATH"] = os.path.join(JAVA_HOME, "bin") + ";" + env["PATH"]

cmd_sign = [APKSIGNER, "sign", "--ks", KEYSTORE, "--ks-pass", f"pass:{KEYSTORE_PASS}", "--key-pass", f"pass:{KEYSTORE_PASS}", "--ks-key-alias", KEYSTORE_ALIAS, "--out", FINAL_APK, OUTPUT_ALIGNED]
subprocess.run(cmd_sign, check=True, env=env)

print(f"\nSUCCESS! Vanilla APK generated at:")
print(f"  {FINAL_APK} ({os.path.getsize(FINAL_APK)} bytes)")
