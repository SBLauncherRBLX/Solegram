"""Reflection helpers for plugins (exteraGram SDK compatible)."""
from typing import Any

from java import jclass

from log_utils import get_logger

_Class = jclass("java.lang.Class")
_XposedHelpers = jclass("de.robv.android.xposed.XposedHelpers")
_logger = get_logger("hook_utils")

JavaClass = Any
JavaObject = Any


def find_class(class_name: str):
    """Returns the java.lang.Class for a fully qualified name, or None."""
    try:
        loader = jclass("org.telegram.messenger.ApplicationLoader").applicationContext.getClassLoader()
        return _Class.forName(class_name, False, loader)
    except Exception:
        try:
            return _Class.forName(class_name)
        except Exception:
            return None


def _as_class(clazz):
    if clazz is None:
        return None
    if isinstance(clazz, _Class):
        return clazz
    try:
        return clazz.getClass() if not hasattr(clazz, "getDeclaredField") else clazz
    except Exception:
        return None


def _find_field(cls, name):
    c = cls
    while c is not None:
        try:
            f = c.getDeclaredField(name)
            f.setAccessible(True)
            return f
        except Exception:
            c = c.getSuperclass()
    return None


def get_private_field(obj, field_name: str):
    try:
        f = _find_field(obj.getClass(), field_name)
        return f.get(obj) if f is not None else None
    except Exception as e:
        _logger.debug("get_private_field(%s) failed: %s", field_name, e)
        return None


def set_private_field(obj, field_name: str, new_value) -> bool:
    try:
        f = _find_field(obj.getClass(), field_name)
        if f is None:
            return False
        _XposedHelpers.setFieldValue(f, obj, new_value)
        return True
    except Exception as e:
        _logger.debug("set_private_field(%s) failed: %s", field_name, e)
        return False


def get_static_private_field(clazz, field_name: str):
    try:
        f = _find_field(_as_class(clazz), field_name)
        return f.get(None) if f is not None else None
    except Exception as e:
        _logger.debug("get_static_private_field(%s) failed: %s", field_name, e)
        return None


def set_static_private_field(clazz, field_name: str, new_value) -> bool:
    try:
        f = _find_field(_as_class(clazz), field_name)
        if f is None:
            return False
        _XposedHelpers.setFieldValue(f, None, new_value)
        return True
    except Exception as e:
        _logger.debug("set_static_private_field(%s) failed: %s", field_name, e)
        return False
