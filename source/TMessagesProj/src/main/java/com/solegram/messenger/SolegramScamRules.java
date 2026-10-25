/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import java.net.IDN;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
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
 * A link is scored by its address (look-alike domains, brand names on other sites, hidden destinations,
 * downloads, Telegram usernames posing as support...) and by the message around it (free Premium, "your account
 * will be blocked", "send me the code"...); an account by Telegram's own marks and what its profile says.
 * Each sign adds points with a reason the user can read; the sum gives safe, caution or danger.
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

    public static final class Reason {
        public final int code;
        /** Domain, word, name... shown in the reason; may be null. */
        public final String arg;
        public final int weight;

        Reason(int code, String arg, int weight) {
            this.code = code;
            this.arg = arg;
            this.weight = weight;
        }

        @Override
        public String toString() {
            return code + (arg != null ? "(" + arg + ")" : "") + "+" + weight;
        }
    }

    /** True for reasons no message context or AI may talk away (a disguised address). */
    public static boolean isHardReason(int code) {
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
                return true;
            default:
                return false;
        }
    }

    // endregion

    // region Levels

    /** {caution, danger} points for links, by sensitivity. */
    private static final int[][] LINK_THRESHOLDS = {{40, 75}, {30, 60}, {20, 50}};
    /** The same for accounts: a stranger is shown a warning only with several signs together. */
    private static final int[][] ACCOUNT_THRESHOLDS = {{45, 80}, {35, 65}, {25, 55}};
    /** Below this a link gets no mark at all, at any sensitivity (the strict caution threshold). */
    public static final int MARK_FLOOR = 20;

    private static int clampSensitivity(int s) {
        return Math.max(SENSITIVITY_SOFT, Math.min(SENSITIVITY_STRICT, s));
    }

    public static int linkLevel(int score, int sensitivity) {
        final int[] t = LINK_THRESHOLDS[clampSensitivity(sensitivity)];
        return score >= t[1] ? DANGER : score >= t[0] ? CAUTION : SAFE;
    }

    public static int accountLevel(int score, int sensitivity) {
        final int[] t = ACCOUNT_THRESHOLDS[clampSensitivity(sensitivity)];
        return score >= t[1] ? DANGER : score >= t[0] ? CAUTION : SAFE;
    }

    /** Between caution and danger: an on-device AI may raise or lower it. */
    public static boolean isBorderlineLink(int score, int sensitivity) {
        return linkLevel(score, sensitivity) == CAUTION;
    }

    public static boolean isBorderlineAccount(int score, int sensitivity) {
        final int[] t = ACCOUNT_THRESHOLDS[clampSensitivity(sensitivity)];
        return score >= t[0] - 15 && score < t[1];
    }

    // endregion

    // region Link report

    public static final class LinkReport {
        public final String url;
        /** Lowercase ASCII host (punycode), or null for a link without one (data:, javascript:). */
        public String host;
        /** The host as people read it (Unicode letters instead of xn--). */
        public String displayHost;
        /** The part of the host that names the site's owner (example.co.uk, user.github.io). */
        public String site;
        /** A big, well known site (or the brand's own): the address itself raises nothing. */
        public boolean trusted;
        /** Telegram username of a t.me link (lowercase), or null. */
        public String telegramUsername;
        /** A Telegram link that is not a username (invite, sticker set...). */
        public boolean telegramService;
        public int score;
        public final ArrayList<Reason> reasons = new ArrayList<>();
        /** Ranges {start, end} in {@link #displayHost} that do the deceiving. */
        public final ArrayList<int[]> highlight = new ArrayList<>();

        LinkReport(String url) {
            this.url = url;
        }

        void add(int code, String arg, int weight) {
            for (int i = 0; i < reasons.size(); i++) {
                final Reason r = reasons.get(i);
                if (r.code == code) {
                    if (r.weight >= weight) {
                        return;
                    }
                    score -= r.weight;
                    reasons.remove(i);
                    break;
                }
            }
            reasons.add(new Reason(code, arg, weight));
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
                if (isHardReason(r.code)) {
                    return true;
                }
            }
            return false;
        }

        /** The reasons, the heaviest first. */
        public List<Reason> sortedReasons() {
            final ArrayList<Reason> list = new ArrayList<>(reasons);
            java.util.Collections.sort(list, (a, b) -> b.weight - a.weight);
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

    // endregion

    // region Data

    private static final class Brand {
        final String label;
        final String display;
        /** The name is also an everyday word (apple, steam): it counts only with other signs. */
        final boolean generic;
        /** brand.<country> is the brand's too (google.de, yandex.kz). */
        final boolean anyCountry;
        final String[] domains;
        /** Real, unrelated names one letter away (finance for binance). */
        final String[] exclude;

        Brand(String label, String display, boolean generic, boolean anyCountry, String[] domains, String... exclude) {
            this.label = label;
            this.display = display;
            this.generic = generic;
            this.anyCountry = anyCountry;
            this.domains = domains;
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
        "sbermarket.ru", "sbermegamarket.ru", "megamarket.ru", "sberauto.com", "sberhealth.ru", "sbermobile.ru", "sberbank.kz");
    private static final String[] TINKOFF_DOMAINS = d("tinkoff.ru", "tbank.ru", "tinkoff.com", "tinkoffinsurance.ru", "t-j.ru");
    private static final String[] GOOGLE_DOMAINS = d("google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "googlevideo.com", "ggpht.com",
        "g.co", "withgoogle.com", "google.dev", "googleblog.com", "googlesource.com", "gmail.com", "youtube.com", "youtu.be", "android.com", "blogger.com",
        "chrome.com", "translate.goog", "googledrive.com", "googlemail.com", "google");
    private static final String[] YOUTUBE_DOMAINS = d("youtube.com", "youtu.be", "ytimg.com", "youtube-nocookie.com", "googlevideo.com", "youtubekids.com", "google.com");
    private static final String[] APPLE_DOMAINS = d("apple.com", "icloud.com", "apple.news", "mzstatic.com", "itunes.com", "me.com", "cdn-apple.com", "apple.co");
    private static final String[] META_DOMAINS = d("facebook.com", "fb.com", "fb.me", "fbcdn.net", "meta.com", "messenger.com", "fb.watch", "facebook.net");
    private static final String[] MICROSOFT_DOMAINS = d("microsoft.com", "live.com", "outlook.com", "office.com", "microsoftonline.com", "windows.com", "msn.com",
        "office365.com", "sharepoint.com", "onedrive.com", "azure.com", "skype.com", "xbox.com", "bing.com", "microsoft365.com", "live.net", "aka.ms");

    private static final Brand[] BRANDS = {
        new Brand("telegram", "telegram.org", false, false, TELEGRAM_DOMAINS),
        new Brand("fragment", "fragment.com", true, false, d("fragment.com")),
        new Brand("steamcommunity", "steamcommunity.com", false, false, STEAM_DOMAINS),
        new Brand("steampowered", "steampowered.com", false, false, STEAM_DOMAINS),
        new Brand("steam", "steampowered.com", true, false, STEAM_DOMAINS),
        new Brand("gosuslugi", "gosuslugi.ru", false, false, d("gosuslugi.ru")),
        new Brand("sberbank", "sberbank.ru", false, false, SBER_DOMAINS),
        new Brand("sber", "sberbank.ru", false, false, SBER_DOMAINS),
        new Brand("tinkoff", "tinkoff.ru", false, false, TINKOFF_DOMAINS),
        new Brand("tbank", "tbank.ru", false, false, TINKOFF_DOMAINS),
        new Brand("vtb", "vtb.ru", false, false, d("vtb.ru", "vtb.com", "vtb24.ru", "vtbcapital.ru", "vtb.am")),
        new Brand("alfabank", "alfabank.ru", false, false, d("alfabank.ru", "alfabank.com", "alfabank.by", "alfabank.kz")),
        new Brand("ozon", "ozon.ru", false, false, d("ozon.ru", "ozon.com", "ozone.ru", "ozon.travel", "ozon.kz", "ozon.by")),
        new Brand("wildberries", "wildberries.ru", false, false, d("wildberries.ru", "wb.ru", "wildberries.by", "wildberries.kz", "wildberries.am",
            "wildberries.uz", "wbstatic.net", "wbbasket.ru", "wildberries.ge", "wildberries.kg", "wb-bank.ru", "wildberries.com")),
        new Brand("avito", "avito.ru", false, false, d("avito.ru", "avito.st", "avito.com")),
        new Brand("yandex", "yandex.ru", false, true, d("yandex.ru", "yandex.com", "ya.ru", "yandex.net", "yastatic.net", "yandex.cloud", "yandexcloud.net", "yandex-team.ru")),
        new Brand("vk", "vk.com", false, false, d("vk.com", "vk.ru", "vk.me", "vkontakte.ru", "vkvideo.ru", "userapi.com", "vk-portal.net", "vkuser.net", "vk.company", "vk-cdn.net")),
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
        new Brand("discord", "discord.com", false, false, d("discord.com", "discord.gg", "discordapp.com", "discordapp.net", "discord.media", "discordstatus.com", "discord.co", "dis.gd")),
        new Brand("instagram", "instagram.com", false, false, d("instagram.com", "cdninstagram.com", "instagr.am", "ig.me")),
        new Brand("tiktok", "tiktok.com", false, false, d("tiktok.com", "tiktokcdn.com", "tiktokv.com", "tiktokcdn-us.com")),
        new Brand("facebook", "facebook.com", false, false, META_DOMAINS),
        new Brand("whatsapp", "whatsapp.com", false, false, d("whatsapp.com", "wa.me", "whatsapp.net")),
        new Brand("microsoft", "microsoft.com", false, false, MICROSOFT_DOMAINS),
        new Brand("outlook", "outlook.com", false, false, MICROSOFT_DOMAINS),
        new Brand("amazon", "amazon.com", false, true, d("amazon.com", "amazonaws.com", "a.co", "amzn.to", "media-amazon.com", "ssl-images-amazon.com", "amazon.co.uk", "amazon.co.jp", "amazon.com.br")),
        new Brand("netflix", "netflix.com", false, false, d("netflix.com", "nflxext.com", "nflximg.net", "nflxvideo.net", "netflix.net")),
        new Brand("spotify", "spotify.com", false, false, d("spotify.com", "spotify.link", "scdn.co", "spoti.fi")),
        new Brand("coinbase", "coinbase.com", false, false, d("coinbase.com")),
        new Brand("trustwallet", "trustwallet.com", false, false, d("trustwallet.com")),
        new Brand("kucoin", "kucoin.com", false, false, d("kucoin.com")),
        new Brand("okx", "okx.com", false, false, d("okx.com")),
        new Brand("epicgames", "epicgames.com", false, false, d("epicgames.com", "unrealengine.com", "fortnite.com")),
        new Brand("faceit", "faceit.com", false, false, d("faceit.com")),
        new Brand("github", "github.com", false, false, d("github.com", "githubusercontent.com", "github.dev", "githubassets.com", "github.blog")),
        new Brand("twitter", "x.com", false, false, d("twitter.com", "x.com", "t.co", "twimg.com")),
        new Brand("linkedin", "linkedin.com", false, false, d("linkedin.com", "lnkd.in")),
        new Brand("aliexpress", "aliexpress.com", false, false, d("aliexpress.com", "aliexpress.ru", "alicdn.com", "aliexpress.us")),
        new Brand("gazprombank", "gazprombank.ru", false, false, d("gazprombank.ru", "gpb.ru")),
        new Brand("pochtabank", "pochtabank.ru", false, false, d("pochtabank.ru")),
        new Brand("yoomoney", "yoomoney.ru", false, false, d("yoomoney.ru")),
        new Brand("megafon", "megafon.ru", false, false, d("megafon.ru")),
        new Brand("kaspi", "kaspi.kz", false, false, d("kaspi.kz")),
    };

    /** {@link #canon} of each brand's name and of its site's name, in the order of {@link #BRANDS}. */
    private static final String[] BRAND_CANON = new String[BRANDS.length];
    private static final String[] BRAND_SITE_CANON = new String[BRANDS.length];

    static {
        for (int i = 0; i < BRANDS.length; i++) {
            BRAND_CANON[i] = canon(BRANDS[i].label);
            BRAND_SITE_CANON[i] = canon(siteLabel(BRANDS[i].display));
        }
    }

    /** Big, well known sites (as {@link LinkReport#site}): any page there is the site's own. */
    private static final Set<String> TRUSTED = new HashSet<>(Arrays.asList(
        "wikipedia.org", "wikimedia.org", "wiktionary.org", "reddit.com", "redd.it", "medium.com", "stackoverflow.com", "stackexchange.com",
        "habr.com", "vc.ru", "pikabu.ru", "rbc.ru", "ria.ru", "lenta.ru", "kommersant.ru", "meduza.io", "tass.ru", "interfax.ru", "rt.com",
        "bbc.com", "bbc.co.uk", "cnn.com", "nytimes.com", "theguardian.com", "reuters.com", "bloomberg.com", "forbes.com", "forbes.ru",
        "yahoo.com", "duckduckgo.com", "zoom.us", "dropbox.com", "notion.so", "notion.site", "figma.com", "canva.com", "cloudflare.com",
        "soundcloud.com", "twitch.tv", "kick.com", "pinterest.com", "gitlab.com", "bitbucket.org", "npmjs.com", "pypi.org", "docker.com",
        "kaggle.com", "openai.com", "chatgpt.com", "anthropic.com", "claude.ai", "deepl.com", "ebay.com", "etsy.com", "booking.com",
        "airbnb.com", "tripadvisor.com", "imdb.com", "steamdb.info", "gog.com", "ea.com", "ubisoft.com", "playstation.com", "nintendo.com",
        "riotgames.com", "leagueoflegends.com", "blizzard.com", "battle.net", "minecraft.net", "mojang.com", "bitcoin.org", "ethereum.org",
        "mail.ru", "ok.ru", "dzen.ru", "rutube.ru", "kinopoisk.ru", "2gis.ru", "hh.ru", "cian.ru", "drom.ru", "auto.ru", "lamoda.ru",
        "dns-shop.ru", "mvideo.ru", "eldorado.ru", "citilink.ru", "rzd.ru", "aeroflot.ru", "tutu.ru", "mos.ru", "pochta.ru", "nalog.gov.ru",
        "kremlin.ru", "government.ru", "cbr.ru", "mts.ru", "mtsbank.ru", "beeline.ru", "tele2.ru", "t2.ru", "rt.ru", "rostelecom.ru",
        "domclick.ru", "raiffeisen.ru", "psbank.ru", "rshb.ru", "open.ru", "sovcombank.ru", "otpbank.ru", "rosbank.ru", "qiwi.com",
        "ton.org", "ton.app", "tonviewer.com", "tonscan.org", "tonapi.io", "getgems.io", "ston.fi", "dedust.io", "coinmarketcap.com",
        "coingecko.com", "etherscan.io", "opensea.io", "uniswap.org", "ledger.com", "trezor.io", "phantom.app", "blockchain.com",
        "kraken.com", "htx.com", "huobi.com", "bitget.com", "gate.io", "mexc.com", "archive.org", "substack.com", "patreon.com",
        "boosty.to", "donationalerts.com", "yookassa.ru", "tilda.cc", "vkvideo.ru", "max.ru", "gosuslugi.ru", "wikia.com", "fandom.com",
        "quora.com", "tumblr.com", "vimeo.com", "dailymotion.com", "imgur.com", "giphy.com", "tenor.com", "deviantart.com", "behance.net",
        "dribbble.com", "unsplash.com", "shutterstock.com", "adobe.com", "mozilla.org", "firefox.com", "opera.com", "brave.com",
        "samsung.com", "xiaomi.com", "mi.com", "huawei.com", "oneplus.com", "sony.com", "lg.com", "nvidia.com", "amd.com", "intel.com",
        "jetbrains.com", "android.com", "kotlinlang.org", "python.org", "oracle.com", "ibm.com", "salesforce.com", "slack.com",
        "trello.com", "atlassian.com", "zoom.com", "webex.com", "teams.microsoft.com", "proton.me", "protonmail.com", "tutanota.com",
        "kaspersky.ru", "kaspersky.com", "drweb.ru", "virustotal.com", "f-droid.org", "apkmirror.com", "4pda.to", "rustore.ru",
        "lnk.to", "linktr.ee", "taplink.cc", "music.yandex.ru", "zvuk.com", "deezer.com", "tidal.com", "last.fm", "genius.com",
        "shazam.com", "ivi.ru", "okko.tv", "start.ru", "wink.ru", "premier.one", "kion.ru", "sports.ru", "championat.com", "championat.ru",
        "rambler.ru", "gazeta.ru", "iz.ru", "rg.ru", "mk.ru", "kp.ru", "aif.ru", "fontanka.ru", "e1.ru", "ngs.ru", "sostav.ru", "tproger.ru",
        "itch.io", "epicgames.com", "humblebundle.com", "chess.com", "lichess.org", "duolingo.com", "coursera.org", "udemy.com",
        "stepik.org", "skillbox.ru", "netology.ru", "practicum.yandex.ru", "khanacademy.org", "ted.com", "spotify.com", "music.apple.com",
        "apps.apple.com", "play.google.com", "telegram.org", "t.me", "wallet.tg", "tribute.tg", "tgstat.ru", "tgstat.com", "combot.org",
        "x.com", "threads.net", "threads.com", "bsky.app", "mastodon.social", "snapchat.com", "wechat.com", "line.me", "viber.com",
        "signal.org", "element.io", "matrix.org", "temu.com", "shein.com", "aliexpress.ru", "joom.com", "market.yandex.ru", "megamarket.ru",
        "detmir.ru", "sportmaster.ru", "leroymerlin.ru", "lemanapro.ru", "ikea.com", "hoff.ru", "vkusvill.ru", "perekrestok.ru", "magnit.ru",
        "5ka.ru", "samokat.ru", "kuper.ru", "delivery-club.ru", "eda.yandex.ru", "go.yandex", "taxi.yandex.ru", "uber.com", "bolt.eu"
    ));

    /** Short-link services: the real destination is hidden. */
    private static final Set<String> SHORTENERS = new HashSet<>(Arrays.asList(
        "bit.ly", "bitly.com", "clck.ru", "tinyurl.com", "cutt.ly", "goo.gl", "is.gd", "v.gd", "ow.ly", "buff.ly", "rebrand.ly",
        "shorturl.at", "tiny.cc", "rb.gy", "bl.ink", "s.id", "t.ly", "short.io", "vk.cc", "u.to", "qps.ru", "surl.li", "gg.gg",
        "adf.ly", "sh.st", "ouo.io", "bc.vc", "kutt.it", "ya.cc", "x.gd", "bit.do", "shorturl.asia", "tny.im", "inlnk.ru", "clc.to",
        "cli.re", "l.wl.co", "urlz.fr", "shrtco.de", "t2m.io", "short.gy", "rotf.lol", "tinu.be", "hyperurl.co", "bitly.ws", "trib.al",
        "lnkd.at", "1url.cz", "2.gp", "ux.nu", "zpr.io", "linkd.in", "link.ru", "kortlink.dk", "go.link"
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
        "hashnode.dev", "readthedocs.io", "gitbook.io", "notion.site"
    ));

    /** Two-part public suffixes (example.co.uk): the owner's name is the label before them. */
    private static final Set<String> MULTI_SUFFIXES = new HashSet<>(Arrays.asList(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "com.au", "net.au", "org.au", "co.jp", "ne.jp", "or.jp", "com.br", "com.tr",
        "co.in", "com.cn", "com.hk", "com.tw", "com.sg", "co.kr", "co.il", "co.nz", "co.za", "com.mx", "com.ar", "com.ua", "kiev.ua",
        "org.ua", "net.ua", "pp.ua", "in.ua", "com.ru", "net.ru", "org.ru", "pp.ru", "msk.ru", "spb.ru", "gov.ru", "edu.ru", "com.kz",
        "org.kz", "gov.kz", "com.by", "gov.by", "com.pl", "com.es", "com.de", "co.de", "com.pk", "com.ng", "com.eg", "com.sa", "com.vn",
        "com.ph", "com.my", "co.id", "co.th", "com.co", "com.pe", "com.uy", "com.ec", "com.ve"
    ));

    /** Country endings a brand with {@link Brand#anyCountry} may use (google.de, yandex.kz). */
    private static final Set<String> COUNTRIES = new HashSet<>(Arrays.asList(
        "ru", "ua", "by", "kz", "uz", "kg", "am", "ge", "az", "md", "de", "fr", "it", "es", "pl", "nl", "be", "ch", "at", "se", "no", "fi",
        "dk", "cz", "sk", "pt", "gr", "tr", "il", "in", "jp", "cn", "kr", "br", "ar", "mx", "ca", "au", "nz", "ie", "ro", "hu", "bg", "rs",
        "ee", "lv", "lt", "si", "hr", "ae", "sa", "eg", "za", "sg", "hk", "tw", "th", "vn", "id", "my", "ph", "pk", "cl", "co", "pe",
        "co.uk", "com.au", "com.br", "com.tr", "co.jp", "co.in", "com.mx", "com.ar", "co.kr", "com.ua", "co.il", "co.nz", "co.za",
        "com.sg", "com.hk", "com.tw", "com.kz", "com.by", "com.cn", "com.pl", "com.vn", "co.id", "co.th"
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
        "cc", "ws", "su", "info", "support", "help", "host", "press", "digital", "network", "center", "app", "services", "sbs", "one", "pro"
    ));

    /** Words in an address that bait (combined with a brand they say "fake login page"). */
    private static final String[] LURE_WORDS = {
        "login", "signin", "logon", "verify", "verification", "verif", "secure", "security", "account", "auth", "support", "helpdesk",
        "premium", "gift", "gifts", "free", "bonus", "promo", "prize", "reward", "claim", "airdrop", "giveaway", "nft", "stars", "wallet",
        "unlock", "recovery", "restore", "confirm", "official", "billing", "payment", "payout", "refund", "vote", "voting", "golos",
        "golosovanie", "konkurs", "podarok", "besplatno", "unban", "appeal", "drop", "x2", "double", "invest", "lottery", "lotto", "win",
        "winner", "compensation", "kompensaciya", "vyplata", "viplata", "trade", "skins", "case"
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
        "telegram", "durov", "botfather", "premium", "premiumbot", "spambot", "notoscam", "stickers", "gif", "vid", "pic", "bing",
        "wiki", "imdb", "bold", "youtube", "wallet", "cryptobot", "send", "tips", "telegramtips", "contest", "previews", "designers",
        "botsupport", "botnews", "bottalk", "gamee", "gamebot", "tondev", "toncoin", "ton", "username", "telegramru", "tginfo",
        "jobsbot", "stars", "storiesbot", "verifybot", "telegramarabic", "telegramfrance", "telegrames", "telegramit", "telegramde",
        "tgbetachat", "tgbeta", "durovschat", "fragment", "giftcode", "asmico", "webappbot", "quizbot", "pollbot", "livebot", "like",
        "vote", "music", "foursquare", "telegraph", "gmailbot", "ifttt", "githubbot", "dropboxbot", "tonkeeper", "tonkeeper_bot"
    ));

    /** t.me paths that are not usernames: invites, sticker sets, previews, proxies... */
    private static final Set<String> TELEGRAM_SERVICE_PATHS = new HashSet<>(Arrays.asList(
        "joinchat", "addstickers", "addemoji", "addlist", "addtheme", "setlanguage", "proxy", "socks", "share", "iv", "c", "s",
        "login", "confirmphone", "bg", "invoice", "contact", "boost", "giftcode", "nft", "m", "premium_offer", "stars_topup",
        "stars", "phone", "story", "a"
    ));

    /** Words of a username that promise something or pose as someone: alone they already raise a link. */
    private static final String[] TG_STRONG_WORDS = {
        "official", "unban", "verify", "verification", "recovery", "premium", "gift", "gifts", "stars", "nft", "airdrop", "giveaway",
        "podarok", "podarki", "freestars", "freepremium", "razdacha"
    };
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
        "avito", "vtb", "alfabank", "yandex", "metamask", "trustwallet", "okx"
    };

    // endregion

    private SolegramScamRules() {
    }

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
            final Matcher m = Pattern.compile("[?&]domain=([a-zA-Z0-9_]{3,64})").matcher(u);
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
            r.add(R_USERINFO, userInfo, userInfo.contains(".") ? 55 : 45);
        }
        String host = authority;
        if (host.startsWith("[")) {
            r.host = host.toLowerCase(Locale.ROOT);
            r.displayHost = r.host;
            r.site = r.host;
            r.add(R_IP, r.host, 40);
            return r;
        }
        final int colon = host.lastIndexOf(':');
        if (colon >= 0) {
            final String port = host.substring(colon + 1);
            host = host.substring(0, colon);
            if (!port.isEmpty() && !port.equals("80") && !port.equals("443")) {
                r.add(R_PORT, port, 10);
            }
        }
        host = host.replace('。', '.').replace('．', '.').replace('｡', '.');
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty()) {
            return r;
        }
        String ascii;
        try {
            ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            ascii = host.toLowerCase(Locale.ROOT);
        }
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
            r.add(R_IP, ascii, isPrivateIp(ascii) ? 10 : 40);
            addFile(r, pathAndQuery, false);
            return r;
        }

        final String[] labels = ascii.split("\\.");
        final int siteLabels = siteLabelCount(labels);
        final StringBuilder siteBuilder = new StringBuilder();
        for (int i = Math.max(0, labels.length - siteLabels); i < labels.length; i++) {
            if (siteBuilder.length() > 0) {
                siteBuilder.append('.');
            }
            siteBuilder.append(labels[i]);
        }
        final String site = siteBuilder.toString();
        r.site = site;
        final String suffix = labels.length >= 2 ? site.substring(site.indexOf('.') + 1) : "";
        final String tld = labels[labels.length - 1];
        final String hosting = FREE_HOSTING.contains(suffix) ? suffix : null;
        r.trusted = isTrustedSite(site) || isTrustedSite(ascii) || brandOwned(site);

        // Telegram's own links: the username is what can deceive
        if (site.equals("t.me") || site.equals("telegram.me") || site.equals("telegram.dog")) {
            scoreTelegramPath(r, pathAndQuery);
            textMismatch(r, shown);
            return r;
        }

        if (r.trusted) {
            // a redirect of a big site (google.com/url?q=...) goes where its parameter says
            final String target = redirectTarget(ascii, pathAndQuery);
            if (target != null) {
                final LinkReport inner = scoreLink(target, null);
                for (Reason reason : inner.reasons) {
                    r.add(reason.code, reason.arg, reason.weight);
                }
            }
            addFile(r, pathAndQuery, true);
            textMismatch(r, shown);
            return r;
        }

        if (SHORTENERS.contains(site) || SHORTENERS.contains(ascii)) {
            r.add(R_SHORTENER, site, 25);
        }
        if (hosting != null) {
            r.add(R_FREE_HOSTING, hosting, 15);
        }
        final int riskyWeight = RISKY_TLDS.contains(tld) ? 20 : SOFT_RISKY_TLDS.contains(tld) ? 10 : 0;
        if (riskyWeight > 0) {
            r.add(R_RISKY_TLD, "." + tld, riskyWeight);
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
            } else if (Integer.bitCount(scripts) > 1) {
                r.add(R_MIXED_SCRIPT, label, 60);
                markNonAscii(r, unicode, i);
            } else if (isAscii(skeleton) && (scripts & SCRIPT_LATIN) == 0 && isAscii(unicodeLabels[unicodeLabels.length - 1])) {
                // a word written only with look-alike letters (а, е, о, р, с...) reads as Latin: a disguise
                r.add(R_MIXED_SCRIPT, label, 45);
                markNonAscii(r, unicode, i);
            }
        }

        // a brand's name, a letter off it, or with someone else's ending
        if (!brandSignal) {
            brandSignal = scoreBrandLabel(r, mainLabel, mainUnicode, mainStart, tld, site, unicode);
        }
        // a brand's name in the subdomains of another site
        for (int i = 0; i < labels.length - siteLabels && !brandSignal; i++) {
            if (labels[i].equals("www") || labels[i].equals("m")) {
                continue;
            }
            for (String token : tokens(labels[i])) {
                final Brand b = brandForToken(token, true);
                if (b != null && !b.generic) {
                    r.add(R_BRAND_SUBDOMAIN, b.label, 45);
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
        if (lure != null) {
            r.add(R_LURE_WORDS, lure, brandSignal ? 20 : 10);
        }
        int subdomains = labels.length - siteLabels;
        if (subdomains > 0 && labels[0].equals("www")) {
            subdomains--;
        }
        if (subdomains >= 4 || ascii.length() > 60) {
            r.add(R_DEEP_SUBDOMAINS, null, 15);
        }
        addFile(r, pathAndQuery, false);
        textMismatch(r, shown);
        return r;
    }

    /** Scores the brand-like parts of the site's own name. @return whether a brand was found */
    private static boolean scoreBrandLabel(LinkReport r, String label, String unicodeLabel, int labelStart, String tld, String site, String unicodeHost) {
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
                final int weight = b.generic ? 25 : 60;
                r.add(R_BRAND_TLD, b.display, weight);
                markSite(r, unicodeHost, site);
                return !b.generic || r.has(R_RISKY_TLD);
            }
        }
        for (int k = 0; k < BRANDS.length; k++) {
            final Brand b = BRANDS[k];
            if (canonLabel.equals(BRAND_CANON[k]) && b.label.length() >= 3) {
                r.add(R_LOOKALIKE, b.display, 70);
                markDiff(r, label, b.label, labelStart);
                return true;
            }
        }
        // one or two letters off (insert, drop, swap, replace)
        final String folded = fold(label);
        for (Brand b : BRANDS) {
            if (b.label.length() < 6 || contains(b.exclude, label)) {
                continue;
            }
            final int distance = distance(folded, b.label);
            if (distance == 1 || distance == 2 && b.label.length() >= 9) {
                r.add(R_LOOKALIKE, b.display, distance == 1 ? 60 : 55);
                markDiff(r, label, b.label, labelStart);
                return true;
            }
        }
        // the brand as a word of a longer name: telegram-premium, sberbank24-online, steam-gift
        final List<String> tokens = tokens(label);
        for (String token : tokens) {
            final Brand b = brandForToken(token, true);
            if (b != null) {
                r.add(R_BRAND_IN_DOMAIN, b.label, b.generic ? 20 : 35);
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
                r.add(R_BRAND_IN_DOMAIN, b.label, 30);
                markRange(r, labelStart, labelStart + unicodeLabel.length());
                return true;
            }
        }
        return false;
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
                if (BRANDS[k].label.length() >= 4 && c.equals(BRAND_CANON[k])) {
                    return BRANDS[k];
                }
            }
            // a letter off a long brand name, as a word: telegrarn-premium
            final String folded = fold(token);
            for (Brand b : BRANDS) {
                if (b.label.length() >= 7 && !contains(b.exclude, token) && distance(folded, b.label) == 1) {
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
        if (path.isEmpty()) {
            r.trusted = true;
            return;
        }
        final String[] parts = path.split("/");
        String first = parts[0];
        if (first.startsWith("@")) {
            first = first.substring(1);
        }
        if (first.startsWith("+") || first.startsWith("$") || TELEGRAM_SERVICE_PATHS.contains(first.toLowerCase(Locale.ROOT))) {
            r.trusted = true;
            r.telegramService = true;
            if (first.equalsIgnoreCase("s") && parts.length > 1) {
                scoreTelegramUsername(r, parts[1].toLowerCase(Locale.ROOT));
            }
            return;
        }
        r.trusted = true;
        scoreTelegramUsername(r, first.toLowerCase(Locale.ROOT));
    }

    private static void scoreTelegramUsername(LinkReport r, String username) {
        if (!username.matches("[a-z0-9_]{3,64}")) {
            return;
        }
        r.telegramUsername = username;
        if (OFFICIAL_USERNAMES.contains(username)) {
            return;
        }
        final int weight = usernameRisk(username);
        if (weight > 0) {
            r.add(R_TG_IMPERSONATION, "@" + username, weight);
        }
    }

    /** How much a Telegram username poses as staff, an official account or a giveaway (0 = not at all). */
    public static int usernameRisk(String username) {
        final String u = username.toLowerCase(Locale.ROOT);
        if (OFFICIAL_USERNAMES.contains(u)) {
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
        final boolean medium = containsAny(u, TG_MEDIUM_WORDS);
        int weight;
        if (telegram && (strong || medium)) {
            weight = 60;
        } else if (brand && (strong || medium)) {
            weight = 45;
        } else if (telegram) {
            weight = 30; // named after Telegram or its founder, but not an official account
        } else if (strong) {
            weight = 30;
        } else if (medium) {
            weight = 15;
        } else {
            weight = 0;
        }
        if (weight >= 30 && u.endsWith("bot")) {
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
    private static void textMismatch(LinkReport r, String shown) {
        if (shown == null || r.host == null) {
            return;
        }
        String s = stripInvisible(shown).trim();
        if (s.isEmpty() || s.length() > 200) {
            return;
        }
        if (s.startsWith("@") && s.length() > 1 && s.substring(1).matches("[A-Za-z0-9_]{3,64}")) {
            final String shownName = s.substring(1).toLowerCase(Locale.ROOT);
            if (r.telegramUsername == null || !r.telegramUsername.equals(shownName)) {
                r.add(R_TEXT_MISMATCH, s, 40);
            }
            return;
        }
        if (s.contains(" ") || !s.contains(".")) {
            return;
        }
        final Matcher m = SHOWN_HOST.matcher(s);
        if (!m.matches()) {
            return;
        }
        String shownHost = m.group(1);
        try {
            shownHost = IDN.toASCII(shownHost, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            shownHost = shownHost.toLowerCase(Locale.ROOT);
        }
        final String[] labels = shownHost.split("\\.");
        if (labels.length < 2 || !labels[labels.length - 1].matches("[a-z]{2,24}|xn--[a-z0-9-]+")) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        final int count = siteLabelCount(labels);
        for (int i = Math.max(0, labels.length - count); i < labels.length; i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(labels[i]);
        }
        final String shownSite = sb.toString();
        if (shownSite.equals(r.site) || sameOwner(shownSite, r.site)) {
            return;
        }
        final boolean shownIsBig = isTrustedSite(shownSite) || brandOwned(shownSite);
        if (r.trusted) {
            r.add(R_TEXT_MISMATCH, shownSite, 15);
        } else {
            r.add(R_TEXT_MISMATCH, shownSite, shownIsBig ? 65 : 50);
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
        // the same name with another country ending (google.ru and google.com)
        final String la = a.substring(0, a.indexOf('.') < 0 ? a.length() : a.indexOf('.'));
        final String lb = b.substring(0, b.indexOf('.') < 0 ? b.length() : b.indexOf('.'));
        return la.equals(lb) && (isTrustedSite(a) || brandOwned(a)) && (isTrustedSite(b) || brandOwned(b));
    }

    private static String redirectTarget(String host, String pathAndQuery) {
        final String lower = pathAndQuery.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("/url?") || lower.startsWith("/away.php") || lower.startsWith("/redirect") || lower.startsWith("/l.php")
            || lower.startsWith("/away?") || lower.startsWith("/link?"))) {
            return null;
        }
        final Matcher m = Pattern.compile("[?&](?:q|url|u|to|target|dest|link)=([^&#]+)").matcher(pathAndQuery);
        if (!m.find()) {
            return null;
        }
        final String value = percentDecode(m.group(1));
        return value.toLowerCase(Locale.ROOT).startsWith("http") ? value : null;
    }

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
            r.add(R_FILE, "." + ext, trusted ? 15 : weight);
        }
    }

    /** The user's own list of sites to trust (as {@link LinkReport#site} or a host). Set by the app. */
    private static volatile Set<String> userTrusted = new HashSet<>();

    public static void setUserTrusted(Set<String> sites) {
        userTrusted = sites == null ? new HashSet<>() : sites;
    }

    public static boolean isTrustedSite(String site) {
        return site != null && (TRUSTED.contains(site) || userTrusted.contains(site));
    }

    /** The site part of a host (the owner's name): what the "trust this site" list keeps. */
    public static String siteOf(String host) {
        if (host == null) {
            return null;
        }
        String h = host.toLowerCase(Locale.ROOT);
        try {
            h = IDN.toASCII(h, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
        } catch (Exception ignore) {
        }
        if (isIpAddress(h)) {
            return h;
        }
        final String[] labels = h.split("\\.");
        final int count = siteLabelCount(labels);
        final StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, labels.length - count); i < labels.length; i++) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(labels[i]);
        }
        return sb.toString();
    }

    private static int siteLabelCount(String[] labels) {
        if (labels.length < 2) {
            return labels.length;
        }
        final String last2 = labels[labels.length - 2] + "." + labels[labels.length - 1];
        if (labels.length >= 3 && (MULTI_SUFFIXES.contains(last2) || FREE_HOSTING.contains(last2))) {
            return 3;
        }
        if (labels.length >= 4) {
            final String last3 = labels[labels.length - 3] + "." + last2;
            if (FREE_HOSTING.contains(last3)) {
                return 4;
            }
        }
        return 2;
    }

    // endregion

    // region Message text

    private static final String W = "[\\p{L}\\p{N}_]";

    private static Pattern p(String regex) {
        return Pattern.compile(regex.replace("\\w", W));
    }

    private static final Object[][] TEXT_RULES = {
        {R_TEXT_GIFT, 20, p("(бесплатн\\w*|в подарок|подар\\w*|дарим|разда(ча|ем|ют|ет)\\w*|розыгрыш\\w*|получи(те)?|забери(те)?|халяв\\w*|free|gift(ed|s)?|giveaway|claim)[^.!?\\n]{0,40}(premium|премиум|stars|звезд\\w*|nft|нфт|telegram|телеграм\\w*|подписк\\w*|подарк\\w*)"), new String[]{"premium", "премиум", "stars", "звезд", "nft", "нфт", "telegram", "телеграм", "подписк", "подарк"}},
        {R_TEXT_GIFT, 20, p("(premium|премиум|stars|звезд\\w*|nft|нфт)[^.!?\\n]{0,40}(бесплатн\\w*|в подарок|даром|free|gift|giveaway|за подписку)"), new String[]{"premium", "премиум", "stars", "звезд", "nft", "нфт"}},
        {R_TEXT_VERIFY, 20, p("(подтверд\\w*|верифиц\\w*|верификац\\w*|пройдите проверку|пройди проверку|авторизуй\\w*)[^.!?\\n]{0,30}(аккаунт\\w*|учетн\\w*|личност\\w*|профил\\w*|номер\\w*|кошел\\w*|страниц\\w*)"), new String[]{"подтверд", "верифи", "провер", "авториз"}},
        {R_TEXT_VERIFY, 20, p("verify (your )?(account|identity|wallet|number|profile)|confirm (your )?(account|identity|wallet)|account verification|verification (is )?required"), new String[]{"verif", "confirm"}},
        {R_TEXT_BLOCK, 20, p("(аккаунт|учетн\\w* запис\\w*|страниц\\w*|профил\\w*|карт\\w*|кошел\\w*)[^.!?\\n]{0,50}(заблокир\\w*|удален\\w*|будет удал\\w*|огранич\\w*|замороз\\w*|заморож\\w*)"), new String[]{"заблокир", "удален", "удал", "огранич", "замороз", "заморож"}},
        {R_TEXT_BLOCK, 20, p("(account|wallet|card|profile)[^.!?\\n]{0,30}(will be|has been|is being|may be|is)[^.!?\\n]{0,10}(suspended|blocked|deleted|restricted|locked|banned|frozen|terminated)"), new String[]{"suspend", "block", "delet", "restrict", "lock", "bann", "frozen", "terminat"}},
        {R_TEXT_CODE, 30, p("(никому не (сообщайте|говорите|передавайте|показывайте)|не сообщайте (его )?никому)[^\\n]{0,40}код"), new String[]{"никому"}},
        {R_TEXT_CODE, 30, p("(отправ\\w*|пришл\\w*|скин\\w*|продиктуй\\w*|сообщи\\w*|напиши\\w*|перешли\\w*|назови\\w*|скажи\\w*)[^.!?\\n]{0,30}(код\\w*|пароль|cvv|cvc|смс)|код\\w* из (смс|sms|сообщени\\w*|телеграм\\w*)"), new String[]{"код", "пароль", "cvv", "cvc", "смс"}},
        {R_TEXT_CODE, 30, p("(send|share|give|tell|forward)( me| us)?[^.!?\\n]{0,20}(the |your )?(code|password|cvv|sms)|(login|verification|confirmation|one-time) code"), new String[]{"code", "password", "cvv", "sms"}},
        {R_TEXT_CRYPTO, 25, p("(^|[^\\p{L}\\p{N}])(x2|х2|x10|х10)([^\\p{L}\\p{N}]|$)|удво\\w*|double (your|the)|airdrop|аирдроп|эйрдроп|эирдроп|seed[- ]?phrase|сид[- ]?фраз\\w*|мнемони\\w*|секретн\\w* фраз\\w*|private key|приватн\\w* ключ\\w*|connect (your )?wallet|подключ\\w* кошел\\w*"), new String[]{"x2", "х2", "x10", "х10", "удво", "double", "airdrop", "дроп", "seed", "сид", "мнемони", "фраз", "private key", "ключ", "wallet", "кошел"}},
        {R_TEXT_CRYPTO, 25, p("(отправ\\w*|send|переведи\\w*)[^.!?\\n]{0,25}[0-9]+[^.!?\\n]{0,6}(usdt|ton|btc|eth|тон)[^.!?\\n]{0,40}(получ\\w*|верн\\w*|receive|get back|back)"), new String[]{"usdt", "ton", "btc", "eth", "тон"}},
        {R_TEXT_MONEY, 20, p("(срочно|urgent\\w*|очень нужно|выручи\\w*)[^.!?\\n]{0,50}(деньг\\w*|денег|переве\\w*|перевод\\w*|займ\\w*|одолж\\w*|скин\\w*|money|transfer|send)"), new String[]{"срочно", "urgent", "очень нужно", "выручи"}},
        {R_TEXT_MONEY, 20, p("(займи|одолжи|переведи|скинь|кинь)( мне)?[^.!?\\n]{0,30}([0-9]|руб|₽|денег|деньг|тыс|косар)|(lend me|can you send|send me|could you send)[^.!?\\n]{0,25}(money|\\$|usd|dollars|[0-9])"), new String[]{"займи", "одолжи", "переведи", "скинь", "кинь", "lend me", "send"}},
        {R_TEXT_MONEY, 20, p("(номер (вашей |твоей )?карты|данные (вашей |твоей )?карты|card number|cvv|cvc|срок действия карты)"), new String[]{"карт", "card", "cvv", "cvc"}},
        {R_TEXT_VOTE, 25, p("(проголосу\\w*|голосу\\w*|голос за|голосовани\\w*|vote for|voting)[^\\n]{0,60}(племянниц\\w*|дочк\\w*|доч\\w*|сын\\w*|ребен\\w*|внучк\\w*|подруг\\w*|крестниц\\w*|niece|nephew|daughter|son|kid|конкурс\\w*|contest)"), new String[]{"голос", "vote", "voting"}},
        {R_TEXT_JOB, 20, p("(легк\\w*|быстр\\w*|пассивн\\w*|дополнительн\\w*)[^.!?\\n]{0,15}(заработ\\w*|деньг\\w*|доход\\w*)|заработ\\w* от [0-9]+|easy money|(earn|make) \\$?[0-9]+[^.!?\\n]{0,25}(day|daily|week|hour)"), new String[]{"заработ", "доход", "деньг", "money", "earn", "make"}},
        {R_TEXT_JOB, 20, p("[0-9]+ ?(₽|руб\\w*|\\$|usdt|долл\\w*|тыс\\w*)[^.!?\\n]{0,15}в (день|час|неделю)|работа без опыта|удаленн\\w* работ\\w*[^.!?\\n]{0,40}(без опыта|от [0-9]+)"), new String[]{"в день", "в час", "в неделю", "без опыта"}},
        {R_TEXT_PRIZE, 20, p("(вы выиграли|ты выиграл\\w*|вы стали победител\\w*|you (have )?won|you are (the |a )?winner|ваш приз|your prize|заберите приз|забери приз|claim your (prize|reward))"), new String[]{"выиграл", "победител", "won", "winner", "приз", "prize", "reward"}},
        {R_TEXT_URGENT, 5, p("(в течение|within|осталось|only) [0-9]+ (час\\w*|минут\\w*|hours?|minutes?)|срочно|немедленно|urgent|immediately|act now"), new String[]{"в течение", "within", "осталось", "only", "срочно", "немедленно", "urgent", "immediately", "act now"}},
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
                t.reasons.add(new Reason(code, null, weight));
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

    /**
     * The score of a link inside a message: the address, plus the message's signs when the link goes somewhere
     * unknown (a big site stays a big site whatever the text says).
     */
    public static int combinedScore(LinkReport link, TextReport text) {
        if (text == null || text.score == 0) {
            return link.score;
        }
        if (link.trusted && link.telegramUsername == null && !link.telegramService) {
            return link.score;
        }
        if (link.telegramUsername != null && OFFICIAL_USERNAMES.contains(link.telegramUsername)) {
            return link.score;
        }
        return link.score + text.score;
    }

    // endregion

    // region Accounts

    /** What is known about a stranger (filled by the app from Telegram's data). */
    public static final class Account {
        public boolean scam, fake, verified, official, bot, contact, deleted;
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

    public static final class AccountReport {
        public int score;
        public final ArrayList<Reason> reasons = new ArrayList<>();
        /** Telegram itself marked it (scam / fake): always dangerous. */
        public boolean flagged;

        void add(int code, String arg, int weight) {
            reasons.add(new Reason(code, arg, weight));
            score += weight;
        }

        public List<Reason> sortedReasons() {
            final ArrayList<Reason> list = new ArrayList<>(reasons);
            java.util.Collections.sort(list, (a, b) -> b.weight - a.weight);
            return list;
        }

        @Override
        public String toString() {
            return score + " " + sortedReasons();
        }
    }

    private static final String[][] NAME_WORDS = {
        // word, weight
        {"durov", "40"}, {"дуров", "40"},
        {"telegram", "35"}, {"телеграм", "35"}, {"telegrarn", "35"}, {"support", "35"}, {"поддержк", "35"}, {"служба", "30"},
        {"администрац", "35"}, {"administrator", "35"}, {"администратор", "30"}, {"admin", "30"}, {"админ", "30"},
        {"модератор", "30"}, {"moderator", "30"}, {"official", "30"}, {"официальн", "30"}, {"security", "30"}, {"безопасност", "30"},
        {"уведомлен", "25"}, {"notification", "25"}, {"верификац", "25"}, {"verification", "25"},
        {"госуслуг", "30"}, {"gosuslug", "30"}, {"сбербанк", "30"}, {"sberbank", "30"}, {"тинькоф", "30"}, {"tinkoff", "30"},
        {"т-банк", "30"}, {"tbank", "30"}, {"втб", "25"}, {"альфа-банк", "30"}, {"альфабанк", "30"}, {"alfabank", "30"},
        {"центробанк", "30"}, {"центральный банк", "30"}, {"цб рф", "30"}, {"wildberries", "25"}, {"вайлдберриз", "25"},
        {"fragment", "20"}, {"binance", "25"}, {"bybit", "25"}, {"wallet", "20"}, {"кошелек", "15"}, {"банк", "15"}, {"bank", "15"},
        {"полиция", "25"}, {"фсб", "30"}, {"мвд", "25"}, {"следователь", "25"},
    };

    private static final String[] CHECKMARKS = {"✅", "✔", "☑", "✓", "🗸", "verified", "верифицирован"};

    /** Scores a stranger. @param now unix seconds */
    public static AccountReport scoreAccount(Account a, long now) {
        final AccountReport r = new AccountReport();
        if (a == null) {
            return r;
        }
        if (a.scam) {
            r.flagged = true;
            r.add(A_SCAM, null, 100);
        }
        if (a.fake) {
            r.flagged = true;
            r.add(A_FAKE, null, 100);
        }
        if (r.flagged) {
            return r;
        }
        if (a.verified || a.official || a.contact || a.deleted) {
            return r;
        }
        final String name = ((a.firstName == null ? "" : a.firstName) + " " + (a.lastName == null ? "" : a.lastName)).trim();
        final String lowerName = name.toLowerCase(Locale.ROOT).replace('ё', 'е');
        final String skeletonName = skeleton(lowerName);
        String nameWord = null;
        int nameWeight = 0, nameWords = 0;
        for (String[] w : NAME_WORDS) {
            if (lowerName.contains(w[0]) || skeletonName.contains(w[0])) {
                nameWords++;
                if (Integer.parseInt(w[1]) > nameWeight) {
                    nameWord = w[0];
                    nameWeight = Integer.parseInt(w[1]);
                }
            }
        }
        if (nameWord != null) {
            // "Служба безопасности Сбербанка": a staff word and a brand say more than one of them
            r.add(A_NAME_IMPERSONATION, nameWord, nameWeight + Math.min(20, 10 * (nameWords - 1)));
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
        if (usernameWeight >= 30) {
            // a name and a username saying the same count a bit more than one of them
            r.add(A_USERNAME_IMPERSONATION, usernameArg, nameWord != null ? 15 : Math.min(40, usernameWeight));
        }
        for (String mark : CHECKMARKS) {
            if (lowerName.contains(mark)) {
                r.add(A_FAKE_CHECKMARK, null, 30);
                break;
            }
        }
        if (a.contactNames != null && !a.contactNames.isEmpty()) {
            final String key = normalizeName(a.firstName, a.lastName);
            final String contact = key.length() >= 5 ? a.contactNames.get(key) : null;
            if (contact != null) {
                final boolean full = key.contains(" ");
                r.add(A_CONTACT_NAME, contact, full && key.length() >= 8 ? 45 : 25);
            }
        }
        if (a.commonChats == 0) {
            r.add(A_NO_COMMON_GROUPS, null, 10);
        }
        if (a.usernames == null || a.usernames.length == 0) {
            r.add(A_NO_USERNAME, null, 5);
        }
        if (!a.hasPhoto) {
            r.add(A_NO_PHOTO, null, 5);
        }
        int monthsOld = -1;
        if (a.registrationMonth != null && !a.bot) {
            monthsOld = monthsSince(a.registrationMonth, now);
            if (monthsOld >= 0 && monthsOld <= 1) {
                r.add(A_NEW_ACCOUNT, a.registrationMonth, 30);
            } else if (monthsOld >= 0 && monthsOld <= 3) {
                r.add(A_NEW_ACCOUNT, a.registrationMonth, 20);
            } else if (monthsOld >= 0 && monthsOld <= 6) {
                r.add(A_NEW_ACCOUNT, a.registrationMonth, 10);
            }
        }
        final int nameDays = a.nameChangeDate > 0 ? (int) Math.max(0, (now - a.nameChangeDate) / 86400L) : -1;
        final int photoDays = a.photoChangeDate > 0 ? (int) Math.max(0, (now - a.photoChangeDate) / 86400L) : -1;
        if (nameDays >= 0 && nameDays <= 7) {
            r.add(A_NAME_CHANGED, String.valueOf(nameDays), 20);
        } else if (nameDays >= 0 && nameDays <= 30) {
            r.add(A_NAME_CHANGED, String.valueOf(nameDays), 12);
        }
        if (photoDays >= 0 && photoDays <= 7) {
            r.add(A_PHOTO_CHANGED, String.valueOf(photoDays), 12);
        } else if (photoDays >= 0 && photoDays <= 30) {
            r.add(A_PHOTO_CHANGED, String.valueOf(photoDays), 6);
        }
        if (monthsOld >= 0 && monthsOld <= 3 && (nameDays >= 0 && nameDays <= 30 || photoDays >= 0 && photoDays <= 30)) {
            r.add(A_FRESH_AND_CHANGED, null, 15);
        }
        if (a.wroteFirst && a.firstMessagesLinks > 0) {
            r.add(A_FIRST_LINKS, null, 15);
        }
        if (a.firstMessages != null) {
            final TextReport t = scoreText(a.firstMessages);
            for (Reason reason : t.reasons) {
                r.reasons.add(reason);
            }
            r.score += a.wroteFirst ? t.score : t.score / 2;
        }
        return r;
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
