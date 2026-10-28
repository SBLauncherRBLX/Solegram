/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The anti-scam rules: how risky a link or an account looks, from what is known right here, with no network.
 * <p>
 * A link is judged by its address (look-alike domains, brand names on other sites, hidden destinations,
 * downloads, Telegram usernames posing as support...), by the message around it (free Premium, "your account
 * will be blocked", "send me the code"...) and by who sent it where ({@link Context}). Every sign has a kind:
 * <ul>
 * <li>hard: a disguised address (telegrarn.org, Cyrillic letters dressed as Latin ones, telegram.org@evil.top).
 * Nothing talks these away but the user's own trust;</li>
 * <li>medium: something to look at (a brand's name inside another site's name, a program to install);</li>
 * <li>weak: common on honest sites too (a cheap domain ending, a short link, free hosting). These count only
 * next to other evidence: another sign of the address, bait in the message, or a stranger who sent it.</li>
 * </ul>
 * Who sent the message then discounts everything but the hard signs: a contact, a long chat, a big public channel,
 * a verified account. An account is warned about only with two independent strong signs, or Telegram's own mark.
 * Pure Java on purpose (no Android classes): the rules are tested on a desktop JVM.
 */
public final class SolegramScamRules {

    /** Accents and other combining marks (declared first: the brand tables below are built with it). */
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    public static final int SAFE = 0;
    public static final int CAUTION = 1;
    public static final int DANGER = 2;

    public static final int SENSITIVITY_SOFT = 0;
    public static final int SENSITIVITY_NORMAL = 1;
    public static final int SENSITIVITY_STRICT = 2;

    // region Reasons

    // the address itself
    public static final int R_SCRIPT = 1;
    public static final int R_DATA = 2;
    /** Letters of another alphabet that look like Latin ones, copying a brand: arg = the brand's site. */
    public static final int R_HOMOGRAPH = 3;
    /** One name mixes alphabets (Latin and Cyrillic). */
    public static final int R_MIXED_SCRIPT = 4;
    /** One or two letters off a brand (telegrarn, g00gle): arg = the brand's site. */
    public static final int R_LOOKALIKE = 5;
    /** A brand's name with a domain the brand does not own (telegram.xyz): arg = the brand's site. */
    public static final int R_BRAND_TLD = 6;
    /** A brand's name inside another site's name (telegram-premium-gift.xyz): arg = the brand. */
    public static final int R_BRAND_IN_DOMAIN = 7;
    /** A brand's name as a subdomain of another site (sberbank.secure-pay.top): arg = the brand. */
    public static final int R_BRAND_SUBDOMAIN = 8;
    /** Bait words in the address (login, gift, verify...): arg = the word. */
    public static final int R_LURE_WORDS = 9;
    /** The text shows one address, the link goes to another: arg = the shown one. */
    public static final int R_TEXT_MISMATCH = 10;
    public static final int R_IP = 11;
    /** Something@ before the site: the part before @ is not where the link goes; arg = that part. */
    public static final int R_USERINFO = 12;
    public static final int R_DEEP_SUBDOMAINS = 13;
    /** A domain ending often bought for throwaway sites: arg = the ending. */
    public static final int R_RISKY_TLD = 14;
    /** A short link: where it goes is not seen. arg = the shortener. */
    public static final int R_SHORTENER = 15;
    /** A program to install (.apk, .exe...): arg = the extension. */
    public static final int R_FILE = 16;
    /** A free site builder or hosting anyone can put a page on: arg = the hosting. */
    public static final int R_FREE_HOSTING = 17;
    /** A Telegram username posing as support, an official account, a gift: arg = @username. */
    public static final int R_TG_IMPERSONATION = 18;
    public static final int R_PORT = 19;

    // the message around the link (and the first messages of a stranger)
    public static final int R_TEXT_GIFT = 30;
    public static final int R_TEXT_VERIFY = 31;
    public static final int R_TEXT_BLOCK = 32;
    public static final int R_TEXT_CODE = 33;
    public static final int R_TEXT_CRYPTO = 34;
    public static final int R_TEXT_MONEY = 35;
    public static final int R_TEXT_VOTE = 36;
    public static final int R_TEXT_JOB = 37;
    public static final int R_TEXT_PRIZE = 38;
    public static final int R_TEXT_URGENT = 39;

    // accounts
    public static final int A_SCAM = 50;
    public static final int A_FAKE = 51;
    /** The name poses as Telegram, support, an administration, a bank: arg = the word. */
    public static final int A_NAME_IMPERSONATION = 52;
    /** The username does: arg = @username. */
    public static final int A_USERNAME_IMPERSONATION = 53;
    /** A check mark drawn into the name. */
    public static final int A_FAKE_CHECKMARK = 54;
    /** The same name as one of the user's contacts (another person): arg = the contact's name. */
    public static final int A_CONTACT_NAME = 55;
    public static final int A_NO_COMMON_GROUPS = 56;
    public static final int A_NO_USERNAME = 57;
    public static final int A_NO_PHOTO = 58;
    /** Registered recently: arg = month "MM.yyyy". */
    public static final int A_NEW_ACCOUNT = 59;
    /** The name was changed recently: arg = days ago. */
    public static final int A_NAME_CHANGED = 60;
    /** The photo was changed recently: arg = days ago. */
    public static final int A_PHOTO_CHANGED = 61;
    /** A new account that has just changed its name or photo. */
    public static final int A_FRESH_AND_CHANGED = 62;
    /** Wrote first, with links. */
    public static final int A_FIRST_LINKS = 63;

    /** The verdict of AI (on the device or asked by the user): arg = what it said. */
    public static final int R_AI = 90;

    /** Kinds of evidence: see the class comment. Text is the message's own signs. */
    public static final int KIND_WEAK = 0, KIND_MEDIUM = 1, KIND_HARD = 2, KIND_TEXT = 3;

    public static final class Reason {
        public final int code;
        /** Domain, word, name... shown in the reason; may be null. */
        public final String arg;
        public final int weight;
        /** {@link #KIND_WEAK}... for links; for accounts {@link #KIND_HARD} marks a strong sign. */
        public final int kind;
        /** For accounts: strong signs of one group are one piece of evidence (0 = no group). */
        final int group;

        Reason(int code, String arg, int weight) {
            this(code, arg, weight, defaultKind(code), 0);
        }

        Reason(int code, String arg, int weight, int kind, int group) {
            this.code = code;
            this.arg = arg;
            this.weight = weight;
            this.kind = kind;
            this.group = group;
        }

        public boolean isHard() {
            return kind == KIND_HARD;
        }

        @Override
        public String toString() {
            return code + (arg != null ? "(" + arg + ")" : "") + "+" + weight + "wmht".charAt(kind);
        }
    }

    /** The usual kind of a reason (some are stronger or weaker by what exactly was found). */
    static int defaultKind(int code) {
        switch (code) {
            case R_SCRIPT:
            case R_DATA:
            case R_HOMOGRAPH:
            case R_MIXED_SCRIPT:
            case R_LOOKALIKE:
            case R_BRAND_TLD:
            case R_USERINFO:
            case A_SCAM:
            case A_FAKE:
                return KIND_HARD;
            case R_BRAND_IN_DOMAIN:
            case R_BRAND_SUBDOMAIN:
            case R_TEXT_MISMATCH:
            case R_IP:
            case R_FILE:
            case R_TG_IMPERSONATION:
                return KIND_MEDIUM;
            default:
                return code >= R_TEXT_GIFT && code <= R_TEXT_URGENT ? KIND_TEXT : KIND_WEAK;
        }
    }

    /** True for reasons no message context or AI may talk away (a disguised address), as they usually are. */
    public static boolean isHardReason(int code) {
        return defaultKind(code) == KIND_HARD;
    }

    // endregion

    // region Levels

    /** {caution, danger} points for links, by sensitivity. */
    private static final int[][] LINK_THRESHOLDS = {{40, 75}, {30, 60}, {20, 50}};
    /** The same for accounts (once two strong signs are there). */
    private static final int[][] ACCOUNT_THRESHOLDS = {{45, 80}, {35, 70}, {25, 55}};
    /** Below this a link gets no mark at all, at any sensitivity (the strict caution threshold). */
    public static final int MARK_FLOOR = 20;

    private static int clampSensitivity(int s) {
        return Math.max(SENSITIVITY_SOFT, Math.min(SENSITIVITY_STRICT, s));
    }

    public static int linkLevel(int score, int sensitivity) {
        final int[] t = LINK_THRESHOLDS[clampSensitivity(sensitivity)];
        return score >= t[1] ? DANGER : score >= t[0] ? CAUTION : SAFE;
    }

    /** Between caution and danger: an on-device AI may take a second look (and only lower it). */
    public static boolean isBorderlineLink(int score, int sensitivity) {
        return linkLevel(score, sensitivity) == CAUTION;
    }

    // endregion

    // region Link report

    public static final class LinkReport {
        public final String url;
        /** Lowercase ASCII host (punycode), or null for a link without one (data:, javascript:). */
        public String host;
        /** The host as people read it (Unicode letters instead of xn--). */
        public String displayHost;
        /** The registrable domain: the part of the host that names the site's owner (example.co.uk, user.github.io). */
        public String site;
        /** A big, well known site (or the brand's own): the address itself raises nothing. */
        public boolean trusted;
        /** A big site where anyone can publish (Google Forms, Telegraph...): the message around it still counts. */
        public boolean userContent;
        /** A short or affiliate link: where it goes is not seen. */
        public boolean shortener;
        /** Telegram username of a t.me link (lowercase), or null. */
        public String telegramUsername;
        /** A Telegram link that is not a username (invite, sticker set...). */
        public boolean telegramService;
        /** Sum of all the weights (the address alone in its worst light; see {@link #score(LinkReport, TextReport, Context)}). */
        public int score;
        public final ArrayList<Reason> reasons = new ArrayList<>();
        /** Ranges {start, end} in {@link #displayHost} that do the deceiving. */
        public final ArrayList<int[]> highlight = new ArrayList<>();

        LinkReport(String url) {
            this.url = url;
        }

        void add(int code, String arg, int weight) {
            add(code, arg, weight, defaultKind(code));
        }

        void add(int code, String arg, int weight, int kind) {
            for (int i = 0; i < reasons.size(); i++) {
                final Reason r = reasons.get(i);
                if (r.code == code) {
                    if (r.weight >= weight && r.kind >= kind) {
                        return;
                    }
                    score -= r.weight;
                    reasons.remove(i);
                    weight = Math.max(weight, r.weight);
                    kind = Math.max(kind, r.kind);
                    break;
                }
            }
            reasons.add(new Reason(code, arg, weight, kind, 0));
            score += weight;
        }

        public boolean has(int code) {
            for (Reason r : reasons) {
                if (r.code == code) {
                    return true;
                }
            }
            return false;
        }

        public boolean hasHardReason() {
            for (Reason r : reasons) {
                if (r.kind == KIND_HARD) {
                    return true;
                }
            }
            return false;
        }

        /** The reasons, the heaviest first. */
        public List<Reason> sortedReasons() {
            final ArrayList<Reason> list = new ArrayList<>(reasons);
            Collections.sort(list, (a, b) -> b.kind != a.kind && (a.kind == KIND_HARD || b.kind == KIND_HARD) ? b.kind - a.kind : b.weight - a.weight);
            return list;
        }

        @Override
        public String toString() {
            return score + " " + sortedReasons();
        }
    }

    /** The message text signs, scored once per message. */
    public static final class TextReport {
        public int score;
        public final ArrayList<Reason> reasons = new ArrayList<>();
    }

    /**
     * Who sent a link, and where (filled by the app). The rules discount everything but a disguised address by it:
     * friends share links all the time, strangers who write first rarely mean well.
     */
    public static final class Context {
        /** No message around the link (a bio, a button, a web page): nothing to discount, nothing to add. */
        public static final Context NONE = new Context();

        /** The user sent it: never marked. */
        public boolean outgoing;
        /** A verified or official account sent it, a verified channel, Telegram itself. */
        public boolean official;
        /** A contact (or a mutual contact, or someone the user said they know) sent it. */
        public boolean contact;
        /** A private chat the user has been talking in. */
        public boolean conversed;
        /** A post of a big public channel. */
        public boolean bigChannel;
        /** A group or channel the user created. */
        public boolean ownChat;
        /** A chat the user joined long ago (or one with a long history). */
        public boolean longHistory;
        /** Forwarded into the user's Saved Messages: kept on purpose. */
        public boolean savedForward;
        /** A stranger who wrote in a private chat the user never answered in. */
        public boolean stranger;
        /** Someone in a group who is not a contact. */
        public boolean unknownSender;

        /** Bits for a cache key: a report made in one context is not reused in another. */
        public int key() {
            return (outgoing ? 1 : 0) | (official ? 2 : 0) | (contact ? 4 : 0) | (conversed ? 8 : 0) | (bigChannel ? 16 : 0)
                | (ownChat ? 32 : 0) | (longHistory ? 64 : 0) | (savedForward ? 128 : 0) | (stranger ? 256 : 0) | (unknownSender ? 512 : 0);
        }

        /** How much of a link's soft signs is left (1 = all of it, 0 = none) when the message comes from here. */
        float trust(boolean combination) {
            if (official) {
                return 0f;
            }
            float f = 1f;
            if (contact) {
                f = Math.min(f, 0.5f);
            }
            if (savedForward || bigChannel) {
                f = Math.min(f, 0.5f);
            }
            if (conversed || ownChat) {
                f = Math.min(f, 0.6f);
            }
            if (longHistory) {
                f = Math.min(f, 0.7f);
            }
            // a hacked friend sends bait text with a matching link: that combination outweighs the trust
            if (combination && f < 1f) {
                f = Math.max(f, 0.8f);
            }
            return f;
        }
    }

    // endregion

    // region Data

    private static final class Brand {
        final String label;
        final String display;
        /** The name is also an everyday word (apple, steam): it counts only with other signs. */
        final boolean generic;
        /** brand.<country> is the brand's too (google.de, yandex.kz). */
        final boolean anyCountry;
        /** Registrable domains (ASCII; written Unicode ones are converted). */
        final String[] domains;
        /** Real, unrelated names a letter or two away (finance for binance). */
        final String[] exclude;

        Brand(String label, String display, boolean generic, boolean anyCountry, String[] domains, String... exclude) {
            this.label = label;
            this.display = display;
            this.generic = generic;
            this.anyCountry = anyCountry;
            this.domains = new String[domains.length];
            for (int i = 0; i < domains.length; i++) {
                this.domains[i] = toAscii(domains[i]);
            }
            this.exclude = exclude;
        }

        boolean owns(String site) {
            for (String d : domains) {
                if (d.equals(site)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static String[] d(String... domains) {
        return domains;
    }

    private static final String[] TELEGRAM_DOMAINS = d("telegram.org", "t.me", "telegram.me", "telegram.dog", "telegra.ph", "graph.org",
        "tdesktop.com", "telesco.pe", "tg.dev", "contest.com", "fragment.com");
    private static final String[] STEAM_DOMAINS = d("steamcommunity.com", "steampowered.com", "steamstatic.com", "steamgames.com", "s.team",
        "valvesoftware.com", "steamdeck.com", "steamchina.com", "steamusercontent.com", "steamserver.net", "steamcontent.com", "steam-chat.com");
    private static final String[] SBER_DOMAINS = d("sberbank.ru", "sberbank.com", "sber.ru", "sberbank-ast.ru", "sberbankins.ru", "sberdevices.ru",
        "sbermarket.ru", "sbermegamarket.ru", "megamarket.ru", "sberauto.com", "sberhealth.ru", "sbermobile.ru", "sberbank.kz", "sbrf.ru",
        "sberinsur.ru", "sberleasing.ru", "sbercloud.ru", "sberzvuk.com", "sber.games", "sberbank.by", "sberpravo.ru", "sberclass.ru");
    private static final String[] TINKOFF_DOMAINS = d("tinkoff.ru", "tbank.ru", "tinkoff.com", "tinkoffinsurance.ru", "t-j.ru", "tbank.com",
        "tinkoffjournal.ru", "tinkoff-bank.ru", "tcsbank.ru");
    private static final String[] GOOGLE_DOMAINS = d("google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "googlevideo.com", "ggpht.com",
        "g.co", "withgoogle.com", "google.dev", "googleblog.com", "googlesource.com", "gmail.com", "youtube.com", "youtu.be", "android.com", "blogger.com",
        "chrome.com", "translate.goog", "googledrive.com", "googlemail.com", "google", "goo.gl", "forms.gle", "g.page", "goo.su", "web.dev",
        "firebase.google.com", "googletagmanager.com", "google-analytics.com", "doubleclick.net");
    private static final String[] YOUTUBE_DOMAINS = d("youtube.com", "youtu.be", "ytimg.com", "youtube-nocookie.com", "googlevideo.com", "youtubekids.com", "google.com");
    private static final String[] APPLE_DOMAINS = d("apple.com", "icloud.com", "apple.news", "mzstatic.com", "itunes.com", "me.com", "cdn-apple.com", "apple.co");
    private static final String[] META_DOMAINS = d("facebook.com", "fb.com", "fb.me", "fbcdn.net", "meta.com", "messenger.com", "fb.watch", "facebook.net");
    private static final String[] MICROSOFT_DOMAINS = d("microsoft.com", "live.com", "outlook.com", "office.com", "microsoftonline.com", "windows.com", "msn.com",
        "office365.com", "sharepoint.com", "onedrive.com", "azure.com", "skype.com", "xbox.com", "bing.com", "microsoft365.com", "live.net", "aka.ms",
        "asp.net", "dot.net", "visualstudio.com", "github.com");
    private static final String[] YANDEX_DOMAINS = d("yandex.ru", "yandex.com", "ya.ru", "yandex.net", "yastatic.net", "yandex.cloud", "yandexcloud.net",
        "yandex-team.ru", "ya.cc", "yandex.by", "yandex.kz", "yandex.uz", "dzen.ru", "kinopoisk.ru", "yadi.sk", "go.yandex", "yandex.eu", "yandex.com.tr");
    private static final String[] VK_DOMAINS = d("vk.com", "vk.ru", "vk.me", "vk.cc", "vk.link", "vkontakte.ru", "vkvideo.ru", "userapi.com", "vk-portal.net",
        "vkuser.net", "vk.company", "vk-cdn.net", "vk.team", "vkplay.ru", "vk.design", "mail.ru", "ok.ru");

    private static final Brand[] BRANDS = {
        new Brand("telegram", "telegram.org", false, false, TELEGRAM_DOMAINS, "telegraf", "telegraph", "telegrams"),
        new Brand("fragment", "fragment.com", true, false, d("fragment.com")),
        new Brand("steamcommunity", "steamcommunity.com", false, false, STEAM_DOMAINS),
        new Brand("steampowered", "steampowered.com", false, false, STEAM_DOMAINS),
        new Brand("steam", "steampowered.com", true, false, STEAM_DOMAINS),
        new Brand("gosuslugi", "gosuslugi.ru", false, false, d("gosuslugi.ru", "госуслуги.рф", "gu-st.ru", "gosuslugi41.ru")),
        new Brand("sberbank", "sberbank.ru", false, false, SBER_DOMAINS),
        new Brand("sber", "sberbank.ru", false, false, SBER_DOMAINS),
        new Brand("tinkoff", "tinkoff.ru", false, false, TINKOFF_DOMAINS),
        new Brand("tbank", "tbank.ru", false, false, TINKOFF_DOMAINS),
        new Brand("vtb", "vtb.ru", false, false, d("vtb.ru", "vtb.com", "vtb24.ru", "vtbcapital.ru", "vtb.am", "vtb.by", "vtbdirect.ru", "vtbreg.ru")),
        new Brand("alfabank", "alfabank.ru", false, false, d("alfabank.ru", "alfabank.com", "alfabank.by", "alfabank.kz", "alfa-bank.ru", "alfa-bank.kz", "alfadirect.ru")),
        new Brand("ozon", "ozon.ru", false, false, d("ozon.ru", "ozon.com", "ozone.ru", "ozon.travel", "ozon.kz", "ozon.by", "ozonbank.ru", "ozon.tech")),
        new Brand("wildberries", "wildberries.ru", false, false, d("wildberries.ru", "wb.ru", "wildberries.by", "wildberries.kz", "wildberries.am",
            "wildberries.uz", "wbstatic.net", "wbbasket.ru", "wildberries.ge", "wildberries.kg", "wb-bank.ru", "wildberries.com", "wbcon.ru")),
        new Brand("avito", "avito.ru", false, false, d("avito.ru", "avito.st", "avito.com", "avito.tech")),
        new Brand("youla", "youla.ru", false, false, d("youla.ru", "youla.io")),
        new Brand("cdek", "cdek.ru", false, false, d("cdek.ru", "cdek.shopping", "cdek.market", "cdek.kz", "cdek.by")),
        new Brand("yandex", "yandex.ru", false, true, YANDEX_DOMAINS),
        new Brand("vk", "vk.com", false, false, VK_DOMAINS),
        new Brand("vkontakte", "vk.com", false, false, VK_DOMAINS),
        new Brand("google", "google.com", false, true, GOOGLE_DOMAINS),
        new Brand("gmail", "gmail.com", false, false, GOOGLE_DOMAINS),
        new Brand("youtube", "youtube.com", false, false, YOUTUBE_DOMAINS),
        new Brand("apple", "apple.com", true, true, APPLE_DOMAINS),
        new Brand("icloud", "icloud.com", false, false, APPLE_DOMAINS),
        new Brand("appleid", "appleid.apple.com", false, false, APPLE_DOMAINS),
        new Brand("paypal", "paypal.com", false, true, d("paypal.com", "paypal.me", "paypalobjects.com", "paypal-community.com")),
        new Brand("binance", "binance.com", false, false, d("binance.com", "binance.org", "binance.us", "binance.info", "bnbchain.org", "binance.cloud", "binance.vision"), "finance"),
        new Brand("bybit", "bybit.com", false, false, d("bybit.com", "bybitglobal.com", "bybit.eu")),
        new Brand("metamask", "metamask.io", false, false, d("metamask.io", "consensys.io", "consensys.net")),
        new Brand("tonkeeper", "tonkeeper.com", false, false, d("tonkeeper.com")),
        new Brand("wallet", "wallet.tg", true, false, d("wallet.tg")),
        new Brand("roblox", "roblox.com", false, false, d("roblox.com", "rbxcdn.com")),
        new Brand("discord", "discord.com", false, false, d("discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media", "discordstatus.com", "discord.co", "dis.gd"), "discogs"),
        new Brand("instagram", "instagram.com", false, false, d("instagram.com", "cdninstagram.com", "instagr.am", "ig.me")),
        new Brand("tiktok", "tiktok.com", false, false, d("tiktok.com", "tiktokcdn.com", "tiktokv.com", "tiktokcdn-us.com")),
        new Brand("facebook", "facebook.com", false, false, META_DOMAINS),
        new Brand("whatsapp", "whatsapp.com", false, false, d("whatsapp.com", "wa.me", "whatsapp.net")),
        new Brand("microsoft", "microsoft.com", false, false, MICROSOFT_DOMAINS),
        new Brand("outlook", "outlook.com", false, false, MICROSOFT_DOMAINS),
        new Brand("amazon", "amazon.com", false, true, d("amazon.com", "a.co", "amzn.to", "media-amazon.com", "ssl-images-amazon.com", "amazon.co.uk", "amazon.co.jp", "amazon.com.br")),
        new Brand("netflix", "netflix.com", false, false, d("netflix.com", "nflxext.com", "nflximg.net", "nflxvideo.net", "netflix.net")),
        new Brand("spotify", "spotify.com", false, false, d("spotify.com", "spotify.link", "scdn.co", "spoti.fi")),
        new Brand("coinbase", "coinbase.com", false, false, d("coinbase.com")),
        new Brand("trustwallet", "trustwallet.com", false, false, d("trustwallet.com")),
        new Brand("kucoin", "kucoin.com", false, false, d("kucoin.com")),
        new Brand("okx", "okx.com", false, false, d("okx.com")),
        new Brand("epicgames", "epicgames.com", false, false, d("epicgames.com", "unrealengine.com", "fortnite.com")),
        new Brand("faceit", "faceit.com", false, false, d("faceit.com"), "facet"),
        new Brand("github", "github.com", false, false, d("github.com", "githubusercontent.com", "github.dev", "githubassets.com", "github.blog")),
        new Brand("twitter", "x.com", false, false, d("twitter.com", "x.com", "t.co", "twimg.com"), "twister"),
        new Brand("linkedin", "linkedin.com", false, false, d("linkedin.com", "lnkd.in")),
        new Brand("aliexpress", "aliexpress.com", false, false, d("aliexpress.com", "aliexpress.ru", "alicdn.com", "aliexpress.us")),
        new Brand("gazprombank", "gazprombank.ru", false, false, d("gazprombank.ru", "gpb.ru")),
        new Brand("pochtabank", "pochtabank.ru", false, false, d("pochtabank.ru")),
        new Brand("yoomoney", "yoomoney.ru", false, false, d("yoomoney.ru")),
        new Brand("megafon", "megafon.ru", false, false, d("megafon.ru"), "megaton"),
        new Brand("kaspi", "kaspi.kz", false, false, d("kaspi.kz")),
        new Brand("nalog", "nalog.gov.ru", true, false, d("nalog.gov.ru", "nalog.ru")),
    };

    /** Brand names as people write them in Cyrillic domains (госуслуги.online): label -> the brand's Latin label. */
    private static final Map<String, String> CYRILLIC_BRANDS = new HashMap<>();

    static {
        final String[][] pairs = {
            {"госуслуги", "gosuslugi"}, {"госуслуга", "gosuslugi"}, {"сбербанк", "sberbank"}, {"сбер", "sber"}, {"тинькофф", "tinkoff"},
            {"тинькоф", "tinkoff"}, {"тбанк", "tbank"}, {"втб", "vtb"}, {"альфабанк", "alfabank"}, {"озон", "ozon"}, {"вайлдберриз", "wildberries"},
            {"валберис", "wildberries"}, {"вб", "wildberries"}, {"авито", "avito"}, {"юла", "youla"}, {"сдэк", "cdek"}, {"яндекс", "yandex"},
            {"вконтакте", "vkontakte"}, {"вк", "vk"}, {"телеграм", "telegram"}, {"телеграмм", "telegram"}, {"гугл", "google"}, {"ютуб", "youtube"},
            {"стим", "steam"}, {"бинанс", "binance"}, {"госуслугирф", "gosuslugi"}
        };
        for (String[] p : pairs) {
            CYRILLIC_BRANDS.put(p[0], p[1]);
        }
    }

    /** {@link #canon} of each brand's name and of its site's name, in the order of {@link #BRANDS}. */
    private static final String[] BRAND_CANON = new String[BRANDS.length];
    private static final String[] BRAND_SITE_CANON = new String[BRANDS.length];

    static {
        for (int i = 0; i < BRANDS.length; i++) {
            BRAND_CANON[i] = canon(BRANDS[i].label);
            BRAND_SITE_CANON[i] = canon(siteLabel(BRANDS[i].display));
        }
    }

    /** Hosts of big sites where anyone can publish a page or a form: trusted as sites, but their content is not. */
    private static final Set<String> USER_CONTENT = new HashSet<>(Arrays.asList(
        "docs.google.com", "forms.gle", "sites.google.com", "drive.google.com", "googleusercontent.com", "telegra.ph", "graph.org",
        "teletype.in", "forms.yandex.ru", "disk.yandex.ru", "yadi.sk", "cloud.mail.ru", "dropbox.com", "notion.so", "medium.com",
        "dzen.ru", "pastebin.com", "linktr.ee", "taplink.cc", "tilda.cc", "vk.com", "web.archive.org"
    ));

    /** Short-link services: the real destination is hidden. */
    private static final Set<String> SHORTENERS = new HashSet<>(Arrays.asList(
        "bit.ly", "bitly.com", "clck.ru", "tinyurl.com", "cutt.ly", "is.gd", "v.gd", "ow.ly", "buff.ly", "rebrand.ly",
        "shorturl.at", "tiny.cc", "rb.gy", "bl.ink", "s.id", "t.ly", "short.io", "u.to", "qps.ru", "surl.li", "gg.gg",
        "adf.ly", "sh.st", "ouo.io", "bc.vc", "kutt.it", "x.gd", "bit.do", "shorturl.asia", "tny.im", "inlnk.ru", "clc.to",
        "cli.re", "l.wl.co", "urlz.fr", "shrtco.de", "t2m.io", "short.gy", "rotf.lol", "tinu.be", "hyperurl.co", "bitly.ws", "trib.al",
        "lnkd.at", "1url.cz", "2.gp", "ux.nu", "zpr.io", "linkd.in", "link.ru", "kortlink.dk", "go.link", "vk.cc", "ya.cc", "goo.su",
        "amzn.to", "spoti.fi", "t.co", "lnkd.in", "dis.gd"
    ));

    /** Affiliate redirects of deal channels (they go to the shop they name). */
    private static final Set<String> AFFILIATE = new HashSet<>(Arrays.asList(
        "alii.pub", "ali.click", "aliclick.shop", "ali.ski", "fas.st", "kpwfp.com", "alitems.com", "alitems.site", "admitad.com", "lenkmio.com",
        "got.by", "rcpt.pub", "pxl.leads.su", "leads.su", "cityads.com", "epn.bz", "lmes.ru", "kas.st"
    ));

    /** Free hosting and site builders, as public suffixes: user.vercel.app is the user's site, not Vercel's. */
    private static final Set<String> FREE_HOSTING = new HashSet<>(Arrays.asList(
        "github.io", "gitlab.io", "vercel.app", "netlify.app", "netlify.com", "web.app", "firebaseapp.com", "pages.dev", "workers.dev",
        "glitch.me", "repl.co", "replit.app", "herokuapp.com", "onrender.com", "blogspot.com", "wixsite.com", "weebly.com",
        "000webhostapp.com", "tilda.ws", "ngrok-free.app", "ngrok.io", "ngrok.app", "trycloudflare.com", "duckdns.org", "ddns.net",
        "azurewebsites.net", "appspot.com", "r2.dev", "surge.sh", "webflow.io", "framer.app", "framer.website", "carrd.co", "squarespace.com",
        "wordpress.com", "ucoz.ru", "ucoz.net", "narod.ru", "nethouse.ru", "tb.ru", "mya5.ru", "jimdosite.com", "godaddysites.com",
        "myshopify.com", "lovable.app", "bolt.host", "fly.dev", "railway.app", "deno.dev", "codesandbox.io", "stackblitz.io", "ipfs.io",
        "dweb.link", "on.fleek.co", "hpage.com", "free.fr", "altervista.org", "mybluehost.me", "page.link", "typedream.app",
        "hashnode.dev", "readthedocs.io", "gitbook.io", "notion.site", "s3.amazonaws.com", "amazonaws.com", "bandcamp.com", "itch.io",
        "taplink.ws", "mssg.me", "ru.net", "ru.com"
    ));

    /**
     * A compact part of the public suffix list: endings under which every name is someone else's site
     * (example.co.uk, shop.msk.ru). With {@link #FREE_HOSTING} it gives the registrable domain (eTLD+1).
     */
    private static final Set<String> MULTI_SUFFIXES = new HashSet<>(Arrays.asList(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk", "nhs.uk", "com.au", "net.au", "org.au", "edu.au", "gov.au",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp", "com.br", "com.tr", "co.in", "com.cn", "edu.cn", "gov.cn", "com.hk", "com.tw", "com.sg",
        "co.kr", "ac.kr", "go.kr", "co.il", "co.nz", "co.za", "com.mx", "com.ar", "com.ua", "kiev.ua", "org.ua", "net.ua", "pp.ua", "in.ua",
        "gov.ua", "com.ru", "net.ru", "org.ru", "pp.ru", "gov.ru", "edu.ru", "ac.ru", "int.ru", "mil.ru", "com.kz", "org.kz", "gov.kz",
        "edu.kz", "com.by", "gov.by", "com.pl", "com.es", "com.de", "co.de", "com.pk", "com.ng", "com.eg", "com.sa", "com.vn", "com.ph",
        "com.my", "co.id", "co.th", "com.co", "com.pe", "com.uy", "com.ec", "com.ve", "gov.uz", "com.uz", "spb.su", "msk.su", "gc.ca",
        "gouv.fr", "europa.eu",
        // Russian city and region names (RU-CENTER's geographic domains)
        "msk.ru", "spb.ru", "nov.ru", "nnov.ru", "nsk.ru", "novosibirsk.ru", "e-burg.ru", "yekaterinburg.ru", "kazan.ru", "samara.ru",
        "perm.ru", "omsk.ru", "chel.ru", "chelyabinsk.ru", "ufa.ru", "rnd.ru", "krasnoyarsk.ru", "voronezh.ru", "vrn.ru", "volgograd.ru",
        "saratov.ru", "tyumen.ru", "tomsk.ru", "tom.ru", "irkutsk.ru", "khabarovsk.ru", "khv.ru", "vladivostok.ru", "kaluga.ru", "tula.ru",
        "tver.ru", "ryazan.ru", "lipetsk.ru", "kursk.ru", "penza.ru", "kirov.ru", "izhevsk.ru", "udm.ru", "orenburg.ru", "murmansk.ru",
        "karelia.ru", "komi.ru", "yakutia.ru", "kuban.ru", "stavropol.ru", "stv.ru", "dagestan.ru", "tatarstan.ru", "bashkiria.ru",
        "chuvashia.ru", "mordovia.ru", "vladimir.ru", "ivanovo.ru", "kostroma.ru", "yaroslavl.ru", "jar.ru", "smolensk.ru", "bryansk.ru",
        "belgorod.ru", "tambov.ru", "kurgan.ru", "magadan.ru", "sakhalin.ru", "kamchatka.ru", "amur.ru", "chita.ru", "buryatia.ru",
        "khakassia.ru", "altai.ru", "kemerovo.ru", "surgut.ru", "yamal.ru", "vologda.ru", "arkhangelsk.ru", "astrakhan.ru", "koenig.ru",
        "simbirsk.ru", "mari-el.ru", "kchr.ru", "nalchik.ru", "vladikavkaz.ru", "grozny.ru", "adygeya.ru", "tsk.ru", "ptz.ru", "cbg.ru"
    ));

    /** Country endings a brand with {@link Brand#anyCountry} may use (google.de, yandex.kz). */
    private static final Set<String> COUNTRIES = new HashSet<>(Arrays.asList(
        "ru", "ua", "by", "kz", "uz", "kg", "am", "ge", "az", "md", "de", "fr", "it", "es", "pl", "nl", "be", "ch", "at", "se", "no", "fi",
        "dk", "cz", "sk", "pt", "gr", "tr", "il", "in", "jp", "cn", "kr", "br", "ar", "mx", "ca", "au", "nz", "ie", "ro", "hu", "bg", "rs",
        "ee", "lv", "lt", "si", "hr", "ae", "sa", "eg", "za", "sg", "hk", "tw", "th", "vn", "id", "my", "ph", "pk", "cl", "co", "pe",
        "co.uk", "com.au", "com.br", "com.tr", "co.jp", "co.in", "com.mx", "com.ar", "co.kr", "com.ua", "co.il", "co.nz", "co.za",
        "com.sg", "com.hk", "com.tw", "com.kz", "com.by", "com.cn", "com.pl", "com.vn", "co.id", "co.th", "eu"
    ));

    /** Endings mostly bought for throwaway sites (+20) and ones that only add to other signs (+10). */
    private static final Set<String> RISKY_TLDS = new HashSet<>(Arrays.asList(
        "xyz", "top", "click", "icu", "monster", "rest", "cfd", "sbs", "zip", "mov", "tk", "ml", "ga", "cf", "gq", "pw", "buzz", "cyou",
        "bond", "quest", "lol", "cam", "gdn", "country", "stream", "download", "racing", "win", "bid", "loan", "date", "faith", "men",
        "party", "review", "science", "trade", "accountant", "cricket", "kim", "work", "fit", "surf", "autos", "boats", "homes",
        "motorcycles", "yachts", "beauty", "hair", "skin", "makeup", "mom", "gift", "gifts", "bonus", "claims", "lat", "uno", "best"
    ));
    private static final Set<String> SOFT_RISKY_TLDS = new HashSet<>(Arrays.asList(
        "online", "site", "website", "space", "fun", "shop", "store", "live", "link", "life", "world", "today", "club", "vip", "biz",
        "cc", "ws", "su", "info", "support", "help", "host", "press", "digital", "network", "center", "app", "services", "one", "pro"
    ));

    /** Endings a text can show as a site without a scheme (README.md and Node.js are names, not sites). */
    private static final Set<String> WEB_TLDS = new HashSet<>(Arrays.asList(
        "com", "ru", "org", "net", "xn--p1ai", "su", "io", "me", "info", "by", "kz", "ua", "uk", "de", "fr", "eu", "us", "app", "dev", "tv",
        "gg", "co", "ai", "xyz", "top", "online", "site", "store", "shop", "pro", "biz", "link", "live", "club", "ws", "tech", "uz",
        "am", "ge", "tg", "ph", "fm", "to", "ly", "gl", "gov", "edu", "click", "icu", "cfd", "sbs", "help", "support", "website", "space", "fun"
    ));

    /** Words in an address that bait (combined with a brand they say "fake login page"). */
    private static final String[] LURE_WORDS = {
        "login", "signin", "logon", "verify", "verification", "verif", "secure", "security", "account", "auth", "support", "helpdesk",
        "premium", "gift", "gifts", "free", "bonus", "promo", "prize", "reward", "claim", "airdrop", "giveaway", "nft", "stars", "wallet",
        "unlock", "recovery", "restore", "confirm", "official", "billing", "payment", "payout", "refund", "vote", "voting", "golos",
        "golosovanie", "konkurs", "podarok", "besplatno", "unban", "appeal", "drop", "x2", "double", "invest", "lottery", "lotto", "win",
        "winner", "compensation", "kompensaciya", "vyplata", "vyplaty", "vyplat", "viplata", "trade", "skins", "case", "dostavka",
        "oplata", "sdelka", "bezopasnaya", "lk", "cabinet", "kabinet"
    };
    /** The same in Cyrillic domains. */
    private static final String[] LURE_WORDS_CYRILLIC = {
        "подарок", "подарки", "бесплатно", "выплата", "выплаты", "компенсация", "розыгрыш", "премиум", "вход", "голосование", "конкурс",
        "бонус", "приз", "доставка", "оплата"
    };

    /** Programs that install themselves, by risk. */
    private static final Map<String, Integer> FILES = new HashMap<>();

    static {
        for (String ext : new String[]{"apk", "xapk", "apks", "apkm", "exe", "msi", "scr", "bat", "cmd", "vbs", "ps1", "jar", "hta", "lnk", "pif"}) {
            FILES.put(ext, 40);
        }
        for (String ext : new String[]{"dmg", "pkg", "ipa", "deb", "appx", "msix", "iso", "img"}) {
            FILES.put(ext, 20);
        }
    }

    /** Official Telegram accounts and big official bots: their usernames sound like what scammers use. */
    private static final Set<String> OFFICIAL_USERNAMES = new HashSet<>(Arrays.asList(
        "telegram", "durov", "durov_russia", "botfather", "premium", "premiumbot", "spambot", "notoscam", "stickers", "gif", "vid", "pic",
        "bing", "wiki", "imdb", "bold", "youtube", "wallet", "cryptobot", "send", "tips", "telegramtips", "contest", "previews", "designers",
        "botsupport", "botnews", "bottalk", "gamee", "gamebot", "tondev", "toncoin", "ton", "username", "telegramru", "tginfo",
        "jobsbot", "stars", "storiesbot", "verifybot", "telegramarabic", "telegramfrance", "telegrames", "telegramit", "telegramde",
        "tgbetachat", "tgbeta", "durovschat", "fragment", "giftcode", "asmico", "webappbot", "quizbot", "pollbot", "livebot", "like",
        "vote", "music", "foursquare", "telegraph", "gmailbot", "ifttt", "githubbot", "dropboxbot", "tonkeeper", "tonkeeper_bot",
        "tonkeeper_news", "tonblockchain", "toncoin_rus", "wallet_news", "cryptobotrussian", "telegramdesktop", "android_beta", "tgandroid",
        "ios_beta", "ru_telegram", "telegram_ru", "username_bot", "premiumbot", "storiesbot", "dropsbot", "giftsbot", "giftchanges"
    ));

    /** t.me paths that are not usernames: invites, sticker sets, previews, proxies... */
    private static final Set<String> TELEGRAM_SERVICE_PATHS = new HashSet<>(Arrays.asList(
        "joinchat", "addstickers", "addemoji", "addlist", "addtheme", "setlanguage", "proxy", "socks", "share", "iv", "c", "s",
        "login", "confirmphone", "bg", "invoice", "contact", "boost", "giftcode", "nft", "m", "premium_offer", "stars_topup",
        "stars", "phone", "story", "a", "k", "z", "web"
    ));

    /** Words of a username that promise something or pose as someone. */
    private static final String[] TG_STRONG_WORDS = {
        "official", "unban", "verify", "verification", "recovery", "airdrop", "giveaway", "podarok", "podarki", "freestars",
        "freepremium", "free_stars", "free_premium", "razdacha", "claim", "vyplat", "kompensac", "unfreeze", "razblok"
    };
    /** What scammers promise, but also what honest gift and NFT markets are named after: little alone. */
    private static final String[] TG_TOPIC_WORDS = {"premium", "gift", "stars", "star", "nft"};
    /** Words businesses use too (shop_support): they count only next to a brand or other signs. */
    private static final String[] TG_MEDIUM_WORDS = {
        "support", "admin", "administrator", "moderator", "security", "secure", "help", "helpdesk", "service", "staff", "team",
        "notification", "notify", "bonus", "reward", "prize", "payout", "withdraw", "refund", "appeal", "restore", "podderzhka",
        "poddershka", "sluzhba"
    };
    /** Telegram itself: a username with these and a staff word poses as Telegram. */
    private static final String[] TG_TELEGRAM_WORDS = {"telegram", "telegramm", "telegrarn", "teleqram", "durov", "fragment", "tlgrm"};
    /** Other brands scammers pose as. */
    private static final String[] TG_BRAND_WORDS = {
        "tonkeeper", "wallet", "sber", "sberbank", "tinkoff", "tbank", "gosuslugi", "binance", "bybit", "steam", "wildberries", "ozon",
        "avito", "vtb", "alfabank", "yandex", "metamask", "trustwallet", "okx", "cdek", "youla"
    };

    // endregion

    private SolegramScamRules() {
    }

    // region Safe domains

    /** Where the list of well known sites comes from (an asset in the app, a file in tests). */
    public interface Source {
        InputStream open() throws IOException;
    }

    /** A few sites that must stay trusted even when the list could not be read. */
    private static final Set<String> TRUSTED_CORE = new HashSet<>(Arrays.asList(
        "wikipedia.org", "telegram.org", "t.me", "gosuslugi.ru", "mos.ru", "github.com", "stackoverflow.com", "habr.com", "ya.ru"
    ));

    private static volatile Source safeSource;
    private static volatile Set<String> safeDomains;
    private static final Object safeLock = new Object();

    /** Sets where the list is read from; it is read once, the first time a link is checked. */
    public static void setSafeDomainSource(Source source) {
        synchronized (safeLock) {
            safeSource = source;
            safeDomains = null;
        }
    }

    private static Set<String> safeDomains() {
        Set<String> set = safeDomains;
        if (set != null) {
            return set;
        }
        synchronized (safeLock) {
            if (safeDomains == null) {
                final HashSet<String> loaded = new HashSet<>(1024);
                final Source source = safeSource;
                if (source != null) {
                    try (InputStream in = source.open(); BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty() || line.startsWith("#")) {
                                continue;
                            }
                            final boolean zone = line.startsWith(".");
                            final String ascii = toAscii(zone ? line.substring(1) : line);
                            loaded.add(zone ? "." + ascii : ascii);
                        }
                    } catch (Throwable ignore) {
                        // the brands and the core list still work
                    }
                }
                safeDomains = loaded;
            }
            return safeDomains;
        }
    }

    /** The user's own list of sites to trust (as {@link LinkReport#site} or a host). Set by the app. */
    private static volatile Set<String> userTrusted = new HashSet<>();
    /** Telegram usernames the user said are fine (lowercase, no @). */
    private static volatile Set<String> userTrustedUsernames = new HashSet<>();

    public static void setUserTrusted(Set<String> sites) {
        userTrusted = sites == null ? new HashSet<>() : sites;
    }

    public static void setUserTrustedUsernames(Set<String> usernames) {
        userTrustedUsernames = usernames == null ? new HashSet<>() : usernames;
    }

    public static boolean isTrustedSite(String site) {
        return site != null && (TRUSTED_CORE.contains(site) || userTrusted.contains(site) || safeDomains().contains(site));
    }

    /** A host of a well known site: by its registrable domain, the host itself or a parent, or a trusted zone (.gov.ru). */
    static boolean isTrustedHost(String host, String site) {
        if (isTrustedSite(site) || host == null) {
            return site != null && isTrustedSite(site);
        }
        final Set<String> safe = safeDomains();
        final Set<String> user = userTrusted;
        String h = host;
        while (true) {
            if (safe.contains(h) || user.contains(h) || safe.contains("." + h)) {
                return true;
            }
            final int dot = h.indexOf('.');
            if (dot < 0) {
                return false;
            }
            h = h.substring(dot + 1);
        }
    }

    // endregion

    // region Links

    /**
     * Scores one link by its address and, when known, the text it is shown as.
     *
     * @param url   the link's address (with or without the scheme)
     * @param shown the text of the link in the message (for text links), or null
     */
    public static LinkReport scoreLink(String url, String shown) {
        final LinkReport r = new LinkReport(url == null ? "" : url);
        if (url == null) {
            return r;
        }
        String u = stripInvisible(url).trim();
        final String lower = u.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:") || lower.startsWith("vbscript:")) {
            r.add(R_SCRIPT, null, 100);
            return r;
        }
        if (lower.startsWith("data:")) {
            r.add(R_DATA, null, 90);
            return r;
        }
        if (lower.startsWith("tg:") || lower.startsWith("tg://")) {
            // tg://resolve?domain=name is a username link like t.me/name
            final Matcher m = TG_DOMAIN.matcher(u);
            if (lower.startsWith("tg://resolve") && m.find()) {
                r.host = "t.me";
                r.displayHost = "t.me";
                r.site = "t.me";
                r.trusted = true;
                scoreTelegramUsername(r, m.group(1).toLowerCase(Locale.ROOT));
            } else {
                r.telegramService = true;
            }
            return r;
        }
        if (u.startsWith("@") && u.length() > 1) {
            u = "https://t.me/" + u.substring(1);
        }
        final int schemeEnd = u.indexOf("://");
        final String scheme = schemeEnd > 0 ? u.substring(0, schemeEnd).toLowerCase(Locale.ROOT) : "";
        if (schemeEnd > 0 && !scheme.equals("http") && !scheme.equals("https")) {
            return r; // mailto:, tel:, tonsite://... are not web pages
        }
        final String rest = schemeEnd > 0 ? u.substring(schemeEnd + 3) : u;
        int authEnd = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            final char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == '\\') {
                authEnd = i;
                break;
            }
        }
        String authority = rest.substring(0, authEnd);
        final String pathAndQuery = rest.substring(authEnd);
        final int at = authority.lastIndexOf('@');
        if (at >= 0) {
            final String userInfo = authority.substring(0, at);
            authority = authority.substring(at + 1);
            // "https://telegram.org@evil.top": what is read first is not where the link goes
            if (userInfo.contains(".")) {
                r.add(R_USERINFO, userInfo, 55, KIND_HARD);
            } else {
                r.add(R_USERINFO, userInfo, 30, KIND_MEDIUM); // user:password@ of a developer's link
            }
        }
        String host = authority;
        if (host.startsWith("[")) {
            r.host = host.toLowerCase(Locale.ROOT);
            r.displayHost = r.host;
            r.site = r.host;
            r.add(R_IP, r.host, 35);
            return r;
        }
        final int colon = host.lastIndexOf(':');
        if (colon >= 0) {
            final String port = host.substring(colon + 1);
            host = host.substring(0, colon);
            if (!port.isEmpty() && !port.equals("80") && !port.equals("443")) {
                r.add(R_PORT, port, 10, KIND_WEAK);
            }
        }
        host = host.replace('。', '.').replace('．', '.').replace('｡', '.');
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty()) {
            return r;
        }
        final String ascii = toAscii(host);
        String unicode;
        try {
            unicode = IDN.toUnicode(ascii, IDN.ALLOW_UNASSIGNED);
        } catch (Exception e) {
            unicode = host.toLowerCase(Locale.ROOT);
        }
        r.host = ascii;
        r.displayHost = unicode;

        if (isIpAddress(ascii)) {
            r.site = ascii;
            if (isPrivateIp(ascii)) {
                r.add(R_IP, ascii, 10, KIND_WEAK); // the home router
            } else {
                r.add(R_IP, ascii, 35, KIND_MEDIUM);
            }
            addFile(r, pathAndQuery, false);
            return r;
        }

        final String[] labels = ascii.split("\\.");
        final int siteLabels = siteLabelCount(labels);
        final String site = joinLast(labels, siteLabels);
        r.site = site;
        final String suffix = labels.length >= 2 ? site.substring(site.indexOf('.') + 1) : "";
        final String tld = labels[labels.length - 1];
        final String hosting = FREE_HOSTING.contains(suffix) ? suffix : null;
        r.trusted = brandOwned(site) || isTrustedHost(ascii, site);
        r.userContent = r.trusted && (USER_CONTENT.contains(ascii) || USER_CONTENT.contains(site));

        // Telegram's own links: the username is what can deceive
        if (site.equals("t.me") || site.equals("telegram.me") || site.equals("telegram.dog")) {
            scoreTelegramPath(r, pathAndQuery);
            textMismatch(r, shown, pathAndQuery);
            return r;
        }

        if (r.trusted) {
            // a redirect of a big site (google.com/url?q=...) goes where its parameter says
            final String target = redirectTarget(ascii, pathAndQuery);
            if (target != null) {
                final LinkReport inner = scoreLink(target, null);
                for (Reason reason : inner.reasons) {
                    r.add(reason.code, reason.arg, reason.weight, reason.kind);
                }
                if (!inner.trusted) {
                    r.trusted = false; // the page it leads to is someone else's
                    r.site = inner.site;
                }
            }
            addFile(r, pathAndQuery, true);
            textMismatch(r, shown, pathAndQuery);
            return r;
        }

        if (SHORTENERS.contains(site) || SHORTENERS.contains(ascii)) {
            r.shortener = true;
            r.add(R_SHORTENER, site, 25, KIND_WEAK);
        } else if (AFFILIATE.contains(site) || AFFILIATE.contains(ascii)) {
            r.shortener = true;
            r.add(R_SHORTENER, site, 15, KIND_WEAK);
        }
        if (hosting != null) {
            r.add(R_FREE_HOSTING, hosting, 15, KIND_WEAK);
        }
        final int riskyWeight = RISKY_TLDS.contains(tld) ? 20 : SOFT_RISKY_TLDS.contains(tld) ? 10 : 0;
        if (riskyWeight > 0) {
            r.add(R_RISKY_TLD, "." + tld, riskyWeight, KIND_WEAK);
        }
        final String mainLabel = labels.length >= siteLabels ? labels[labels.length - siteLabels] : labels[0];
        final int mainStart = unicodeLabelStart(unicode, labels.length - siteLabels);
        final String[] unicodeLabels = unicode.split("\\.");
        final String mainUnicode = labels.length == unicodeLabels.length ? unicodeLabels[labels.length - siteLabels] : mainLabel;

        // letters of other alphabets dressed as Latin ones
        boolean brandSignal = false;
        for (int i = 0; i < unicodeLabels.length; i++) {
            final String label = unicodeLabels[i];
            if (isAscii(label)) {
                continue;
            }
            final int scripts = scriptMask(label);
            final String skeleton = skeleton(label);
            Brand homograph = null;
            if (isAscii(skeleton)) {
                final String c = canon(skeleton);
                for (int k = 0; k < BRANDS.length; k++) {
                    if (c.equals(BRAND_CANON[k]) || c.equals(BRAND_SITE_CANON[k])) {
                        homograph = BRANDS[k];
                        break;
                    }
                }
            }
            if (homograph != null) {
                r.add(R_HOMOGRAPH, homograph.display, 80);
                brandSignal = true;
                markNonAscii(r, unicode, i);
            } else if (Integer.bitCount(scripts & ~SCRIPT_OTHER) > 1) {
                r.add(R_MIXED_SCRIPT, label, 60);
                markNonAscii(r, unicode, i);
            } else if (isAscii(skeleton) && (scripts & SCRIPT_LATIN) == 0 && label.length() >= 3 && isAscii(unicodeLabels[unicodeLabels.length - 1])
                && !CYRILLIC_ZONES.contains(unicodeLabels[unicodeLabels.length - 1])) {
                // a word written only with look-alike letters (а, е, о, р, с...) on a Latin ending reads as Latin: a disguise
                r.add(R_MIXED_SCRIPT, label, 45);
                markNonAscii(r, unicode, i);
            }
        }

        // a brand's name, a letter off it, or with someone else's ending
        if (!brandSignal) {
            brandSignal = isAscii(mainUnicode)
                ? scoreBrandLabel(r, mainLabel, mainUnicode, mainStart, site, unicode)
                : scoreCyrillicBrand(r, mainUnicode, mainStart, site, unicode);
        }
        // a brand's name in the subdomains of another site
        for (int i = 0; i < labels.length - siteLabels && !brandSignal; i++) {
            if (labels[i].equals("www") || labels[i].equals("m")) {
                continue;
            }
            for (String token : tokens(labels[i])) {
                final Brand b = brandForToken(token, true);
                if (b != null && !b.generic) {
                    r.add(R_BRAND_SUBDOMAIN, b.label, 25, KIND_MEDIUM);
                    markSite(r, unicode, site);
                    brandSignal = true;
                    break;
                }
            }
        }
        // bait words anywhere in the host
        String lure = null;
        for (String token : tokens(ascii)) {
            for (String w : LURE_WORDS) {
                if (token.equals(w) || token.length() >= 6 && w.length() >= 5 && token.contains(w)) {
                    lure = w;
                    break;
                }
            }
            if (lure != null) {
                break;
            }
        }
        if (lure == null && !isAscii(unicode)) {
            for (String token : tokens(unicode)) {
                for (String w : LURE_WORDS_CYRILLIC) {
                    if (token.startsWith(w)) {
                        lure = w;
                        break;
                    }
                }
                if (lure != null) {
                    break;
                }
            }
        }
        if (lure == null && brandSignal) {
            // web-telegram.ru/login: next to a brand's name a login or gift page in the path says the same
            final Matcher m = PATH_LURE.matcher(pathAndQuery.toLowerCase(Locale.ROOT));
            if (m.find()) {
                lure = m.group(1);
            }
        }
        if (lure != null) {
            // with a brand it says "fake login page"; alone it is a word honest sites use too
            if (brandSignal) {
                r.add(R_LURE_WORDS, lure, 25, KIND_MEDIUM);
            } else {
                r.add(R_LURE_WORDS, lure, 15, KIND_WEAK);
            }
        }
        int subdomains = labels.length - siteLabels;
        if (subdomains > 0 && labels[0].equals("www")) {
            subdomains--;
        }
        if (subdomains >= 4 || ascii.length() > 60) {
            r.add(R_DEEP_SUBDOMAINS, null, 15, KIND_WEAK);
        }
        addFile(r, pathAndQuery, false);
        textMismatch(r, shown, pathAndQuery);
        return r;
    }

    private static final Pattern PATH_LURE = Pattern.compile("^/+(?:[a-z0-9_-]+/)?(login|signin|sign-in|auth|verify|verification|claim|gift|gifts|premium|stars|wallet|connect|restore|recovery|unlock|bonus|prize|vyplata|vyplaty|kompensaciya)(?:[/?#.\\-_]|$)");

    private static final Pattern TG_DOMAIN = Pattern.compile("[?&]domain=([a-zA-Z0-9_]{3,64})");

    /** Endings in Cyrillic: names in them are written in Cyrillic on purpose (госуслуги.рф, президент.рф). */
    private static final Set<String> CYRILLIC_ZONES = new HashSet<>(Arrays.asList("рф", "рус", "москва", "дети", "онлайн", "сайт", "орг", "ком", "бел", "укр", "қаз", "срб", "мкд"));

    /** Scores the brand-like parts of the site's own name. @return whether a brand was found */
    private static boolean scoreBrandLabel(LinkReport r, String label, String unicodeLabel, int labelStart, String site, String unicodeHost) {
        if (label.startsWith("xn--")) {
            return false; // judged by its letters above
        }
        final String suffix = site.substring(site.indexOf('.') + 1);
        final String canonLabel = canon(label);
        // the whole name
        for (Brand b : BRANDS) {
            if (label.equals(b.label)) {
                if (b.anyCountry && COUNTRIES.contains(suffix)) {
                    return false; // google.de
                }
                if (b.generic) {
                    r.add(R_BRAND_TLD, b.display, 25, KIND_MEDIUM); // steam.games, apple.ru: often someone else's
                } else {
                    r.add(R_BRAND_TLD, b.display, 60, KIND_HARD);
                }
                markSite(r, unicodeHost, site);
                return !b.generic || r.has(R_RISKY_TLD);
            }
        }
        for (int k = 0; k < BRANDS.length; k++) {
            final Brand b = BRANDS[k];
            if (canonLabel.equals(BRAND_CANON[k]) && b.label.length() >= 3 && !contains(b.exclude, label)) {
                r.add(R_LOOKALIKE, b.display, 70, KIND_HARD); // digits and letters swapped for look-alikes: t3legram, rnetamask
                markDiff(r, label, b.label, labelStart);
                return true;
            }
        }
        // a word of the name written with look-alikes: faceb00k-login, steamc0mmunity-trade
        final String[] hyphenWords = label.split("-");
        if (hyphenWords.length > 1) {
            for (String word : hyphenWords) {
                final String c = canon(word);
                for (int k = 0; k < BRANDS.length; k++) {
                    if (BRANDS[k].label.length() >= 5 && c.equals(BRAND_CANON[k]) && !word.equals(BRANDS[k].label)) {
                        r.add(R_LOOKALIKE, BRANDS[k].display, 65, KIND_HARD);
                        markRange(r, labelStart, labelStart + unicodeLabel.length());
                        return true;
                    }
                }
            }
        }
        // one or two letters off (insert, drop, swap, replace)
        final String folded = fold(label);
        for (Brand b : BRANDS) {
            if (b.generic || b.label.length() < 6 || contains(b.exclude, label) || contains(b.exclude, folded)) {
                continue;
            }
            final int distance = distance(folded, b.label);
            if (distance == 1) {
                // a dropped, doubled or swapped letter, or one that looks the same, is a disguise; another letter
                // (megaton, telegraf, twister) is mostly a different word
                if (visualEdit(folded, b.label)) {
                    r.add(R_LOOKALIKE, b.display, 60, KIND_HARD);
                } else {
                    r.add(R_LOOKALIKE, b.display, 25, KIND_MEDIUM);
                }
                markDiff(r, label, b.label, labelStart);
                return true;
            }
            if (distance == 2 && b.label.length() >= 9) {
                r.add(R_LOOKALIKE, b.display, 25, KIND_MEDIUM);
                markDiff(r, label, b.label, labelStart);
                return true;
            }
        }
        // a short brand with a doubled or look-alike letter: ozzon, avlto (a dropped letter would hit too many words)
        for (Brand b : BRANDS) {
            if (b.generic || b.label.length() < 4 || b.label.length() >= 6 || contains(b.exclude, label)) {
                continue;
            }
            if (folded.length() >= b.label.length() && distance(folded, b.label) == 1 && visualEdit(folded, b.label)) {
                r.add(R_LOOKALIKE, b.display, 55, KIND_HARD);
                markDiff(r, label, b.label, labelStart);
                return true;
            }
        }
        // the brand as a word of a longer name: telegram-premium, sberbank24-online, steam-gift
        final List<String> tokens = tokens(label);
        for (String token : tokens) {
            final Brand b = brandForToken(token, true);
            if (b != null) {
                if (b.generic) {
                    r.add(R_BRAND_IN_DOMAIN, b.label, 20, KIND_WEAK); // apple-pie-recipes, steam-cleaning
                } else {
                    r.add(R_BRAND_IN_DOMAIN, b.label, 25, KIND_MEDIUM);
                }
                markRange(r, labelStart, labelStart + unicodeLabel.length());
                return !b.generic;
            }
        }
        // or glued into it: telegrampremium, mysberbank
        for (Brand b : BRANDS) {
            if (b.generic || b.label.length() < 7) {
                continue;
            }
            if (label.contains(b.label) || folded.contains(b.label)) {
                r.add(R_BRAND_IN_DOMAIN, b.label, 25, KIND_MEDIUM);
                markRange(r, labelStart, labelStart + unicodeLabel.length());
                return true;
            }
        }
        return false;
    }

    /** A brand written in Cyrillic: госуслуги.online, сбербанк-онлайн.рф. */
    private static boolean scoreCyrillicBrand(LinkReport r, String unicodeLabel, int labelStart, String site, String unicodeHost) {
        final String exact = CYRILLIC_BRANDS.get(unicodeLabel);
        if (exact != null) {
            final Brand b = brandByLabel(exact);
            if (b != null && !b.owns(site)) {
                r.add(R_BRAND_TLD, b.display, b.generic ? 25 : 60, b.generic ? KIND_MEDIUM : KIND_HARD);
                markSite(r, unicodeHost, site);
                return !b.generic;
            }
            return false;
        }
        for (String token : unicodeLabel.split("-")) {
            final String brand = CYRILLIC_BRANDS.get(token);
            if (brand != null && token.length() >= 3) {
                final Brand b = brandByLabel(brand);
                if (b != null) {
                    r.add(R_BRAND_IN_DOMAIN, b.label, b.generic ? 20 : 25, b.generic ? KIND_WEAK : KIND_MEDIUM);
                    markRange(r, labelStart, labelStart + unicodeLabel.length());
                    return !b.generic;
                }
            }
        }
        return false;
    }

    private static Brand brandByLabel(String label) {
        for (Brand b : BRANDS) {
            if (b.label.equals(label)) {
                return b;
            }
        }
        return null;
    }

    /** The brand a word of an address names (exactly, or written with digits for letters). */
    private static Brand brandForToken(String token, boolean allowCanon) {
        if (token.length() < 2) {
            return null;
        }
        for (Brand b : BRANDS) {
            if (token.equals(b.label)) {
                return b;
            }
        }
        if (allowCanon && token.length() >= 4) {
            final String c = canon(token);
            for (int k = 0; k < BRANDS.length; k++) {
                if (BRANDS[k].label.length() >= 4 && c.equals(BRAND_CANON[k]) && !contains(BRANDS[k].exclude, token)) {
                    return BRANDS[k];
                }
            }
            // a letter off a long brand name, as a word: telegrarn-premium
            final String folded = fold(token);
            for (Brand b : BRANDS) {
                if (b.label.length() >= 7 && !b.generic && !contains(b.exclude, token) && distance(folded, b.label) == 1 && visualEdit(folded, b.label)) {
                    return b;
                }
            }
        }
        return null;
    }

    private static boolean brandOwned(String site) {
        for (Brand b : BRANDS) {
            if (b.owns(site)) {
                return true;
            }
        }
        return false;
    }

    private static void scoreTelegramPath(LinkReport r, String pathAndQuery) {
        String path = pathAndQuery;
        final int q = path.indexOf('?');
        final int h = path.indexOf('#');
        int end = path.length();
        if (q >= 0) {
            end = Math.min(end, q);
        }
        if (h >= 0) {
            end = Math.min(end, h);
        }
        path = path.substring(0, end);
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        r.trusted = true;
        if (path.isEmpty()) {
            return;
        }
        final String[] parts = path.split("/");
        String first = parts[0];
        if (first.startsWith("@")) {
            first = first.substring(1);
        }
        if (first.startsWith("+") || first.startsWith("$") || TELEGRAM_SERVICE_PATHS.contains(first.toLowerCase(Locale.ROOT))) {
            r.telegramService = true;
            if (first.equalsIgnoreCase("s") && parts.length > 1) {
                scoreTelegramUsername(r, parts[1].toLowerCase(Locale.ROOT));
            }
            return;
        }
        scoreTelegramUsername(r, first.toLowerCase(Locale.ROOT));
    }

    private static void scoreTelegramUsername(LinkReport r, String username) {
        if (!username.matches("[a-z0-9_]{3,64}")) {
            return;
        }
        r.telegramUsername = username;
        if (isTrustedUsername(username)) {
            return;
        }
        final int weight = usernameRisk(username);
        if (weight > 0) {
            r.add(R_TG_IMPERSONATION, "@" + username, weight, KIND_MEDIUM);
        }
    }

    /** An official account, or one the user marked as fine. */
    public static boolean isTrustedUsername(String username) {
        final String u = username.toLowerCase(Locale.ROOT);
        return OFFICIAL_USERNAMES.contains(u) || userTrustedUsernames.contains(u);
    }

    /** How much a Telegram username poses as staff, an official account or a giveaway (0 = not at all). */
    public static int usernameRisk(String username) {
        final String u = username.toLowerCase(Locale.ROOT);
        if (isTrustedUsername(u)) {
            return 0;
        }
        final String c = canon(u);
        boolean telegram = false, brand = false;
        for (String w : TG_TELEGRAM_WORDS) {
            if (u.contains(w) || c.contains(canon(w))) {
                telegram = true;
                break;
            }
        }
        // "tg" counts only as a word of its own: tg_support, tgsupport, support_tg
        if (!telegram && (u.startsWith("tg_") || u.endsWith("_tg") || u.contains("_tg_") || u.startsWith("tg") && containsAny(u.substring(2), TG_STRONG_WORDS, TG_MEDIUM_WORDS))) {
            telegram = true;
        }
        for (String w : TG_BRAND_WORDS) {
            if (u.contains(w)) {
                brand = true;
                break;
            }
        }
        final boolean strong = containsAny(u, TG_STRONG_WORDS);
        final boolean staff = containsAny(u, TG_MEDIUM_WORDS);
        int topics = 0;
        for (String w : TG_TOPIC_WORDS) {
            if (u.contains(w) && !(w.equals("star") && u.contains("stars"))) {
                topics++;
            }
        }
        int weight;
        if (telegram && (strong || staff)) {
            weight = 60;
        } else if (telegram && topics > 0) {
            weight = 50; // telegram_premium, telegramgifts: Telegram's own things, but not Telegram's account
        } else if (brand && (strong || staff)) {
            weight = 45;
        } else if (strong && topics > 0) {
            weight = 35;
        } else if (strong) {
            weight = 25;
        } else if (brand && topics > 0) {
            weight = 25;
        } else if (telegram) {
            weight = 20; // named after Telegram or its founder: fan channels too
        } else if (topics > 0) {
            weight = topics >= 2 ? 15 : 10; // nft_gifts_market: honest markets have such names
        } else if (staff) {
            weight = 10;
        } else {
            weight = 0;
        }
        if (weight >= 25 && u.endsWith("bot")) {
            weight += 10;
        }
        return weight;
    }

    private static boolean containsAny(String s, String[]... lists) {
        for (String[] list : lists) {
            for (String w : list) {
                if (s.contains(w)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The shown text names a site or @username the link does not go to. */
    private static void textMismatch(LinkReport r, String shown, String pathAndQuery) {
        if (shown == null || r.host == null) {
            return;
        }
        String s = stripInvisible(shown).trim();
        if (s.isEmpty() || s.length() > 200) {
            return;
        }
        if (s.startsWith("@") && s.length() > 1 && s.substring(1).matches("[A-Za-z0-9_]{3,64}")) {
            final String shownName = s.substring(1).toLowerCase(Locale.ROOT);
            final boolean official = OFFICIAL_USERNAMES.contains(shownName);
            if (r.telegramUsername != null) {
                if (!r.telegramUsername.equals(shownName)) {
                    r.add(R_TEXT_MISMATCH, s, official ? 50 : 30, official ? KIND_HARD : KIND_MEDIUM);
                }
            } else if (!r.telegramService && !pathAndQuery.toLowerCase(Locale.ROOT).contains(shownName)) {
                // @name over instagram.com/name is the same person; over another site it is not
                if (official) {
                    r.add(R_TEXT_MISMATCH, s, 50, KIND_HARD);
                } else if (!r.trusted) {
                    r.add(R_TEXT_MISMATCH, s, 25, KIND_MEDIUM);
                }
            }
            return;
        }
        if (s.contains(" ") || !s.contains(".")) {
            return; // "Read more", "Подробнее": words, not an address
        }
        final Matcher m = SHOWN_HOST.matcher(s);
        if (!m.matches()) {
            return;
        }
        final String lowerShown = s.toLowerCase(Locale.ROOT);
        final boolean looksLikeUrl = lowerShown.startsWith("http") || lowerShown.startsWith("www.") || s.indexOf('/') > 0;
        final String shownHost = toAscii(m.group(1));
        final String[] labels = shownHost.split("\\.");
        final String shownTld = labels[labels.length - 1];
        if (labels.length < 2 || !shownTld.matches("[a-z]{2,24}|xn--[a-z0-9-]+")) {
            return;
        }
        if (!looksLikeUrl && !WEB_TLDS.contains(shownTld)) {
            return; // README.md, main.py, Vue.js
        }
        final String shownSite = joinLast(labels, siteLabelCount(labels));
        if (shownSite.equals(r.site) || sameOwner(shownSite, r.site) || shownHost.equals(r.host)
            || r.host.endsWith("." + shownSite) || shownHost.endsWith("." + r.site)) {
            return;
        }
        // the same name with another ending (mysite.ru and mysite.com) is the same owner more often than not;
        // a brand's name on someone else's ending is caught by the brand rules anyway
        if (siteLabel(shownSite).equals(siteLabel(r.site))) {
            return;
        }
        final boolean shownIsBig = isTrustedSite(shownSite) || brandOwned(shownSite);
        if (r.trusted) {
            if (shownIsBig) {
                r.add(R_TEXT_MISMATCH, shownSite, 15, KIND_MEDIUM); // a big site, but not the one named
            }
        } else if (r.shortener) {
            if (shownIsBig && !AFFILIATE.contains(r.site)) {
                r.add(R_TEXT_MISMATCH, shownSite, 35, KIND_MEDIUM); // tinkoff.ru shown, bit.ly behind it
            }
        } else if (shownIsBig) {
            r.add(R_TEXT_MISMATCH, shownSite, 60, KIND_HARD); // sberbank.ru shown, evil.top behind it
        } else {
            r.add(R_TEXT_MISMATCH, shownSite, 30, KIND_MEDIUM);
        }
    }

    private static final Pattern SHOWN_HOST = Pattern.compile("(?i)(?:https?://)?(?:www\\.)?([\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+)\\.?(?:[:/?#].*)?");

    /** Two sites of one owner (a brand's domains, youtu.be and youtube.com). */
    private static boolean sameOwner(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        for (Brand brand : BRANDS) {
            if (brand.owns(a) && brand.owns(b)) {
                return true;
            }
        }
        return false;
    }

    private static String redirectTarget(String host, String pathAndQuery) {
        final String lower = pathAndQuery.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("/url?") || lower.startsWith("/away.php") || lower.startsWith("/redirect") || lower.startsWith("/l.php")
            || lower.startsWith("/away?") || lower.startsWith("/link?"))) {
            return null;
        }
        final Matcher m = REDIRECT_PARAM.matcher(pathAndQuery);
        if (!m.find()) {
            return null;
        }
        final String value = percentDecode(m.group(1));
        return value.toLowerCase(Locale.ROOT).startsWith("http") ? value : null;
    }

    private static final Pattern REDIRECT_PARAM = Pattern.compile("[?&](?:q|url|u|to|target|dest|link)=([^&#]+)");

    private static void addFile(LinkReport r, String pathAndQuery, boolean trusted) {
        String path = pathAndQuery;
        final int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        final int h = path.indexOf('#');
        if (h >= 0) {
            path = path.substring(0, h);
        }
        final int dot = path.lastIndexOf('.');
        final int slash = path.lastIndexOf('/');
        if (dot < 0 || dot < slash) {
            return;
        }
        final String ext = percentDecode(path.substring(dot + 1)).toLowerCase(Locale.ROOT);
        final Integer weight = FILES.get(ext);
        if (weight != null) {
            if (trusted) {
                r.add(R_FILE, "." + ext, 10, KIND_WEAK); // a release on GitHub, an app on 4PDA
            } else {
                r.add(R_FILE, "." + ext, weight, KIND_MEDIUM);
            }
        }
    }

    /** The site part of a host (the registrable domain): what the "trust this site" list keeps. */
    public static String siteOf(String host) {
        if (host == null) {
            return null;
        }
        final String h = toAscii(host);
        if (isIpAddress(h)) {
            return h;
        }
        final String[] labels = h.split("\\.");
        return joinLast(labels, siteLabelCount(labels));
    }

    private static int siteLabelCount(String[] labels) {
        if (labels.length < 2) {
            return labels.length;
        }
        final String last2 = labels[labels.length - 2] + "." + labels[labels.length - 1];
        if (labels.length >= 4) {
            final String last3 = labels[labels.length - 3] + "." + last2;
            if (FREE_HOSTING.contains(last3) || MULTI_SUFFIXES.contains(last3)) {
                return 4;
            }
        }
        if (labels.length >= 3 && (MULTI_SUFFIXES.contains(last2) || FREE_HOSTING.contains(last2))) {
            return 3;
        }
        return 2;
    }

    private static String joinLast(String[] labels, int count) {
        final StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, labels.length - count); i < labels.length; i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(labels[i]);
        }
        return sb.toString();
    }

    static String toAscii(String host) {
        try {
            return IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return host.toLowerCase(Locale.ROOT);
        }
    }

    // endregion

    // region Scoring in context

    /** Whether the message's words count for this link (a big site stays a big site whatever the text says). */
    private static boolean textCounts(LinkReport link) {
        if (link.telegramUsername != null) {
            return !isTrustedUsername(link.telegramUsername);
        }
        if (link.telegramService) {
            return true; // an invite to a chat with "free Premium" around it
        }
        return !link.trusted || link.userContent;
    }

    /**
     * The score of a link in its message: hard signs as they are; the others only in combination (a cheap ending
     * counts next to a brand's name, bait in the text or a stranger), and discounted by who sent it.
     */
    public static int score(LinkReport link, TextReport text, Context ctx) {
        if (ctx == null) {
            ctx = Context.NONE;
        }
        if (ctx.outgoing || link == null) {
            return 0;
        }
        int hard = 0, medium = 0, weak = 0, weakCount = 0;
        for (Reason r : link.reasons) {
            if (r.kind == KIND_HARD) {
                hard += r.weight;
            } else if (r.kind == KIND_MEDIUM) {
                medium += r.weight;
            } else {
                weak += r.weight;
                weakCount++;
            }
        }
        final int t = text != null && textCounts(link) ? text.score : 0;
        final boolean corroborated = hard > 0 || medium > 0 || t > 0 || ctx.stranger || weakCount >= 2;
        // bait from someone the user does not know adds to itself
        final int bonus = (ctx.stranger || ctx.unknownSender) && t > 0 ? 10 : 0;
        final int soft = medium + (corroborated ? weak : 0) + t + bonus;
        final float f = ctx.trust(t > 0 && medium + weak > 0);
        return hard + Math.round(soft * f);
    }

    /** {@link #score(LinkReport, TextReport, Context)} with nothing known about the sender. */
    public static int combinedScore(LinkReport link, TextReport text) {
        return score(link, text, Context.NONE);
    }

    /** Whether the message's signs were part of the score (to list them with the link's). */
    public static boolean textCounted(LinkReport link, TextReport text, Context ctx) {
        return text != null && text.score > 0 && (ctx == null || !ctx.outgoing) && textCounts(link) && (ctx == null || !ctx.official);
    }

    // endregion

    // region Message text

    private static final String W = "[\\p{L}\\p{N}_]";
    /** The end of a word (Java's \b knows only Latin letters). */
    private static final String E = "(?![\\p{L}\\p{N}])";

    private static Pattern p(String regex) {
        return Pattern.compile(regex.replace("\\w", W).replace("\\E", E));
    }

    private static final String TG_PREMIUM = "(telegram premium|телеграм\\w* премиум|тг премиум|tg premium|премиум[- ]?подписк\\w*|premium subscription|"
        + "(premium|премиум) (на|for) [0-9]+|stars|звезд\\w* (телеграм\\w*|telegram|тг)|телеграм\\w* звезд\\w*|telegram stars|звезд(ы|у|ами)?\\E|nft|нфт)";

    private static final Object[][] TEXT_RULES = {
        {R_TEXT_GIFT, 20, p("(бесплатн\\w*|даром|в подарок|дарим|разда(ча|ем|ют|ет|чу)\\w*|розыгрыш\\w*|халяв\\w*|получи(те)?\\E|забери(те)?\\E|активиру\\w*|free|giveaway|claim)[^.!?\\n]{0,40}" + TG_PREMIUM),
            new String[]{"premium", "премиум", "stars", "звезд", "nft", "нфт"}},
        {R_TEXT_GIFT, 20, p("(telegram premium|телеграм\\w* премиум|premium|премиум|stars|звезд\\w*|nft|нфт)[^.!?\\n]{0,40}(бесплатн\\w*|даром|в подарок|free|giveaway|за подписку|всем подписчикам)(?=[\\s\\S]*(telegram|телеграм|тг\\E|stars|звезд|nft|нфт|premium|премиум))"),
            new String[]{"premium", "премиум", "stars", "звезд", "nft", "нфт"}},
        {R_TEXT_GIFT, 20, p("(бесплатн\\w*|даром|в подарок|дарим|разда(ча|ем|ют|ет|чу)\\w*|халяв\\w*|получи(те)?\\E|забери(те)?\\E|активиру\\w*|free|claim)[^.!?\\n]{0,40}(premium|премиум)"),
            new String[]{"telegram", "телеграм", "тг ", " tg"}}, // Premium is Telegram's only where Telegram is named
        {R_TEXT_GIFT, 20, p("(вам|тебе|you)( \\w+)? (отправил\\w*|подарил\\w*|прислал\\w*|sent|gifted)[^.!?\\n]{0,25}(подар\\w*|premium|премиум|звезд\\w*|stars|gift|nft)|(подарок|подарки|gift) (в|от|in|from) (telegram|телеграм\\w*)"),
            new String[]{"отправил", "подарил", "прислал", "sent", "gifted", "подар", "gift"}},
        {R_TEXT_VERIFY, 20, p("(подтверд\\w*|верифиц\\w*|верификац\\w*|пройдите проверку|пройди проверку|авторизуй\\w*)[^.!?\\n]{0,30}(аккаунт\\w*|учетн\\w*|личност\\w* (в|на) (telegram|телеграм\\w*|сайт\\w*)|профил\\w*|кошел\\w*|номер\\w* телефон\\w*)"),
            new String[]{"подтверд", "верифи", "провер", "авториз"}},
        {R_TEXT_VERIFY, 20, p("verify (your )?(account|identity|wallet|number|profile)|confirm (your )?(account|identity|wallet)|account verification|verification (is )?required"),
            new String[]{"verif", "confirm"}},
        {R_TEXT_BLOCK, 20, p("(аккаунт\\w*|учетн\\w* запис\\w*|страниц\\w*|профил\\w*|карт[ауые]\\E|кошел\\w*|номер\\w*)[^.!?\\n]{0,50}(будет|будут|могут быть|может быть|подлежит|подлежат|грозит)[^.!?\\n]{0,15}(заблокир\\w*|удален\\w*|удал\\w*|огранич\\w*|заморож\\w*|блокировк\\w*|деактивир\\w*)"),
            new String[]{"будет", "будут", "могут", "может", "подлеж", "грозит"}},
        {R_TEXT_BLOCK, 20, p("(заблокируют|удалят|ограничат|заморозят|деактивируют)[^.!?\\n]{0,40}(аккаунт\\w*|учетн\\w*|страниц\\w*|профил\\w*|карт[ауы]\\E|кошел\\w*)"),
            new String[]{"заблокируют", "удалят", "ограничат", "заморозят", "деактивируют"}},
        {R_TEXT_BLOCK, 20, p("(account|wallet|card|profile)[^.!?\\n]{0,30}(will be|may be|is going to be|is about to be)[^.!?\\n]{0,10}(suspended|blocked|deleted|restricted|locked|banned|frozen|terminated|disabled)"),
            new String[]{"suspend", "block", "delet", "restrict", "lock", "bann", "frozen", "terminat", "disabl"}},
        {R_TEXT_CODE, 30, p("(никому не (сообщайте|говорите|передавайте|показывайте)|не сообщайте (его )?никому|do not (give|share) (this|the) code)[^\\n]{0,40}(код|code)"),
            new String[]{"никому", "do not"}},
        {R_TEXT_CODE, 30, p("(отправ\\w*|пришл\\w*|скин\\w*|кин\\w*|продиктуй\\w*|сообщи\\w*|напиши\\w*|перешли\\w*|назови\\w*|скажи\\w*|дай\\w*|send|share|give|tell|forward)[^.!?\\n]{0,40}"
            + "(код\\w* (из|с|в) (смс|sms|сообщени\\w*|телеграм\\w*|telegram|тг|приложени\\w*|банк\\w*)|(смс|sms)[- ]?код\\w*|код\\w* подтвержд\\w*|код\\w* верифик\\w*|проверочн\\w* код\\w*|"
            + "код\\w*,? (который|что) (при\\w*|долж\\w*)|cvv2?|cvc|пароль от (телеграм\\w*|telegram|аккаунт\\w*|госуслуг\\w*|банк\\w*|почт\\w*|облачн\\w*)|облачн\\w* пароль|"
            + "(login|verification|confirmation|security|one[- ]time|sms) code|(your|ur) password)"),
            new String[]{"код", "пароль", "cvv", "cvc", "code", "password"}},
        {R_TEXT_CRYPTO, 25, p("(^|[^\\p{L}\\p{N}])(x2|х2|x3|х3|x5|х5|x10|х10)\\E[^.!?\\n]{0,40}(usdt|ton\\E|тон\\w*|btc|eth|крипт\\w*|депозит\\w*|вклад\\w*|деньг\\w*|прибыл\\w*|profit|invest\\w*|инвест\\w*)"
            + "|(usdt|ton\\E|btc|eth|депозит\\w*|вклад\\w*|деньг\\w*|инвест\\w*)[^.!?\\n]{0,40}(^|[^\\p{L}\\p{N}])(x2|х2|x3|х3|x5|х5|x10|х10)\\E"),
            new String[]{"x2", "х2", "x3", "х3", "x5", "х5", "x10", "х10"}},
        {R_TEXT_CRYPTO, 25, p("удво\\w*[^.!?\\n]{0,20}(депозит|вклад|деньг|сумм|средств|usdt|ton|тон|btc|eth|крипт|инвест)|double (your|the) (money|deposit|crypto|btc|eth|usdt|ton|investment)"),
            new String[]{"удво", "double"}},
        {R_TEXT_CRYPTO, 30, p("(введи\\w*|отправ\\w*|пришл\\w*|скин\\w*|укажи\\w*|вставь\\w*|импортир\\w*|enter|send|share|import|paste)[^.!?\\n]{0,30}(seed|сид[- ]?фраз\\w*|мнемони\\w*|секретн\\w* фраз\\w*|private key|приватн\\w* ключ\\w*|recovery phrase|фраз\\w* восстановлени\\w*)"),
            new String[]{"seed", "сид", "мнемони", "фраз", "private key", "ключ", "recovery phrase"}},
        {R_TEXT_CRYPTO, 25, p("(connect|подключи(те)?\\E)[^.!?\\n]{0,10}(your )?(wallet|кошел\\w*)[^.!?\\n]{0,40}(получ\\w*|claim|airdrop|бонус\\w*|reward|награ\\w*|nft|нфт)|airdrop|аирдроп|эйрдроп|эирдроп"),
            new String[]{"wallet", "кошел", "airdrop", "дроп"}},
        {R_TEXT_CRYPTO, 25, p("(отправ\\w*|send|переведи\\w*)[^.!?\\n]{0,25}[0-9]+[^.!?\\n]{0,6}(usdt|ton|btc|eth|тон)[^.!?\\n]{0,40}(получ\\w*|верн\\w*|receive|get back|back)"),
            new String[]{"usdt", "ton", "btc", "eth", "тон"}},
        {R_TEXT_MONEY, 20, p("(срочно|urgent\\w*|очень нужн\\w*|выручи\\w*)[^.!?\\n]{0,50}(деньг\\w*|денег|[0-9]+ ?(₽|р\\.|руб\\w*|тыс\\w*|к\\E|\\$|usd|долл\\w*|евро)|на карту|займ\\w*|одолж\\w*|money|transfer \\$?[0-9])"),
            new String[]{"срочно", "urgent", "очень нужн", "выручи"}},
        {R_TEXT_MONEY, 20, p("(займи|одолжи|переведи|скинь|кинь|перекинь)( мне| пожалуйста| плиз)?[^.!?\\n]{0,30}([0-9]+ ?(₽|р\\E|руб\\w*|тыс\\w*|к\\E|косар\\w*|\\$|usd|долл\\w*|евро)|денег|деньг\\w*|на карту)"
            + "|(lend me|can you send|send me|could you send)[^.!?\\n]{0,25}(money|\\$[0-9]|[0-9]+ ?(usd|dollars|\\$|eur))"),
            new String[]{"займи", "одолжи", "переведи", "скинь", "кинь", "lend me", "send"}},
        {R_TEXT_MONEY, 20, p("((номер|данные|реквизиты|срок действия) (вашей |твоей )?(банковской )?карты|cvv|cvc|код с обратной стороны|card number|card details)"),
            new String[]{"карт", "card", "cvv", "cvc", "обратной"}},
        {R_TEXT_VOTE, 25, p("(проголосу\\w*|голосу\\w*|голос за|голосовани\\w*|vote for|voting)[^\\n]{0,60}(племянниц\\w*|племянник\\w*|дочк\\w*|доч\\w*|сын\\w*|ребен\\w*|внучк\\w*|внук\\w*|подруг\\w*|крестниц\\w*|niece|nephew|daughter|son\\E|kid|конкурс\\w*|contest)"),
            new String[]{"голос", "vote", "voting"}},
        {R_TEXT_JOB, 20, p("(легк\\w*|быстр\\w*|пассивн\\w*)[^.!?\\n]{0,15}(заработ\\w*|деньг\\w*|доход\\w*)|заработ\\w* от [0-9]+|easy money|(earn|make) \\$?[0-9]+[^.!?\\n]{0,25}(day|daily|week|hour)"),
            new String[]{"заработ", "доход", "деньг", "money", "earn", "make"}},
        {R_TEXT_JOB, 20, p("[0-9]+ ?(₽|руб\\w*|\\$|usdt|долл\\w*|тыс\\w*)[^.!?\\n]{0,15}в (день|час|неделю)|работа без опыта|удаленн\\w* работ\\w*[^.!?\\n]{0,40}(без опыта|от [0-9]+)"),
            new String[]{"в день", "в час", "в неделю", "без опыта"}},
        {R_TEXT_PRIZE, 20, p("(вы|ты) (выиграли|выиграл\\w*|стали победител\\w*|стал\\w* победител\\w*)[^.!?\\n]{0,40}(приз\\w*|подар\\w*|iphone|айфон\\w*|деньг\\w*|руб\\w*|₽|\\$|сертификат\\w*|розыгрыш\\w*|лотере\\w*|бонус\\w*|автомобил\\w*|машин\\w*|premium|премиум)"
            + "|you (have )?won|you are (the |a )?winner|заберите (ваш )?приз|забери (свой )?приз|claim your (prize|reward)"),
            new String[]{"выиграл", "победител", "won", "winner", "приз", "prize", "reward"}},
        {R_TEXT_URGENT, 5, p("(в течение|within|осталось|only) [0-9]+ (час\\w*|минут\\w*|hours?|minutes?)|срочно|немедленно|urgent|immediately|act now"),
            new String[]{"в течение", "within", "осталось", "only", "срочно", "немедленно", "urgent", "immediately", "act now"}},
    };

    private static final int TEXT_CAP = 40;

    /** Scores the text of a message (what surrounds its links, or a stranger's first messages). */
    public static TextReport scoreText(String text) {
        final TextReport t = new TextReport();
        if (text == null || text.isEmpty()) {
            return t;
        }
        String s = text.length() > 4000 ? text.substring(0, 4000) : text;
        s = s.toLowerCase(Locale.ROOT).replace('ё', 'е').replace(' ', ' ');
        final HashSet<Integer> seen = new HashSet<>();
        for (Object[] rule : TEXT_RULES) {
            final int code = (Integer) rule[0];
            if (seen.contains(code) || !containsAny(s, (String[]) rule[3])) {
                continue; // none of the words the rule needs: the expression is not run
            }
            if (((Pattern) rule[2]).matcher(s).find()) {
                seen.add(code);
                final int weight = (Integer) rule[1];
                t.reasons.add(new Reason(code, null, weight, KIND_TEXT, 0));
                t.score += weight;
            }
        }
        // a lone "urgent" says little when nothing else is there
        if (t.reasons.size() == 1 && t.reasons.get(0).code == R_TEXT_URGENT) {
            t.reasons.clear();
            t.score = 0;
        }
        t.score = Math.min(TEXT_CAP, t.score);
        return t;
    }

    // endregion

    // region Accounts

    /** What is known about a stranger (filled by the app from Telegram's data). */
    public static final class Account {
        public boolean scam, fake, verified, official, bot, contact, deleted;
        /** A mutual contact; a bot or account verified by a third party Telegram trusts (the badge). */
        public boolean mutualContact, botVerified;
        /** A long chat (more than about 50 messages), or the user has written there a lot. */
        public boolean longHistory;
        /** The user has answered in the chat. */
        public boolean conversed;
        public String firstName, lastName;
        /** The usernames, without @. */
        public String[] usernames;
        public boolean hasPhoto = true;
        /** Groups in common, or -1 when not known yet. */
        public int commonChats = -1;
        /** "MM.yyyy" from Telegram, or null. */
        public String registrationMonth;
        /** Unix seconds, 0 when not known. */
        public int nameChangeDate, photoChangeDate;
        /** The stranger wrote first (the chat begins with their message). */
        public boolean wroteFirst;
        /** Text of their first messages, or null. */
        public String firstMessages;
        public int firstMessagesLinks;
        /** Normalized names of the user's contacts ({@link #normalizeName}) -> the name as saved. */
        public Map<String, String> contactNames;
    }

    /** Groups of strong signs: two in one group are one piece of evidence. */
    private static final int G_NAME = 1, G_USERNAME = 2, G_CHECKMARK = 3, G_CLONE = 4, G_TEXT = 5, G_COLD = 6, G_AI = 7;

    public static final class AccountReport {
        public int score;
        public final ArrayList<Reason> reasons = new ArrayList<>();
        /** Telegram itself marked it (scam / fake): always dangerous. */
        public boolean flagged;
        /** AI on the phone said it looks fine: never more than caution. */
        public boolean aiSafe;

        void add(int code, String arg, int weight) {
            add(code, arg, weight, KIND_WEAK, 0);
        }

        void add(int code, String arg, int weight, int kind, int group) {
            reasons.add(new Reason(code, arg, weight, kind, group));
            score += weight;
        }

        /** What AI made of the first messages. */
        public void addAi(String note, int level) {
            if (level == DANGER) {
                add(R_AI, note, 30, KIND_HARD, G_AI);
            } else if (level == CAUTION) {
                add(R_AI, note, 10, KIND_MEDIUM, 0);
            } else {
                aiSafe = true;
                score -= 15;
            }
        }

        /** Independent strong signs. */
        public int strongSigns() {
            int mask = 0;
            for (Reason r : reasons) {
                if (r.kind == KIND_HARD && r.group > 0) {
                    mask |= 1 << r.group;
                }
            }
            return Integer.bitCount(mask);
        }

        public int mediumSigns() {
            int n = 0;
            for (Reason r : reasons) {
                if (r.kind == KIND_MEDIUM) {
                    n++;
                }
            }
            return n;
        }

        public List<Reason> sortedReasons() {
            final ArrayList<Reason> list = new ArrayList<>(reasons);
            Collections.sort(list, (a, b) -> a.kind != b.kind ? b.kind % 3 - a.kind % 3 : b.weight - a.weight);
            return list;
        }

        @Override
        public String toString() {
            return score + " s" + strongSigns() + " m" + mediumSigns() + " " + sortedReasons();
        }
    }

    /**
     * The level of a stranger: Telegram's mark, or at least two independent strong signs (on strict, one strong and
     * two lesser ones). One odd thing about a new contact is never enough.
     */
    public static int accountLevel(AccountReport r, int sensitivity) {
        if (r == null) {
            return SAFE;
        }
        if (r.flagged) {
            return DANGER;
        }
        final int s = clampSensitivity(sensitivity);
        final int strong = r.strongSigns();
        final boolean show = strong >= 2 || s == SENSITIVITY_STRICT && strong >= 1 && r.mediumSigns() >= 2;
        if (!show) {
            return SAFE;
        }
        final int[] t = ACCOUNT_THRESHOLDS[s];
        final int level = r.score >= t[1] || strong >= 3 ? DANGER : CAUTION;
        return r.aiSafe ? Math.min(level, CAUTION) : level;
    }

    /** One strong sign and their first messages to read: an on-device AI may take a second look. */
    public static boolean isBorderlineAccount(AccountReport r, int sensitivity) {
        return r != null && !r.flagged && r.strongSigns() == 1 && accountLevel(r, sensitivity) == SAFE;
    }

    /** Words of staff: harmless alone (a shop's support), telling next to Telegram, a bank or the state. */
    private static final String[] NAME_STAFF = {
        "support", "поддержк", "служба", "администрац", "administrator", "администратор", "admin", "админ", "модератор", "moderator",
        "official", "официальн", "security", "безопасност", "уведомлен", "notification", "верификац", "verification", "отдел", "сотрудник"
    };
    /** Who scammers pose as. */
    private static final String[] NAME_AUTHORITY = {
        "telegram", "телеграм", "telegrarn", "госуслуг", "gosuslug", "сбербанк", "sberbank", "сбер", "sber", "тинькоф", "tinkoff", "т-банк",
        "tbank", "втб", "vtb", "альфа-банк", "альфабанк", "alfabank", "центробанк", "центральный банк", "цб рф", "банк россии", "wildberries",
        "вайлдберриз", "fragment", "binance", "bybit", "полиция", "фсб", "мвд", "следователь", "прокуратур", "соцфонд", "пенсионн", "налогов",
        "fns", "фнс", "росфинмониторинг", "озон", "ozon", "авито", "avito", "сдэк", "cdek", "госуслуги", "банк", "bank", "wallet", "кошелек"
    };
    /** Telegram and its founder: posing as them needs no staff word. */
    private static final String[] NAME_TELEGRAM = {"telegram", "телеграм", "telegrarn", "pavel durov", "павел дуров", "durov official"};

    /** What a fake "Telegram ..." account offers: Telegram Premium, Telegram Gifts. */
    private static final String[] NAME_TOPIC = {"premium", "премиум", "stars", "звезд", "gift", "подар", "nft", "нфт"};

    private static final String[] CHECKMARKS = {"✅", "✔", "☑", "✓", "🗸", "verified", "верифицирован"};

    /** Scores a stranger. @param now unix seconds */
    public static AccountReport scoreAccount(Account a, long now) {
        final AccountReport r = new AccountReport();
        if (a == null) {
            return r;
        }
        if (a.scam) {
            r.flagged = true;
            r.add(A_SCAM, null, 100, KIND_HARD, 0);
        }
        if (a.fake) {
            r.flagged = true;
            r.add(A_FAKE, null, 100, KIND_HARD, 0);
        }
        if (r.flagged) {
            return r;
        }
        // people and accounts that are never warned about
        if (a.verified || a.official || a.botVerified || a.contact || a.mutualContact || a.deleted || a.longHistory
            || a.conversed && a.commonChats > 0) {
            return r;
        }
        if (a.usernames != null) {
            for (String u : a.usernames) {
                if (u != null && OFFICIAL_USERNAMES.contains(u.toLowerCase(Locale.ROOT))) {
                    return r;
                }
            }
        }
        final String name = ((a.firstName == null ? "" : a.firstName) + " " + (a.lastName == null ? "" : a.lastName)).trim();
        final String lowerName = name.toLowerCase(Locale.ROOT).replace('ё', 'е');
        final String skeletonName = skeleton(lowerName);
        final String staff = firstIn(lowerName, skeletonName, NAME_STAFF);
        final String authority = firstIn(lowerName, skeletonName, NAME_AUTHORITY);
        final String telegram = firstIn(lowerName, skeletonName, NAME_TELEGRAM);
        final boolean topic = telegram != null && firstIn(lowerName, skeletonName, NAME_TOPIC) != null;
        if (staff != null && authority != null || topic) {
            // "Служба безопасности Сбербанка", "Telegram Support"
            r.add(A_NAME_IMPERSONATION, authority != null ? authority : telegram, 45, KIND_HARD, G_NAME);
        } else if (telegram != null) {
            r.add(A_NAME_IMPERSONATION, telegram, telegram.contains(" ") ? 40 : 25, telegram.contains(" ") ? KIND_HARD : KIND_MEDIUM, G_NAME);
        } else if (authority != null && !authority.equals("банк") && !authority.equals("bank") && !authority.equals("wallet") && !authority.equals("кошелек")) {
            r.add(A_NAME_IMPERSONATION, authority, 20, KIND_MEDIUM, 0);
        } else if (staff != null) {
            r.add(A_NAME_IMPERSONATION, staff, a.bot ? 5 : 15, a.bot ? KIND_WEAK : KIND_MEDIUM, 0); // a shop's support bot
        }
        int usernameWeight = 0;
        String usernameArg = null;
        if (a.usernames != null) {
            for (String u : a.usernames) {
                if (u == null || u.isEmpty()) {
                    continue;
                }
                final int w = usernameRisk(u);
                if (w > usernameWeight) {
                    usernameWeight = w;
                    usernameArg = "@" + u;
                }
            }
        }
        if (usernameWeight >= 45) {
            r.add(A_USERNAME_IMPERSONATION, usernameArg, 30, KIND_HARD, G_USERNAME);
        } else if (usernameWeight >= 25) {
            r.add(A_USERNAME_IMPERSONATION, usernameArg, 15, KIND_MEDIUM, 0);
        }
        for (String mark : CHECKMARKS) {
            if (lowerName.contains(mark)) {
                r.add(A_FAKE_CHECKMARK, null, 30, KIND_HARD, G_CHECKMARK);
                break;
            }
        }
        // someone copying a contact: the same full name (a first name alone is shared by thousands)
        boolean clone = false;
        if (a.contactNames != null && !a.contactNames.isEmpty() && a.firstName != null && a.lastName != null
            && !a.firstName.trim().isEmpty() && !a.lastName.trim().isEmpty()) {
            final String key = normalizeName(a.firstName, a.lastName);
            final String contact = key.length() >= 8 && key.contains(" ") ? a.contactNames.get(key) : null;
            if (contact != null) {
                r.add(A_CONTACT_NAME, contact, 35, KIND_HARD, G_CLONE);
                clone = true;
            }
        }
        if (a.commonChats == 0) {
            r.add(A_NO_COMMON_GROUPS, null, 5);
        }
        if (a.usernames == null || a.usernames.length == 0) {
            r.add(A_NO_USERNAME, null, 3);
        }
        if (!a.hasPhoto) {
            r.add(A_NO_PHOTO, null, 3);
        }
        int monthsOld = -1;
        if (a.registrationMonth != null && !a.bot) {
            monthsOld = monthsSince(a.registrationMonth, now);
            if (monthsOld >= 0 && monthsOld <= 1) {
                r.add(A_NEW_ACCOUNT, a.registrationMonth, 15, KIND_MEDIUM, 0);
            } else if (monthsOld >= 0 && monthsOld <= 3) {
                r.add(A_NEW_ACCOUNT, a.registrationMonth, 10, KIND_MEDIUM, 0);
            } else if (monthsOld >= 0 && monthsOld <= 6) {
                r.add(A_NEW_ACCOUNT, a.registrationMonth, 5);
            }
        }
        final int nameDays = a.nameChangeDate > 0 ? (int) Math.max(0, (now - a.nameChangeDate) / 86400L) : -1;
        final int photoDays = a.photoChangeDate > 0 ? (int) Math.max(0, (now - a.photoChangeDate) / 86400L) : -1;
        if (nameDays >= 0 && nameDays <= 7) {
            r.add(A_NAME_CHANGED, String.valueOf(nameDays), 12, KIND_MEDIUM, 0);
        } else if (nameDays >= 0 && nameDays <= 30) {
            r.add(A_NAME_CHANGED, String.valueOf(nameDays), 6);
        }
        if (photoDays >= 0 && photoDays <= 7) {
            r.add(A_PHOTO_CHANGED, String.valueOf(photoDays), 6);
        } else if (photoDays >= 0 && photoDays <= 30) {
            r.add(A_PHOTO_CHANGED, String.valueOf(photoDays), 3);
        }
        if (monthsOld >= 0 && monthsOld <= 3 && (nameDays >= 0 && nameDays <= 30 || photoDays >= 0 && photoDays <= 30)) {
            // one more face of "someone made up a person": the same piece of evidence as a copied contact's name
            r.add(A_FRESH_AND_CHANGED, null, clone ? 10 : 25, KIND_HARD, G_CLONE);
        }
        if (a.wroteFirst && a.firstMessagesLinks > 0) {
            if (a.commonChats == 0) {
                r.add(A_FIRST_LINKS, null, 20, KIND_HARD, G_COLD); // a cold message with links from nowhere
            } else {
                r.add(A_FIRST_LINKS, null, 10, KIND_MEDIUM, 0);
            }
        }
        if (a.firstMessages != null) {
            final TextReport t = scoreText(a.firstMessages);
            final boolean strong = a.wroteFirst && t.score >= 20;
            for (Reason reason : t.reasons) {
                // listed with their own weights; the sum is added once below (capped as the text's score is)
                r.reasons.add(new Reason(reason.code, null, reason.weight, strong ? KIND_HARD : KIND_MEDIUM, strong ? G_TEXT : 0));
            }
            r.score += strong ? t.score : t.score / 2;
        }
        return r;
    }

    private static String firstIn(String lower, String skeleton, String[] words) {
        for (String w : words) {
            if (lower.contains(w) || skeleton.contains(w)) {
                return w;
            }
        }
        return null;
    }

    /** A name as compared with the contacts' names: lowercase, letters only, look-alike letters as Latin. */
    public static String normalizeName(String first, String last) {
        final String full = ((first == null ? "" : first) + " " + (last == null ? "" : last)).toLowerCase(Locale.ROOT).replace('ё', 'е');
        final StringBuilder sb = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < full.length(); ) {
            final int cp = full.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                if (space && sb.length() > 0) {
                    sb.append(' ');
                }
                space = false;
                sb.appendCodePoint(cp);
            } else if (Character.isWhitespace(cp)) {
                space = true;
            }
        }
        return skeleton(sb.toString());
    }

    private static int monthsSince(String monthYear, long now) {
        try {
            final String[] parts = monthYear.split("\\.");
            if (parts.length != 2) {
                return -1;
            }
            final int month = Integer.parseInt(parts[0].trim());
            final int year = Integer.parseInt(parts[1].trim());
            final Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            c.setTimeInMillis(now * 1000L);
            final int months = (c.get(Calendar.YEAR) - year) * 12 + (c.get(Calendar.MONTH) + 1 - month);
            return Math.max(0, months);
        } catch (Exception e) {
            return -1;
        }
    }

    // endregion

    /** Pairs of letters that read alike at a glance (after {@link #fold}): one swapped for the other is a disguise. */
    private static final String[] VISUAL_PAIRS = {"il", "ij", "lj", "uv", "nm", "nh", "ce", "co", "eo", "ae", "ao", "gq", "pq", "bd", "vy", "kx", "uw", "vw"};

    /** A one-letter edit that deceives the eye: a dropped or doubled letter, two swapped, or a look-alike letter. */
    static boolean visualEdit(String label, String brand) {
        final int n = label.length(), m = brand.length();
        if (n == m) {
            int first = -1, second = -1, diffs = 0;
            for (int i = 0; i < n; i++) {
                if (label.charAt(i) != brand.charAt(i)) {
                    if (first < 0) {
                        first = i;
                    } else {
                        second = i;
                    }
                    diffs++;
                }
            }
            if (diffs == 2) {
                return second == first + 1 && label.charAt(first) == brand.charAt(second) && label.charAt(second) == brand.charAt(first);
            }
            if (diffs != 1) {
                return false;
            }
            final char a = label.charAt(first), b = brand.charAt(first);
            for (String pair : VISUAL_PAIRS) {
                if (pair.charAt(0) == a && pair.charAt(1) == b || pair.charAt(0) == b && pair.charAt(1) == a) {
                    return true;
                }
            }
            return false;
        }
        if (n == m + 1) {
            // a letter added: doubled (telegramm, wildberriess) or a hyphen
            for (int i = 0; i < n; i++) {
                if (i >= m || label.charAt(i) != brand.charAt(i)) {
                    final char extra = label.charAt(i);
                    return extra == '-' || i > 0 && label.charAt(i - 1) == extra || i + 1 < n && label.charAt(i + 1) == extra;
                }
            }
            return false;
        }
        if (n == m - 1) {
            // a letter dropped inside the name (wildberies, telgram); not at its end (megafo, faceit -> facet is excluded)
            for (int i = 0; i < m; i++) {
                if (i >= n || label.charAt(i) != brand.charAt(i)) {
                    return i > 0 && i < m - 1;
                }
            }
        }
        return false;
    }

    // region Text helpers

    private static final int SCRIPT_LATIN = 1, SCRIPT_CYRILLIC = 2, SCRIPT_GREEK = 4, SCRIPT_ARMENIAN = 8, SCRIPT_OTHER = 16;

    private static int scriptMask(String s) {
        int mask = 0;
        for (int i = 0; i < s.length(); ) {
            final int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            final Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
            if (block == Character.UnicodeBlock.BASIC_LATIN || block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT
                || block == Character.UnicodeBlock.LATIN_EXTENDED_A || block == Character.UnicodeBlock.LATIN_EXTENDED_B
                || block == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL || block == Character.UnicodeBlock.IPA_EXTENSIONS) {
                mask |= SCRIPT_LATIN;
            } else if (block == Character.UnicodeBlock.CYRILLIC || block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY) {
                mask |= SCRIPT_CYRILLIC;
            } else if (block == Character.UnicodeBlock.GREEK || block == Character.UnicodeBlock.GREEK_EXTENDED) {
                mask |= SCRIPT_GREEK;
            } else if (block == Character.UnicodeBlock.ARMENIAN) {
                mask |= SCRIPT_ARMENIAN;
            } else {
                mask |= SCRIPT_OTHER;
            }
        }
        return mask;
    }

    /** Letters of other alphabets that really look like Latin ones (lowercase; hosts and names are lowercased first). */
    private static final String CONFUSABLE_FROM = "аеорсухіјѕԁһӏԛԝαοριυνκχωօսոցհլռքɑɡıłʟɩǀ";
    private static final String CONFUSABLE_TO = "aeopcyxijsdhlqwaopiuvkxwounghlnqagillil";

    static {
        if (CONFUSABLE_FROM.length() != CONFUSABLE_TO.length()) {
            throw new IllegalStateException("confusables");
        }
    }

    /** The text with accents dropped and look-alike letters replaced by the Latin letters they imitate. */
    public static String skeleton(String s) {
        if (s == null) {
            return "";
        }
        final String lower = s.toLowerCase(Locale.ROOT);
        final String plain = isAscii(lower) ? lower : MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
        final StringBuilder sb = new StringBuilder(plain.length());
        for (int i = 0; i < plain.length(); i++) {
            final char c = plain.charAt(i);
            final int k = CONFUSABLE_FROM.indexOf(c);
            sb.append(k >= 0 ? CONFUSABLE_TO.charAt(k) : c);
        }
        return sb.toString();
    }

    /** For comparing with brands: digits as the letters they stand for, "rn" as "m", i/l/1 alike, no hyphens. */
    static String canon(String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        final String k = skeleton(s);
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            switch (c) {
                case '0':
                    c = 'o';
                    break;
                case '1':
                case 'i':
                case '|':
                case '!':
                    c = 'l';
                    break;
                case '3':
                    c = 'e';
                    break;
                case '4':
                case '@':
                    c = 'a';
                    break;
                case '5':
                case '$':
                    c = 's';
                    break;
                case '7':
                    c = 't';
                    break;
                case '8':
                    c = 'b';
                    break;
                case '9':
                    c = 'g';
                    break;
                case '-':
                case '_':
                    continue;
                default:
                    break;
            }
            sb.append(c);
        }
        return sb.toString().replace("rn", "m").replace("vv", "w").replace("cl", "d");
    }

    /** Lighter than {@link #canon}: only the digits that stand for letters and accents. */
    private static String fold(String s) {
        final String k = skeleton(s);
        final StringBuilder sb = new StringBuilder(k.length());
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (c == '0') {
                c = 'o';
            } else if (c == '1') {
                c = 'l';
            } else if (c == '3') {
                c = 'e';
            } else if (c == '5') {
                c = 's';
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Words of a name: split at hyphens, dots, underscores and between letters and digits. */
    private static List<String> tokens(String s) {
        final ArrayList<String> list = new ArrayList<>();
        final StringBuilder sb = new StringBuilder();
        int kind = 0; // 1 letters, 2 digits
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            final int k = Character.isLetter(c) ? 1 : Character.isDigit(c) ? 2 : 0;
            if (k == 0 || kind != 0 && k != kind) {
                if (sb.length() > 0) {
                    list.add(sb.toString());
                    sb.setLength(0);
                }
            }
            if (k != 0) {
                sb.append(c);
            }
            kind = k;
        }
        if (sb.length() > 0) {
            list.add(sb.toString());
        }
        return list;
    }

    /** Edit distance with swapped neighbours counted as one edit (optimal string alignment). */
    static int distance(String a, String b) {
        final int n = a.length(), m = b.length();
        if (Math.abs(n - m) > 2) {
            return 3;
        }
        final int[][] dp = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                final int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, dp[i - 2][j - 2] + 1);
                }
                dp[i][j] = v;
            }
        }
        return dp[n][m];
    }

    /** Marks the letters of {@code label} that differ from {@code brand} (by the alignment of the two). */
    private static void markDiff(LinkReport r, String label, String brand, int labelStart) {
        if (labelStart < 0) {
            return;
        }
        final String a = label, b = brand;
        final int n = a.length(), m = b.length();
        final int[][] dp = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                final int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        int i = n, j = m;
        final boolean[] diff = new boolean[n];
        while (i > 0 && j > 0) {
            final int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
            if (dp[i][j] == dp[i - 1][j - 1] + cost) {
                if (cost == 1) {
                    diff[i - 1] = true;
                }
                i--;
                j--;
            } else if (dp[i][j] == dp[i - 1][j] + 1) {
                diff[i - 1] = true;
                i--;
            } else {
                // a letter dropped: mark the neighbours
                if (i - 1 >= 0) {
                    diff[i - 1] = true;
                }
                j--;
            }
        }
        while (i > 0) {
            diff[--i] = true;
        }
        for (int k = 0; k < n; k++) {
            if (diff[k]) {
                int e = k;
                while (e < n && diff[e]) {
                    e++;
                }
                markRange(r, labelStart + k, labelStart + e);
                k = e;
            }
        }
    }

    private static void markNonAscii(LinkReport r, String unicodeHost, int labelIndex) {
        final int start = unicodeLabelStart(unicodeHost, labelIndex);
        if (start < 0) {
            return;
        }
        int end = unicodeHost.indexOf('.', start);
        if (end < 0) {
            end = unicodeHost.length();
        }
        for (int k = start; k < end; k++) {
            if (unicodeHost.charAt(k) > 127) {
                int e = k;
                while (e < end && unicodeHost.charAt(e) > 127) {
                    e++;
                }
                markRange(r, k, e);
                k = e;
            }
        }
    }

    /** Marks the owner's part of the host (the site): "secure-pay.top" in sberbank.secure-pay.top. */
    private static void markSite(LinkReport r, String unicodeHost, String site) {
        final int siteLabels = site.length() - site.replace(".", "").length() + 1;
        final int hostLabels = unicodeHost.length() - unicodeHost.replace(".", "").length() + 1;
        final int start = unicodeLabelStart(unicodeHost, hostLabels - siteLabels);
        if (start >= 0) {
            markRange(r, start, unicodeHost.length());
        }
    }

    private static void markRange(LinkReport r, int start, int end) {
        if (start < 0 || end <= start || r.displayHost == null || end > r.displayHost.length()) {
            return;
        }
        r.highlight.add(new int[]{start, end});
    }

    private static int unicodeLabelStart(String unicodeHost, int labelIndex) {
        if (labelIndex < 0) {
            return -1;
        }
        int pos = 0;
        for (int k = 0; k < labelIndex; k++) {
            final int dot = unicodeHost.indexOf('.', pos);
            if (dot < 0) {
                return -1;
            }
            pos = dot + 1;
        }
        return pos;
    }

    private static String siteLabel(String site) {
        final int dot = site.indexOf('.');
        return dot < 0 ? site : site.substring(0, dot);
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(String[] list, String s) {
        if (list == null) {
            return false;
        }
        for (String x : list) {
            if (x.equals(s)) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    private static boolean isIpAddress(String host) {
        if (IPV4.matcher(host).matches()) {
            return true;
        }
        // 3232235777, 0x7f000001, 0177.0.0.1: an address written as a number
        return host.matches("^(0x[0-9a-f]+|\\d{6,})$") || host.matches("^(0[0-7]+|0x[0-9a-f]+)(\\.(\\d+|0x[0-9a-f]+)){0,3}$");
    }

    private static boolean isPrivateIp(String host) {
        return host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("127.") || host.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

    /** Zero-width and direction marks that hide what an address really is. */
    private static String stripInvisible(String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '​' || c == '‌' || c == '‍' || c == '⁠' || c == '﻿' || c >= '‪' && c <= '‮'
                || c >= '⁦' && c <= '⁩' || c == '­') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String percentDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // endregion
}
