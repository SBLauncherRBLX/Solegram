/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

/**
 * Static security analysis of a plugin's source code. The plugin is never executed: the source is
 * tokenized like Python does it (so a word inside a comment or a string is not an API call), imports
 * and aliases are resolved, and the uses are matched against rules grouped into permission
 * categories. Combinations that only make sense for malware (secrets + network, decoding + exec...)
 * raise the verdict.
 * <p>
 * Pure Java on purpose (no Android classes): the rules are unit tested on a desktop JVM.
 */
public final class PluginSecurity {

    /** Bump when the rules change: cached reports of an older analyzer are computed again. */
    public static final int VERSION = 1;

    public static final int VERDICT_SAFE = 0;
    public static final int VERDICT_WARNING = 1;
    public static final int VERDICT_DANGER = 2;

    public static final int LOW = 1;
    public static final int MEDIUM = 2;
    public static final int HIGH = 3;
    public static final int CRITICAL = 4;

    public static final int CAT_NETWORK = 0;
    public static final int CAT_MESSAGES = 1;
    public static final int CAT_ACTIONS = 2;
    public static final int CAT_SECRETS = 3;
    public static final int CAT_FILES = 4;
    public static final int CAT_CODE = 5;
    public static final int CAT_SYSTEM = 6;
    public static final int CAT_UI = 7;
    public static final int CATEGORY_COUNT = 8;

    /** Evidence lines kept per rule: enough to judge, small enough to cache. */
    private static final int MAX_EVIDENCE = 8;
    private static final int MAX_DETAILS = 8;
    private static final int MAX_SNIPPET = 120;

    private PluginSecurity() {
    }

    // region Model

    public static final class Evidence {
        public final int line;
        public final String snippet;

        public Evidence(int line, String snippet) {
            this.line = line;
            this.snippet = snippet;
        }
    }

    /** One rule that matched, with where it matched. */
    public static final class Finding {
        public final String rule;
        public final int category;
        public final int severity;
        public final ArrayList<Evidence> evidence = new ArrayList<>();
        /** Distinct short values: modules, hosts, TL methods, decoded strings... */
        public final ArrayList<String> details = new ArrayList<>();
        public int count;

        public Finding(String rule, int category, int severity) {
            this.rule = rule;
            this.category = category;
            this.severity = severity;
        }

        public boolean isCombo() {
            return rule.startsWith("combo_");
        }
    }

    public static final class Report {
        public int version = VERSION;
        public String sha256;
        public int verdict;
        public long size;
        public int lines;
        /** Rule that decided the verdict (the most severe finding), or null. */
        public String reason;
        public final ArrayList<Finding> findings = new ArrayList<>();
        /** Hosts of the URLs written in the code. */
        public final ArrayList<String> hosts = new ArrayList<>();

        public Finding find(String rule) {
            for (Finding f : findings) {
                if (f.rule.equals(rule)) {
                    return f;
                }
            }
            return null;
        }

        public boolean has(String rule) {
            return find(rule) != null;
        }

        /** Highest severity in a category, 0 when the category is not used. */
        public int categorySeverity(int category) {
            int max = 0;
            for (Finding f : findings) {
                if (f.category == category && f.severity > max) {
                    max = f.severity;
                }
            }
            return max;
        }

        /** Used categories, the riskiest first. */
        public List<Integer> categories() {
            ArrayList<Integer> list = new ArrayList<>();
            for (Finding f : findings) {
                if (!list.contains(f.category)) {
                    list.add(f.category);
                }
            }
            return list;
        }

        public List<Finding> inCategory(int category) {
            ArrayList<Finding> list = new ArrayList<>();
            for (Finding f : findings) {
                if (f.category == category) {
                    list.add(f);
                }
            }
            return list;
        }

        public int maxSeverity() {
            int max = 0;
            for (Finding f : findings) {
                max = Math.max(max, f.severity);
            }
            return max;
        }
    }

    /** Rules of {@code fresh} the old version did not have: what an update newly asks for. */
    public static List<Finding> newPermissions(Report old, Report fresh) {
        ArrayList<Finding> list = new ArrayList<>();
        if (fresh == null) {
            return list;
        }
        for (Finding f : fresh.findings) {
            if (old == null || !old.has(f.rule)) {
                list.add(f);
            }
        }
        return list;
    }

    public static int ruleCategory(String rule) {
        int[] r = RULES.get(rule);
        return r != null ? r[0] : CAT_SYSTEM;
    }

    public static int ruleSeverity(String rule) {
        int[] r = RULES.get(rule);
        return r != null ? r[1] : LOW;
    }

    public static boolean isKnownRule(String rule) {
        return RULES.containsKey(rule);
    }
    // endregion

    // region Rules

    /** rule -> {category, severity} */
    private static final Map<String, int[]> RULES = new HashMap<>();

    private static void rule(String id, int category, int severity) {
        RULES.put(id, new int[]{category, severity});
    }

    static {
        rule("net_python", CAT_NETWORK, MEDIUM);
        rule("net_java", CAT_NETWORK, MEDIUM);
        rule("net_webview", CAT_NETWORK, MEDIUM);
        rule("net_suspicious", CAT_NETWORK, HIGH);
        rule("combo_suspicious_exfil", CAT_NETWORK, CRITICAL);

        rule("msg_updates", CAT_MESSAGES, MEDIUM);
        rule("msg_outgoing", CAT_MESSAGES, MEDIUM);
        rule("msg_requests", CAT_MESSAGES, MEDIUM);
        rule("msg_read", CAT_MESSAGES, MEDIUM);
        rule("msg_contacts", CAT_MESSAGES, MEDIUM);
        rule("msg_database", CAT_MESSAGES, HIGH);
        rule("combo_exfil_messages", CAT_MESSAGES, HIGH);

        rule("act_request", CAT_ACTIONS, LOW);
        rule("act_send", CAT_ACTIONS, MEDIUM);
        rule("act_edit", CAT_ACTIONS, LOW);
        rule("act_forward", CAT_ACTIONS, MEDIUM);
        rule("act_delete", CAT_ACTIONS, MEDIUM);
        rule("act_join", CAT_ACTIONS, MEDIUM);
        rule("act_profile", CAT_ACTIONS, MEDIUM);
        rule("act_payments", CAT_ACTIONS, HIGH);

        rule("sec_session_files", CAT_SECRETS, CRITICAL);
        rule("sec_auth_internals", CAT_SECRETS, HIGH);
        rule("sec_prefs", CAT_SECRETS, HIGH);
        rule("sec_login_accept", CAT_SECRETS, CRITICAL);
        rule("sec_login_export", CAT_SECRETS, HIGH);
        rule("sec_2fa", CAT_SECRETS, HIGH);
        rule("sec_sessions", CAT_SECRETS, HIGH);
        rule("sec_account", CAT_SECRETS, CRITICAL);
        rule("sec_login_codes", CAT_SECRETS, HIGH);
        rule("sec_phone", CAT_SECRETS, MEDIUM);
        rule("sec_clipboard", CAT_SECRETS, MEDIUM);
        rule("sec_keystore", CAT_SECRETS, HIGH);
        rule("sec_plugins_control", CAT_SECRETS, HIGH);
        rule("sec_tamper", CAT_SECRETS, CRITICAL);
        rule("combo_exfil_secrets", CAT_SECRETS, CRITICAL);

        rule("file_io", CAT_FILES, LOW);
        rule("file_listing", CAT_FILES, LOW);
        rule("file_delete", CAT_FILES, MEDIUM);
        rule("file_private_dir", CAT_FILES, MEDIUM);
        rule("file_shared_storage", CAT_FILES, LOW);
        rule("file_archive", CAT_FILES, LOW);
        rule("combo_private_network", CAT_FILES, HIGH);

        rule("code_exec", CAT_CODE, HIGH);
        rule("code_exec_static", CAT_CODE, MEDIUM);
        rule("code_import", CAT_CODE, MEDIUM);
        rule("code_dynamic_import", CAT_CODE, HIGH);
        rule("code_deserialize", CAT_CODE, HIGH);
        rule("code_decode", CAT_CODE, LOW);
        rule("code_blob", CAT_CODE, HIGH);
        rule("code_hidden_string", CAT_CODE, HIGH);
        rule("code_escapes", CAT_CODE, MEDIUM);
        rule("code_chr", CAT_CODE, MEDIUM);
        rule("code_getattr", CAT_CODE, HIGH);
        rule("code_native", CAT_CODE, HIGH);
        rule("code_packages", CAT_CODE, LOW);
        rule("combo_hidden_code", CAT_CODE, CRITICAL);
        rule("combo_remote_code", CAT_CODE, CRITICAL);
        rule("combo_obfuscated_network", CAT_CODE, CRITICAL);

        rule("sys_shell", CAT_SYSTEM, HIGH);
        rule("sys_reflection", CAT_SYSTEM, MEDIUM);
        rule("sys_hooks", CAT_SYSTEM, MEDIUM);
        rule("sys_dex", CAT_SYSTEM, HIGH);
        rule("sys_native_lib", CAT_SYSTEM, HIGH);
        rule("sys_install", CAT_SYSTEM, HIGH);
        rule("sys_permissions", CAT_SYSTEM, MEDIUM);
        rule("sys_apps", CAT_SYSTEM, MEDIUM);
        rule("sys_intents", CAT_SYSTEM, LOW);
        rule("combo_shell_network", CAT_SYSTEM, CRITICAL);

        rule("ui_overlay", CAT_UI, HIGH);
        rule("ui_settings", CAT_UI, MEDIUM);
        rule("ui_notifications", CAT_UI, LOW);
        rule("ui_menu", CAT_UI, LOW);
        rule("ui_links", CAT_UI, LOW);
    }

    /** Qualified names (modules, Java classes, module functions): matched on whole dotted segments. */
    private static final String[][] QNAMES = {
        // network, Python
        {"requests", "net_python"}, {"urllib", "net_python"}, {"urllib2", "net_python"}, {"urllib3", "net_python"},
        {"http.client", "net_python"}, {"httplib", "net_python"}, {"socket", "net_python"}, {"socketserver", "net_python"},
        {"aiohttp", "net_python"}, {"websocket", "net_python"}, {"websockets", "net_python"}, {"httpx", "net_python"},
        {"ftplib", "net_python"}, {"smtplib", "net_python"}, {"telnetlib", "net_python"}, {"poplib", "net_python"},
        {"imaplib", "net_python"}, {"xmlrpc", "net_python"}, {"paramiko", "net_python"}, {"pycurl", "net_python"},
        {"grpc", "net_python"}, {"asyncio.open_connection", "net_python"}, {"asyncio.start_server", "net_python"},
        // network, Java through the bridge
        {"java.net", "net_java"}, {"javax.net", "net_java"}, {"okhttp3", "net_java"}, {"org.apache.http", "net_java"},
        {"com.android.volley", "net_java"}, {"android.net.http", "net_java"}, {"android.webkit", "net_webview"},
        // messages and contacts
        {"org.telegram.messenger.MessagesStorage", "msg_read"}, {"org.telegram.messenger.ContactsController", "msg_contacts"},
        {"org.telegram.SQLite", "msg_database"},
        // acting as the user
        {"org.telegram.messenger.SendMessagesHelper", "act_send"}, {"com.solegram.messenger.plugins.PluginApi", "act_send"},
        // secrets and the app's own security
        {"com.solegram.messenger.plugins", "sec_plugins_control"}, {"java.security.KeyStore", "sec_keystore"},
        {"android.security.keystore", "sec_keystore"}, {"android.accounts", "sec_keystore"},
        {"org.telegram.messenger.SharedConfig", "ui_settings"}, {"com.solegram.messenger.SolegramConfig", "ui_settings"},
        // Solegram's own secrets: the app's api credentials and the user's encrypted keys
        {"com.solegram.messenger.ApiCredentials", "sec_auth_internals"}, {"com.solegram.messenger.SolegramSecrets", "sec_keystore"},
        // files
        {"os.remove", "file_delete"}, {"os.unlink", "file_delete"}, {"os.rmdir", "file_delete"}, {"os.removedirs", "file_delete"},
        {"shutil.rmtree", "file_delete"}, {"shutil.move", "file_delete"}, {"shutil.make_archive", "file_archive"}, {"shutil", "file_io"},
        {"os.rename", "file_io"}, {"os.replace", "file_io"}, {"os.open", "file_io"}, {"io.open", "file_io"}, {"pathlib", "file_io"},
        {"sqlite3", "file_io"}, {"os.listdir", "file_listing"}, {"os.walk", "file_listing"}, {"os.scandir", "file_listing"},
        {"glob", "file_listing"}, {"zipfile", "file_archive"}, {"tarfile", "file_archive"},
        {"java.io.File", "file_io"}, {"java.io.FileInputStream", "file_io"}, {"java.io.FileOutputStream", "file_io"},
        {"java.io.RandomAccessFile", "file_io"}, {"java.nio.file", "file_io"}, {"java.util.zip.ZipOutputStream", "file_archive"},
        {"android.os.Environment", "file_shared_storage"},
        // code execution and obfuscation
        // builtins exec / eval / compile are judged by their argument in onExec()
        {"importlib.util", "code_exec"}, {"importlib.machinery", "code_exec"},
        {"runpy", "code_exec"}, {"codeop", "code_exec"}, {"types.FunctionType", "code_exec"}, {"types.CodeType", "code_exec"},
        {"code.InteractiveInterpreter", "code_exec"}, {"code.InteractiveConsole", "code_exec"},
        {"marshal", "code_deserialize"}, {"pickle", "code_deserialize"}, {"cPickle", "code_deserialize"},
        {"_pickle", "code_deserialize"}, {"dill", "code_deserialize"}, {"shelve", "code_deserialize"}, {"jsonpickle", "code_deserialize"},
        {"base64.b64decode", "code_decode"}, {"base64.b32decode", "code_decode"}, {"base64.b16decode", "code_decode"},
        {"base64.b85decode", "code_decode"}, {"base64.a85decode", "code_decode"}, {"base64.decodebytes", "code_decode"},
        {"base64.decodestring", "code_decode"}, {"base64.standard_b64decode", "code_decode"}, {"base64.urlsafe_b64decode", "code_decode"},
        {"codecs.decode", "code_decode"}, {"zlib.decompress", "code_decode"}, {"bz2.decompress", "code_decode"},
        {"lzma.decompress", "code_decode"}, {"gzip.decompress", "code_decode"}, {"binascii.unhexlify", "code_decode"},
        {"binascii.a2b_base64", "code_decode"}, {"binascii.a2b_hex", "code_decode"}, {"android.util.Base64.decode", "code_decode"},
        {"ctypes", "code_native"}, {"cffi", "code_native"},
        // system
        {"subprocess", "sys_shell"}, {"os.system", "sys_shell"}, {"os.popen", "sys_shell"}, {"os.execl", "sys_shell"},
        {"os.execle", "sys_shell"}, {"os.execlp", "sys_shell"}, {"os.execlpe", "sys_shell"}, {"os.execv", "sys_shell"},
        {"os.execve", "sys_shell"}, {"os.execvp", "sys_shell"}, {"os.execvpe", "sys_shell"}, {"os.spawnl", "sys_shell"},
        {"os.spawnv", "sys_shell"}, {"os.spawnlp", "sys_shell"}, {"os.spawnvp", "sys_shell"}, {"os.fork", "sys_shell"},
        {"os.forkpty", "sys_shell"}, {"os.posix_spawn", "sys_shell"}, {"os.posix_spawnp", "sys_shell"}, {"pty", "sys_shell"},
        {"commands", "sys_shell"}, {"java.lang.ProcessBuilder", "sys_shell"}, {"java.lang.Runtime.exec", "sys_shell"},
        {"java.lang.reflect", "sys_reflection"}, {"de.robv.android.xposed", "sys_hooks"},
        {"dalvik.system", "sys_dex"}, {"java.lang.ClassLoader", "sys_dex"}, {"java.net.URLClassLoader", "sys_dex"},
        {"java.lang.System.load", "sys_native_lib"}, {"java.lang.System.loadLibrary", "sys_native_lib"},
        {"android.content.pm.PackageInstaller", "sys_install"}, {"android.permission", "sys_permissions"},
        // interface
        {"android.app.NotificationManager", "ui_notifications"}, {"android.app.Notification", "ui_notifications"},
        {"androidx.core.app.NotificationCompat", "ui_notifications"}, {"org.telegram.messenger.NotificationsController", "ui_notifications"},
        {"org.telegram.messenger.browser.Browser", "ui_links"}, {"webbrowser", "ui_links"},
    };

    /** Attribute and method names (after a dot), and names reached through getattr("..."). */
    private static final String[][] ATTRS = {
        {"getSharedPreferences", "sec_prefs"}, {"getDefaultSharedPreferences", "sec_prefs"},
        {"getMainSettings", "ui_settings"}, {"getGlobalMainSettings", "ui_settings"}, {"getNotificationsSettings", "ui_settings"},
        {"getGlobalNotificationsSettings", "ui_settings"}, {"getEmojiSettings", "ui_settings"},
        {"savedPasswordHash", "sec_auth_internals"}, {"savedSaltedPassword", "sec_auth_internals"}, {"tmpPassword", "sec_auth_internals"},
        {"passcodeHash", "sec_auth_internals"}, {"passcodeSalt", "sec_auth_internals"}, {"pushString", "sec_auth_internals"},
        {"getAuthKey", "sec_auth_internals"}, {"authKey", "sec_auth_internals"}, {"auth_key", "sec_auth_internals"},
        {"aiApiKey", "sec_auth_internals"}, {"getApiHash", "sec_auth_internals"}, {"APP_HASH", "sec_auth_internals"},
        {"getDatabase", "msg_database"}, {"queryFinalized", "msg_database"}, {"executeFast", "msg_database"},
        {"getMessagesStorage", "msg_read"}, {"dialogs_dict", "msg_read"}, {"allDialogs", "msg_read"},
        {"getContactsController", "msg_contacts"}, {"contactsDict", "msg_contacts"}, {"phoneBookContacts", "msg_contacts"},
        {"phone", "sec_phone"}, {"getClientPhone", "sec_phone"}, {"getPrimaryClip", "sec_clipboard"},
        {"loadUrl", "net_webview"}, {"loadData", "net_webview"}, {"loadDataWithBaseURL", "net_webview"},
        {"evaluateJavascript", "net_webview"}, {"addJavascriptInterface", "net_webview"},
        {"openConnection", "net_java"}, {"newCall", "net_java"},
        {"getFilesDir", "file_private_dir"}, {"getFilesDirFixed", "file_private_dir"}, {"getDataDir", "file_private_dir"},
        {"dataDir", "file_private_dir"}, {"filesDir", "file_private_dir"}, {"getDatabasePath", "file_private_dir"},
        {"getNoBackupFilesDir", "file_private_dir"},
        {"getExternalStorageDirectory", "file_shared_storage"}, {"getExternalStoragePublicDirectory", "file_shared_storage"},
        {"send_message", "act_send"}, {"send_text", "act_send"}, {"send_photo", "act_send"}, {"send_document", "act_send"},
        {"send_video", "act_send"}, {"send_audio", "act_send"}, {"sendMessage", "act_send"}, {"prepareSendingDocuments", "act_send"},
        {"prepareSendingMedia", "act_send"}, {"prepareSendingVideo", "act_send"}, {"prepareSendingText", "act_send"},
        {"send_request", "act_request"}, {"sendRequest", "act_request"},
        {"edit_message", "act_edit"}, {"editMessage", "act_edit"},
        {"forwardMessages", "act_forward"}, {"deleteMessages", "act_delete"}, {"deleteDialog", "act_delete"},
        {"addUserToChat", "act_join"}, {"deleteParticipantFromChat", "act_join"}, {"deleteUserFromChat", "act_join"},
        {"add_on_send_message_hook", "msg_outgoing"},
        {"hook_method", "sys_hooks"}, {"hook_all_methods", "sys_hooks"}, {"hook_all_constructors", "sys_hooks"},
        {"findAndHookMethod", "sys_hooks"}, {"hookAllMethods", "sys_hooks"}, {"hookMethod", "sys_hooks"},
        {"getDeclaredField", "sys_reflection"}, {"getDeclaredFields", "sys_reflection"}, {"getDeclaredMethod", "sys_reflection"},
        {"getDeclaredMethods", "sys_reflection"}, {"getDeclaredConstructor", "sys_reflection"}, {"setAccessible", "sys_reflection"},
        {"getObjectField", "sys_reflection"}, {"setObjectField", "sys_reflection"}, {"getStaticObjectField", "sys_reflection"},
        {"setStaticObjectField", "sys_reflection"}, {"callMethod", "sys_reflection"}, {"callStaticMethod", "sys_reflection"},
        {"get_private_field", "sys_reflection"}, {"set_private_field", "sys_reflection"},
        {"get_static_private_field", "sys_reflection"}, {"set_static_private_field", "sys_reflection"},
        {"loadClass", "sys_dex"}, {"defineClass", "sys_dex"}, {"loadLibrary", "sys_native_lib"},
        {"getInstalledPackages", "sys_apps"}, {"getInstalledApplications", "sys_apps"}, {"queryIntentActivities", "sys_apps"},
        {"requestPermissions", "sys_permissions"},
        {"startActivity", "sys_intents"}, {"startActivityForResult", "sys_intents"}, {"sendBroadcast", "sys_intents"},
        {"startService", "sys_intents"}, {"startForegroundService", "sys_intents"},
        {"TYPE_APPLICATION_OVERLAY", "ui_overlay"}, {"TYPE_SYSTEM_ALERT", "ui_overlay"}, {"TYPE_SYSTEM_OVERLAY", "ui_overlay"},
        {"TYPE_PHONE", "ui_overlay"}, {"canDrawOverlays", "ui_overlay"}, {"ACTION_MANAGE_OVERLAY_PERMISSION", "ui_overlay"},
        {"ACTION_INSTALL_PACKAGE", "sys_install"}, {"REQUEST_INSTALL_PACKAGES", "sys_install"}, {"getPackageInstaller", "sys_install"},
        {"add_menu_item", "ui_menu"}, {"openUrl", "ui_links"}, {"createNotificationChannel", "ui_notifications"},
        {"getNotificationsController", "ui_notifications"},
    };

    /** Functions of the plugin SDK modules, also reachable through "from client_utils import *". */
    private static final String[][] SDK_NAMES = {
        {"send_message", "act_send"}, {"send_text", "act_send"}, {"send_photo", "act_send"}, {"send_document", "act_send"},
        {"send_video", "act_send"}, {"send_audio", "act_send"}, {"send_request", "act_request"}, {"edit_message", "act_edit"},
        {"get_messages_storage", "msg_read"}, {"get_contacts_controller", "msg_contacts"},
        {"get_notifications_controller", "ui_notifications"}, {"get_files_dir", "file_private_dir"},
        {"read_file", "file_io"}, {"write_file", "file_io"}, {"read_file_bytes", "file_io"}, {"write_file_bytes", "file_io"},
        {"delete_file", "file_delete"}, {"list_dir", "file_listing"},
        {"get_private_field", "sys_reflection"}, {"set_private_field", "sys_reflection"},
        {"get_static_private_field", "sys_reflection"}, {"set_static_private_field", "sys_reflection"},
        {"MenuItemData", "ui_menu"},
    };

    private static final Set<String> SDK_MODULES = new HashSet<>(java.util.Arrays.asList(
        "client_utils", "file_utils", "hook_utils", "android_utils", "base_plugin", "ui", "ui.settings", "ui.alert",
        "ui.bulletin", "markdown_utils", "extera_utils", "plugin_settings", "utils", "intents", "log_utils"));

    /** Telegram API methods, as TLRPC class names (TL_auth_acceptLoginToken). */
    private static final String[][] TL_METHODS = {
        {"TL_messages_sendMessage", "act_send"}, {"TL_messages_sendMedia", "act_send"}, {"TL_messages_sendMultiMedia", "act_send"},
        {"TL_messages_sendInlineBotResult", "act_send"},
        {"TL_messages_forwardMessages", "act_forward"},
        {"TL_messages_deleteMessages", "act_delete"}, {"TL_channels_deleteMessages", "act_delete"}, {"TL_messages_deleteHistory", "act_delete"},
        {"TL_channels_deleteHistory", "act_delete"}, {"TL_messages_deleteChat", "act_delete"}, {"TL_channels_deleteChannel", "act_delete"},
        {"TL_channels_joinChannel", "act_join"}, {"TL_messages_importChatInvite", "act_join"}, {"TL_channels_leaveChannel", "act_join"},
        {"TL_messages_deleteChatUser", "act_join"}, {"TL_channels_inviteToChannel", "act_join"}, {"TL_messages_addChatUser", "act_join"},
        {"TL_contacts_block", "act_join"}, {"TL_contacts_unblock", "act_join"}, {"TL_contacts_addContact", "act_join"},
        {"TL_messages_startBot", "act_join"}, {"TL_chatlists_joinChatlistInvite", "act_join"},
        {"TL_account_updateProfile", "act_profile"}, {"TL_account_updateUsername", "act_profile"}, {"TL_photos_uploadProfilePhoto", "act_profile"},
        {"TL_photos_updateProfilePhoto", "act_profile"}, {"TL_account_updateEmojiStatus", "act_profile"}, {"TL_account_setPrivacy", "act_profile"},
        {"TL_account_updateBirthday", "act_profile"}, {"TL_account_updatePersonalChannel", "act_profile"},
        {"TL_messages_getHistory", "msg_read"}, {"TL_messages_search", "msg_read"}, {"TL_messages_searchGlobal", "msg_read"},
        {"TL_messages_getDialogs", "msg_read"}, {"TL_messages_getMessages", "msg_read"}, {"TL_channels_getMessages", "msg_read"},
        {"TL_messages_getPeerDialogs", "msg_read"},
        {"TL_contacts_getContacts", "msg_contacts"}, {"TL_contacts_importContacts", "msg_contacts"}, {"TL_contacts_getSaved", "msg_contacts"},
        {"TL_auth_acceptLoginToken", "sec_login_accept"},
        {"TL_auth_exportLoginToken", "sec_login_export"}, {"TL_auth_importLoginToken", "sec_login_export"},
        {"TL_auth_exportAuthorization", "sec_login_export"}, {"TL_auth_importAuthorization", "sec_login_export"},
        {"TL_auth_bindTempAuthKey", "sec_login_export"},
        {"TL_account_getPassword", "sec_2fa"}, {"TL_account_getPasswordSettings", "sec_2fa"}, {"TL_account_updatePasswordSettings", "sec_2fa"},
        {"TL_account_getTmpPassword", "sec_2fa"}, {"TL_auth_checkPassword", "sec_2fa"}, {"TL_auth_requestPasswordRecovery", "sec_2fa"},
        {"TL_auth_recoverPassword", "sec_2fa"}, {"TL_account_confirmPasswordEmail", "sec_2fa"}, {"TL_account_resendPasswordEmail", "sec_2fa"},
        {"TL_account_cancelPasswordEmail", "sec_2fa"},
        {"TL_auth_resetAuthorizations", "sec_sessions"}, {"TL_account_resetAuthorization", "sec_sessions"},
        {"TL_account_getAuthorizations", "sec_sessions"}, {"TL_auth_logOut", "sec_sessions"}, {"TL_account_setAccountTTL", "sec_sessions"},
        {"TL_account_setAuthorizationTTL", "sec_sessions"}, {"TL_account_changeAuthorizationSettings", "sec_sessions"},
        {"TL_account_getWebAuthorizations", "sec_sessions"}, {"TL_account_resetWebAuthorization", "sec_sessions"},
        {"TL_account_resetWebAuthorizations", "sec_sessions"},
        {"TL_account_deleteAccount", "sec_account"}, {"TL_account_changePhone", "sec_account"}, {"TL_account_sendChangePhoneCode", "sec_account"},
        {"TL_updateServiceNotification", "sec_login_codes"},
    };

    private static final Map<String, String> QNAME_MAP = new HashMap<>();
    private static final Map<String, String> ATTR_MAP = new HashMap<>();
    private static final Map<String, String> SDK_MAP = new HashMap<>();
    private static final Map<String, String> TL_MAP = new HashMap<>();

    static {
        for (String[] e : QNAMES) {
            QNAME_MAP.put(e[0], e[1]);
        }
        for (String[] e : ATTRS) {
            ATTR_MAP.put(e[0], e[1]);
        }
        for (String[] e : SDK_NAMES) {
            SDK_MAP.put(e[0], e[1]);
        }
        for (String[] e : TL_METHODS) {
            TL_MAP.put(e[0], e[1]);
        }
    }

    /** ConnectionsManager natives that only read harmless state; every other native_* is internal. */
    private static final Set<String> HARMLESS_NATIVES = new HashSet<>(java.util.Arrays.asList(
        "native_getCurrentTime", "native_getCurrentTimeMillis", "native_getCurrentPingTime", "native_getCurrentDatacenterId",
        "native_getConnectionState", "native_getTimeDifference", "native_isTestBackend"));

    private static final Set<String> BUILTINS = new HashSet<>(java.util.Arrays.asList(
        "exec", "eval", "compile", "execfile", "__import__", "getattr", "setattr", "delattr", "open", "chr", "globals",
        "vars", "__builtins__", "input"));

    private static final Set<String> EXEC_BUILTINS = new HashSet<>(java.util.Arrays.asList(
        "builtins.exec", "builtins.eval", "builtins.compile", "builtins.execfile"));

    private static final Set<String> KEYWORDS = new HashSet<>(java.util.Arrays.asList(
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
        "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not", "or",
        "pass", "raise", "return", "try", "while", "with", "yield", "match", "case", "self", "cls"));

    /** Words that make a hidden (encoded, concatenated, reversed) string suspicious. */
    private static final String[] HIDDEN_KEYWORDS = {
        "exec", "eval", "compile", "__import__", "import ", "subprocess", "os.system", "system(", "popen", "socket",
        "requests", "urllib", "http://", "https://", "tgnet", "userconfing", "cache4", "shared_prefs", "getsharedpreferences",
        "marshal", "pickle", "base64", "dexclassloader", "webhook", "api.telegram.org", "getruntime", "loadlibrary", "builtins",
        "authkey", "auth_key", "acceptlogintoken", "jclass"};

    /** Whole strings that are only ever hidden to escape a scanner ("o" + "s"). */
    private static final Set<String> HIDDEN_NAMES = new HashSet<>(java.util.Arrays.asList(
        "os", "sys", "exec", "eval", "system", "popen", "subprocess", "socket", "requests", "urllib", "marshal", "pickle",
        "builtins", "__import__", "getattr", "compile", "base64", "zlib", "ctypes", "jclass", "importlib", "__builtins__"));

    /** Function names that are dangerous when looked up by name (getattr(os, "system")). */
    private static final Set<String> DANGEROUS_ATTRS = new HashSet<>(java.util.Arrays.asList(
        "exec", "eval", "compile", "__import__", "system", "popen", "spawn", "loads", "load", "b64decode", "decompress",
        "getSharedPreferences", "getRuntime", "loadClass", "loadLibrary", "getDeclaredField", "setAccessible", "import_module",
        "open", "remove", "unlink", "rmtree", "urlopen", "post", "get", "connect", "socket", "fork", "execv", "execve"));

    /** Paths and names of the files that hold the Telegram session and settings. */
    private static final String[] SESSION_FILES = {
        "tgnet.dat", "userconfing", "cache4.db", "shared_prefs", "/data/data/", "/data/user/", "solegram_api", "solegram_secrets"};

    /** Settings and files of the plugin security itself. */
    private static final String[] TAMPER_STRINGS = {
        "pluginsBlockDangerous", "pluginsScanBeforeInstall", "pluginsSafeMode", "solegram_plugins", "solegram_plugin_security",
        "plugins_blocklist"};

    /** Where stolen data is typically sent: throwaway endpoints and chat webhooks. */
    private static final String[] SUSPICIOUS_HOSTS = {
        "discord.com/api/webhooks", "discordapp.com/api/webhooks", "api.telegram.org/bot", "pastebin.com", "paste.ee",
        "hastebin", "webhook.site", "requestbin", "pipedream.net", "ngrok", "trycloudflare.com", "transfer.sh", "anonfiles",
        "file.io", "0x0.st", "serveo.net", "loca.lt", "localtunnel", "bit.ly", "tinyurl.com", "cutt.ly", "is.gd",
        "interact.sh", "oast.", "burpcollaborator", "beeceptor", "hookbin", ".onion"};

    private static final String[] NETWORK_PACKAGES = {
        "requests", "httpx", "aiohttp", "websocket", "websockets", "websocket_client", "urllib3", "pycurl", "paramiko", "grpcio"};

    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|wss?|ftp)://([^\\s\"'<>/?#:]+)[^\\s\"'<>]*");
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern FQCN = Pattern.compile("^(?:java|javax|android|androidx|dalvik|okhttp3|org\\.telegram|com\\.solegram|de\\.robv)(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+$");
    private static final Pattern REQUIREMENT_NAME = Pattern.compile("^\\s*([A-Za-z0-9_.\\-]+)");
    // endregion

    // region Entry points

    public static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Report analyze(byte[] data) {
        String src = new String(data, StandardCharsets.UTF_8);
        Report report = analyze(src);
        report.sha256 = sha256(data);
        report.size = data.length;
        return report;
    }

    public static Report analyze(String src) {
        if (src.startsWith("﻿")) {
            src = src.substring(1);
        }
        Analyzer a = new Analyzer(src, 0);
        a.run();
        return a.report;
    }
    // endregion

    // region Tokenizer

    static final int T_NAME = 0;
    static final int T_OP = 1;
    static final int T_STRING = 2;
    static final int T_NUMBER = 3;

    static final class Token {
        final int type;
        /** NAME / OP / NUMBER: the text; STRING: the decoded value. */
        final String text;
        final int line;
        int rawLength;
        /** Hex, short unicode and octal escapes of ASCII characters: the classic way to hide a word. */
        int asciiEscapes;
        boolean bytes;
        /** Column of a name: 0 means module level (only those definitions shadow a builtin). */
        int col = -1;

        Token(int type, String text, int line) {
            this.type = type;
            this.text = text;
            this.line = line;
        }

        boolean is(int type, String text) {
            return this.type == type && this.text.equals(text);
        }

        boolean op(String text) {
            return type == T_OP && this.text.equals(text);
        }

        @Override
        public String toString() {
            return type + ":" + text + "@" + line;
        }
    }

    private static final String[] TWO_CHAR_OPS = {
        "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "->", "**", "//", "<<", ">>", ":=", "@="};

    /** A Python tokenizer good enough for static checks: comments dropped, strings decoded, f-string expressions kept as code. */
    static final class Lexer {
        private final String s;
        private final int n;
        private int pos;
        private int line;
        private int lineStart;
        private final ArrayList<Token> out;

        Lexer(String s, int line, ArrayList<Token> out) {
            this.s = s;
            this.n = s.length();
            this.line = line;
            this.out = out;
        }

        /** A lexer for the code inside an f-string: its names are never at module level. */
        private Lexer nested() {
            lineStart = Integer.MIN_VALUE / 2;
            return this;
        }

        static ArrayList<Token> tokenize(String src) {
            ArrayList<Token> out = new ArrayList<>();
            new Lexer(src, 1, out).run();
            return out;
        }

        void run() {
            while (pos < n) {
                char c = s.charAt(pos);
                if (c == '\n') {
                    line++;
                    pos++;
                    lineStart = pos;
                } else if (c <= ' ' || c == '﻿' || c == '\\') {
                    pos++;
                } else if (c == '#') {
                    while (pos < n && s.charAt(pos) != '\n') {
                        pos++;
                    }
                } else {
                    int quote = stringStart();
                    if (quote >= 0) {
                        readString(quote);
                    } else if (Character.isLetter(c) || c == '_') {
                        int start = pos;
                        while (pos < n && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                            pos++;
                        }
                        Token name = new Token(T_NAME, s.substring(start, pos), line);
                        name.col = start - lineStart;
                        out.add(name);
                    } else if (Character.isDigit(c) || (c == '.' && pos + 1 < n && Character.isDigit(s.charAt(pos + 1)))) {
                        int start = pos;
                        while (pos < n && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_' || s.charAt(pos) == '.')) {
                            pos++;
                        }
                        out.add(new Token(T_NUMBER, s.substring(start, pos), line));
                    } else {
                        String op = String.valueOf(c);
                        if (pos + 1 < n) {
                            String two = s.substring(pos, pos + 2);
                            for (String o : TWO_CHAR_OPS) {
                                if (o.equals(two)) {
                                    op = two;
                                    break;
                                }
                            }
                        }
                        pos += op.length();
                        out.add(new Token(T_OP, op, line));
                    }
                }
            }
        }

        /** Index of the opening quote when a string (with an optional r/b/u/f prefix) starts at pos, else -1. */
        private int stringStart() {
            int j = pos;
            while (j < n && j - pos < 2 && "rRbBuUfF".indexOf(s.charAt(j)) >= 0) {
                j++;
            }
            if (j < n && (s.charAt(j) == '"' || s.charAt(j) == '\'')) {
                return j;
            }
            return -1;
        }

        private void readString(int quoteIndex) {
            String prefix = s.substring(pos, quoteIndex).toLowerCase(Locale.ROOT);
            boolean raw = prefix.indexOf('r') >= 0;
            boolean bytes = prefix.indexOf('b') >= 0;
            boolean fstring = prefix.indexOf('f') >= 0;
            char q = s.charAt(quoteIndex);
            String triple = "" + q + q + q;
            boolean isTriple = s.startsWith(triple, quoteIndex);
            int start = pos;
            int startLine = line;
            int i = quoteIndex + (isTriple ? 3 : 1);
            StringBuilder sb = new StringBuilder();
            int escapes = 0;
            ArrayList<String> expressions = null;
            ArrayList<Integer> expressionLines = null;
            while (i < n) {
                char c = s.charAt(i);
                if (c == q && (!isTriple || s.startsWith(triple, i))) {
                    i += isTriple ? 3 : 1;
                    break;
                }
                if (c == '\n') {
                    line++;
                    if (!isTriple) {
                        // unterminated: Python would refuse it, the scan just moves on
                        break;
                    }
                }
                if (c == '\\' && i + 1 < n) {
                    char d = s.charAt(i + 1);
                    if (raw) {
                        sb.append(c).append(d);
                        if (d == '\n') {
                            line++;
                        }
                        i += 2;
                        continue;
                    }
                    i += 2;
                    switch (d) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'a': sb.append('\u0007'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'v': sb.append('\u000B'); break;
                        case '\\': sb.append('\\'); break;
                        case '\'': sb.append('\''); break;
                        case '"': sb.append('"'); break;
                        case '\n': line++; break;
                        case 'x': {
                            int v = hex(i, 2);
                            if (v >= 0) {
                                sb.append((char) v);
                                if (v < 0x80) {
                                    escapes++;
                                }
                                i += 2;
                            } else {
                                sb.append("\\x");
                            }
                            break;
                        }
                        case 'u': {
                            int v = bytes ? -1 : hex(i, 4);
                            if (v >= 0) {
                                sb.append((char) v);
                                if (v < 0x80) {
                                    escapes++;
                                }
                                i += 4;
                            } else {
                                sb.append("\\u");
                            }
                            break;
                        }
                        case 'U': {
                            int v = bytes ? -1 : hex(i, 8);
                            if (v >= 0 && Character.isValidCodePoint(v)) {
                                sb.appendCodePoint(v);
                                i += 8;
                            } else {
                                sb.append("\\U");
                            }
                            break;
                        }
                        case 'N': {
                            int close = s.indexOf('}', i);
                            if (!bytes && i < n && s.charAt(i) == '{' && close > 0 && close - i < 80) {
                                sb.append('?');
                                i = close + 1;
                            } else {
                                sb.append("\\N");
                            }
                            break;
                        }
                        default:
                            if (d >= '0' && d <= '7') {
                                int v = d - '0';
                                int k = 0;
                                while (k < 2 && i < n && s.charAt(i) >= '0' && s.charAt(i) <= '7') {
                                    v = v * 8 + (s.charAt(i) - '0');
                                    i++;
                                    k++;
                                }
                                sb.append((char) v);
                                if (v < 0x80) {
                                    escapes++;
                                }
                            } else {
                                sb.append('\\').append(d);
                            }
                    }
                    continue;
                }
                if (fstring && c == '{') {
                    if (i + 1 < n && s.charAt(i + 1) == '{') {
                        sb.append('{');
                        i += 2;
                        continue;
                    }
                    int depth = 1;
                    int j = i + 1;
                    while (j < n && depth > 0) {
                        char e = s.charAt(j);
                        if (e == '{') {
                            depth++;
                        } else if (e == '}') {
                            depth--;
                        } else if (e == '\n' && !isTriple) {
                            break;
                        }
                        j++;
                    }
                    if (expressions == null) {
                        expressions = new ArrayList<>();
                        expressionLines = new ArrayList<>();
                    }
                    expressions.add(s.substring(i + 1, Math.max(i + 1, depth == 0 ? j - 1 : j)));
                    expressionLines.add(line);
                    sb.append("{}");
                    i = j;
                    continue;
                }
                if (fstring && c == '}' && i + 1 < n && s.charAt(i + 1) == '}') {
                    sb.append('}');
                    i += 2;
                    continue;
                }
                sb.append(c);
                i++;
            }
            Token t = new Token(T_STRING, sb.toString(), startLine);
            t.rawLength = i - start;
            t.asciiEscapes = escapes;
            t.bytes = bytes;
            out.add(t);
            pos = i;
            if (expressions != null) {
                // the code inside f"{...}" runs like any other code
                for (int k = 0; k < expressions.size(); k++) {
                    new Lexer(expressions.get(k), expressionLines.get(k), out).nested().run();
                }
            }
        }

        private int hex(int from, int count) {
            if (from + count > n) {
                return -1;
            }
            int v = 0;
            for (int k = 0; k < count; k++) {
                int d = Character.digit(s.charAt(from + k), 16);
                if (d < 0) {
                    return -1;
                }
                v = v * 16 + d;
            }
            return v;
        }
    }
    // endregion

    // region Analyzer

    private static final class Analyzer {
        final String[] lines;
        final ArrayList<Token> t;
        final Report report = new Report();
        final LinkedHashMap<String, Finding> findings = new LinkedHashMap<>();
        /** Local name -> what it refers to (module, class, function), from imports and simple assignments. */
        final HashMap<String, String> aliases = new HashMap<>();
        /** Names bound with "import x": getattr on them is a module lookup. */
        final HashSet<String> moduleNames = new HashSet<>();
        /** Names the plugin defines itself: they shadow builtins (a method called "eval" is not eval). */
        final HashSet<String> defined = new HashSet<>();
        final LinkedHashSet<String> hosts = new LinkedHashSet<>();
        /** Lines of exec calls whose argument is decoded / deserialized / hidden data. */
        final ArrayList<Integer> hiddenExecLines = new ArrayList<>();
        /** Tokens already judged as part of a hidden payload (so a blob is not reported twice). */
        final HashSet<Token> handledStrings = new HashSet<>();
        boolean blobIsCode;
        int chrCalls;
        int firstChrLine;

        /** Hidden code found inside the plugin is analyzed too, but not endlessly. */
        final int nesting;

        Analyzer(String src, int nesting) {
            this.nesting = nesting;
            lines = src.split("\n", -1);
            t = Lexer.tokenize(src);
            report.lines = lines.length;
        }

        void run() {
            collectBindings();
            scanUsages();
            scanStrings();
            scanNumbers();
            if (chrCalls >= 12) {
                add("code_chr", firstChrLine, chrCalls + " × chr()");
            }
            combine();
            finish();
        }

        // region helpers

        Token at(int i) {
            return i >= 0 && i < t.size() ? t.get(i) : null;
        }

        boolean opAt(int i, String op) {
            Token k = at(i);
            return k != null && k.op(op);
        }

        String snippet(int line) {
            if (line < 1 || line > lines.length) {
                return "";
            }
            String s = lines[line - 1].trim();
            if (s.length() > MAX_SNIPPET) {
                s = s.substring(0, MAX_SNIPPET - 1) + "…";
            }
            return s;
        }

        void add(String rule, int line, String detail) {
            int[] def = RULES.get(rule);
            if (def == null) {
                return;
            }
            Finding f = findings.get(rule);
            if (f == null) {
                f = new Finding(rule, def[0], def[1]);
                findings.put(rule, f);
            }
            boolean newLine = true;
            for (Evidence e : f.evidence) {
                if (e.line == line) {
                    newLine = false;
                    break;
                }
            }
            if (newLine) {
                f.count++;
                if (f.evidence.size() < MAX_EVIDENCE && line > 0) {
                    f.evidence.add(new Evidence(line, snippet(line)));
                }
            }
            if (detail != null && !detail.isEmpty() && f.details.size() < MAX_DETAILS && !f.details.contains(detail)) {
                f.details.add(detail);
            }
        }

        boolean has(String rule) {
            return findings.containsKey(rule);
        }

        int firstLine(String rule) {
            Finding f = findings.get(rule);
            return f != null && !f.evidence.isEmpty() ? f.evidence.get(0).line : 0;
        }

        /** Index of the token closing the bracket opened at {@code open}, or the last token. */
        int matching(int open) {
            int depth = 0;
            for (int i = open; i < t.size(); i++) {
                Token k = t.get(i);
                if (k.type == T_OP) {
                    if (k.text.equals("(") || k.text.equals("[") || k.text.equals("{")) {
                        depth++;
                    } else if (k.text.equals(")") || k.text.equals("]") || k.text.equals("}")) {
                        depth--;
                        if (depth == 0) {
                            return i;
                        }
                    }
                }
            }
            return t.size() - 1;
        }

        /** Splits the arguments of the call whose "(" is at {@code open} into token ranges [from, to). */
        ArrayList<int[]> arguments(int open) {
            ArrayList<int[]> args = new ArrayList<>();
            int close = matching(open);
            int depth = 0;
            int from = open + 1;
            for (int i = open + 1; i < close; i++) {
                Token k = t.get(i);
                if (k.type == T_OP) {
                    if (k.text.equals("(") || k.text.equals("[") || k.text.equals("{")) {
                        depth++;
                    } else if (k.text.equals(")") || k.text.equals("]") || k.text.equals("}")) {
                        depth--;
                    } else if (k.text.equals(",") && depth == 0) {
                        args.add(new int[]{from, i});
                        from = i + 1;
                    }
                }
            }
            if (from < close) {
                args.add(new int[]{from, close});
            }
            return args;
        }

        /** Value of an argument made only of string literals joined with + (or adjacent), else null. */
        String staticString(int[] range) {
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            for (int i = range[0]; i < range[1]; i++) {
                Token k = t.get(i);
                if (k.type == T_STRING) {
                    sb.append(k.text);
                    any = true;
                } else if (!(k.op("+") || k.op("(") || k.op(")"))) {
                    return null;
                }
            }
            return any ? sb.toString() : null;
        }

        int stringCount(int[] range) {
            int c = 0;
            for (int i = range[0]; i < range[1]; i++) {
                if (t.get(i).type == T_STRING) {
                    c++;
                }
            }
            return c;
        }

        /** Resolves the first name of a dotted chain: alias, builtin, local or unknown ("?."). */
        String resolveHead(String name) {
            String alias = aliases.get(name);
            if (alias != null) {
                return alias;
            }
            if (defined.contains(name)) {
                return null;
            }
            if (BUILTINS.contains(name)) {
                return "builtins." + name;
            }
            return "?." + name;
        }
        // endregion

        // region pass 1: imports, definitions, aliases

        void collectBindings() {
            int depth = 0;
            for (int i = 0; i < t.size(); i++) {
                Token k = t.get(i);
                if (k.type == T_OP) {
                    if (k.text.equals("(") || k.text.equals("[") || k.text.equals("{")) {
                        depth++;
                    } else if (k.text.equals(")") || k.text.equals("]") || k.text.equals("}")) {
                        depth = Math.max(0, depth - 1);
                    }
                    continue;
                }
                if (k.type != T_NAME) {
                    continue;
                }
                Token prev = at(i - 1);
                boolean attribute = prev != null && prev.op(".");
                if (k.text.equals("import") && !attribute) {
                    i = parseImport(i);
                } else if (k.text.equals("from") && !attribute) {
                    i = parseFromImport(i);
                } else if ((k.text.equals("def") || k.text.equals("class")) && !attribute) {
                    Token name = at(i + 1);
                    if (name != null && name.type == T_NAME) {
                        // a method called "eval" does not shadow the builtin; a module-level function does
                        if (k.col == 0) {
                            defined.add(name.text);
                        }
                        if (k.text.equals("def")) {
                            onDefinition(name);
                        }
                    }
                } else if (!attribute && depth == 0 && opAt(i + 1, "=") && !KEYWORDS.contains(k.text)) {
                    if (k.text.equals("__requirements__")) {
                        parseRequirements(i + 2);
                    }
                    if (!bindAlias(k.text, i + 2) && k.col == 0) {
                        defined.add(k.text);
                    }
                }
            }
        }

        int parseImport(int i) {
            int line = t.get(i).line;
            int j = i + 1;
            while (j < t.size()) {
                StringBuilder name = new StringBuilder();
                String first = null;
                while (j < t.size() && t.get(j).type == T_NAME) {
                    if (first == null) {
                        first = t.get(j).text;
                    }
                    name.append(t.get(j).text);
                    if (opAt(j + 1, ".") && at(j + 2) != null && at(j + 2).type == T_NAME) {
                        name.append('.');
                        j += 2;
                    } else {
                        j++;
                        break;
                    }
                }
                if (first == null) {
                    break;
                }
                String module = name.toString();
                if (at(j) != null && at(j).is(T_NAME, "as") && at(j + 1) != null && at(j + 1).type == T_NAME) {
                    aliases.put(at(j + 1).text, module);
                    moduleNames.add(at(j + 1).text);
                    j += 2;
                } else {
                    aliases.put(first, first);
                    moduleNames.add(first);
                }
                useQualified(module, line, true);
                if (opAt(j, ",") && at(j + 1) != null && at(j + 1).line == line) {
                    j++;
                    continue;
                }
                break;
            }
            return j - 1;
        }

        int parseFromImport(int i) {
            int line = t.get(i).line;
            int j = i + 1;
            StringBuilder module = new StringBuilder();
            while (j < t.size() && (t.get(j).type == T_NAME || t.get(j).op("."))) {
                if (t.get(j).is(T_NAME, "import")) {
                    break;
                }
                module.append(t.get(j).text);
                j++;
            }
            if (at(j) == null || !at(j).is(T_NAME, "import")) {
                return i;
            }
            String mod = module.toString();
            boolean relative = mod.startsWith(".");
            j++;
            boolean paren = opAt(j, "(");
            if (paren) {
                j++;
            }
            if (!relative) {
                useQualified(mod, line, true);
            }
            while (j < t.size()) {
                Token k = t.get(j);
                if (k.op("*")) {
                    j++;
                } else if (k.type == T_NAME) {
                    String local = k.text;
                    String qualified = mod + "." + k.text;
                    j++;
                    if (at(j) != null && at(j).is(T_NAME, "as") && at(j + 1) != null && at(j + 1).type == T_NAME) {
                        local = at(j + 1).text;
                        j += 2;
                    }
                    if (!relative) {
                        aliases.put(local, qualified);
                        useQualified(qualified, k.line, true);
                    } else {
                        defined.add(local);
                    }
                } else {
                    break;
                }
                if (opAt(j, ",")) {
                    j++;
                    if (!paren && at(j) != null && at(j).line != line) {
                        break;
                    }
                    continue;
                }
                break;
            }
            if (paren && opAt(j, ")")) {
                j++;
            }
            return j - 1;
        }

        /** __requirements__ = ["requests", ...]: packages installed from PyPI at load time. */
        void parseRequirements(int i) {
            Token k = at(i);
            if (k == null) {
                return;
            }
            int end = i;
            if (k.op("[") || k.op("(")) {
                end = matching(i);
            }
            for (int j = i; j <= end && j < t.size(); j++) {
                Token s = t.get(j);
                if (s.type != T_STRING) {
                    continue;
                }
                Matcher m = REQUIREMENT_NAME.matcher(s.text);
                if (!m.find()) {
                    continue;
                }
                String pkg = m.group(1).toLowerCase(Locale.ROOT).replace('-', '_');
                add("code_packages", s.line, m.group(1));
                for (String net : NETWORK_PACKAGES) {
                    if (pkg.equals(net)) {
                        add("net_python", s.line, m.group(1));
                    }
                }
            }
        }

        /**
         * name = jclass("java.lang.Runtime") / find_class(...) / __import__("os") / os.system / exec:
         * the name becomes an alias of what it was given, so later calls through it are recognized.
         */
        boolean bindAlias(String name, int i) {
            Token k = at(i);
            if (k == null || k.type != T_NAME || KEYWORDS.contains(k.text) || opAt(i - 2, ".")) {
                return false;
            }
            int j = i;
            StringBuilder chain = new StringBuilder(k.text);
            while (opAt(j + 1, ".") && at(j + 2) != null && at(j + 2).type == T_NAME) {
                chain.append('.').append(at(j + 2).text);
                j += 2;
            }
            String head = chain.toString();
            String resolved;
            int dot = head.indexOf('.');
            String first = dot < 0 ? head : head.substring(0, dot);
            String base = aliases.containsKey(first) ? aliases.get(first) : BUILTINS.contains(first) && !defined.contains(first) ? "builtins." + first : null;
            if (base == null) {
                return false;
            }
            resolved = dot < 0 ? base : base + head.substring(dot);
            if (opAt(j + 1, "(")) {
                // only class / module loaders with a literal name produce an alias
                if (!isLoader(resolved)) {
                    return false;
                }
                ArrayList<int[]> args = arguments(j + 1);
                String value = args.isEmpty() ? null : staticString(args.get(0));
                if (value == null) {
                    return false;
                }
                aliases.put(name, value);
                if (!resolved.endsWith("jclass") && !resolved.endsWith("find_class")) {
                    moduleNames.add(name);
                }
                return true;
            }
            aliases.put(name, resolved);
            return true;
        }

        boolean isLoader(String q) {
            return q.equals("java.jclass") || q.endsWith(".find_class") || q.equals("builtins.__import__")
                || q.equals("importlib.import_module") || q.endsWith(".jclass");
        }

        void onDefinition(Token name) {
            switch (name.text) {
                case "on_update_hook":
                case "on_updates_hook":
                    add("msg_updates", name.line, null);
                    break;
                case "on_send_message_hook":
                    add("msg_outgoing", name.line, null);
                    break;
                case "pre_request_hook":
                case "post_request_hook":
                    add("msg_requests", name.line, null);
                    break;
            }
        }
        // endregion

        // region pass 2: uses

        void scanUsages() {
            int depth = 0;
            for (int i = 0; i < t.size(); i++) {
                Token k = t.get(i);
                if (k.type == T_OP) {
                    if (k.text.equals("(") || k.text.equals("[") || k.text.equals("{")) {
                        depth++;
                    } else if (k.text.equals(")") || k.text.equals("]") || k.text.equals("}")) {
                        depth = Math.max(0, depth - 1);
                    }
                    continue;
                }
                if (k.type != T_NAME) {
                    continue;
                }
                if (k.text.startsWith("TL_")) {
                    useTl(i);
                }
                Token prev = at(i - 1);
                if (prev != null && prev.op(".")) {
                    useAttribute(k, i);
                    continue;
                }
                if (KEYWORDS.contains(k.text)) {
                    if (k.text.equals("import") || k.text.equals("from")) {
                        // the import line itself was handled in pass 1: skip to its end
                        int line = k.line;
                        while (i + 1 < t.size() && t.get(i + 1).line == line && !t.get(i + 1).op(";")) {
                            i++;
                        }
                    }
                    continue;
                }
                if (prev != null && (prev.is(T_NAME, "def") || prev.is(T_NAME, "class"))) {
                    continue;
                }
                if (opAt(i + 1, "=")) {
                    // assignment target or keyword argument: a binding, not a use
                    continue;
                }
                // a dotted chain: os.path.join
                int j = i;
                StringBuilder chain = new StringBuilder(k.text);
                while (opAt(j + 1, ".") && at(j + 2) != null && at(j + 2).type == T_NAME) {
                    chain.append('.').append(at(j + 2).text);
                    j += 2;
                }
                String text = chain.toString();
                int dot = text.indexOf('.');
                String head = resolveHead(dot < 0 ? text : text.substring(0, dot));
                if (head == null) {
                    continue;
                }
                String qualified = dot < 0 ? head : head + text.substring(dot);
                useQualified(qualified, k.line, false);
                if (opAt(j + 1, "(")) {
                    onCall(qualified, j + 1, k.line);
                } else if (EXEC_BUILTINS.contains(qualified)) {
                    // map(eval, items), run = exec: passed around instead of called
                    add("code_exec", k.line, qualified.substring(9));
                }
            }
        }

        void useAttribute(Token k, int i) {
            String rule = ATTR_MAP.get(k.text);
            if (rule != null) {
                add(rule, k.line, k.text);
            } else if (k.text.startsWith("native_") && !HARMLESS_NATIVES.contains(k.text)) {
                add("sec_auth_internals", k.line, k.text);
            }
            if (opAt(i + 1, "(")) {
                switch (k.text) {
                    case "exec":
                        // Runtime.getRuntime().exec("..."); getRuntime().exit(0) only restarts the app
                        for (int j = i - 1; j >= Math.max(0, i - 8); j--) {
                            if (t.get(j).is(T_NAME, "getRuntime")) {
                                add("sys_shell", k.line, "Runtime.exec");
                                break;
                            }
                        }
                        break;
                    case "add_hook":
                        onAddHook(i + 1);
                        break;
                    case "__getattribute__":
                        onGetattr(i + 1, k.line, true);
                        break;
                    case "decode": {
                        // "...".decode("rot13") / codecs-style decoding of a literal
                        ArrayList<int[]> args = arguments(i + 1);
                        String codec = args.isEmpty() ? null : staticString(args.get(0));
                        if (codec != null && codec.toLowerCase(Locale.ROOT).replace("_", "").contains("rot13")) {
                            add("code_decode", k.line, "rot13");
                        }
                        break;
                    }
                }
            }
        }

        /** Matches a qualified name against the rules: whole-segment prefixes, SDK functions. */
        void useQualified(String q, int line, boolean fromImport) {
            if (q == null) {
                return;
            }
            String probe = q;
            while (true) {
                String rule = QNAME_MAP.get(probe);
                if (rule != null) {
                    add(rule, line, displayName(probe));
                    break;
                }
                int dot = probe.lastIndexOf('.');
                if (dot <= 0) {
                    break;
                }
                probe = probe.substring(0, dot);
            }
            int dot = q.lastIndexOf('.');
            if (dot > 0) {
                String module = q.substring(0, dot);
                String last = q.substring(dot + 1);
                if (module.equals("?") || SDK_MODULES.contains(module)) {
                    String rule = SDK_MAP.get(last);
                    if (rule != null) {
                        add(rule, line, last);
                    }
                }
                // from org.telegram.tgnet.TLRPC import TL_auth_acceptLoginToken
                if (fromImport && last.startsWith("TL_")) {
                    useTlName(last, line);
                }
            }
            if (q.equals("builtins.__builtins__")) {
                add("code_getattr", line, "__builtins__");
            }
        }

        String displayName(String q) {
            if (q.startsWith("builtins.")) {
                return q.substring(9);
            }
            return q;
        }

        void useTl(int i) {
            Token k = t.get(i);
            String name = k.text;
            // TL_account.updatePasswordSettings (nested request classes of newer layers)
            if (opAt(i + 1, ".") && at(i + 2) != null && at(i + 2).type == T_NAME && !at(i + 2).text.isEmpty()
                && Character.isLowerCase(at(i + 2).text.charAt(0))) {
                name = name + "_" + at(i + 2).text;
            }
            useTlName(name, k.line);
        }

        void useTlName(String name, int line) {
            String rule = TL_MAP.get(name);
            if (rule == null && name.startsWith("TL_payments_")) {
                String m = name.substring("TL_payments_".length());
                if (m.startsWith("send") || m.startsWith("transfer") || m.startsWith("convert") || m.startsWith("upgrade")
                    || m.startsWith("buy") || m.startsWith("assign") || m.startsWith("launch")) {
                    rule = "act_payments";
                }
            }
            if (rule == null && name.startsWith("TL_update") && !name.startsWith("TL_updates_")) {
                rule = "msg_updates";
            }
            if (rule != null) {
                add(rule, line, tlDisplay(name));
            }
        }

        String tlDisplay(String name) {
            String s = name.startsWith("TL_") ? name.substring(3) : name;
            int u = s.indexOf('_');
            return u > 0 ? s.substring(0, u) + "." + s.substring(u + 1) : s;
        }

        void onCall(String q, int open, int line) {
            switch (q) {
                case "builtins.exec":
                case "builtins.eval":
                case "builtins.compile":
                case "builtins.execfile":
                    onExec(q, open, line);
                    break;
                case "builtins.__import__":
                case "importlib.import_module":
                    onImportCall(open, line);
                    break;
                case "builtins.getattr":
                case "builtins.setattr":
                case "builtins.delattr":
                case "operator.attrgetter":
                case "operator.methodcaller":
                    onGetattr(open, line, false);
                    break;
                case "builtins.open":
                    add("file_io", line, "open");
                    break;
                case "builtins.chr":
                    if (chrCalls++ == 0) {
                        firstChrLine = line;
                    }
                    break;
                case "builtins.globals":
                case "builtins.vars": {
                    int close = matching(open);
                    Token after = at(close + 1);
                    ArrayList<int[]> args = arguments(open);
                    boolean moduleArg = false;
                    if (!args.isEmpty()) {
                        Token a = t.get(args.get(0)[0]);
                        // vars(math) is a calculator; vars(os) / vars(builtins) is a lookup by name
                        String module = a.type == T_NAME ? aliases.get(a.text) : null;
                        moduleArg = a.type == T_NAME && (a.text.equals("__builtins__") || moduleNames.contains(a.text) && module != null
                            && (module.equals("builtins") || module.equals("os") || module.equals("sys") || module.equals("subprocess") || module.equals("importlib")));
                    }
                    if ((after != null && after.op("[")) || moduleArg) {
                        add("code_getattr", line, q.substring(9) + "()");
                    }
                    break;
                }
                default:
                    if (q.equals("java.jclass") || q.endsWith(".find_class") || q.endsWith(".jclass")) {
                        // jclass("java.lang.Runtime").getRuntime(): the class named by the literal is used
                        ArrayList<int[]> args = arguments(open);
                        String cls = args.isEmpty() ? null : staticString(args.get(0));
                        if (cls != null) {
                            useQualified(cls, line, false);
                            if (stringCount(args.get(0)) > 1) {
                                hidden(cls, line, true);
                            }
                        } else if (!args.isEmpty()) {
                            add("code_dynamic_import", line, "jclass(…)");
                        }
                    } else if (q.startsWith("builtins.") && q.endsWith("Loader")) {
                        add("sys_dex", line, null);
                    }
            }
        }

        void onExec(String q, int open, int line) {
            ArrayList<int[]> args = arguments(open);
            String name = q.substring(9);
            if (!args.isEmpty() && staticString(args.get(0)) != null) {
                add("code_exec_static", line, name);
                return;
            }
            add("code_exec", line, name);
            int close = matching(open);
            for (int i = open + 1; i < close; i++) {
                Token k = t.get(i);
                if (k.type == T_NAME) {
                    String r = ATTR_MAP.get(k.text);
                    String text = k.text;
                    boolean decoding = text.endsWith("decode") || text.equals("decompress") || text.equals("loads")
                        || text.equals("fromhex") || text.equals("unhexlify") || text.equals("urlopen") || text.equals("get")
                        || text.equals("read") || text.equals("text") || text.equals("content");
                    if (decoding || r != null && r.startsWith("net_")) {
                        hiddenExecLines.add(line);
                        break;
                    }
                } else if (k.type == T_STRING && k.text.length() >= 64) {
                    hiddenExecLines.add(line);
                    break;
                }
            }
        }

        void onImportCall(int open, int line) {
            ArrayList<int[]> args = arguments(open);
            if (args.isEmpty()) {
                return;
            }
            String module = staticString(args.get(0));
            if (module == null) {
                add("code_dynamic_import", line, null);
                return;
            }
            add("code_import", line, module);
            if (stringCount(args.get(0)) > 1) {
                hidden(module, line, true);
            }
            useQualified(module, line, false);
            // __import__("os").system(...)
            int close = matching(open);
            if (opAt(close + 1, ".") && at(close + 2) != null && at(close + 2).type == T_NAME) {
                useQualified(module + "." + at(close + 2).text, line, false);
            }
        }

        void onGetattr(int open, int line, boolean method) {
            ArrayList<int[]> args = arguments(open);
            int nameIndex = method ? 0 : 1;
            if (args.size() <= nameIndex) {
                return;
            }
            int[] target = method ? null : args.get(0);
            String targetModule = null;
            boolean targetIsModule = false;
            if (target != null) {
                Token first = t.get(target[0]);
                if (first.type == T_NAME) {
                    if (moduleNames.contains(first.text)) {
                        targetModule = aliases.get(first.text);
                        targetIsModule = true;
                    } else if (first.text.equals("__builtins__") || first.text.equals("builtins")
                        || first.text.equals("__import__") || first.text.equals("globals") || first.text.equals("sys")) {
                        targetModule = "builtins";
                        targetIsModule = true;
                    } else if (aliases.containsKey(first.text) && FQCN.matcher(aliases.get(first.text)).matches()) {
                        // a Java class loaded with jclass()
                        targetModule = aliases.get(first.text);
                    }
                }
            }
            String attr = staticString(args.get(nameIndex));
            if (attr == null) {
                if (targetIsModule) {
                    add("code_getattr", line, "getattr(" + displayName(targetModule) + ", …)");
                }
                return;
            }
            String rule = ATTR_MAP.get(attr);
            if (rule != null) {
                add(rule, line, attr);
            }
            if (targetModule != null) {
                useQualified(targetModule + "." + attr, line, false);
            }
            boolean concatenated = stringCount(args.get(nameIndex)) > 1;
            if (concatenated) {
                hidden(attr, line, true);
            }
            if (targetIsModule && DANGEROUS_ATTRS.contains(attr) || concatenated) {
                add("code_getattr", line, "\"" + attr + "\"");
            }
        }

        /** self.add_hook("TL_updateNewMessage"): what the plugin intercepts. */
        void onAddHook(int open) {
            ArrayList<int[]> args = arguments(open);
            if (args.isEmpty()) {
                return;
            }
            String name = staticString(args.get(0));
            int line = t.get(open).line;
            if (name == null) {
                add("msg_requests", line, null);
                return;
            }
            if (name.startsWith("TL_update") || name.startsWith("update")) {
                add("msg_updates", line, name.startsWith("TL_") ? name.substring(3) : name);
            } else {
                add("msg_requests", line, tlDisplay(name));
                // hooking the 2FA / login requests is as sensitive as calling them
                String rule = TL_MAP.get(name);
                if (rule != null && rule.startsWith("sec_")) {
                    add(rule, line, tlDisplay(name));
                }
            }
        }
        // endregion

        // region pass 3: literals

        void scanStrings() {
            for (int i = 0; i < t.size(); i++) {
                Token k = t.get(i);
                if (k.type != T_STRING) {
                    continue;
                }
                checkString(k.text, k.line, false);
                if (FQCN.matcher(k.text).matches()) {
                    useQualified(k.text, k.line, false);
                }
                if (!handledStrings.contains(k)) {
                    checkEncoded(k);
                }
                // "sub" + "process", "sub" "process"
                Token prev = at(i - 1);
                boolean continuation = prev != null && (prev.type == T_STRING || prev.op("+") && at(i - 2) != null && at(i - 2).type == T_STRING);
                if (!continuation) {
                    StringBuilder joined = new StringBuilder(k.text);
                    int pieces = 1;
                    int j = i + 1;
                    while (j < t.size()) {
                        if (t.get(j).type == T_STRING) {
                            joined.append(t.get(j).text);
                            pieces++;
                            j++;
                        } else if (t.get(j).op("+") && at(j + 1) != null && at(j + 1).type == T_STRING) {
                            joined.append(at(j + 1).text);
                            pieces++;
                            j += 2;
                        } else {
                            break;
                        }
                    }
                    if (pieces > 1) {
                        String value = joined.toString();
                        boolean pieceHas = false;
                        for (int p = i; p < j; p++) {
                            if (t.get(p).type == T_STRING && suspiciousText(t.get(p).text) != null) {
                                pieceHas = true;
                                break;
                            }
                        }
                        if (!pieceHas) {
                            hidden(value, k.line, true);
                        }
                    }
                }
                // "ssecorpbus"[::-1]
                if (opAt(i + 1, "[") && opAt(i + 2, ":") && opAt(i + 3, ":") && opAt(i + 4, "-")
                    && at(i + 5) != null && at(i + 5).is(T_NUMBER, "1") && opAt(i + 6, "]")) {
                    hidden(new StringBuilder(k.text).reverse().toString(), k.line, true);
                }
            }
        }

        /** Things a string literal reveals by itself: URLs, session files, security settings. */
        void checkString(String value, int line, boolean revealed) {
            String lower = value.toLowerCase(Locale.ROOT);
            Matcher m = URL.matcher(value);
            while (m.find()) {
                String host = m.group(1).toLowerCase(Locale.ROOT);
                if (host.isEmpty()) {
                    continue;
                }
                if (hosts.size() < 16) {
                    hosts.add(host);
                }
                String url = m.group().toLowerCase(Locale.ROOT);
                boolean suspicious = IPV4.matcher(host).matches() && !host.startsWith("127.") && !host.startsWith("10.") && !host.startsWith("192.168.");
                for (String s : SUSPICIOUS_HOSTS) {
                    if (url.contains(s)) {
                        suspicious = true;
                        break;
                    }
                }
                if (suspicious) {
                    add("net_suspicious", line, host);
                }
            }
            for (String s : SESSION_FILES) {
                if (lower.contains(s)) {
                    add("sec_session_files", line, s);
                }
            }
            for (String s : TAMPER_STRINGS) {
                if (value.contains(s)) {
                    add("sec_tamper", line, s);
                }
            }
            if (lower.contains("application/vnd.android.package-archive")) {
                add("sys_install", line, "APK");
            }
            if (lower.contains("/sdcard") || lower.contains("/storage/emulated")) {
                add("file_shared_storage", line, null);
            }
            if (revealed && FQCN.matcher(value).matches()) {
                useQualified(value, line, false);
            }
        }

        /** The dangerous word a text contains, or null. */
        String suspiciousText(String value) {
            String lower = value.toLowerCase(Locale.ROOT);
            if (HIDDEN_NAMES.contains(lower.trim())) {
                return lower.trim();
            }
            for (String w : HIDDEN_KEYWORDS) {
                if (lower.contains(w)) {
                    return w;
                }
            }
            return null;
        }

        /** A string that was built to be unreadable: reported with what it really says. */
        void hidden(String value, int line, boolean report) {
            if (suspiciousText(value) == null) {
                return;
            }
            if (report) {
                add("code_hidden_string", line, quote(value));
            }
            checkString(value, line, true);
            String name = value.trim();
            if (name.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
                // __import__(b64decode("cmVxdWVzdHM=")) still imports requests
                useQualified(name, line, false);
            }
        }

        String quote(String value) {
            String v = value.replace('\n', ' ').trim();
            if (v.length() > 48) {
                v = v.substring(0, 47) + "…";
            }
            return "«" + v + "»";
        }

        /** Base64 / hex / compressed / escaped literals: decoded and judged by their content. */
        void checkEncoded(Token k) {
            String v = k.text;
            int len = v.length();
            // heavy \x41 escaping of plain ASCII text: nobody writes code like that by hand
            if (k.asciiEscapes >= 8 && k.asciiEscapes * 4 >= k.rawLength * 0.6) {
                if (suspiciousText(v) != null && !k.bytes) {
                    hidden(v, k.line, true);
                } else if (k.bytes && k.asciiEscapes >= 256) {
                    classifyBinary(v.getBytes(StandardCharsets.ISO_8859_1), k.line, true);
                } else if (!k.bytes && k.asciiEscapes >= 16) {
                    add("code_escapes", k.line, k.asciiEscapes + " × \\x");
                }
                return;
            }
            if (len < 12) {
                return;
            }
            String compact = len >= 256 ? v.replaceAll("\\s+", "") : v;
            byte[] decoded = null;
            if (isHex(compact) && compact.length() >= 16 && compact.length() % 2 == 0) {
                decoded = fromHex(compact);
            } else if (isBase64(compact)) {
                decoded = fromBase64(compact);
            }
            if (decoded != null && decoded.length > 0) {
                classifyBinary(decoded, k.line, compact.length() >= 512);
                return;
            }
            // a long random-looking literal in a custom encoding
            if (len >= 2000 && compact.length() == len && !looksStructured(v) && entropy(v) >= 5.0) {
                add("code_blob", k.line, kb(len));
            }
        }

        void classifyBinary(byte[] data, int line, boolean long_) {
            byte[] plain = inflate(data);
            boolean compressed = plain != null;
            if (plain == null) {
                plain = data;
            }
            if (isMedia(plain)) {
                return;
            }
            if (isText(plain)) {
                String text = new String(plain, StandardCharsets.UTF_8);
                if (looksLikeCode(text)) {
                    add(long_ || compressed ? "code_blob" : "code_hidden_string", line, long_ || compressed ? kb(plain.length) : quote(text));
                    blobIsCode = true;
                    checkString(text, line, true);
                    if (nesting < 2) {
                        // what the hidden code does is reported at the line that hides it
                        Analyzer inner = new Analyzer(text, nesting + 1);
                        inner.run();
                        for (Finding f : inner.report.findings) {
                            if (!f.isCombo()) {
                                for (String d : f.details.isEmpty() ? java.util.Collections.singletonList((String) null) : f.details) {
                                    add(f.rule, line, d);
                                }
                            }
                        }
                        hosts.addAll(inner.report.hosts);
                    }
                } else if (suspiciousText(text) != null) {
                    hidden(text, line, true);
                }
                return;
            }
            if (long_ || compressed && data.length >= 128) {
                add("code_blob", line, kb(data.length));
            }
        }
        // endregion

        void scanNumbers() {
            for (Token k : t) {
                if (k.type == T_NUMBER && (k.text.equals("777000") || k.text.equals("42777"))) {
                    add("sec_login_codes", k.line, k.text);
                }
            }
        }

        // region verdict

        /** Combinations that turn individual capabilities into a stealer's toolkit. */
        void combine() {
            boolean network = has("net_python") || has("net_java") || has("net_webview");
            boolean readsMessages = has("msg_updates") || has("msg_outgoing") || has("msg_requests") || has("msg_read")
                || has("msg_contacts") || has("msg_database") || has("sec_phone") || has("sec_clipboard");
            boolean secrets = has("sec_session_files") || has("sec_auth_internals") || has("sec_prefs") || has("sec_login_export")
                || has("sec_2fa") || has("sec_login_codes") || has("sec_keystore") || has("sec_sessions") || has("msg_database");
            boolean obfuscated = has("code_blob") || has("code_hidden_string") || has("code_getattr") || has("code_dynamic_import") || has("code_chr");
            boolean runsCode = has("code_exec") || has("code_deserialize");

            for (int line : hiddenExecLines) {
                add("combo_hidden_code", line, null);
            }
            // marshal.loads + exec is how compiled payloads are run
            if (blobIsCode || runsCode && (obfuscated || has("code_escapes")) || has("code_exec") && has("code_deserialize")) {
                add("combo_hidden_code", firstOf("code_exec", "code_deserialize", "code_blob", "code_hidden_string"), null);
            }
            if (network && (runsCode || has("code_dynamic_import") || has("sys_dex") || has("sys_native_lib") || has("code_native"))) {
                add("combo_remote_code", firstOf("code_exec", "code_deserialize", "code_dynamic_import", "sys_dex", "sys_native_lib", "code_native"), null);
            }
            if (network && obfuscated) {
                add("combo_obfuscated_network", firstOf("code_blob", "code_hidden_string", "code_getattr", "code_dynamic_import", "code_chr"), null);
            }
            if (network && secrets) {
                add("combo_exfil_secrets", firstOf("sec_session_files", "sec_auth_internals", "sec_prefs", "sec_login_export", "sec_2fa",
                    "sec_login_codes", "sec_keystore", "sec_sessions", "msg_database"), null);
            }
            if (network && has("file_private_dir")) {
                if (has("file_listing") || has("file_archive")) {
                    add("combo_exfil_secrets", firstLine("file_private_dir"), null);
                } else {
                    add("combo_private_network", firstLine("file_private_dir"), null);
                }
            }
            if (network && has("sys_shell")) {
                add("combo_shell_network", firstLine("sys_shell"), null);
            }
            if (has("net_suspicious") && (readsMessages || secrets || has("file_private_dir") || has("file_io"))) {
                add("combo_suspicious_exfil", firstLine("net_suspicious"), null);
            }
            if (network && readsMessages) {
                add("combo_exfil_messages", firstOf("msg_updates", "msg_outgoing", "msg_requests", "msg_read", "msg_contacts",
                    "msg_database", "sec_phone", "sec_clipboard"), null);
            }
        }

        int firstOf(String... rules) {
            for (String r : rules) {
                int l = firstLine(r);
                if (l > 0) {
                    return l;
                }
            }
            return 0;
        }

        void finish() {
            ArrayList<Finding> list = new ArrayList<>(findings.values());
            Collections.sort(list, (a, b) -> {
                if (a.severity != b.severity) {
                    return b.severity - a.severity;
                }
                if (a.isCombo() != b.isCombo()) {
                    return a.isCombo() ? -1 : 1;
                }
                if (a.category != b.category) {
                    return CATEGORY_ORDER[a.category] - CATEGORY_ORDER[b.category];
                }
                return 0;
            });
            report.findings.addAll(list);
            report.hosts.addAll(hosts);
            int max = report.maxSeverity();
            report.verdict = max >= CRITICAL ? VERDICT_DANGER : max >= HIGH ? VERDICT_WARNING : VERDICT_SAFE;
            report.reason = list.isEmpty() ? null : list.get(0).rule;
        }
        // endregion
    }

    /** Display priority of the categories: what a user should read first. */
    private static final int[] CATEGORY_ORDER = new int[CATEGORY_COUNT];

    static {
        int[] order = {CAT_SECRETS, CAT_CODE, CAT_SYSTEM, CAT_NETWORK, CAT_MESSAGES, CAT_ACTIONS, CAT_FILES, CAT_UI};
        for (int i = 0; i < order.length; i++) {
            CATEGORY_ORDER[order[i]] = i;
        }
    }
    // endregion

    // region Decoding helpers

    static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    static byte[] fromHex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (Character.digit(s.charAt(i * 2), 16) * 16 + Character.digit(s.charAt(i * 2 + 1), 16));
        }
        return out;
    }

    /** Standard or URL-safe base64 with a believable length and a mix of character classes (not a plain word). */
    static boolean isBase64(String s) {
        int n = s.length();
        int pad = 0;
        while (pad < 2 && n - pad - 1 >= 0 && s.charAt(n - pad - 1) == '=') {
            pad++;
        }
        boolean upper = false, lower = false, digit = false;
        for (int i = 0; i < n - pad; i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                upper = true;
            } else if (c >= 'a' && c <= 'z') {
                lower = true;
            } else if (c >= '0' && c <= '9') {
                digit = true;
            } else if (c != '+' && c != '/' && c != '-' && c != '_') {
                return false;
            }
        }
        if (pad > 0 && n % 4 != 0) {
            return false;
        }
        int classes = (upper ? 1 : 0) + (lower ? 1 : 0) + (digit ? 1 : 0);
        return n % 4 == 0 && classes >= 2 || n >= 64 && classes >= 2;
    }

    static byte[] fromBase64(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() * 3 / 4);
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') {
                v = c - 'A';
            } else if (c >= 'a' && c <= 'z') {
                v = c - 'a' + 26;
            } else if (c >= '0' && c <= '9') {
                v = c - '0' + 52;
            } else if (c == '+' || c == '-') {
                v = 62;
            } else if (c == '/' || c == '_') {
                v = 63;
            } else if (c == '=') {
                break;
            } else {
                return null;
            }
            buffer = (buffer << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    /** zlib / gzip payloads, inflated up to 2 MB; null when the data is not compressed. */
    static byte[] inflate(byte[] data) {
        if (data.length < 4) {
            return null;
        }
        int b0 = data[0] & 0xFF;
        int b1 = data[1] & 0xFF;
        try {
            if (b0 == 0x1F && b1 == 0x8B) {
                try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
                    return readLimited(in);
                }
            }
            if (b0 == 0x78 && (b1 == 0x01 || b1 == 0x5E || b1 == 0x9C || b1 == 0xDA)) {
                Inflater inflater = new Inflater();
                try {
                    inflater.setInput(data);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    while (!inflater.finished() && out.size() < 2 * 1024 * 1024) {
                        int r = inflater.inflate(buf);
                        if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                            break;
                        }
                        out.write(buf, 0, r);
                    }
                    return out.size() > 0 ? out.toByteArray() : null;
                } finally {
                    inflater.end();
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private static byte[] readLimited(java.io.InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0 && out.size() < 2 * 1024 * 1024) {
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    /** Pictures, fonts, sounds and Lottie stickers: what plugins legitimately embed. */
    static boolean isMedia(byte[] d) {
        if (d.length < 4) {
            return false;
        }
        int b0 = d[0] & 0xFF, b1 = d[1] & 0xFF, b2 = d[2] & 0xFF, b3 = d[3] & 0xFF;
        if (b0 == 0x89 && b1 == 'P' && b2 == 'N' && b3 == 'G') return true;
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return true;
        if (b0 == 'G' && b1 == 'I' && b2 == 'F') return true;
        if (b0 == 'R' && b1 == 'I' && b2 == 'F' && b3 == 'F') return true;
        if (b0 == '%' && b1 == 'P' && b2 == 'D' && b3 == 'F') return true;
        if (b0 == 0 && b1 == 1 && b2 == 0 && b3 == 0) return true;
        if (b0 == 'O' && b1 == 'T' && b2 == 'T' && b3 == 'O') return true;
        if (b0 == 'w' && b1 == 'O' && b2 == 'F') return true;
        if (b0 == 'I' && b1 == 'D' && b2 == '3') return true;
        if (b0 == 'O' && b1 == 'g' && b2 == 'g' && b3 == 'S') return true;
        if (b0 == 0 && b1 == 0 && b2 == 1 && b3 == 0) return true;
        if (b0 == '<' && (b1 == 's' || b1 == '?')) return true;
        if (d.length > 12 && b0 == 0 && b1 == 0 && b2 == 0 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') return true;
        if (b0 == '{') {
            // Lottie JSON: {"v":"5.5.7","fr":60,...,"layers":[...]}
            String head = new String(d, 0, Math.min(d.length, 4096), StandardCharsets.ISO_8859_1);
            return head.contains("\"v\"") && head.contains("\"fr\"") || head.contains("\"layers\"");
        }
        return false;
    }

    static boolean isText(byte[] d) {
        if (d.length == 0) {
            return false;
        }
        int printable = 0;
        int n = Math.min(d.length, 4096);
        for (int i = 0; i < n; i++) {
            int b = d[i] & 0xFF;
            if (b >= 0x20 && b < 0x7F || b == '\n' || b == '\r' || b == '\t' || b >= 0x80) {
                printable++;
            }
        }
        return printable >= n * 0.95;
    }

    static boolean looksLikeCode(String s) {
        int score = 0;
        if (s.contains("import ")) score++;
        if (s.contains("def ")) score++;
        if (s.contains("exec(") || s.contains("eval(")) score += 2;
        if (s.contains("os.") || s.contains("sys.")) score++;
        if (s.contains("jclass(")) score++;
        if (s.contains("subprocess") || s.contains("requests.") || s.contains("urllib")) score += 2;
        if (s.contains("lambda") || s.contains("return ")) score++;
        return score >= 2;
    }

    static boolean looksStructured(String s) {
        char c = s.charAt(0);
        if (c == '{' || c == '[' || c == '<') {
            return true;
        }
        int punct = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '{' || ch == '}' || ch == '"' || ch == ':' || ch == ',' || ch == ' ') {
                punct++;
            }
        }
        return punct > s.length() * 0.05;
    }

    static double entropy(String s) {
        int[] counts = new int[128];
        int other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 128) {
                counts[c]++;
            } else {
                other++;
            }
        }
        double e = 0;
        double n = s.length();
        for (int c : counts) {
            if (c > 0) {
                double p = c / n;
                e -= p * Math.log(p) / Math.log(2);
            }
        }
        if (other > 0) {
            double p = other / n;
            e -= p * Math.log(p) / Math.log(2);
        }
        return e;
    }

    static String kb(int bytes) {
        return bytes >= 1024 ? String.format(Locale.ROOT, "%.1f KB", bytes / 1024f) : bytes + " B";
    }
    // endregion
}
