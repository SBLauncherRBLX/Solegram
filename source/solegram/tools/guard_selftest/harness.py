"""Runs one attack plugin against the real solegram_guard.

Usage: python3 -I harness.py <attack.py>
Env: GRANT=comma,perms (permissions the user supposedly granted the plugin; default none)

Prints one line:  BLOCKED <kind> | LEAK <detail> | ERROR <detail>
A guard that works denies every attack of a plugin that was granted nothing.
"""
import os
import sys
import types

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")

# self-provision a mock app data layout so this runs from a clean checkout
def _ensure_data():
    import os
    base = os.path.join(HERE, "data")
    tree = {
        "files/tgnet.dat": "SESSION",
        "databases/cache4.db": "CHATS",
        "shared_prefs/solegram_secrets.xml": "KEY=secret",
        "files/plugins/victim.py": "print('x')",
    }
    for d in ("files/plugins", "files/plugins_libs", "secrets", "chaquopy", "dex",
              "code_cache", "shared_prefs", "databases"):
        os.makedirs(os.path.join(base, d), exist_ok=True)
    for rel, content in tree.items():
        fp = os.path.join(base, rel)
        os.makedirs(os.path.dirname(fp), exist_ok=True)
        if not os.path.exists(fp):
            open(fp, "w").write(content)

_ensure_data()

# resolve `import java` / `from java import jclass` to a real fake package so submodule imports work
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "javapkg"))
import java as fakejava   # the package __init__ holds BLOCKED and jclass

import solegram_guard

# roots: data, files, plugins, libs, sec, chaquopy, dex, code_cache, prefs, db
roots = [
    DATA,
    os.path.join(DATA, "files"),
    os.path.join(DATA, "files", "plugins"),
    os.path.join(DATA, "files", "plugins_libs"),
    os.path.join(DATA, "secrets"),
    os.path.join(DATA, "chaquopy"),
    os.path.join(DATA, "dex"),
    os.path.join(DATA, "code_cache"),
    os.path.join(DATA, "shared_prefs"),
    os.path.join(DATA, "databases"),
]
solegram_guard.install(roots, "com.solegram.messenger")

# load the attack as a module living under the plugins dir, with real frames
attack_path = sys.argv[1]
src = open(attack_path).read()
mod = types.ModuleType("evilplugin")
mod.__file__ = os.path.join(roots[2], "evilplugin.py")
code = compile(src, mod.__file__, "exec")
# register the plugin's globals BEFORE running, so every frame counts as the plugin
solegram_guard.register(mod.__dict__, "evil")

CTX = {
    "session": os.path.join(roots[1], "tgnet.dat"),
    "db": os.path.join(roots[9], "cache4.db"),
    "secret": os.path.join(roots[8], "solegram_secrets.xml"),
    "plugin_code": os.path.join(roots[2], "victim.py"),
    "data_dir": DATA,
    "roots": roots,
}
mod.__dict__["CTX"] = CTX

SECRET_MARKERS = ("SESSION", "CHATS", "KEY=secret")


def leaked(value):
    try:
        text = value.decode("utf-8", "ignore") if isinstance(value, (bytes, bytearray)) else str(value)
    except Exception:
        return False
    return any(m in text for m in SECRET_MARKERS)


result = None
err = None
try:
    exec(code, mod.__dict__)
    run = mod.__dict__.get("run")
    if run is None:
        print("ERROR no run()")
        sys.exit(0)
    result = run(CTX)
except PermissionError as e:
    err = ("PermissionError", str(e))
except Exception as e:
    err = (type(e).__name__, str(e))

# authoritative: did the plugin obtain secret bytes anywhere (incl. files it copied out and could read back)?
got = leaked(result)
if not got:
    for name in ("stolen.dat", "stolen_pool.dat", "stolen_raw.dat"):
        for d in (roots[1], roots[2]):
            p = os.path.join(d, name)
            try:
                if os.path.isfile(p) and leaked(open(p, "rb").read()):
                    got = True
            except Exception:
                pass

blocked = len(fakejava.BLOCKED) > 0
if got:
    print("LEAK " + (str(result)[:120].replace("\n", " ")))
elif blocked:
    print("BLOCKED " + fakejava.BLOCKED[-1][1] + ": " + fakejava.BLOCKED[-1][2][:120])
elif err:
    print("ERROR %s: %s" % (err[0], err[1][:160].replace("\n", " ")))
else:
    print("PASS (no secret obtained, no block recorded)")
