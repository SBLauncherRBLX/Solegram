/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links to one's profiles on other platforms, kept in the bio («О себе»), so they need nothing but Telegram:
 * Solegram shows them as buttons with the platform's mark, plain Telegram as text.
 * <p>
 * The bio holds only 70 characters (140 with Premium), so the links are written the shortest way that still
 * reads well and opens anywhere: the platform's own short address without https:// and www. (instagram.com/anna,
 * tiktok.com/@anna, x.com/anna), Telegram as @anna, one link per line (a line break costs one character) or in
 * a row with « · ». Any user's bio is read, not only Solegram's: every address of a known platform becomes a
 * button. A line of nothing but links (and a run of links after « · » at the end of a line) is shown by the
 * buttons alone; a link inside a sentence stays in the text and gets a button too.
 */
public final class SolegramProfileLinks {

    /** Between links in a row; a line break is the other (and the shortest) way. */
    public static final String ROW_SEPARATOR = " · ";

    public static final class Platform {
        public final String id;
        public final String name;
        /** The brand colour, opaque. */
        public final int color;
        public final int icon;
        /** What comes before the username when the editor builds the link (Telegram: "@"). */
        public final String prefix;
        final String[] hosts;
        /** Matched against the path (from its "/"); the last group that matched is the username. */
        final Pattern path;

        Platform(String id, String name, int color, int icon, String prefix, String[] hosts, String path) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.icon = icon;
            this.prefix = prefix;
            this.hosts = hosts;
            this.path = path == null ? null : Pattern.compile(path, Pattern.CASE_INSENSITIVE);
        }

        public boolean isWebsite() {
            return WEBSITE_ID.equals(id);
        }

        boolean ownsHost(String host) {
            for (String h : hosts) {
                if (host.equals(h) || host.endsWith("." + h)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final String WEBSITE_ID = "website";
    public static final String TELEGRAM_ID = "telegram";

    // the order of the editor's grid: the most used first
    public static final Platform[] PLATFORMS = {
        new Platform(TELEGRAM_ID, "Telegram", 0xFF26A5E4, R.drawable.sg_brand_telegram, "@",
            new String[]{"t.me", "telegram.me", "telegram.dog"}, "^/(\\+[\\w-]{6,}|joinchat/[\\w-]{6,}|[a-z][a-z0-9_]{3,31})/?$"),
        new Platform("instagram", "Instagram", 0xFFFF0069, R.drawable.sg_brand_instagram, "instagram.com/",
            new String[]{"instagram.com", "instagr.am"}, "^/(?!p/|reels?/|stories/|explore/|accounts/)([a-z0-9_.]{1,30})/?$"),
        new Platform("tiktok", "TikTok", 0xFF000000, R.drawable.sg_brand_tiktok, "tiktok.com/@",
            new String[]{"tiktok.com"}, "^/@([a-z0-9_.]{1,30})/?$"),
        new Platform("youtube", "YouTube", 0xFFFF0000, R.drawable.sg_brand_youtube, "youtube.com/@",
            new String[]{"youtube.com"}, "^/(?:@|c/|user/|channel/)([\\w.\\-]{1,100})/?$"),
        new Platform("x", "X", 0xFF000000, R.drawable.sg_brand_x, "x.com/",
            new String[]{"x.com", "twitter.com"}, "^/(?!home/?$|i/|intent/|search|share|hashtag/|explore)([a-z0-9_]{1,15})/?$"),
        new Platform("vk", "VK", 0xFF0077FF, R.drawable.sg_brand_vk, "vk.com/",
            new String[]{"vk.com", "vk.ru", "vkontakte.ru"}, "^/(?!feed/?$|im/?$|wall-?\\d)([a-z0-9_.]{2,32})/?$"),
        new Platform("github", "GitHub", 0xFF181717, R.drawable.sg_brand_github, "github.com/",
            new String[]{"github.com"}, "^/(?!orgs/|sponsors/|topics/|features/)([a-z0-9][a-z0-9-]{0,38}(?:/[\\w.\\-]+)?)/?$"),
        new Platform("twitch", "Twitch", 0xFF9146FF, R.drawable.sg_brand_twitch, "twitch.tv/",
            new String[]{"twitch.tv"}, "^/([a-z0-9_]{2,25})/?$"),
        new Platform("spotify", "Spotify", 0xFF1ED760, R.drawable.sg_brand_spotify, "open.spotify.com/user/",
            new String[]{"open.spotify.com", "spotify.com"}, "^/(?:intl-[\\w-]+/)?(?:user|artist|show)/([\\w.\\-]{1,64})/?$"),
        new Platform("discord", "Discord", 0xFF5865F2, R.drawable.sg_brand_discord, "discord.gg/",
            new String[]{"discord.gg", "discord.com", "discord.me"}, "^/(?:invite/|users/)?([\\w-]{2,32})/?$"),
        new Platform("steam", "Steam", 0xFF1B2838, R.drawable.sg_brand_steam, "steamcommunity.com/id/",
            new String[]{"steamcommunity.com"}, "^/(?:id|profiles)/([\\w-]{2,64})/?$"),
        new Platform("pinterest", "Pinterest", 0xFFBD081C, R.drawable.sg_brand_pinterest, "pinterest.com/",
            new String[]{"pinterest.com", "pinterest.ru"}, "^/(?!pin/|search/|ideas/)([a-z0-9_]{3,30})/?$"),
        new Platform("reddit", "Reddit", 0xFFFF4500, R.drawable.sg_brand_reddit, "reddit.com/u/",
            new String[]{"reddit.com"}, "^/(?:u|user|r)/([\\w-]{2,30})/?$"),
        new Platform("linkedin", "LinkedIn", 0xFF0A66C2, R.drawable.sg_brand_linkedin, "linkedin.com/in/",
            new String[]{"linkedin.com"}, "^/(?:in|company)/([\\w\\-%]{2,100})/?$"),
        new Platform("facebook", "Facebook", 0xFF0866FF, R.drawable.sg_brand_facebook, "fb.com/",
            new String[]{"facebook.com", "fb.com"}, "^/(?!sharer|share|groups/|events/|watch|profile\\.php)([a-z0-9.]{2,50})/?$"),
        new Platform("threads", "Threads", 0xFF000000, R.drawable.sg_brand_threads, "threads.com/@",
            new String[]{"threads.com", "threads.net"}, "^/@([\\w.]{1,30})/?$"),
        new Platform("snapchat", "Snapchat", 0xFFFFFC00, R.drawable.sg_brand_snapchat, "snapchat.com/add/",
            new String[]{"snapchat.com"}, "^/add/([\\w.\\-]{3,15})/?$"),
        new Platform("soundcloud", "SoundCloud", 0xFFFF5500, R.drawable.sg_brand_soundcloud, "soundcloud.com/",
            new String[]{"soundcloud.com"}, "^/(?!discover|search|stream)([\\w-]{3,25})/?$"),
        new Platform("bluesky", "Bluesky", 0xFF1185FE, R.drawable.sg_brand_bluesky, "bsky.app/profile/",
            new String[]{"bsky.app"}, "^/profile/([\\w.\\-:]{3,253})/?$"),
        new Platform("boosty", "Boosty", 0xFFF15F2C, R.drawable.sg_brand_boosty, "boosty.to/",
            new String[]{"boosty.to"}, "^/([\\w.\\-]{2,40})/?$"),
        new Platform("patreon", "Patreon", 0xFF000000, R.drawable.sg_brand_patreon, "patreon.com/",
            new String[]{"patreon.com"}, "^/(?:c/)?(\\w{2,64})/?$"),
        new Platform(WEBSITE_ID, "Website", 0xFF8E8E93, R.drawable.sg_brand_link, "", new String[0], null),
    };

    public static Platform platform(String id) {
        for (Platform p : PLATFORMS) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return website();
    }

    public static Platform website() {
        return PLATFORMS[PLATFORMS.length - 1];
    }

    public static final class Link {
        public final Platform platform;
        /** The username (or the address, for a website). */
        public final String handle;
        /** As written in the bio: no https://, no www. (Telegram: @name). */
        public final String text;
        /** Where it stands in the parsed bio, -1 for a link made in the editor. */
        final int start, end;

        Link(Platform platform, String handle, String text, int start, int end) {
            this.platform = platform;
            this.handle = handle;
            this.text = text;
            this.start = start;
            this.end = end;
        }

        /** The address to open. */
        public String url() {
            if (text.startsWith("@")) {
                return "https://t.me/" + text.substring(1);
            }
            final String lower = text.toLowerCase(Locale.ROOT);
            return lower.startsWith("http://") || lower.startsWith("https://") ? text : "https://" + text;
        }

        /** What a button says: the platform, or the site's address. */
        public String label() {
            if (platform.isWebsite()) {
                String host = compact(text);
                final int slash = host.indexOf('/');
                return slash > 0 ? host.substring(0, slash) : host;
            }
            return platform.name;
        }

        boolean same(Link other) {
            return other != null && text.equalsIgnoreCase(other.text);
        }
    }

    public static final class Parsed {
        /** The bio without the lines (and line ends) that only hold links: what is shown above the buttons. */
        public final String text;
        /** Every recognised link, in order (the buttons). */
        public final List<Link> links;
        /** Of {@link #links}, those the text no longer shows: the editor's list (the rest stays in the text). */
        public final List<Link> removed;

        Parsed(String text, List<Link> links, List<Link> removed) {
            this.text = text;
            this.links = links;
            this.removed = removed;
        }
    }

    // an address: optional scheme and www, a host with a dot, an optional path (up to a space or punctuation)
    private static final Pattern URL = Pattern.compile(
        "(?<![\\w@./\\-])(https?://)?(?:www\\.)?((?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}\\-]{0,61}[\\p{L}\\p{N}])?\\.)+[\\p{L}]{2,24})(/[^\\s·•|,;!?()\\[\\]{}<>\"«»]*)?",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern MENTION = Pattern.compile("(?<![\\w@./])@([a-z][a-z0-9_]{3,31})(?![\\w@])", Pattern.CASE_INSENSITIVE);
    // an address without https:// counts as a website only on these (so "readme.md" or "Node.js" stay words)
    private static final HashSet<String> SITE_TLDS = new HashSet<>(Arrays.asList(
        "com", "net", "org", "io", "dev", "app", "me", "ru", "рф", "su", "ua", "by", "kz", "uz", "de", "uk", "eu", "fr", "it", "es",
        "pl", "nl", "us", "ca", "co", "ai", "xyz", "info", "tv", "fm", "gg", "to", "cc"));

    private static String cachedAbout;
    private static Parsed cachedParsed;

    private SolegramProfileLinks() {
    }

    /** Reads any bio (cached for the last one: the profile asks several times per layout). */
    public static Parsed parse(@Nullable String about) {
        if (about == null) {
            about = "";
        }
        synchronized (SolegramProfileLinks.class) {
            if (cachedParsed != null && about.equals(cachedAbout)) {
                return cachedParsed;
            }
        }
        final Parsed parsed = parseNow(about);
        synchronized (SolegramProfileLinks.class) {
            cachedAbout = about;
            cachedParsed = parsed;
        }
        return parsed;
    }

    private static Parsed parseNow(String about) {
        final ArrayList<Link> links = new ArrayList<>();
        final ArrayList<Link> removed = new ArrayList<>();
        final StringBuilder kept = new StringBuilder();
        int lineStart = 0;
        while (lineStart <= about.length()) {
            int lineEnd = about.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = about.length();
            }
            final String line = about.substring(lineStart, lineEnd);
            final ArrayList<Link> found = findLinks(line, lineStart);
            String keep = line;
            if (!found.isEmpty() || line.indexOf('@') >= 0) {
                // with @mentions too, the line may be nothing but links: then the mentions are links as well
                final ArrayList<Link> withMentions = new ArrayList<>(found);
                addMentions(line, lineStart, withMentions);
                if (!withMentions.isEmpty() && onlyLinks(line, lineStart, withMentions)) {
                    addAll(links, withMentions);
                    addAll(removed, withMentions);
                    keep = null;
                } else {
                    addAll(links, found);
                    final int cut = trailingRun(line, lineStart, found);
                    if (cut >= 0) {
                        for (Link l : found) {
                            if (l.start - lineStart >= cut) {
                                addAll(removed, l);
                            }
                        }
                        keep = trimEnd(line.substring(0, cut));
                    }
                }
            }
            if (keep != null) {
                if (kept.length() > 0) {
                    kept.append('\n');
                }
                kept.append(keep);
            }
            lineStart = lineEnd + 1;
        }
        String text = kept.toString();
        while (text.contains("\n\n\n")) {
            text = text.replace("\n\n\n", "\n\n");
        }
        return new Parsed(text.trim(), links, removed);
    }

    private static void addAll(List<Link> to, List<Link> from) {
        for (Link l : from) {
            addAll(to, l);
        }
    }

    private static void addAll(List<Link> to, Link link) {
        for (Link l : to) {
            if (l.same(link)) {
                return;
            }
        }
        to.add(link);
    }

    /** The addresses in one line (with their place in the whole bio). */
    private static ArrayList<Link> findLinks(String line, int offset) {
        final ArrayList<Link> out = new ArrayList<>();
        if (line.indexOf('.') < 0) {
            return out;
        }
        final Matcher m = URL.matcher(line);
        while (m.find()) {
            int end = m.end();
            // a sentence's full stop, colon or quote after the address is not part of it
            while (end > m.start() && ".,:;'\"".indexOf(line.charAt(end - 1)) >= 0) {
                end--;
            }
            final Link link = recognise(line.substring(m.start(), end), offset + m.start(), offset + end);
            if (link != null) {
                out.add(link);
            }
        }
        return out;
    }

    private static void addMentions(String line, int offset, ArrayList<Link> out) {
        if (line.indexOf('@') < 0) {
            return;
        }
        final Matcher m = MENTION.matcher(line);
        while (m.find()) {
            final int s = offset + m.start();
            boolean inside = false;
            for (Link l : out) {
                if (s >= l.start && s < l.end) {
                    inside = true; // tiktok.com/@name
                    break;
                }
            }
            if (!inside) {
                out.add(new Link(platform(TELEGRAM_ID), m.group(1), "@" + m.group(1), s, offset + m.end()));
            }
        }
        // in the order they stand
        out.sort((a, b) -> Integer.compare(a.start, b.start));
    }

    /** One address as a link: a known platform's profile, or a website; null when it is neither. */
    @Nullable
    static Link recognise(String raw, int start, int end) {
        final Matcher m = URL.matcher(raw);
        if (!m.matches()) {
            return null;
        }
        final boolean scheme = m.group(1) != null;
        final boolean www = raw.regionMatches(true, scheme ? m.group(1).length() : 0, "www.", 0, 4);
        final String host = m.group(2).toLowerCase(Locale.ROOT);
        final String path = m.group(3) == null ? "" : m.group(3);
        for (Platform p : PLATFORMS) {
            if (p.path == null || !p.ownsHost(host)) {
                continue;
            }
            final Matcher pm = p.path.matcher(path);
            if (!pm.find()) {
                // the platform's own page that is not a profile (instagram.com/p/…): not a profile link
                return null;
            }
            String handle = null;
            for (int g = pm.groupCount(); g >= 1 && handle == null; g--) {
                handle = pm.group(g);
            }
            return new Link(p, handle == null ? "" : handle, compact(raw), start, end);
        }
        final boolean knownTld = SITE_TLDS.contains(host.substring(host.lastIndexOf('.') + 1));
        if (!scheme && !www && !knownTld) {
            return null;
        }
        // a site on a rarer ending keeps "www." or "https://": without them it would read as a word, here and in Telegram
        String text = compact(raw);
        if (!knownTld) {
            text = (www ? "www." : "https://") + text;
        }
        return new Link(website(), text, text, start, end);
    }

    /** Without https:// and www. and the closing "/": the shortest way that still opens. */
    static String compact(String url) {
        String s = url.trim();
        final String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://")) {
            s = s.substring(8);
        } else if (lower.startsWith("http://")) {
            s = s.substring(7);
        }
        if (s.regionMatches(true, 0, "www.", 0, 4)) {
            s = s.substring(4);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static boolean isSeparator(int c) {
        if (Character.isWhitespace(c) || "·•|,;/–—-:+".indexOf(c) >= 0 || c == 0x2060 || c == 0xFE0F || c == 0x200D) {
            return true;
        }
        // an emoji in front of a link is a label: "📷 instagram.com/anna"
        final int type = Character.getType(c);
        return type == Character.OTHER_SYMBOL || type == Character.SURROGATE;
    }

    private static boolean onlyLinks(String line, int offset, List<Link> found) {
        int pos = 0;
        for (Link l : found) {
            final int s = l.start - offset;
            if (s < pos) {
                continue;
            }
            if (!separatorsOnly(line, pos, s)) {
                return false;
            }
            pos = l.end - offset;
        }
        return separatorsOnly(line, pos, line.length());
    }

    private static boolean separatorsOnly(String s, int from, int to) {
        for (int i = from; i < to; ) {
            final int c = s.codePointAt(i);
            if (!isSeparator(c)) {
                return false;
            }
            i += Character.charCount(c);
        }
        return true;
    }

    /**
     * Where a run of links at the end of the line starts ("Designer · instagram.com/anna · x.com/anna" → after
     * "Designer"), or -1. The run must follow a real separator (·, •, |), so "write to me at site.com" keeps its words.
     */
    private static int trailingRun(String line, int offset, List<Link> found) {
        int runStart = -1;
        int pos = line.length();
        for (int i = found.size() - 1; i >= 0; i--) {
            final Link l = found.get(i);
            if (!separatorsOnly(line, l.end - offset, pos)) {
                break;
            }
            runStart = l.start - offset;
            pos = runStart;
        }
        if (runStart <= 0) {
            return -1;
        }
        int i = runStart;
        boolean hard = false;
        while (i > 0) {
            final char c = line.charAt(i - 1);
            if (c == '·' || c == '•' || c == '|') {
                hard = true;
            } else if (!Character.isWhitespace(c) && "–—-:".indexOf(c) < 0) {
                break;
            }
            i--;
        }
        return hard && i > 0 ? i : -1;
    }

    private static String trimEnd(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    // region Editor

    /**
     * The link for what the user typed for a platform: a username («anna», «@anna») or a whole address pasted
     * (which may be of another platform: then that one). Null when it is neither.
     */
    @Nullable
    public static Link build(Platform platform, String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.contains(".") && (s.contains("/") || platform.isWebsite() || s.toLowerCase(Locale.ROOT).startsWith("http"))) {
            s = compactPasted(s);
            Link pasted = recognise(s, -1, -1);
            if (pasted == null && platform.isWebsite() && !s.toLowerCase(Locale.ROOT).startsWith("http")) {
                pasted = recognise("https://" + s, -1, -1); // a site on a rarer ending
            }
            if (pasted != null || platform.isWebsite()) {
                return pasted;
            }
        }
        if (platform.isWebsite()) {
            return null;
        }
        while (s.startsWith("@")) {
            s = s.substring(1);
        }
        if (s.isEmpty() || s.contains(" ")) {
            return null;
        }
        if (TELEGRAM_ID.equals(platform.id)) {
            return MENTION.matcher("@" + s).matches() ? new Link(platform, s, "@" + s, -1, -1) : null;
        }
        final Link link = recognise(platform.prefix + s, -1, -1);
        return link != null && link.platform == platform ? link : null;
    }

    /** A pasted address without its query (?igsh=… and the like): the profile is the same and it is shorter. */
    private static String compactPasted(String s) {
        final int q = s.indexOf('?');
        if (q > 0) {
            s = s.substring(0, q);
        }
        final int hash = s.indexOf('#');
        if (hash > 0) {
            s = s.substring(0, hash);
        }
        return s;
    }

    /** The bio the editor writes: the text, then the links (one per line, or in a row). */
    public static String compose(@Nullable String text, List<Link> links, boolean row) {
        final StringBuilder sb = new StringBuilder();
        final String t = text == null ? "" : text.trim();
        sb.append(t);
        if (!links.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            for (int i = 0; i < links.size(); i++) {
                if (i > 0) {
                    sb.append(row ? ROW_SEPARATOR : "\n");
                }
                sb.append(links.get(i).text);
            }
        }
        return sb.toString();
    }

    /** Whether the links of a bio stand in a row (so the editor keeps the way the user chose). */
    public static boolean looksLikeRow(@Nullable String about) {
        return !TextUtils.isEmpty(about) && about.contains(ROW_SEPARATOR.trim());
    }

    /** The length Telegram counts for the bio (UTF-16 units, as its own editor does). */
    public static int length(String about) {
        return about == null ? 0 : about.length();
    }
    // endregion
}
