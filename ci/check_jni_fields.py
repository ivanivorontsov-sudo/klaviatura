#!/usr/bin/env python3
"""Release sanity check: every field/method of JNI-reflected classes must survive R8.
Compares fields of the sherpa-onnx / vosk / jna classes in the original jars with the dex in the release APK.
usage: check_jni_fields.py <apk> <sherpa.aar>"""
import glob, os, re, subprocess, sys, tempfile, zipfile

apk, aar = sys.argv[1], sys.argv[2]
dexdump = sorted(glob.glob(os.path.join(os.environ.get('ANDROID_HOME', '/usr/local/lib/android/sdk'), 'build-tools/*/dexdump')))[-1]
dump = subprocess.run([dexdump, apk], capture_output=True, text=True).stdout
dexfields = {}
cls = None
for line in dump.splitlines():
    m = re.match(r"\s+Class descriptor\s+: 'L([^;]+);'", line)
    if m: cls = m.group(1).replace('/', '.'); dexfields[cls] = set(); continue
    m = re.match(r"\s+name\s+: '([^']+)'", line)
    if m and cls: dexfields[cls].add(m.group(1))

tmp = tempfile.mkdtemp()
with zipfile.ZipFile(aar) as z: z.extract('classes.jar', tmp)
jar = os.path.join(tmp, 'classes.jar')
classes = [n[:-6].replace('/', '.') for n in zipfile.ZipFile(jar).namelist() if n.endswith('.class') and '$' not in n]
needed = [c for c in classes if c.endswith('Config') or c.endswith('Result') or c in ('com.k2fsa.sherpa.onnx.EndpointRule',)]
missing = []
for c in needed:
    out = subprocess.run(['javap', '-p', '-cp', jar, c], capture_output=True, text=True).stdout
    fields = re.findall(r"^\s+(?:private|public|protected)?\s*(?:final\s+)?[\w.\[\]<>$]+\s+(\w+);$", out, re.M)
    if c not in dexfields:
        # class not packaged at all: fine only if unused (R8 removed it entirely) - JNI looks classes up lazily
        continue
    for f in fields:
        if f not in dexfields[c]: missing.append(f"{c}.{f}")
checked = sum(1 for c in needed if c in dexfields)
print(f"checked {checked} sherpa classes in dex")
for c in ('org.vosk.LibVosk', 'com.sun.jna.Native', 'com.sun.jna.Pointer', 'com.sun.jna.Structure'):
    if c not in dexfields: missing.append(c + ' (class)')
if 'com.k2fsa.sherpa.onnx.OnlineRecognizerConfig' not in dexfields or 'blankPenalty' not in dexfields['com.k2fsa.sherpa.onnx.OnlineRecognizerConfig']:
    missing.append('OnlineRecognizerConfig.blankPenalty')
if missing:
    print('MISSING (R8 stripped JNI members):\n  ' + '\n  '.join(missing)); sys.exit(1)
print('JNI fields OK')
