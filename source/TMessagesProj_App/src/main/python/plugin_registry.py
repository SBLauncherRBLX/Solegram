"""Internal bookkeeping shared by the SDK modules: which plugin owns the running code, and what has to
be released when that plugin unloads (factories, file handlers, intent handlers...)."""
import inspect
import os
import threading
import traceback

import log_utils

_lock = threading.RLock()
_plugins = {}      # plugin id -> BasePlugin instance
_cleanups = {}     # plugin id -> [callable]
_current = threading.local()


def set_plugin(plugin_id, plugin):
    with _lock:
        if plugin is None:
            _plugins.pop(plugin_id, None)
        else:
            _plugins[plugin_id] = plugin


def get_plugin(plugin_id):
    return _plugins.get(plugin_id)


def all_plugins():
    with _lock:
        return dict(_plugins)


class loading:
    """Context manager marking the plugin whose module code / on_plugin_load is running."""

    def __init__(self, plugin_id):
        self.plugin_id = plugin_id

    def __enter__(self):
        stack = getattr(_current, "stack", None)
        if stack is None:
            stack = _current.stack = []
        stack.append(self.plugin_id)
        return self

    def __exit__(self, *exc):
        _current.stack.pop()
        return False


def owner_id(default=None):
    """Plugin id owning the caller: the plugin being loaded, else the first plugin file on the stack."""
    stack = getattr(_current, "stack", None)
    if stack:
        return stack[-1]
    owners = log_utils._file_owners
    if owners:
        frame = inspect.currentframe()
        try:
            while frame is not None:
                pid = owners.get(os.path.abspath(frame.f_code.co_filename))
                if pid is not None:
                    return pid
                frame = frame.f_back
        finally:
            del frame
    return default


def owner_plugin():
    pid = owner_id()
    return _plugins.get(pid) if pid else None


def add_cleanup(plugin_id, fn):
    if not plugin_id:
        return
    with _lock:
        _cleanups.setdefault(plugin_id, []).append(fn)


def remove_cleanup(plugin_id, fn):
    with _lock:
        try:
            _cleanups.get(plugin_id, []).remove(fn)
        except ValueError:
            pass


def run_cleanups(plugin_id):
    with _lock:
        fns = _cleanups.pop(plugin_id, [])
    for fn in reversed(fns):
        try:
            fn()
        except Exception:
            log_utils.get_logger("pysdk").error("cleanup for %s failed:\n%s", plugin_id, traceback.format_exc())
