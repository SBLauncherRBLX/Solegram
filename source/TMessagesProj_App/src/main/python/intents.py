"""Global link / intent handlers (exteraGram plugin SDK compatible).

    from intents import IntentsManager as IM
    handle = IM.new_global_before_handler(on_chat, scheme="tg", host="chat", required_path_args_names=["id"])
    handle.unhandle()

before handlers returning True stop the remaining handlers and Telegram's own handling.
"""
import inspect
import re
import threading
import traceback
import uuid
from typing import Any, Callable, Dict, List, Optional
from urllib.parse import parse_qs, urlsplit

from java import dynamic_proxy, jclass

import plugin_registry
from log_utils import get_logger

__all__ = ["IntentsManager", "HandlerHandle"]

_SolegramIntents = jclass("com.solegram.messenger.SolegramIntents")
_Listener = jclass("com.solegram.messenger.SolegramIntents$Listener")

_logger = get_logger("intents")
_STANDARD = ("intent", "scheme", "host", "path", "query_args", "action", "flags", "type", "categories")


class HandlerHandle:
    def __init__(self, handler_id):
        self.handler_id = handler_id

    def unhandle(self):
        try:
            IntentsManager.unhandle(self.handler_id)
        except IntentsManager.HandlerNotRegistered:
            pass

    def __repr__(self):
        return f"HandlerHandle({self.handler_id})"


class _Handler:
    def __init__(self, fn, before, plugin_id, scheme, host, path, required, action, whitelist_flags,
                 blacklist_flags, mime_type, categories, priority):
        self.id = uuid.uuid4().hex
        self.fn = fn
        self.before = before
        self.plugin_id = plugin_id
        self.scheme = scheme.lower() if scheme else None
        self.host = host.lower() if host else None
        self.required = list(required or [])
        self.action = action
        self.whitelist_flags = list(whitelist_flags or [])
        self.blacklist_flags = list(blacklist_flags or [])
        self.type = mime_type
        self.categories = list(categories or [])
        self.priority = int(priority or 0)
        self.path_regex = None
        if path:
            if not path.startswith("/"):
                path = "/" + path
            pattern = re.escape(path)
            pattern = re.sub(r"\\\{(\w+)\\\}", r"(?P<\1>[^/]+)", pattern)
            self.path_regex = re.compile("^" + pattern + "/?$")
        try:
            sig = inspect.signature(fn)
            params = sig.parameters.values()
            self.var_kw = any(p.kind == p.VAR_KEYWORD for p in params)
            self.var_pos = any(p.kind == p.VAR_POSITIONAL for p in params)
            self.names = [p.name for p in sig.parameters.values()
                          if p.kind in (p.POSITIONAL_OR_KEYWORD, p.KEYWORD_ONLY)]
        except (TypeError, ValueError):
            self.var_kw, self.var_pos, self.names = True, False, []

    def match(self, ctx):
        if self.scheme is not None and (ctx["scheme"] or "").lower() != self.scheme:
            return None
        if self.host is not None and (ctx["host"] or "").lower() != self.host:
            return None
        if self.action is not None and ctx["action"] != self.action:
            return None
        if self.type is not None and ctx["type"] != self.type:
            return None
        flags = ctx["flags"] or 0
        if any((flags & int(f)) != int(f) for f in self.whitelist_flags):
            return None
        if any(flags & int(f) for f in self.blacklist_flags):
            return None
        if self.categories and not all(c in ctx["categories"] for c in self.categories):
            return None
        query = ctx["query_args"]
        if any(name not in query for name in self.required):
            return None
        extra = {}
        if self.path_regex is not None:
            m = self.path_regex.match(ctx["path"] or "")
            if m is None:
                return None
            extra.update(m.groupdict())
        return extra

    def invoke(self, ctx, extra):
        named = dict(ctx["query_args"])
        named.update(extra)
        for k in _STANDARD:
            named[k] = ctx[k]
        if self.var_pos:
            args = [ctx[k] for k in _STANDARD]
            if self.var_kw:
                kwargs = {k: v for k, v in named.items() if k not in _STANDARD}
            else:
                kwargs = {k: named[k] for k in self.names if k in named and k not in _STANDARD}
        else:
            args = []
            kwargs = dict(named) if self.var_kw else {k: named[k] for k in self.names if k in named}
        return self.fn(*args, **kwargs)


class _Bridge(dynamic_proxy(_Listener)):
    def before(self, intent):
        return IntentsManager._dispatch(intent, True)

    def after(self, intent):
        IntentsManager._dispatch(intent, False)


class IntentsManager:
    class HandlerNotRegistered(Exception):
        pass

    _handlers: Dict[str, _Handler] = {}
    _lock = threading.RLock()
    _bridge = None

    @classmethod
    def _register(cls, fn, before, scheme=None, host=None, path=None, required_path_args_names=None, action=None,
                  whitelist_flags=None, blacklist_flags=None, type=None, categories=None, priority=0):
        if not callable(fn):
            raise TypeError("handler must be callable")
        plugin_id = plugin_registry.owner_id()
        h = _Handler(fn, before, plugin_id, scheme, host, path, required_path_args_names, action, whitelist_flags,
                     blacklist_flags, type, categories, priority)
        with cls._lock:
            cls._handlers[h.id] = h
            if cls._bridge is None:
                cls._bridge = _Bridge()
                _SolegramIntents.setListener(cls._bridge)
        if plugin_id:
            plugin_registry.add_cleanup(plugin_id, lambda: cls._handlers.pop(h.id, None))
        return HandlerHandle(h.id)

    @classmethod
    def new_global_before_handler(cls, fn: Callable, **filters) -> HandlerHandle:
        return cls._register(fn, True, **filters)

    @classmethod
    def new_global_after_handler(cls, fn: Callable, **filters) -> HandlerHandle:
        return cls._register(fn, False, **filters)

    @classmethod
    def unhandle(cls, handler_id: str):
        with cls._lock:
            if cls._handlers.pop(handler_id, None) is None:
                raise cls.HandlerNotRegistered(f"Handler {handler_id} is not registered")

    @staticmethod
    def parse(url: str) -> Dict[str, Any]:
        parts = urlsplit(str(url))
        result = {"scheme": parts.scheme or None, "host": parts.hostname or parts.netloc or None}
        if parts.path:
            result["path"] = parts.path
        if parts.query:
            result["query"] = parts.query
        return result

    @staticmethod
    def _context(intent):
        data = intent.getData()
        scheme = host = path = None
        query = {}
        if data is not None:
            scheme = data.getScheme()
            host = data.getHost()
            path = data.getPath()
            raw_query = data.getEncodedQuery()
            if raw_query:
                for k, v in parse_qs(str(raw_query), keep_blank_values=True).items():
                    query[k] = v[0] if len(v) == 1 else v
        cats = intent.getCategories()
        categories = [str(c) for c in cats.toArray()] if cats is not None else []
        return {
            "intent": intent,
            "scheme": None if scheme is None else str(scheme),
            "host": None if host is None else str(host),
            "path": None if path is None else str(path),
            "query_args": query,
            "action": None if intent.getAction() is None else str(intent.getAction()),
            "flags": int(intent.getFlags()),
            "type": None if intent.getType() is None else str(intent.getType()),
            "categories": categories,
        }

    @classmethod
    def _dispatch(cls, intent, before):
        with cls._lock:
            handlers = sorted((h for h in cls._handlers.values() if h.before == before), key=lambda h: h.priority)
        if not handlers:
            return False
        try:
            ctx = cls._context(intent)
        except Exception:
            _logger.error("intent parsing failed:\n%s", traceback.format_exc())
            return False
        for h in handlers:
            extra = h.match(ctx)
            if extra is None:
                continue
            try:
                result = h.invoke(ctx, extra)
            except Exception:
                _logger.error("intent handler failed:\n%s", traceback.format_exc())
                continue
            if before and result is True:
                return True
        return False
