/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Telegram where it is blocked, in one tap: public MTProto proxy lists are downloaded, the proxies are
 * pinged with Telegram's own checker and the fastest go into Telegram's proxy list, with rotation on.
 * Solegram remembers which proxies it added, so it only ever replaces or removes its own, never the
 * user's. Downloads run on a background thread; everything else on the UI thread, as in ProxyListActivity.
 */
public final class SolegramProxy implements NotificationCenter.NotificationCenterDelegate {

    /**
     * Public lists of t.me/proxy, tg://proxy, t.me/socks and tg://socks links (text around them is ignored).
     * Each list with its mirrors: jsDelivr serves the same GitHub files where raw.githubusercontent.com is blocked.
     */
    public static final String[][] SOURCES = {
        // Solegram's own list, kept by the project: tried first
        {"https://raw.githubusercontent.com/SBLauncherRBLX/Solegram/main/proxies.txt", "https://cdn.jsdelivr.net/gh/SBLauncherRBLX/Solegram@main/proxies.txt"},
        {"https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt", "https://cdn.jsdelivr.net/gh/SoliSpirit/mtproto@master/all_proxies.txt"},
        {"https://raw.githubusercontent.com/Grim1313/mtproto-for-telegram/master/all_proxies.txt", "https://cdn.jsdelivr.net/gh/Grim1313/mtproto-for-telegram@master/all_proxies.txt"},
        {"https://raw.githubusercontent.com/Argh94/Proxy-List/main/MTProto.txt", "https://cdn.jsdelivr.net/gh/Argh94/Proxy-List@main/MTProto.txt"},
    };

    /** How many working proxies go into Telegram's list (rotation switches between them). */
    private static final int KEEP = 6;
    /** Enough working ones found: the search stops early. */
    private static final int ENOUGH = 10;
    private static final int MAX_CHECKS = 120;
    /** Checks in flight; Telegram runs 4 at a time and queues the rest. */
    private static final int PARALLEL = 8;
    private static final long CHECK_TIMEOUT = 15_000;
    /** When Telegram's checker cannot run at all, proxies are tried for real, one by one. */
    private static final int LIVE_TRIES = 5;
    private static final long LIVE_TIMEOUT = 20_000;
    /** Auto mode: no connection for this long and a proxy is searched for. */
    private static final long STUCK_DELAY = 20_000;
    /** Longer while one of Solegram's proxies is on: Telegram's rotation gets to try the others first. */
    private static final long STUCK_DELAY_OURS = 40_000;
    /** Auto mode never searches more often than this, so a hopeless network does not drain the battery. */
    private static final long AUTO_PAUSE = 10 * 60_000;
    private static final long REFRESH_INTERVAL = 4 * 60 * 60_000L;
    private static final int GOOD_MAX = 30;
    private static final int CACHE_MAX = 2000;
    private static final int PER_SOURCE_MAX = 3000;
    private static final int SOURCE_MAX_BYTES = 1024 * 1024;

    public static final int PHASE_IDLE = 0;
    public static final int PHASE_DOWNLOADING = 1;
    public static final int PHASE_CHECKING = 2;
    public static final int PHASE_TRYING = 3;

    public static final int RESULT_OK = 0;
    /** No list could be downloaded and none is saved. */
    public static final int RESULT_NO_LIST = 1;
    public static final int RESULT_NONE_WORKING = 2;
    public static final int RESULT_CANCELLED = 3;

    /** What a search ended with; sent with {@link NotificationCenter#solegramProxyChanged}. */
    public static final class Result {
        public final int code;
        public final boolean byUser;
        public final String host;
        /** 0 when the proxy was tried for real instead of pinged. */
        public final long ping;
        public final int working;
        public final int checked;
        /** Lists could not be downloaded, the saved one was used. */
        public final boolean fromCache;

        Result(int code, boolean byUser, String host, long ping, int working, int checked, boolean fromCache) {
            this.code = code;
            this.byUser = byUser;
            this.host = host;
            this.ping = ping;
            this.working = working;
            this.checked = checked;
            this.fromCache = fromCache;
        }
    }

    private static final class Candidate {
        final ProxySettings settings;
        final String key;
        int lists;
        boolean own;
        long ping;

        Candidate(ProxySettings settings, String key) {
            this.settings = settings;
            this.key = key;
        }
    }

    private static final class Loaded {
        final ArrayList<Candidate> candidates;
        final boolean fromCache;

        Loaded(ArrayList<Candidate> candidates, boolean fromCache) {
            this.candidates = candidates;
            this.fromCache = fromCache;
        }
    }

    private static final SolegramProxy INSTANCE = new SolegramProxy();

    // search state, UI thread only
    private static int phase = PHASE_IDLE;
    /** Bumped by every search and cancel: callbacks of an older one are ignored. */
    private static int session;
    private static boolean byUser;
    private static boolean fromCache;
    private static int progress;
    private static int progressTotal;
    private static ArrayList<Candidate> candidates;
    private static ArrayList<Candidate> working;
    private static final HashSet<String> failed = new HashSet<>();
    private static int next;
    private static int inFlight;
    private static int answered;
    private static int silent;

    // trying for real
    private static int liveIndex;
    private static Candidate liveCandidate;
    private static SharedConfig.ProxyInfo liveInfo;
    private static boolean liveInfoAdded;
    private static SharedConfig.ProxyInfo prevProxy;
    private static boolean prevEnabled;
    private static final Runnable liveTimeout = SolegramProxy::tryNextLive;

    // auto mode
    private static boolean inited;
    private static long lastAutoSearch;
    private static boolean stuckScheduled;
    /** Auto searches in a row that did not bring a connection back. */
    private static int autoFailures;
    private static final Runnable stuckCheck = SolegramProxy::onStuck;
    private static final Runnable refreshRunnable = SolegramProxy::refresh;

    private static Set<String> added;

    private SolegramProxy() {
    }

    // region state for the settings page

    public static int getPhase() {
        return phase;
    }

    public static boolean isSearching() {
        return phase != PHASE_IDLE;
    }

    public static int getProgress() {
        return progress;
    }

    public static int getProgressTotal() {
        return progressTotal;
    }

    public static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("solegram_proxy", Activity.MODE_PRIVATE);
    }

    /** Host of the proxy the last successful search turned on, or null. */
    public static String getLastHost() {
        return prefs().getString("lastHost", null);
    }

    public static long getLastPing() {
        return prefs().getLong("lastPing", 0);
    }

    public static int getLastWorking() {
        return prefs().getInt("lastWorking", 0);
    }

    /** Proxies Solegram added that are still in Telegram's list. */
    public static int getAddedCount() {
        SharedConfig.loadProxyList();
        final Set<String> ours = addedKeys();
        int count = 0;
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (ours.contains(key(info.settings))) {
                count++;
            }
        }
        return count;
    }

    public static boolean isOurs(SharedConfig.ProxyInfo info) {
        return info != null && addedKeys().contains(key(info.settings));
    }

    /** Address of the proxy in use, or null for a direct connection. */
    public static String getCurrentHost() {
        SharedConfig.loadProxyList();
        return SharedConfig.isProxyEnabled() ? SharedConfig.currentProxy.settings.getAddress() : null;
    }
    // endregion

    // region actions

    /**
     * Downloads the lists, pings the proxies and turns on the fastest. Progress and the result come
     * with {@link NotificationCenter#solegramProxyChanged}.
     */
    public static void search(boolean user) {
        if (phase != PHASE_IDLE) {
            return;
        }
        if (!user) {
            lastAutoSearch = SystemClock.elapsedRealtime();
        }
        byUser = user;
        final int sess = ++session;
        phase = PHASE_DOWNLOADING;
        progress = progressTotal = 0;
        notifyChanged(null);
        final Thread thread = new Thread(() -> {
            Loaded loaded;
            try {
                loaded = load();
            } catch (Throwable e) {
                FileLog.e(e);
                loaded = new Loaded(new ArrayList<>(), false);
            }
            final Loaded result = loaded;
            AndroidUtilities.runOnUIThread(() -> startChecking(sess, result));
        }, "SolegramProxy");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    public static void cancel() {
        if (phase == PHASE_IDLE) {
            return;
        }
        if (phase == PHASE_TRYING) {
            AndroidUtilities.cancelRunOnUIThread(liveTimeout);
            dropLiveInfo();
            restorePrevious();
        }
        finish(new Result(RESULT_CANCELLED, byUser, null, 0, 0, progress, fromCache));
    }

    /** The "Use proxy" switch, as in Telegram's proxy list. False when there is no proxy to turn on. */
    public static boolean setProxyEnabled(boolean enabled) {
        SharedConfig.loadProxyList();
        if (enabled && SharedConfig.currentProxy == null) {
            SharedConfig.ProxyInfo pick = null;
            for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
                if (isOurs(info) && info.available && (pick == null || info.ping < pick.ping)) {
                    pick = info;
                }
            }
            if (pick == null && !SharedConfig.proxyList.isEmpty()) {
                pick = SharedConfig.proxyList.get(0);
            }
            if (pick == null) {
                return false;
            }
            select(pick, true);
            return true;
        }
        if (SharedConfig.currentProxy == null) {
            return !enabled;
        }
        select(SharedConfig.currentProxy, enabled);
        return true;
    }

    /** Removes the proxies Solegram added; the user's own stay. */
    public static int removeAdded() {
        SharedConfig.loadProxyList();
        final Set<String> ours = addedKeys();
        int count = 0;
        for (SharedConfig.ProxyInfo info : new ArrayList<>(SharedConfig.proxyList)) {
            if (ours.contains(key(info.settings))) {
                SharedConfig.deleteProxy(info);
                count++;
            }
        }
        ours.clear();
        saveAdded();
        prefs().edit().remove("lastHost").remove("lastPing").remove("lastWorking").apply();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        notifyChanged(null);
        return count;
    }

    /** Auto mode was switched in the settings. */
    public static void onAutoChanged() {
        init();
        AndroidUtilities.cancelRunOnUIThread(stuckCheck);
        stuckScheduled = false;
        AndroidUtilities.cancelRunOnUIThread(refreshRunnable);
        if (SolegramConfig.proxyAuto) {
            lastAutoSearch = 0;
            scheduleStuckCheck();
            AndroidUtilities.runOnUIThread(refreshRunnable, REFRESH_INTERVAL);
        }
    }

    /** Called once the accounts are loaded. */
    public static void init() {
        if (inited) {
            return;
        }
        inited = true;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter.getInstance(a).addObserver(INSTANCE, NotificationCenter.didUpdateConnectionState);
        }
        if (SolegramConfig.proxyAuto) {
            // on start: a found proxy that died meanwhile is replaced, and a blocked start is noticed
            AndroidUtilities.runOnUIThread(refreshRunnable, 60_000);
            scheduleStuckCheck();
        }
    }
    // endregion

    // region checking

    private static void startChecking(int sess, Loaded loaded) {
        if (sess != session || phase != PHASE_DOWNLOADING) {
            return;
        }
        fromCache = loaded.fromCache;
        if (loaded.candidates.isEmpty()) {
            finish(new Result(RESULT_NO_LIST, byUser, null, 0, 0, 0, false));
            return;
        }
        candidates = loaded.candidates;
        working = new ArrayList<>();
        failed.clear();
        next = inFlight = answered = silent = 0;
        phase = PHASE_CHECKING;
        progress = 0;
        progressTotal = candidates.size();
        notifyChanged(null);
        pump();
    }

    private static void pump() {
        if (phase != PHASE_CHECKING) {
            return;
        }
        if (working.size() >= ENOUGH || next >= candidates.size() && inFlight == 0) {
            finishChecking();
            return;
        }
        if (answered == 0 && silent >= PARALLEL) {
            // not even a failure came back: Telegram has no connection to check proxies through yet
            startLive();
            return;
        }
        while (inFlight < PARALLEL && next < candidates.size()) {
            startCheck(candidates.get(next++));
        }
    }

    private static void startCheck(Candidate c) {
        final int sess = session;
        final boolean[] done = new boolean[1];
        inFlight++;
        final Runnable timeout = () -> {
            if (done[0] || sess != session) {
                return;
            }
            done[0] = true;
            silent++;
            onChecked(c, -1);
        };
        AndroidUtilities.runOnUIThread(timeout, CHECK_TIMEOUT);
        ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(c.settings, time -> AndroidUtilities.runOnUIThread(() -> {
            if (done[0] || sess != session) {
                return;
            }
            done[0] = true;
            AndroidUtilities.cancelRunOnUIThread(timeout);
            answered++;
            onChecked(c, time);
        }));
    }

    private static void onChecked(Candidate c, long time) {
        inFlight--;
        progress++;
        if (time >= 0) {
            c.ping = Math.max(1, time);
            working.add(c);
        } else {
            failed.add(c.key);
        }
        notifyChanged(null);
        pump();
    }

    private static void finishChecking() {
        if (working.isEmpty()) {
            forgetFailed();
            finish(new Result(RESULT_NONE_WORKING, byUser, null, 0, 0, progress, fromCache));
            return;
        }
        Collections.sort(working, (a, b) -> Long.compare(a.ping, b.ping));
        final List<Candidate> best = working.subList(0, Math.min(KEEP, working.size()));
        apply(best);
        remember(best);
        final Candidate first = best.get(0);
        finish(new Result(RESULT_OK, byUser, first.settings.getAddress(), first.ping, working.size(), progress, fromCache));
    }

    private static void finish(Result result) {
        session++;
        phase = PHASE_IDLE;
        candidates = null;
        working = null;
        liveCandidate = null;
        liveInfo = null;
        prevProxy = null;
        if (result.code == RESULT_OK) {
            prefs().edit()
                .putString("lastHost", result.host)
                .putLong("lastPing", result.ping)
                .putInt("lastWorking", result.working)
                .apply();
        }
        notifyChanged(result);
        if (SolegramConfig.proxyAuto) {
            // still not connected: look again later, not right away
            scheduleStuckCheck();
        }
    }
    // endregion

    // region trying for real

    private static void startLive() {
        phase = PHASE_TRYING;
        SharedConfig.loadProxyList();
        prevProxy = SharedConfig.currentProxy;
        prevEnabled = SharedConfig.isProxyEnabled();
        liveIndex = 0;
        progress = 0;
        progressTotal = Math.min(LIVE_TRIES, candidates.size());
        tryNextLive();
    }

    private static void tryNextLive() {
        if (phase != PHASE_TRYING) {
            return;
        }
        dropLiveInfo();
        if (liveIndex >= progressTotal) {
            restorePrevious();
            finish(new Result(RESULT_NONE_WORKING, byUser, null, 0, 0, liveIndex, fromCache));
            return;
        }
        liveCandidate = candidates.get(liveIndex++);
        progress = liveIndex;
        SharedConfig.ProxyInfo info = find(liveCandidate.settings);
        liveInfoAdded = info == null;
        if (info == null) {
            info = SharedConfig.addProxy(new SharedConfig.ProxyInfo(liveCandidate.settings));
        }
        liveInfo = info;
        notifyChanged(null);
        select(info, true);
        AndroidUtilities.runOnUIThread(liveTimeout, LIVE_TIMEOUT);
    }

    private static void onLiveConnected() {
        AndroidUtilities.cancelRunOnUIThread(liveTimeout);
        final Candidate c = liveCandidate;
        if (liveInfoAdded) {
            addedKeys().add(c.key);
        }
        liveInfo = null;
        c.ping = 0;
        final ArrayList<Candidate> best = new ArrayList<>();
        best.add(c);
        apply(best);
        remember(best);
        finish(new Result(RESULT_OK, byUser, c.settings.getAddress(), 0, 1, liveIndex, fromCache));
    }

    /** A proxy tried for real that did not connect leaves the list again (if Solegram added it). */
    private static void dropLiveInfo() {
        if (liveInfo != null && liveInfoAdded) {
            if (liveInfo == SharedConfig.currentProxy) {
                // deleteProxy would cut the connection: point at the previous proxy first
                restorePrevious();
            }
            SharedConfig.deleteProxy(liveInfo);
        }
        liveInfo = null;
    }

    private static void restorePrevious() {
        if (prevProxy != null && SharedConfig.proxyList.contains(prevProxy)) {
            select(prevProxy, prevEnabled);
        } else if (SharedConfig.currentProxy != null) {
            select(SharedConfig.currentProxy, false);
        }
    }
    // endregion

    // region Telegram's proxy list

    /** Puts the proxies into Telegram's list (fastest on), with rotation, and drops older ones Solegram added. */
    private static void apply(List<Candidate> best) {
        SharedConfig.loadProxyList();
        final Set<String> ours = addedKeys();
        final Set<String> keep = new HashSet<>();
        SharedConfig.ProxyInfo fastest = null;
        final long now = SystemClock.elapsedRealtime();
        // backwards: addProxy puts each on top, so the fastest ends up first
        for (int i = best.size() - 1; i >= 0; i--) {
            final Candidate c = best.get(i);
            SharedConfig.ProxyInfo info = find(c.settings);
            if (info == null) {
                info = SharedConfig.addProxy(new SharedConfig.ProxyInfo(c.settings));
                ours.add(c.key);
            }
            // a proxy the user already had stays theirs
            keep.add(c.key);
            info.checking = false;
            if (c.ping > 0) {
                info.ping = c.ping;
                info.available = true;
                info.availableCheckTime = now;
            }
            if (i == 0) {
                fastest = info;
            }
        }
        final SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
        final boolean keepCurrent = !byUser && current != null && current != fastest && SharedConfig.isProxyEnabled()
            && keep.contains(key(current.settings)) && isConnected();
        if (fastest != null && !keepCurrent) {
            // a background refresh that found the proxy in use among the good ones leaves it on: switching would
            // reconnect every socket and hold up messages being sent right now for a few milliseconds of ping
            select(fastest, true);
        }
        for (SharedConfig.ProxyInfo info : new ArrayList<>(SharedConfig.proxyList)) {
            final String k = key(info.settings);
            if (ours.contains(k) && !keep.contains(k) && info != SharedConfig.currentProxy) {
                SharedConfig.deleteProxy(info);
            }
        }
        saveAdded();
        if (!SharedConfig.proxyRotationEnabled) {
            // when one dies, Telegram switches to the next working one by itself
            SharedConfig.proxyRotationEnabled = true;
            SharedConfig.saveConfig();
        }
        SharedConfig.saveProxyList();
    }

    /** Makes the proxy current, as tapping it in Telegram's proxy list does. */
    private static void select(SharedConfig.ProxyInfo info, boolean enabled) {
        final boolean same = SharedConfig.currentProxy == info && SharedConfig.isProxyEnabled() == enabled;
        final SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        info.settings.toSharedPreferences(editor);
        editor.putBoolean("proxy_enabled", enabled);
        editor.commit();
        SharedConfig.currentProxy = info;
        if (!same) {
            ConnectionsManager.setProxySettings(enabled, info.settings);
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    private static SharedConfig.ProxyInfo find(ProxySettings settings) {
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (settings.equals(info.settings)) {
                return info;
            }
        }
        return null;
    }

    private static String key(ProxySettings settings) {
        return settings.getLink();
    }

    private static Set<String> addedKeys() {
        if (added == null) {
            added = new LinkedHashSet<>(split(prefs().getString("added", "")));
        }
        return added;
    }

    /** Keeps only keys still in Telegram's list: the user may have deleted some there. */
    private static void saveAdded() {
        final Set<String> ours = addedKeys();
        final Set<String> present = new HashSet<>();
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            present.add(key(info.settings));
        }
        ours.retainAll(present);
        prefs().edit().putString("added", TextUtils.join("\n", ours)).apply();
    }

    /** Proxies that worked are tried first next time (also when no list can be downloaded). */
    private static void remember(List<Candidate> best) {
        final LinkedHashSet<String> good = new LinkedHashSet<>();
        for (Candidate c : best) {
            good.add(c.key);
        }
        for (String k : split(prefs().getString("good", ""))) {
            if (!failed.contains(k)) {
                good.add(k);
            }
        }
        final ArrayList<String> list = new ArrayList<>(good);
        prefs().edit().putString("good", TextUtils.join("\n", list.subList(0, Math.min(GOOD_MAX, list.size())))).apply();
    }

    private static void forgetFailed() {
        if (failed.isEmpty()) {
            return;
        }
        final ArrayList<String> good = new ArrayList<>();
        for (String k : split(prefs().getString("good", ""))) {
            if (!failed.contains(k)) {
                good.add(k);
            }
        }
        prefs().edit().putString("good", TextUtils.join("\n", good)).apply();
    }

    private static List<String> split(String s) {
        final ArrayList<String> list = new ArrayList<>();
        if (s != null) {
            for (String line : s.split("\n")) {
                if (!line.isEmpty()) {
                    list.add(line);
                }
            }
        }
        return list;
    }
    // endregion

    // region auto mode

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didUpdateConnectionState || account != UserConfig.selectedAccount) {
            return;
        }
        if (isConnected()) {
            if (phase == PHASE_TRYING) {
                onLiveConnected();
            } else if (!byUser && (phase == PHASE_DOWNLOADING || phase == PHASE_CHECKING)) {
                // the connection came back by itself while auto mode was still looking (a short drop, a tunnel):
                // finishing the search would move a working, often direct, connection to a public proxy for good
                cancel();
            }
            AndroidUtilities.cancelRunOnUIThread(stuckCheck);
            stuckScheduled = false;
            autoFailures = 0;
            return;
        }
        if (SolegramConfig.proxyAuto && !stuckScheduled) {
            scheduleStuckCheck();
        }
    }

    private static boolean isConnected() {
        final int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        return state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating;
    }

    private static void scheduleStuckCheck() {
        if (!SolegramConfig.proxyAuto || isConnected()) {
            return;
        }
        SharedConfig.loadProxyList();
        long delay = SharedConfig.isProxyEnabled() && isOurs(SharedConfig.currentProxy) ? STUCK_DELAY_OURS : STUCK_DELAY;
        if (lastAutoSearch != 0) {
            delay = Math.max(delay, lastAutoSearch + autoPause() - SystemClock.elapsedRealtime());
        }
        AndroidUtilities.cancelRunOnUIThread(stuckCheck);
        AndroidUtilities.runOnUIThread(stuckCheck, delay);
        stuckScheduled = true;
    }

    private static void onStuck() {
        stuckScheduled = false;
        if (!SolegramConfig.proxyAuto || phase != PHASE_IDLE || isConnected()) {
            return;
        }
        final int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        if (state == ConnectionsManager.ConnectionStateWaitingForNetwork && !ApplicationLoader.isNetworkOnlineRealtime()) {
            // no internet at all: a proxy will not help, the next state change brings us back
            return;
        }
        if (!mayAct()) {
            return;
        }
        if (lastAutoSearch != 0 && SystemClock.elapsedRealtime() - lastAutoSearch < autoPause() || ApplicationLoader.mainInterfacePaused) {
            // no search while the app is in the background (battery): looked at again a minute later
            AndroidUtilities.runOnUIThread(stuckCheck, ApplicationLoader.mainInterfacePaused ? 60_000 : Math.max(1000, lastAutoSearch + autoPause() - SystemClock.elapsedRealtime()));
            stuckScheduled = true;
            return;
        }
        autoFailures++;
        search(false);
    }

    /** 10, 20, 40, then 80 minutes between auto searches while Telegram stays unreachable. */
    private static long autoPause() {
        return AUTO_PAUSE << Math.min(3, Math.max(0, autoFailures - 1));
    }

    /** Auto mode only touches a direct connection or one of Solegram's proxies, never the user's own proxy. */
    private static boolean mayAct() {
        SharedConfig.loadProxyList();
        return SharedConfig.currentProxy == null || isOurs(SharedConfig.currentProxy);
    }

    /** Every few hours: pings Solegram's proxies, and searches again when the one in use died or few are left. */
    private static void refresh() {
        AndroidUtilities.cancelRunOnUIThread(refreshRunnable);
        if (!SolegramConfig.proxyAuto) {
            return;
        }
        AndroidUtilities.runOnUIThread(refreshRunnable, REFRESH_INTERVAL);
        SharedConfig.loadProxyList();
        if (phase != PHASE_IDLE || !SharedConfig.isProxyEnabled() || !isOurs(SharedConfig.currentProxy)) {
            return;
        }
        final ArrayList<SharedConfig.ProxyInfo> mine = new ArrayList<>();
        for (SharedConfig.ProxyInfo info : SharedConfig.proxyList) {
            if (isOurs(info) && !info.checking) {
                mine.add(info);
            }
        }
        if (mine.isEmpty()) {
            return;
        }
        final int[] pending = {mine.size()};
        final int[] alive = {0};
        final boolean[] done = {false};
        final Runnable evaluate = () -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            for (SharedConfig.ProxyInfo info : mine) {
                info.checking = false;
            }
            SharedConfig.saveProxyList();
            if (pending[0] == mine.size()) {
                // no answer at all: Telegram's checker cannot run now, the stuck check handles a lost connection
                return;
            }
            final SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
            final boolean currentAlive = current != null && current.available;
            if (phase == PHASE_IDLE && SolegramConfig.proxyAuto && mayAct() && (!currentAlive || alive[0] < 2)) {
                search(false);
            }
        };
        AndroidUtilities.runOnUIThread(evaluate, CHECK_TIMEOUT + 5_000);
        for (SharedConfig.ProxyInfo info : mine) {
            info.checking = true;
            ConnectionsManager.getInstance(UserConfig.selectedAccount).checkProxy(info.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                info.checking = false;
                info.availableCheckTime = SystemClock.elapsedRealtime();
                info.available = time >= 0;
                info.ping = time >= 0 ? time : 0;
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, info);
                if (time >= 0) {
                    alive[0]++;
                }
                if (--pending[0] == 0) {
                    AndroidUtilities.cancelRunOnUIThread(evaluate);
                    evaluate.run();
                }
            }));
        }
    }
    // endregion

    // region lists (background thread)

    private static Loaded load() {
        final LinkedHashMap<String, Candidate> all = new LinkedHashMap<>();
        final ExecutorService pool = Executors.newFixedThreadPool(SOURCES.length);
        try {
            final ArrayList<Future<String>> futures = new ArrayList<>();
            for (String[] mirrors : SOURCES) {
                futures.add(pool.submit(() -> downloadAny(mirrors)));
            }
            final long deadline = SystemClock.elapsedRealtime() + 40_000;
            for (int i = 0; i < futures.size(); i++) {
                String text = null;
                try {
                    text = futures.get(i).get(Math.max(1, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
                } catch (Throwable e) {
                    FileLog.d("SolegramProxy: list " + i + " failed: " + e);
                }
                if (text != null) {
                    addLinks(all, text, i == 0, PER_SOURCE_MAX);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        final File cacheFile = new File(ApplicationLoader.getFilesDirFixed(), "solegram_proxies.txt");
        boolean usedCache = false;
        if (!all.isEmpty()) {
            writeCache(cacheFile, all);
        } else {
            // GitHub blocked too, or offline: the list that was downloaded last time
            final String cached = readFile(cacheFile);
            if (cached != null) {
                addLinks(all, cached, false, CACHE_MAX);
                usedCache = !all.isEmpty();
            }
        }

        // proxies that worked before come first, even when they are in no list any more
        final LinkedHashMap<String, Candidate> good = new LinkedHashMap<>();
        addLinks(good, prefs().getString("good", ""), false, GOOD_MAX);
        final ArrayList<Candidate> order = new ArrayList<>();
        for (Candidate c : good.values()) {
            final Candidate listed = all.remove(c.key);
            order.add(listed != null ? listed : c);
        }
        final ArrayList<Candidate> rest = new ArrayList<>(all.values());
        // shuffled so that dead entries at the top of a list do not take every search; then
        // Solegram's own list first, then those in more lists
        Collections.shuffle(rest);
        Collections.sort(rest, (a, b) -> a.own != b.own ? (a.own ? -1 : 1) : Integer.compare(b.lists, a.lists));
        order.addAll(rest);
        while (order.size() > MAX_CHECKS) {
            order.remove(order.size() - 1);
        }
        return new Loaded(order, usedCache);
    }

    private static final class NotFound extends IOException {
    }

    private static String downloadAny(String[] mirrors) throws IOException {
        IOException last = null;
        for (String url : mirrors) {
            try {
                return download(url);
            } catch (NotFound e) {
                // the list is gone (or not made yet): the mirrors have nothing either
                throw e;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no url");
    }

    private static String download(String url) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Solegram");
            final int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new NotFound();
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = connection.getInputStream()) {
                return readCapped(in);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String readCapped(InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            // a list is a few dozen KB; past the cap the rest is dropped, what came is still used
            final int room = SOURCE_MAX_BYTES - out.size();
            out.write(buffer, 0, Math.min(read, room));
            if (read >= room) {
                break;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String readFile(File file) {
        if (!file.exists()) {
            return null;
        }
        try (InputStream in = new java.io.FileInputStream(file)) {
            return readCapped(in);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void writeCache(File file, LinkedHashMap<String, Candidate> all) {
        final StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String k : all.keySet()) {
            if (count++ >= CACHE_MAX) {
                break;
            }
            sb.append(k).append('\n');
        }
        final File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable e) {
            FileLog.e(e);
            return;
        }
        if (!tmp.renameTo(file)) {
            tmp.delete();
        }
    }
    // endregion

    // region parsing

    private static final Pattern LINK = Pattern.compile(
        "(?:(?:https?://)?(?:www\\.)?(?:t\\.me|telegram\\.me|telegram\\.dog)/|tg://)(proxy|socks)\\?([^\\s\"'<>`()]+)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");
    private static final Pattern IPV6 = Pattern.compile("[0-9a-f:.]+");
    private static final Pattern SECRET = Pattern.compile("[0-9A-Za-z_+/=-]{16,512}");
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]+");

    /** Adds every proxy link in the text; the key is the link Telegram makes, so lists de-duplicate. */
    private static void addLinks(LinkedHashMap<String, Candidate> into, String text, boolean own, int max) {
        if (text == null) {
            return;
        }
        final HashSet<String> seen = new HashSet<>();
        final Matcher m = LINK.matcher(text);
        int count = 0;
        while (m.find() && count < max) {
            final ProxySettings settings = parse(m.group(1), m.group(2));
            if (settings == null) {
                continue;
            }
            final String k = key(settings);
            if (!seen.add(k)) {
                continue;
            }
            count++;
            Candidate c = into.get(k);
            if (c == null) {
                c = new Candidate(settings, k);
                into.put(k, c);
            }
            c.lists++;
            c.own |= own;
        }
    }

    static ProxySettings parse(String kind, String query) {
        String server = null, secret = null, user = null, pass = null;
        int port = 0;
        for (String part : query.replace("&amp;", "&").split("&")) {
            final int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            final String name = part.substring(0, eq).toLowerCase(Locale.US);
            final String value = percentDecode(part.substring(eq + 1)).trim();
            switch (name) {
                case "server":
                case "host":
                    server = value;
                    break;
                case "port":
                    try {
                        port = Integer.parseInt(value);
                    } catch (NumberFormatException ignore) {
                    }
                    break;
                case "secret":
                    secret = value;
                    break;
                case "user":
                    user = value;
                    break;
                case "pass":
                    pass = value;
                    break;
            }
        }
        if (server == null || port <= 0 || port > 65535) {
            return null;
        }
        server = server.toLowerCase(Locale.US);
        if (server.startsWith("[") && server.endsWith("]")) {
            server = server.substring(1, server.length() - 1);
        }
        // "host.info." is the same host; some lists write it fully qualified
        while (server.endsWith(".")) {
            server = server.substring(0, server.length() - 1);
        }
        if (server.isEmpty() || server.length() > 253 || !HOST.matcher(server).matches() && !(server.indexOf(':') >= 0 && IPV6.matcher(server).matches())) {
            return null;
        }
        final ProxySettings.Builder builder = ProxySettings.builder().setAddress(server).setPort(port);
        if ("proxy".equalsIgnoreCase(kind)) {
            if (secret == null || !SECRET.matcher(secret).matches()) {
                return null;
            }
            if (HEX.matcher(secret).matches()) {
                if (secret.length() % 2 != 0 || secret.length() < 32) {
                    return null;
                }
                secret = secret.toLowerCase(Locale.US);
                if (secret.replace("0", "").isEmpty()) {
                    return null;
                }
            } else if (secret.replace("A", "").replace("=", "").isEmpty()) {
                return null; // an all-zero secret is a placeholder
            }
            builder.setType(ProxySettings.Type.MTPROTO).setSecret(secret);
        } else {
            builder.setType(ProxySettings.Type.SOCKS5).setUser(user).setPassword(pass);
        }
        return builder.build();
    }

    /** %XX only: a "+" in a base64 secret is a plus, not a space. */
    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            final char ch = s.charAt(i);
            if (ch == '%' && i + 2 < s.length()) {
                final int hi = Character.digit(s.charAt(i + 1), 16);
                final int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo);
                    i += 2;
                    continue;
                }
            }
            final byte[] bytes = String.valueOf(ch).getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    // endregion

    private static void notifyChanged(Result result) {
        if (result != null) {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.solegramProxyChanged, result);
        } else {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.solegramProxyChanged);
        }
    }
}
