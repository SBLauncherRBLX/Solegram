/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.solegram.messenger.ApiCredentials;
import com.solegram.messenger.BackgroundConnection;
import com.solegram.messenger.GhostMode;
import com.solegram.messenger.MessageVault;
import com.solegram.messenger.MonetTheme;
import com.solegram.messenger.Solegram;
import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramProxy;
import com.solegram.messenger.plugins.PluginsController;
import com.solegram.messenger.updater.UpdateController;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ThemeActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static org.telegram.messenger.LocaleController.getString;

/** Solegram preferences: a main page with categories and one page per category. */
public class SolegramSettingsActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    public static final int PAGE_MAIN = 0;
    public static final int PAGE_APPEARANCE = 1;
    public static final int PAGE_CHATS = 2;
    public static final int PAGE_GHOST = 3;
    public static final int PAGE_GENERAL = 4;
    public static final int PAGE_UPDATES = 5;
    public static final int PAGE_ABOUT = 6;
    public static final int PAGE_GLASS = 7;
    public static final int PAGE_AI = 8;
    public static final int PAGE_MAIN_SCREEN = 9;
    public static final int PAGE_MUSIC = 10;
    public static final int PAGE_CAMERA = 11;
    public static final int PAGE_BROWSER = 12;
    public static final int PAGE_PROXY = 13;

    private final int page;

    // main
    private static final int ID_APPEARANCE = 1;
    private static final int ID_CHATS = 2;
    private static final int ID_GHOST = 3;
    private static final int ID_GENERAL = 4;
    private static final int ID_PLUGINS = 5;
    private static final int ID_UPDATES = 6;
    private static final int ID_ABOUT = 7;
    private static final int ID_EXPORT = 8;
    private static final int ID_IMPORT = 9;
    private static final int ID_RESET = 10;
    private static final int ID_GLASS_PAGE = 11;
    private static final int ID_AI_PAGE = 12;
    private static final int ID_MAIN_SCREEN = 13;
    private static final int ID_MUSIC = 14;
    private static final int ID_CAMERA = 15;
    private static final int ID_BROWSER = 16;
    private static final int ID_PROXY = 17;
    private static final int ID_FILTERS = 18;
    private static final int ID_REMINDERS = 19;

    // music in the profile
    private static final int ID_NOW_PLAYING = 701;
    private static final int ID_NOW_PLAYING_ACCESS = 702;
    private static final int ID_NOW_PLAYING_SPOTIFY = 703;
    private static final int ID_NOW_PLAYING_SOUNDCLOUD = 704;
    private static final int ID_NOW_PLAYING_SHARE = 705;

    // camera
    private static final int ID_CAMERA_FPS_AUTO = 801;
    private static final int ID_CAMERA_FPS_60 = 802;
    private static final int ID_CAMERA_FPS_30 = 803;
    private static final int ID_CAMERA_ENHANCE = 804;
    private static final int ID_CAMERA_HD = 805;
    private static final int ID_CAMERA_CONTROLS = 806;

    // browser
    private static final int ID_ADBLOCK = 901;
    private static final int ID_ADBLOCK_COSMETIC = 902;
    private static final int ID_ADBLOCK_YOUTUBE = 903;
    private static final int ID_ADBLOCK_POPUPS = 904;
    private static final int ID_ADBLOCK_TOTAL = 905;
    private static final int ID_ADBLOCK_ALLOWED = 906;
    private static final int ID_BROWSER_SETTINGS = 907;

    // proxy
    private static final int ID_PROXY_FIND = 951;
    private static final int ID_PROXY_USE = 952;
    private static final int ID_PROXY_AUTO = 953;
    private static final int ID_PROXY_LIST = 954;
    private static final int ID_PROXY_CLEAR = 955;

    // appearance
    private static final int ID_SYSTEM_FONT = 101;
    private static final int ID_CENTER_TITLE = 102;
    private static final int ID_HIDE_STORIES = 103;
    private static final int ID_HIDE_EMOJI_STATUS = 104;
    private static final int ID_HIDE_PHONE = 105;
    private static final int ID_MONET = 106;
    private static final int ID_THEMES = 107;
    private static final int ID_APP_ICON = 108;
    private static final int ID_TEXTURED_GLASS = 109;
    private static final int ID_GLASS_SETTINGS = 111;
    private static final int ID_AVATAR_IN_CHATS = 112;
    private static final int ID_MAIN_TITLE = 113;
    private static final int ID_SMOOTH_TRANSITIONS = 114;
    private static final int ID_PRESS_SCALE = 115;
    private static final int ID_ANIMATE_LISTS = 116;
    private static final int ID_NEUTRAL_OVERSCROLL = 117;
    private static final int ID_TYPING_ANIMATION = 118;

    // glass
    private static final int ID_GLASS_TINT = 120;
    private static final int ID_GLASS_SHADOW = 121;
    private static final int ID_GLASS_VIBRANCY = 122;
    private static final int ID_GLASS_RESTART = 123;
    private static final int ID_GLASS_MENUS = 129;
    private static final int ID_GLASS_DIALOGS = 130;
    private static final int ID_GLASS_SHEETS = 131;
    private static final int ID_LIQUID_CONTROLS = 132;
    private static final int ID_GLASS_PREVIEW_LOOK = 133;
    private static final int ID_FROSTED_HEADERS = 134;
    private static final int ID_TITLE_EMOJI = 135;
    private static final int ID_GLASS_CALL = 136;
    private static final int ID_FLOATING_HEADER = 137;
    private static final int ID_HEADER_BUTTONS = 138;
    private static final int ID_LIQUID_MOTION = 139;

    // chats
    private static final int ID_DOUBLE_TAP = 201;
    private static final int ID_HIDE_KEYBOARD = 202;
    private static final int ID_SHOW_SECONDS = 203;
    private static final int ID_NO_NEXT_CHANNEL = 204;
    private static final int ID_CONFIRM_STICKERS = 205;
    private static final int ID_CONFIRM_VOICE = 206;
    private static final int ID_HIDE_SHARE = 207;
    private static final int ID_SHOW_ID = 208;
    private static final int ID_SHOW_MESSAGE_ID = 209;
    private static final int ID_SEND_SILENTLY = 210;
    private static final int ID_NO_LINK_PREVIEWS = 211;
    private static final int ID_VOICE_ONLY = 212;
    private static final int ID_NO_SWIPE_REPLY = 213;
    private static final int ID_CONFIRM_GIFS = 214;
    private static final int ID_MESSAGE_DETAILS = 215;
    private static final int ID_JUMP_TO_FIRST = 216;
    private static final int ID_FORWARD_NO_AUTHOR = 217;
    private static final int ID_SHOW_DC = 218;
    private static final int ID_FREE_TRANSLATE = 219;
    private static final int ID_AUTO_TRANSLATE = 220;
    private static final int ID_TRANSLATE_TO = 221;
    private static final int ID_DONT_TRANSLATE = 222;
    private static final int ID_TRANSLATE_HERE = 223;
    private static final int ID_AI_SUMMARY = 224;
    private static final int ID_AI_PROVIDER = 225;
    private static final int ID_AI_URL = 226;
    private static final int ID_AI_KEY = 227;
    private static final int ID_AI_MODEL = 228;
    private static final int ID_AI_LANGUAGE = 229;
    private static final int ID_AI_SUMMARY_BUTTON = 230;
    private static final int ID_AI_NANO = 232;
    private static final int ID_IOS_INPUT_BAR = 231;
    private static final int ID_TRANSCRIBE = 240;
    private static final int ID_TRANSCRIBE_ENGINE = 241;
    private static final int ID_TRANSCRIBE_URL = 242;
    private static final int ID_TRANSCRIBE_KEY = 243;
    private static final int ID_TRANSCRIBE_MODEL = 244;
    private static final int ID_PROFILE_LINKS = 250;
    private static final int ID_PROFILE_LINKS_EDIT = 251;

    // ghost
    private static final int ID_GHOST_MASTER = 301;
    private static final int ID_GHOST_TYPING = 302;
    private static final int ID_GHOST_READ = 303;
    private static final int ID_GHOST_READ_INTERACT = 304;
    private static final int ID_GHOST_OFFLINE = 305;
    private static final int ID_GHOST_STORIES = 306;
    private static final int ID_GHOST_MENU = 307;
    private static final int ID_SAVE_DELETED = 308;
    private static final int ID_SAVE_EDITS = 309;
    private static final int ID_CLEAR_VAULT = 310;

    // general
    private static final int ID_BACKGROUND = 401;
    private static final int ID_BATTERY = 402;
    private static final int ID_CONFIRM_CALLS = 403;
    private static final int ID_HIDE_PREMIUM = 404;
    private static final int ID_HIDE_GIFTS = 405;
    private static final int ID_PROXIMITY = 406;
    private static final int ID_NO_VIBRATION = 407;
    private static final int ID_REMEMBER_FOLDER = 408;
    private static final int ID_MARK_ALL_READ = 409;
    private static final int ID_HIDE_CONTACTS_TAB = 410;
    private static final int ID_HIDE_PROFILE_TAB = 411;
    private static final int ID_MARK_ALL_READ_NOW = 412;
    private static final int ID_SAVED_IN_MENU = 413;
    private static final int ID_FEED_TAB = 414;
    private static final int ID_CONTACTS_SWIPE = 415;
    private static final int ID_FEED_SKIP_MUTED = 416;
    private static final int ID_OPEN_FEED = 417;
    private static final int ID_ADD_ACCOUNT = 418;

    // updates
    private static final int ID_CHECK_UPDATE = 501;
    private static final int ID_AUTO_UPDATE = 502;
    private static final int ID_UPDATE_CHANNEL = 503;
    private static final int ID_UPDATE_SOURCE = 504;
    private static final int ID_UPDATE_REPO = 505;
    private static final int ID_UPDATE_BROWSER = 506;
    private static final int ID_UPDATE_WIFI = 507;

    // about
    private static final int ID_SOURCE = 601;
    private static final int ID_LICENSE = 602;
    private static final int ID_CHANNEL = 603;
    private static final int ID_GITHUB = 604;

    public SolegramSettingsActivity() {
        this(PAGE_MAIN);
    }

    public SolegramSettingsActivity(int page) {
        super();
        this.page = page;
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.solegramUpdateAvailable);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.solegramPluginsChanged);
        if (page == PAGE_MUSIC) {
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.solegramNowPlayingChanged);
        }
        if (page == PAGE_PROXY) {
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.solegramProxyChanged);
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        }
        if (page == PAGE_AI) {
            // Gemini Nano status and download progress
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.solegramGeminiNanoChanged);
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.solegramUpdateAvailable);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.solegramPluginsChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.solegramNowPlayingChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.solegramProxyChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.solegramGeminiNanoChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.solegramProxyChanged && args.length > 0 && args[0] instanceof SolegramProxy.Result) {
            showProxyResult((SolegramProxy.Result) args[0]);
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        switch (page) {
            case PAGE_APPEARANCE:
                fillAppearance(items);
                break;
            case PAGE_CHATS:
                fillChats(items);
                break;
            case PAGE_GHOST:
                fillGhost(items);
                break;
            case PAGE_GENERAL:
                fillGeneral(items);
                break;
            case PAGE_UPDATES:
                fillUpdates(items);
                break;
            case PAGE_ABOUT:
                fillAbout(items);
                break;
            case PAGE_GLASS:
                fillGlass(items);
                break;
            case PAGE_AI:
                fillAi(items);
                break;
            case PAGE_MAIN_SCREEN:
                fillMainScreen(items);
                break;
            case PAGE_MUSIC:
                fillMusic(items);
                break;
            case PAGE_CAMERA:
                fillCamera(items);
                break;
            case PAGE_BROWSER:
                fillBrowser(items);
                break;
            case PAGE_PROXY:
                fillProxy(items);
                break;
            default:
                fillMain(items);
        }
    }

    // region pages

    /**
     * The categories, grouped as one thinks of them: how the app looks, chatting, the app itself, extensions.
     * Every category says in a line what it holds.
     */
    private void fillMain(ArrayList<UItem> items) {
        items.add(UItem.asCustomShadow(headerView()).setChecked(true)); // full width: the haze dissolves into the page, not a card; checked = its cell does not clip (the haze reaches the top)
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.SG_SectionLook)));
        items.add(category(ID_APPEARANCE, R.drawable.msg_theme, R.string.SG_Appearance, R.string.SG_AppearanceSub, null, TILE_INDIGO));
        items.add(category(ID_GLASS_PAGE, R.drawable.msg_customize, R.string.SG_GlassSettings, R.string.SG_GlassSub, null, TILE_CYAN));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.SG_SectionTalk)));
        items.add(category(ID_CHATS, R.drawable.msg_discussion, R.string.SG_Chats, R.string.SG_ChatsSub, null, TILE_GREEN));
        items.add(category(ID_FILTERS, R.drawable.msg_archive_hide, R.string.SG_Filters, R.string.SG_FiltersSub,
            SolegramConfig.messageFilters ? getString(R.string.SG_On) : null, TILE_RED));
        items.add(category(ID_AI_PAGE, R.drawable.sg_ai_summary, R.string.SG_AiPage, R.string.SG_AiPageSub, null, TILE_PURPLE));
        final int reminders = com.solegram.messenger.SolegramReminders.upcomingCount();
        items.add(category(ID_REMINDERS, R.drawable.msg_notifications, R.string.SG_Reminders, R.string.SG_RemindersSub,
            reminders > 0 ? String.valueOf(reminders) : null, TILE_ORANGE));
        items.add(category(ID_MUSIC, R.drawable.files_music, R.string.SG_NowPlaying, R.string.SG_NowPlayingSub,
            !SolegramConfig.nowPlaying ? getString(R.string.SG_Off) : com.solegram.messenger.SolegramNowPlaying.hasAccess() ? getString(R.string.SG_On) : null, TILE_PINK));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.SG_SectionApp)));
        items.add(category(ID_MAIN_SCREEN, R.drawable.msg_home, R.string.SG_MainScreen, R.string.SG_MainScreenSub, null, TILE_BLUE));
        items.add(category(ID_GHOST, R.drawable.msg_secret, R.string.SG_Privacy, R.string.SG_PrivacySub,
            SolegramConfig.isGhostModeActive() ? getString(R.string.SG_Ghost) : null, TILE_GRAY));
        items.add(category(ID_CAMERA, R.drawable.msg_camera, R.string.SG_Camera, R.string.SG_CameraSub, cameraFpsName(), TILE_ORANGE));
        items.add(category(ID_BROWSER, R.drawable.msg_language, R.string.SG_Browser, R.string.SG_BrowserSub,
            getString(SolegramConfig.adBlock ? R.string.SG_On : R.string.SG_Off), TILE_TEAL));
        items.add(category(ID_PROXY, R.drawable.outline_shield_check, R.string.SG_Proxy, R.string.SG_ProxySub,
            org.telegram.messenger.SharedConfig.isProxyEnabled() ? getString(R.string.SG_On) : null, TILE_GREEN));
        items.add(category(ID_GENERAL, R.drawable.msg_settings, R.string.SG_System, R.string.SG_SystemSub, null, TILE_GRAY));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.SG_SectionExtensions)));
        String pluginsValue = PluginsController.isEngineAvailable() ? String.valueOf(PluginsController.getInstance().getLoadedCount()) : "";
        items.add(category(ID_PLUGINS, R.drawable.msg_bots, R.string.SG_Plugins, R.string.SG_PluginsSub, pluginsValue, TILE_ORANGE));
        String updateValue = UpdateController.getInstance().hasPendingUpdate() ? getString(R.string.SG_UpdateAvailableShort) : "";
        items.add(tile(UItem.asButton(ID_UPDATES, R.drawable.msg_download, getString(R.string.SG_Updates), updateValue), TILE_BLUE)
            .setSolegramSubtitle(LocaleController.formatString(R.string.SG_UpdatesSub, Solegram.getVersionName())));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.SG_Backup)));
        items.add(tile(UItem.asButton(ID_EXPORT, R.drawable.msg_shareout, getString(R.string.SG_ExportSettings)), TILE_TEAL));
        items.add(tile(UItem.asButton(ID_IMPORT, R.drawable.msg_photo_settings, getString(R.string.SG_ImportSettings)), TILE_CYAN));
        items.add(UItem.asButton(ID_RESET, R.drawable.msg_reset, getString(R.string.SG_ResetSettings)).red());
        items.add(UItem.asShadow(getString(R.string.SG_BackupInfo)));
        items.add(tile(UItem.asButton(ID_ABOUT, R.drawable.msg_info, getString(R.string.SG_About)), TILE_GRAY));
        items.add(UItem.asShadow(null));
    }

    private UItem category(int id, int icon, int title, int subtitle, CharSequence value, int[] color) {
        final UItem item = value == null ? UItem.asButton(id, icon, getString(title)) : UItem.asButton(id, icon, getString(title), value);
        return tile(item, color).setSolegramSubtitle(getString(subtitle));
    }

    private void fillAppearance(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.SG_AvatarCorners)));
        items.add(UItem.asIntSlideView(1, 0, SolegramConfig.avatarCorners, 100,
            v -> v == 100 ? getString(R.string.SG_AvatarCircle) : v == 0 ? getString(R.string.SG_AvatarSquare) : v + "%",
            v -> {
                SolegramConfig.putInt("avatarCorners", v);
                AndroidUtilities.runOnUIThread(SolegramSettingsActivity::redrawEverything);
            }));
        items.add(UItem.asCheck(ID_AVATAR_IN_CHATS, getString(R.string.SG_AvatarShapeInChats)).setChecked(SolegramConfig.avatarShapeInChats));
        items.add(UItem.asShadow(getString(R.string.SG_AvatarCornersInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_Interface)));
        items.add(UItem.asButtonCheck(ID_SYSTEM_FONT, getString(R.string.SG_SystemFont), getString(R.string.SG_SystemFontInfo)).setChecked(SolegramConfig.useSystemFont));
        items.add(tile(UItem.asButton(ID_MAIN_TITLE, R.drawable.msg_edit, getString(R.string.SG_MainTitle), mainTitleName(SolegramConfig.mainTitle)), TILE_ORANGE));
        items.add(tile(UItem.asButton(ID_TITLE_EMOJI, R.drawable.msg2_smile_status, getString(R.string.SG_TitleEmoji),
            getString(com.solegram.messenger.SolegramTitleEmoji.get() != 0 ? R.string.SG_TitleEmojiSet : R.string.SG_TitleEmojiNone)), TILE_YELLOW));
        items.add(UItem.asCheck(ID_CENTER_TITLE, getString(R.string.SG_CenterTitle)).setChecked(SolegramConfig.centerTitle));
        if (Build.VERSION.SDK_INT >= 31) {
            items.add(UItem.asButtonCheck(ID_MONET, getString(R.string.SG_Monet), getString(R.string.SG_MonetInfo)).setChecked(SolegramConfig.monetAccent));
        }
        items.add(tile(UItem.asButton(ID_THEMES, R.drawable.msg_theme, getString(R.string.SG_ThemesAndIcons)), TILE_BLUE));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.SG_IconPack)));
        if (iconPackPicker == null) {
            iconPackPicker = new SolegramIconPackPicker(getContext(), getResourceProvider(), this::setIconPack);
        }
        iconPackPicker.setSelected(SolegramConfig.iconPack);
        items.add(UItem.asCustom(iconPackPicker));
        items.add(UItem.asShadow(getString(R.string.SG_IconPackInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_Animations)));
        items.add(UItem.asButtonCheck(ID_SMOOTH_TRANSITIONS, getString(R.string.SG_SmoothTransitions), getString(R.string.SG_SmoothTransitionsInfo)).setChecked(SolegramConfig.smoothTransitions));
        items.add(UItem.asButtonCheck(ID_PRESS_SCALE, getString(R.string.SG_PressScale), getString(R.string.SG_PressScaleInfo)).setChecked(SolegramConfig.pressScale));
        items.add(UItem.asButtonCheck(ID_ANIMATE_LISTS, getString(R.string.SG_AnimateLists), getString(R.string.SG_AnimateListsInfo)).setChecked(SolegramConfig.animateLists));
        items.add(UItem.asButtonCheck(ID_NEUTRAL_OVERSCROLL, getString(R.string.SG_NeutralOverscroll), getString(R.string.SG_NeutralOverscrollInfo)).setChecked(SolegramConfig.neutralOverscroll));
        items.add(UItem.asButtonCheck(ID_TYPING_ANIMATION, getString(R.string.SG_TypingAnimation), getString(R.string.SG_TypingAnimationInfo)).setChecked(SolegramConfig.typingAnimation));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.SG_HideAndDeclutter)));
        items.add(UItem.asCheck(ID_HIDE_STORIES, getString(R.string.SG_HideStories)).setChecked(SolegramConfig.hideStories));
        items.add(UItem.asCheck(ID_HIDE_EMOJI_STATUS, getString(R.string.SG_HideEmojiStatus)).setChecked(SolegramConfig.hideEmojiStatus));
        items.add(UItem.asCheck(ID_HIDE_PREMIUM, getString(R.string.SG_HidePremium)).setChecked(SolegramConfig.hidePremiumSettings));
        items.add(UItem.asCheck(ID_HIDE_GIFTS, getString(R.string.SG_HideGifts)).setChecked(SolegramConfig.hideGiftButtons));
        items.add(UItem.asShadow(null));
    }

    private SolegramGlassPreview glassPreview;
    private SolegramIconPackPicker iconPackPicker;

    /**
     * Another icon pack: new views load from it at once. Theme's cached icons load again, the screens below
     * build their views again when they come back, and this list makes its rows anew (rebinding a row keeps
     * the icon it has).
     */
    private void setIconPack(int pack) {
        SolegramConfig.putInt("iconPack", pack);
        Theme.reloadAllResources(ApplicationLoader.applicationContext);
        if (getParentActivity() instanceof LaunchActivity) {
            ((LaunchActivity) getParentActivity()).rebuildAllFragments(false);
        }
        if (listView != null && listView.adapter != null) {
            final androidx.recyclerview.widget.RecyclerView.LayoutManager layoutManager = listView.getLayoutManager();
            final android.os.Parcelable state = layoutManager != null ? layoutManager.onSaveInstanceState() : null;
            listView.setAdapter(listView.adapter);
            listView.getRecycledViewPool().clear();
            if (state != null) {
                layoutManager.onRestoreInstanceState(state);
            }
        }
        redrawEverything();
    }
    private org.telegram.ui.SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow titleEmojiPicker;

    private void fillGlass(ArrayList<UItem> items) {
        // preview: the top of a Telegram screen (glass buttons and the search field, Telegram's own views)
        // over chats that scroll underneath. One instance per page, so the scroll survives list updates.
        if (glassPreview == null) {
            glassPreview = new SolegramGlassPreview(getContext());
        }
        items.add(UItem.asCustom(glassPreview));
        final boolean liquid = Build.VERSION.SDK_INT >= 33 && org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS);
        items.add(UItem.asShadow(getString(liquid ? R.string.SG_GlassPreviewInfo : R.string.SG_GlassFrostedOnlyInfo)));

        // one switch for the glass material: liquid (refraction, Android 13+) and frosted (blur) together
        items.add(UItem.asHeader(getString(R.string.SG_Glass)));
        items.add(UItem.asButtonCheck(ID_TEXTURED_GLASS, getString(R.string.SG_TexturedGlass),
                getString(texturedGlassSupported() ? R.string.SG_TexturedGlassInfo : R.string.SG_TexturedGlassUnsupported))
            .setChecked(texturedGlassSupported() && texturedGlassEnabled())
            .setEnabled(texturedGlassSupported()));
        items.add(UItem.asShadow(getString(R.string.SG_GlassInfo)));

        if (liquid) {
            glassSlider(items, R.string.SG_GlassThickness, "glassThickness", 25, 300, SolegramConfig.glassThickness, v -> v + "%");
            glassSlider(items, R.string.SG_GlassRefraction, "glassRefraction", 0, 300, SolegramConfig.glassRefraction, v -> v == 0 ? getString(R.string.SG_Off) : v + "%");
            glassSlider(items, R.string.SG_GlassIndex, "glassIndex", 100, 250, SolegramConfig.glassIndex, v -> String.format(java.util.Locale.US, "%.2f", v / 100f));
            glassSlider(items, R.string.SG_GlassDispersion, "glassDispersion", 0, 100, SolegramConfig.glassDispersion, v -> v == 0 ? getString(R.string.SG_Off) : v + "%");
            glassSlider(items, R.string.SG_GlassRim, "glassRim", 0, 100, SolegramConfig.glassRim, v -> v == 0 ? getString(R.string.SG_Off) : v + "%");
            items.add(UItem.asShadow(getString(R.string.SG_GlassRefractionInfo)));
        }

        glassSlider(items, R.string.SG_GlassOpacity, "glassOpacity", 10, 100, SolegramConfig.glassOpacity, v -> v + "%");
        glassSlider(items, R.string.SG_GlassBlur, "glassBlur", 0, 300, SolegramConfig.glassBlur, v -> v + "%");
        glassSlider(items, R.string.SG_GlassHighlight, "glassHighlight", 0, 300, SolegramConfig.glassHighlight, v -> v == 0 ? getString(R.string.SG_Off) : v + "%");
        items.add(UItem.asButtonCheck(ID_GLASS_PREVIEW_LOOK, getString(R.string.SG_GlassPreviewLook), getString(R.string.SG_GlassPreviewLookInfo)).setChecked(SolegramConfig.glassPreviewLook));
        items.add(UItem.asCheck(ID_GLASS_SHADOW, getString(R.string.SG_GlassShadow)).setChecked(SolegramConfig.glassShadow));
        if (liquid) {
            items.add(UItem.asButtonCheck(ID_GLASS_VIBRANCY, getString(R.string.SG_GlassVibrancy), getString(R.string.SG_GlassVibrancyInfo)).setChecked(SolegramConfig.glassVibrancy));
        }
        items.add(UItem.asShadow(null));

        final boolean windowBlur = com.solegram.messenger.SolegramUi.canBlurWindows();
        items.add(UItem.asHeader(getString(R.string.SG_GlassElsewhere)));
        items.add(UItem.asButtonCheck(ID_LIQUID_CONTROLS, getString(R.string.SG_LiquidControls), getString(R.string.SG_LiquidControlsInfo)).setChecked(SolegramConfig.liquidControls));
        items.add(UItem.asButtonCheck(ID_FLOATING_HEADER, getString(R.string.SG_FloatingHeader), getString(R.string.SG_FloatingHeaderInfo)).setChecked(SolegramConfig.floatingHeader));
        items.add(UItem.asButtonCheck(ID_HEADER_BUTTONS, getString(R.string.SG_HeaderButtons), getString(R.string.SG_HeaderButtonsInfo)).setChecked(SolegramConfig.glassHeaderButtons));
        items.add(UItem.asButtonCheck(ID_FROSTED_HEADERS, getString(R.string.SG_FrostedHeaders), getString(R.string.SG_FrostedHeadersInfo)).setChecked(SolegramConfig.frostedHeaders));
        items.add(UItem.asButtonCheck(ID_GLASS_MENUS, getString(R.string.SG_GlassMenus), getString(R.string.SG_GlassMenusInfo)).setChecked(SolegramConfig.glassMenus));
        items.add(UItem.asButtonCheck(ID_GLASS_DIALOGS, getString(R.string.SG_GlassDialogs), getString(R.string.SG_GlassDialogsInfo)).setChecked(SolegramConfig.glassDialogs));
        items.add(UItem.asButtonCheck(ID_GLASS_SHEETS, getString(R.string.SG_GlassSheets), getString(R.string.SG_GlassSheetsInfo)).setChecked(SolegramConfig.glassSheets));
        items.add(UItem.asButtonCheck(ID_GLASS_CALL, getString(R.string.SG_GlassCallScreen), getString(R.string.SG_GlassCallScreenInfo)).setChecked(SolegramConfig.glassCallScreen));
        items.add(UItem.asShadow(getString(windowBlur ? R.string.SG_GlassElsewhereInfo : R.string.SG_GlassElsewhereUnavailable)));

        items.add(UItem.asHeader(getString(R.string.SG_GlassTint)));
        items.add(tile(UItem.asButton(ID_GLASS_TINT, R.drawable.msg_palette, getString(R.string.SG_GlassTintColor), glassTintName(SolegramConfig.glassTint)), TILE_PINK));
        if (SolegramConfig.glassTint != com.solegram.messenger.SolegramGlass.TINT_NONE) {
            glassSlider(items, R.string.SG_GlassTintAmount, "glassTintAmount", 0, 60, SolegramConfig.glassTintAmount, v -> v + "%");
        }
        items.add(UItem.asShadow(getString(R.string.SG_GlassApplyInfo)));
        items.add(UItem.asButton(ID_GLASS_RESTART, R.drawable.msg_retry, getString(R.string.SG_RestartNow)).accent());
        items.add(UItem.asShadow(null));
    }

    private void glassSlider(ArrayList<UItem> items, int title, String key, int min, int max, int value, org.telegram.messenger.Utilities.CallbackReturn<Integer, CharSequence> format) {
        items.add(UItem.asHeader(getString(title)));
        items.add(UItem.asIntSlideView(1, min, value, max, format, v -> {
            SolegramConfig.putInt(key, v);
            if (glassPreview != null) {
                glassPreview.refresh();
            }
        }));
    }

    /** Liquid glass needs Android 13; frosted glass needs a device fast enough for blur. */
    private static boolean texturedGlassSupported() {
        return Build.VERSION.SDK_INT >= 33 || org.telegram.messenger.SharedConfig.canBlurChat();
    }

    private static boolean texturedGlassEnabled() {
        boolean on = true;
        if (Build.VERSION.SDK_INT >= 33) {
            on = org.telegram.messenger.LiteMode.isEnabledSetting(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS);
        }
        if (org.telegram.messenger.SharedConfig.canBlurChat()) {
            on &= org.telegram.messenger.LiteMode.isEnabledSetting(org.telegram.messenger.LiteMode.FLAG_CHAT_BLUR);
        }
        return on;
    }

    public static String glassTintName(int index) {
        final int[] names = {R.string.SG_TintNone, R.string.SG_TintAccent, R.string.SG_TintPink, R.string.SG_TintPurple, R.string.SG_TintIndigo,
            R.string.SG_TintBlue, R.string.SG_TintTeal, R.string.SG_TintGreen, R.string.SG_TintOrange, R.string.SG_TintYellow};
        return getString(names[Math.max(0, Math.min(names.length - 1, index))]);
    }

    private void showGlassTintSelector() {
        String[] names = new String[com.solegram.messenger.SolegramGlass.TINT_COLORS.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = glassTintName(i);
        }
        showDialog(org.telegram.ui.Components.AlertsCreator.createSingleChoiceDialog(getParentActivity(), names, getString(R.string.SG_GlassTintColor), SolegramConfig.glassTint, (d, which) -> {
            SolegramConfig.putInt("glassTint", which);
            listView.adapter.update(true);
        }));
    }

    public static String mainTitleName(int mode) {
        switch (mode) {
            case com.solegram.messenger.SolegramUi.TITLE_ACCOUNT:
                return getString(R.string.SG_TitleAccount);
            case com.solegram.messenger.SolegramUi.TITLE_CUSTOM:
                return TextUtils.isEmpty(SolegramConfig.mainTitleText) ? getString(R.string.SG_TitleCustom) : SolegramConfig.mainTitleText;
            case com.solegram.messenger.SolegramUi.TITLE_NONE:
                return getString(R.string.SG_TitleNone);
            default:
                return Solegram.NAME;
        }
    }

    private void showMainTitleSelector() {
        String[] names = {Solegram.NAME, getString(R.string.SG_TitleAccount), getString(R.string.SG_TitleCustom), getString(R.string.SG_TitleNone)};
        showDialog(org.telegram.ui.Components.AlertsCreator.createSingleChoiceDialog(getParentActivity(), names, getString(R.string.SG_MainTitle), SolegramConfig.mainTitle, (d, which) -> {
            if (which == com.solegram.messenger.SolegramUi.TITLE_CUSTOM) {
                askText(getString(R.string.SG_MainTitle), getString(R.string.SG_TitleCustomInfo), SolegramConfig.mainTitleText, Solegram.NAME, text -> {
                    SolegramConfig.prefs().edit().putString("mainTitleText", text).putInt("mainTitle", TextUtils.isEmpty(text) ? 0 : which).apply();
                    SolegramConfig.reload();
                    listView.adapter.update(true);
                    restartBulletin();
                });
                return;
            }
            SolegramConfig.putInt("mainTitle", which);
            listView.adapter.update(true);
            restartBulletin();
        }));
    }

    /** One-line text prompt. */
    private void askText(String title, String message, String value, String hint, org.telegram.messenger.Utilities.Callback<String> onSave) {
        askText(title, message, value, hint, false, onSave);
    }

    /** @param secret a password-style field: hidden, no suggestions, nothing learned by the keyboard */
    private void askText(String title, String message, String value, String hint, boolean secret, org.telegram.messenger.Utilities.Callback<String> onSave) {
        Context ctx = getParentActivity();
        if (ctx == null) {
            return;
        }
        org.telegram.ui.Components.EditTextBoldCursor field = new org.telegram.ui.Components.EditTextBoldCursor(ctx);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintText(hint);
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setText(value);
        field.setSingleLine(true);
        if (secret) {
            field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            field.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                field.setImeOptions(field.getImeOptions() | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
            }
        }
        field.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        field.setBackground(null);
        FrameLayout container = new FrameLayout(ctx);
        container.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP, 24, 8, 24, 8));
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx, getResourceProvider());
        builder.setTitle(title);
        if (message != null) {
            builder.setMessage(message);
        }
        builder.setView(container);
        builder.setPositiveButton(getString(R.string.Save), (d, w) -> onSave.run(field.getText() == null ? "" : field.getText().toString().trim()));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void fillChats(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.SG_StickerSize)));
        items.add(UItem.asIntSlideView(1, 50, SolegramConfig.stickerSize, 150,
            v -> v + "%",
            v -> SolegramConfig.putInt("stickerSize", v)));
        // shortcuts to Telegram's own message text size and bubble corner options
        items.add(UItem.asHeader(getString(R.string.SG_MessageTextSize)));
        items.add(UItem.asIntSlideView(1, 12, org.telegram.messenger.SharedConfig.fontSize, 30,
            v -> String.valueOf(v),
            SolegramSettingsActivity::applyMessageTextSize));
        items.add(UItem.asHeader(getString(R.string.SG_BubbleRadius)));
        items.add(UItem.asIntSlideView(1, 0, org.telegram.messenger.SharedConfig.bubbleRadius, 17,
            v -> v == 0 ? getString(R.string.SG_AvatarSquare) : String.valueOf(v),
            SolegramSettingsActivity::applyBubbleRadius));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.SG_Sending)));
        items.add(UItem.asButtonCheck(ID_IOS_INPUT_BAR, getString(R.string.SG_IosInputBar), getString(R.string.SG_IosInputBarInfo)).setChecked(SolegramConfig.iosInputBar));
        items.add(UItem.asButtonCheck(ID_SEND_SILENTLY, getString(R.string.SG_SendSilently), getString(R.string.SG_SendSilentlyInfo)).setChecked(SolegramConfig.sendSilently));
        items.add(UItem.asButtonCheck(ID_NO_LINK_PREVIEWS, getString(R.string.SG_NoLinkPreviews), getString(R.string.SG_NoLinkPreviewsInfo)).setChecked(SolegramConfig.disableLinkPreviews));
        items.add(UItem.asButtonCheck(ID_VOICE_ONLY, getString(R.string.SG_VoiceOnly), getString(R.string.SG_VoiceOnlyInfo)).setChecked(SolegramConfig.voiceOnlyRecord));
        items.add(UItem.asCheck(ID_NO_SWIPE_REPLY, getString(R.string.SG_NoSwipeReply)).setChecked(SolegramConfig.disableSwipeToReply));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.SG_Messages)));
        items.add(tile(UItem.asButton(ID_DOUBLE_TAP, R.drawable.msg_reactions2, getString(R.string.SG_DoubleTap), doubleTapName(SolegramConfig.doubleTapAction)), TILE_PINK));
        items.add(UItem.asCheck(ID_SHOW_SECONDS, getString(R.string.SG_ShowSeconds)).setChecked(SolegramConfig.showSeconds));
        items.add(UItem.asCheck(ID_SHOW_MESSAGE_ID, getString(R.string.SG_ShowMessageId)).setChecked(SolegramConfig.showMessageId));
        items.add(UItem.asCheck(ID_HIDE_KEYBOARD, getString(R.string.SG_HideKeyboardOnScroll)).setChecked(SolegramConfig.hideKeyboardOnScroll));
        items.add(UItem.asCheck(ID_NO_NEXT_CHANNEL, getString(R.string.SG_NoNextChannel)).setChecked(SolegramConfig.disableJumpToNextChannel));
        items.add(UItem.asCheck(ID_HIDE_SHARE, getString(R.string.SG_HideShareButton)).setChecked(SolegramConfig.hideShareButton));
        items.add(UItem.asButtonCheck(ID_MESSAGE_DETAILS, getString(R.string.SG_MessageDetailsItem), getString(R.string.SG_MessageDetailsItemInfo)).setChecked(SolegramConfig.messageDetails));
        items.add(UItem.asButtonCheck(ID_JUMP_TO_FIRST, getString(R.string.SG_JumpToFirstItem), getString(R.string.SG_JumpToFirstItemInfo)).setChecked(SolegramConfig.jumpToFirstInMenu));
        items.add(UItem.asButtonCheck(ID_FORWARD_NO_AUTHOR, getString(R.string.SG_ForwardNoAuthor), getString(R.string.SG_ForwardNoAuthorInfo)).setChecked(SolegramConfig.forwardWithoutAuthor));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.SG_Confirmations)));
        items.add(UItem.asCheck(ID_CONFIRM_STICKERS, getString(R.string.SG_ConfirmStickers)).setChecked(SolegramConfig.confirmStickers));
        items.add(UItem.asCheck(ID_CONFIRM_GIFS, getString(R.string.SG_ConfirmGifs)).setChecked(SolegramConfig.confirmGifs));
        items.add(UItem.asCheck(ID_CONFIRM_VOICE, getString(R.string.SG_ConfirmVoice)).setChecked(SolegramConfig.confirmVoice));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.SG_Profiles)));
        items.add(UItem.asButtonCheck(ID_SHOW_ID, getString(R.string.SG_ShowId), getString(R.string.SG_ShowIdInfo)).setChecked(SolegramConfig.showUserId));
        if (SolegramConfig.showUserId) {
            items.add(UItem.asButtonCheck(ID_SHOW_DC, getString(R.string.SG_ShowDc), getString(R.string.SG_ShowDcInfo)).setChecked(SolegramConfig.showDcInProfile));
        }
        items.add(UItem.asButtonCheck(ID_PROFILE_LINKS, getString(R.string.SG_ProfileLinksShow), getString(R.string.SG_ProfileLinksShowInfo)).setChecked(SolegramConfig.profileLinks));
        items.add(tile(UItem.asButton(ID_PROFILE_LINKS_EDIT, R.drawable.sg_brand_link, getString(R.string.SG_ProfileLinksMine)), TILE_BLUE));
        items.add(UItem.asShadow(null));
    }

    private void fillAi(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.SG_Translation)));
        items.add(UItem.asButtonCheck(ID_TRANSLATE_HERE, getString(R.string.SG_TranslateHereItem), getString(R.string.SG_TranslateHereItemInfo)).setChecked(SolegramConfig.translateHere));
        items.add(UItem.asButtonCheck(ID_FREE_TRANSLATE, getString(R.string.SG_FreeTranslate), getString(R.string.SG_FreeTranslateInfo)).setChecked(SolegramConfig.freeTranslate));
        if (SolegramConfig.freeTranslate) {
            items.add(UItem.asButtonCheck(ID_AUTO_TRANSLATE, getString(R.string.SG_AutoTranslate), getString(R.string.SG_AutoTranslateInfo)).setChecked(SolegramConfig.autoTranslate));
            items.add(tile(UItem.asButton(ID_TRANSLATE_TO, R.drawable.msg_translate, getString(R.string.SG_TranslateTo), translateTargetName()), TILE_BLUE));
            items.add(tile(UItem.asButton(ID_DONT_TRANSLATE, R.drawable.msg_block2, getString(R.string.DoNotTranslate)), TILE_RED));
        }
        items.add(UItem.asShadow(getString(R.string.SG_TranslationInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_AiSummaryHeader)));
        items.add(UItem.asButtonCheck(ID_AI_SUMMARY, getString(R.string.SG_AiSummary), getString(R.string.SG_AiSummaryInfo)).setChecked(SolegramConfig.aiSummary));
        if (SolegramConfig.aiSummary) {
            items.add(UItem.asButtonCheck(ID_AI_SUMMARY_BUTTON, getString(R.string.SG_AiSummaryButton), getString(R.string.SG_AiSummaryButtonInfo)).setChecked(SolegramConfig.aiSummaryButton));
            final boolean own = com.solegram.messenger.SolegramSummarizer.usesOwnService();
            items.add(tile(UItem.asButton(ID_AI_PROVIDER, R.drawable.sg_ai_summary, getString(R.string.SG_AiProvider),
                aiProviderName(SolegramConfig.aiProvider)), TILE_PURPLE));
            if (com.solegram.messenger.SolegramSummarizer.usesNano()) {
                items.add(tile(UItem.asButton(ID_AI_NANO, R.drawable.msg_newphone, getString(R.string.SG_NanoTitle), nanoStatusText()), TILE_INDIGO));
            }
            if (own) {
                items.add(tile(UItem.asButton(ID_AI_URL, R.drawable.msg_link, getString(R.string.SG_AiUrl),
                    TextUtils.isEmpty(SolegramConfig.aiBaseUrl) ? getString(R.string.SG_AiNotSet) : aiHost()), TILE_BLUE));
                items.add(tile(UItem.asButton(ID_AI_KEY, R.drawable.msg_permissions, getString(R.string.SG_AiKey),
                    getString(TextUtils.isEmpty(SolegramConfig.aiApiKey) ? R.string.SG_AiNotSet : R.string.SG_AiKeySet)), TILE_GRAY));
                items.add(tile(UItem.asButton(ID_AI_MODEL, R.drawable.msg_bots, getString(R.string.SG_AiModel),
                    TextUtils.isEmpty(SolegramConfig.aiModel) ? "gpt-4o-mini" : SolegramConfig.aiModel), TILE_INDIGO));
            }
            items.add(tile(UItem.asButton(ID_AI_LANGUAGE, R.drawable.msg_language, getString(R.string.SG_AiLanguage),
                SolegramConfig.aiSummaryLanguage == 1 ? translateTargetName() : getString(R.string.SG_AiLanguageMessage)), TILE_TEAL));
            items.add(UItem.asShadow(getString(aiPrivacyText(SolegramConfig.aiProvider))));
        } else {
            items.add(UItem.asShadow(null));
        }

        fillTranscription(items);
    }

    private void fillTranscription(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.SG_TranscribeHeader)));
        items.add(UItem.asButtonCheck(ID_TRANSCRIBE, getString(R.string.SG_Transcribe), getString(R.string.SG_TranscribeInfo)).setChecked(SolegramConfig.transcribe));
        if (!SolegramConfig.transcribe) {
            items.add(UItem.asShadow(null));
            return;
        }
        final int engine = SolegramConfig.transcribeEngine;
        // a speech service may have been installed or turned on since the chats asked
        com.solegram.messenger.SolegramTranscriber.refreshAvailability();
        items.add(tile(UItem.asButton(ID_TRANSCRIBE_ENGINE, R.drawable.msg_voicechat, getString(R.string.SG_TranscribeEngine), transcribeEngineName(engine)), TILE_PURPLE));
        if (engine != com.solegram.messenger.SolegramTranscriber.ENGINE_DEVICE) {
            final boolean aiRowsAbove = SolegramConfig.aiSummary && com.solegram.messenger.SolegramSummarizer.usesOwnService();
            // the service is shared with the summary; its rows show here when the summary section does not show them
            if (engine == com.solegram.messenger.SolegramTranscriber.ENGINE_AI && !aiRowsAbove) {
                items.add(tile(UItem.asButton(ID_TRANSCRIBE_URL, R.drawable.msg_link, getString(R.string.SG_AiUrl),
                    TextUtils.isEmpty(SolegramConfig.aiBaseUrl) ? getString(R.string.SG_AiNotSet) : aiHost()), TILE_BLUE));
                items.add(tile(UItem.asButton(ID_TRANSCRIBE_KEY, R.drawable.msg_permissions, getString(R.string.SG_AiKey),
                    getString(TextUtils.isEmpty(SolegramConfig.aiApiKey) ? R.string.SG_AiNotSet : R.string.SG_AiKeySet)), TILE_GRAY));
            }
            if (engine == com.solegram.messenger.SolegramTranscriber.ENGINE_AI || aiRowsAbove) {
                items.add(tile(UItem.asButton(ID_TRANSCRIBE_MODEL, R.drawable.msg_bots, getString(R.string.SG_TranscribeModel),
                    TextUtils.isEmpty(SolegramConfig.transcribeModel) ? com.solegram.messenger.SolegramTranscriber.defaultAiModel() : SolegramConfig.transcribeModel), TILE_INDIGO));
            }
        }
        final int info;
        if (engine == com.solegram.messenger.SolegramTranscriber.ENGINE_AI) {
            info = R.string.SG_TranscribePrivacyAi;
        } else if (engine == com.solegram.messenger.SolegramTranscriber.ENGINE_DEVICE) {
            info = R.string.SG_TranscribePrivacyDevice;
        } else {
            info = R.string.SG_TranscribePrivacyAuto;
        }
        items.add(UItem.asShadow(getString(info)));
    }

    private String transcribeEngineName(int engine) {
        if (engine == com.solegram.messenger.SolegramTranscriber.ENGINE_AI) {
            return getString(R.string.SG_TranscribeEngineAi);
        }
        final String name = getString(engine == com.solegram.messenger.SolegramTranscriber.ENGINE_DEVICE ? R.string.SG_TranscribeEngineDevice : R.string.SG_TranscribeEngineAuto);
        // said right here, so nobody waits for a recognizer the phone does not have
        return com.solegram.messenger.SolegramTranscriber.deviceAvailable() ? name : name + " (" + getString(R.string.SG_TranscribeUnavailable) + ")";
    }

    private void showTranscribeEnginePicker() {
        if (getParentActivity() == null) {
            return;
        }
        final String[] names = {getString(R.string.SG_TranscribeEngineAutoLong), getString(R.string.SG_TranscribeEngineDeviceLong), getString(R.string.SG_TranscribeEngineAiLong)};
        showDialog(org.telegram.ui.Components.AlertsCreator.createSingleChoiceDialog(getParentActivity(), names, getString(R.string.SG_TranscribeEngine), SolegramConfig.transcribeEngine, (d, which) -> {
            SolegramConfig.putInt("transcribeEngine", which);
            listView.adapter.update(true);
            if (which == com.solegram.messenger.SolegramTranscriber.ENGINE_AI && TextUtils.isEmpty(SolegramConfig.aiBaseUrl)) {
                askAiUrl();
            }
        }));
    }

    // region settings search

    /** Shows one row of the page (opened from the settings search). */
    public void highlightItem(int itemId) {
        if (listView == null) {
            return;
        }
        int position = listView.findPositionByItemId(itemId);
        if (position < 0) {
            // a row shown only while its main switch is on: point at the switch
            final int parent = parentRow(itemId);
            if (parent != 0) {
                position = listView.findPositionByItemId(parent);
            }
        }
        if (position < 0) {
            return;
        }
        final int row = position;
        listView.highlightRow(() -> {
            listView.layoutManager.scrollToPositionWithOffset(row, AndroidUtilities.dp(60));
            return row;
        });
    }

    /** The switch that shows a row (searchable rows only), or 0. */
    private static int parentRow(int itemId) {
        switch (itemId) {
            case ID_AI_SUMMARY_BUTTON:
            case ID_AI_PROVIDER:
            case ID_AI_NANO:
                return ID_AI_SUMMARY;
            case ID_TRANSCRIBE_ENGINE:
                return ID_TRANSCRIBE;
            case ID_NOW_PLAYING_SHARE:
                return ID_NOW_PLAYING;
            case ID_ADBLOCK_YOUTUBE:
            case ID_ADBLOCK_POPUPS:
                return ID_ADBLOCK;
            default:
                return 0;
        }
    }

    /** Not a page of this activity: the plugins screen (PluginsActivity), reachable from the settings search. */
    private static final int PAGE_PLUGINS = 50;
    /** Not a page of this activity either: the message filters (SolegramFiltersActivity). */
    private static final int PAGE_FILTERS = 51;
    /** Nor this: the reminders (SolegramRemindersActivity). */
    private static final int PAGE_REMINDERS = 52;

    /** {page, item id (0 = the page itself), title, keywords (an index into SEARCH_KEYWORDS, -1 = none)} */
    private static final int[][] SEARCH = {
        {PAGE_MAIN, 0, R.string.SG_SettingsTitle, 0},
        {PAGE_APPEARANCE, 0, R.string.SG_Appearance, -1},
        {PAGE_APPEARANCE, ID_AVATAR_IN_CHATS, R.string.SG_AvatarShapeInChats, -1},
        {PAGE_APPEARANCE, 0, R.string.SG_IconPack, 18},
        {PAGE_APPEARANCE, ID_SYSTEM_FONT, R.string.SG_SystemFont, -1},
        {PAGE_APPEARANCE, ID_MAIN_TITLE, R.string.SG_MainTitle, -1},
        {PAGE_APPEARANCE, ID_TITLE_EMOJI, R.string.SG_TitleEmoji, -1},
        {PAGE_APPEARANCE, ID_CENTER_TITLE, R.string.SG_CenterTitle, -1},
        {PAGE_APPEARANCE, ID_SMOOTH_TRANSITIONS, R.string.SG_SmoothTransitions, -1},
        {PAGE_APPEARANCE, ID_PRESS_SCALE, R.string.SG_PressScale, -1},
        {PAGE_APPEARANCE, ID_ANIMATE_LISTS, R.string.SG_AnimateLists, -1},
        {PAGE_APPEARANCE, ID_NEUTRAL_OVERSCROLL, R.string.SG_NeutralOverscroll, -1},
        {PAGE_APPEARANCE, ID_TYPING_ANIMATION, R.string.SG_TypingAnimation, -1},
        {PAGE_APPEARANCE, ID_HIDE_STORIES, R.string.SG_HideStories, -1},
        {PAGE_APPEARANCE, ID_HIDE_EMOJI_STATUS, R.string.SG_HideEmojiStatus, -1},
        {PAGE_APPEARANCE, ID_HIDE_PREMIUM, R.string.SG_HidePremium, -1},
        {PAGE_APPEARANCE, ID_HIDE_GIFTS, R.string.SG_HideGifts, -1},
        {PAGE_GLASS, 0, R.string.SG_GlassSettings, 1},
        {PAGE_GLASS, ID_TEXTURED_GLASS, R.string.SG_TexturedGlass, 1},
        {PAGE_GLASS, ID_LIQUID_CONTROLS, R.string.SG_LiquidControls, -1},
        {PAGE_GLASS, ID_FLOATING_HEADER, R.string.SG_FloatingHeader, -1},
        {PAGE_GLASS, ID_HEADER_BUTTONS, R.string.SG_HeaderButtons, -1},
        {PAGE_GLASS, ID_FROSTED_HEADERS, R.string.SG_FrostedHeaders, -1},
        {PAGE_GLASS, ID_GLASS_MENUS, R.string.SG_GlassMenus, -1},
        {PAGE_GLASS, ID_GLASS_DIALOGS, R.string.SG_GlassDialogs, -1},
        {PAGE_GLASS, ID_GLASS_SHEETS, R.string.SG_GlassSheets, -1},
        {PAGE_GLASS, ID_GLASS_CALL, R.string.SG_GlassCallScreen, -1},
        {PAGE_GLASS, ID_GLASS_TINT, R.string.SG_GlassTintColor, -1},
        {PAGE_GLASS, ID_GLASS_SHADOW, R.string.SG_GlassShadow, -1},
        {PAGE_CHATS, 0, R.string.SG_Chats, -1},
        {PAGE_CHATS, ID_DOUBLE_TAP, R.string.SG_DoubleTap, -1},
        {PAGE_CHATS, ID_IOS_INPUT_BAR, R.string.SG_IosInputBar, -1},
        {PAGE_CHATS, ID_SHOW_SECONDS, R.string.SG_ShowSeconds, -1},
        {PAGE_CHATS, ID_SHOW_MESSAGE_ID, R.string.SG_ShowMessageId, -1},
        {PAGE_CHATS, ID_HIDE_KEYBOARD, R.string.SG_HideKeyboardOnScroll, -1},
        {PAGE_CHATS, ID_NO_NEXT_CHANNEL, R.string.SG_NoNextChannel, -1},
        {PAGE_CHATS, ID_HIDE_SHARE, R.string.SG_HideShareButton, -1},
        {PAGE_CHATS, ID_MESSAGE_DETAILS, R.string.SG_MessageDetailsItem, -1},
        {PAGE_CHATS, ID_JUMP_TO_FIRST, R.string.SG_JumpToFirstItem, -1},
        {PAGE_CHATS, ID_FORWARD_NO_AUTHOR, R.string.SG_ForwardNoAuthor, -1},
        {PAGE_CHATS, ID_SEND_SILENTLY, R.string.SG_SendSilently, -1},
        {PAGE_CHATS, ID_NO_LINK_PREVIEWS, R.string.SG_NoLinkPreviews, -1},
        {PAGE_CHATS, ID_VOICE_ONLY, R.string.SG_VoiceOnly, -1},
        {PAGE_CHATS, ID_NO_SWIPE_REPLY, R.string.SG_NoSwipeReply, -1},
        {PAGE_CHATS, ID_CONFIRM_STICKERS, R.string.SG_ConfirmStickers, -1},
        {PAGE_CHATS, ID_CONFIRM_GIFS, R.string.SG_ConfirmGifs, -1},
        {PAGE_CHATS, ID_CONFIRM_VOICE, R.string.SG_ConfirmVoice, -1},
        {PAGE_CHATS, ID_SHOW_ID, R.string.SG_ShowId, -1},
        {PAGE_CHATS, ID_PROFILE_LINKS, R.string.SG_ProfileLinksShow, 17},
        {PAGE_CHATS, ID_PROFILE_LINKS_EDIT, R.string.SG_ProfileLinksMine, 17},
        {PAGE_AI, 0, R.string.SG_AiPage, 2},
        {PAGE_AI, ID_TRANSLATE_HERE, R.string.SG_TranslateHereItem, 3},
        {PAGE_AI, ID_FREE_TRANSLATE, R.string.SG_FreeTranslate, 3},
        {PAGE_AI, ID_AI_SUMMARY, R.string.SG_AiSummary, 2},
        {PAGE_AI, ID_AI_SUMMARY_BUTTON, R.string.SG_AiSummaryButton, 2},
        {PAGE_AI, ID_AI_PROVIDER, R.string.SG_AiProviderSearch, 13},
        {PAGE_AI, ID_AI_NANO, R.string.SG_NanoTitle, 13},
        {PAGE_AI, ID_TRANSCRIBE, R.string.SG_Transcribe, 12},
        {PAGE_AI, ID_TRANSCRIBE_ENGINE, R.string.SG_TranscribeEngine, 12},
        {PAGE_MUSIC, 0, R.string.SG_NowPlaying, 4},
        {PAGE_MUSIC, ID_NOW_PLAYING_SHARE, R.string.SG_NowPlayingShare, 4},
        {PAGE_MAIN_SCREEN, 0, R.string.SG_MainScreen, -1},
        {PAGE_MAIN_SCREEN, ID_REMEMBER_FOLDER, R.string.SG_RememberFolder, -1},
        {PAGE_MAIN_SCREEN, ID_MARK_ALL_READ, R.string.SG_MarkAllReadInMenu, -1},
        {PAGE_MAIN_SCREEN, ID_SAVED_IN_MENU, R.string.SG_SavedInMenu, -1},
        {PAGE_MAIN_SCREEN, ID_HIDE_PROFILE_TAB, R.string.SG_HideProfileTab, -1},
        {PAGE_MAIN_SCREEN, ID_FEED_TAB, R.string.SG_FeedTab, -1},
        {PAGE_MAIN_SCREEN, ID_CONTACTS_SWIPE, R.string.SG_ContactsSwipe, -1},
        {PAGE_MAIN_SCREEN, ID_FEED_SKIP_MUTED, R.string.SG_FeedSkipMuted, -1},
        {PAGE_GHOST, 0, R.string.SG_Privacy, 5},
        {PAGE_GHOST, ID_GHOST_MASTER, R.string.SG_GhostMode, 5},
        {PAGE_GHOST, ID_GHOST_TYPING, R.string.SG_GhostTyping, 5},
        {PAGE_GHOST, ID_GHOST_READ, R.string.SG_GhostRead, 5},
        {PAGE_GHOST, ID_GHOST_OFFLINE, R.string.SG_GhostOffline, 5},
        {PAGE_GHOST, ID_GHOST_STORIES, R.string.SG_GhostStories, 5},
        {PAGE_GHOST, ID_SAVE_DELETED, R.string.SG_SaveDeleted, 6},
        {PAGE_GHOST, ID_SAVE_EDITS, R.string.SG_SaveEdits, 6},
        {PAGE_GHOST, ID_HIDE_PHONE, R.string.SG_HidePhone, -1},
        {PAGE_CAMERA, 0, R.string.SG_Camera, 7},
        {PAGE_CAMERA, ID_CAMERA_ENHANCE, R.string.SG_CameraEnhance, 7},
        {PAGE_CAMERA, ID_CAMERA_HD, R.string.SG_CameraHd, 7},
        {PAGE_CAMERA, ID_CAMERA_CONTROLS, R.string.SG_CameraControls, 7},
        {PAGE_CAMERA, ID_CAMERA_FPS_AUTO, R.string.SG_CameraFps, 7},
        {PAGE_BROWSER, 0, R.string.SG_Browser, 8},
        {PAGE_BROWSER, ID_ADBLOCK, R.string.SG_AdBlock, 8},
        {PAGE_BROWSER, ID_ADBLOCK_YOUTUBE, R.string.SG_AdBlockYouTube, 8},
        {PAGE_BROWSER, ID_ADBLOCK_POPUPS, R.string.SG_AdBlockPopups, 8},
        {PAGE_PROXY, 0, R.string.SG_Proxy, 11},
        {PAGE_PROXY, ID_PROXY_FIND, R.string.SG_ProxyFind, 11},
        {PAGE_PROXY, ID_PROXY_AUTO, R.string.SG_ProxyAuto, 11},
        {PAGE_PROXY, ID_PROXY_USE, R.string.SG_ProxyUse, 11},
        {PAGE_PROXY, ID_PROXY_CLEAR, R.string.SG_ProxyClear, 11},
        {PAGE_GENERAL, 0, R.string.SG_System, -1},
        {PAGE_GENERAL, ID_BACKGROUND, R.string.SG_BackgroundConnection, -1},
        {PAGE_GENERAL, ID_BATTERY, R.string.SG_BatteryOptimization, -1},
        {PAGE_GENERAL, ID_CONFIRM_CALLS, R.string.SG_ConfirmCalls, -1},
        {PAGE_GENERAL, ID_PROXIMITY, R.string.SG_DisableProximity, -1},
        {PAGE_GENERAL, ID_NO_VIBRATION, R.string.SG_DisableVibration, -1},
        {PAGE_GENERAL, ID_ADD_ACCOUNT, R.string.SG_Accounts, -1},
        {PAGE_UPDATES, 0, R.string.SG_Updates, 9},
        {PAGE_UPDATES, ID_AUTO_UPDATE, R.string.SG_AutoUpdate, 9},
        {PAGE_MAIN, ID_EXPORT, R.string.SG_ExportSettings, 10},
        {PAGE_MAIN, ID_IMPORT, R.string.SG_ImportSettings, 10},
        {PAGE_PLUGINS, 0, R.string.SG_Plugins, 14},
        {PAGE_PLUGINS, PluginsActivity.ID_SCAN, R.string.SG_SecScanBeforeInstall, 14},
        {PAGE_PLUGINS, PluginsActivity.ID_BLOCK_DANGEROUS, R.string.SG_SecBlockDangerous, 14},
        {PAGE_PLUGINS, PluginsActivity.ID_SAFE_MODE, R.string.SG_SafeMode, 14},
        {PAGE_PLUGINS, PluginsActivity.ID_DEV_MODE, R.string.SG_DevMode, 14},
        {PAGE_FILTERS, 0, R.string.SG_Filters, 15},
        {PAGE_FILTERS, SolegramFiltersActivity.ID_MODE_HIDE, R.string.SG_FiltersLook, 15},
        {PAGE_FILTERS, SolegramFiltersActivity.ID_PRESET_ADS, R.string.SG_FilterPresetAds, 15},
        {PAGE_FILTERS, SolegramFiltersActivity.ID_PRESET_JUNK, R.string.SG_FilterPresetJunk, 15},
        {PAGE_FILTERS, SolegramFiltersActivity.ID_PRESET_INVITES, R.string.SG_FilterPresetInvites, 15},
        {PAGE_FILTERS, SolegramFiltersActivity.ID_HIDE_SPONSORED, R.string.SG_HideSponsored, 15},
        {PAGE_FILTERS, SolegramFiltersActivity.ID_ADD, R.string.SG_FilterAdd, 15},
        {PAGE_FILTERS, SolegramFiltersActivity.ID_STATS_TODAY, R.string.SG_FiltersStats, 15},
        {PAGE_FILTERS, SolegramFiltersActivity.ID_EXCLUDED, R.string.SG_FiltersExcluded, 15},
        {PAGE_FILTERS, SolegramFiltersActivity.ID_EXPORT, R.string.SG_FiltersExport, 15},
        {PAGE_REMINDERS, 0, R.string.SG_Reminders, 16},
        {PAGE_REMINDERS, SolegramRemindersActivity.ID_AI, R.string.SG_ReminderAiSetting, 16},
        {PAGE_REMINDERS, SolegramRemindersActivity.ID_MORNING, R.string.SG_ReminderMorningTime, 16},
        {PAGE_REMINDERS, SolegramRemindersActivity.ID_EVENING, R.string.SG_ReminderEveningTime, 16},
        {PAGE_REMINDERS, SolegramRemindersActivity.ID_EXACT, R.string.SG_ReminderExact, 16},
        {PAGE_REMINDERS, SolegramRemindersActivity.ID_SOUND, R.string.SG_ReminderSound, 16},
    };

    private static final String[] SEARCH_KEYWORDS = {
        "solegram солеграм солеграмм",
        "liquid glass жидкое стекло стекло матовое blur fluid motion капли анимации вытекают",
        "ai ии пересказ summary gpt нейросеть",
        "перевод переводчик translate translation",
        "spotify soundcloud спотифай саундклауд музыка music трек слушаю",
        "ghost призрак невидимка прочитано онлайн",
        "удалённые удаленные изменённые deleted edits история",
        "fps кадры кадров кружки видео камера",
        "adblock adguard ublock реклама блокировщик браузер",
        "обновления update версия",
        "резервная копия backup экспорт импорт",
        "прокси proxy mtproto socks блокировка блокировки обход vpn впн не подключается соединение",
        "расшифровка голосовых голосовые транскрипция распознавание речи текст кружки speech to text transcribe voice whisper",
        "gemini nano джемини гемини нано на устройстве офлайн без интернета on-device offline aicore ии ai пересказ summary сервис",
        "плагины plugins extera ayugram безопасность проверка вредоносный вирус опасные блокировка security malware",
        "фильтры фильтр сообщений скрыть спрятать реклама спам мат слова регулярные выражения regex zalgo erid ayugram swiftgram filter hide спонсорские sponsored ads",
        "напоминания напоминание напомнить напоминалка будильник позже отложить дела задачи remind reminder reminders alarm later snooze todo",
        "ссылки ссылка профили соцсети социальные сети о себе био инстаграм тикток ютуб гитхаб вк твич дискорд стим сайт links bio instagram tiktok youtube github vk twitch discord steam x twitter website",
        "иконки значки иконка набор пак стиль тонкие линейные заливка залитые phosphor icons icon pack thin outline filled",
    };

    /** Solegram's pages and settings as results of Telegram's settings search. */
    public static org.telegram.ui.ProfileActivity.SearchAdapter.SearchResult[] getSearchResults(org.telegram.ui.ActionBar.BaseFragment fragment) {
        final ArrayList<org.telegram.ui.ProfileActivity.SearchAdapter.SearchResult> results = new ArrayList<>();
        final String root = LocaleController.getString(R.string.SG_SettingsTitle);
        for (int[] entry : SEARCH) {
            final int page = entry[0], itemId = entry[1];
            final String title = LocaleController.getString(entry[2]);
            final String pageTitle = pageTitle(page);
            final Runnable open = () -> {
                if (page == PAGE_FILTERS) {
                    final SolegramFiltersActivity filters = new SolegramFiltersActivity();
                    fragment.presentFragment(filters);
                    if (itemId != 0) {
                        filters.highlightItem(itemId);
                    }
                    return;
                }
                if (page == PAGE_REMINDERS) {
                    final SolegramRemindersActivity reminders = new SolegramRemindersActivity();
                    fragment.presentFragment(reminders);
                    if (itemId != 0) {
                        reminders.highlightItem(itemId);
                    }
                    return;
                }
                if (page == PAGE_PLUGINS) {
                    final PluginsActivity plugins = new PluginsActivity();
                    fragment.presentFragment(plugins);
                    if (itemId != 0) {
                        plugins.highlightItem(itemId);
                    }
                    return;
                }
                if (page == PAGE_MAIN && itemId == 0) {
                    fragment.presentFragment(new SolegramSettingsActivity(PAGE_MAIN));
                    return;
                }
                final SolegramSettingsActivity activity = new SolegramSettingsActivity(page);
                fragment.presentFragment(activity);
                if (itemId != 0) {
                    activity.highlightItem(itemId);
                }
            };
            // stable ids, far above Telegram's own, so recent searches keep them
            final int guid = 20000 + page * 1000 + itemId;
            final org.telegram.ui.ProfileActivity.SearchAdapter.SearchResult result;
            if (page == PAGE_MAIN && itemId == 0) {
                result = new org.telegram.ui.ProfileActivity.SearchAdapter.SearchResult(guid, title, null, null, null, R.drawable.sg_settings_mark, open);
            } else if (itemId == 0) {
                result = new org.telegram.ui.ProfileActivity.SearchAdapter.SearchResult(guid, title, null, root, null, R.drawable.sg_settings_mark, open);
            } else {
                result = new org.telegram.ui.ProfileActivity.SearchAdapter.SearchResult(guid, title, null, root, page == PAGE_MAIN ? null : pageTitle, R.drawable.sg_settings_mark, open);
            }
            if (entry[3] >= 0) {
                result.withKeywords(SEARCH_KEYWORDS[entry[3]]);
            }
            results.add(result);
        }
        return results.toArray(new org.telegram.ui.ProfileActivity.SearchAdapter.SearchResult[0]);
    }

    private static String pageTitle(int page) {
        switch (page) {
            case PAGE_APPEARANCE:
                return LocaleController.getString(R.string.SG_Appearance);
            case PAGE_CHATS:
                return LocaleController.getString(R.string.SG_Chats);
            case PAGE_GHOST:
                return LocaleController.getString(R.string.SG_Privacy);
            case PAGE_GENERAL:
                return LocaleController.getString(R.string.SG_System);
            case PAGE_UPDATES:
                return LocaleController.getString(R.string.SG_Updates);
            case PAGE_ABOUT:
                return LocaleController.getString(R.string.SG_About);
            case PAGE_GLASS:
                return LocaleController.getString(R.string.SG_GlassSettings);
            case PAGE_AI:
                return LocaleController.getString(R.string.SG_AiPage);
            case PAGE_MAIN_SCREEN:
                return LocaleController.getString(R.string.SG_MainScreen);
            case PAGE_MUSIC:
                return LocaleController.getString(R.string.SG_NowPlaying);
            case PAGE_CAMERA:
                return LocaleController.getString(R.string.SG_Camera);
            case PAGE_BROWSER:
                return LocaleController.getString(R.string.SG_Browser);
            case PAGE_PROXY:
                return LocaleController.getString(R.string.SG_Proxy);
            case PAGE_PLUGINS:
                return LocaleController.getString(R.string.SG_Plugins);
            case PAGE_FILTERS:
                return LocaleController.getString(R.string.SG_Filters);
            case PAGE_REMINDERS:
                return LocaleController.getString(R.string.SG_Reminders);
            default:
                return LocaleController.getString(R.string.SG_SettingsTitle);
        }
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        // rounded section cards, as in Telegram's own settings
        listView.setSections();
        return fragmentView;
    }

    @Override
    protected CharSequence getTitle() {
        return pageTitle(page);
    }
    // endregion

    // region music, camera, browser

    private SolegramNowPlayingView nowPlayingPreview;

    private void fillMusic(ArrayList<UItem> items) {
        // the card as the profile shows it, with what plays now
        if (nowPlayingPreview == null) {
            nowPlayingPreview = new SolegramNowPlayingView(getContext(), getResourceProvider());
            nowPlayingPreview.setEmptyText(getString(R.string.SG_NowPlayingNothing));
            nowPlayingPreview.setOnClickListener(v -> {
                final com.solegram.messenger.SolegramNowPlaying.Track t = nowPlayingPreview.getTrack();
                if (t != null && getParentActivity() != null) {
                    com.solegram.messenger.SolegramNowPlaying.openPlayer(getParentActivity(), t.source);
                }
            });
        }
        nowPlayingPreview.setTrack(com.solegram.messenger.SolegramNowPlaying.get(), true);
        items.add(UItem.asHeader(getString(R.string.SG_NowPlayingPreview)));
        items.add(UItem.asCustom(nowPlayingPreview));
        items.add(UItem.asShadow(null));

        items.add(UItem.asRippleCheck(ID_NOW_PLAYING, getString(R.string.SG_NowPlayingSwitch)).setChecked(SolegramConfig.nowPlaying));
        items.add(UItem.asShadow(getString(R.string.SG_NowPlayingSwitchInfo)));
        if (!SolegramConfig.nowPlaying) {
            return;
        }
        final boolean access = com.solegram.messenger.SolegramNowPlaying.hasAccess();
        final UItem accessItem = tile(UItem.asButton(ID_NOW_PLAYING_ACCESS, R.drawable.msg2_permissions, getString(R.string.SG_NowPlayingAccess),
            getString(access ? R.string.SG_NowPlayingAccessOn : R.string.SG_NowPlayingAccessOff)), access ? TILE_GREEN : TILE_ORANGE);
        items.add(access ? accessItem : accessItem.accent());
        items.add(UItem.asShadow(getString(R.string.SG_NowPlayingAccessInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_NowPlayingSources)));
        items.add(UItem.asButtonCheck(ID_NOW_PLAYING_SPOTIFY, "Spotify", getString(com.solegram.messenger.SolegramNowPlaying.isInstalled(com.solegram.messenger.SolegramNowPlaying.SPOTIFY)
            ? R.string.SG_NowPlayingInstalled : R.string.SG_NowPlayingNotInstalled)).setChecked(SolegramConfig.nowPlayingSpotify));
        items.add(UItem.asButtonCheck(ID_NOW_PLAYING_SOUNDCLOUD, "SoundCloud", getString(com.solegram.messenger.SolegramNowPlaying.isInstalled(com.solegram.messenger.SolegramNowPlaying.SOUNDCLOUD)
            ? R.string.SG_NowPlayingInstalled : R.string.SG_NowPlayingNotInstalled)).setChecked(SolegramConfig.nowPlayingSoundCloud));
        items.add(UItem.asShadow(null));

        items.add(UItem.asButtonCheck(ID_NOW_PLAYING_SHARE, getString(R.string.SG_NowPlayingShare), getString(R.string.SG_NowPlayingShareInfo))
            .setChecked(SolegramConfig.nowPlayingShareBio));
        items.add(UItem.asShadow(null));
    }

    private void fillCamera(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.SG_CameraScreen)));
        items.add(UItem.asCheck(ID_CAMERA_CONTROLS, getString(R.string.SG_CameraControls)).setChecked(SolegramConfig.cameraControls));
        items.add(UItem.asShadow(getString(R.string.SG_CameraControlsInfo2)));

        items.add(UItem.asHeader(getString(R.string.SG_CameraPhotos)));
        items.add(UItem.asCheck(ID_CAMERA_ENHANCE, getString(R.string.SG_CameraEnhance)).setChecked(SolegramConfig.cameraEnhance));
        // the same setting as the HDR button on the camera screen
        items.add(UItem.asShadow(SolegramConfig.cameraControls ? getString(R.string.SG_CameraEnhanceInfo) + "\n\n" + getString(R.string.SG_CameraEnhanceHdr) : getString(R.string.SG_CameraEnhanceInfo)));
        items.add(UItem.asCheck(ID_CAMERA_HD, getString(R.string.SG_CameraHd)).setChecked(SolegramConfig.cameraHd));
        items.add(UItem.asShadow(getString(R.string.SG_CameraHdInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_CameraFps)));
        items.add(UItem.asRadio(ID_CAMERA_FPS_AUTO, getString(R.string.SG_CameraFpsAuto)).setChecked(SolegramConfig.cameraFps == 0));
        items.add(UItem.asRadio(ID_CAMERA_FPS_60, LocaleController.formatString(R.string.SG_CameraFpsValue, 60)).setChecked(SolegramConfig.cameraFps == 60));
        items.add(UItem.asRadio(ID_CAMERA_FPS_30, LocaleController.formatString(R.string.SG_CameraFpsValue, 30)).setChecked(SolegramConfig.cameraFps == 30));
        items.add(UItem.asShadow(getString(R.string.SG_CameraFpsInfo)));
    }

    private String cameraFpsName() {
        return SolegramConfig.cameraFps == 0 ? getString(R.string.SG_CameraFpsAuto) : LocaleController.formatString(R.string.SG_CameraFpsValue, SolegramConfig.cameraFps);
    }

    private void fillBrowser(ArrayList<UItem> items) {
        items.add(UItem.asRippleCheck(ID_ADBLOCK, getString(R.string.SG_AdBlock)).setChecked(SolegramConfig.adBlock));
        items.add(UItem.asShadow(getString(R.string.SG_AdBlockInfo)));
        if (SolegramConfig.adBlock) {
            items.add(UItem.asButtonCheck(ID_ADBLOCK_COSMETIC, getString(R.string.SG_AdBlockCosmetic), getString(R.string.SG_AdBlockCosmeticInfo)).setChecked(SolegramConfig.adBlockCosmetic));
            items.add(UItem.asButtonCheck(ID_ADBLOCK_YOUTUBE, getString(R.string.SG_AdBlockYouTube), getString(R.string.SG_AdBlockYouTubeInfo)).setChecked(SolegramConfig.adBlockYouTube));
            items.add(UItem.asButtonCheck(ID_ADBLOCK_POPUPS, getString(R.string.SG_AdBlockPopups), getString(R.string.SG_AdBlockPopupsInfo)).setChecked(SolegramConfig.adBlockPopups));
            items.add(UItem.asShadow(null));
            final int allowed = com.solegram.messenger.SolegramAdBlock.allowedCount();
            items.add(tile(UItem.asButton(ID_ADBLOCK_TOTAL, R.drawable.msg_block2, getString(R.string.SG_AdBlockTotal),
                LocaleController.formatNumber(com.solegram.messenger.SolegramAdBlock.getTotal(), ' ')), TILE_RED));
            items.add(tile(UItem.asButton(ID_ADBLOCK_ALLOWED, R.drawable.msg_language, getString(R.string.SG_AdBlockAllowed),
                allowed == 0 ? getString(R.string.SG_AdBlockAllowedNone) : String.valueOf(allowed)), TILE_TEAL));
            items.add(UItem.asShadow(null));
        }
        items.add(tile(UItem.asButton(ID_BROWSER_SETTINGS, R.drawable.msg_settings, getString(R.string.SG_AdBlockOpenBrowserSettings)), TILE_GRAY));
        items.add(UItem.asShadow(null));
    }

    private void showAllowedSites() {
        final String[] sites = com.solegram.messenger.SolegramAdBlock.getAllowed();
        if (sites == null || sites.length == 0 || getParentActivity() == null) {
            return;
        }
        final org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getString(R.string.SG_AdBlockAllowed));
        builder.setMessage(TextUtils.join("\n", sites));
        builder.setPositiveButton(getString(R.string.SG_AdBlockClearAllowed), (d, w) -> {
            com.solegram.messenger.SolegramAdBlock.clearAllowed();
            listView.adapter.update(true);
        });
        builder.setNegativeButton(getString(R.string.Close), null);
        showDialog(builder.create());
    }

    private void fillProxy(ArrayList<UItem> items) {
        final int phase = SolegramProxy.getPhase();
        final CharSequence status;
        if (phase == SolegramProxy.PHASE_DOWNLOADING) {
            status = getString(R.string.SG_ProxyLoading);
        } else if (phase == SolegramProxy.PHASE_CHECKING) {
            status = LocaleController.formatString(R.string.SG_ProxyChecking, SolegramProxy.getProgress(), SolegramProxy.getProgressTotal());
        } else if (phase == SolegramProxy.PHASE_TRYING) {
            status = LocaleController.formatString(R.string.SG_ProxyTrying, SolegramProxy.getProgress(), SolegramProxy.getProgressTotal());
        } else if (SolegramProxy.getLastHost() != null) {
            final long ping = SolegramProxy.getLastPing();
            status = ping > 0
                ? LocaleController.formatString(R.string.SG_ProxyLast, SolegramProxy.getLastHost(), (int) ping, SolegramProxy.getLastWorking())
                : LocaleController.formatString(R.string.SG_ProxyLastNoPing, SolegramProxy.getLastHost());
        } else {
            status = getString(R.string.SG_ProxyFindSub);
        }
        // while searching the value says how to stop it
        final UItem find = phase == SolegramProxy.PHASE_IDLE
            ? UItem.asButton(ID_PROXY_FIND, R.drawable.msg_search, getString(R.string.SG_ProxyFind))
            : UItem.asButton(ID_PROXY_FIND, R.drawable.msg_search, getString(R.string.SG_ProxyFind), getString(R.string.Cancel));
        items.add(tile(find, TILE_GREEN).setSolegramSubtitle(status));
        final String host = SolegramProxy.getCurrentHost();
        items.add(UItem.asButtonCheck(ID_PROXY_USE, getString(R.string.SG_ProxyUse),
            host != null ? LocaleController.formatString(R.string.SG_ProxyVia, host) : getString(R.string.SG_ProxyDirect)).setChecked(host != null));
        items.add(UItem.asShadow(null));

        items.add(UItem.asButtonCheck(ID_PROXY_AUTO, getString(R.string.SG_ProxyAuto), getString(R.string.SG_ProxyAutoSub)).setChecked(SolegramConfig.proxyAuto));
        items.add(UItem.asShadow(getString(R.string.SG_ProxyAutoInfo)));

        items.add(tile(UItem.asButton(ID_PROXY_LIST, R.drawable.msg2_data, getString(R.string.SG_ProxyAll),
            String.valueOf(org.telegram.messenger.SharedConfig.proxyList.size())), TILE_BLUE));
        final int added = SolegramProxy.getAddedCount();
        if (added > 0) {
            items.add(UItem.asButton(ID_PROXY_CLEAR, R.drawable.msg_delete, getString(R.string.SG_ProxyClear), String.valueOf(added)).red());
        }
        items.add(UItem.asShadow(getString(R.string.SG_ProxyInfo)));
    }

    private void showProxyResult(SolegramProxy.Result r) {
        if (getParentActivity() == null) {
            return;
        }
        switch (r.code) {
            case SolegramProxy.RESULT_OK: {
                final String text = r.ping > 0
                    ? LocaleController.formatString(R.string.SG_ProxyFound, r.host, (int) r.ping, r.working)
                    : LocaleController.formatString(R.string.SG_ProxyFoundNoPing, r.host);
                if (r.fromCache) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.done, text, getString(R.string.SG_ProxyFromCache)).show();
                } else {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.done, text).show();
                }
                break;
            }
            case SolegramProxy.RESULT_NO_LIST:
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.SG_ProxyNoList), 5).show();
                break;
            case SolegramProxy.RESULT_NONE_WORKING:
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.SG_ProxyNoneWorking, r.checked), 5).show();
                break;
            case SolegramProxy.RESULT_CANCELLED:
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.SG_ProxyStopped)).show();
                break;
        }
    }

    private void setCameraFps(int fps) {
        SolegramConfig.putInt("cameraFps", fps);
        listView.adapter.update(true);
    }

    private void askMusicAccess() {
        if (getParentActivity() == null) {
            return;
        }
        final org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(getString(R.string.SG_NowPlayingAccessTitle));
        builder.setMessage(getString(R.string.SG_NowPlayingAccessText));
        builder.setPositiveButton(getString(R.string.SG_NowPlayingAccessOpen), (d, w) -> {
            try {
                getParentActivity().startActivity(com.solegram.messenger.SolegramNowPlaying.accessSettingsIntent());
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void musicSettingChanged(View view, String key) {
        toggleCheck(view, key);
        com.solegram.messenger.SolegramNowPlaying.onSettingsChanged();
        listView.adapter.update(true);
    }
    // endregion

    private static String aiHost() {
        try {
            final String host = android.net.Uri.parse(SolegramConfig.aiBaseUrl.trim()).getHost();
            return TextUtils.isEmpty(host) ? SolegramConfig.aiBaseUrl : host;
        } catch (Exception e) {
            return SolegramConfig.aiBaseUrl;
        }
    }

    // the picker lists the modes in this order; the values stay those saved by older versions
    private static final int[] AI_PROVIDER_ORDER = {
        com.solegram.messenger.SolegramSummarizer.PROVIDER_AUTO,
        com.solegram.messenger.SolegramSummarizer.PROVIDER_NANO,
        com.solegram.messenger.SolegramSummarizer.PROVIDER_FREE,
        com.solegram.messenger.SolegramSummarizer.PROVIDER_OWN,
    };

    private String aiProviderName(int provider) {
        switch (provider) {
            case com.solegram.messenger.SolegramSummarizer.PROVIDER_AUTO:
                return getString(R.string.SG_AiProviderAuto);
            case com.solegram.messenger.SolegramSummarizer.PROVIDER_NANO:
                return getString(R.string.SG_AiProviderNano);
            case com.solegram.messenger.SolegramSummarizer.PROVIDER_OWN:
                return getString(R.string.SG_AiProviderOwn);
            default:
                return getString(R.string.SG_AiProviderFree);
        }
    }

    private static int aiPrivacyText(int provider) {
        switch (provider) {
            case com.solegram.messenger.SolegramSummarizer.PROVIDER_AUTO:
                return R.string.SG_AiPrivacyAuto;
            case com.solegram.messenger.SolegramSummarizer.PROVIDER_NANO:
                return R.string.SG_AiPrivacyNano;
            case com.solegram.messenger.SolegramSummarizer.PROVIDER_OWN:
                return R.string.SG_AiPrivacyOwn;
            default:
                return R.string.SG_AiPrivacyFree;
        }
    }

    /** "Ready", "Downloading 42%", "Download"... for the Gemini Nano row. */
    private String nanoStatusText() {
        switch (com.solegram.messenger.SolegramGeminiNano.status()) {
            case com.solegram.messenger.SolegramGeminiNano.STATUS_AVAILABLE: {
                final String model = com.solegram.messenger.SolegramGeminiNano.modelName();
                return TextUtils.isEmpty(model) ? getString(R.string.SG_NanoReady) : getString(R.string.SG_NanoReady) + " · " + model;
            }
            case com.solegram.messenger.SolegramGeminiNano.STATUS_DOWNLOADING: {
                final int percent = com.solegram.messenger.SolegramGeminiNano.downloadPercent();
                return percent < 0 ? getString(R.string.SG_NanoDownloadingNow) : LocaleController.formatString(R.string.SG_NanoDownloading, percent);
            }
            case com.solegram.messenger.SolegramGeminiNano.STATUS_DOWNLOADABLE:
                return getString(com.solegram.messenger.SolegramGeminiNano.lastDownloadFailed() ? R.string.SG_NanoDownloadRetry : R.string.SG_NanoDownload);
            case com.solegram.messenger.SolegramGeminiNano.STATUS_CHECKING:
                return getString(R.string.SG_NanoChecking);
            default:
                return getString(R.string.SG_NanoUnsupportedShort);
        }
    }

    private void onNanoClick() {
        if (getParentActivity() == null) {
            return;
        }
        final int status = com.solegram.messenger.SolegramGeminiNano.status();
        if (status == com.solegram.messenger.SolegramGeminiNano.STATUS_DOWNLOADABLE) {
            if (com.solegram.messenger.SolegramGeminiNano.onUnmeteredNetwork()) {
                startNanoDownload();
                return;
            }
            // mobile data: the model is a few gigabytes, so ask first
            showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.SG_NanoDownloadTitle))
                .setMessage(getString(R.string.SG_NanoDownloadMetered))
                .setPositiveButton(getString(R.string.SG_NanoDownloadAction), (d, w) -> startNanoDownload())
                .setNegativeButton(getString(R.string.Cancel), null)
                .create());
        } else if (status == com.solegram.messenger.SolegramGeminiNano.STATUS_UNSUPPORTED) {
            // Android may have received its AI configuration since the last look
            com.solegram.messenger.SolegramGeminiNano.refresh(true);
            showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(getString(R.string.SG_NanoTitle))
                .setMessage(getString(R.string.SG_NanoUnsupportedInfo))
                .setPositiveButton(getString(R.string.OK), null)
                .create());
        } else {
            com.solegram.messenger.SolegramGeminiNano.refresh(true);
        }
    }

    private void startNanoDownload() {
        if (com.solegram.messenger.SolegramGeminiNano.download()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_download, getString(R.string.SG_NanoDownloadStarted)).show();
        }
        listView.adapter.update(true);
    }

    private void showAiProviderPicker() {
        if (getParentActivity() == null) {
            return;
        }
        final boolean nanoSupported = com.solegram.messenger.SolegramGeminiNano.isSupported();
        final String[] names = new String[AI_PROVIDER_ORDER.length];
        int selected = 0;
        for (int i = 0; i < AI_PROVIDER_ORDER.length; i++) {
            final int provider = AI_PROVIDER_ORDER[i];
            switch (provider) {
                case com.solegram.messenger.SolegramSummarizer.PROVIDER_AUTO:
                    names[i] = getString(R.string.SG_AiProviderAutoLong);
                    break;
                case com.solegram.messenger.SolegramSummarizer.PROVIDER_NANO:
                    names[i] = getString(R.string.SG_AiProviderNanoLong)
                        + (nanoSupported ? "" : " (" + getString(R.string.SG_NanoUnsupportedShort).toLowerCase(java.util.Locale.getDefault()) + ")");
                    break;
                case com.solegram.messenger.SolegramSummarizer.PROVIDER_OWN:
                    names[i] = getString(R.string.SG_AiProviderOwnLong);
                    break;
                default:
                    names[i] = getString(R.string.SG_AiProviderFreeLong);
                    break;
            }
            if (provider == SolegramConfig.aiProvider) {
                selected = i;
            }
        }
        showDialog(org.telegram.ui.Components.AlertsCreator.createSingleChoiceDialog(getParentActivity(), names, getString(R.string.SG_AiProvider), selected, (d, which) -> {
            final int provider = AI_PROVIDER_ORDER[which];
            if (provider == com.solegram.messenger.SolegramSummarizer.PROVIDER_NANO && !com.solegram.messenger.SolegramGeminiNano.isSupported()) {
                // nothing would ever be summarized: say why instead of switching
                com.solegram.messenger.SolegramGeminiNano.refresh(true);
                showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                    .setTitle(getString(R.string.SG_NanoTitle))
                    .setMessage(getString(R.string.SG_NanoUnsupportedInfo))
                    .setPositiveButton(getString(R.string.OK), null)
                    .create());
                return;
            }
            SolegramConfig.putInt("aiMode", provider);
            if (com.solegram.messenger.SolegramSummarizer.usesNano()) {
                // picking Nano is the consent to fetch it; on mobile data the row offers the download instead
                com.solegram.messenger.SolegramGeminiNano.downloadIfUnmetered();
            }
            listView.adapter.update(true);
            if (provider == com.solegram.messenger.SolegramSummarizer.PROVIDER_OWN && TextUtils.isEmpty(SolegramConfig.aiBaseUrl)) {
                askAiUrl();
            }
        }));
    }

    private void askAiUrl() {
        askText(getString(R.string.SG_AiUrl), getString(R.string.SG_AiUrlInfo), SolegramConfig.aiBaseUrl, "https://", text -> {
            String url = text.trim();
            if (!url.isEmpty() && !url.startsWith("https://") && !url.startsWith("http://")) {
                url = "https://" + url;
            }
            SolegramConfig.prefs().edit().putString("aiBaseUrl", url).apply();
            SolegramConfig.reload();
            listView.adapter.update(true);
        });
    }

    private void showAiLanguagePicker() {
        if (getParentActivity() == null) {
            return;
        }
        final String[] names = {getString(R.string.SG_AiLanguageMessage), getString(R.string.SG_AiLanguageTarget) + " (" + translateTargetName() + ")"};
        showDialog(org.telegram.ui.Components.AlertsCreator.createSingleChoiceDialog(getParentActivity(), names, getString(R.string.SG_AiLanguage), SolegramConfig.aiSummaryLanguage, (d, which) -> {
            SolegramConfig.putInt("aiSummaryLanguage", which);
            listView.adapter.update(true);
        }));
    }

    private static String translateTargetName() {
        final String code = org.telegram.ui.Components.TranslateAlert2.getToLanguage();
        final String name = org.telegram.ui.Components.TranslateAlert2.languageName(code);
        return name == null ? code : org.telegram.ui.Components.TranslateAlert2.capitalFirst(name);
    }

    private void showTranslateTargetPicker() {
        if (getParentActivity() == null) {
            return;
        }
        final ArrayList<org.telegram.messenger.TranslateController.Language> languages = org.telegram.messenger.TranslateController.getLanguages();
        final String current = org.telegram.ui.Components.TranslateAlert2.getToLanguage();
        final CharSequence[] names = new CharSequence[languages.size()];
        for (int i = 0; i < languages.size(); i++) {
            final org.telegram.messenger.TranslateController.Language l = languages.get(i);
            final boolean selected = l.code.equals(current) || "nb".equals(l.code) && "no".equals(current);
            names[i] = (selected ? "✓  " : "") + l.displayName + (l.ownDisplayName != null && !l.ownDisplayName.equals(l.displayName) ? " · " + l.ownDisplayName : "");
        }
        final org.telegram.ui.ActionBar.AlertDialog.Builder builder = new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.SG_TranslateTo));
        builder.setItems(names, (d, which) -> {
            org.telegram.ui.Components.TranslateAlert2.setToLanguage(languages.get(which).code);
            listView.adapter.update(true);
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void fillGhost(ArrayList<UItem> items) {
        items.add(UItem.asRippleCheck(ID_GHOST_MASTER, getString(R.string.SG_GhostMode)).setChecked(SolegramConfig.isGhostModeActive()));
        items.add(UItem.asShadow(getString(R.string.SG_GhostModeInfo)));
        items.add(UItem.asHeader(getString(R.string.SG_GhostOptions)));
        items.add(UItem.asCheck(ID_GHOST_TYPING, getString(R.string.SG_GhostTyping)).setChecked(SolegramConfig.ghostNoTyping));
        items.add(UItem.asCheck(ID_GHOST_READ, getString(R.string.SG_GhostRead)).setChecked(SolegramConfig.ghostNoRead));
        if (SolegramConfig.ghostNoRead) {
            items.add(UItem.asButtonCheck(ID_GHOST_READ_INTERACT, getString(R.string.SG_GhostReadOnInteract), getString(R.string.SG_GhostReadOnInteractInfo)).setChecked(SolegramConfig.ghostReadOnInteract));
        }
        items.add(UItem.asCheck(ID_GHOST_OFFLINE, getString(R.string.SG_GhostOffline)).setChecked(SolegramConfig.ghostOffline));
        items.add(UItem.asCheck(ID_GHOST_STORIES, getString(R.string.SG_GhostStories)).setChecked(SolegramConfig.ghostNoStoryRead));
        items.add(UItem.asCheck(ID_GHOST_MENU, getString(R.string.SG_GhostInMenu)).setChecked(SolegramConfig.ghostInDrawer));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.SG_History)));
        items.add(UItem.asButtonCheck(ID_SAVE_DELETED, getString(R.string.SG_SaveDeleted), getString(R.string.SG_SaveDeletedInfo)).setChecked(SolegramConfig.saveDeletedMessages));
        items.add(UItem.asButtonCheck(ID_SAVE_EDITS, getString(R.string.SG_SaveEdits), getString(R.string.SG_SaveEditsInfo)).setChecked(SolegramConfig.saveEditsHistory));
        items.add(UItem.asButton(ID_CLEAR_VAULT, R.drawable.msg_clear, getString(R.string.SG_ClearVault), String.valueOf(MessageVault.deletedCount())).red());
        items.add(UItem.asShadow(getString(R.string.SG_HistoryInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_Profiles)));
        items.add(UItem.asCheck(ID_HIDE_PHONE, getString(R.string.SG_HidePhone)).setChecked(SolegramConfig.hidePhoneNumber));
        items.add(UItem.asShadow(null));
    }

    private void fillGeneral(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.SG_Notifications)));
        items.add(UItem.asButtonCheck(ID_BACKGROUND, getString(R.string.SG_BackgroundConnection), getString(R.string.SG_BackgroundConnectionInfo)).setChecked(SolegramConfig.backgroundConnection));
        items.add(tile(UItem.asButton(ID_BATTERY, R.drawable.msg2_battery, getString(R.string.SG_BatteryOptimization), isIgnoringBatteryOptimizations() ? getString(R.string.SG_Off) : getString(R.string.SG_On)), TILE_GREEN));
        items.add(UItem.asShadow(getString(R.string.SG_NotificationsInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_Behaviour)));
        items.add(UItem.asCheck(ID_CONFIRM_CALLS, getString(R.string.SG_ConfirmCalls)).setChecked(SolegramConfig.confirmCalls));
        items.add(UItem.asCheck(ID_PROXIMITY, getString(R.string.SG_DisableProximity)).setChecked(SolegramConfig.disableProximitySensor));
        items.add(UItem.asButtonCheck(ID_NO_VIBRATION, getString(R.string.SG_DisableVibration), getString(R.string.SG_DisableVibrationInfo)).setChecked(SolegramConfig.disableVibration));
        items.add(UItem.asShadow(null));

        // accounts: up to 16 for free (UserConfig.MAX_ACCOUNT_COUNT)
        final int maxAccounts = org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT;
        final int accounts = org.telegram.messenger.UserConfig.getActivatedAccountsCount();
        items.add(UItem.asHeader(getString(R.string.SG_Accounts)));
        items.add(tile(UItem.asButton(ID_ADD_ACCOUNT, R.drawable.outline_add_account, getString(R.string.AddAccount), accounts + " / " + maxAccounts), TILE_BLUE));
        items.add(UItem.asShadow(getString(R.string.SG_AccountsInfo)));

    }

    private void fillMainScreen(ArrayList<UItem> items) {
        items.add(UItem.asHeader(getString(R.string.SG_ChatsScreen)));
        items.add(UItem.asButtonCheck(ID_REMEMBER_FOLDER, getString(R.string.SG_RememberFolder), getString(R.string.SG_RememberFolderInfo)).setChecked(SolegramConfig.rememberFolder));
        items.add(UItem.asCheck(ID_MARK_ALL_READ, getString(R.string.SG_MarkAllReadInMenu)).setChecked(SolegramConfig.markAllReadInMenu));
        items.add(UItem.asCheck(ID_SAVED_IN_MENU, getString(R.string.SG_SavedInMenu)).setChecked(SolegramConfig.savedInMenu));
        items.add(UItem.asCheck(ID_HIDE_CONTACTS_TAB, getString(SolegramConfig.feedInsteadOfContacts ? R.string.SG_HideFeedTab : R.string.SG_HideContactsTab)).setChecked(SolegramConfig.hideContactsTab));
        items.add(UItem.asCheck(ID_HIDE_PROFILE_TAB, getString(R.string.SG_HideProfileTab)).setChecked(SolegramConfig.hideProfileTab));
        items.add(tile(UItem.asButton(ID_MARK_ALL_READ_NOW, R.drawable.msg_markread, getString(R.string.SG_MarkAllRead)), TILE_BLUE));
        items.add(UItem.asShadow(getString(R.string.SG_ChatsScreenInfo)));

        items.add(UItem.asHeader(getString(R.string.SG_Feed)));
        items.add(UItem.asButtonCheck(ID_FEED_TAB, getString(R.string.SG_FeedTab), getString(R.string.SG_FeedTabInfo)).setChecked(SolegramConfig.feedInsteadOfContacts));
        items.add(UItem.asButtonCheck(ID_CONTACTS_SWIPE, getString(R.string.SG_ContactsSwipe), getString(R.string.SG_ContactsSwipeInfo)).setChecked(SolegramConfig.contactsSwipe));
        items.add(UItem.asCheck(ID_FEED_SKIP_MUTED, getString(R.string.SG_FeedSkipMuted)).setChecked(SolegramConfig.feedSkipMuted));
        items.add(tile(UItem.asButton(ID_OPEN_FEED, R.drawable.msg_channel, getString(R.string.SG_OpenFeed)), TILE_BLUE));
        items.add(UItem.asShadow(getString(R.string.SG_FeedInfo)));

    }

    private void fillUpdates(ArrayList<UItem> items) {
        UpdateController uc = UpdateController.getInstance();
        items.add(UItem.asCustomShadow(headerView()).setChecked(true)); // full width: the haze dissolves into the page, not a card; checked = its cell does not clip (the haze reaches the top)
        items.add(UItem.asShadow(null));
        String value;
        if (uc.isChecking()) {
            value = getString(R.string.SG_Checking);
        } else if (uc.hasPendingUpdate()) {
            value = uc.getPendingVersionName();
        } else {
            value = "";
        }
        items.add(UItem.asButton(ID_CHECK_UPDATE, R.drawable.msg_download, uc.hasPendingUpdate() ? getString(R.string.SG_InstallUpdate) : getString(R.string.SG_CheckUpdates), value).accent());
        items.add(tile(UItem.asButton(ID_UPDATE_BROWSER, R.drawable.msg_language, getString(R.string.SG_UpdateInBrowser)), TILE_GREEN));
        items.add(tile(UItem.asButton(ID_AUTO_UPDATE, R.drawable.msg_retry, getString(R.string.SG_AutoUpdate), autoUpdateModeName()), TILE_ORANGE));
        if (SolegramConfig.autoUpdateMode == SolegramConfig.AUTO_UPDATE_INSTALL) {
            items.add(UItem.asCheck(ID_UPDATE_WIFI, getString(R.string.SG_UpdateWifiOnly)).setChecked(SolegramConfig.updateWifiOnly));
        }
        final boolean github = SolegramConfig.updateSource == SolegramConfig.UPDATE_SOURCE_GITHUB;
        items.add(tile(UItem.asButton(ID_UPDATE_SOURCE, R.drawable.msg_download, getString(R.string.SG_UpdateSource), getString(github ? R.string.SG_UpdateSourceGithub : R.string.SG_UpdateSourceTelegram)), TILE_INDIGO));
        if (github) {
            items.add(tile(UItem.asButton(ID_UPDATE_REPO, R.drawable.msg_link, getString(R.string.SG_UpdateRepo), SolegramConfig.getUpdateRepo()), TILE_GRAY));
        } else {
            items.add(tile(UItem.asButton(ID_UPDATE_CHANNEL, R.drawable.msg_channel, getString(R.string.SG_UpdateChannel), "@" + SolegramConfig.getUpdateChannel()), TILE_CYAN));
        }
        String last = SolegramConfig.lastUpdateCheck > 0 ? LocaleController.formatDateAudio(SolegramConfig.lastUpdateCheck / 1000, true) : getString(R.string.SG_Never);
        String info = LocaleController.formatString(R.string.SG_UpdatesInfo, last);
        final String status = com.solegram.messenger.updater.AutoUpdater.getStatusText();
        if (status != null) {
            info += "\n" + LocaleController.formatString(R.string.SG_UpdateStatus, status);
        }
        items.add(UItem.asShadow(info));
    }

    private String autoUpdateModeName() {
        switch (SolegramConfig.autoUpdateMode) {
            case SolegramConfig.AUTO_UPDATE_OFF:
                return getString(R.string.SG_Off);
            case SolegramConfig.AUTO_UPDATE_NOTIFY:
                return getString(R.string.SG_AutoUpdateNotify);
            default:
                return getString(R.string.SG_AutoUpdateInstallShort);
        }
    }

    private void fillAbout(ArrayList<UItem> items) {
        items.add(UItem.asCustomShadow(headerView()).setChecked(true)); // full width: the haze dissolves into the page, not a card; checked = its cell does not clip (the haze reaches the top)
        items.add(UItem.asShadow(getString(R.string.SG_AboutText)));
        items.add(tile(UItem.asButton(ID_GITHUB, R.drawable.msg_link2, getString(R.string.SG_Github), SolegramConfig.getUpdateRepo()), TILE_INDIGO));
        items.add(tile(UItem.asButton(ID_CHANNEL, R.drawable.msg_channel, getString(R.string.SG_Channel), "@" + SolegramConfig.getUpdateChannel()), TILE_BLUE));
        items.add(tile(UItem.asButton(ID_SOURCE, R.drawable.msg_link, getString(R.string.SG_SourceCode)), TILE_GRAY));
        items.add(tile(UItem.asButton(ID_LICENSE, R.drawable.msg_info, getString(R.string.SG_License), "GPL-2.0"), TILE_GRAY));
        items.add(UItem.asShadow(getString(R.string.SG_Credits) + " " + getString(R.string.SG_CreditsIcons)));
    }
    // endregion

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            // new categories
            case ID_GLASS_PAGE:
                presentFragment(new SolegramSettingsActivity(PAGE_GLASS));
                return;
            case ID_AI_PAGE:
                presentFragment(new SolegramSettingsActivity(PAGE_AI));
                return;
            case ID_MAIN_SCREEN:
                presentFragment(new SolegramSettingsActivity(PAGE_MAIN_SCREEN));
                return;
            case ID_MUSIC:
                presentFragment(new SolegramSettingsActivity(PAGE_MUSIC));
                return;
            case ID_CAMERA:
                presentFragment(new SolegramSettingsActivity(PAGE_CAMERA));
                return;
            case ID_BROWSER:
                presentFragment(new SolegramSettingsActivity(PAGE_BROWSER));
                return;
            case ID_PROXY:
                presentFragment(new SolegramSettingsActivity(PAGE_PROXY));
                return;
            case ID_FILTERS:
                presentFragment(new SolegramFiltersActivity());
                return;
            case ID_REMINDERS:
                presentFragment(new SolegramRemindersActivity());
                return;

            // music in the profile
            case ID_NOW_PLAYING:
                musicSettingChanged(view, "nowPlaying");
                if (SolegramConfig.nowPlaying && !com.solegram.messenger.SolegramNowPlaying.hasAccess()) {
                    askMusicAccess();
                }
                return;
            case ID_NOW_PLAYING_ACCESS:
                if (com.solegram.messenger.SolegramNowPlaying.hasAccess()) {
                    try {
                        getParentActivity().startActivity(com.solegram.messenger.SolegramNowPlaying.accessSettingsIntent());
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                } else {
                    askMusicAccess();
                }
                return;
            case ID_NOW_PLAYING_SPOTIFY:
                musicSettingChanged(view, "nowPlayingSpotify");
                return;
            case ID_NOW_PLAYING_SOUNDCLOUD:
                musicSettingChanged(view, "nowPlayingSoundCloud");
                return;
            case ID_NOW_PLAYING_SHARE:
                if (!SolegramConfig.nowPlayingShareBio) {
                    // the bio of the account open now gets the music
                    com.solegram.messenger.SolegramNowPlayingBio.setSharingAccount(currentAccount);
                }
                musicSettingChanged(view, "nowPlayingShareBio");
                return;

            // camera
            case ID_CAMERA_FPS_AUTO:
                setCameraFps(0);
                return;
            case ID_CAMERA_FPS_60:
                setCameraFps(60);
                return;
            case ID_CAMERA_FPS_30:
                setCameraFps(30);
                return;
            case ID_CAMERA_ENHANCE:
                toggleCheck(view, "cameraEnhance");
                return;
            case ID_CAMERA_HD:
                toggleCheck(view, "cameraHd");
                return;
            case ID_CAMERA_CONTROLS:
                toggleCheck(view, "cameraControls");
                listView.adapter.update(true);
                return;

            // browser
            case ID_ADBLOCK:
                toggleCheck(view, "adBlock");
                listView.adapter.update(true);
                return;
            case ID_ADBLOCK_COSMETIC:
                toggleCheck(view, "adBlockCosmetic");
                return;
            case ID_ADBLOCK_YOUTUBE:
                toggleCheck(view, "adBlockYouTube");
                return;
            case ID_ADBLOCK_POPUPS:
                toggleCheck(view, "adBlockPopups");
                return;
            case ID_ADBLOCK_ALLOWED:
                showAllowedSites();
                return;
            case ID_BROWSER_SETTINGS:
                presentFragment(new org.telegram.ui.web.WebBrowserSettings(null));
                return;

            // proxy
            case ID_PROXY_FIND:
                if (SolegramProxy.isSearching()) {
                    SolegramProxy.cancel();
                } else {
                    SolegramProxy.search(true);
                }
                listView.adapter.update(true);
                return;
            case ID_PROXY_USE:
                // nothing to turn on yet: find one
                if (!SolegramProxy.setProxyEnabled(!org.telegram.messenger.SharedConfig.isProxyEnabled())) {
                    SolegramProxy.search(true);
                }
                listView.adapter.update(true);
                return;
            case ID_PROXY_AUTO:
                toggleCheck(view, "proxyAuto");
                SolegramProxy.onAutoChanged();
                return;
            case ID_PROXY_LIST:
                presentFragment(new org.telegram.ui.ProxyListActivity());
                return;
            case ID_PROXY_CLEAR:
                confirm(getString(R.string.SG_ProxyClear), LocaleController.formatString(R.string.SG_ProxyClearConfirm, SolegramProxy.getAddedCount()),
                    getString(R.string.Delete), () -> {
                        SolegramProxy.removeAdded();
                        listView.adapter.update(true);
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.SG_ProxyCleared)).show();
                    });
                return;

            // main
            case ID_APPEARANCE:
                presentFragment(new SolegramSettingsActivity(PAGE_APPEARANCE));
                return;
            case ID_CHATS:
                presentFragment(new SolegramSettingsActivity(PAGE_CHATS));
                return;
            case ID_GHOST:
                presentFragment(new SolegramSettingsActivity(PAGE_GHOST));
                return;
            case ID_GENERAL:
                presentFragment(new SolegramSettingsActivity(PAGE_GENERAL));
                return;
            case ID_PLUGINS:
                presentFragment(new PluginsActivity());
                return;
            case ID_UPDATES:
                presentFragment(new SolegramSettingsActivity(PAGE_UPDATES));
                return;
            case ID_ABOUT:
                presentFragment(new SolegramSettingsActivity(PAGE_ABOUT));
                return;
            case ID_EXPORT:
                exportSettings();
                return;
            case ID_IMPORT:
                importSettings();
                return;
            case ID_RESET:
                confirm(getString(R.string.SG_ResetSettings), getString(R.string.SG_ResetSettingsConfirm), getString(R.string.SG_Reset), () -> {
                    SolegramConfig.resetAll();
                    com.solegram.messenger.SolegramNowPlaying.onSettingsChanged();
                    listView.adapter.update(true);
                    restartBulletin();
                });
                return;

            // appearance
            case ID_TEXTURED_GLASS: {
                if (!item.enabled) {
                    return;
                }
                final boolean value = !texturedGlassEnabled();
                if (Build.VERSION.SDK_INT >= 33) {
                    org.telegram.messenger.LiteMode.toggleFlag(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS, value);
                }
                if (org.telegram.messenger.SharedConfig.canBlurChat()) {
                    org.telegram.messenger.LiteMode.toggleFlag(org.telegram.messenger.LiteMode.FLAG_CHAT_BLUR, value);
                }
                if (view instanceof NotificationsCheckCell) {
                    ((NotificationsCheckCell) view).setChecked(value);
                } else if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
                // tabs and headers build their glass when created: reopen the app to see it everywhere
                restartBulletin();
                return;
            }
            case ID_GLASS_SETTINGS:
                presentFragment(new SolegramSettingsActivity(PAGE_GLASS));
                return;
            case ID_SMOOTH_TRANSITIONS:
                toggleCheck(view, "smoothTransitions");
                return;
            case ID_PRESS_SCALE:
                toggleCheck(view, "pressScale");
                return;
            case ID_ANIMATE_LISTS:
                toggleCheck(view, "animateLists");
                return;
            case ID_TYPING_ANIMATION:
                toggleCheck(view, "typingAnimation");
                return;
            case ID_NEUTRAL_OVERSCROLL:
                toggleCheck(view, "neutralOverscroll");
                restartBulletin();
                return;
            case ID_FROSTED_HEADERS:
                toggleCheck(view, "frostedHeaders");
                return;
            case ID_FLOATING_HEADER:
            case ID_HEADER_BUTTONS:
                // headers are built once per screen
                toggleCheck(view, item.id == ID_FLOATING_HEADER ? "floatingHeader" : "glassHeaderButtons");
                restartBulletin();
                return;
            case ID_TRANSLATE_HERE:
                toggleCheck(view, "translateHere");
                return;
            case ID_AI_SUMMARY:
                toggleCheck(view, "aiSummary");
                listView.adapter.update(true);
                return;
            case ID_AI_SUMMARY_BUTTON:
                toggleCheck(view, "aiSummaryButton");
                return;
            case ID_AI_PROVIDER:
                showAiProviderPicker();
                return;
            case ID_AI_NANO:
                onNanoClick();
                return;
            case ID_AI_URL:
                askAiUrl();
                return;
            case ID_AI_KEY:
            case ID_TRANSCRIBE_KEY:
                askText(getString(R.string.SG_AiKey), getString(R.string.SG_AiKeyInfo), SolegramConfig.aiApiKey, "sk-...", true, text -> {
                    SolegramConfig.prefs().edit().putString("aiApiKey", text.trim()).apply();
                    SolegramConfig.reload();
                    listView.adapter.update(true);
                });
                return;
            case ID_AI_MODEL:
                askText(getString(R.string.SG_AiModel), getString(R.string.SG_AiModelInfo), SolegramConfig.aiModel, "gpt-4o-mini", text -> {
                    SolegramConfig.prefs().edit().putString("aiModel", text.trim()).apply();
                    SolegramConfig.reload();
                    listView.adapter.update(true);
                });
                return;
            case ID_AI_LANGUAGE:
                showAiLanguagePicker();
                return;
            case ID_TRANSCRIBE:
                toggleCheck(view, "transcribe");
                listView.adapter.update(true);
                return;
            case ID_TRANSCRIBE_ENGINE:
                showTranscribeEnginePicker();
                return;
            case ID_TRANSCRIBE_URL:
                askAiUrl();
                return;
            case ID_TRANSCRIBE_MODEL:
                askText(getString(R.string.SG_TranscribeModel), getString(R.string.SG_TranscribeModelInfo), SolegramConfig.transcribeModel, com.solegram.messenger.SolegramTranscriber.defaultAiModel(), text -> {
                    SolegramConfig.prefs().edit().putString("transcribeModel", text.trim()).apply();
                    SolegramConfig.reload();
                    listView.adapter.update(true);
                });
                return;
            case ID_LIQUID_MOTION:
                toggleCheck(view, "liquidMotion");
                return;
            case ID_LIQUID_CONTROLS:
                toggleCheck(view, "liquidControls");
                listView.adapter.update(true);
                return;
            case ID_GLASS_CALL:
                // read when the call screen opens
                toggleCheck(view, "glassCallScreen");
                return;
            case ID_GLASS_MENUS:
            case ID_GLASS_DIALOGS:
            case ID_GLASS_SHEETS:
                if (!item.enabled) {
                    return;
                }
                toggleCheck(view, item.id == ID_GLASS_MENUS ? "glassMenus" : item.id == ID_GLASS_DIALOGS ? "glassDialogs" : "glassSheets");
                return;
            case ID_AVATAR_IN_CHATS:
                toggleCheck(view, "avatarShapeInChats");
                redrawEverything();
                return;
            case ID_TITLE_EMOJI:
                // Telegram's emoji status picker; the emoji shows next to the title on the chats screen
                if (titleEmojiPicker == null || !titleEmojiPicker.isShowing()) {
                    titleEmojiPicker = com.solegram.messenger.SolegramTitleEmoji.showPicker(this, view, 0, 0, null, null, null, () -> listView.adapter.update(true));
                }
                return;
            case ID_MAIN_TITLE:
                showMainTitleSelector();
                return;

            // glass
            case ID_GLASS_TINT:
                showGlassTintSelector();
                return;
            case ID_GLASS_PREVIEW_LOOK:
                toggleCheck(view, "glassPreviewLook");
                if (glassPreview != null) {
                    glassPreview.refresh();
                }
                restartBulletin();
                return;
            case ID_GLASS_SHADOW:
                toggleCheck(view, "glassShadow");
                if (glassPreview != null) {
                    glassPreview.refresh();
                }
                return;
            case ID_GLASS_VIBRANCY:
                toggleCheck(view, "glassVibrancy");
                restartBulletin();
                return;
            case ID_GLASS_RESTART:
                Solegram.restartApp(getParentActivity());
                return;
            case ID_SYSTEM_FONT:
                toggleCheck(view, "useSystemFont");
                restartBulletin();
                return;
            case ID_CENTER_TITLE:
                toggleCheck(view, "centerTitle");
                restartBulletin();
                return;
            case ID_MONET:
                toggleCheck(view, "monetAccent");
                MonetTheme.apply(getParentActivity());
                return;
            case ID_THEMES:
                presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC));
                return;
            case ID_HIDE_STORIES:
                toggleCheck(view, "hideStories");
                redrawEverything();
                return;
            case ID_HIDE_EMOJI_STATUS:
                toggleCheck(view, "hideEmojiStatus");
                redrawEverything();
                return;
            case ID_HIDE_PHONE:
                toggleCheck(view, "hidePhoneNumber");
                return;

            // chats
            case ID_DOUBLE_TAP:
                showDoubleTapSelector();
                return;
            case ID_IOS_INPUT_BAR:
                toggleCheck(view, "iosInputBar");
                return;
            case ID_SHOW_SECONDS:
                toggleCheck(view, "showSeconds");
                redrawEverything();
                return;
            case ID_SHOW_MESSAGE_ID:
                toggleCheck(view, "showMessageId");
                redrawEverything();
                return;
            case ID_HIDE_KEYBOARD:
                toggleCheck(view, "hideKeyboardOnScroll");
                return;
            case ID_NO_NEXT_CHANNEL:
                toggleCheck(view, "disableJumpToNextChannel");
                return;
            case ID_HIDE_SHARE:
                toggleCheck(view, "hideShareButton");
                redrawEverything();
                return;
            case ID_CONFIRM_STICKERS:
                toggleCheck(view, "confirmStickers");
                return;
            case ID_CONFIRM_VOICE:
                toggleCheck(view, "confirmVoice");
                return;
            case ID_SHOW_ID:
                toggleCheck(view, "showUserId");
                listView.adapter.update(true);
                return;
            case ID_SEND_SILENTLY:
                toggleCheck(view, "sendSilently");
                return;
            case ID_NO_LINK_PREVIEWS:
                toggleCheck(view, "disableLinkPreviews");
                return;
            case ID_VOICE_ONLY:
                toggleCheck(view, "voiceOnlyRecord");
                return;
            case ID_NO_SWIPE_REPLY:
                toggleCheck(view, "disableSwipeToReply");
                return;
            case ID_CONFIRM_GIFS:
                toggleCheck(view, "confirmGifs");
                return;
            case ID_MESSAGE_DETAILS:
                toggleCheck(view, "messageDetails");
                return;
            case ID_REMEMBER_FOLDER:
                toggleCheck(view, "rememberFolder");
                return;
            case ID_MARK_ALL_READ:
                toggleCheck(view, "markAllReadInMenu");
                return;
            case ID_HIDE_CONTACTS_TAB:
                toggleCheck(view, "hideContactsTab");
                restartBulletin();
                return;
            case ID_HIDE_PROFILE_TAB:
                toggleCheck(view, "hideProfileTab");
                restartBulletin();
                return;
            case ID_MARK_ALL_READ_NOW:
                com.solegram.messenger.SolegramUi.markAllRead(this);
                return;
            case ID_SAVED_IN_MENU:
                toggleCheck(view, "savedInMenu");
                return;
            case ID_FEED_TAB:
                toggleCheck(view, "feedInsteadOfContacts");
                listView.adapter.update(true);
                restartBulletin();
                return;
            case ID_CONTACTS_SWIPE:
                toggleCheck(view, "contactsSwipe");
                return;
            case ID_FEED_SKIP_MUTED:
                toggleCheck(view, "feedSkipMuted");
                com.solegram.messenger.FeedController.getInstance(currentAccount).reload();
                return;
            case ID_OPEN_FEED:
                presentFragment(new FeedActivity(new android.os.Bundle()));
                return;
            case ID_JUMP_TO_FIRST:
                toggleCheck(view, "jumpToFirstInMenu");
                return;
            case ID_FORWARD_NO_AUTHOR:
                toggleCheck(view, "forwardWithoutAuthor");
                return;
            case ID_PROFILE_LINKS:
                toggleCheck(view, "profileLinks");
                return;
            case ID_PROFILE_LINKS_EDIT:
                SolegramProfileLinksSheet.show(this);
                return;
            case ID_SHOW_DC:
                toggleCheck(view, "showDcInProfile");
                return;
            case ID_FREE_TRANSLATE:
                toggleCheck(view, "freeTranslate");
                listView.adapter.update(true);
                return;
            case ID_AUTO_TRANSLATE:
                toggleCheck(view, "autoTranslate");
                return;
            case ID_TRANSLATE_TO:
                showTranslateTargetPicker();
                return;
            case ID_DONT_TRANSLATE:
                presentFragment(new org.telegram.ui.RestrictedLanguagesSelectActivity());
                return;

            // ghost
            case ID_GHOST_MASTER: {
                boolean enable = !SolegramConfig.isGhostModeActive();
                SolegramConfig.setGhostMode(enable);
                if (view instanceof TextCheckCell) {
                    TextCheckCell cell = (TextCheckCell) view;
                    cell.setChecked(enable);
                    cell.setBackgroundColorAnimated(enable, Theme.getColor(enable ? Theme.key_windowBackgroundChecked : Theme.key_windowBackgroundUnchecked));
                }
                listView.adapter.update(true);
                return;
            }
            case ID_GHOST_TYPING:
                toggleCheck(view, "ghostNoTyping");
                listView.adapter.update(true);
                return;
            case ID_GHOST_READ:
                toggleCheck(view, "ghostNoRead");
                listView.adapter.update(true);
                return;
            case ID_GHOST_READ_INTERACT:
                toggleCheck(view, "ghostReadOnInteract");
                return;
            case ID_GHOST_OFFLINE:
                toggleCheck(view, "ghostOffline");
                GhostMode.onGhostModeChanged();
                listView.adapter.update(true);
                return;
            case ID_GHOST_STORIES:
                toggleCheck(view, "ghostNoStoryRead");
                listView.adapter.update(true);
                return;
            case ID_GHOST_MENU:
                toggleCheck(view, "ghostInDrawer");
                return;
            case ID_SAVE_DELETED:
                toggleCheck(view, "saveDeletedMessages");
                redrawEverything();
                return;
            case ID_SAVE_EDITS:
                toggleCheck(view, "saveEditsHistory");
                return;
            case ID_CLEAR_VAULT:
                confirm(getString(R.string.SG_ClearVault), getString(R.string.SG_ClearVaultConfirm), getString(R.string.Clear), () -> MessageVault.clearAll(() -> {
                    listView.adapter.update(true);
                    redrawEverything();
                }));
                return;

            // general
            case ID_BACKGROUND:
                toggleCheck(view, "backgroundConnection");
                BackgroundConnection.start(ApplicationLoader.applicationContext);
                BackgroundConnection.applyPushConnection();
                return;
            case ID_BATTERY:
                openBatterySettings();
                return;
            case ID_CONFIRM_CALLS:
                toggleCheck(view, "confirmCalls");
                return;
            case ID_PROXIMITY:
                toggleCheck(view, "disableProximitySensor");
                return;
            case ID_NO_VIBRATION:
                toggleCheck(view, "disableVibration");
                com.solegram.messenger.Solegram.applyVibrationSetting();
                return;
            case ID_ADD_ACCOUNT: {
                final int slot = org.telegram.messenger.UserConfig.getFreeAccountSlot();
                if (slot >= 0) {
                    presentFragment(new org.telegram.ui.LoginActivity(slot));
                } else {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.SG_AccountsInfo)).show();
                }
                return;
            }
            case ID_HIDE_PREMIUM:
                toggleCheck(view, "hidePremiumSettings");
                return;
            case ID_HIDE_GIFTS:
                toggleCheck(view, "hideGiftButtons");
                return;

            // updates
            case ID_CHECK_UPDATE: {
                UpdateController uc = UpdateController.getInstance();
                if (uc.hasPendingUpdate()) {
                    uc.showUpdateSheet(this);
                } else {
                    uc.checkNow(this, true);
                    listView.adapter.update(true);
                }
                return;
            }
            case ID_AUTO_UPDATE: {
                String[] names = {getString(R.string.SG_Off), getString(R.string.SG_AutoUpdateNotify), getString(R.string.SG_AutoUpdateInstall)};
                showDialog(org.telegram.ui.Components.AlertsCreator.createSingleChoiceDialog(getParentActivity(), names, getString(R.string.SG_AutoUpdate), SolegramConfig.autoUpdateMode, (d, which) -> {
                    SolegramConfig.putInt("autoUpdateMode", which);
                    com.solegram.messenger.updater.AutoUpdater.onSettingsChanged();
                    listView.adapter.update(true);
                    if (which == SolegramConfig.AUTO_UPDATE_INSTALL) {
                        // after the choice dialog has gone: the one-time "install unknown apps" explanation
                        AndroidUtilities.runOnUIThread(() -> com.solegram.messenger.updater.AutoUpdater.guideUnknownSources(this), 300);
                    }
                }));
                return;
            }
            case ID_UPDATE_WIFI:
                toggleCheck(view, "updateWifiOnly");
                com.solegram.messenger.updater.AutoUpdater.onSettingsChanged();
                return;
            case ID_UPDATE_BROWSER:
                UpdateController.getInstance().openInBrowser(getParentActivity());
                return;
            case ID_UPDATE_CHANNEL:
                askUpdateChannel();
                return;
            case ID_UPDATE_SOURCE: {
                String[] names = {getString(R.string.SG_UpdateSourceGithub), getString(R.string.SG_UpdateSourceTelegram)};
                showDialog(org.telegram.ui.Components.AlertsCreator.createSingleChoiceDialog(getParentActivity(), names, getString(R.string.SG_UpdateSource), SolegramConfig.updateSource, (d, which) -> {
                    SolegramConfig.putInt("updateSource", which);
                    listView.adapter.update(true);
                }));
                return;
            }
            case ID_UPDATE_REPO:
                askText(getString(R.string.SG_UpdateRepo), getString(R.string.SG_UpdateRepoInfo), SolegramConfig.getUpdateRepo(), Solegram.GITHUB_REPO, text -> {
                    SolegramConfig.putString("updateRepo", text);
                    listView.adapter.update(true);
                });
                return;

            // about
            case ID_CHANNEL:
                Browser.openUrl(getParentActivity(), "https://t.me/" + SolegramConfig.getUpdateChannel());
                return;
            case ID_GITHUB:
                Browser.openUrl(getParentActivity(), "https://github.com/" + SolegramConfig.getUpdateRepo());
                return;
            case ID_SOURCE:
                Browser.openUrl(getParentActivity(), Solegram.SOURCE_URL);
                return;
            case ID_LICENSE:
                Browser.openUrl(getParentActivity(), "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html");
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    // region helpers

    private void toggleCheck(View view, String key) {
        boolean value = SolegramConfig.toggle(key);
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        } else if (view instanceof NotificationsCheckCell) {
            ((NotificationsCheckCell) view).setChecked(value);
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.solegramSettingsChanged, key);
    }

    /** Same as the text size slider in Telegram's chat settings. */
    private static void applyMessageTextSize(int size) {
        org.telegram.messenger.SharedConfig.fontSize = size;
        org.telegram.messenger.SharedConfig.fontSizeIsDefault = false;
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).edit().putInt("fons_size", size).apply();
        Theme.createCommonMessageResources();
        redrawEverything();
    }

    /** Same as the corner radius slider in Telegram's chat settings. */
    private static void applyBubbleRadius(int radius) {
        org.telegram.messenger.SharedConfig.bubbleRadius = radius;
        org.telegram.messenger.MessagesController.getGlobalMainSettings().edit().putInt("bubbleRadius", radius).apply();
        redrawEverything();
    }

    /** Forces every visible chat/dialog cell to redraw so appearance options apply instantly. */
    public static void redrawEverything() {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.solegramSettingsChanged, "redraw");
        for (int a = 0; a < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (org.telegram.messenger.UserConfig.getInstance(a).isClientActivated()) {
                NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateInterfaces, org.telegram.messenger.MessagesController.UPDATE_MASK_ALL);
            }
        }
    }

    private void restartBulletin() {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.SG_RestartRequired), getString(R.string.SG_Restart), () -> Solegram.restartApp(getParentActivity())).show();
    }

    private void confirm(String title, String message, String action, Runnable onConfirm) {
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action, (d, w) -> onConfirm.run())
            .setNegativeButton(getString(R.string.Cancel), null)
            .create();
        showDialog(dialog);
        android.widget.TextView button = (android.widget.TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    public static String doubleTapName(int action) {
        switch (action) {
            case SolegramConfig.DoubleTap.REPLY:
                return getString(R.string.Reply);
            case SolegramConfig.DoubleTap.COPY:
                return getString(R.string.Copy);
            case SolegramConfig.DoubleTap.FORWARD:
                return getString(R.string.Forward);
            case SolegramConfig.DoubleTap.EDIT:
                return getString(R.string.Edit);
            case SolegramConfig.DoubleTap.SAVE:
                return getString(R.string.SG_SaveToSaved);
            case SolegramConfig.DoubleTap.DELETE:
                return getString(R.string.Delete);
            case SolegramConfig.DoubleTap.NONE:
                return getString(R.string.SG_Nothing);
            default:
                return getString(R.string.SG_Reaction);
        }
    }

    private void showDoubleTapSelector() {
        String[] names = new String[SolegramConfig.DoubleTap.NONE + 1];
        for (int i = 0; i < names.length; i++) {
            names[i] = doubleTapName(i);
        }
        showDialog(org.telegram.ui.Components.AlertsCreator.createSingleChoiceDialog(getParentActivity(), names, getString(R.string.SG_DoubleTap), SolegramConfig.doubleTapAction, (d, which) -> {
            SolegramConfig.putInt("doubleTapAction", which);
            listView.adapter.update(true);
        }));
    }

    private void askUpdateChannel() {
        Context ctx = getParentActivity();
        if (ctx == null) {
            return;
        }
        org.telegram.ui.Components.EditTextBoldCursor field = new org.telegram.ui.Components.EditTextBoldCursor(ctx);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintText("@username");
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        field.setText("@" + SolegramConfig.getUpdateChannel());
        field.setSingleLine(true);
        field.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        field.setBackground(null);
        FrameLayout container = new FrameLayout(ctx);
        container.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36, Gravity.TOP, 24, 8, 24, 8));
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx, getResourceProvider());
        builder.setTitle(getString(R.string.SG_UpdateChannel));
        builder.setMessage(getString(R.string.SG_UpdateChannelInfo));
        builder.setView(container);
        builder.setPositiveButton(getString(R.string.Save), (d, w) -> {
            String text = field.getText() == null ? "" : field.getText().toString().trim();
            SolegramConfig.putString("updateChannel", text);
            listView.adapter.update(true);
        });
        builder.setNeutralButton(getString(R.string.SG_Default), (d, w) -> {
            SolegramConfig.putString("updateChannel", "");
            listView.adapter.update(true);
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private boolean isIgnoringBatteryOptimizations() {
        try {
            PowerManager pm = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(ApplicationLoader.applicationContext.getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }

    private void openBatterySettings() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        try {
            Intent intent;
            if (!isIgnoringBatteryOptimizations()) {
                intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + activity.getPackageName()));
            } else {
                intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            }
            activity.startActivity(intent);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void exportSettings() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        try {
            File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "solegram");
            dir.mkdirs();
            File file = new File(dir, "solegram-settings.json");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(SolegramConfig.exportToJson().getBytes(StandardCharsets.UTF_8));
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, getString(R.string.SG_ExportSettings)));
        } catch (Throwable e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(e.getMessage()).show();
        }
    }

    private static final int REQUEST_IMPORT = 0x5051;

    private void importSettings() {
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_IMPORT);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_IMPORT || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try (java.io.InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(data.getData())) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while (in != null && (r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
            int count = SolegramConfig.importFromJson(out.toString("UTF-8"));
            if (count < 0) {
                BulletinFactory.of(this).createErrorBulletin(getString(R.string.SG_ImportFailed)).show();
            } else {
                com.solegram.messenger.SolegramNowPlaying.onSettingsChanged();
                listView.adapter.update(true);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.formatString(R.string.SG_Imported, count), getString(R.string.SG_Restart), () -> Solegram.restartApp(getParentActivity())).show();
            }
        } catch (Throwable e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(getString(R.string.SG_ImportFailed)).show();
        }
    }

    private View headerView;

    /** One banner per page: the Updates page refreshes several times a second while a download runs. */
    private View headerView() {
        if (headerView == null) {
            headerView = createHeaderView(getContext());
        }
        return headerView;
    }

    /** Logo, name and version over the flowing theme-colour haze; only on the Updates and About pages. */
    public static View createHeaderView(Context context) {
        // the hero shows the app icon the user picked for the launcher
        int bg = R.drawable.sg_icon_bg_sunset, fg = R.drawable.sg_icon_fg_sunset;
        for (org.telegram.ui.LauncherIconController.LauncherIcon icon : org.telegram.ui.LauncherIconController.LauncherIcon.values()) {
            if (org.telegram.ui.LauncherIconController.isEnabled(icon)) {
                bg = icon.background;
                fg = icon.foreground;
                break;
            }
        }
        return new SolegramFlowHeader(context, Solegram.NAME,
            LocaleController.formatString(R.string.SG_VersionLine, Solegram.getVersionName(), Solegram.getBuild(), org.telegram.messenger.BuildConfig.BUILD_VERSION_STRING),
            bg, fg);
    }

    // Apple HIG system colors (light, dark): tiles read as one family in both themes.
    private static final int[] TILE_INDIGO = {0xFF5856D6, 0xFF5E5CE6};
    private static final int[] TILE_GREEN = {0xFF34C759, 0xFF30D158};
    private static final int[] TILE_PURPLE = {0xFFAF52DE, 0xFFBF5AF2};
    private static final int[] TILE_GRAY = {0xFF8E8E93, 0xFF8E8E93};
    private static final int[] TILE_ORANGE = {0xFFFF9500, 0xFFFF9F0A};
    private static final int[] TILE_BLUE = {0xFF007AFF, 0xFF0A84FF};
    private static final int[] TILE_TEAL = {0xFF30B0C7, 0xFF40C8E0};
    private static final int[] TILE_CYAN = {0xFF32ADE6, 0xFF64D2FF};
    private static final int[] TILE_YELLOW = {0xFFFFB800, 0xFFFFC70A};
    private static final int[] TILE_RED = {0xFFFF3B30, 0xFFFF453A};
    private static final int[] TILE_PINK = {0xFFFF2D55, 0xFFFF375F};

    /** Colored rounded tile behind the row icon: a lighter top fading into the system color. */
    private UItem tile(UItem item, int[] color) {
        final int base = Theme.isCurrentThemeDark() ? color[1] : color[0];
        return item.setIconTile(androidx.core.graphics.ColorUtils.blendARGB(base, 0xFFFFFFFF, 0.18f), base);
    }

    // endregion
}
