"""Solegram runtime guard for plugins (the Java side is com.solegram.messenger.plugins.PluginRuntimeGuard).

Installed by PythonEngine before the plugin runtime and before any plugin or plugin package is imported. It
watches what plugin code does while it runs, through a CPython audit hook (PEP 578: once added, an audit hook
cannot be removed from Python) and through the two ways plugins reach Java classes (jclass and
"from java.lang import ..."):

* a plugin may do at run time only the kinds of things the static analysis found in its file and the user
  accepted (network, a shell, native code, dex, code built from strings, unpickling, installing apps): what was
  hidden from the analysis is stopped when it happens;
* never, whatever the report says: reading the account's session and key files, the preferences and the
  databases; writing plugin code, packages, Python itself, generated classes; looking for the guard through the
  garbage collector or other threads' frames.

What runs is attributed to a plugin by the Python frames on the stack: the module of every loaded plugin is
registered here, so a function of the plugin, a thread it started, a callback it gave to Java and a library it
called all count as the plugin.

Plugins run in Solegram's process: a plugin written against Solegram itself can still get around this through
Java reflection. This guard stops what the static analysis could not see and what generic malicious plugins do;
it is one layer of several (see SECURITY.md in the source).
"""
import builtins
import os
import sys
import threading

__all__ = ["install", "register", "forget", "current_plugin", "check_java_class"]

_api = {}


def install(roots, package):
    """roots: PluginRuntimeGuard.protectedRoots() (canonical paths, fixed order); package: the app's package."""
    if _api:
        return
    from java import jclass
    import java

    rg = jclass("com.solegram.messenger.plugins.PluginRuntimeGuard")
    roots = [str(r) if r is not None else "" for r in roots]
    (data_dir, files_dir, plugins_dir, libs_dir, sec_dir, chaq_dir, dex_dir, code_cache_dir,
     prefs_dir, db_dir) = (roots + [""] * 10)[:10]
    alias = "/data/data/" + str(package)
    sep = os.sep

    # plugin module globals (by identity) -> plugin id; the dicts are kept so an id is never reused
    registry = {}
    local = threading.local()

    code_ext = (".so", ".dex", ".jar", ".apk", ".odex", ".vdex", ".oat", ".pth")
    plugin_code_ext = (".py", ".plugin", ".pyc", ".pth", ".so", ".dex", ".jar")

    def under(path, root):
        return bool(root) and (path == root or path.startswith(root + sep))

    def norm(value, real=True):
        """The absolute path inside the app's data dir, or None for anything else (fds, other places)."""
        if value is None or isinstance(value, int):
            return None
        try:
            path = os.fsdecode(os.fspath(value))
        except Exception:
            return None
        if not path:
            return None
        if not os.path.isabs(path):
            try:
                path = os.path.join(os.getcwd(), path)
            except Exception:
                return None
        path = os.path.normpath(path)
        if path == alias or path.startswith(alias + sep):
            path = data_dir + path[len(alias):]
        if not under(path, data_dir):
            return None
        if real and not (under(path, chaq_dir) or under(path, libs_dir)):
            try:
                path = os.path.realpath(path)
            except Exception:
                pass
        return path

    def secret(path):
        base = os.path.basename(path)
        return (base == "tgnet.dat" or base.startswith("cache4.db") or under(path, prefs_dir) or under(path, db_dir)
                or under(path, sec_dir))

    def guarded(path):
        if secret(path):
            return True
        if under(path, libs_dir) or under(path, chaq_dir) or under(path, dex_dir) or under(path, code_cache_dir):
            return True
        return under(path, plugins_dir) and path.lower().endswith(plugin_code_ext)

    def caller(depth):
        """The frame `depth` levels above the one asking, or None when there is none: a call made straight from
        Java (Chaquopy calls jclass from Java while the engine starts) has no Python code above it."""
        try:
            return sys._getframe(depth + 1)
        except ValueError:
            return None

    def plugin_at(frame, immediate=False):
        f = frame
        while f is not None:
            entry = registry.get(id(f.f_globals))
            if entry is not None and entry[0] is f.f_globals:
                return entry[1]
            if immediate:
                return None
            f = f.f_back
        return None

    def permits(plugin_id, permission):
        try:
            return bool(rg.permits(plugin_id, permission))
        except Exception:
            return False

    def deny(plugin_id, kind, detail):
        try:
            rg.onBlocked(plugin_id, kind, str(detail)[:200])
        except Exception:
            pass
        raise PermissionError("Solegram blocked this for the plugin (%s): %s" % (kind, detail))

    def is_write(mode, flags):
        if isinstance(mode, str):
            return any(c in mode for c in "wax+")
        if isinstance(flags, int):
            return bool(flags & (os.O_WRONLY | os.O_RDWR | os.O_APPEND | os.O_CREAT | os.O_TRUNC))
        return False

    network = {"socket.connect", "socket.bind", "socket.sendto", "socket.sendmsg", "socket.getaddrinfo",
               "socket.gethostbyname", "socket.gethostbyname_ex", "socket.gethostbyaddr"}
    shell = {"subprocess.Popen", "os.system", "os.exec", "os.posix_spawn", "os.spawn", "os.fork", "os.forkpty",
             "pty.spawn", "os.kill", "os.killpg"}
    native = {"ctypes.dlopen", "ctypes.dlsym", "ctypes.dlsym/handle", "ctypes.call_function", "ctypes.cdata",
              "ctypes.cdata/buffer", "ctypes.addressof", "ctypes.string_at", "ctypes.wstring_at"}
    paths_one = {"os.remove", "os.rmdir", "os.truncate", "os.chmod", "os.chown", "os.utime", "os.mkdir",
                 "shutil.rmtree", "os.chflags", "os.setxattr", "os.removexattr"}
    paths_two = {"os.rename", "os.link", "os.symlink", "shutil.copyfile", "shutil.copytree", "shutil.move"}
    tamper = {"gc.get_objects", "gc.get_referrers", "gc.get_referents", "sys._current_frames",
              "sys._current_exceptions"}
    code = {"exec", "compile", "marshal.loads", "marshal.load"}
    watched = network | shell | native | paths_one | paths_two | tamper | code | {"open", "pickle.find_class"}

    def hook(event, args):
        if event not in watched or not registry:
            return
        if getattr(local, "busy", False):
            return
        local.busy = True
        try:
            check(event, args)
        except PermissionError:
            raise
        except Exception:
            pass
        finally:
            local.busy = False

    def check(event, args):
        frame = caller(2)  # check <- hook <- the Python code that called the audited function
        if event == "open":
            if not args:
                return
            write = is_write(args[1] if len(args) > 1 else None, args[2] if len(args) > 2 else None)
            path = norm(args[0])
            if path is None:
                return
            if secret(path):
                pid = plugin_at(frame)
                if pid is not None:
                    deny(pid, "secrets", os.path.basename(path))
                return
            if write:
                if guarded(path):
                    pid = plugin_at(frame)
                    if pid is not None:
                        deny(pid, "files", path)
                elif path.lower().endswith(code_ext):
                    pid = plugin_at(frame)
                    if pid is not None and not (permits(pid, "native") or permits(pid, "dex")):
                        deny(pid, "native", os.path.basename(path))
            return
        if event in network:
            pid = plugin_at(frame)
            if pid is not None and not permits(pid, "network"):
                target = args[1] if event in ("socket.connect", "socket.bind", "socket.sendto") and len(args) > 1 else (args[0] if args else "")
                deny(pid, "network", target)
            return
        if event in shell:
            pid = plugin_at(frame)
            if pid is not None and not permits(pid, "shell"):
                deny(pid, "shell", args[0] if args else event)
            return
        if event in native:
            pid = plugin_at(frame)
            if pid is not None and not permits(pid, "native"):
                deny(pid, "native", event)
            return
        if event in tamper:
            pid = plugin_at(frame)
            if pid is not None:
                deny(pid, "tamper", event)
            return
        if event in paths_one or event in paths_two:
            values = args[:2] if event in paths_two else args[:1]
            for i, value in enumerate(values):
                path = norm(value)
                if path is None:
                    continue
                code_target = event in paths_two and i == 1 and path.lower().endswith(code_ext)
                if guarded(path) or (event in ("os.symlink", "os.link") and i == 0 and secret(path)):
                    pid = plugin_at(frame)
                    if pid is not None:
                        deny(pid, "files", path)
                elif code_target:
                    pid = plugin_at(frame)
                    if pid is not None and not (permits(pid, "native") or permits(pid, "dex")):
                        deny(pid, "native", os.path.basename(path))
            return
        if event in code:
            # only code the plugin itself runs from strings: dataclasses, namedtuple, imports... run theirs
            pid = plugin_at(frame, immediate=True)
            if pid is None:
                return
            kind = "deserialize" if event.startswith("marshal") else "exec"
            if not permits(pid, kind):
                deny(pid, kind, event)
            return
        if event == "pickle.find_class":
            pid = plugin_at(frame)
            if pid is not None and not permits(pid, "deserialize"):
                deny(pid, "deserialize", "%s.%s" % (args[0], args[1]) if len(args) > 1 else event)

    # --- Java classes a plugin may reach only with the matching permission (None: never)
    java_rules = (
        ("java.lang.Runtime", "shell"), ("java.lang.ProcessBuilder", "shell"), ("java.lang.Process", "shell"),
        ("java.lang.ProcessImpl", "shell"), ("android.system.Os", "shell"),
        ("dalvik.system.", "dex"), ("java.lang.ClassLoader", "dex"), ("java.net.URLClassLoader", "dex"),
        ("java.net.URL", "network"), ("java.net.URLConnection", "network"), ("java.net.HttpURLConnection", "network"),
        ("java.net.JarURLConnection", "network"), ("java.net.Socket", "network"), ("java.net.ServerSocket", "network"),
        ("java.net.DatagramSocket", "network"), ("java.net.MulticastSocket", "network"), ("java.net.InetAddress", "network"),
        ("java.net.Inet4Address", "network"), ("java.net.Inet6Address", "network"), ("java.nio.channels.SocketChannel", "network"),
        ("javax.net.", "network"), ("okhttp3.", "network"), ("org.apache.http.", "network"),
        ("android.net.http.", "network"), ("android.webkit.WebView", "network"), ("android.net.LocalSocket", "network"),
        ("android.content.pm.PackageInstaller", "install"),
        ("com.solegram.messenger.plugins.PluginRuntimeGuard", None), ("com.solegram.messenger.plugins.PluginGuard", None),
        ("com.solegram.messenger.SolegramSecrets", None), ("com.solegram.messenger.ApiCredentials", None),
    )
    java_roots = ("java", "javax", "android", "dalvik", "okhttp3", "org", "com")

    def java_rule(name):
        for prefix, permission in java_rules:
            if prefix.endswith("."):
                if name.startswith(prefix):
                    return True, permission
            elif name == prefix or name.startswith(prefix + "$"):
                return True, permission
        return False, None

    def check_java(name, frame):
        name = str(name).replace("/", ".")
        matched, permission = java_rule(name)
        if not matched:
            return
        pid = plugin_at(frame)
        if pid is None:
            return
        if permission is None:
            deny(pid, "tamper" if "plugins" in name else "secrets", name)
        if not permits(pid, permission):
            deny(pid, "native" if permission == "dex" else permission, name)

    def check_java_class(name):
        """For SDK helpers that look classes up by name for a plugin (hook_utils.find_class)."""
        check_java(name, caller(1))

    original_jclass = java.jclass

    def guarded_jclass(name, *args, **kwargs):
        check_java(name, caller(1))
        return original_jclass(name, *args, **kwargs)

    guarded_jclass.__doc__ = getattr(original_jclass, "__doc__", None)
    guarded_jclass.__name__ = "jclass"
    # only the public name: Chaquopy's own lookups (java.chaquopy) stay untouched
    java.jclass = guarded_jclass

    original_import = builtins.__import__

    def guarded_import(name, globals=None, locals=None, fromlist=(), level=0):
        if level == 0 and name:
            if fromlist and name.split(".", 1)[0] in java_roots:
                frame = None
                for item in fromlist:
                    if not isinstance(item, str) or item == "*":
                        continue
                    if frame is None:
                        frame = caller(1)
                    check_java(name + "." + item, frame)
            elif name == "solegram_guard":
                # the plugin's own import statement only (the SDK imports the guard for it)
                pid = plugin_at(caller(1), immediate=True)
                if pid is not None:
                    deny(pid, "tamper", name)
        return original_import(name, globals, locals, fromlist, level)

    builtins.__import__ = guarded_import

    def register(module_globals, plugin_id):
        registry[id(module_globals)] = (module_globals, str(plugin_id))

    def forget(plugin_id):
        plugin_id = str(plugin_id)
        for key in [k for k, v in registry.items() if v[1] == plugin_id]:
            registry.pop(key, None)

    def current_plugin():
        try:
            return plugin_at(caller(1))
        except Exception:
            return None

    _api.update(register=register, forget=forget, current_plugin=current_plugin, check_java_class=check_java_class)
    sys.addaudithook(hook)


def register(module_globals, plugin_id):
    _api["register"](module_globals, plugin_id)


def forget(plugin_id):
    _api["forget"](plugin_id)


def current_plugin():
    return _api["current_plugin"]() if _api else None


def check_java_class(name):
    if _api:
        _api["check_java_class"](name)
