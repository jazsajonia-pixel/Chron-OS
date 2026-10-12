"""Copies the runtime's C sources for a host build, adapting @CriticalNative functions: on Android (ART) they take
no JNIEnv/jclass, but a desktop JVM always passes both, so the host copy gets the two leading parameters back."""
import os, re, sys
src, dst = sys.argv[1], sys.argv[2]
os.makedirs(dst, exist_ok=True)
sig = re.compile(r'(JNIEXPORT\s+\w+\s+JNICALL\s+Java_\w+\s*\()([^)]*)\)', re.S)
def fix(m):
    params = m.group(2).strip()
    if params.startswith('JNIEnv'): return m.group(0)
    return m.group(1) + 'JNIEnv* __env, jclass __cls' + (', ' + params if params and params != 'void' else '') + ')'
for name in os.listdir(src):
    if name.endswith('.c'):
        text = open(os.path.join(src, name)).read()
        open(os.path.join(dst, name), 'w').write(sig.sub(fix, text))
