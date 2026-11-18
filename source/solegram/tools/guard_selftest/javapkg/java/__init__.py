import os
GRANTED = set((os.environ.get("GRANT") or "").split(","))
BLOCKED = []
class _RG:
    def permits(self, pid, perm): return perm in GRANTED
    def onBlocked(self, pid, kind, detail): BLOCKED.append((str(pid),str(kind),str(detail)))
_rg=_RG()
class FakeObj:
    def __init__(s,n): s._n=n
    def __call__(s,*a,**k): return FakeObj(s._n+"()")
    def __getattr__(s,i): return FakeObj(s._n+"."+i)
def jclass(name,*a,**k):
    if str(name)=="com.solegram.messenger.plugins.PluginRuntimeGuard": return _rg
    return FakeObj(str(name))

def __getattr__(name):
    import importlib
    return importlib.import_module(__name__ + "." + name)
