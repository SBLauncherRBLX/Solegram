"""Android helpers for plugins: UI thread, listeners, clipboard (exteraGram SDK compatible)."""
import traceback
from typing import Any

from java import dynamic_proxy, jclass

from log_utils import dump, get_logger

_Runnable = jclass("java.lang.Runnable")
_OnClickListener = jclass("android.view.View$OnClickListener")
_OnLongClickListener = jclass("android.view.View$OnLongClickListener")
_AndroidUtilities = jclass("org.telegram.messenger.AndroidUtilities")

_logger = get_logger("android_utils")


class R(dynamic_proxy(_Runnable)):
    """A java.lang.Runnable backed by a Python callable."""

    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def run(self):
        try:
            self.fn()
        except Exception:
            _logger.error("Runnable failed:\n%s", traceback.format_exc())


class OnClickListener(dynamic_proxy(_OnClickListener)):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def onClick(self, view):
        try:
            self.fn(view)
        except Exception:
            _logger.error("OnClickListener failed:\n%s", traceback.format_exc())


class OnLongClickListener(dynamic_proxy(_OnLongClickListener)):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def onLongClick(self, view):
        try:
            return bool(self.fn(view))
        except Exception:
            _logger.error("OnLongClickListener failed:\n%s", traceback.format_exc())
            return False


def run_on_ui_thread(func, delay: int = 0):
    """Runs a Python callable on the Android main thread (optionally after delay ms)."""
    runnable = func if isinstance(func, _Runnable) else R(func)
    if delay and delay > 0:
        _AndroidUtilities.runOnUIThread(runnable, int(delay))
    else:
        _AndroidUtilities.runOnUIThread(runnable)


def log(data: Any):
    """Legacy logger: debug level, generic tag. Prefer self.logger."""
    if data is None or isinstance(data, (str, int, float, bool)):
        get_logger("plugins").log(3, "%s", data)
    else:
        get_logger("plugins").log(3, "%s", dump(data))


def copy_to_clipboard(text: str):
    """Copies text and shows the standard "copied" bulletin."""
    def _do():
        try:
            if _AndroidUtilities.addToClipboard(str(text)):
                from ui.bulletin import BulletinHelper
                BulletinHelper.show_copied_to_clipboard()
        except Exception:
            _logger.error("copy_to_clipboard failed:\n%s", traceback.format_exc())
    run_on_ui_thread(_do)
