"""Plugin logger (exteraGram plugin SDK compatible).

Every plugin gets ``self.logger``: it writes to logcat with the tag ``plugin.<id>``, supports levels,
tracebacks, object dumps, child loggers, timers and handlers.
"""
import enum
import inspect
import os
import threading
import time
import traceback as _tb
from contextlib import contextmanager

try:
    from android.util import Log as _Log
except Exception:  # pragma: no cover - desktop stubs
    _Log = None

__all__ = ["LogLevel", "LogRecord", "Logger", "get_logger", "dump"]


class LogLevel(enum.IntEnum):
    VERBOSE = 2
    DEBUG = 3
    INFO = 4
    WARNING = 5
    ERROR = 6
    OFF = 100

    @classmethod
    def parse(cls, value):
        if value is None or isinstance(value, LogLevel):
            return value
        if isinstance(value, int):
            for lvl in cls:
                if lvl.value == value:
                    return lvl
            return cls.DEBUG
        name = str(value).strip().upper()
        if name == "WARN":
            name = "WARNING"
        return cls[name]


class LogRecord:
    __slots__ = ("level", "tag", "message", "location", "traceback", "created", "text")

    def __init__(self, level, tag, message, location, traceback, created, text):
        self.level = level
        self.tag = tag
        self.message = message
        self.location = location
        self.traceback = traceback
        self.created = created
        self.text = text

    def __repr__(self):
        return f"LogRecord({self.level.name}, {self.tag!r}, {self.message!r})"


_dev_mode = False


def _set_dev_mode(enabled):
    global _dev_mode
    _dev_mode = bool(enabled)


_MAX_CHUNK = 3800
_handler_guard = threading.local()


def _write(level, tag, text):
    if _Log is None:
        print(f"{level.name[0]} {tag}: {text}")
        return
    priority = int(level)
    if priority > 7:
        return
    # logcat truncates entries at ~4 KB: split on line boundaries
    chunks = []
    current = ""
    for line in text.split("\n"):
        if len(current) + len(line) + 1 > _MAX_CHUNK and current:
            chunks.append(current)
            current = ""
        while len(line) > _MAX_CHUNK:
            chunks.append(line[:_MAX_CHUNK])
            line = line[_MAX_CHUNK:]
        current = line if not current else current + "\n" + line
    if current or not chunks:
        chunks.append(current)
    for chunk in chunks:
        try:
            _Log.println(priority, tag[:120], chunk)
        except Exception:
            pass


class Logger:
    def __init__(self, tag, parent=None):
        self.tag = tag
        self._parent = parent
        self._level = None
        self._handlers = []
        self._children = {}

    # level handling -------------------------------------------------------
    @property
    def level(self):
        return self._level

    @level.setter
    def level(self, value):
        self._level = LogLevel.parse(value)

    @property
    def effective_level(self):
        logger = self
        while logger is not None:
            if logger._level is not None:
                return logger._level
            logger = logger._parent
        return LogLevel.DEBUG if _dev_mode else LogLevel.INFO

    def is_enabled_for(self, level):
        return LogLevel.parse(level) >= self.effective_level

    # logging --------------------------------------------------------------
    def _location(self, stacklevel):
        try:
            frame = inspect.currentframe()
            for _ in range(stacklevel + 1):
                if frame is None:
                    break
                frame = frame.f_back
            if frame is None:
                return None
            return f"{os.path.basename(frame.f_code.co_filename)}:{frame.f_code.co_name}:{frame.f_lineno}"
        except Exception:
            return None

    def log(self, level, msg, *args, exc_info=None, stacklevel=1):
        level = LogLevel.parse(level)
        if level == LogLevel.OFF or not self.is_enabled_for(level):
            return
        try:
            message = str(msg) % args if args else str(msg)
        except Exception:
            message = f"{msg} {args}"
        tb = None
        if exc_info:
            if isinstance(exc_info, BaseException):
                tb = "".join(_tb.format_exception(type(exc_info), exc_info, exc_info.__traceback__))
            else:
                tb = _tb.format_exc()
        location = self._location(stacklevel)
        text = f"[{location}] {message}" if location else message
        if tb:
            text += "\n" + tb.rstrip()
        _write(level, self.tag, text)
        record = LogRecord(level, self.tag, message, location, tb, time.time(), text)
        self._dispatch(record)

    def _dispatch(self, record):
        if getattr(_handler_guard, "active", False):
            return
        logger = self
        _handler_guard.active = True
        try:
            while logger is not None:
                for handler in list(logger._handlers):
                    try:
                        handler(record)
                    except Exception:
                        _write(LogLevel.ERROR, "pysdk", "log handler failed:\n" + _tb.format_exc())
                logger = logger._parent
        finally:
            _handler_guard.active = False

    def verbose(self, msg, *args, exc_info=None, stacklevel=1):
        self.log(LogLevel.VERBOSE, msg, *args, exc_info=exc_info, stacklevel=stacklevel + 1)

    def debug(self, msg, *args, exc_info=None, stacklevel=1):
        self.log(LogLevel.DEBUG, msg, *args, exc_info=exc_info, stacklevel=stacklevel + 1)

    def info(self, msg, *args, exc_info=None, stacklevel=1):
        self.log(LogLevel.INFO, msg, *args, exc_info=exc_info, stacklevel=stacklevel + 1)

    def warning(self, msg, *args, exc_info=None, stacklevel=1):
        self.log(LogLevel.WARNING, msg, *args, exc_info=exc_info, stacklevel=stacklevel + 1)

    warn = warning

    def error(self, msg, *args, exc_info=None, stacklevel=1):
        self.log(LogLevel.ERROR, msg, *args, exc_info=exc_info, stacklevel=stacklevel + 1)

    def exception(self, msg, *args, stacklevel=1):
        self.log(LogLevel.ERROR, msg, *args, exc_info=True, stacklevel=stacklevel + 1)

    def dump(self, obj, label=None, *, level=LogLevel.DEBUG, depth=2, max_items=20, stacklevel=1):
        if not self.is_enabled_for(level):
            return
        text = dump(obj, depth=depth, max_items=max_items)
        if label:
            text = f"{label}: {text}"
        self.log(level, "%s", text, stacklevel=stacklevel + 1)

    @contextmanager
    def timed(self, label, level=LogLevel.DEBUG):
        start = time.perf_counter()
        failed = False
        try:
            yield
        except BaseException:
            failed = True
            raise
        finally:
            took = (time.perf_counter() - start) * 1000
            self.log(level, "%s took %.1f ms%s", label, took, " (failed)" if failed else "", stacklevel=3)

    def child(self, name):
        child = self._children.get(name)
        if child is None:
            child = Logger(f"{self.tag}.{name}", self)
            self._children[name] = child
        return child

    def add_handler(self, handler):
        if handler not in self._handlers:
            self._handlers.append(handler)

    def remove_handler(self, handler):
        try:
            self._handlers.remove(handler)
            return True
        except ValueError:
            return False

    def _reset(self):
        self._handlers.clear()
        self._level = None
        for c in self._children.values():
            c._reset()


# --------------------------------------------------------------------------- dump

def _is_java(obj):
    return type(obj).__module__.startswith("java") or hasattr(obj, "getClass") and callable(getattr(obj, "getClass", None))


_PLATFORM_PREFIXES = ("java.", "javax.", "android.", "androidx.", "kotlin.", "dalvik.")


def dump(obj, depth=2, max_items=20, _seen=None, _indent=0):
    """Structure of Python or Java objects (fields only, no getters)."""
    seen = _seen if _seen is not None else set()
    pad = "  " * _indent
    if obj is None or isinstance(obj, (bool, int, float)):
        return repr(obj)
    if isinstance(obj, str):
        return repr(obj if len(obj) <= 500 else obj[:500] + "…")
    if isinstance(obj, (bytes, bytearray)):
        return f"bytes[{len(obj)}]"
    oid = id(obj)
    if oid in seen:
        return "<cycle>"
    if isinstance(obj, (list, tuple, set)):
        items = list(obj)
        if depth <= 0:
            return f"{type(obj).__name__}[{len(items)}] {{…}}"
        seen.add(oid)
        lines = [f"{type(obj).__name__}[{len(items)}]"]
        for i, v in enumerate(items[:max_items]):
            lines.append(f"{pad}  [{i}] = " + dump(v, depth - 1, max_items, seen, _indent + 1))
        if len(items) > max_items:
            lines.append(f"{pad}  …")
        return "\n".join(lines)
    if isinstance(obj, dict):
        if depth <= 0:
            return f"dict[{len(obj)}] {{…}}"
        seen.add(oid)
        lines = [f"dict[{len(obj)}]"]
        for i, (k, v) in enumerate(obj.items()):
            if i >= max_items:
                lines.append(f"{pad}  …")
                break
            lines.append(f"{pad}  {k!r} = " + dump(v, depth - 1, max_items, seen, _indent + 1))
        return "\n".join(lines)
    try:
        cls = obj.getClass()
    except Exception:
        cls = None
    if cls is not None:
        name = cls.getName()
        if name.startswith(_PLATFORM_PREFIXES):
            try:
                if cls.isArray():
                    return f"{cls.getComponentType().getSimpleName()}[{len(obj)}]"
            except Exception:
                pass
            try:
                return f"{name} {obj.toString()}"
            except Exception:
                return name
        short = name.replace("org.telegram.tgnet.", "").replace("$", ".")
        header = f"{short} @{abs(hash(obj)) & 0xffffff:x}"
        if depth <= 0:
            return header + " {…}"
        seen.add(oid)
        lines = [header]
        try:
            from java.lang.reflect import Modifier
            fields = []
            c = cls
            while c is not None and not c.getName().startswith(_PLATFORM_PREFIXES):
                for f in c.getDeclaredFields():
                    if Modifier.isStatic(f.getModifiers()):
                        continue
                    fields.append(f)
                c = c.getSuperclass()
            fields.sort(key=lambda f: f.getName())
            for f in fields:
                try:
                    f.setAccessible(True)
                    value = f.get(obj)
                except Exception as e:
                    value = f"<{e}>"
                lines.append(f"{pad}  {f.getName()} = " + dump(value, depth - 1, max_items, seen, _indent + 1))
        except Exception as e:
            lines.append(f"{pad}  <{e}>")
        return "\n".join(lines)
    # plain python object
    attrs = getattr(obj, "__dict__", None)
    header = f"{type(obj).__name__} @{oid & 0xffffff:x}"
    if not attrs or depth <= 0:
        return header if attrs is None else header + " {…}"
    seen.add(oid)
    lines = [header]
    for k, v in list(attrs.items())[:max_items]:
        lines.append(f"{pad}  {k} = " + dump(v, depth - 1, max_items, seen, _indent + 1))
    return "\n".join(lines)


# --------------------------------------------------------------------------- registry

_loggers = {}
_file_owners = {}  # absolute file path -> plugin id


def _plugin_logger(plugin_id):
    logger = _loggers.get(plugin_id)
    if logger is None:
        logger = Logger(f"plugin.{plugin_id}")
        _loggers[plugin_id] = logger
    return logger


def _register_file(path, plugin_id):
    _file_owners[os.path.abspath(path)] = plugin_id


def _release_plugin(plugin_id):
    logger = _loggers.get(plugin_id)
    if logger is not None:
        logger._reset()


def get_logger(name=None):
    frame = inspect.currentframe().f_back
    filename = os.path.abspath(frame.f_code.co_filename) if frame else ""
    plugin_id = _file_owners.get(filename)
    if plugin_id is not None:
        base = _plugin_logger(plugin_id)
        module_name = frame.f_globals.get("__module_name__") if frame else None
        if name and name not in (plugin_id, module_name, "__main__") and "." in str(name):
            return base.child(str(name).rsplit(".", 1)[-1])
        return base
    tag = name or "pysdk"
    logger = _loggers.get("::" + tag)
    if logger is None:
        logger = Logger(tag)
        _loggers["::" + tag] = logger
    return logger
