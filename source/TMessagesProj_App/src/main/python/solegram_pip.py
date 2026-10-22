"""Minimal package manager for plugin __requirements__ (pure-Python wheels from PyPI).

Packages are unpacked into a shared libs directory placed first on sys.path. A small JSON state file
remembers which plugin needs which package so unused packages can be removed.
"""
import hashlib
import importlib
import importlib.metadata
import io
import json
import os
import shutil
import sys
import threading
import zipfile

from packaging.markers import default_environment
from packaging.requirements import Requirement
from packaging.specifiers import SpecifierSet
from packaging.tags import sys_tags
from packaging.utils import canonicalize_name, parse_wheel_filename
from packaging.version import InvalidVersion, Version

from log_utils import get_logger

_logger = get_logger("pip")
_lock = threading.RLock()
_libs_dir = None
_INDEX = "https://pypi.org/pypi/{name}/json"
_TIMEOUT = 30


class PipError(Exception):
    pass


def init(libs_dir):
    global _libs_dir
    _libs_dir = libs_dir
    os.makedirs(libs_dir, exist_ok=True)
    if libs_dir not in sys.path:
        sys.path.insert(0, libs_dir)


def _state_path():
    return os.path.join(_libs_dir, ".solegram_pip.json")


def _load_state():
    try:
        with open(_state_path(), "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {"packages": {}}


def _save_state(state):
    tmp = _state_path() + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=1, sort_keys=True)
    os.replace(tmp, _state_path())


def _installed_version(name, state):
    entry = state["packages"].get(name)
    if entry:
        return Version(entry["version"]), True
    try:
        return Version(importlib.metadata.version(name)), False
    except importlib.metadata.PackageNotFoundError:
        return None, False
    except InvalidVersion:
        return None, False


def _http_get(url):
    import requests
    response = requests.get(url, timeout=_TIMEOUT, headers={"User-Agent": "Solegram-plugins"})
    if response.status_code == 404:
        raise PipError("Package not found")
    response.raise_for_status()
    return response


_compatible_tags = None


def _wheel_compatible(filename):
    global _compatible_tags
    if _compatible_tags is None:
        _compatible_tags = set()
        for tag in sys_tags():
            if tag.platform == "any":
                _compatible_tags.add(str(tag))
    try:
        _, _, _, tags = parse_wheel_filename(filename)
    except Exception:
        return False
    return any(str(t) in _compatible_tags for t in tags)


def _pick_release(name, specifier):
    data = _http_get(_INDEX.format(name=name)).json()
    allow_pre = any(_safe_version(s.version) and Version(s.version).is_prerelease for s in specifier)
    candidates = []
    for ver_str, files in data.get("releases", {}).items():
        try:
            ver = Version(ver_str)
        except InvalidVersion:
            continue
        if not specifier.contains(ver, prereleases=allow_pre):
            continue
        for f in files:
            if f.get("yanked") or f.get("packagetype") != "bdist_wheel":
                continue
            if not _wheel_compatible(f["filename"]):
                continue
            requires_python = f.get("requires_python")
            if requires_python:
                try:
                    if not SpecifierSet(requires_python).contains(Version("%d.%d.%d" % sys.version_info[:3])):
                        continue
                except Exception:
                    pass
            candidates.append((ver, f))
            break
    if not candidates:
        raise PipError(f"No pure-Python wheel found for {name}{specifier}")
    candidates.sort(key=lambda c: c[0], reverse=True)
    return candidates[0]


def _safe_version(v):
    try:
        Version(v)
        return True
    except Exception:
        return False


def _remove_package_files(entry):
    for rel in entry.get("files", []):
        path = os.path.join(_libs_dir, rel)
        try:
            if os.path.isdir(path):
                shutil.rmtree(path)
            elif os.path.exists(path):
                os.remove(path)
        except Exception:
            pass


def _install_wheel(name, version, fileinfo):
    content = _http_get(fileinfo["url"]).content
    sha = (fileinfo.get("digests") or {}).get("sha256")
    if sha and hashlib.sha256(content).hexdigest() != sha:
        raise PipError(f"Checksum mismatch for {fileinfo['filename']}")
    tops = set()
    requires = []
    with zipfile.ZipFile(io.BytesIO(content)) as zf:
        for member in zf.namelist():
            norm = os.path.normpath(member)
            if norm.startswith("..") or os.path.isabs(norm):
                raise PipError(f"Unsafe path in wheel: {member}")
            tops.add(norm.split(os.sep, 1)[0])
        zf.extractall(_libs_dir)
        for member in zf.namelist():
            if member.endswith(".dist-info/METADATA"):
                text = zf.read(member).decode("utf-8", "replace")
                for line in text.splitlines():
                    if line.startswith("Requires-Dist:"):
                        requires.append(line.split(":", 1)[1].strip())
                    elif not line.strip():
                        break
    return sorted(tops), requires


def _ensure(req, plugin_id, state, chain, env):
    name = canonicalize_name(req.name)
    if name in chain:
        return
    chain = chain | {name}
    current, ours = _installed_version(name, state)
    if current is not None and req.specifier.contains(current, prereleases=True):
        entry = state["packages"].get(name)
        if entry is not None and plugin_id not in entry["plugins"]:
            entry["plugins"].append(plugin_id)
        return
    if current is not None:
        entry = state["packages"].get(name)
        others = [p for p in (entry or {}).get("plugins", []) if p != plugin_id]
        if not ours or others:
            raise PipError(f"Dependency conflict: {name} {current} is installed, {req} is required")
    version, fileinfo = _pick_release(name, req.specifier)
    _logger.info("installing %s %s for %s", name, version, plugin_id)
    old = state["packages"].get(name)
    if old:
        _remove_package_files(old)
    files, requires = _install_wheel(name, version, fileinfo)
    state["packages"][name] = {"version": str(version), "files": files, "plugins": [plugin_id]}
    _save_state(state)
    for dep_str in requires:
        try:
            dep = Requirement(dep_str)
        except Exception:
            continue
        if dep.marker is not None:
            try:
                if not dep.marker.evaluate(dict(env, extra="")):
                    continue
            except Exception:
                continue
        _ensure(dep, plugin_id, state, chain, env)


def install(plugin_id, requirements):
    """Installs requirements for a plugin. Returns None on success or an error message."""
    if not requirements:
        return None
    if _libs_dir is None:
        return "Package manager is not initialized"
    env = default_environment()
    with _lock:
        state = _load_state()
        try:
            for raw in requirements:
                raw = str(raw).strip()
                if not raw:
                    continue
                try:
                    req = Requirement(raw)
                except Exception:
                    return f"Invalid requirement: {raw}"
                if req.marker is not None and not req.marker.evaluate(env):
                    continue
                _ensure(req, plugin_id, state, frozenset(), env)
            _save_state(state)
        except PipError as e:
            return str(e)
        except Exception as e:
            _logger.error("installing requirements for %s failed", plugin_id, exc_info=e)
            return f"{type(e).__name__}: {e}"
        finally:
            importlib.invalidate_caches()
    return None


def release(plugin_id):
    """Removes packages no longer needed by any plugin after plugin_id was deleted."""
    if _libs_dir is None:
        return
    with _lock:
        state = _load_state()
        changed = False
        for name, entry in list(state["packages"].items()):
            if plugin_id in entry["plugins"]:
                entry["plugins"].remove(plugin_id)
                changed = True
            if not entry["plugins"]:
                _remove_package_files(entry)
                del state["packages"][name]
                changed = True
        if changed:
            _save_state(state)
