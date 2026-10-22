"""Filesystem helpers and file-open handlers for plugins (exteraGram plugin SDK compatible)."""
import enum
import os
import shutil
import traceback
from dataclasses import dataclass, field
from typing import Any, Callable, List, Optional

from java import dynamic_proxy, jarray, jclass

import plugin_registry
from log_utils import get_logger

__all__ = [
    "get_plugins_dir", "get_cache_dir", "get_files_dir", "get_images_dir", "get_videos_dir",
    "get_audios_dir", "get_documents_dir", "ensure_dir_exists", "list_dir", "read_file", "write_file",
    "read_file_bytes", "write_file_bytes", "delete_file", "FilesController",
]

_FileLoader = jclass("org.telegram.messenger.FileLoader")
_ApplicationLoader = jclass("org.telegram.messenger.ApplicationLoader")
_PluginsController = jclass("com.solegram.messenger.plugins.PluginsController")
_SolegramFiles = jclass("com.solegram.messenger.SolegramFiles")
_Handler = jclass("com.solegram.messenger.SolegramFiles$Handler")
_String = jclass("java.lang.String")

_logger = get_logger("file_utils")


# --------------------------------------------------------------------------- directories

def _dir(media_type):
    try:
        d = _FileLoader.getDirectory(media_type)
        if d is not None:
            return str(d.getAbsolutePath())
    except Exception:
        pass
    return str(_ApplicationLoader.applicationContext.getCacheDir().getAbsolutePath())


def get_plugins_dir() -> str:
    return str(_PluginsController.getPluginsDir().getAbsolutePath())


def get_cache_dir() -> str:
    return _dir(_FileLoader.MEDIA_DIR_CACHE)


def get_files_dir() -> str:
    return _dir(_FileLoader.MEDIA_DIR_FILES)


def get_images_dir() -> str:
    return _dir(_FileLoader.MEDIA_DIR_IMAGE)


def get_videos_dir() -> str:
    return _dir(_FileLoader.MEDIA_DIR_VIDEO)


def get_audios_dir() -> str:
    return _dir(_FileLoader.MEDIA_DIR_AUDIO)


def get_documents_dir() -> str:
    return _dir(_FileLoader.MEDIA_DIR_DOCUMENT)


# --------------------------------------------------------------------------- operations

def ensure_dir_exists(dir_path: str):
    try:
        os.makedirs(dir_path, exist_ok=True)
    except Exception:
        _logger.error("ensure_dir_exists(%s) failed:\n%s", dir_path, traceback.format_exc())


def list_dir(path: str, recursive: bool = False, include_files: bool = True, include_dirs: bool = False,
             extensions: Optional[List[str]] = None) -> List[str]:
    result = []
    exts = tuple(e.lower() if e.startswith(".") else "." + e.lower() for e in extensions) if extensions else None
    try:
        if recursive:
            for root, dirs, files in os.walk(path):
                if include_dirs:
                    result.extend(os.path.join(root, d) for d in dirs)
                if include_files:
                    for f in files:
                        if exts is None or f.lower().endswith(exts):
                            result.append(os.path.join(root, f))
        else:
            for name in os.listdir(path):
                full = os.path.join(path, name)
                if os.path.isdir(full):
                    if include_dirs:
                        result.append(full)
                elif include_files and (exts is None or name.lower().endswith(exts)):
                    result.append(full)
    except Exception:
        _logger.error("list_dir(%s) failed:\n%s", path, traceback.format_exc())
    return result


def read_file(file_path: str) -> Optional[str]:
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            return f.read()
    except Exception:
        _logger.error("read_file(%s) failed:\n%s", file_path, traceback.format_exc())
        return None


def write_file(file_path: str, content: str):
    try:
        parent = os.path.dirname(file_path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(file_path, "w", encoding="utf-8") as f:
            f.write(content)
    except Exception:
        _logger.error("write_file(%s) failed:\n%s", file_path, traceback.format_exc())


def read_file_bytes(file_path: str) -> Optional[bytes]:
    try:
        with open(file_path, "rb") as f:
            return f.read()
    except Exception:
        _logger.error("read_file_bytes(%s) failed:\n%s", file_path, traceback.format_exc())
        return None


def write_file_bytes(file_path: str, content: bytes):
    try:
        parent = os.path.dirname(file_path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(file_path, "wb") as f:
            f.write(bytes(content))
    except Exception:
        _logger.error("write_file_bytes(%s) failed:\n%s", file_path, traceback.format_exc())


def delete_file(file_path: str) -> bool:
    try:
        if os.path.isdir(file_path) and not os.path.islink(file_path):
            shutil.rmtree(file_path)
            return True
        if os.path.exists(file_path):
            os.remove(file_path)
            return True
    except Exception:
        _logger.error("delete_file(%s) failed:\n%s", file_path, traceback.format_exc())
    return False


# --------------------------------------------------------------------------- FilesController

class _JavaHandler(dynamic_proxy(_Handler)):
    def __init__(self, info, plugin_id):
        super().__init__()
        self.info = info
        self.plugin_id = plugin_id

    def onClick(self, place, file, file_name, message, activity, fragment):
        try:
            try:
                place_enum = FilesController.Place(str(place))
            except ValueError:
                place_enum = FilesController.Place.UNKNOWN
            args = FilesController.OnClickArgs(place_enum, file, file_name, message, activity, fragment)
            result = self.info.on_click(args)
            return result is None or bool(result)
        except Exception:
            _logger.error("FilesController handler for .%s failed:\n%s", self.info.ext, traceback.format_exc())
            return False

    def getIcon(self):
        if self.info.get_icon is None:
            return None
        try:
            return self.info.get_icon()
        except Exception:
            _logger.error("get_icon for .%s failed:\n%s", self.info.ext, traceback.format_exc())
            return None


class FilesController:
    """Intercepts opening files with a given extension."""

    SUPPORT_ICONS = False

    class ExtensionAlreadyRegistered(Exception):
        pass

    class ExtensionNotRegistered(Exception):
        pass

    class SecretInvalid(Exception):
        pass

    class Place(enum.Enum):
        UNKNOWN = "UNKNOWN"
        ChatActivity = "ChatActivity"
        FilteredSearchView = "FilteredSearchView"
        SharedMediaLayout = "SharedMediaLayout"
        SearchDownloadsContainer = "SearchDownloadsContainer"
        ChannelAdminLogActivity = "ChannelAdminLogActivity"

    @dataclass
    class OnClickArgs:
        place: Any
        file: Any
        file_name: str
        message: Any
        activity: Any
        parent_fragment: Any

    @dataclass
    class FileInfo:
        ext: str
        on_click: Callable[[Any], None]
        whitelist_places: List[Any] = field(default_factory=list)
        blacklist_places: List[Any] = field(default_factory=list)
        get_icon: Optional[Callable[[], Any]] = None

    _handlers = {}

    @staticmethod
    def _norm(ext):
        ext = str(ext).strip().lower()
        return ext[1:] if ext.startswith(".") else ext

    @staticmethod
    def _places(places):
        names = []
        for p in places or []:
            names.append(p.value if isinstance(p, enum.Enum) else str(p))
        return jarray(_String)(names)

    @classmethod
    def register(cls, file_info: "FilesController.FileInfo") -> str:
        if file_info.whitelist_places and file_info.blacklist_places:
            raise ValueError("whitelist_places and blacklist_places can't be used together")
        if file_info.get_icon is not None and not cls.SUPPORT_ICONS:
            _logger.warning("custom file icons are not supported by this client, get_icon is ignored")
        ext = cls._norm(file_info.ext)
        plugin_id = plugin_registry.owner_id()
        handler = _JavaHandler(file_info, plugin_id)
        secret = _SolegramFiles.register(ext, handler, cls._places(file_info.whitelist_places),
                                         cls._places(file_info.blacklist_places))
        if secret is None:
            raise cls.ExtensionAlreadyRegistered(f"Extension .{ext} is already registered")
        secret = str(secret)
        cls._handlers[ext] = handler

        def _cleanup():
            try:
                cls.unregister(ext, secret)
            except Exception:
                pass
        handler.cleanup = _cleanup
        plugin_registry.add_cleanup(plugin_id, _cleanup)
        return secret

    @classmethod
    def unregister(cls, ext: str, secret: str):
        ext = cls._norm(ext)
        code = int(_SolegramFiles.unregister(ext, str(secret)))
        if code == 1:
            raise cls.ExtensionNotRegistered(f"Extension .{ext} is not registered")
        if code == 2:
            raise cls.SecretInvalid(f"Invalid secret for .{ext}")
        handler = cls._handlers.pop(ext, None)
        if handler is not None:
            plugin_registry.remove_cleanup(handler.plugin_id, getattr(handler, "cleanup", None))
