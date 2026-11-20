/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Solegram: takes the tracking out of links: the parameters that only say who shared a link and where from
 * (utm_*, fbclid, the "si" of YouTube and Spotify links, Instagram's igsh...), so a link sent from Solegram or opened
 * in it no longer tells the site who shared it. Everything else of the link stays as it was (a video's time, a
 * search, a page's anchor). Plain Java on purpose: it is tested off the phone (solegram/tools/privacy_tests).
 */
public final class SolegramLinkCleaner {

    private SolegramLinkCleaner() {
    }

    /** Parameters that only track, on any site. */
    private static final String[] ANY_SITE = {
        "fbclid", "gclid", "gclsrc", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "ysclid", "_openstat",
        "mc_cid", "mc_eid", "igshid", "igsh", "mibextid", "_hsenc", "_hsmi", "hsctatracking", "oly_anon_id",
        "oly_enc_id", "vero_id", "rb_clickid", "twclid", "ttclid", "srsltid", "si_source", "trk_source",
    };
    /** Prefixes of parameters that only track, on any site. */
    private static final String[] ANY_SITE_PREFIX = {"utm_", "pk_", "mtm_", "pd_rd_", "pf_rd_"};

    /** Site-specific ones: the site (and its subdomains) and what is tracking there. */
    private static final String[][] SITES = {
        {"youtube.com", "si", "feature", "pp"},
        {"youtu.be", "si", "feature", "pp"},
        {"spotify.com", "si", "context", "nd", "dlsi"},
        {"spotify.link", "si"},
        {"music.apple.com", "itscg", "itsct", "ls"},
        {"instagram.com", "igsh", "igshid", "img_index"},
        {"tiktok.com", "_r", "_t", "is_from_webapp", "sender_device", "share_app_id", "share_link_id", "social_sharing", "tt_from", "u_code", "web_id", "is_copy_url", "checksum", "sec_uid", "share_item_id"},
        {"twitter.com", "s", "t", "ref_src", "ref_url"},
        {"x.com", "s", "t", "ref_src", "ref_url"},
        {"facebook.com", "mibextid", "rdid", "share_url", "sfnsn"},
        {"reddit.com", "share_id", "ref", "ref_source", "ref_campaign"},
        {"linkedin.com", "trk", "trackingid", "lipi", "lici"},
        {"amazon.com", "ref", "ref_", "tag", "linkcode", "linkid", "psc", "content-id"},
        {"music.yandex.ru", "from", "utm_source"},
        {"music.yandex.com", "from"},
        {"vk.com", "from"},
        {"ozon.ru", "asb", "asb2", "avtc", "avte", "avts"},
        {"wildberries.ru", "targeturl"},
        {"pinterest.com", "invite_code", "sender"},
        {"threads.net", "igshid", "igsh", "xmt"},
        {"aliexpress.com", "spm", "scm", "aff_platform", "aff_trace_key", "sk", "aff_fcid", "aff_fsk"},
    };

    /** http(s) links and bare www. ones, as they appear in text. */
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>\"'`]+");

    /** A link without its tracking parameters (the same string when there is nothing to take out). */
    public static String cleanUrl(String url) {
        if (url == null) {
            return null;
        }
        final String lower = url.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www."))) {
            return url;
        }
        final int q = url.indexOf('?');
        if (q < 0) {
            return url;
        }
        final int hash = url.indexOf('#', q);
        final String base = url.substring(0, q);
        final String query = hash < 0 ? url.substring(q + 1) : url.substring(q + 1, hash);
        final String fragment = hash < 0 ? "" : url.substring(hash);
        final String host = hostOf(base);
        if (host.endsWith("t.me") || host.endsWith("telegram.me") || host.endsWith("telegram.org")) {
            return url; // Telegram's own links: start parameters and the like matter
        }
        final StringBuilder kept = new StringBuilder();
        boolean removed = false;
        for (String param : query.split("&", -1)) {
            if (param.isEmpty()) {
                removed = true; // "&&" or a trailing "&"
                continue;
            }
            final int eq = param.indexOf('=');
            final String key = (eq < 0 ? param : param.substring(0, eq)).toLowerCase(Locale.ROOT);
            if (isTracking(host, key)) {
                removed = true;
                continue;
            }
            if (kept.length() > 0) {
                kept.append('&');
            }
            kept.append(param);
        }
        if (!removed) {
            return url;
        }
        return kept.length() > 0 ? base + "?" + kept + fragment : base + fragment;
    }

    private static String hostOf(String base) {
        String rest = base;
        final int scheme = rest.indexOf("://");
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3);
        }
        final int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        final int at = rest.lastIndexOf('@');
        if (at >= 0) {
            rest = rest.substring(at + 1);
        }
        final int colon = rest.indexOf(':');
        if (colon >= 0) {
            rest = rest.substring(0, colon);
        }
        return rest.toLowerCase(Locale.ROOT);
    }

    private static boolean onSite(String host, String site) {
        return host.equals(site) || host.endsWith("." + site);
    }

    private static boolean isTracking(String host, String key) {
        for (String k : ANY_SITE) {
            if (k.equals(key)) {
                return true;
            }
        }
        for (String p : ANY_SITE_PREFIX) {
            if (key.startsWith(p)) {
                return true;
            }
        }
        for (String[] site : SITES) {
            if (onSite(host, site[0])) {
                for (int i = 1; i < site.length; i++) {
                    if (site[i].equals(key)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** A change to make in a text: [start, end) becomes {@code replacement}. */
    public static final class Change {
        public final int start, end;
        public final String replacement;

        Change(int start, int end, String replacement) {
            this.start = start;
            this.end = end;
            this.replacement = replacement;
        }
    }

    /** The links of a text to clean, in order (none: nothing to change). */
    public static ArrayList<Change> changesIn(CharSequence text) {
        final ArrayList<Change> changes = new ArrayList<>();
        if (text == null || text.length() == 0) {
            return changes;
        }
        final Matcher m = URL.matcher(text);
        while (m.find()) {
            int end = m.end();
            // punctuation right after a link belongs to the sentence ("see https://a.b/?x=1.")
            while (end > m.start() && ".,;:!?".indexOf(text.charAt(end - 1)) >= 0) {
                end--;
            }
            String url = text.subSequence(m.start(), end).toString();
            if (url.endsWith(")") && url.indexOf('(') < 0) {
                url = url.substring(0, url.length() - 1);
                end--;
            }
            final String clean = cleanUrl(url);
            if (!clean.equals(url)) {
                changes.add(new Change(m.start(), end, clean));
            }
        }
        return changes;
    }

    /** A plain text with its links cleaned. */
    public static String cleanText(String text) {
        final ArrayList<Change> changes = changesIn(text);
        if (changes.isEmpty()) {
            return text;
        }
        final StringBuilder sb = new StringBuilder(text);
        for (int i = changes.size() - 1; i >= 0; i--) {
            final Change c = changes.get(i);
            sb.replace(c.start, c.end, c.replacement);
        }
        return sb.toString();
    }
}
