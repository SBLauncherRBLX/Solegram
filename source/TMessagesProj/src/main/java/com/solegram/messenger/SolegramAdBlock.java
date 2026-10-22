/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.webkit.WebResourceResponse;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.IDN;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

/**
 * Ad blocker of the in-app browser (never of bot mini-apps): requests to ad and tracker domains
 * are answered with an empty response, ad blocks left on the page are hidden, YouTube ads are
 * skipped and new windows a site opens to ad and tracker domains are not opened.
 * The domain list is StevenBlack/hosts (MIT), kept as a gzip in the assets.
 */
public final class SolegramAdBlock {

    private SolegramAdBlock() {
    }

    // gzip inside, but not named .gz: the build unpacks .gz assets and drops the extension
    private static final String ASSET = "sg_adblock_domains.dat";
    private static final String KEY_TOTAL = "adBlockTotal";
    private static final byte[] EMPTY = new byte[0];

    // region Domain list
    // a failed load is tried again, but not on every request that finds the list missing
    private static final long RETRY_DELAY = 60_000;
    private static final AtomicBoolean loadStarted = new AtomicBoolean();
    private static volatile long loadFailedAt;
    private static volatile DomainList domains;

    /**
     * Starts loading the domain list in the background when blocking is on; until it is loaded
     * only the path rules work. Cheap and safe to call any number of times from any thread, e.g.
     * at app start.
     */
    public static void warmUp() {
        if (!SolegramConfig.adBlock || domains != null) {
            return;
        }
        final long failedAt = loadFailedAt;
        if (failedAt != 0 && SystemClock.elapsedRealtime() - failedAt < RETRY_DELAY) {
            return;
        }
        if (!loadStarted.compareAndSet(false, true)) {
            return;
        }
        final Thread thread = new Thread(SolegramAdBlock::load, "SolegramAdBlock");
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void load() {
        final long start = SystemClock.elapsedRealtime();
        try (InputStream in = new GZIPInputStream(ApplicationLoader.applicationContext.getAssets().open(ASSET), 65536)) {
            byte[] raw = new byte[1 << 20];
            int length = 0;
            int read;
            while ((read = in.read(raw, length, raw.length - length)) >= 0) {
                length += read;
                if (length == raw.length) {
                    raw = Arrays.copyOf(raw, raw.length * 2);
                }
            }
            final DomainList list = DomainList.parse(raw, length);
            domains = list;
            FileLog.d("[adblock] " + list.count + " domains loaded in " + (SystemClock.elapsedRealtime() - start) + " ms");
        } catch (Throwable e) {
            FileLog.e(e);
            loadFailedAt = SystemClock.elapsedRealtime();
            loadStarted.set(false);
        }
    }

    /**
     * Sorted domains in one byte array, one per line: about a megabyte for 40 000 domains,
     * looked up without allocating anything.
     */
    private static final class DomainList {
        final byte[] data;
        // entry i is data[offsets[i] .. offsets[i + 1] - 1), the byte before the next one is '\n'
        final int[] offsets;
        final int count;

        private DomainList(byte[] data, int[] offsets, int count) {
            this.data = data;
            this.offsets = offsets;
            this.count = count;
        }

        /** {@code raw} has room for at least one byte after {@code length}. */
        static DomainList parse(byte[] raw, int length) {
            int[] starts = new int[1 << 16];
            int count = 0;
            int write = 0;
            int lineStart = 0;
            boolean comment = false;
            // lines are compacted in place: spaces, '\r' and comments dropped, letters lowercased
            for (int i = 0; i <= length; i++) {
                final byte b = i < length ? raw[i] : (byte) '\n';
                if (b == '\n') {
                    if (write > lineStart) {
                        if (count + 1 >= starts.length) {
                            starts = Arrays.copyOf(starts, starts.length * 2);
                        }
                        starts[count++] = lineStart;
                        raw[write++] = '\n';
                    }
                    lineStart = write;
                    comment = false;
                } else if (comment || b == '#') {
                    comment = true;
                } else if (b > ' ') {
                    raw[write++] = b >= 'A' && b <= 'Z' ? (byte) (b + 32) : b;
                }
            }
            starts[count] = write;
            DomainList list = new DomainList(Arrays.copyOf(raw, write), Arrays.copyOf(starts, count + 1), count);
            for (int i = 1; i < count; i++) {
                if (list.compareEntries(i - 1, i) > 0) {
                    return list.sorted();
                }
            }
            return list;
        }

        private int compareEntries(int a, int b) {
            int i = offsets[a], iEnd = offsets[a + 1] - 1;
            int j = offsets[b], jEnd = offsets[b + 1] - 1;
            while (i < iEnd && j < jEnd) {
                final int d = (data[i++] & 0xff) - (data[j++] & 0xff);
                if (d != 0) {
                    return d;
                }
            }
            return (iEnd - i) - (jEnd - j);
        }

        /** The asset is sorted already; this is only for a list that is not. */
        private DomainList sorted() {
            final Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) {
                order[i] = i;
            }
            Arrays.sort(order, this::compareEntries);
            final byte[] newData = new byte[data.length];
            final int[] newOffsets = new int[count + 1];
            int write = 0;
            for (int i = 0; i < count; i++) {
                final int from = offsets[order[i]];
                final int size = offsets[order[i] + 1] - from;
                System.arraycopy(data, from, newData, write, size);
                newOffsets[i] = write;
                write += size;
            }
            newOffsets[count] = write;
            return new DomainList(newData, newOffsets, count);
        }

        private int compare(int index, String s, int from, int to) {
            int i = offsets[index];
            final int end = offsets[index + 1] - 1;
            while (i < end && from < to) {
                int c = s.charAt(from++);
                if (c >= 'A' && c <= 'Z') {
                    c += 32;
                }
                final int d = (data[i++] & 0xff) - c;
                if (d != 0) {
                    return d;
                }
            }
            return (end - i) - (to - from);
        }

        private boolean contains(String s, int from, int to) {
            int low = 0, high = count - 1;
            while (low <= high) {
                final int mid = (low + high) >>> 1;
                final int c = compare(mid, s, from, to);
                if (c < 0) {
                    low = mid + 1;
                } else if (c > 0) {
                    high = mid - 1;
                } else {
                    return true;
                }
            }
            return false;
        }

        /** The host or one of its parent domains is listed (a bare top-level domain never is). */
        boolean containsHost(String host) {
            int to = host.length();
            while (to > 0 && host.charAt(to - 1) == '.') {
                to--;
            }
            int from = 0;
            while (from < to) {
                final int dot = host.indexOf('.', from);
                if (dot < 0 || dot >= to) {
                    break;
                }
                if (contains(host, from, to)) {
                    return true;
                }
                from = dot + 1;
            }
            return false;
        }
    }

    // the page host changes once per page, requests come by dozens: its verdict is kept
    private static final class PageVerdict {
        final String host;
        final DomainList list;
        final boolean listed;

        PageVerdict(String host, DomainList list, boolean listed) {
            this.host = host;
            this.list = list;
            this.listed = listed;
        }
    }

    private static volatile PageVerdict pageVerdict;

    private static boolean isListedPage(DomainList list, String pageHost) {
        if (pageHost == null) {
            return false;
        }
        PageVerdict verdict = pageVerdict;
        if (verdict == null || verdict.list != list || !pageHost.equals(verdict.host)) {
            pageVerdict = verdict = new PageVerdict(pageHost, list, list.containsHost(pageHost));
        }
        return verdict.listed;
    }
    // endregion

    // region Blocking
    /** Blocking is on and the site is not one the user turned it off for. */
    public static boolean enabledFor(String pageHost) {
        return SolegramConfig.adBlock && !isAllowed(pageHost);
    }

    /**
     * A subresource of a page on {@code pageHost} that should not load. Main-frame navigations are
     * never passed here. A page of a listed domain (opened on purpose) keeps the requests to its
     * own domain; other listed hosts stay blocked, so a page host that is stale or wrong can never
     * let a third party's ads through.
     */
    public static boolean shouldBlock(Uri url, String pageHost) {
        if (!SolegramConfig.adBlock || url == null || !isWeb(url)) {
            return false;
        }
        final String host = url.getHost();
        if (TextUtils.isEmpty(host) || isAllowed(pageHost)) {
            return false;
        }
        final DomainList list = domains;
        if (list == null) {
            warmUp();
        } else {
            if (isListedPage(list, pageHost) && isOwnDomain(host, pageHost)) {
                return false;
            }
            if (list.containsHost(host)) {
                return true;
            }
        }
        return isAdPath(url.getEncodedPath());
    }

    /**
     * A new window a page on {@code pageHost} opens whose first page is on a listed domain: a
     * pop-under or an ad opened on a tap anywhere on the page. The window of a link the user tapped
     * ({@code tappedHost}, its host) is their own choice and opens, like windows to the page's own
     * domain on a listed page and to sites without blocking.
     */
    public static boolean shouldBlockPopup(Uri url, String pageHost, String tappedHost) {
        if (!SolegramConfig.adBlock || !SolegramConfig.adBlockPopups || url == null || !isWeb(url)) {
            return false;
        }
        final String host = url.getHost();
        if (TextUtils.isEmpty(host) || isAllowed(pageHost) || isAllowed(host) || isOwnDomain(host, tappedHost)) {
            return false;
        }
        final DomainList list = domains;
        if (list == null) {
            warmUp();
            return false;
        }
        if (isListedPage(list, pageHost) && isOwnDomain(host, pageHost)) {
            return false;
        }
        return list.containsHost(host);
    }

    public static boolean isWeb(Uri url) {
        final String scheme = url == null ? null : url.getScheme();
        return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    }

    // The request goes to the page's host, one of its subdomains, or a parent short of the top level.
    private static boolean isOwnDomain(String host, String pageHost) {
        final String page = normalizeHost(pageHost);
        final String request = normalizeHost(host);
        if (page == null || request == null) {
            return false;
        }
        return covers(page, request, request.length()) || request.indexOf('.') > 0 && covers(request, page, page.length());
    }

    /** Ad scripts that look the same on any host, also when a site serves them itself. */
    private static boolean isAdPath(String path) {
        if (path == null || path.length() < 8) {
            return false;
        }
        if (path.contains("/pagead/") || path.contains("/gampad/") || path.contains("adsbygoogle") || path.contains("pubads_impl")) {
            return true;
        }
        // prebid.js, prebid-8.1.min.js
        return path.startsWith("prebid", path.lastIndexOf('/') + 1) && path.endsWith(".js");
    }

    /** What a blocked request gets: an empty answer, so the page sees no network error. */
    public static WebResourceResponse blockedResponse() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(EMPTY));
    }

    public static String hostOf(String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        try {
            return Uri.parse(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }
    // endregion

    // region Sites without blocking
    private static final class Allowed {
        final String source;
        final String[] hosts;

        Allowed(String source, String[] hosts) {
            this.source = source;
            this.hosts = hosts;
        }
    }

    private static volatile Allowed allowed;

    private static String[] allowedHosts() {
        final String source = SolegramConfig.adBlockAllowed == null ? "" : SolegramConfig.adBlockAllowed;
        Allowed cached = allowed;
        if (cached == null || cached.source != source) {
            if (cached != null && cached.source.equals(source)) {
                cached = new Allowed(source, cached.hosts);
            } else {
                final LinkedHashSet<String> hosts = new LinkedHashSet<>();
                for (String line : source.split("\n")) {
                    final String host = normalizeHost(line);
                    if (host != null) {
                        hosts.add(host);
                    }
                }
                cached = new Allowed(source, hosts.toArray(new String[0]));
            }
            allowed = cached;
        }
        return cached.hosts;
    }

    /** Lowercase ASCII host without "www." and trailing dots, or null when it is not a host. */
    public static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.startsWith("www.") && h.indexOf('.', 4) > 0) {
            h = h.substring(4);
        }
        if (h.isEmpty()) {
            return null;
        }
        if (!isAsciiHost(h)) {
            try {
                h = IDN.toASCII(h).toLowerCase(Locale.ROOT);
            } catch (Exception e) {
                return null;
            }
            if (h.isEmpty() || !isAsciiHost(h)) {
                return null;
            }
        }
        return h;
    }

    // only these characters reach the page script, so nothing there can be more than a host name
    private static boolean isAsciiHost(String h) {
        for (int i = 0; i < h.length(); i++) {
            final char c = h.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /** {@code entry} is {@code host} or one of its parent domains. */
    private static boolean covers(String entry, String host, int end) {
        final int start = end - entry.length();
        if (start < 0 || start > 0 && host.charAt(start - 1) != '.') {
            return false;
        }
        return host.regionMatches(true, start, entry, 0, entry.length());
    }

    /** The user turned blocking off for this site or one of its parent domains. */
    public static boolean isAllowed(String host) {
        if (TextUtils.isEmpty(host)) {
            return false;
        }
        final String[] hosts = allowedHosts();
        if (hosts.length == 0) {
            return false;
        }
        int end = host.length();
        while (end > 0 && host.charAt(end - 1) == '.') {
            end--;
        }
        for (String entry : hosts) {
            if (covers(entry, host, end)) {
                return true;
            }
        }
        return false;
    }

    /** Turns blocking off (or back on) for the site; on also removes parent domains that covered it. */
    public static void setAllowed(String host, boolean allow) {
        final String h = normalizeHost(host);
        if (h == null) {
            return;
        }
        final ArrayList<String> hosts = new ArrayList<>(Arrays.asList(allowedHosts()));
        if (allow) {
            if (isAllowed(h)) {
                return;
            }
            hosts.add(h);
        } else {
            boolean changed = false;
            for (Iterator<String> i = hosts.iterator(); i.hasNext(); ) {
                if (covers(i.next(), h, h.length())) {
                    i.remove();
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
        }
        saveAllowed(hosts);
    }

    public static String[] getAllowed() {
        return allowedHosts().clone();
    }

    public static int allowedCount() {
        return allowedHosts().length;
    }

    public static void clearAllowed() {
        saveAllowed(new ArrayList<>());
    }

    private static void saveAllowed(ArrayList<String> hosts) {
        SolegramConfig.putString("adBlockAllowed", TextUtils.join("\n", hosts));
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.solegramSettingsChanged, "adBlockAllowed");
    }
    // endregion

    // region Counter
    private static final AtomicLong total = new AtomicLong();
    private static volatile boolean totalLoaded;
    private static final AtomicBoolean saveScheduled = new AtomicBoolean();
    private static final Runnable saveTotal = () -> {
        saveScheduled.set(false);
        SolegramConfig.prefs().edit().putLong(KEY_TOTAL, total.get()).apply();
    };

    private static void loadTotal() {
        if (totalLoaded) {
            return;
        }
        synchronized (SolegramAdBlock.class) {
            if (totalLoaded) {
                return;
            }
            long saved = 0;
            try {
                final Object value = SolegramConfig.prefs().getAll().get(KEY_TOTAL);
                if (value instanceof Number) {
                    saved = Math.max(0, ((Number) value).longValue());
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            // blocks counted before this read are kept on top of the saved number
            total.addAndGet(saved);
            totalLoaded = true;
        }
    }

    /** Counts one blocked request or pop-up; any thread. Saved at most every few seconds. */
    public static void onBlocked() {
        loadTotal();
        total.incrementAndGet();
        if (saveScheduled.compareAndSet(false, true)) {
            AndroidUtilities.runOnUIThread(saveTotal, 5000);
        }
    }

    /** Requests and pop-ups blocked since the app was installed. */
    public static long getTotal() {
        loadTotal();
        return total.get();
    }
    // endregion

    // region Page scripts
    private static String scriptTemplate;

    private static String template() {
        if (scriptTemplate == null) {
            scriptTemplate = AndroidUtilities.readRes(R.raw.sg_adblock);
        }
        return scriptTemplate;
    }

    private static String script(boolean on, String[] allowedHosts) {
        final String template = template();
        if (TextUtils.isEmpty(template)) {
            return null;
        }
        final StringBuilder config = new StringBuilder(64)
            .append("{on:").append(on)
            .append(",cosmetic:").append(SolegramConfig.adBlockCosmetic)
            .append(",youtube:").append(SolegramConfig.adBlockYouTube)
            .append(",allowed:[");
        for (int i = 0; i < allowedHosts.length; i++) {
            if (i > 0) {
                config.append(',');
            }
            config.append('"').append(allowedHosts[i]).append('"');
        }
        config.append("]}");
        return template.replace("$CONFIG$", config);
    }

    /**
     * What the document-start script is made of, null when none is needed. When it changes, the
     * script is registered again.
     */
    public static String startScriptKey() {
        if (!SolegramConfig.adBlock || !SolegramConfig.adBlockCosmetic && !SolegramConfig.adBlockYouTube) {
            return null;
        }
        return (SolegramConfig.adBlockCosmetic ? "c" : "") + (SolegramConfig.adBlockYouTube ? "y" : "") + "|" + SolegramConfig.adBlockAllowed;
    }

    /**
     * Registered once for all sites, so it checks the site itself. The list of sites without
     * blocking is read before anything of the page runs, then it stays inside the script.
     */
    public static String startScript() {
        return script(SolegramConfig.adBlock, allowedHosts());
    }

    /**
     * Run when the page is shown and loaded: applies the current settings to this page, also
     * when they changed after the document-start script was registered.
     */
    public static String pageScript(String pageHost) {
        return script(enabledFor(pageHost), new String[0]);
    }
    // endregion
}
