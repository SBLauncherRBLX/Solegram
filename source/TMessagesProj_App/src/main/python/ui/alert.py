"""Telegram-style AlertDialog builder for plugins (exteraGram plugin SDK compatible)."""
import traceback
from typing import Callable, Dict, List, Optional

from java import dynamic_proxy, jarray, jclass, jint

from log_utils import get_logger

__all__ = ["AlertDialogBuilder"]

_AlertDialog = jclass("org.telegram.ui.ActionBar.AlertDialog")
_Builder = jclass("org.telegram.ui.ActionBar.AlertDialog$Builder")
_OnButtonClickListener = jclass("org.telegram.ui.ActionBar.AlertDialog$OnButtonClickListener")
_DialogInterface = jclass("android.content.DialogInterface")
_OnClickListener = jclass("android.content.DialogInterface$OnClickListener")
_OnDismissListener = jclass("android.content.DialogInterface$OnDismissListener")
_OnCancelListener = jclass("android.content.DialogInterface$OnCancelListener")
_Theme = jclass("org.telegram.ui.ActionBar.Theme")
_HashMap = jclass("java.util.HashMap")
_Integer = jclass("java.lang.Integer")
_CharSequence = jclass("java.lang.CharSequence")

_logger = get_logger("ui.alert")


def _call(fn, *args):
    if fn is None:
        return
    try:
        fn(*args)
    except Exception:
        _logger.error("dialog listener failed:\n%s", traceback.format_exc())


class _ButtonClickListenerProxy(dynamic_proxy(_OnButtonClickListener)):
    def __init__(self, py_callable, builder_instance):
        super().__init__()
        self.fn = py_callable
        self.builder = builder_instance

    def onClick(self, dialog_java_instance, which):
        _call(self.fn, self.builder, int(which))


class _ItemsClickListenerProxy(dynamic_proxy(_OnClickListener)):
    def __init__(self, py_callable, builder_instance):
        super().__init__()
        self.fn = py_callable
        self.builder = builder_instance

    def onClick(self, dialog_java_instance, which):
        _call(self.fn, self.builder, int(which))


class _DismissListenerProxy(dynamic_proxy(_OnDismissListener)):
    def __init__(self, py_callable, builder_instance):
        super().__init__()
        self.fn = py_callable
        self.builder = builder_instance

    def onDismiss(self, dialog_java_instance):
        builder = self.builder
        try:
            _call(self.fn, builder)
        finally:
            if builder is not None:
                builder._on_dismissed()


class _CancelListenerProxy(dynamic_proxy(_OnCancelListener)):
    def __init__(self, py_callable, builder_instance):
        super().__init__()
        self.fn = py_callable
        self.builder = builder_instance

    def onCancel(self, dialog_java_instance):
        _call(self.fn, self.builder)


class AlertDialogBuilder:
    ALERT_TYPE_MESSAGE = 0
    ALERT_TYPE_LOADING = 2
    ALERT_TYPE_SPINNER = 3

    BUTTON_POSITIVE = -1
    BUTTON_NEGATIVE = -2
    BUTTON_NEUTRAL = -3

    def __init__(self, context, progress_style: int = ALERT_TYPE_MESSAGE, resources_provider=None):
        self._context = context
        self._builder = _Builder(context, int(progress_style), resources_provider)
        self._dialog = None
        self._dismissed = False
        self._dismiss_proxy = _DismissListenerProxy(None, self)
        self._builder.setOnDismissListener(self._dismiss_proxy)

    # content -----------------------------------------------------------------
    def get_context(self):
        return self._context

    def set_title(self, title: str):
        self._builder.setTitle(str(title))
        return self

    def set_message(self, message):
        self._builder.setMessage(message if not isinstance(message, str) else str(message))
        return self

    def set_message_text_view_clickable(self, clickable: bool):
        self._builder.setMessageTextViewClickable(bool(clickable))
        return self

    def set_view(self, view, height: int = -2):
        self._builder.setView(view, int(height))
        return self

    def set_items(self, items: List[str], listener: Optional[Callable] = None, icons: Optional[List[int]] = None):
        texts = jarray(_CharSequence)([str(i) for i in items])
        proxy = _ItemsClickListenerProxy(listener, self)
        if icons:
            self._builder.setItems(texts, jarray(jint)([int(i) for i in icons]), proxy)
        else:
            self._builder.setItems(texts, proxy)
        return self

    # buttons -----------------------------------------------------------------
    def set_positive_button(self, text: str, listener: Optional[Callable] = None):
        self._builder.setPositiveButton(str(text), _ButtonClickListenerProxy(listener, self))
        return self

    def set_negative_button(self, text: str, listener: Optional[Callable] = None):
        self._builder.setNegativeButton(str(text), _ButtonClickListenerProxy(listener, self))
        return self

    def set_neutral_button(self, text: str, listener: Optional[Callable] = None):
        self._builder.setNeutralButton(str(text), _ButtonClickListenerProxy(listener, self))
        return self

    def make_button_red(self, button_type: int):
        self._builder.makeRed(int(button_type))
        dialog = self.get_dialog()
        if dialog is not None:
            button = dialog.getButton(int(button_type))
            if button is not None:
                try:
                    button.setTextColor(_Theme.getColor(_Theme.key_text_RedBold))
                except Exception:
                    pass
        return self

    # listeners ---------------------------------------------------------------
    def set_on_back_button_listener(self, listener: Optional[Callable] = None):
        self._builder.setOnBackButtonListener(_ButtonClickListenerProxy(listener, self) if listener else None)
        return self

    def set_on_dismiss_listener(self, listener: Optional[Callable] = None):
        self._dismiss_proxy.fn = listener
        return self

    def set_on_cancel_listener(self, listener: Optional[Callable] = None):
        self._builder.setOnCancelListener(_CancelListenerProxy(listener, self) if listener else None)
        return self

    # appearance --------------------------------------------------------------
    def set_top_image(self, res_id: int, background_color: int):
        self._builder.setTopImage(int(res_id), int(background_color))
        return self

    def set_top_drawable(self, drawable, background_color: int):
        self._builder.setTopImage(drawable, int(background_color))
        return self

    def set_top_animation(self, res_id: int, size: int, auto_repeat: bool, background_color: int,
                          layer_colors: Optional[Dict[str, int]] = None):
        if layer_colors:
            colors = _HashMap()
            for k, v in layer_colors.items():
                colors.put(str(k), _Integer.valueOf(int(v)))
            self._builder.setTopAnimation(int(res_id), int(size), bool(auto_repeat), int(background_color), colors)
        else:
            self._builder.setTopAnimation(int(res_id), int(size), bool(auto_repeat), int(background_color))
        return self

    def set_top_animation_is_new(self, is_new: bool):
        self._builder.setTopAnimationIsNew(bool(is_new))
        return self

    def set_top_view(self, view):
        self._builder.setTopView(view)
        return self

    def set_dim_enabled(self, enabled: bool):
        self._builder.setDimEnabled(bool(enabled))
        return self

    def set_dialog_button_color_key(self, theme_key: int):
        self._builder.setDialogButtonColorKey(int(theme_key))
        return self

    def set_blurred_background(self, blur: bool, blur_behind_if_possible: bool = True):
        self._builder.setBlurredBackground(bool(blur))
        return self

    # lifecycle ---------------------------------------------------------------
    def create(self):
        if self._dialog is None and not self._dismissed:
            self._dialog = self._builder.create()
        return self

    def show(self):
        self.create()
        dialog = self.get_dialog()
        if dialog is not None:
            dialog.show()
        return self

    def dismiss(self):
        dialog = self.get_dialog()
        if dialog is not None:
            try:
                dialog.dismiss()
            except Exception:
                pass

    def _on_dismissed(self):
        # A dismissed dialog cannot be shown again: drop it so it (and the listeners) can be freed.
        self._dialog = None
        self._dismissed = True

    def get_dialog(self):
        return self._dialog

    def get_button(self, button_type: int):
        dialog = self.get_dialog()
        return dialog.getButton(int(button_type)) if dialog is not None else None

    def set_progress(self, progress: int):
        dialog = self.get_dialog()
        if dialog is not None:
            dialog.setProgress(int(progress))

    def set_cancelable(self, cancelable: bool):
        dialog = self.get_dialog()
        if dialog is not None:
            dialog.setCancelable(bool(cancelable))
        return self

    def set_canceled_on_touch_outside(self, cancel: bool):
        dialog = self.get_dialog()
        if dialog is not None:
            dialog.setCanceledOnTouchOutside(bool(cancel))
        return self
