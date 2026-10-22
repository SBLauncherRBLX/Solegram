"""Solegram plugin runtime: loads exteraGram/AyuGram-style plugin files and exposes each loaded plugin
to Java as a com.solegram.messenger.plugins.PluginHandle.

Entry points (called by PythonEngine.java): init, read_metadata, install_requirements, load_plugin,
unload_plugin, forget_plugin, python_version.
"""
import os
import platform
import re
import sys
import threading
import traceback
import types

from java import dynamic_proxy, jclass

import log_utils
import plugin_registry
from utils import metadata_parser

_PluginHandle = jclass("com.solegram.messenger.plugins.PluginHandle")
_JHookResult = jclass("com.solegram.messenger.plugins.HookResult")
_PC = jclass("com.solegram.messenger.plugins.PluginsController")
_JNumber = jclass("java.lang.Number")
_JBoolean = jclass("java.lang.Boolean")
_JCharSequence = jclass("java.lang.CharSequence")

_logger = log_utils.get_logger("pysdk")
_lock = threading.RLock()
_modules = {}  # plugin id -> module name
_aliases = {}  # plugin id -> display name registered in sys.modules

_state = {
    "plugins_dir": None,
    "app_version": "0",
    "sdk_version": "0",
    "dev_mode": False,
}


# --------------------------------------------------------------------------- boot

def init(plugins_dir, libs_dir, dex_dir, app_version, sdk_version, dev_mode=False):
    _state.update(plugins_dir=str(plugins_dir), app_version=str(app_version), sdk_version=str(sdk_version),
                  dex_dir=str(dex_dir), dev_mode=bool(dev_mode))
    log_utils._set_dev_mode(bool(dev_mode))
    try:
        jclass("com.solegram.messenger.plugins.python.PyClassProxy").setDexCache(str(dex_dir))
    except Exception:
        _logger.error("class proxy unavailable:\n%s", traceback.format_exc())
    try:
        import solegram_pip
        solegram_pip.init(str(libs_dir))
    except Exception:
        _logger.error("package manager unavailable:\n%s", traceback.format_exc())
    if dev_mode:
        try:
            import dev_server
            dev_server.DevServer.start_server()
        except Exception:
            _logger.error("dev server failed to start:\n%s", traceback.format_exc())
    _logger.info("plugin runtime ready: Python %s, SDK %s", platform.python_version(), sdk_version)


def python_version():
    return platform.python_version()


def app_version():
    return _state["app_version"]


def sdk_version():
    return _state["sdk_version"]


# --------------------------------------------------------------------------- metadata

def read_metadata(path):
    return metadata_parser.get_metadata(path)


_CONSTRAINT = re.compile(r"(>=|<=|==|!=|>|<|=)?\s*([0-9][0-9A-Za-z.\-]*)")


def _vtuple(v):
    parts = []
    for p in re.split(r"[.\-]", str(v)):
        m = re.match(r"\d+", p)
        parts.append(int(m.group(0)) if m else 0)
    while len(parts) > 1 and parts[-1] == 0:
        parts.pop()
    return tuple(parts)


def satisfies(actual, constraint):
    """True when `actual` matches a constraint such as ">=12.5.1" or ">=1.4, <2"."""
    if not constraint:
        return True
    a = _vtuple(actual)
    for chunk in re.split(r"[,\s]+(?=[<>=!])|,", str(constraint)):
        chunk = chunk.strip()
        if not chunk:
            continue
        m = _CONSTRAINT.fullmatch(chunk)
        if not m:
            continue
        op, ver = m.group(1) or ">=", _vtuple(m.group(2))
        ok = {
            ">=": a >= ver, "<=": a <= ver, "==": a == ver, "=": a == ver,
            "!=": a != ver, ">": a > ver, "<": a < ver,
        }[op]
        if not ok:
            return False
    return True


# --------------------------------------------------------------------------- requirements

def install_requirements(plugin_id, requirements):
    import solegram_pip
    return solegram_pip.install(str(plugin_id), [str(r) for r in (requirements or [])])


def forget_plugin(plugin_id):
    """The plugin file was deleted: drop packages nobody else needs."""
    try:
        import solegram_pip
        solegram_pip.release(str(plugin_id))
    except Exception:
        _logger.error("releasing packages of %s failed:\n%s", plugin_id, traceback.format_exc())


# --------------------------------------------------------------------------- conversion helpers

def _py(value):
    """Java boxed values -> Python values (Chaquopy usually does this already)."""
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    try:
        if isinstance(value, _JBoolean):
            return bool(value.booleanValue())
        if isinstance(value, _JNumber):
            cls = value.getClass().getSimpleName()
            if cls in ("Float", "Double", "BigDecimal"):
                return float(value.doubleValue())
            return int(value.longValue())
    except Exception:
        pass
    return value


def _java_map_to_dict(jmap):
    result = {}
    if jmap is None:
        return result
    try:
        for entry in jmap.entrySet().toArray():
            result[str(entry.getKey())] = _py(entry.getValue())
    except Exception:
        _logger.error("context conversion failed:\n%s", traceback.format_exc())
    return result


_STRATEGIES = {"DEFAULT": 0, "CANCEL": 1, "MODIFY": 2, "MODIFY_FINAL": 3}


def _strategy_code(strategy):
    if strategy is None:
        return 0
    name = getattr(strategy, "name", None)
    if name in _STRATEGIES:
        return _STRATEGIES[name]
    value = getattr(strategy, "value", strategy)
    try:
        return int(value)
    except Exception:
        return _STRATEGIES.get(str(value).upper(), 0)


def _to_java_result(plugin, result):
    if result is None:
        return None
    code = _strategy_code(getattr(result, "strategy", None))
    if code == 0:
        return None
    jr = _JHookResult(code)
    for name in ("request", "response", "update", "updates", "error", "params"):
        value = getattr(result, name, None)
        if value is not None:
            try:
                setattr(jr, name, value)
            except Exception:
                plugin.logger.warning("HookResult.%s can't be passed to Java: %r", name, value)
    return jr


def _overrides(cls, name):
    from base_plugin import BasePlugin
    return getattr(cls, name, None) is not getattr(BasePlugin, name, None)


# --------------------------------------------------------------------------- handle

class _Handle(dynamic_proxy(_PluginHandle)):
    """Java view of one loaded plugin. Every call is isolated: plugin errors are logged, never thrown."""

    def __init__(self, plugin):
        super().__init__()
        self.plugin = plugin
        cls = type(plugin)
        self._pre = _overrides(cls, "pre_request_hook")
        self._post = _overrides(cls, "post_request_hook")
        self._update = _overrides(cls, "on_update_hook")
        self._updates = _overrides(cls, "on_updates_hook")
        self._send = _overrides(cls, "on_send_message_hook")
        self._settings = _overrides(cls, "create_settings")

    def _hook(self, fn, account, *args):
        from client_utils import _account_scope
        with _account_scope(int(account)):
            try:
                return _to_java_result(self.plugin, fn(*args))
            except Exception:
                self.plugin.logger.error("%s failed:\n%s", fn.__name__, traceback.format_exc())
                return None

    def preRequestHook(self, request_name, account, request):
        if not self._pre:
            return None
        return self._hook(self.plugin.pre_request_hook, account, str(request_name), int(account), request)

    def postRequestHook(self, request_name, account, response, error):
        if not self._post:
            return None
        return self._hook(self.plugin.post_request_hook, account, str(request_name), int(account), response, error)

    def onUpdateHook(self, update_name, account, update):
        if not self._update:
            return None
        return self._hook(self.plugin.on_update_hook, account, str(update_name), int(account), update)

    def onUpdatesHook(self, container_name, account, updates):
        if not self._updates:
            return None
        return self._hook(self.plugin.on_updates_hook, account, str(container_name), int(account), updates)

    def onSendMessageHook(self, account, params):
        if not self._send:
            return None
        return self._hook(self.plugin.on_send_message_hook, account, int(account), params)

    def onAppEvent(self, event):
        from base_plugin import AppEvent
        try:
            ev = AppEvent(str(event))
        except ValueError:
            return
        try:
            self.plugin.on_app_event(ev)
        except Exception:
            self.plugin.logger.error("on_app_event failed:\n%s", traceback.format_exc())

    def hasSettings(self):
        return bool(self._settings)

    def createSettings(self):
        if not self._settings:
            return None
        from ui.settings import to_java_list
        with plugin_registry.loading(self.plugin.id):
            items = self.plugin.create_settings()
            return to_java_list(items, self.plugin)

    def onMenuItemClick(self, item_id, context):
        callbacks = self.plugin.__dict__.get("_sg_menu_callbacks") or {}
        fn = callbacks.get(str(item_id))
        if fn is None:
            return
        ctx = _java_map_to_dict(context)
        account = ctx.get("account")
        from client_utils import _account_scope
        with _account_scope(account if isinstance(account, int) else None):
            try:
                fn(ctx)
            except Exception:
                self.plugin.logger.error("menu item %s failed:\n%s", item_id, traceback.format_exc())


# --------------------------------------------------------------------------- load / unload

class PluginLoadError(Exception):
    pass


def _find_plugin_class(module, module_name):
    from base_plugin import BasePlugin
    found = []
    for value in list(module.__dict__.values()):
        if isinstance(value, type) and issubclass(value, BasePlugin) and value is not BasePlugin:
            found.append(value)
    if not found:
        return None
    own_names = {module_name, module.__dict__.get("__name__")}
    own = [c for c in found if c.__module__ in own_names]
    candidates = own or found
    # prefer the most derived class (a plugin may define a helper base class first)
    for c in candidates:
        if not any(o is not c and issubclass(o, c) for o in candidates):
            return c
    return candidates[-1]


def _module_name(plugin_id):
    name = plugin_id
    existing = sys.modules.get(name)
    if existing is not None and getattr(existing, "__sg_plugin__", None) != plugin_id:
        name = "solegram_plugin_" + plugin_id.replace("-", "_")
    return name


def load_plugin(plugin_id, path):
    plugin_id = str(plugin_id)
    path = os.path.abspath(str(path))
    with _lock:
        if plugin_registry.get_plugin(plugin_id) is not None:
            unload_plugin(plugin_id)
        with open(path, "r", encoding="utf-8-sig") as f:
            source = f.read()
        meta = metadata_parser.parse_source(source, path)
        if meta.get("app_version") and not satisfies(_state["app_version"], meta["app_version"]):
            raise PluginLoadError(f"Requires app version {meta['app_version']} (this is {_state['app_version']})")
        if meta.get("sdk_version") and not satisfies(_state["sdk_version"], meta["sdk_version"]):
            raise PluginLoadError(f"Requires plugin SDK {meta['sdk_version']} (this is {_state['sdk_version']})")

        module_name = _module_name(plugin_id)
        module = types.ModuleType(module_name)
        module.__file__ = path
        module.__dict__["__module_name__"] = module_name
        module.__dict__["__sg_plugin__"] = plugin_id
        sys.modules[module_name] = module
        _modules[plugin_id] = module_name
        # Plugin metadata assigns __name__ = "Display Name", so classes get __module__ = "Display Name";
        # dataclasses/typing then look that up in sys.modules. Alias it while the plugin is loaded.
        alias = meta.get("name")
        if alias and alias != module_name and alias not in sys.modules:
            sys.modules[alias] = module
            _aliases[plugin_id] = alias
        log_utils._register_file(path, plugin_id)

        plugin = None
        try:
            with plugin_registry.loading(plugin_id):
                code = compile(source, path, "exec")
                exec(code, module.__dict__)
                cls = _find_plugin_class(module, module_name)
                if cls is None:
                    raise PluginLoadError("No BasePlugin subclass found in the plugin file")
                cls.id = plugin_id
                cls.name = meta.get("name") or plugin_id
                cls.description = meta.get("description") or ""
                cls.author = meta.get("author") or ""
                cls.version = meta.get("version") or "1.0"
                cls.icon = meta.get("icon")
                cls.min_version = meta.get("app_version")
                plugin = cls()
                plugin.id = plugin_id
                plugin_registry.set_plugin(plugin_id, plugin)
                plugin.on_plugin_load()
                plugin.enabled = True
                plugin.initialized = True
                if _overrides(cls, "on_updates_hook"):
                    _PC.setUpdatesHook(plugin_id, True)
        except BaseException as e:
            log_utils._plugin_logger(plugin_id).error("loading failed:\n%s", traceback.format_exc())
            _release(plugin_id, plugin, call_unload=False)
            if isinstance(e, (PluginLoadError, SyntaxError, ImportError)):
                raise
            raise PluginLoadError(f"{type(e).__name__}: {e}") from e
        return _Handle(plugin)


def _release(plugin_id, plugin, call_unload=True):
    if plugin is not None:
        if call_unload:
            try:
                plugin.on_plugin_unload()
            except Exception:
                plugin.logger.error("on_plugin_unload failed:\n%s", traceback.format_exc())
        try:
            plugin._sg_release()
        except Exception:
            pass
        plugin.enabled = False
    plugin_registry.run_cleanups(plugin_id)
    plugin_registry.set_plugin(plugin_id, None)
    log_utils._release_plugin(plugin_id)
    module_name = _modules.pop(plugin_id, None)
    if module_name is not None:
        sys.modules.pop(module_name, None)
    alias = _aliases.pop(plugin_id, None)
    if alias is not None:
        sys.modules.pop(alias, None)


def unload_plugin(plugin_id):
    plugin_id = str(plugin_id)
    with _lock:
        _release(plugin_id, plugin_registry.get_plugin(plugin_id), call_unload=True)


def set_dev_mode(enabled):
    enabled = bool(enabled)
    _state["dev_mode"] = enabled
    log_utils._set_dev_mode(enabled)
    try:
        import dev_server
        if enabled:
            dev_server.DevServer.start_server()
        else:
            dev_server.DevServer.stop_server()
    except Exception:
        _logger.error("dev server toggle failed:\n%s", traceback.format_exc())
