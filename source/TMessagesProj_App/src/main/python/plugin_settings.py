"""Persistent plugin settings (JSON values stored per plugin)."""
import json
from typing import Any

from java import jclass

_PC = jclass("com.solegram.messenger.plugins.PluginsController")


def init(plugins_dir_path: str = None, all_shared_prefs: dict = None):
    """Kept for SDK compatibility: storage is managed by the app."""
    return None


def _decode(raw):
    if raw is None:
        return None
    try:
        return json.loads(raw)
    except Exception:
        return raw


def get_setting(plugin_id: str, key: str, default: Any = None) -> Any:
    raw = _PC.getSettingJson(plugin_id, str(key))
    if raw is None:
        return default
    value = _decode(raw)
    return default if value is None else value


def set_setting(plugin_id: str, key: str, value: Any):
    if value is None:
        _PC.setSettingJson(plugin_id, str(key), None)
        return
    try:
        raw = json.dumps(value, ensure_ascii=False)
    except TypeError:
        raw = json.dumps(str(value), ensure_ascii=False)
    _PC.setSettingJson(plugin_id, str(key), raw)


def clear_settings(plugin_id: str):
    _PC.clearSettings(plugin_id)


def get_all_settings(plugin_id: str) -> dict:
    try:
        raw = json.loads(_PC.getAllSettingsJson(plugin_id))
    except Exception:
        return {}
    return {k: _decode(v) for k, v in raw.items()}


def set_all_settings(plugin_id: str, settings: dict):
    for k, v in (settings or {}).items():
        set_setting(plugin_id, k, v)
