"""Development server for desktop plugin tooling (exteraGram plugin SDK compatible protocol).

Listens on 127.0.0.1:42690 (reach it with `adb forward tcp:42690 tcp:42690`). The stream carries
consecutive UTF-8 JSON objects: {"@": command, "#": request id, ...}; every answer repeats "#".
Only started when plugin developer mode is enabled in Solegram settings.
"""
import enum
import json
import os
import socket
import tempfile
import threading
import traceback

from java import dynamic_proxy, jclass

from log_utils import get_logger

__all__ = ["DevServer", "DebuggerPlatform"]

_PC = jclass("com.solegram.messenger.plugins.PluginsController")
_InstallCallback = jclass("com.solegram.messenger.plugins.PluginsController$InstallCallback")
_File = jclass("java.io.File")

_logger = get_logger("dev_server")


class DebuggerPlatform(enum.Enum):
    PyCharm = "pycharm"
    VSCode = "vscode"


class _Install(dynamic_proxy(_InstallCallback)):
    def __init__(self):
        super().__init__()
        self.event = threading.Event()
        self.meta = None
        self.error = None

    def onResult(self, meta, error):
        self.meta = meta
        self.error = None if error is None else str(error)
        self.event.set()


def _describe(meta):
    return {
        "id": str(meta.id),
        "name": str(meta.name) if meta.name is not None else str(meta.id),
        "version": str(meta.version),
        "author": str(meta.author or ""),
        "enabled": bool(meta.enabled),
        "loaded": bool(meta.loaded),
        "error": None if meta.error is None else str(meta.error),
        "file": str(meta.file.getAbsolutePath()) if meta.file is not None else None,
    }


class DevServer:
    DEFAULT_HOST = "127.0.0.1"
    DEFAULT_PORT = 42690
    SOCKET_TIMEOUT = 1.0
    BUFFER_SIZE = 65536
    PYCHARM_DEBUGGER_DIR = None

    _thread = None
    _socket = None
    _running = False

    @classmethod
    def start_server(cls, host: str = None, port: int = None):
        if cls._running:
            return cls._thread
        host = host or cls.DEFAULT_HOST
        port = int(port or cls.DEFAULT_PORT)
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        srv.bind((host, port))
        srv.listen(2)
        srv.settimeout(cls.SOCKET_TIMEOUT)
        cls._socket = srv
        cls._running = True
        cls._thread = threading.Thread(target=cls._serve, name="solegram-dev-server", daemon=True)
        cls._thread.start()
        _logger.info("dev server listening on %s:%d", host, port)
        return cls._thread

    @classmethod
    def stop_server(cls) -> bool:
        if not cls._running:
            return False
        cls._running = False
        try:
            cls._socket.close()
        except Exception:
            pass
        return True

    @classmethod
    def _serve(cls):
        while cls._running:
            try:
                conn, _ = cls._socket.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            threading.Thread(target=cls._client, args=(conn,), daemon=True).start()

    @classmethod
    def _client(cls, conn):
        decoder = json.JSONDecoder()
        buffer = ""
        conn.settimeout(None)
        with conn:
            while cls._running:
                try:
                    chunk = conn.recv(cls.BUFFER_SIZE)
                except OSError:
                    return
                if not chunk:
                    return
                buffer += chunk.decode("utf-8", "replace")
                while True:
                    buffer = buffer.lstrip()
                    if not buffer:
                        break
                    try:
                        message, end = decoder.raw_decode(buffer)
                    except json.JSONDecodeError:
                        break
                    buffer = buffer[end:]
                    response = cls._handle(message)
                    try:
                        conn.sendall(json.dumps(response, ensure_ascii=False).encode("utf-8"))
                    except OSError:
                        return

    @classmethod
    def _handle(cls, message):
        req_id = message.get("#") if isinstance(message, dict) else None
        try:
            result = cls._dispatch(message)
        except Exception as e:
            _logger.error("dev server command failed:\n%s", traceback.format_exc())
            result = {"error": f"{type(e).__name__}: {e}"}
        result["#"] = req_id
        return result

    @classmethod
    def _dispatch(cls, message):
        command = message.get("@")
        controller = _PC.getInstance()
        if command == "ping":
            return {"pong": True}
        if command == "get_plugins":
            return {"plugins": [_describe(m) for m in controller.getPlugins().toArray()]}
        plugin_id = message.get("plugin_id")
        if command in ("enable_plugin", "disable_plugin"):
            if controller.getPlugin(plugin_id) is None:
                return {"error": f"Plugin {plugin_id} is not installed"}
            controller.setEnabled(plugin_id, command == "enable_plugin")
            return {"ok": True}
        if command == "reload_plugin":
            if controller.getPlugin(plugin_id) is None:
                return {"error": f"Plugin {plugin_id} is not installed"}
            controller.reloadPlugin(plugin_id)
            return {"ok": True}
        if command == "write_plugin":
            content = message.get("content")
            if not isinstance(content, str):
                return {"error": "content must be a string"}
            fd, tmp = tempfile.mkstemp(suffix=".plugin")
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                f.write(content)
            try:
                callback = _Install()
                controller.install(_File(tmp), callback)
                if not callback.event.wait(60):
                    return {"error": "timeout"}
                if callback.meta is None:
                    return {"error": callback.error or "install failed"}
                result = {"ok": callback.error is None, "plugin": _describe(callback.meta)}
                if callback.error:
                    result["error"] = callback.error
                return result
            finally:
                try:
                    os.remove(tmp)
                except OSError:
                    pass
        if command == "remove_plugin":
            if controller.getPlugin(plugin_id) is None:
                return {"error": f"Plugin {plugin_id} is not installed"}
            controller.delete(plugin_id, None)
            return {"ok": True}
        if command == "start_debugger":
            ok = cls.setup_remote_debugging(message.get("host", "127.0.0.1"), int(message.get("port", 5678)),
                                            DebuggerPlatform(message.get("platform", "vscode")))
            return {"ok": ok} if ok else {"error": "debugpy is not available in this build"}
        if command == "stop_debugger":
            return {"ok": cls.stop_remote_debugging(DebuggerPlatform(message.get("platform", "vscode")))}
        return {"error": f"Unknown command: {command}"}

    @classmethod
    def setup_remote_debugging(cls, host: str, port: int, platform: DebuggerPlatform) -> bool:
        try:
            import debugpy
        except ImportError:
            return False
        try:
            debugpy.connect((host, int(port)))
            return True
        except Exception:
            _logger.error("debugger connection failed:\n%s", traceback.format_exc())
            return False

    @classmethod
    def stop_remote_debugging(cls, platform: DebuggerPlatform) -> bool:
        return False
