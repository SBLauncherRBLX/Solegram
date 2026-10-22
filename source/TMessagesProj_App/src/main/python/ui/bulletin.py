"""Bottom-screen notifications ("bulletins") for plugins (exteraGram plugin SDK compatible)."""
import traceback
from typing import Callable, Optional

from java import dynamic_proxy, jclass

from android_utils import run_on_ui_thread
from log_utils import get_logger

__all__ = ["BulletinHelper"]

_BulletinFactory = jclass("org.telegram.ui.Components.BulletinFactory")
_FileType = jclass("org.telegram.ui.Components.BulletinFactory$FileType")
_LaunchActivity = jclass("org.telegram.ui.LaunchActivity")
_R = jclass("org.telegram.messenger.R")
_Runnable = jclass("java.lang.Runnable")

_logger = get_logger("ui.bulletin")


class _Callback(dynamic_proxy(_Runnable)):
    def __init__(self, fn):
        super().__init__()
        self.fn = fn

    def run(self):
        if self.fn is None:
            return
        try:
            self.fn()
        except Exception:
            _logger.error("bulletin callback failed:\n%s", traceback.format_exc())


def _raw(name, fallback=0):
    try:
        return int(getattr(_R.raw, name))
    except Exception:
        return fallback


class BulletinHelper:
    DURATION_SHORT = 1500
    DURATION_LONG = 2750
    DURATION_PROLONG = 5000

    @staticmethod
    def _factory(fragment=None):
        if fragment is not None:
            try:
                return _BulletinFactory.of(fragment)
            except Exception:
                pass
        try:
            last = _LaunchActivity.getSafeLastFragment()
            if last is not None:
                return _BulletinFactory.of(last)
        except Exception:
            pass
        return _BulletinFactory.global_()

    @classmethod
    def _show(cls, build, fragment=None):
        def _do():
            try:
                bulletin = build(cls._factory(fragment))
                if bulletin is not None:
                    bulletin.show()
            except Exception:
                _logger.error("bulletin failed:\n%s", traceback.format_exc())
        run_on_ui_thread(_do)

    @classmethod
    def show_info(cls, message: str, fragment=None):
        cls._show(lambda f: f.createSimpleBulletin(_raw("info"), str(message)), fragment)

    @classmethod
    def show_error(cls, message: str, fragment=None):
        cls._show(lambda f: f.createErrorBulletin(str(message)), fragment)

    @classmethod
    def show_success(cls, message: str, fragment=None):
        cls._show(lambda f: f.createSuccessBulletin(str(message)), fragment)

    @classmethod
    def show_simple(cls, text: str, icon_res_id: int, fragment=None):
        cls._show(lambda f: f.createSimpleBulletin(int(icon_res_id), str(text)), fragment)

    @classmethod
    def show_two_line(cls, title: str, subtitle: str, icon_res_id: int, fragment=None):
        cls._show(lambda f: f.createSimpleBulletin(int(icon_res_id), str(title), str(subtitle)), fragment)

    @classmethod
    def show_with_button(cls, text: str, icon_res_id: int, button_text: str, on_click: Optional[Callable[[], None]],
                         fragment=None, duration: int = DURATION_PROLONG):
        cls._show(lambda f: f.createSimpleBulletin(int(icon_res_id), str(text), str(button_text), int(duration),
                                                   _Callback(on_click)), fragment)

    @classmethod
    def show_undo(cls, text: str, on_undo: Callable[[], None], on_action: Optional[Callable[[], None]] = None,
                  subtitle: Optional[str] = None, fragment=None):
        if subtitle:
            cls._show(lambda f: f.createUndoBulletin(str(text), str(subtitle), _Callback(on_undo), _Callback(on_action)), fragment)
        else:
            cls._show(lambda f: f.createUndoBulletin(str(text), _Callback(on_undo), _Callback(on_action)), fragment)

    @classmethod
    def show_copied_to_clipboard(cls, message: Optional[str] = None, fragment=None):
        if message:
            cls._show(lambda f: f.createCopyBulletin(str(message)), fragment)
        else:
            text = jclass("org.telegram.messenger.LocaleController").getString(_R.string.TextCopied)
            cls._show(lambda f: f.createCopyBulletin(str(text)), fragment)

    @classmethod
    def show_link_copied(cls, is_private_link_info: bool = False, fragment=None):
        cls._show(lambda f: f.createCopyLinkBulletin(bool(is_private_link_info)), fragment)

    @classmethod
    def show_file_saved_to_gallery(cls, is_video: bool = False, amount: int = 1, fragment=None):
        if amount > 1:
            ft = _FileType.VIDEOS if is_video else _FileType.PHOTOS
        else:
            ft = _FileType.VIDEO if is_video else _FileType.PHOTO
        cls._show(lambda f: f.createDownloadBulletin(ft, int(amount), None), fragment)

    @classmethod
    def show_file_saved_to_downloads(cls, file_type_enum_name: str = "UNKNOWN", amount: int = 1, fragment=None):
        try:
            ft = _FileType.valueOf(str(file_type_enum_name).upper())
        except Exception:
            ft = _FileType.UNKNOWN
        cls._show(lambda f: f.createDownloadBulletin(ft, int(amount), None), fragment)
