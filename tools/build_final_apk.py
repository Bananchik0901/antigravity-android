import os
import shutil
import zipfile
import hashlib
import json
import subprocess
import sys

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
FINAL_APK = os.path.join(WORK_DIR, r"release\Antigravity-2.31.2-mobile.apk")

BUILD_TOOLS = r"C:\Program Files\Unity\Hub\Editor\2022.3.22f1\Editor\Data\PlaybackEngines\AndroidPlayer\SDK\build-tools\32.0.0"
AAPT = os.path.join(BUILD_TOOLS, "aapt.exe")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe")
APKSIGNER = os.path.join(BUILD_TOOLS, "apksigner.bat")
KEYSTORE = os.path.join(APP_DIR, r"tools\debug.keystore")
R8_JAR = os.path.join(APP_DIR, r"tools\r8.jar")
ANDROID_JAR = os.path.join(APP_DIR, r"tools\android.jar")
JAVA_HOME = r"C:\Program Files\Unity\Hub\Editor\2022.3.22f1\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK"
JAVAC_EXE = os.path.join(JAVA_HOME, "bin", "javac.exe")
JAVA_EXE = os.path.join(JAVA_HOME, "bin", "java.exe")

print("[0/5] Compiling Java classes with javac...", flush=True)
SRC_DIR = os.path.join(APP_DIR, r"app\src\main\java")
GEN_DIR = os.path.join(APP_DIR, r"build\gen")
java_files = []
for d in [SRC_DIR, GEN_DIR]:
    for root, _, files in os.walk(d):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f).replace("\\", "/"))

sources_file = os.path.join(APP_DIR, r"build\java_sources.txt")
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
print("  javac compiled classes successfully.", flush=True)

print("[1/5] Dexing compiled classes...", flush=True)
with zipfile.ZipFile(CLASSES_JAR, "w", compression=zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(OBJ_DIR):
        for f in files:
            if f.endswith(".class"):
                full = os.path.join(root, f)
                rel = os.path.relpath(full, OBJ_DIR)
                z.write(full, rel)

cmd_d8 = [JAVA_EXE, "-cp", R8_JAR, "com.android.tools.r8.D8", "--min-api", "24", "--output", DEX_DIR, "--lib", ANDROID_JAR, CLASSES_JAR]
subprocess.run(cmd_d8, check=True)
print(f"  D8 generated classes.dex ({os.path.getsize(CLASSES_DEX)} bytes)", flush=True)

print("[2/5] Compiling resources with updated AndroidManifest.xml...", flush=True)
manifest_src = os.path.join(APP_DIR, r"app\src\main\AndroidManifest.xml")
res_dir = os.path.join(APP_DIR, r"app\src\main\res")
if os.path.exists(RESOURCES_APK): os.remove(RESOURCES_APK)
cmd_aapt = [AAPT, "package", "-f", "-M", manifest_src, "-S", res_dir, "-I", ANDROID_JAR, "-F", RESOURCES_APK, "--auto-add-overlay"]
subprocess.run(cmd_aapt, check=True)
print(f"  aapt generated base_resources.apk ({os.path.getsize(RESOURCES_APK)} bytes)", flush=True)

print("[3/5] Packaging base resources and assets into unaligned APK...", flush=True)
with zipfile.ZipFile(BASE_APK, "r") as zin:
    ls_bytes = zin.read("assets/runtime/bin/liblanguage_server.so")
    ls_bytes = ls_bytes.replace(b"ineligible", b"inexigible").replace(b"https_proxy", b"AG_LS_PROXY")

    m = json.loads(zin.read("assets/runtime/manifest.json").decode())
    m["files"]["bin/liblanguage_server.so"] = hashlib.sha256(ls_bytes).hexdigest()
    new_manifest_bytes = json.dumps(m, indent=2).encode()

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

    with zipfile.ZipFile(BASE_APK, "r") as zin:
        for item in zin.infolist():
            if item.filename.startswith("META-INF/") or item.filename in ("AndroidManifest.xml", "resources.arsc", "classes.dex") or item.filename.startswith("res/"):
                continue
            if item.filename == "assets/runtime/bin/liblanguage_server.so":
                zout.writestr(item, ls_bytes)
            elif item.filename == "assets/runtime/manifest.json":
                zout.writestr(item, new_manifest_bytes)
            elif item.filename == "assets/web/generated/bridge.core.js":
                zout.writestr(item, bridge_core_bytes)
            elif item.filename == "assets/web/generated/main.js":
                zout.writestr(item, main_js_bytes)
            else:
                zout.writestr(item, zin.read(item.filename))

print(f"  Unaligned APK created: {os.path.getsize(OUTPUT_UNALIGNED)} bytes", flush=True)

print("[4/5] ZipAligning APK (4-byte alignment)...", flush=True)
if os.path.exists(OUTPUT_ALIGNED):
    os.remove(OUTPUT_ALIGNED)

subprocess.run([ZIPALIGN, "-f", "4", OUTPUT_UNALIGNED, OUTPUT_ALIGNED], check=True)

print("[5/5] Signing APK with apksigner...", flush=True)
if os.path.exists(FINAL_APK):
    os.remove(FINAL_APK)

env = os.environ.copy()
env["JAVA_HOME"] = JAVA_HOME
env["PATH"] = os.path.join(JAVA_HOME, "bin") + ";" + env["PATH"]

cmd_sign = [APKSIGNER, "sign", "--ks", KEYSTORE, "--ks-pass", "pass:android", "--key-pass", "pass:android", "--ks-key-alias", "androiddebugkey", "--out", FINAL_APK, OUTPUT_ALIGNED]
subprocess.run(cmd_sign, check=True, env=env)

print(f"SUCCESS! Created {FINAL_APK} ({os.path.getsize(FINAL_APK)} bytes)", flush=True)
