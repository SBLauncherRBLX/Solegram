"""Plugin base classes and hook types (exteraGram plugin SDK compatible).

A plugin file defines top-level metadata (__id__, __name__, ...) and one subclass of BasePlugin.
"""
import abc
import enum
import traceback
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any, Callable, Dict, List, Optional

from java import jclass

import plugin_settings as _settings
from log_utils import _plugin_logger

_PC = jclass("com.solegram.messenger.plugins.PluginsController")
_PyXposedHook = jclass("com.solegram.messenger.plugins.python.PyXposedHook")
_PyReplacement = jclass("com.solegram.messenger.plugins.python.PyXposedHook$Replacement")

__all__ = [
    "BasePlugin", "HookResult", "HookStrategy", "MenuItemData", "MenuItemType", "AppEvent",
    "MethodHook", "MethodReplacement", "XposedHook", "BaseHook", "HookFilter", "HookFilterData",
    "hook_filters", "PluginMetadata", "PluginError",
]

_xposed_bridge = None


def _xposed():
    global _xposed_bridge
    if _xposed_bridge is None:
        try:
            _xposed_bridge = jclass("de.robv.android.xposed.XposedBridge")
        except Exception as e:
            raise PluginError(f"Method hooking is not available on this device: {e}")
    return _xposed_bridge


@dataclass
class PluginMetadata:
    id: str
    name: str
    description: str = ""
    author: str = ""
    version: str = "1.0"
    icon: str = None
    min_version: str = None


class HookStrategy(enum.Enum):
    DEFAULT = 0
    CANCEL = 1
    MODIFY = 2
    MODIFY_FINAL = 3


@dataclass
class HookResult:
    strategy: HookStrategy = HookStrategy.DEFAULT
    request: Any = None
    response: Any = None
    update: Any = None
    updates: Any = None
    error: Any = None
    params: Any = None


class PluginError(Exception):
    def __init__(self, message: str, plugin_id: Optional[str] = None):
        super().__init__(message)
        self.plugin_id = plugin_id


class AppEvent(enum.Enum):
    START = "START"
    STOP = "STOP"
    PAUSE = "PAUSE"
    RESUME = "RESUME"


class MenuItemType(enum.Enum):
    MESSAGE_CONTEXT_MENU = "MESSAGE_CONTEXT_MENU"
    DRAWER_MENU = "DRAWER_MENU"
    MAIN_MENU = "MAIN_MENU"
    CHAT_ACTION_MENU = "CHAT_ACTION_MENU"
    PROFILE_ACTION_MENU = "PROFILE_ACTION_MENU"


@dataclass
class MenuItemData:
    menu_type: MenuItemType
    text: str
    on_click: Callable[[Dict[str, Any]], None]
    item_id: Optional[str] = None
    icon: Optional[str] = None
    subtext: Optional[str] = None
    condition: Optional[str] = None
    priority: int = 0


# --------------------------------------------------------------------------- filters

@dataclass
class HookFilterData:
    filter_type: str
    arg_index: Optional[int] = None
    or_filters: Any = None
    mvel_expression: Optional[str] = None
    instance_of: Any = None
    object: Any = None

    def to_java_filter(self):
        return self

    def matches(self, param) -> bool:
        t = self.filter_type
        try:
            if t.startswith("RESULT"):
                result = param.getResult()
                if t == "RESULT_IS_NULL":
                    return result is None
                if t == "RESULT_NOT_NULL":
                    return result is not None
                if t == "RESULT_IS_TRUE":
                    return result is True or result == True  # noqa: E712
                if t == "RESULT_IS_FALSE":
                    return result is False or result == False  # noqa: E712
                if t == "RESULT_INSTANCE_OF":
                    return result is not None and self.instance_of.isInstance(result)
                if t == "RESULT_EQUAL":
                    return result == self.object
                if t == "RESULT_NOT_EQUAL":
                    return result != self.object
            if t.startswith("ARG"):
                args = param.args
                if args is None or self.arg_index is None or self.arg_index >= len(args):
                    return False
                arg = args[self.arg_index]
                if t == "ARG_IS_NULL":
                    return arg is None
                if t == "ARG_NOT_NULL":
                    return arg is not None
                if t == "ARG_IS_TRUE":
                    return arg is True or arg == True  # noqa: E712
                if t == "ARG_IS_FALSE":
                    return arg is False or arg == False  # noqa: E712
                if t == "ARG_INSTANCE_OF":
                    return arg is not None and self.instance_of.isInstance(arg)
                if t == "ARG_EQUAL":
                    return arg == self.object
                if t == "ARG_NOT_EQUAL":
                    return arg != self.object
            if t == "CONDITION":
                support = jclass("com.solegram.messenger.plugins.python.PySupport")
                return bool(support.evalCondition(self.mvel_expression, param, self.object))
            if t == "OR":
                return any(f.matches(param) for f in self.or_filters)
        except Exception:
            return False
        return True


class HookFilter(enum.Enum):
    RESULT_IS_NULL = "RESULT_IS_NULL"
    RESULT_IS_TRUE = "RESULT_IS_TRUE"
    RESULT_IS_FALSE = "RESULT_IS_FALSE"
    RESULT_NOT_NULL = "RESULT_NOT_NULL"

    @property
    def filter_data(self):
        return HookFilterData(self.value)

    @staticmethod
    def ResultIsInstanceOf(clazz):
        return HookFilterData("RESULT_INSTANCE_OF", instance_of=clazz)

    @staticmethod
    def ResultEqual(value):
        return HookFilterData("RESULT_EQUAL", object=value)

    @staticmethod
    def ResultNotEqual(value):
        return HookFilterData("RESULT_NOT_EQUAL", object=value)

    @staticmethod
    def ArgumentIsNull(index: int):
        return HookFilterData("ARG_IS_NULL", arg_index=index)

    @staticmethod
    def ArgumentNotNull(index: int):
        return HookFilterData("ARG_NOT_NULL", arg_index=index)

    @staticmethod
    def ArgumentIsTrue(index: int):
        return HookFilterData("ARG_IS_TRUE", arg_index=index)

    @staticmethod
    def ArgumentIsFalse(index: int):
        return HookFilterData("ARG_IS_FALSE", arg_index=index)

    @staticmethod
    def ArgumentIsInstanceOf(index: int, clazz):
        return HookFilterData("ARG_INSTANCE_OF", arg_index=index, instance_of=clazz)

    @staticmethod
    def ArgumentEqual(index: int, value):
        return HookFilterData("ARG_EQUAL", arg_index=index, object=value)

    @staticmethod
    def ArgumentNotEqual(index: int, value):
        return HookFilterData("ARG_NOT_EQUAL", arg_index=index, object=value)

    @staticmethod
    def Condition(condition: str, object: Any = None):
        return HookFilterData("CONDITION", mvel_expression=condition, object=object)

    @staticmethod
    def Or(*filters):
        return HookFilterData("OR", or_filters=[_as_filter(f) for f in filters])


def _as_filter(f):
    if isinstance(f, HookFilter):
        return f.filter_data
    return f


def hook_filters(*filters):
    """Decorator: run the hook callback only when all filters match."""
    data = [_as_filter(f) for f in filters]

    def decorator(fn):
        fn._sg_filters = data
        return fn
    return decorator


def _passes(filters, param) -> bool:
    if not filters:
        return True
    for f in filters:
        if not _as_filter(f).matches(param):
            return False
    return True


# --------------------------------------------------------------------------- xposed hooks

class XposedHook(ABC):
    pass


class MethodHook(XposedHook):
    def before_hooked_method(self, param):
        pass

    def after_hooked_method(self, param):
        pass


class MethodReplacement(XposedHook, metaclass=abc.ABCMeta):
    @abstractmethod
    def replace_hooked_method(self, param) -> Any:
        ...


class BaseHook(MethodHook):
    """Functional hook used by hook_method(before=..., after=...)."""

    def __init__(self, before=None, after=None, before_filters=None, after_filters=None):
        self._before = before
        self._after = after
        self._before_filters = before_filters
        self._after_filters = after_filters

    def before_hooked_method(self, param):
        if self._before is not None and _passes(self._before_filters, param):
            self._before(param)

    def after_hooked_method(self, param):
        if self._after is not None and _passes(self._after_filters, param):
            self._after(param)


class _HookBridge:
    """Object called from Java (PyXposedHook) for every hooked invocation."""

    def __init__(self, plugin, hook):
        self.plugin = plugin
        self.hook = hook
        cls = type(hook)
        self._has_before = getattr(cls, "before_hooked_method", None) not in (None, MethodHook.before_hooked_method)
        self._has_after = getattr(cls, "after_hooked_method", None) not in (None, MethodHook.after_hooked_method)
        before = getattr(hook, "before_hooked_method", None)
        after = getattr(hook, "after_hooked_method", None)
        self._before_filters = getattr(before, "_sg_filters", None)
        self._after_filters = getattr(after, "_sg_filters", None)

    def _sg_before(self, param):
        if not self._has_before:
            return
        if self._before_filters and not _passes(self._before_filters, param):
            return
        try:
            self.hook.before_hooked_method(param)
        except Exception:
            self.plugin.logger.error("before_hooked_method failed:\n%s", traceback.format_exc())

    def _sg_after(self, param):
        if not self._has_after:
            return
        if self._after_filters and not _passes(self._after_filters, param):
            return
        try:
            self.hook.after_hooked_method(param)
        except Exception:
            self.plugin.logger.error("after_hooked_method failed:\n%s", traceback.format_exc())

    def _sg_replace(self, param):
        try:
            return self.hook.replace_hooked_method(param)
        except Exception:
            self.plugin.logger.error("replace_hooked_method failed:\n%s", traceback.format_exc())
            raise  # Java then runs the original method so the app keeps working


# --------------------------------------------------------------------------- plugin

class BasePlugin:
    id: str = None
    name: str = None
    description: str = ""
    author: str = ""
    min_version: str = None
    version: str = "1.0"
    icon: Any = None
    error_message: Any = None
    enabled: bool = False
    initialized: bool = False

    def __init__(self):
        self._sg_unhooks = []
        self._sg_menu_callbacks = {}
        self._sg_cleanups = []

    # lifecycle ---------------------------------------------------------------
    def on_plugin_load(self) -> None:
        pass

    def on_plugin_unload(self) -> None:
        pass

    def create_settings(self) -> List[Any]:
        return []

    def on_app_event(self, event_type: AppEvent):
        pass

    # hooks -------------------------------------------------------------------
    def pre_request_hook(self, request_name: str, account: int, request: Any) -> HookResult:
        return HookResult()

    def post_request_hook(self, request_name: str, account: int, response: Any, error: Any) -> HookResult:
        return HookResult()

    def on_update_hook(self, update_name: str, account: int, update: Any) -> HookResult:
        return HookResult()

    def on_updates_hook(self, container_name: str, account: int, updates: Any) -> HookResult:
        return HookResult()

    def on_send_message_hook(self, account: int, params: Any) -> HookResult:
        return HookResult()

    def add_hook(self, name: str, match_substring: bool = False, priority: int = 0):
        _PC.registerHook(self.id, str(name), bool(match_substring), int(priority))

    def add_on_send_message_hook(self, priority: int = 0):
        _PC.registerSendMessageHook(self.id, int(priority))

    def remove_hook(self, name: str):
        _PC.unregisterHook(self.id, str(name))

    # settings ----------------------------------------------------------------
    def get_setting(self, key: str, default: Any = None) -> Any:
        return _settings.get_setting(self.id, key, default)

    def set_setting(self, key: str, value: Any, reload_settings: bool = False):
        _settings.set_setting(self.id, key, value)
        if reload_settings:
            _PC.notifySettingsReload(self.id)

    def export_settings(self) -> dict:
        return _settings.get_all_settings(self.id)

    def import_settings(self, settings: dict, reload_settings: bool = True):
        _settings.set_all_settings(self.id, settings)
        if reload_settings:
            _PC.notifySettingsReload(self.id)

    # xposed ------------------------------------------------------------------
    def hook_method(self, method_or_constructor, xposed_hook=None, priority=None, *, before=None, after=None,
                    before_filters=None, after_filters=None):
        if method_or_constructor is None:
            raise PluginError("hook_method: method is None", self.id)
        hook = xposed_hook if xposed_hook is not None else BaseHook(before, after, before_filters, after_filters)
        bridge = _HookBridge(self, hook)
        prio = 50 if priority is None else int(priority)
        try:
            method_or_constructor.setAccessible(True)
        except Exception:
            pass
        if isinstance(hook, MethodReplacement) or (not hasattr(hook, "before_hooked_method") and hasattr(hook, "replace_hooked_method")):
            java_hook = _PyReplacement(self.id, bridge, prio)
        else:
            java_hook = _PyXposedHook(self.id, bridge, prio)
        try:
            unhook = _xposed().hookMethod(method_or_constructor, java_hook)
        except PluginError:
            raise
        except Exception as e:
            self.logger.error("hook_method failed for %s: %s", method_or_constructor, e)
            return None
        if unhook is not None:
            self.__dict__.setdefault("_sg_unhooks", []).append(unhook)
        return unhook

    def hook_all_methods(self, hook_class, method_name: str, xposed_hook=None, priority=None):
        if hook_class is None:
            return None
        result = []
        for m in hook_class.getDeclaredMethods():
            if m.getName() == method_name:
                u = self.hook_method(m, xposed_hook, priority)
                if u is not None:
                    result.append(u)
        return result

    def hook_all_constructors(self, hook_class, xposed_hook=None, priority=None):
        if hook_class is None:
            return None
        result = []
        for c in hook_class.getDeclaredConstructors():
            u = self.hook_method(c, xposed_hook, priority)
            if u is not None:
                result.append(u)
        return result

    def unhook_method(self, unhook):
        if unhook is None:
            return
        try:
            unhook.unhook()
        except Exception as e:
            self.logger.warning("unhook failed: %s", e)
        try:
            self.__dict__.setdefault("_sg_unhooks", []).remove(unhook)
        except ValueError:
            pass

    # menus -------------------------------------------------------------------
    def add_menu_item(self, menu_item_data: MenuItemData) -> Optional[str]:
        d = menu_item_data
        menu_type = d.menu_type.value if isinstance(d.menu_type, MenuItemType) else str(d.menu_type)
        item_id = _PC.addMenuItem(self.id, d.item_id, menu_type, str(d.text), d.subtext, d.icon, d.condition, int(d.priority or 0))
        self.__dict__.setdefault("_sg_menu_callbacks", {})[str(item_id)] = d.on_click
        return str(item_id)

    def remove_menu_item(self, item_id: str) -> bool:
        self.__dict__.setdefault("_sg_menu_callbacks", {}).pop(item_id, None)
        return bool(_PC.removeMenuItem(self.id, item_id))

    # misc --------------------------------------------------------------------
    @property
    def logger(self):
        custom = self.__dict__.get("_sg_custom_logger")
        return custom if custom is not None else _plugin_logger(self.id or "unknown")

    @logger.setter
    def logger(self, value):
        self.__dict__["_sg_custom_logger"] = value

    def log(self, message: str):
        _plugin_logger(self.id or "unknown").log(3, "%s", message, stacklevel=2)

    def client(self, account: Optional[int] = None):
        import client_utils
        if account is None:
            account = client_utils.get_hook_account()
        return client_utils.get_client(account)

    def add_cleanup(self, fn: Callable[[], None]):
        """Solegram extension: callable invoked when the plugin is unloaded."""
        self.__dict__.setdefault("_sg_cleanups", []).append(fn)

    def getName(self):
        return self.name

    def getId(self):
        return self.id

    # internal -----------------------------------------------------------------
    def _sg_release(self):
        for unhook in list(getattr(self, "_sg_unhooks", [])):
            try:
                unhook.unhook()
            except Exception:
                pass
        self._sg_unhooks = []
        self._sg_menu_callbacks = {}
        for fn in list(getattr(self, "_sg_cleanups", [])):
            try:
                fn()
            except Exception:
                pass
        self._sg_cleanups = []
