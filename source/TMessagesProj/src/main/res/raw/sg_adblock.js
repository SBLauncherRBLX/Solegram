// Solegram ad blocker: hides ad blocks on pages and skips YouTube ads.
// Runs at document start and again once the page is shown; a later run only updates the first one.
(function (config) {
    if (window !== window.top) {
        return;
    }
    var on = config.on;
    var allowed = config.allowed;
    if (on && allowed.length) {
        // the site and its parent domains, compared with === only: a reused window may have changed built-ins
        var host = location.hostname;
        var n = host.length;
        for (var i = 0; i < n && on; i++) {
            if (i > 0 && host[i - 1] !== '.') {
                continue;
            }
            var part = '';
            for (var j = i; j < n; j++) {
                part += host[j];
            }
            for (var k = 0; k < allowed.length; k++) {
                if (allowed[k] === part) {
                    on = false;
                    break;
                }
            }
        }
    }
    var youtubePage = /(^|\.)youtube\.com$/.test(location.hostname);
    var cosmetic = !!(on && config.cosmetic);
    var youtube = !!(on && config.youtube && youtubePage);
    var KEY = '__sgAdBlock';
    var state = window[KEY];
    if (!state) {
        if (!cosmetic && !youtube) {
            return;
        }
        state = create();
        try {
            Object.defineProperty(window, KEY, {value: state});
        } catch (e) {
            return;
        }
    }
    if (typeof state.update === 'function') {
        state.update(cosmetic, youtube);
    }

    function create() {
        // only containers that hold nothing but ads, nothing that could match page content
        var CSS = 'ins.adsbygoogle,.adsbygoogle,[id^="google_ads_iframe"],[id^="div-gpt-ad"],[data-ad-slot],[data-google-query-id],' +
            'iframe[src*="doubleclick.net"],iframe[src*="googlesyndication.com"],iframe[src*="amazon-adsystem.com"],' +
            '.ad-banner,.ad_banner,.advertisement,.ad-container,.ad-slot,.adslot,amp-ad,amp-embed[type="taboola"],' +
            '[id^="taboola-"],.trc_related_container,.OUTBRAIN,.ob-widget,[id^="yandex_rtb"],.yandex-rtb,[id^="adfox_"],[id^="AdFox_"]' +
            '{display:none!important}';
        var YOUTUBE_CSS = 'ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-companion-ad-renderer,ad-slot-renderer,' +
            'ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-promoted-sparkles-web-renderer,ytd-display-ad-renderer,' +
            '.ytp-ad-overlay-container,#player-ads,ytm-statement-banner-renderer{display:none!important}' +
            // a separate rule: an older WebView without :has() drops only this one
            'ytm-rich-item-renderer:has(ad-slot-renderer),ytd-rich-item-renderer:has(ytd-ad-slot-renderer){display:none!important}';
        var SKIP_BUTTONS = '.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,button.ytp-ad-skip-button-container';
        // parts of the player that are only there while an ad plays
        var AD_PARTS = '.ytp-ad-player-overlay,.ytp-ad-player-overlay-layout,.ytp-ad-text,.ytp-ad-preview-container,' +
            '.ytp-ad-skip-button-container,.ytp-ad-skip-button-slot,.ytp-ad-badge,.ytp-ad-simple-ad-badge,.ytp-ad-duration-remaining';

        var sheet = null; // a constructed sheet: a page's CSP does not block it and it is not part of the DOM
        var style = null; // a <style> where constructed sheets are missing
        var css = '';
        var waiting = false;
        var timer = 0;
        var youtubeOn = false;
        var muted = false;
        var wasMuted = false;
        var adSrc = null; // the video an ad was seen on, null while no ad shows

        function attach() {
            if (sheet) {
                try {
                    var sheets = Array.prototype.slice.call(document.adoptedStyleSheets);
                    if (sheets.indexOf(sheet) < 0) {
                        sheets.push(sheet);
                        document.adoptedStyleSheets = sheets;
                    }
                    return true;
                } catch (e) {
                    // the sheet belongs to an earlier document of this window
                    sheet = null;
                    style = document.createElement('style');
                    style.textContent = css;
                }
            }
            if (!style) {
                return true;
            }
            // head when there is one; another root only once the page is parsed (SVG, XML)
            var root = document.head || (document.readyState !== 'loading' ? document.documentElement : null);
            if (!root) {
                return false;
            }
            if (!style.isConnected) {
                root.appendChild(style);
            }
            return true;
        }

        function attachLater() {
            if (waiting) {
                return;
            }
            waiting = true;
            var observer = new MutationObserver(function () {
                if (attach()) {
                    done();
                }
            });
            function done() {
                waiting = false;
                observer.disconnect();
                document.removeEventListener('DOMContentLoaded', onLoaded);
            }
            function onLoaded() {
                attach();
                done();
            }
            observer.observe(document, {childList: true, subtree: true});
            document.addEventListener('DOMContentLoaded', onLoaded);
        }

        function removeCss() {
            if (sheet) {
                var sheets = Array.prototype.slice.call(document.adoptedStyleSheets);
                var index = sheets.indexOf(sheet);
                if (index >= 0) {
                    sheets.splice(index, 1);
                    document.adoptedStyleSheets = sheets;
                }
                sheet = null;
            }
            if (style && style.parentNode) {
                style.parentNode.removeChild(style);
            }
            style = null;
            css = '';
        }

        function setCss(text) {
            if (!text) {
                removeCss();
                return;
            }
            if (text !== css) {
                removeCss();
                css = text;
                try {
                    if ('adoptedStyleSheets' in document) {
                        sheet = new CSSStyleSheet();
                        sheet.replaceSync(text);
                    }
                } catch (e) {
                    sheet = null;
                }
                if (!sheet) {
                    style = document.createElement('style');
                    style.textContent = text;
                }
            }
            if (!attach()) {
                attachLater();
            }
        }

        function player() {
            return document.getElementById('movie_player') || document.querySelector('.html5-video-player');
        }

        function restoreSound(video) {
            if (muted) {
                muted = false;
                if (video) {
                    video.muted = wasMuted;
                }
            }
        }

        // Jumps to the end of the ad, only on a video an earlier check saw as an ad too: right as an ad
        // starts or ends, the player may still hold the real video.
        function seekPastAd(p, video) {
            var src = video.currentSrc || video.src || '';
            if (!src || !p.querySelector(AD_PARTS)) {
                adSrc = null;
                return;
            }
            if (src !== adSrc) {
                adSrc = src;
                return;
            }
            var duration = video.duration;
            if (isFinite(duration) && duration > 0 && video.currentTime < duration - 0.1) {
                try {
                    video.currentTime = duration;
                } catch (e) {
                }
            }
        }

        function tick() {
            timer = 0;
            if (!youtubeOn) {
                return;
            }
            var p = player();
            var video = p ? p.querySelector('video') : null;
            if (video) {
                var classes = p.classList;
                if (classes.contains('ad-showing') || classes.contains('ad-interrupting')) {
                    if (!muted) {
                        muted = true;
                        wasMuted = video.muted;
                    }
                    video.muted = true;
                    seekPastAd(p, video);
                    var skip = p.querySelector(SKIP_BUTTONS);
                    if (skip) {
                        skip.click();
                    }
                } else {
                    adSrc = null;
                    restoreSound(video);
                }
            }
            // YouTube changes pages without loading new documents
            attach();
            // fast only while there is a video to watch
            timer = setTimeout(tick, video ? 250 : 1000);
        }

        return {
            update: function (cosmeticOn, youtubeOnNow) {
                youtubeOn = youtubeOnNow;
                setCss((cosmeticOn ? CSS : '') + (youtubeOn ? YOUTUBE_CSS : ''));
                if (youtubeOn) {
                    if (!timer) {
                        tick();
                    }
                } else {
                    if (timer) {
                        clearTimeout(timer);
                        timer = 0;
                    }
                    var p = player();
                    adSrc = null;
                    restoreSound(p ? p.querySelector('video') : null);
                }
            }
        };
    }
})($CONFIG$);
