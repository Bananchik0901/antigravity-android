import os, subprocess

APP_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src_dir = os.path.join(APP_DIR, "app", "src", "main", "java")
gen_dir = os.path.join(APP_DIR, "build", "gen")
obj_dir = os.path.join(APP_DIR, "build", "obj")
android_jar = os.path.join(APP_DIR, "tools", "android.jar")
java_home = os.environ.get("JAVA_HOME", r"C:\Program Files\Unity\Hub\Editor\2022.3.22f1\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK")
javac = os.path.join(java_home, "bin", "javac.exe") if os.name == "nt" else "javac"

os.makedirs(obj_dir, exist_ok=True)
java_files = []
for d in [src_dir, gen_dir]:
    for root, _, files in os.walk(d):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f).replace("\\", "/"))

print(f"Total java files: {len(java_files)}")
sources_file = os.path.join(APP_DIR, "build", "java_sources.txt")
with open(sources_file, "w", encoding="utf-8") as f:
    for jf in java_files:
        f.write(f'"{jf}"\n')

cmd = [
    javac,
    "-encoding", "UTF-8",
    "-source", "1.8",
    "-target", "1.8",
    "-d", obj_dir,
    "-cp", android_jar,
    f"@{sources_file}"
]

res = subprocess.run(cmd, capture_output=True)
print("javac return code:", res.returncode)
if res.stdout:
    print("stdout:", res.stdout.decode("cp1251", errors="replace")[:2000])
if res.stderr:
    print("stderr:", res.stderr.decode("cp1251", errors="replace")[:2000])
