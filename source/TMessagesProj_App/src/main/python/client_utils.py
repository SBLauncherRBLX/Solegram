"""Client helpers: queues, requests, controllers, sending and editing messages (exteraGram SDK compatible)."""
import threading
import traceback
from contextlib import contextmanager
from typing import Any, Optional

from java import dynamic_proxy, jclass

from log_utils import get_logger

STAGE_QUEUE = "stageQueue"
GLOBAL_QUEUE = "globalQueue"
CACHE_CLEAR_QUEUE = "cacheClearQueue"
SEARCH_QUEUE = "searchQueue"
PHONE_BOOK_QUEUE = "phoneBookQueue"
THEME_QUEUE = "themeQueue"
EXTERNAL_NETWORK_QUEUE = "externalNetworkQueue"
PLUGINS_QUEUE = "pluginsQueue"

_logger = get_logger("client_utils")

_Utilities = jclass("org.telegram.messenger.Utilities")
_PluginsController = jclass("com.solegram.messenger.plugins.PluginsController")
_PluginApi = jclass("com.solegram.messenger.plugins.PluginApi")
_UserConfig = jclass("org.telegram.messenger.UserConfig")
_AccountInstance = jclass("org.telegram.messenger.AccountInstance")
_ConnectionsManager = jclass("org.telegram.tgnet.ConnectionsManager")
_RequestDelegate = jclass("org.telegram.tgnet.RequestDelegate")
_Runnable = jclass("java.lang.Runnable")
_NotificationCenter = jclass("org.telegram.messenger.NotificationCenter")
_NCDelegate = jclass("org.telegram.messenger.NotificationCenter$NotificationCenterDelegate")
_MediaController = jclass("org.telegram.messenger.MediaController")
_LaunchActivity = jclass("org.telegram.ui.LaunchActivity")
_ArrayList = jclass("java.util.ArrayList")


# --------------------------------------------------------------------------- account scope

_scope = threading.local()
_warned = set()


def get_hook_account() -> Optional[int]:
    """The account the current hook / queued task / request callback runs for, or None."""
    return getattr(_scope, "account", None)


def get_selected_account() -> int:
    return int(_UserConfig.selectedAccount)


@contextmanager
def _account_scope(account):
    previous = getattr(_scope, "account", None)
    _scope.account = account
    try:
        yield
    finally:
        _scope.account = previous


def _resolve(account, helper: str) -> int:
    if account is not None:
        return int(account)
    selected = get_selected_account()
    hook = get_hook_account()
    if hook is not None and hook != selected and helper not in _warned:
        _warned.add(helper)
        _logger.warning(
            "%s() is using account %d because it is the one selected in the UI, but it was called while handling "
            "account %d. Pass account=%d, or use get_client(%d), if you meant that account. Logged once per helper.",
            helper, selected, hook, hook, hook)
    return selected


# --------------------------------------------------------------------------- queues

def get_queue_by_name(queue_name: str):
    if queue_name == PLUGINS_QUEUE:
        return _PluginsController.pluginsQueue
    try:
        return getattr(_Utilities, queue_name)
    except Exception:
        return None


class _ScopedRunnable(dynamic_proxy(_Runnable)):
    def __init__(self, fn, account):
        super().__init__()
        self.fn = fn
        self.account = account

    def run(self):
        try:
            with _account_scope(self.account):
                self.fn()
        except Exception:
            _logger.error("queued task failed:\n%s", traceback.format_exc())


def run_on_queue(fn, queue_name: str = PLUGINS_QUEUE, delay: int = 0):
    queue = get_queue_by_name(queue_name) or _PluginsController.pluginsQueue
    runnable = _ScopedRunnable(fn, get_hook_account())
    if delay and delay > 0:
        queue.postRunnable(runnable, int(delay))
    else:
        queue.postRunnable(runnable)


# --------------------------------------------------------------------------- requests

class RequestCallback(dynamic_proxy(_RequestDelegate)):
    def __init__(self, fn, account=None):
        super().__init__()
        self.fn = fn
        self.account = account

    def run(self, response, error):
        if self.fn is None:
            return
        try:
            with _account_scope(self.account):
                self.fn(response, error)
        except Exception:
            _logger.error("request callback failed:\n%s", traceback.format_exc())


def send_request(request: Any, fn=None, account: Optional[int] = None) -> int:
    acc = _resolve(account, "send_request")
    return _ConnectionsManager.getInstance(acc).sendRequest(request, RequestCallback(fn, acc))


# --------------------------------------------------------------------------- controllers

def get_last_fragment():
    return _LaunchActivity.getSafeLastFragment()


def get_account_instance(account: Optional[int] = None):
    return _AccountInstance.getInstance(_resolve(account, "get_account_instance"))


def _ai(account, name):
    return _AccountInstance.getInstance(_resolve(account, name))


def get_messages_controller(account: Optional[int] = None):
    return _ai(account, "get_messages_controller").getMessagesController()


def get_contacts_controller(account: Optional[int] = None):
    return _ai(account, "get_contacts_controller").getContactsController()


def get_media_data_controller(account: Optional[int] = None):
    return _ai(account, "get_media_data_controller").getMediaDataController()


def get_connections_manager(account: Optional[int] = None):
    return _ai(account, "get_connections_manager").getConnectionsManager()


def get_location_controller(account: Optional[int] = None):
    return _ai(account, "get_location_controller").getLocationController()


def get_notifications_controller(account: Optional[int] = None):
    return _ai(account, "get_notifications_controller").getNotificationsController()


def get_messages_storage(account: Optional[int] = None):
    return _ai(account, "get_messages_storage").getMessagesStorage()


def get_send_messages_helper(account: Optional[int] = None):
    return _ai(account, "get_send_messages_helper").getSendMessagesHelper()


def get_file_loader(account: Optional[int] = None):
    return _ai(account, "get_file_loader").getFileLoader()


def get_secret_chat_helper(account: Optional[int] = None):
    return _ai(account, "get_secret_chat_helper").getSecretChatHelper()


def get_download_controller(account: Optional[int] = None):
    return _ai(account, "get_download_controller").getDownloadController()


def get_notifications_settings(account: Optional[int] = None):
    return _ai(account, "get_notifications_settings").getNotificationsSettings()


def get_notification_center(account: Optional[int] = None):
    return _ai(account, "get_notification_center").getNotificationCenter()


def get_user_config(account: Optional[int] = None):
    return _ai(account, "get_user_config").getUserConfig()


def get_media_controller():
    return _MediaController.getInstance()


# --------------------------------------------------------------------------- sending

def _entities_list(entities):
    lst = _ArrayList()
    for e in entities or []:
        lst.add(e)
    return lst


def _parse(text, parse_mode, is_caption=False):
    if text is None or not parse_mode:
        return text, None
    mode = str(parse_mode).lower()
    if mode not in ("html", "markdown"):
        raise ValueError(f"Unsupported parse_mode: {parse_mode}")
    from extera_utils.text_formatting import parse_text
    parsed = parse_text(text, "HTML" if mode == "html" else "Markdown", is_caption)
    return parsed["caption" if is_caption else "message"], parsed["entities"]


def _reply(acc, peer, reply):
    if reply is None:
        return None
    if isinstance(reply, int):
        return _PluginApi.replyStub(acc, int(peer), int(reply))
    return reply


def send_message(params: dict, parse_mode: Optional[str] = None, account: Optional[int] = None):
    """Sends a message described by SendMessagesHelper.SendMessageParams fields."""
    params = dict(params or {})
    acc = _resolve(account if account is not None else params.pop("account", None), "send_message")
    peer = int(params.pop("peer"))
    text = params.pop("message", None)
    caption = params.get("caption")
    if parse_mode:
        if text is not None:
            text, entities = _parse(text, parse_mode)
            params["entities"] = entities
        elif caption is not None:
            caption, entities = _parse(caption, parse_mode, True)
            params["caption"] = caption
            params["entities"] = entities
    java_params = _PluginApi.textParams(peer, text)
    if "replyToMsg" in params:
        params["replyToMsg"] = _reply(acc, peer, params["replyToMsg"])
    if "replyToTopMsg" in params:
        params["replyToTopMsg"] = _reply(acc, peer, params["replyToTopMsg"])
    if "entities" in params and params["entities"] is not None and not hasattr(params["entities"], "size"):
        params["entities"] = _entities_list(params["entities"])
    if "notify" not in params:
        params["notify"] = True
    for key, value in params.items():
        try:
            setattr(java_params, key, value)
        except Exception as e:
            _logger.warning("send_message: unknown field %s (%s)", key, e)
    _PluginApi.send(acc, java_params)
    return java_params


def send_text(peer: int, text: str, account: Optional[int] = None, parse_mode: Optional[str] = None, **kwargs):
    params = {"peer": peer, "message": text}
    params.update(kwargs)
    return send_message(params, parse_mode=parse_mode, account=account)


def _media_common(peer, caption, parse_mode, kwargs, account, name):
    acc = _resolve(account, name)
    entities = kwargs.get("entities")
    if parse_mode and caption:
        caption, entities = _parse(caption, parse_mode, True)
    reply = _reply(acc, peer, kwargs.get("replyToMsg"))
    notify = kwargs.get("notify", True)
    schedule = int(kwargs.get("scheduleDate", 0) or 0)
    return acc, caption, (_entities_list(entities) if entities is not None and not hasattr(entities, "size") else entities), reply, notify, schedule


def send_photo(peer: int, file_path: str, caption: str = "", high_quality: bool = False, account: Optional[int] = None,
               parse_mode: Optional[str] = None, **kwargs):
    acc, caption, entities, reply, notify, schedule = _media_common(peer, caption, parse_mode, kwargs, account, "send_photo")
    _PluginApi.sendPhoto(acc, int(peer), str(file_path), caption or None, entities, bool(high_quality), reply, bool(notify),
                         schedule, bool(kwargs.get("hasMediaSpoilers", False)))


def send_document(peer: int, file_path: str, caption: str = "", account: Optional[int] = None,
                  parse_mode: Optional[str] = None, **kwargs):
    acc, caption, entities, reply, notify, schedule = _media_common(peer, caption, parse_mode, kwargs, account, "send_document")
    _PluginApi.sendDocument(acc, int(peer), str(file_path), caption or None, entities, reply, bool(notify), schedule)


def send_video(peer: int, file_path: str, caption: str = "", account: Optional[int] = None,
               parse_mode: Optional[str] = None, **kwargs):
    acc, caption, entities, reply, notify, schedule = _media_common(peer, caption, parse_mode, kwargs, account, "send_video")
    _PluginApi.sendVideo(acc, int(peer), str(file_path), caption or None, entities, reply, bool(notify), schedule,
                         bool(kwargs.get("hasMediaSpoilers", False)))


def send_audio(peer: int, file_path: str, caption: str = "", account: Optional[int] = None,
               parse_mode: Optional[str] = None, **kwargs):
    acc, caption, entities, reply, notify, schedule = _media_common(peer, caption, parse_mode, kwargs, account, "send_audio")
    _PluginApi.sendDocument(acc, int(peer), str(file_path), caption or None, entities, reply, bool(notify), schedule)


def edit_message(message_obj: Any, text: Optional[str] = None, file_path: Optional[str] = None, with_spoiler: bool = False,
                 parse_mode: Optional[str] = None, **kwargs):
    entities = kwargs.get("entities")
    if text is not None and parse_mode:
        text, entities = _parse(text, parse_mode, file_path is not None)
    if entities is not None and not hasattr(entities, "size"):
        entities = _entities_list(entities)
    if file_path:
        _PluginApi.editMedia(message_obj, str(file_path), text, entities, bool(with_spoiler))
    elif text is not None:
        _PluginApi.editText(message_obj, str(text), entities)


# --------------------------------------------------------------------------- notifications

class NotificationCenterDelegate(dynamic_proxy(_NCDelegate)):
    def __init__(self):
        super().__init__()

    def didReceivedNotification(self, id: int, account: int, args):
        pass


# --------------------------------------------------------------------------- account client

class AccountClient:
    """All account-scoped helpers bound to one account."""

    def __init__(self, account: int):
        self.account = int(account)

    def __repr__(self):
        return f"AccountClient({self.account})"

    def __getattr__(self, name):
        target = globals().get(name)
        if target is None or not callable(target) or name.startswith("_"):
            raise AttributeError(name)
        if name in ("get_media_controller", "get_last_fragment", "get_queue_by_name", "run_on_queue"):
            return target

        def bound(*args, **kwargs):
            kwargs.setdefault("account", self.account)
            return target(*args, **kwargs)
        return bound


def get_client(account: Optional[int] = None) -> AccountClient:
    if account is None:
        account = get_hook_account()
    if account is None:
        account = get_selected_account()
    return AccountClient(account)
