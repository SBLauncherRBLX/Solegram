"""Plugin settings rows (exteraGram plugin SDK compatible).

create_settings() returns a list of these dataclasses; the runtime converts them into
com.solegram.messenger.plugins.PluginSetting objects rendered by PluginSettingsActivity.
"""
import traceback
import weakref
from dataclasses import dataclass, field
from typing import Any, Callable, List, Optional

from java import dynamic_proxy, jarray, jclass

import plugin_registry
from log_utils import get_logger

__all__ = [
    "Header", "Divider", "Switch", "Selector", "Input", "Text", "EditText", "Custom",
    "SimpleSettingFactory",
]

_PluginSetting = jclass("com.solegram.messenger.plugins.PluginSetting")
_ValueCallback = jclass("com.solegram.messenger.plugins.PluginSetting$ValueCallback")
_ViewCallback = jclass("com.solegram.messenger.plugins.PluginSetting$ViewCallback")
_LongClickCallback = jclass("com.solegram.messenger.plugins.PluginSetting$LongClickCallback")
_SubPage = jclass("com.solegram.messenger.plugins.PluginSetting$SubPage")
_ViewFactory = jclass("com.solegram.messenger.plugins.PluginSetting$ViewFactory")
_ArrayList = jclass("java.util.ArrayList")
_String = jclass("java.lang.String")

_logger = get_logger("ui.settings")


# --------------------------------------------------------------------------- rows

@dataclass
class Header:
    text: str
    type: str = field(default="header", init=False)


@dataclass
class Divider:
    text: Optional[str] = None
    type: str = field(default="divider", init=False)


@dataclass
class Switch:
    key: str
    text: str
    default: bool
    subtext: Optional[str] = None
    icon: Optional[str] = None
    on_change: Optional[Callable[[bool], None]] = None
    on_long_click: Optional[Callable[[Any], None]] = None
    link_alias: Optional[str] = None
    type: str = field(default="switch", init=False)


@dataclass
class Selector:
    key: str
    text: str
    default: int
    items: List[str]
    icon: Optional[str] = None
    on_change: Optional[Callable[[int], None]] = None
    on_long_click: Optional[Callable[[Any], None]] = None
    link_alias: Optional[str] = None
    type: str = field(default="selector", init=False)


@dataclass
class Input:
    key: str
    text: str
    default: Optional[str] = ""
    subtext: Optional[str] = None
    icon: Optional[str] = None
    on_change: Optional[Callable[[str], None]] = None
    on_long_click: Optional[Callable[[Any], None]] = None
    link_alias: Optional[str] = None
    type: str = field(default="input", init=False)


@dataclass
class Text:
    text: str
    icon: Optional[str] = None
    accent: bool = False
    red: bool = False
    on_click: Optional[Callable[[Any], None]] = None
    create_sub_fragment: Optional[Callable[[], List[Any]]] = None
    on_long_click: Optional[Callable[[Any], None]] = None
    link_alias: Optional[str] = None
    subtext: Optional[str] = None
    type: str = field(default="text", init=False)


@dataclass
class EditText:
    key: str
    hint: str
    default: Optional[str] = ""
    multiline: bool = False
    max_length: Optional[int] = None
    mask: Optional[str] = None
    on_change: Optional[Callable[[str], None]] = None
    type: str = field(default="edit_text", init=False)


@dataclass
class Custom:
    item: Any = None
    view: Any = None
    factory: Any = None
    factory_args: Any = None
    on_click: Optional[Callable[[Any], None]] = None
    on_long_click: Optional[Callable[[Any], None]] = None
    create_sub_fragment: Optional[Callable[[], List[Any]]] = None
    link_alias: Optional[str] = None
    text: Optional[str] = None
    type: str = field(default="custom", init=False)


# --------------------------------------------------------------------------- java callbacks

def _report(plugin, what):
    log = plugin.logger if plugin is not None else _logger
    log.error("%s failed:\n%s", what, traceback.format_exc())


def _unbox(value):
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    for name, conv in (("booleanValue", bool), ("longValue", int), ("doubleValue", float)):
        fn = getattr(value, name, None)
        if fn is not None:
            try:
                return conv(fn())
            except Exception:
                pass
    return str(value)


class _ValueCb(dynamic_proxy(_ValueCallback)):
    def __init__(self, plugin, fn, kind):
        super().__init__()
        self.plugin = plugin
        self.fn = fn
        self.kind = kind

    def onChange(self, value):
        try:
            value = _unbox(value)
            if self.kind is not None and value is not None:
                value = self.kind(value)
            self.fn(value)
        except Exception:
            _report(self.plugin, "on_change")


class _ViewCb(dynamic_proxy(_ViewCallback)):
    def __init__(self, plugin, fn):
        super().__init__()
        self.plugin = plugin
        self.fn = fn

    def onClick(self, view):
        try:
            self.fn(view)
        except Exception:
            _report(self.plugin, "on_click")


class _LongClickCb(dynamic_proxy(_LongClickCallback)):
    def __init__(self, plugin, fn):
        super().__init__()
        self.plugin = plugin
        self.fn = fn

    def onLongClick(self, view):
        try:
            result = self.fn(view)
            return True if result is None else bool(result)
        except Exception:
            _report(self.plugin, "on_long_click")
            return False


class _SubPageCb(dynamic_proxy(_SubPage)):
    def __init__(self, plugin, fn):
        super().__init__()
        self.plugin = plugin
        self.fn = fn

    def create(self):
        try:
            return to_java_list(self.fn(), self.plugin)
        except Exception:
            _report(self.plugin, "create_sub_fragment")
            return None


# --------------------------------------------------------------------------- custom factories

class _FactoryProxy(dynamic_proxy(_ViewFactory)):
    def __init__(self, owner):
        super().__init__()
        self._owner = weakref.ref(owner)
        self._items = {}

    @property
    def owner(self):
        return self._owner()

    def createView(self, context, list_view, account, class_guid, resources_provider):
        f = self.owner
        if f is None:
            return None
        try:
            return f.create_view(context, list_view, account, class_guid, resources_provider)
        except Exception:
            _report(f.plugin, "SimpleSettingFactory.create_view")
            return None

    def bindView(self, view, item, divider, adapter, list_view):
        f = self.owner
        if f is None:
            return
        try:
            self._items[view.hashCode()] = item
        except Exception:
            pass
        if f.bind_view is None:
            return
        try:
            f.bind_view(view, item, bool(divider), adapter, list_view)
        except Exception:
            _report(f.plugin, "SimpleSettingFactory.bind_view")

    def isClickable(self):
        f = self.owner
        return bool(f is not None and (f.is_clickable or f.on_click is not None))

    def onClick(self, view):
        f = self.owner
        if f is None or f.on_click is None:
            return
        try:
            f.on_click(f.plugin, self._items.get(view.hashCode()), view)
        except Exception:
            _report(f.plugin, "SimpleSettingFactory.on_click")

    def onLongClick(self, view):
        f = self.owner
        if f is None or f.on_long_click is None:
            return False
        try:
            return bool(f.on_long_click(f.plugin, self._items.get(view.hashCode()), view))
        except Exception:
            _report(f.plugin, "SimpleSettingFactory.on_long_click")
            return False


class _FactoryInstance:
    def __init__(self, java):
        self.java = java


class SimpleSettingFactory:
    """High level custom settings row: create_view builds the row once, bind_view fills it."""

    def __init__(self, create_view, bind_view=None, is_clickable=False, is_shadow=False, create_item=None,
                 on_click=None, on_long_click=None, attached_view=None, equals=None, content_equals=None):
        self.create_view = create_view
        self.bind_view = bind_view
        self.is_clickable = bool(is_clickable)
        self.is_shadow = bool(is_shadow)
        self.create_item = create_item
        self.on_click = on_click
        self.on_long_click = on_long_click
        self.attached_view = attached_view
        self.equals = equals
        self.content_equals = content_equals
        self.plugin_id = plugin_registry.owner_id()
        self._proxy = _FactoryProxy(self)
        self.instance = _FactoryInstance(self._proxy)
        _factories[id(self._proxy)] = self
        if self.plugin_id:
            plugin_registry.add_cleanup(self.plugin_id, self.release)

    @property
    def plugin(self):
        return plugin_registry.get_plugin(self.plugin_id) if self.plugin_id else None

    def __call__(self, *args, **kwargs):
        if args:
            kwargs["factory_args"] = list(args)
        return Custom(factory=self.instance.java, **kwargs)

    def release(self):
        _factories.pop(id(self._proxy), None)
        if self.plugin_id:
            plugin_registry.remove_cleanup(self.plugin_id, self.release)


_factories = {}


def _factory_of(obj):
    if isinstance(obj, SimpleSettingFactory):
        return obj
    if isinstance(obj, _FactoryInstance):
        obj = obj.java
    return _factories.get(id(obj))


# --------------------------------------------------------------------------- conversion

def _str(v):
    return None if v is None else str(v)


def to_java(item, plugin=None):
    """Converts one settings dataclass into a PluginSetting (Java)."""
    if item is None:
        return None
    if isinstance(item, _PluginSetting):
        return item
    t = getattr(item, "type", None)
    if t is None:
        cls = type(item).__name__.lower()
        t = {"edittext": "edit_text"}.get(cls, cls)
    js = _PluginSetting()
    js.type = t
    js.key = _str(getattr(item, "key", None))
    js.text = _str(getattr(item, "text", None))
    js.subtext = _str(getattr(item, "subtext", None))
    js.icon = _str(getattr(item, "icon", None))
    js.linkAlias = _str(getattr(item, "link_alias", None))
    js.accent = bool(getattr(item, "accent", False))
    js.red = bool(getattr(item, "red", False))

    default = getattr(item, "default", None)
    if t == "switch":
        js.defaultValue = bool(default)
        kind = bool
    elif t == "selector":
        js.defaultValue = int(default or 0)
        items = [str(x) for x in (getattr(item, "items", None) or [])]
        js.items = jarray(_String)(items)
        kind = int
    elif t in ("input", "edit_text"):
        js.defaultValue = "" if default is None else str(default)
        kind = str
    else:
        kind = None
    if t == "edit_text":
        js.hint = _str(getattr(item, "hint", None))
        js.multiline = bool(getattr(item, "multiline", False))
        js.maxLength = int(getattr(item, "max_length", None) or 0)
        js.mask = _str(getattr(item, "mask", None))

    on_change = getattr(item, "on_change", None)
    if on_change is not None:
        js.onChange = _ValueCb(plugin, on_change, kind)
    on_click = getattr(item, "on_click", None)
    if on_click is not None:
        js.onClick = _ViewCb(plugin, on_click)
    on_long_click = getattr(item, "on_long_click", None)
    if on_long_click is not None:
        js.onLongClick = _LongClickCb(plugin, on_long_click)
    sub = getattr(item, "create_sub_fragment", None)
    if sub is not None:
        js.subPage = _SubPageCb(plugin, sub)

    if t == "custom":
        js.uitem = getattr(item, "item", None)
        js.view = getattr(item, "view", None)
        factory = getattr(item, "factory", None)
        if factory is not None:
            f = _factory_of(factory)
            if f is not None:
                if f.plugin_id is None and plugin is not None:
                    f.plugin_id = plugin.id
                js.factory = f._proxy
                js.shadow = f.is_shadow
            elif isinstance(factory, _ViewFactory):
                js.factory = factory
            else:
                _logger.warning("Custom(factory=%r) is not supported, use SimpleSettingFactory", factory)
        args = getattr(item, "factory_args", None)
        if args is not None:
            try:
                if isinstance(args, (list, tuple)):
                    jlist = _ArrayList()
                    for a in args:
                        jlist.add(a)
                    js.factoryArgs = jlist
                else:
                    js.factoryArgs = args
            except Exception:
                js.factoryArgs = None
    return js


def to_java_list(items, plugin=None):
    result = _ArrayList()
    for item in items or []:
        try:
            js = to_java(item, plugin)
        except Exception:
            _report(plugin, f"settings item {item!r}")
            continue
        if js is not None:
            result.add(js)
    return result
