/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.Iterator;
import java.util.Map;

/**
 * All Solegram preferences in one place. Values are cached in static fields because many of them
 * are read on hot paths (cell drawing, request pipeline), so reads must stay allocation free.
 */
public class SolegramConfig {

    public static final String PREFS = "solegram_config";

    // region Appearance
    /** Avatar roundness in percent: 100 = circle, 0 = square. */
    public static int avatarCorners = 100;
    public static boolean useSystemFont;
    public static boolean centerTitle;
    public static boolean hideStories;
    public static boolean hideEmojiStatus;
    public static boolean hidePhoneNumber;
    public static boolean monetAccent;
    public static boolean showSeconds;
    public static boolean disableVibration;
    /** Avatar corners also inside chats (messages, header). Off: chats keep round avatars. */
    public static boolean avatarShapeInChats;
    /** Chats screen title, see {@link SolegramUi#TITLE_APP}. */
    public static int mainTitle;
    public static String mainTitleText = "";
    /** Icon pack, see {@link SolegramIcons#PACK_STANDARD}: Telegram's icons, thin or filled ones. */
    public static int iconPack;
    // endregion

    // region Glass (100 = Telegram's value unless noted)
    public static int glassThickness = 100;
    public static int glassRefraction = 100;
    /** Refraction index x100: 150 = Telegram's 1.5. */
    public static int glassIndex = 150;
    public static int glassDispersion;
    public static int glassRim;
    /** Tint layer opacity in percent: 85 = Telegram's liquid glass. */
    public static int glassOpacity = 85;
    public static int glassBlur = 100;
    public static int glassHighlight = 100;
    public static int glassTint;
    public static int glassTintAmount = 25;
    public static boolean glassShadow = true;
    public static boolean glassVibrancy = true;
    /** Window blur (Android 12+) behind popup menus, dialogs and bottom sheets. */
    public static boolean glassMenus;
    public static boolean glassDialogs;
    public static boolean glassSheets;
    /** Switches and sliders drawn like the liquid glass demo (white pill that turns into a lens). */
    public static boolean liquidControls = true;
    /** The app's glass gets the light rim and soft shadow of the glass preview. */
    public static boolean glassPreviewLook = true;
    /** Letters fade in when typed and fade out when erased. */
    public static boolean typingAnimation = true;
    /** "Translate here" in the message menu: the bubble shows the translation. */
    public static boolean translateHere = true;
    /** Headers (chat list, Feed) blur what scrolls under them, also in the light theme, with a lighter veil. */
    public static boolean frostedHeaders = true;
    /** Chats screen: no panel behind the title - the list goes under it and fades out, the buttons sit in glass bubbles. */
    public static boolean floatingHeader = true;
    /** Every header: the back button and the header buttons sit in bubbles, as in a chat. */
    public static boolean glassHeaderButtons = true;
    /** AI summary of long messages without Premium (free AI service, or the user's own). */
    public static boolean aiSummary = true;
    /** The summary button beside long messages in every chat (not only on long channel posts). */
    public static boolean aiSummaryButton = true;
    /** SolegramSummarizer.PROVIDER_*: 2 = auto (Gemini Nano on the phone, otherwise the free service), 3 = Gemini Nano only,
     * 0 = free service (no key), 1 = the user's own OpenAI-compatible service. */
    public static int aiProvider = SolegramSummarizer.PROVIDER_AUTO;
    public static String aiBaseUrl = "";
    public static String aiApiKey = "";
    public static String aiModel = "";
    /** 0 = in the language of the message, 1 = in the translation language. */
    public static int aiSummaryLanguage = 0;
    /** The user agreed once that message text goes to the free AI service when a summary is asked for. */
    public static boolean aiConsent = false;
    /** Voice and round video transcription without Premium (on the device, or the user's own AI service). */
    public static boolean transcribe = true;
    /** SolegramTranscriber.ENGINE_*: 0 = auto, 1 = on the device, 2 = the user's AI service. */
    public static int transcribeEngine = 0;
    /** Speech-to-text model of the user's AI service; empty = whisper-1 (whisper-large-v3-turbo on Groq). */
    public static String transcribeModel = "";
    /** Custom emoji next to the chats screen title, kept on this device (0 = none). */
    public static long titleEmoji = 0;
    /** The call screen with the glass sheet (controls at the bottom in the app's glass). */
    public static boolean glassCallScreen = true;
    // endregion

    // region Liquid motion
    /** Menus and buttons flow out of the button that opens them, like drops of liquid, and flow back in. */
    public static boolean liquidMotion = true;
    /** The chat input as on iOS: emoji in a round button on the left, the text capsule, voice/send on the right. */
    public static boolean iosInputBar = true;
    // endregion

    // region Music in the profile
    /** What plays now in Spotify or SoundCloud shows as a card in the profile. */
    public static boolean nowPlaying = true;
    public static boolean nowPlayingSpotify = true;
    public static boolean nowPlayingSoundCloud = true;
    /** Others see it too: the track goes into the profile's bio while it plays (the bio comes back after). */
    public static boolean nowPlayingShareBio = false;
    // endregion

    // region Profile links
    /** Links to profiles on other platforms in a bio show as buttons with the platform's mark (SolegramProfileLinks). */
    public static boolean profileLinks = true;
    // endregion

    // region In-app browser
    /** Ads and trackers of web pages are not loaded (a domain list, page cleanup, YouTube ads, pop-ups). */
    public static boolean adBlock = true;
    public static boolean adBlockCosmetic = true;
    public static boolean adBlockYouTube = true;
    public static boolean adBlockPopups = true;
    /** Sites where the blocker is off, one host per line. */
    public static String adBlockAllowed = "";
    // endregion

    // region Anti-scam
    /** Suspicious links in messages are marked, and opening one asks first (SolegramScamGuard). */
    public static boolean scamLinks = true;
    /** A stranger who looks like a scammer gets a warning over the chat and in the profile. */
    public static boolean scamAccounts = true;
    /** Borderline links and strangers are checked again by Gemini Nano right on the phone, where it exists. */
    public static boolean scamNano = true;
    /** How many signs it takes to warn: SolegramScamRules.SENSITIVITY_SOFT / NORMAL / STRICT. */
    public static int scamSensitivity = SolegramScamRules.SENSITIVITY_NORMAL;
    /** Sites the user trusts, one per line: never warned about. */
    public static String scamTrustedSites = "";
    // endregion

    // region Camera
    /** Frame rate of recorded videos and round videos: 0 = the most the camera gives (up to 60), or 30 / 60. */
    public static int cameraFps = 0;
    /** Photos from the in-app camera get Solegram's processing (tone, noise, detail, colour). */
    public static boolean cameraEnhance = true;
    /** Photos from the in-app camera are sent in HD (2560 px) unless switched off in the editor. */
    public static boolean cameraHd = true;
    /** The attach camera with Solegram's controls on the viewfinder (flash, HDR, timer, frame, grid, zoom, exposure). */
    public static boolean cameraControls = true;
    /** 3x3 grid over the viewfinder (attach camera and stories). */
    public static boolean cameraGrid;
    /** Self-timer of the attach camera in seconds: 0, 3 or 10. */
    public static int cameraTimer;
    /** Frame of attach camera photos: 0 = 4:3, 1 = 16:9, 2 = 1:1 (the middle of 4:3), 3 = the whole screen (default). */
    public static int cameraAspect;
    public static final int CAMERA_ASPECT_4_3 = 0, CAMERA_ASPECT_16_9 = 1, CAMERA_ASPECT_1_1 = 2, CAMERA_ASPECT_FULL = 3;
    // endregion

    // region Animations
    public static boolean smoothTransitions = true;
    public static boolean pressScale = true;
    public static boolean animateLists = true;
    public static boolean neutralOverscroll = true;
    // endregion

    // region Chats
    /** Sticker size in percent of the default size (50..150). */
    public static int stickerSize = 100;
    public static boolean hideKeyboardOnScroll;
    public static int doubleTapAction = DoubleTap.REACTION;
    public static boolean disableJumpToNextChannel;
    public static boolean confirmStickers;
    public static boolean confirmVoice;
    public static boolean hideShareButton;
    public static boolean showMessageId;
    public static boolean showUserId = true;
    public static boolean sendSilently;
    public static boolean disableLinkPreviews;
    public static boolean voiceOnlyRecord;
    public static boolean disableSwipeToReply;
    public static boolean confirmGifs;
    public static boolean messageDetails = true;
    public static boolean jumpToFirstInMenu = true;
    public static boolean showDcInProfile = true;
    public static boolean forwardWithoutAuthor;
    // endregion

    // region Message filters
    /** Messages matching the user's filters or the presets are folded or hidden (see SolegramMessageFilters). */
    public static boolean messageFilters;
    /** SolegramMessageFilters.MODE_*: 0 = folded to a slim line that opens on tap, 1 = not shown at all. */
    public static int messageFiltersMode;
    /** Presets: advertising law marks in channels, zalgo and junk text, invite link spam in groups. */
    public static boolean filterAds;
    public static boolean filterJunk;
    public static boolean filterInvites;
    /** The ads preset also hides posts with several weak signs together (a tracked link, a call to act, an offer). */
    public static boolean filterAdsStrict;
    /**
     * Telegram's own sponsored messages (the "Ad" block under channel posts, in bots, in the video player and in
     * search) are not requested at all. Off by default: the Telegram API terms ask apps to show them.
     */
    public static boolean hideSponsored;
    // endregion

    // region Chats screen
    public static boolean rememberFolder;
    public static boolean markAllReadInMenu = true;
    public static boolean hideContactsTab;
    public static boolean hideProfileTab;
    public static boolean savedInMenu = true;
    /** Bottom tab "Feed" (posts of subscribed channels) takes the place of Contacts. */
    public static boolean feedInsteadOfContacts = true;
    /** Swipe right on the first chat folder opens Contacts. */
    public static boolean contactsSwipe = true;
    public static boolean feedSkipMuted;
    /** Chat translation (the banner in foreign chats) without Premium, through Google Translate. */
    public static boolean freeTranslate = true;
    /** Chats in other languages open already translated. */
    public static boolean autoTranslate;
    // endregion

    // region Reminders
    /** The text of a reminder made from messages is composed by AI (the AI choice of "Translation and AI"). */
    public static boolean reminderAi = true;
    /** Times of the "this evening" / "tomorrow morning" choices, in minutes after midnight. */
    public static int reminderMorning = 9 * 60;
    public static int reminderEvening = 19 * 60;
    /** Why exact alarms help was explained once (Android 12+ without the permission). */
    public static boolean reminderExactAsked;
    // endregion

    // region Ghost mode & history
    public static boolean ghostNoTyping;
    public static boolean ghostNoRead;
    public static boolean ghostReadOnInteract = true;
    public static boolean ghostOffline;
    public static boolean ghostNoStoryRead;
    public static boolean saveDeletedMessages;
    public static boolean saveEditsHistory;
    public static boolean ghostInDrawer = true;
    // endregion

    // region General
    public static boolean backgroundConnection = true;
    public static boolean confirmCalls;
    public static boolean disableProximitySensor;
    public static boolean hidePremiumSettings;
    public static boolean hideGiftButtons;
    // endregion

    // region Proxy
    /** When Telegram cannot connect, a working public proxy is found and turned on (see SolegramProxy). */
    public static boolean proxyAuto;
    // endregion

    // region Plugins
    public static boolean pluginsEngine = true;
    public static boolean pluginsSafeMode;
    public static boolean pluginsDevMode;
    /** Show the security report (permissions, verdict) before installing, updating or enabling a plugin. */
    public static boolean pluginsScanBeforeInstall = true;
    /** Refuse plugins rated dangerous instead of only warning. */
    public static boolean pluginsBlockDangerous;
    // endregion

    // region Updates
    /** Derived from {@link #autoUpdateMode}: anything but off checks by itself. */
    public static boolean autoUpdateCheck = true;
    public static final int AUTO_UPDATE_OFF = 0;
    public static final int AUTO_UPDATE_NOTIFY = 1;
    public static final int AUTO_UPDATE_INSTALL = 2;
    /** Off / only tell about a new version / download and install it by itself (updater.AutoUpdater). */
    public static int autoUpdateMode = AUTO_UPDATE_INSTALL;
    /** Updates download by themselves only over Wi-Fi (an unmetered network). */
    public static boolean updateWifiOnly = true;
    /** 0 = GitHub repository (update.json), 1 = Telegram channel. */
    public static int updateSource;
    public static String updateRepo = "";
    public static String updateChannel = "";
    public static long lastUpdateCheck;
    public static int skippedUpdateBuild;
    // endregion

    public static final class DoubleTap {
        public static final int REACTION = 0;
        public static final int REPLY = 1;
        public static final int COPY = 2;
        public static final int FORWARD = 3;
        public static final int EDIT = 4;
        public static final int SAVE = 5;
        public static final int DELETE = 6;
        public static final int NONE = 7;
    }

    private static volatile boolean loaded;

    public static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    public static void load() {
        if (loaded) {
            return;
        }
        synchronized (SolegramConfig.class) {
            if (loaded) {
                return;
            }
            reload();
            loaded = true;
        }
    }

    /** Incremented on every change so screens can re-layout content built with old values. */
    public static volatile int version;

    public static void reload() {
        version++;
        SharedPreferences p = prefs();
        avatarCorners = clamp(p.getInt("avatarCorners", 100), 0, 100);
        useSystemFont = p.getBoolean("useSystemFont", false);
        centerTitle = p.getBoolean("centerTitle", false);
        hideStories = p.getBoolean("hideStories", false);
        hideEmojiStatus = p.getBoolean("hideEmojiStatus", false);
        hidePhoneNumber = p.getBoolean("hidePhoneNumber", false);
        monetAccent = p.getBoolean("monetAccent", false);
        showSeconds = p.getBoolean("showSeconds", false);
        disableVibration = p.getBoolean("disableVibration", false);
        avatarShapeInChats = p.getBoolean("avatarShapeInChats", false);
        mainTitle = clamp(p.getInt("mainTitle", 0), 0, 3);
        mainTitleText = p.getString("mainTitleText", "");
        iconPack = clamp(p.getInt("iconPack", 0), 0, 2);
        SolegramIcons.setPack(iconPack);

        glassThickness = clamp(p.getInt("glassThickness", 100), 25, 300);
        glassRefraction = clamp(p.getInt("glassRefraction", 100), 0, 300);
        glassIndex = clamp(p.getInt("glassIndex", 150), 100, 250);
        glassDispersion = clamp(p.getInt("glassDispersion", 0), 0, 100);
        glassRim = clamp(p.getInt("glassRim", 0), 0, 100);
        glassOpacity = clamp(p.getInt("glassOpacity", 85), 10, 100);
        glassBlur = clamp(p.getInt("glassBlur", 100), 0, 300);
        glassHighlight = clamp(p.getInt("glassHighlight", 100), 0, 300);
        glassTint = clamp(p.getInt("glassTint", 0), 0, SolegramGlass.TINT_COLORS.length - 1);
        glassTintAmount = clamp(p.getInt("glassTintAmount", 25), 0, 60);
        glassShadow = p.getBoolean("glassShadow", true);
        glassVibrancy = p.getBoolean("glassVibrancy", true);
        glassMenus = p.getBoolean("glassMenus", false);
        glassDialogs = p.getBoolean("glassDialogs", false);
        glassSheets = p.getBoolean("glassSheets", false);
        liquidControls = p.getBoolean("liquidControls", true);
        glassPreviewLook = p.getBoolean("glassPreviewLook", true);
        typingAnimation = p.getBoolean("typingAnimation", true);
        translateHere = p.getBoolean("translateHere", true);
        frostedHeaders = p.getBoolean("frostedHeaders", true);
        floatingHeader = p.getBoolean("floatingHeader", true);
        glassHeaderButtons = p.getBoolean("glassHeaderButtons", true);
        aiSummary = p.getBoolean("aiSummary", true);
        aiSummaryButton = p.getBoolean("aiSummaryButton", true);
        // "aiMode" replaced "aiProvider" (free/own) when Gemini Nano came: one's own service stays, the free one becomes auto
        aiProvider = p.contains("aiMode") ? clamp(p.getInt("aiMode", SolegramSummarizer.PROVIDER_AUTO), 0, 3)
            : p.getInt("aiProvider", 0) == SolegramSummarizer.PROVIDER_OWN ? SolegramSummarizer.PROVIDER_OWN : SolegramSummarizer.PROVIDER_AUTO;
        aiBaseUrl = p.getString("aiBaseUrl", "");
        aiApiKey = p.getString("aiApiKey", "");
        aiModel = p.getString("aiModel", "");
        aiSummaryLanguage = p.getInt("aiSummaryLanguage", 0);
        aiConsent = p.getBoolean("aiConsent", false);
        transcribe = p.getBoolean("transcribe", true);
        transcribeEngine = clamp(p.getInt("transcribeEngine", 0), 0, 2);
        transcribeModel = p.getString("transcribeModel", "");
        titleEmoji = readLong(p, "titleEmoji");
        glassCallScreen = p.getBoolean("glassCallScreen", true);
        liquidMotion = p.getBoolean("liquidMotion", true);
        iosInputBar = p.getBoolean("iosInputBar", true);
        nowPlaying = p.getBoolean("nowPlaying", true);
        nowPlayingSpotify = p.getBoolean("nowPlayingSpotify", true);
        nowPlayingSoundCloud = p.getBoolean("nowPlayingSoundCloud", true);
        nowPlayingShareBio = p.getBoolean("nowPlayingShareBio", false);
        profileLinks = p.getBoolean("profileLinks", true);
        adBlock = p.getBoolean("adBlock", true);
        adBlockCosmetic = p.getBoolean("adBlockCosmetic", true);
        adBlockYouTube = p.getBoolean("adBlockYouTube", true);
        adBlockPopups = p.getBoolean("adBlockPopups", true);
        adBlockAllowed = p.getString("adBlockAllowed", "");
        scamLinks = p.getBoolean("scamLinks", true);
        scamAccounts = p.getBoolean("scamAccounts", true);
        scamNano = p.getBoolean("scamNano", true);
        scamSensitivity = clamp(p.getInt("scamSensitivity", SolegramScamRules.SENSITIVITY_NORMAL), SolegramScamRules.SENSITIVITY_SOFT, SolegramScamRules.SENSITIVITY_STRICT);
        scamTrustedSites = p.getString("scamTrustedSites", "");
        cameraFps = p.getInt("cameraFps", 0);
        cameraEnhance = p.getBoolean("cameraEnhance", true);
        cameraHd = p.getBoolean("cameraHd", true);
        cameraControls = p.getBoolean("cameraControls", true);
        cameraGrid = p.getBoolean("cameraGrid", false);
        cameraTimer = p.getInt("cameraTimer", 0);
        if (cameraTimer != 3 && cameraTimer != 10) {
            cameraTimer = 0;
        }
        cameraAspect = clamp(p.getInt("cameraAspect", CAMERA_ASPECT_FULL), CAMERA_ASPECT_4_3, CAMERA_ASPECT_FULL);

        smoothTransitions = p.getBoolean("smoothTransitions", true);
        pressScale = p.getBoolean("pressScale", true);
        animateLists = p.getBoolean("animateLists", true);
        neutralOverscroll = p.getBoolean("neutralOverscroll", true);

        stickerSize = clamp(p.getInt("stickerSize", 100), 50, 150);
        hideKeyboardOnScroll = p.getBoolean("hideKeyboardOnScroll", false);
        doubleTapAction = clamp(p.getInt("doubleTapAction", DoubleTap.REACTION), 0, DoubleTap.NONE);
        disableJumpToNextChannel = p.getBoolean("disableJumpToNextChannel", false);
        confirmStickers = p.getBoolean("confirmStickers", false);
        confirmVoice = p.getBoolean("confirmVoice", false);
        hideShareButton = p.getBoolean("hideShareButton", false);
        showMessageId = p.getBoolean("showMessageId", false);
        showUserId = p.getBoolean("showUserId", true);
        sendSilently = p.getBoolean("sendSilently", false);
        disableLinkPreviews = p.getBoolean("disableLinkPreviews", false);
        voiceOnlyRecord = p.getBoolean("voiceOnlyRecord", false);
        disableSwipeToReply = p.getBoolean("disableSwipeToReply", false);
        confirmGifs = p.getBoolean("confirmGifs", false);
        messageDetails = p.getBoolean("messageDetails", true);
        jumpToFirstInMenu = p.getBoolean("jumpToFirstInMenu", true);
        showDcInProfile = p.getBoolean("showDcInProfile", true);
        forwardWithoutAuthor = p.getBoolean("forwardWithoutAuthor", false);

        messageFilters = p.getBoolean("messageFilters", false);
        messageFiltersMode = clamp(p.getInt("messageFiltersMode", 0), 0, 1);
        filterAds = p.getBoolean("filterAds", false);
        filterJunk = p.getBoolean("filterJunk", false);
        filterInvites = p.getBoolean("filterInvites", false);
        filterAdsStrict = p.getBoolean("filterAdsStrict", false);
        hideSponsored = p.getBoolean("hideSponsored", false);

        rememberFolder = p.getBoolean("rememberFolder", false);
        markAllReadInMenu = p.getBoolean("markAllReadInMenu", true);
        hideContactsTab = p.getBoolean("hideContactsTab", false);
        hideProfileTab = p.getBoolean("hideProfileTab", false);
        savedInMenu = p.getBoolean("savedInMenu", true);
        feedInsteadOfContacts = p.getBoolean("feedInsteadOfContacts", true);
        contactsSwipe = p.getBoolean("contactsSwipe", true);
        feedSkipMuted = p.getBoolean("feedSkipMuted", false);
        freeTranslate = p.getBoolean("freeTranslate", true);
        autoTranslate = p.getBoolean("autoTranslate", false);

        reminderAi = p.getBoolean("reminderAi", true);
        reminderMorning = clamp(p.getInt("reminderMorning", 9 * 60), 0, 24 * 60 - 1);
        reminderEvening = clamp(p.getInt("reminderEvening", 19 * 60), 0, 24 * 60 - 1);
        reminderExactAsked = p.getBoolean("reminderExactAsked", false);

        ghostNoTyping = p.getBoolean("ghostNoTyping", false);
        ghostNoRead = p.getBoolean("ghostNoRead", false);
        ghostReadOnInteract = p.getBoolean("ghostReadOnInteract", true);
        ghostOffline = p.getBoolean("ghostOffline", false);
        ghostNoStoryRead = p.getBoolean("ghostNoStoryRead", false);
        saveDeletedMessages = p.getBoolean("saveDeletedMessages", false);
        saveEditsHistory = p.getBoolean("saveEditsHistory", false);
        ghostInDrawer = p.getBoolean("ghostInDrawer", true);

        backgroundConnection = p.getBoolean("backgroundConnection", true);
        confirmCalls = p.getBoolean("confirmCalls", false);
        disableProximitySensor = p.getBoolean("disableProximitySensor", false);
        hidePremiumSettings = p.getBoolean("hidePremiumSettings", false);
        hideGiftButtons = p.getBoolean("hideGiftButtons", false);

        proxyAuto = p.getBoolean("proxyAuto", false);

        pluginsEngine = p.getBoolean("pluginsEngine", true);
        pluginsSafeMode = p.getBoolean("pluginsSafeMode", false);
        pluginsDevMode = p.getBoolean("pluginsDevMode", false);
        pluginsScanBeforeInstall = p.getBoolean("pluginsScanBeforeInstall", true);
        pluginsBlockDangerous = p.getBoolean("pluginsBlockDangerous", false);

        // before the modes there was only the check switch: whoever turned it off keeps updates off
        autoUpdateMode = p.contains("autoUpdateMode") ? clamp(p.getInt("autoUpdateMode", AUTO_UPDATE_INSTALL), AUTO_UPDATE_OFF, AUTO_UPDATE_INSTALL)
            : p.getBoolean("autoUpdateCheck", true) ? AUTO_UPDATE_INSTALL : AUTO_UPDATE_OFF;
        autoUpdateCheck = autoUpdateMode != AUTO_UPDATE_OFF;
        updateWifiOnly = p.getBoolean("updateWifiOnly", true);
        updateSource = clamp(p.getInt("updateSource", 0), 0, 1);
        updateRepo = p.getString("updateRepo", "");
        updateChannel = p.getString("updateChannel", "");
        lastUpdateCheck = readLong(p, "lastUpdateCheck");
        skippedUpdateBuild = p.getInt("skippedUpdateBuild", 0);
    }

    /** True when any ghost-mode option is active. */
    public static boolean isGhostModeActive() {
        return ghostNoTyping || ghostNoRead || ghostOffline || ghostNoStoryRead;
    }

    /** Toggles the full ghost mode set (typing, read receipts, online, stories) at once. */
    public static void setGhostMode(boolean enabled) {
        prefs().edit()
            .putBoolean("ghostNoTyping", enabled)
            .putBoolean("ghostNoRead", enabled)
            .putBoolean("ghostOffline", enabled)
            .putBoolean("ghostNoStoryRead", enabled)
            .apply();
        ghostNoTyping = ghostNoRead = ghostOffline = ghostNoStoryRead = enabled;
        GhostMode.onGhostModeChanged();
    }

    public static final int UPDATE_SOURCE_GITHUB = 0;
    public static final int UPDATE_SOURCE_TELEGRAM = 1;

    /** owner/name of the update repository; accepts a pasted github.com link. */
    public static String getUpdateRepo() {
        String repo = updateRepo == null ? "" : updateRepo.trim();
        if (repo.isEmpty()) {
            return Solegram.GITHUB_REPO;
        }
        repo = repo.replaceFirst("^(?i)https?://", "").replaceFirst("^(?i)(www\\.)?github\\.com/", "");
        if (repo.endsWith(".git")) {
            repo = repo.substring(0, repo.length() - 4);
        }
        String[] parts = repo.split("/");
        if (parts.length < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return Solegram.GITHUB_REPO;
        }
        return parts[0] + "/" + parts[1];
    }

    public static String getUpdateBranch() {
        return "main";
    }

    public static String getUpdateChannel() {
        String channel = updateChannel;
        if (channel == null || channel.trim().isEmpty()) {
            channel = org.telegram.messenger.BuildConfig.SOLEGRAM_UPDATE_CHANNEL;
        }
        channel = channel.trim();
        if (channel.startsWith("https://t.me/")) {
            channel = channel.substring("https://t.me/".length());
        } else if (channel.startsWith("t.me/")) {
            channel = channel.substring("t.me/".length());
        }
        if (channel.startsWith("@")) {
            channel = channel.substring(1);
        }
        int slash = channel.indexOf('/');
        if (slash >= 0) {
            channel = channel.substring(0, slash);
        }
        return channel;
    }

    public static void putBoolean(String key, boolean value) {
        prefs().edit().putBoolean(key, value).apply();
        reload();
    }

    public static boolean toggle(String key) {
        boolean value = !prefs().getBoolean(key, defaultBoolean(key));
        putBoolean(key, value);
        return value;
    }

    public static void putInt(String key, int value) {
        prefs().edit().putInt(key, value).apply();
        reload();
    }

    public static void putLong(String key, long value) {
        prefs().edit().putLong(key, value).apply();
        reload();
    }

    public static void putString(String key, String value) {
        prefs().edit().putString(key, value).apply();
        reload();
    }

    public static boolean getBoolean(String key) {
        return prefs().getBoolean(key, defaultBoolean(key));
    }

    /** Keys stored as long: a backup file turns small numbers into ints, so they are read as any number. */
    private static final java.util.List<String> LONG_KEYS = java.util.Arrays.asList("titleEmoji", "lastUpdateCheck");

    private static long readLong(SharedPreferences p, String key) {
        final Object v = p.getAll().get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0;
    }

    private static boolean defaultBoolean(String key) {
        switch (key) {
            case "ghostReadOnInteract":
            case "ghostInDrawer":
            case "backgroundConnection":
            case "pluginsEngine":
            case "pluginsScanBeforeInstall":
            case "autoUpdateCheck":
            case "updateWifiOnly":
            case "showUserId":
            case "glassShadow":
            case "glassVibrancy":
            case "messageDetails":
            case "markAllReadInMenu":
            case "jumpToFirstInMenu":
            case "showDcInProfile":
            case "savedInMenu":
            case "feedInsteadOfContacts":
            case "contactsSwipe":
            case "freeTranslate":
            case "liquidControls":
            case "glassPreviewLook":
            case "typingAnimation":
            case "translateHere":
            case "frostedHeaders":
            case "floatingHeader":
            case "glassHeaderButtons":
            case "aiSummary":
            case "aiSummaryButton":
            case "transcribe":
            case "glassCallScreen":
            case "liquidMotion":
            case "iosInputBar":
            case "nowPlaying":
            case "nowPlayingSpotify":
            case "nowPlayingSoundCloud":
            case "profileLinks":
            case "adBlock":
            case "adBlockCosmetic":
            case "adBlockYouTube":
            case "adBlockPopups":
            case "scamLinks":
            case "scamAccounts":
            case "scamNano":
            case "smoothTransitions":
            case "pressScale":
            case "animateLists":
            case "neutralOverscroll":
            case "cameraEnhance":
            case "cameraHd":
            case "cameraControls":
            case "reminderAi":
                return true;
            default:
                return false;
        }
    }

    // region Backup
    // the AI key is a secret: it stays on this device, a backup file must not carry it
    private static final String[] NOT_EXPORTED = {"lastUpdateCheck", "skippedUpdateBuild", "aiApiKey", "aiBaseUrl", "aiConsent",
        "nowPlayingBio", "nowPlayingBioWritten", "adBlockTotal", "reminderExactAsked"};
    // where message text is sent for AI summaries is chosen on the device, never taken from a settings file
    // changing the public bio is decided on the device too
    private static final java.util.List<String> NOT_IMPORTED = java.util.Arrays.asList("aiApiKey", "aiBaseUrl", "aiProvider", "aiMode", "aiConsent", "transcribeEngine",
        "nowPlayingShareBio", "nowPlayingBio", "nowPlayingBioWritten", "adBlockTotal");

    public static String exportToJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("format", "solegram-settings");
            root.put("version", 1);
            JSONObject values = new JSONObject();
            for (Map.Entry<String, ?> e : prefs().getAll().entrySet()) {
                // the bio kept while music is shared belongs to this device and account
                boolean skip = e.getKey().startsWith("nowPlayingBio");
                for (String k : NOT_EXPORTED) {
                    if (k.equals(e.getKey())) {
                        skip = true;
                        break;
                    }
                }
                if (!skip) {
                    values.put(e.getKey(), e.getValue());
                }
            }
            root.put("values", values);
            return root.toString(2);
        } catch (Exception e) {
            FileLog.e(e);
            return "{}";
        }
    }

    /** @return number of imported values, or -1 if the json is not a Solegram backup */
    public static int importFromJson(String json) {
        try {
            JSONObject root = new JSONObject(json);
            if (!"solegram-settings".equals(root.optString("format"))) {
                return -1;
            }
            JSONObject values = root.getJSONObject("values");
            SharedPreferences.Editor editor = prefs().edit();
            int count = 0;
            for (Iterator<String> it = values.keys(); it.hasNext(); ) {
                String key = it.next();
                if (NOT_IMPORTED.contains(key) || key.startsWith("nowPlayingBio")) {
                    continue;
                }
                Object v = values.get(key);
                if (LONG_KEYS.contains(key)) {
                    if (v instanceof Number) {
                        editor.putLong(key, ((Number) v).longValue());
                        count++;
                    }
                    continue;
                }
                if (v instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) v);
                } else if (v instanceof Integer) {
                    editor.putInt(key, (Integer) v);
                } else if (v instanceof Long) {
                    editor.putLong(key, (Long) v);
                } else if (v instanceof Double) {
                    editor.putFloat(key, ((Double) v).floatValue());
                } else if (v instanceof String) {
                    editor.putString(key, (String) v);
                } else {
                    continue;
                }
                count++;
            }
            editor.commit();
            reload();
            return count;
        } catch (Exception e) {
            FileLog.e(e);
            return -1;
        }
    }

    public static void resetAll() {
        final SharedPreferences.Editor editor = prefs().edit();
        for (String key : prefs().getAll().keySet()) {
            // what the music in the bio needs to give the user's own bio back stays
            if (!key.startsWith("nowPlayingBio")) {
                editor.remove(key);
            }
        }
        editor.commit();
        reload();
    }
    // endregion

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private SolegramConfig() {
    }
}
