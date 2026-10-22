"""Managed Java subclasses for plugins (exteraGram class proxy API, DexMaker based).

    from extera_utils.classes import Base, java_subclass, joverload, jfield
    from java.util import ArrayList

    @java_subclass(ArrayList)
    class CountingList(Base):
        added_count = jfield("int", default=0)

        @joverload("add", ["java.lang.Object"])
        def add_item(self, value):
            self.added_count += 1
            return super().add_item(value)

Supported: java_subclass/bind (base class + interfaces), joverride/joverload, jMVELoverride, jfield with
jgetmethod/jsetmethod overriding existing accessors, jpreconstructor/jconstructor/__init__/on_post_init,
new_instance/new_java_instance/from_java/java_class, jrelease/on_release, PyObj.
Methods that do not exist on the base class or its interfaces (jmethod, jMVELmethod, new accessors) are not
generated on the Java class; they stay callable from Python.
"""
import inspect
import threading
import traceback
import weakref
from typing import Any, List, Optional, Sequence

from java import jarray, jclass

import plugin_registry
from log_utils import get_logger

__all__ = [
    "Base", "java_subclass", "joverride", "joverload", "jmethod", "jMVELmethod", "jmvelmethod", "jMVELoverride",
    "jmveloverride", "jclassbuilder", "jfield", "jgetmethod", "jsetmethod", "jconstructor", "jpreconstructor", "PyObj",
]

_Proxy = jclass("com.solegram.messenger.plugins.python.PyClassProxy")
_Spec = jclass("com.solegram.messenger.plugins.python.PyClassProxy$Spec")
_JClass = jclass("java.lang.Class")
_JString = jclass("java.lang.String")
_JObject = jclass("java.lang.Object")
_JMethod = jclass("java.lang.reflect.Method")
_ArrayList = jclass("java.util.ArrayList")
_Modifier = jclass("java.lang.reflect.Modifier")

_logger = get_logger("extera_utils.classes")
_lock = threading.RLock()


# --------------------------------------------------------------------------- declarations

def _type_name(t):
    if t is None:
        return None
    if isinstance(t, str):
        return t
    try:
        return str(_as_class(t).getName())
    except Exception:
        return str(t)


def _type_names(types):
    if types is None:
        return None
    return [_type_name(t) for t in types]


class _Override:
    def __init__(self, java_name, arg_types, fn):
        self.java_name = java_name
        self.arg_types = _type_names(arg_types)
        self.fn = fn


def joverride(name: Optional[str] = None, arg_types: Optional[Sequence[Any]] = None):
    """Overrides a parent Java method; the Java name defaults to the Python function name."""
    def decorator(fn):
        fn._sg_override = _Override(name or fn.__name__, arg_types, fn)
        return fn
    if callable(name) and arg_types is None:
        fn, name = name, None
        return decorator(fn)
    return decorator


def joverload(name: str, arg_types: Sequence[Any]):
    return joverride(name, arg_types)


def jmethod(*spec, **kwargs):
    """Declares a new Java method. Generated only when it exists on an implemented interface."""
    name = spec[0] if len(spec) == 3 else kwargs.get("name")

    def decorator(fn):
        fn._sg_override = _Override(name or fn.__name__, None, fn)
        fn._sg_optional = True
        return fn
    if len(spec) == 1 and callable(spec[0]):
        return decorator(spec[0])
    return decorator


class jMVELoverride:
    def __init__(self, code: str = "", arguments: Optional[List] = None, name: Optional[str] = None, **kwargs):
        self.code = code
        self.arguments = None if arguments is None else list(arguments)
        self.name = name
        self.optional = False

    def __set_name__(self, owner, name):
        if self.name is None:
            self.name = name


class jMVELmethod(jMVELoverride):
    def __init__(self, return_type=None, arguments=None, code: str = "", name: Optional[str] = None, **kwargs):
        super().__init__(code=code, arguments=arguments, name=name)
        self.return_type = return_type
        self.optional = True


jmveloverride = jMVELoverride
jmvelmethod = jMVELmethod


class jgetmethod:
    def __init__(self, name: str):
        self.name = name


class jsetmethod:
    def __init__(self, name: str):
        self.name = name


class jfield:
    """A field stored with the Java instance; readable/writable as a normal attribute of the peer."""

    def __init__(self, type_name: Any = "java.lang.Object", default: Any = None, methods: Optional[List] = None, **kwargs):
        self.type_name = _type_name(type_name)
        self.default = default
        self.methods = list(methods or [])
        self.name = None

    def __set_name__(self, owner, name):
        self.name = name

    def __get__(self, obj, owner=None):
        if obj is None:
            return self
        handler = obj.__dict__.get("_sg_handler")
        if handler is None:
            return obj.__dict__.get("_sg_field_" + self.name, self.default)
        value = handler.getField(self.name)
        return self.default if value is None else value

    def __set__(self, obj, value):
        handler = obj.__dict__.get("_sg_handler")
        if handler is None:
            obj.__dict__["_sg_field_" + self.name] = value
        else:
            handler.setField(self.name, value)


def jconstructor(arg_types: Optional[Sequence[Any]] = None):
    def decorator(fn):
        fn._sg_ctor = _type_names(arg_types)
        return fn
    return decorator


def jpreconstructor(arg_types: Optional[Sequence[Any]] = None):
    def decorator(fn):
        fn._sg_prector = _type_names(arg_types)
        return fn
    return decorator


def jclassbuilder(*args, **kwargs):
    def decorator(fn):
        fn._sg_classbuilder = True
        return fn
    return decorator


# --------------------------------------------------------------------------- PyObj

class PyObj:
    """Carries an arbitrary Python object through Java APIs (wrapped as a Java object)."""

    _registry = {}

    @classmethod
    def create(cls, value):
        holder = _ArrayList()
        holder.add(None)
        cls._registry[id(holder)] = (holder, value)
        return holder

    @classmethod
    def get(cls, java_obj, default=None):
        entry = cls._registry.get(id(java_obj))
        return entry[1] if entry is not None else default


# --------------------------------------------------------------------------- base class

class _ClassInfo:
    def __init__(self, base, interfaces, java_class, spec, owner):
        self.base = base
        self.interfaces = interfaces
        self.java_class = java_class
        self.spec = spec
        self.owner = owner
        self.instances = weakref.WeakSet()


def _matches(types_spec, java_args_types):
    if types_spec is None:
        return True
    return list(types_spec) == list(java_args_types)


class Base:
    """Base class of Python peers of generated Java subclasses."""

    _sg_info: _ClassInfo = None

    # construction ------------------------------------------------------------
    @classmethod
    def bind(cls, base_class, *interfaces, methods=None, constructors=None, custom_name=None):
        _bind(cls, base_class, interfaces)
        return cls

    @classmethod
    def java_class(cls):
        return cls._info().java_class

    @classmethod
    def _info(cls):
        info = cls.__dict__.get("_sg_info")
        if info is None:
            raise TypeError(f"{cls.__name__} is not bound to a Java class, use @java_subclass(...)")
        return info

    @classmethod
    def new_java_instance(cls, *args):
        return cls.new_instance(*args).java

    @classmethod
    def new_instance(cls, *args, init_args=None):
        info = cls._info()
        java_args = list(args)
        for fn in _members(cls, "_sg_prector"):
            if fn._sg_prector is None or len(fn._sg_prector) == len(java_args):
                replaced = fn(cls, *java_args)
                if replaced is not None:
                    java_args = list(replaced) if isinstance(replaced, (list, tuple)) else [replaced]
                break
        handler = info.spec.newHandler()
        java_obj = _Proxy.newInstance(info.java_class, jarray(_JObject)(java_args), handler)
        peer = cls.__new__(cls)
        peer.__dict__["_sg_java"] = java_obj
        peer.__dict__["_sg_handler"] = handler
        for name, field in _fields(cls):
            if field.default is not None and handler.getField(name) is None:
                handler.setField(name, field.default)
        handler.setPeer(peer)
        info.instances.add(peer)
        try:
            py_args = list(init_args) if init_args is not None else java_args
            init = cls.__init__
            if init is not object.__init__ and init is not Base.__init__:
                try:
                    peer.__init__(*py_args)
                except TypeError:
                    if init_args is not None:
                        raise
                    peer.__init__()
            for fn in _members(cls, "_sg_ctor"):
                if fn._sg_ctor is None or len(fn._sg_ctor) == len(java_args):
                    fn(peer, *java_args)
                    break
            post = getattr(peer, "on_post_init", None)
            if post is not None:
                try:
                    post(*java_args)
                except TypeError:
                    post()
        except Exception:
            handler.release()
            raise
        return peer

    def __init__(self, *args, **kwargs):
        pass

    @classmethod
    def from_java(cls, java_obj):
        handler = _Proxy.handlerOf(java_obj)
        if handler is None or handler.isReleased():
            return None
        peer = handler.getPeer()
        if peer is not None:
            return peer
        peer = cls.__new__(cls)
        peer.__dict__["_sg_java"] = java_obj
        peer.__dict__["_sg_handler"] = handler
        handler.setPeer(peer)
        return peer

    # instance ------------------------------------------------------------------
    @property
    def java(self):
        return self.__dict__.get("_sg_java")

    @property
    def this(self):
        return self.java

    def jrelease(self):
        handler = self.__dict__.get("_sg_handler")
        if handler is None or handler.isReleased():
            return
        try:
            self.on_release()
        except Exception:
            _logger.error("on_release failed:\n%s", traceback.format_exc())
        handler.release()

    def on_release(self):
        pass

    def __getattr__(self, name):
        if name.startswith("_sg_") or name.startswith("__"):
            raise AttributeError(name)
        java_obj = self.__dict__.get("_sg_java")
        if java_obj is None:
            raise AttributeError(name)
        return getattr(java_obj, name)


def _members(cls, marker):
    seen = set()
    for klass in cls.__mro__:
        for name, value in klass.__dict__.items():
            fn = value.__func__ if isinstance(value, (classmethod, staticmethod)) else value
            if name not in seen and hasattr(fn, marker):
                seen.add(name)
                yield fn


def _fields(cls):
    seen = set()
    for klass in cls.__mro__:
        for name, value in klass.__dict__.items():
            if isinstance(value, jfield) and name not in seen:
                seen.add(name)
                yield name, value


def _super_caller(method):
    def call_super(self, *args):
        return _Proxy.callSuper(self.java, method, jarray(_JObject)(list(args)))
    return call_super


def _bind(cls, base_class, interfaces):
    base = base_class if base_class is not None else _JObject
    base = _as_class(base)
    ifaces = [_as_class(i) for i in interfaces]
    j_ifaces = jarray(_JClass)(ifaces)
    spec = _Spec()
    targeted = {}
    super_methods = {}

    def find(name, arg_types, optional):
        try:
            return _Proxy.findMethod(base, j_ifaces, name, None if arg_types is None else jarray(_JString)(arg_types))
        except Exception as e:
            if optional:
                _logger.warning("%s.%s: %s (kept as a Python-only method)", cls.__name__, name, e)
                return None
            raise TypeError(f"{cls.__name__}: {e}") from None

    for attr, value in list(cls.__dict__.items()):
        fn = value.__func__ if isinstance(value, (classmethod, staticmethod)) else value
        ov = getattr(fn, "_sg_override", None)
        if ov is not None:
            m = find(ov.java_name, ov.arg_types, getattr(fn, "_sg_optional", False))
            if m is not None:
                spec.addPython(m, fn)
                targeted[str(_Proxy.key(m))] = m
                super_methods[attr] = m
        elif isinstance(value, jMVELoverride):
            arg_types = None if value.arguments is None else [_type_name(t) for _, t in value.arguments]
            m = find(value.name, arg_types, value.optional)
            if m is not None:
                names = jarray(_JString)([n for n, _ in (value.arguments or [])])
                spec.addMvel(m, value.code, names)
                targeted[str(_Proxy.key(m))] = m
        elif isinstance(value, jfield):
            spec.addField(attr, value.default)
            for acc in value.methods:
                if isinstance(acc, jgetmethod):
                    m = find(acc.name, [], True)
                    if m is not None:
                        spec.addGetter(m, attr)
                        targeted[str(_Proxy.key(m))] = m
                elif isinstance(acc, jsetmethod):
                    m = find(acc.name, [value.type_name] if value.type_name else None, True)
                    if m is not None:
                        spec.addSetter(m, attr)
                        targeted[str(_Proxy.key(m))] = m
        elif getattr(fn, "_sg_classbuilder", False):
            _logger.warning("%s: jclassbuilder is not supported by Solegram and was ignored", cls.__name__)

    methods = list(targeted.values())
    for m in _Proxy.overridableMethods(base, j_ifaces):
        if _Modifier.isAbstract(m.getModifiers()) and str(_Proxy.key(m)) not in targeted:
            methods.append(m)
            spec.addMethod(m)
    java_class = _Proxy.buildClass(base, j_ifaces, jarray(_JMethod)(methods))

    # super().<python name>(...) calls the parent Java implementation
    mixin_ns = {name: _super_caller(m) for name, m in super_methods.items()}
    if mixin_ns:
        mixin = type(f"_{cls.__name__}_JavaSuper", cls.__bases__, mixin_ns)
        cls.__bases__ = (mixin,)

    owner = plugin_registry.owner_id()
    info = _ClassInfo(base, ifaces, java_class, spec, owner)
    cls._sg_info = info
    if owner:
        def _release_all():
            for peer in list(info.instances):
                try:
                    peer.jrelease()
                except Exception:
                    pass
        plugin_registry.add_cleanup(owner, _release_all)


def _as_class(c):
    """java.lang.Class for a Class object, a class name or a Chaquopy class (jclass / `from java... import`)."""
    if isinstance(c, _JClass):
        return c
    if isinstance(c, str):
        return _Proxy.typeOf(c, None)
    for attr in ("class_", "__javaclass__"):
        try:
            v = getattr(c, attr)
        except Exception:
            continue
        if isinstance(v, _JClass):
            return v
        if isinstance(v, str):
            return _Proxy.typeOf(v.replace("/", "."), None)
    name = getattr(c, "_jname", None) or f"{getattr(c, '__module__', '')}.{getattr(c, '__name__', '')}"
    return _Proxy.typeOf(name, None)


def java_subclass(base_class, *interfaces, methods=None, constructors=None, custom_name=None):
    def decorator(cls):
        if not issubclass(cls, Base):
            ns = {k: v for k, v in cls.__dict__.items() if k not in ("__dict__", "__weakref__")}
            cls = type(cls.__name__, (Base,), ns)
        _bind(cls, base_class, interfaces)
        return cls
    return decorator
