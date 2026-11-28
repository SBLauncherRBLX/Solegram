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
import types
import _thread

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
    # a thread a plugin started -> that plugin's id (so what runs on it counts as the plugin even when no
    # plugin code is on the stack, e.g. threading.Thread(target=<a stdlib function>)); only the threads a
    # plugin itself started are here, never the app's own threads
    thread_owner = {}

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
        # no plugin code on the stack: still the plugin's doing if this is a thread the plugin started
        if not immediate:
            return thread_owner.get(_thread.get_ident())
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
        # Java's own ways to read and write files: never. open() in Python is the way to files, and there the guard
        # sees the path (the session, the keys, the databases, plugin code are refused even with file access);
        # Java's streams would take any path past it
        ("java.io.FileInputStream", None), ("java.io.FileOutputStream", None), ("java.io.RandomAccessFile", None),
        ("java.io.FileReader", None), ("java.io.FileWriter", None), ("java.nio.file.", None),
        ("java.nio.channels.FileChannel", None), ("android.os.ParcelFileDescriptor", None),
        ("android.os.SharedMemory", None),
        ("android.database.sqlite.", "database"), ("org.telegram.SQLite.", "database"),
        # reflection reaches what jclass would refuse: only for plugins whose report shows reflection or hooks
        ("java.lang.Class", "reflection"), ("java.lang.reflect.", "reflection"),
        # never: Solegram's plugin internals (installing, enabling, the guard, the report), its secrets and keys,
        # the wallet, the raw hook bridge (hooks go through hook_method, which keeps the guard's own code safe)
        ("com.solegram.messenger.plugins.", None),
        ("com.solegram.messenger.SolegramSecrets", None), ("com.solegram.messenger.ApiCredentials", None),
        ("com.solegram.messenger.wallet.", None),
        ("java.security.KeyStore", None), ("android.security.keystore.", None),
        ("de.robv.android.xposed.XposedBridge", None),
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
            deny(pid, "tamper" if ("plugins" in name or "xposed" in name) else ("files" if name.startswith(("java.io.", "java.nio.", "android.os.")) else "secrets"), name)
        if not permits(pid, permission):
            deny(pid, "native" if permission == "dex" else permission, name)

    def check_java_class(name):
        """For SDK helpers that look classes up by name for a plugin (hook_utils.find_class)."""
        check_java(name, caller(1))

    # --- dangerous Java *packages* a plugin must not import as a module (import java.io, from java import io,
    # importlib.import_module("java.io")). The jclass rules above see a class name; this sees a package the
    # plugin pulls in to reach a class by attribute, past the from-import check. Packages with ordinary plugin
    # use (java.util, android.widget, org.telegram.*, java.nio ByteBuffer) are not here.
    import_rules = (
        ("java.io", None), ("java.nio.file", None), ("java.nio.channels", None),
        ("java.net", "network"),
        ("java.lang.reflect", "reflection"),
        ("dalvik.system", "dex"), ("java.lang.ClassLoader", "dex"), ("java.net.URLClassLoader", "dex"),
        ("android.security", None), ("java.security.KeyStore", None),
        ("de.robv.android.xposed", None),
        ("com.solegram.messenger.plugins", None), ("com.solegram.messenger.wallet", None),
        ("com.solegram.messenger.SolegramSecrets", None), ("com.solegram.messenger.ApiCredentials", None),
    )

    def import_rule(name):
        for prefix, permission in import_rules:
            if name == prefix or name.startswith(prefix + "."):
                return True, permission
        return False, None

    def import_denied(name, frame):
        """Whether check_import would refuse this package to the plugin on the stack (no report, no raise)."""
        matched, permission = import_rule(name)
        if not matched:
            return False
        pid = plugin_at(frame)
        if pid is None:
            return False
        return permission is None or not permits(pid, permission)

    def check_import(name, frame):
        matched, permission = import_rule(name)
        if not matched:
            return
        pid = plugin_at(frame)
        if pid is None:
            return
        if permission is None:
            deny(pid, "tamper" if ("plugins" in name or "xposed" in name)
                 else ("files" if name.startswith(("java.io", "java.nio")) else "secrets"), name)
        if not permits(pid, permission):
            deny(pid, "native" if permission == "dex" else permission, name)

    original_jclass = java.jclass

    # --- PluginsController (installing, enabling, deleting plugins, the security policy) is never given to a plugin.
    # Plugins written against it get a stand-in instead: the read-only parts, and the parts that act on the plugin
    # itself (its settings, hooks, menu items). Anything else on it is refused with its name, so it is clear what was
    # asked for.
    pc_name = "com.solegram.messenger.plugins.PluginsController"
    pc_passthrough = ("isEngineAvailable", "isEngineRunning", "hasLoadedPlugins", "getLoadedCount", "hasRequestHooks",
                      "hasUpdateHooks", "hasSendMessageHooks", "isEnabledInPrefs", "getPluginsDir", "tlName",
                      "resolveDrawable", "isPluginFileName", "pluginsQueue", "APP_EVENT_START", "APP_EVENT_STOP",
                      "APP_EVENT_PAUSE", "APP_EVENT_RESUME")
    pc_own = ("getSettingJson", "setSettingJson", "getAllSettingsJson", "clearSettings", "notifySettingsReload",
              "registerHook", "unregisterHook", "registerSendMessageHook", "unregisterSendMessageHook",
              "setUpdatesHook", "addMenuItem", "removeMenuItem", "reportHookError")

    class PluginInfo(object):
        """What a plugin may know of an installed plugin (a copy: changing it changes nothing)."""
        __slots__ = ("id", "name", "description", "author", "version", "enabled")

        def __init__(self, meta):
            for field in ("id", "name", "description", "author", "version"):
                value = getattr(meta, field, None)
                object.__setattr__(self, field, None if value is None else str(value))
            object.__setattr__(self, "enabled", bool(getattr(meta, "enabled", False)))

        def __setattr__(self, key, value):
            raise AttributeError("read-only")

        def __repr__(self):
            return "PluginInfo(%r)" % (self.id,)

    def plugins_controller_for(pid):
        real = original_jclass(pc_name)

        class PluginsControllerView(object):
            __slots__ = ()

            def __getattr__(self, attr):
                if attr == "getInstance":
                    return lambda: self
                if attr in pc_passthrough:
                    return getattr(real, attr)
                if attr in pc_own:
                    method = getattr(real, attr)

                    def own(plugin_id, *rest):
                        if str(plugin_id) != pid:
                            deny(pid, "tamper", "%s.%s for another plugin" % (pc_name, attr))
                        return method(plugin_id, *rest)
                    own.__name__ = attr
                    return own
                if attr == "getPlugin":
                    def get_plugin(plugin_id):
                        meta = real.getInstance().getPlugin(plugin_id)
                        return None if meta is None else PluginInfo(meta)
                    return get_plugin
                if attr == "getPlugins":
                    return lambda: [PluginInfo(m) for m in real.getInstance().getPlugins()]
                deny(pid, "tamper", "%s.%s" % (pc_name, attr))

            def __setattr__(self, key, value):
                deny(pid, "tamper", "%s.%s" % (pc_name, key))

            def __repr__(self):
                return "<PluginsController (plugin view)>"

        return PluginsControllerView()

    def plugins_controller_wanted(name, frame):
        """The stand-in, when the plugin itself (not the SDK on its behalf) asks for PluginsController."""
        if str(name).replace("/", ".") != pc_name:
            return None
        pid = plugin_at(frame, immediate=True)
        return None if pid is None else plugins_controller_for(pid)

    def guarded_jclass(name, *args, **kwargs):
        view = plugins_controller_wanted(name, caller(1))
        if view is not None:
            return view
        check_java(name, caller(1))
        return original_jclass(name, *args, **kwargs)

    guarded_jclass.__doc__ = getattr(original_jclass, "__doc__", None)
    guarded_jclass.__name__ = "jclass"
    # only the public name: Chaquopy's own lookups (java.chaquopy) stay untouched
    java.jclass = guarded_jclass

    original_import = builtins.__import__

    def guarded_import(name, globals=None, locals=None, fromlist=(), level=0):
        if level == 0 and name:
            if name.split(".", 1)[0] in java_roots:
                frame = caller(1)
                # the module being pulled in (import java.io, importlib.import_module("java.io")); with a from-list
                # the plugin takes named classes out of it and gets no handle to the package itself, so the
                # classes are checked one by one instead: "from java.io import File" is fine, FileInputStream
                # is not ("from java import io" still checks the package, as java.io below)
                if not fromlist or "*" in fromlist:
                    check_import(name, frame)
                views = {}
                if fromlist:
                    for item in fromlist:
                        if not isinstance(item, str) or item == "*":
                            continue
                        view = plugins_controller_wanted(name + "." + item, frame)
                        if view is not None:
                            views[item] = view
                            continue
                        # from java.io import FileInputStream  /  from java import io
                        check_java(name + "." + item, frame)
                        if not item[:1].isupper():
                            # a package (Java packages are lower case, classes start with a capital)
                            check_import(name + "." + item, frame)
                    if import_denied(name, frame):
                        # __import__ hands back the package itself: a plugin calling it straight would get all
                        # of java.io past the checks, so it gets a stand-in holding only the checked names
                        module = original_import(name, globals, locals, fromlist, level)
                        stand_in = types.ModuleType(name)
                        for item in fromlist:
                            if isinstance(item, str) and item != "*":
                                if item in views:
                                    setattr(stand_in, item, views[item])
                                    continue
                                try:
                                    setattr(stand_in, item, getattr(module, item))
                                except AttributeError:
                                    pass
                        return stand_in
            elif name == "solegram_guard":
                # the plugin's own import statement only (the SDK imports the guard for it)
                pid = plugin_at(caller(1), immediate=True)
                if pid is not None:
                    deny(pid, "tamper", name)
        return original_import(name, globals, locals, fromlist, level)

    builtins.__import__ = guarded_import

    # importlib.import_module does not go through builtins.__import__, so the same package check is put on it;
    # a plugin reaching dangerous Java packages or the guard module through importlib is stopped the same way
    try:
        import importlib as _importlib
        original_import_module = _importlib.import_module

        def guarded_import_module(name, package=None):
            if name and not name.startswith("."):
                root = name.split(".", 1)[0]
                if root in java_roots:
                    check_import(name, caller(1))
                elif name == "solegram_guard":
                    pid = plugin_at(caller(1), immediate=True)
                    if pid is not None:
                        deny(pid, "tamper", name)
            return original_import_module(name, package)

        guarded_import_module.__name__ = "import_module"
        _importlib.import_module = guarded_import_module
    except Exception:
        pass

    # --- threads a plugin starts are tagged, so what runs on them is still attributed to the plugin even when
    # the thread's work is a stdlib function with no plugin frame on it: threading.Thread(target=shutil.copyfile),
    # a thread pool the plugin fed (ThreadPoolExecutor.submit), raw _thread. Every new OS thread, whatever starts
    # it, comes through one of these low-level primitives; the function it will run is wrapped so that, while it
    # runs, this thread is noted as the plugin's. The plugin is looked for anywhere under the code that started
    # the thread (fail closed): the app itself is Java and almost never makes a Python thread, so a Python thread
    # born while a plugin is on the stack is the plugin's doing. The tag is dropped the moment the thread ends.
    def run_as(pid, func, args, kwargs):
        ident = _thread.get_ident()
        thread_owner[ident] = pid
        try:
            return func(*args, **(kwargs or {}))
        finally:
            thread_owner.pop(ident, None)

    def wrap_thread_primitive(module, attr):
        original = getattr(module, attr, None)
        if not callable(original):
            return
        def guarded(func, *a, **k):
            pid = plugin_at(caller(1))  # the plugin under whoever is starting this thread
            wrapped = func if pid is None else (lambda *fa, **fk: run_as(pid, func, fa, fk))
            return original(wrapped, *a, **k)
        guarded.__name__ = getattr(original, "__name__", attr)
        try:
            setattr(module, attr, guarded)
        except Exception:
            pass

    # the OS primitive in _thread (for raw users), and the names threading cached from it at its own import
    for _m, _a in ((_thread, "start_new_thread"), (_thread, "start_joinable_thread"),
                   (threading, "_start_new_thread"), (threading, "_start_joinable_thread")):
        wrap_thread_primitive(_m, _a)

    def control_caller_ok(depth):
        """These are the engine's to call, never a plugin's. A plugin that got a handle to this module
        (sys.modules, a cached import) and tries to unregister itself or register something is stopped: its
        own frames are on the stack, the engine's calls run with none."""
        pid = plugin_at(sys._getframe(depth + 1))
        if pid is not None:
            deny(pid, "tamper", "guard control")

    def register(module_globals, plugin_id):
        control_caller_ok(1)
        registry[id(module_globals)] = (module_globals, str(plugin_id))

    def forget(plugin_id):
        control_caller_ok(1)
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
