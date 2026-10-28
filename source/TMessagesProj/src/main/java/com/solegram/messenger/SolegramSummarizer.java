/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.text.TextUtils;
import android.util.LruCache;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI summary of long messages without Telegram Premium.
 * Telegram's own summary is used while the server gives it; otherwise (no summary for that message, or the
 * Premium limit) Solegram makes it: by default with Gemini Nano right on the phone where it exists
 * ({@link SolegramGeminiNano}), else with a free AI service (Pollinations, no key); or with the user's own
 * OpenAI-compatible service. Secret chats are never summarized.
 */
public final class SolegramSummarizer {

    public static final int PROVIDER_FREE = 0;
    public static final int PROVIDER_OWN = 1;
    /** Gemini Nano on the phone when it can, otherwise the free service; the default. */
    public static final int PROVIDER_AUTO = 2;
    /** Gemini Nano only: the text never leaves the phone, and there is no summary where Nano is missing. */
    public static final int PROVIDER_NANO = 3;

    /** Who made a summary (shown beside it). */
    public static final int SOURCE_NANO = 1;
    public static final int SOURCE_FREE = 2;
    public static final int SOURCE_OWN = 3;

    /** Free, keyless, OpenAI-compatible; "private" keeps requests out of its public feed. */
    private static final String FREE_URL = "https://text.pollinations.ai/openai";
    private static final String FREE_MODEL = "openai";
    private static final String DEFAULT_OWN_MODEL = "gpt-4o-mini";
    /** Shorter messages are read faster than a summary. */
    public static final int MIN_LENGTH = 200;
    private static final int MAX_INPUT = 8000;
    private static final int MAX_OUTPUT = 1500;
    /** A few bullet points; also keeps a confused small model from rambling on. */
    private static final int NANO_OUTPUT_TOKENS = 400;
    /** Very long texts go to Nano in at most this many parts (each part is one more request against its quota). */
    private static final int NANO_MAX_PARTS = 4;

    // one request at a time: the free service allows only a few per minute
    private static final ExecutorService pool = Executors.newSingleThreadExecutor();
    private static final LruCache<String, String> cache = new LruCache<>(200);
    // summary text -> SOURCE_*: the bubble and the feed say who made it
    private static final LruCache<String, Integer> sources = new LruCache<>(300);
    // languages Gemini Nano answered in a wrong one (code -> times): after two misses they go to the service directly
    private static final java.util.Map<String, Integer> nanoLanguageMisses = new java.util.concurrent.ConcurrentHashMap<>();

    private SolegramSummarizer() {
    }

    public static boolean enabled() {
        return SolegramConfig.aiSummary;
    }

    /** A message Solegram can summarize (long text, not a secret chat, not an ad, not one's own). */
    public static boolean canSummarize(MessageObject m) {
        if (!enabled() || m == null || m.messageOwner == null || m.getId() <= 0) {
            return false;
        }
        final String text = m.messageOwner.message;
        if (TextUtils.isEmpty(text) || text.length() < MIN_LENGTH) {
            return false;
        }
        if (DialogObject.isEncryptedDialog(m.getDialogId()) || m.isSponsored() || m.isRestrictedMessage || m.isOutOwner()) {
            return false;
        }
        // protected content (no forwarding/copying) is not handed to an outside service either
        if (m.messageOwner.noforwards || MessagesController.getInstance(m.currentAccount).isPeerNoForwards(m.getDialogId())) {
            return false;
        }
        return m.type == MessageObject.TYPE_TEXT || m.type == MessageObject.TYPE_PHOTO || m.type == MessageObject.TYPE_VIDEO
            || m.type == MessageObject.TYPE_FILE || m.type == MessageObject.TYPE_MUSIC;
    }

    /** The summary button beside a long message (in any chat, as Telegram shows it on long channel posts). */
    public static boolean offersButton(MessageObject m) {
        return SolegramConfig.aiSummaryButton && canSummarize(m);
    }

    /** A Solegram summary was asked for or made for this message: its bubble then has the summary controls. */
    public static boolean hasOwnSummary(MessageObject m) {
        return canSummarize(m) && (m.messageOwner.summarizedOpen || m.messageOwner.summaryText != null || m.messageOwner.translatedSummaryText != null);
    }

    // messages whose summary the user just asked for (tap); a summary is never fetched without one
    private static final java.util.Set<String> requested = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static String requestKey(MessageObject m) {
        return m.currentAccount + "_" + m.getDialogId() + "_" + m.getId();
    }

    /** The user tapped to open the summary of this message. */
    public static void markRequested(MessageObject m) {
        if (m != null) {
            requested.add(requestKey(m));
        }
    }

    /** Whether the user asked for this summary (and uses that request up); refreshes after edits etc. do not count. */
    public static boolean consumeRequest(MessageObject m) {
        return m != null && requested.remove(requestKey(m));
    }

    public static boolean usesOwnService() {
        return SolegramConfig.aiProvider == PROVIDER_OWN;
    }

    /** Gemini Nano is tried first (auto) or only (on device). */
    public static boolean usesNano() {
        return SolegramConfig.aiProvider == PROVIDER_AUTO || SolegramConfig.aiProvider == PROVIDER_NANO;
    }

    /** Who made this summary ("Gemini Nano · on device", ...), or null for Telegram's own or an unknown one. */
    @Nullable
    public static String sourceLabel(@Nullable CharSequence summary) {
        if (summary == null) {
            return null;
        }
        final Integer source = sources.get(summary.toString());
        if (source == null) {
            return null;
        }
        switch (source) {
            case SOURCE_NANO:
                return LocaleController.getString(R.string.SG_AiSourceNano);
            case SOURCE_OWN:
                return LocaleController.getString(R.string.SG_AiSourceOwn);
            default:
                return LocaleController.getString(R.string.SG_AiSourceFree);
        }
    }

    /** {@link #sourceLabel(CharSequence)} of the summary this message shows. */
    @Nullable
    public static String sourceLabel(@Nullable MessageObject m) {
        if (m == null || m.messageOwner == null) {
            return null;
        }
        final org.telegram.tgnet.TLRPC.TL_textWithEntities shown = m.translated && m.messageOwner.translatedSummaryText != null
            ? m.messageOwner.translatedSummaryText : m.messageOwner.summaryText;
        return shown == null ? null : sourceLabel(shown.text);
    }

    /**
     * @param language target language code, or null for the language of the message
     * @param done     (summary, error text) on the UI thread; both null when the user declined to send the text
     */
    public static void summarize(String text, @Nullable String language, Utilities.Callback2<String, String> done) {
        if (TextUtils.isEmpty(text)) {
            AndroidUtilities.runOnUIThread(() -> done.run(null, LocaleController.getString(R.string.SG_SummaryFailed)));
            return;
        }
        final int provider = SolegramConfig.aiProvider;
        if (provider == PROVIDER_OWN) {
            if (TextUtils.isEmpty(SolegramConfig.aiBaseUrl)) {
                AndroidUtilities.runOnUIThread(() -> done.run(null, LocaleController.getString(R.string.SG_SummaryNoService)));
                return;
            }
            summarizeRemote(text, language, true, done);
            return;
        }
        if (provider == PROVIDER_FREE) {
            AndroidUtilities.runOnUIThread(() -> withConsent(false, () -> summarizeRemote(text, language, false, done), done));
            return;
        }
        // Gemini Nano first; any summary already made for this text (by Nano or the service) is reused
        final String cached = cache.get(cacheKey(false, language, text));
        if (cached != null) {
            AndroidUtilities.runOnUIThread(() -> done.run(cached, null));
            return;
        }
        pool.execute(() -> {
            try {
                final String result = summarizeOnDevice(text, language);
                remember(cacheKey(false, language, text), result, SOURCE_NANO);
                AndroidUtilities.runOnUIThread(() -> done.run(result, null));
            } catch (SolegramGeminiNano.NanoException e) {
                if (provider == PROVIDER_NANO) {
                    final String error = nanoError(e);
                    AndroidUtilities.runOnUIThread(() -> done.run(null, error));
                } else {
                    // auto: the free service, quietly (the label beside the summary says who made it)
                    AndroidUtilities.runOnUIThread(() -> withConsent(true, () -> summarizeRemote(text, language, false, done), done));
                }
            } catch (Throwable e) {
                FileLog.e(e);
                if (provider == PROVIDER_NANO) {
                    AndroidUtilities.runOnUIThread(() -> done.run(null, LocaleController.getString(R.string.SG_SummaryFailed)));
                } else {
                    AndroidUtilities.runOnUIThread(() -> withConsent(true, () -> summarizeRemote(text, language, false, done), done));
                }
            }
        });
    }

    private static String cacheKey(boolean own, @Nullable String language, String text) {
        return (own ? SolegramConfig.aiBaseUrl + '\u0000' + SolegramConfig.aiModel : "free") + '\u0000' + language + '\u0000' + text;
    }

    private static void remember(String key, String result, int source) {
        cache.put(key, result);
        sources.put(result, source);
    }

    /** The free service needs the user's consent once; declining sends nothing. UI thread. */
    private static void withConsent(boolean fallback, Runnable send, Utilities.Callback2<String, String> done) {
        if (SolegramConfig.aiConsent) {
            send.run();
            return;
        }
        askConsent(R.string.SG_AiSummaryHeader, fallback ? R.string.SG_AiConsentFallback : R.string.SG_AiConsent, send, () -> done.run(null, null));
    }

    /** The free service or the user's own one. */
    private static void summarizeRemote(String text, @Nullable String language, boolean own, Utilities.Callback2<String, String> done) {
        final String key = cacheKey(own, language, text);
        final String cached = cache.get(key);
        if (cached != null) {
            AndroidUtilities.runOnUIThread(() -> done.run(cached, null));
            return;
        }
        pool.execute(() -> {
            String result = null;
            String error = null;
            try {
                try {
                    result = request(text, language, own);
                } catch (BusyException busy) {
                    // the free service asks to slow down now and then: one more try after a short pause
                    Thread.sleep(4000);
                    result = request(text, language, own);
                }
            } catch (BusyException e) {
                error = LocaleController.getString(R.string.SG_SummaryBusy);
            } catch (Throwable e) {
                FileLog.e(e);
                error = LocaleController.getString(R.string.SG_SummaryFailed);
            }
            if (TextUtils.isEmpty(result)) {
                result = null;
                if (error == null) {
                    error = LocaleController.getString(R.string.SG_SummaryFailed);
                }
            } else {
                remember(key, result, own ? SOURCE_OWN : SOURCE_FREE);
                error = null;
            }
            final String r = result, err = error;
            AndroidUtilities.runOnUIThread(() -> done.run(r, err));
        });
    }

    private static void askConsent(int title, int message, Runnable accepted, Runnable declined) {
        final BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            declined.run();
            return;
        }
        final boolean[] answered = new boolean[1];
        final AlertDialog dialog = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider())
            .setTitle(LocaleController.getString(title))
            .setMessage(LocaleController.getString(message))
            .setPositiveButton(LocaleController.getString(R.string.SG_AiConsentAgree), (d, w) -> {
                answered[0] = true;
                SolegramConfig.prefs().edit().putBoolean("aiConsent", true).apply();
                SolegramConfig.reload();
                accepted.run();
            })
            .setNegativeButton(LocaleController.getString(R.string.Cancel), (d, w) -> {
                answered[0] = true;
                declined.run();
            })
            .create();
        dialog.setOnDismissListener(d -> {
            if (!answered[0]) {
                answered[0] = true;
                declined.run();
            }
        });
        fragment.showDialog(dialog);
    }

    private static final class BusyException extends IOException {
    }

    private static String request(String text, @Nullable String language, boolean own) throws Exception {
        return clean(chat(prompt(language), text.length() > MAX_INPUT ? text.substring(0, MAX_INPUT) : text, own));
    }

    /** One chat completion from the free service or the user's own one: the raw answer. */
    private static String chat(String instruction, String input, boolean own) throws Exception {
        final String url = own ? chatCompletionsUrl(SolegramConfig.aiBaseUrl) : FREE_URL;
        final String model = own ? (TextUtils.isEmpty(SolegramConfig.aiModel) ? DEFAULT_OWN_MODEL : SolegramConfig.aiModel.trim()) : FREE_MODEL;

        final JSONObject body = new JSONObject();
        body.put("model", model);
        final JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", instruction));
        messages.put(new JSONObject().put("role", "user").put("content", input));
        body.put("messages", messages);
        if (!own) {
            body.put("private", true);
            body.put("referrer", "solegram");
        }

        if (own) {
            // the key and the message go to the user's service: https, or plain http only inside the local network
            SolegramNet.checkPrivateEndpoint(url);
        }
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(false); // the key is never carried to another address
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "Solegram");
            if (own && !TextUtils.isEmpty(SolegramConfig.aiApiKey)) {
                c.setRequestProperty("Authorization", "Bearer " + SolegramConfig.aiApiKey.trim());
            }
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            final int code = c.getResponseCode();
            if (code == 429 || code == 503) {
                throw new BusyException();
            }
            if (code < 200 || code >= 300) {
                throw new IOException("AI service answered " + code);
            }
            final JSONObject response = new JSONObject(SolegramNet.readString(c.getInputStream(), 2 * 1024 * 1024));
            final JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            return message.isNull("content") ? "" : message.optString("content", "");
        } finally {
            c.disconnect();
        }
    }

    // region Other short texts (reminders)

    /** {@link #compose} made nothing: the request failed. */
    public static final int COMPOSE_FAILED = 0;
    /** The free service would be needed, and the user has not agreed to it (or declined now). */
    public static final int COMPOSE_NEEDS_CONSENT = -1;
    /** "Own service" is chosen but not set up. */
    public static final int COMPOSE_NO_SERVICE = -2;
    /** "On device only" is chosen and Gemini Nano is not ready on this phone. */
    public static final int COMPOSE_NANO_MISSING = -3;

    /** Placeholder in a {@link #compose} instruction for the language to write in. */
    public static final String LANGUAGE = "{language}";

    /**
     * A short text with its own instruction, made the way the user chose for AI (auto, on device, free or own service).
     * Not cached: asking again gives another wording.
     *
     * @param sample         text to find the language in for {@link #LANGUAGE}; {@code fallbackLanguage} when it is unclear
     * @param maxTokens      answer limit on the device
     * @param mayAskConsent  the free service may ask for the user's consent now (the user tapped something); otherwise
     *                       it answers {@link #COMPOSE_NEEDS_CONSENT} without showing anything
     * @param consentTitle   title of the consent dialog
     * @param consentMessage what the consent dialog says
     * @param done           (raw answer, SOURCE_*) on the UI thread, or (null, COMPOSE_*) when nothing was made
     */
    public static void compose(String instruction, String input, String sample, String fallbackLanguage, int maxTokens,
                               boolean mayAskConsent, int consentTitle, int consentMessage, Utilities.Callback2<String, Integer> done) {
        final String clipped = input.length() > MAX_INPUT ? input.substring(0, MAX_INPUT) : input;
        final int provider = SolegramConfig.aiProvider;
        if (provider == PROVIDER_OWN) {
            if (TextUtils.isEmpty(SolegramConfig.aiBaseUrl)) {
                AndroidUtilities.runOnUIThread(() -> done.run(null, COMPOSE_NO_SERVICE));
                return;
            }
            composeRemote(instruction, clipped, sample, fallbackLanguage, true, done);
            return;
        }
        // UI thread: the free service, once the user agreed to it
        final Runnable remote = () -> {
            if (SolegramConfig.aiConsent) {
                composeRemote(instruction, clipped, sample, fallbackLanguage, false, done);
            } else if (mayAskConsent) {
                askConsent(consentTitle, consentMessage, () -> composeRemote(instruction, clipped, sample, fallbackLanguage, false, done),
                    () -> done.run(null, COMPOSE_NEEDS_CONSENT));
            } else {
                done.run(null, COMPOSE_NEEDS_CONSENT);
            }
        };
        if (provider == PROVIDER_FREE) {
            AndroidUtilities.runOnUIThread(remote);
            return;
        }
        pool.execute(() -> {
            try {
                final String result = composeOnDevice(instruction, clipped, sample, fallbackLanguage, maxTokens);
                AndroidUtilities.runOnUIThread(() -> done.run(result, SOURCE_NANO));
            } catch (Throwable e) {
                if (!(e instanceof SolegramGeminiNano.NanoException)) {
                    FileLog.e(e);
                }
                if (provider == PROVIDER_NANO) {
                    final boolean missing = e instanceof SolegramGeminiNano.NanoException
                        && ((SolegramGeminiNano.NanoException) e).reason == SolegramGeminiNano.NanoException.UNAVAILABLE;
                    AndroidUtilities.runOnUIThread(() -> done.run(null, missing ? COMPOSE_NANO_MISSING : COMPOSE_FAILED));
                } else {
                    AndroidUtilities.runOnUIThread(remote);
                }
            }
        });
    }

    /** The language to write in: the one of the sample, else the fallback. Background threads only. */
    private static String composeLanguage(String sample, String fallbackLanguage) {
        final String found = TextUtils.isEmpty(sample) ? null : detectLanguage(sample);
        return found != null ? found : TextUtils.isEmpty(fallbackLanguage) ? null : normalizeLanguage(fallbackLanguage);
    }

    private static String composeOnDevice(String instruction, String input, String sample, String fallbackLanguage, int maxTokens) throws SolegramGeminiNano.NanoException {
        if (SolegramGeminiNano.statusBlocking() != SolegramGeminiNano.STATUS_AVAILABLE) {
            SolegramGeminiNano.downloadIfUnmetered();
            throw new SolegramGeminiNano.NanoException(SolegramGeminiNano.NanoException.UNAVAILABLE, null);
        }
        final String target = composeLanguage(sample, fallbackLanguage);
        if (target != null && missCount(target) >= 2) {
            throw new SolegramGeminiNano.NanoException(SolegramGeminiNano.NanoException.LANGUAGE, null);
        }
        final String answer = SolegramGeminiNano.generate(instruction.replace(LANGUAGE, languageName(target)),
            input + "\n\nWrite in " + languageName(target) + ".", maxTokens);
        final String result = answer == null ? null : answer.replaceAll("(?s)<think>.*?</think>", "").trim();
        if (TextUtils.isEmpty(result)) {
            throw new SolegramGeminiNano.NanoException(SolegramGeminiNano.NanoException.FAILED, null);
        }
        // as with summaries: an answer in the wrong language goes to the service instead (in auto)
        final Character.UnicodeScript expected = target != null ? scriptOfLanguage(target) : null;
        final Character.UnicodeScript got = dominantScript(result);
        if (expected != null && got != null && !sameScript(expected, got)) {
            nanoLanguageMisses.put(target, missCount(target) + 1);
            throw new SolegramGeminiNano.NanoException(SolegramGeminiNano.NanoException.LANGUAGE, null);
        }
        return result;
    }

    private static void composeRemote(String instruction, String input, String sample, String fallbackLanguage, boolean own, Utilities.Callback2<String, Integer> done) {
        pool.execute(() -> {
            String result = null;
            try {
                final String system = instruction.replace(LANGUAGE, languageName(composeLanguage(sample, fallbackLanguage)));
                try {
                    result = chat(system, input, own);
                } catch (BusyException busy) {
                    Thread.sleep(4000);
                    result = chat(system, input, own);
                }
                if (result != null) {
                    result = result.replaceAll("(?s)<think>.*?</think>", "").trim();
                }
            } catch (BusyException ignore) {
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final String r = TextUtils.isEmpty(result) ? null : result;
            AndroidUtilities.runOnUIThread(() -> done.run(r, r == null ? COMPOSE_FAILED : own ? SOURCE_OWN : SOURCE_FREE));
        });
    }

    // endregion

    // region Gemini Nano

    /** The summary made on the phone. Blocks: the summary thread only. */
    private static String summarizeOnDevice(String text, @Nullable String language) throws SolegramGeminiNano.NanoException {
        final int status = SolegramGeminiNano.statusBlocking();
        if (status != SolegramGeminiNano.STATUS_AVAILABLE) {
            // the model is not on the phone yet: fetched in the background while Wi-Fi costs nothing, this one goes elsewhere
            SolegramGeminiNano.downloadIfUnmetered();
            throw new SolegramGeminiNano.NanoException(SolegramGeminiNano.NanoException.UNAVAILABLE, null);
        }
        final String input = text.length() > MAX_INPUT ? text.substring(0, MAX_INPUT) : text;
        // a small model keeps the language far better when it is named, so "the language of the message" is found out first
        final String target = TextUtils.isEmpty(language) ? detectLanguage(input) : normalizeLanguage(language);
        if (target != null && missCount(target) >= 2) {
            throw new SolegramGeminiNano.NanoException(SolegramGeminiNano.NanoException.LANGUAGE, null);
        }
        final String instruction = prompt(target);
        final String reminder = "\n\nWrite the summary in " + languageName(target) + ".";
        final int budget = SolegramGeminiNano.tokenLimit() - 64;
        int tokens = SolegramGeminiNano.countTokens(instruction, input + reminder);
        if (tokens < 0) {
            tokens = input.length() / 2; // about two characters per token for Cyrillic; generous for Latin
        }
        String answer = null;
        if (tokens <= budget) {
            try {
                answer = SolegramGeminiNano.generate(instruction, input + reminder, NANO_OUTPUT_TOKENS);
            } catch (SolegramGeminiNano.NanoException e) {
                if (e.reason != SolegramGeminiNano.NanoException.TOO_LARGE) {
                    throw e;
                }
                tokens = Math.max(tokens, budget * 2); // the count was off: in parts then
            }
        }
        if (answer == null) {
            answer = summarizeInParts(input, target, tokens, budget, reminder);
        }
        final String result = clean(answer);
        if (TextUtils.isEmpty(result)) {
            throw new SolegramGeminiNano.NanoException(SolegramGeminiNano.NanoException.FAILED, null);
        }
        // Nano is officially tuned for a few languages; a summary in the wrong one goes to the service instead
        final Character.UnicodeScript expected = target != null ? scriptOfLanguage(target) : dominantScript(input);
        final Character.UnicodeScript got = dominantScript(result);
        if (expected != null && got != null && !sameScript(expected, got)) {
            if (target != null) {
                nanoLanguageMisses.put(target, missCount(target) + 1);
            }
            throw new SolegramGeminiNano.NanoException(SolegramGeminiNano.NanoException.LANGUAGE, null);
        }
        if (target != null) {
            nanoLanguageMisses.remove(target);
        }
        return result;
    }

    /** Map-reduce for a text above the model's input limit: notes on each part, then one summary of the notes. */
    private static String summarizeInParts(String input, @Nullable String target, int tokens, int budget, String reminder) throws SolegramGeminiNano.NanoException {
        final int parts = Math.min(NANO_MAX_PARTS, Math.max(2, (int) Math.ceil(tokens / (budget * 0.8))));
        final java.util.List<String> chunks = split(input, (input.length() + parts - 1) / parts);
        final String partInstruction = "You take notes on one part of a long message from a messenger chat or channel.\n"
            + "Write in " + languageName(target) + ".\n"
            + "Use up to 4 short bullet points, each on its own line starting with \"• \".\n"
            + "Keep the names, numbers, dates and conclusions that matter. Do not add anything that is not in the text.";
        final StringBuilder notes = new StringBuilder();
        for (int i = 0; i < chunks.size() && i < NANO_MAX_PARTS; i++) {
            final String note = clean(SolegramGeminiNano.generate(partInstruction, "Part " + (i + 1) + " of " + chunks.size() + ":\n" + chunks.get(i) + reminder, NANO_OUTPUT_TOKENS));
            if (!TextUtils.isEmpty(note)) {
                notes.append(note).append('\n');
            }
        }
        if (notes.length() == 0) {
            throw new SolegramGeminiNano.NanoException(SolegramGeminiNano.NanoException.FAILED, null);
        }
        final String mergeInstruction = "You get notes on the parts of one long message, in order. Turn them into one summary of the whole message.\n"
            + "Write in " + languageName(target) + ".\n"
            + "Use 1 to 4 short bullet points, each on its own line starting with \"• \".\n"
            + "Keep the names, numbers, dates and conclusions that matter. Do not add anything that is not in the notes.\n"
            + "No introduction, no headings, no markdown, no closing remarks.";
        return SolegramGeminiNano.generate(mergeInstruction, notes.toString().trim() + reminder, NANO_OUTPUT_TOKENS);
    }

    /** Pieces of at most {@code max} characters, cut at paragraphs, sentences or spaces where possible. */
    private static java.util.List<String> split(String text, int max) {
        final java.util.ArrayList<String> out = new java.util.ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + max);
            if (end < text.length()) {
                int cut = text.lastIndexOf('\n', end);
                if (cut <= start + max / 2) {
                    cut = Math.max(text.lastIndexOf(". ", end), text.lastIndexOf("! ", end));
                    cut = Math.max(cut, text.lastIndexOf("? ", end));
                    if (cut > 0) {
                        cut++;
                    }
                }
                if (cut <= start + max / 2) {
                    cut = text.lastIndexOf(' ', end);
                }
                if (cut > start + max / 2) {
                    end = cut;
                }
            }
            final String piece = text.substring(start, end).trim();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            start = end;
        }
        return out;
    }

    private static int missCount(String language) {
        final Integer n = nanoLanguageMisses.get(language);
        return n == null ? 0 : n;
    }

    /** What to tell the user when the on-device-only mode cannot make the summary. */
    private static String nanoError(SolegramGeminiNano.NanoException e) {
        switch (e.reason) {
            case SolegramGeminiNano.NanoException.UNAVAILABLE:
                return LocaleController.getString(SolegramGeminiNano.isSupported() ? R.string.SG_NanoNotReady : R.string.SG_NanoUnsupported);
            case SolegramGeminiNano.NanoException.BUSY:
                return LocaleController.getString(R.string.SG_NanoBusy);
            case SolegramGeminiNano.NanoException.QUOTA:
                return LocaleController.getString(R.string.SG_NanoQuota);
            case SolegramGeminiNano.NanoException.LANGUAGE:
                return LocaleController.getString(R.string.SG_NanoLanguage);
            default:
                return LocaleController.getString(R.string.SG_SummaryFailed);
        }
    }

    /** "pt-BR" -> "pt", "zh_CN" -> "zh". */
    private static String normalizeLanguage(String language) {
        String code = language.toLowerCase(Locale.ROOT);
        final int sep = Math.max(code.indexOf('-'), code.indexOf('_'));
        if (sep > 0) {
            code = code.substring(0, sep);
        }
        return "iw".equals(code) ? "he" : code;
    }

    /** The language of the text by ML Kit language identification (on the device), or null. Background threads only. */
    @Nullable
    private static String detectLanguage(String text) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final String[] found = new String[1];
        org.telegram.messenger.LanguageDetector.detectLanguage(text.length() > 1000 ? text.substring(0, 1000) : text, code -> {
            found[0] = code;
            latch.countDown();
        }, e -> latch.countDown());
        try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignore) {
        }
        final String code = found[0];
        return TextUtils.isEmpty(code) || "und".equals(code) ? null : normalizeLanguage(code);
    }

    private static String languageName(@Nullable String language) {
        if (TextUtils.isEmpty(language)) {
            return "the same language as the message";
        }
        final String name = new Locale(language).getDisplayLanguage(Locale.ENGLISH);
        return TextUtils.isEmpty(name) ? language : name;
    }

    /** The writing system a language is normally written in, or null when it is not clear. */
    @Nullable
    private static Character.UnicodeScript scriptOfLanguage(String code) {
        switch (code) {
            case "ru": case "uk": case "be": case "bg": case "mk": case "kk": case "ky": case "tg": case "mn": case "tt": case "ba":
                return Character.UnicodeScript.CYRILLIC;
            case "el":
                return Character.UnicodeScript.GREEK;
            case "ar": case "fa": case "ur": case "ps":
                return Character.UnicodeScript.ARABIC;
            case "he": case "yi":
                return Character.UnicodeScript.HEBREW;
            case "zh": case "ja":
                return Character.UnicodeScript.HAN;
            case "ko":
                return Character.UnicodeScript.HANGUL;
            case "hi": case "mr": case "ne":
                return Character.UnicodeScript.DEVANAGARI;
            case "th":
                return Character.UnicodeScript.THAI;
            case "ka":
                return Character.UnicodeScript.GEORGIAN;
            case "hy":
                return Character.UnicodeScript.ARMENIAN;
            case "en": case "de": case "fr": case "es": case "it": case "pt": case "nl": case "pl": case "cs": case "sk": case "sl":
            case "hr": case "bs": case "ro": case "hu": case "tr": case "az": case "id": case "ms": case "vi": case "sv": case "no":
            case "nb": case "da": case "fi": case "et": case "lv": case "lt": case "is": case "ga": case "ca": case "gl": case "sq":
            case "af": case "sw": case "tl": case "eu": case "cy": case "mt": case "lb": case "eo":
                return Character.UnicodeScript.LATIN;
            default:
                return null;
        }
    }

    /** The script most letters of the text are in, or null for a text with too few letters to say. */
    @Nullable
    private static Character.UnicodeScript dominantScript(String text) {
        final java.util.EnumMap<Character.UnicodeScript, Integer> counts = new java.util.EnumMap<>(Character.UnicodeScript.class);
        int letters = 0;
        for (int i = 0; i < text.length(); ) {
            final int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) {
                script = Character.UnicodeScript.HAN; // Japanese: kana and kanji count as one
            }
            final Integer n = counts.get(script);
            counts.put(script, n == null ? 1 : n + 1);
            letters++;
        }
        if (letters < 12) {
            return null;
        }
        Character.UnicodeScript best = null;
        int bestCount = 0;
        for (java.util.Map.Entry<Character.UnicodeScript, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    private static boolean sameScript(Character.UnicodeScript expected, Character.UnicodeScript got) {
        // Korean text may carry some hanja; the rest must match
        return expected == got || expected == Character.UnicodeScript.HANGUL && got == Character.UnicodeScript.HAN;
    }

    // endregion

    /** Accepts both a base URL (".../v1") and the full ".../chat/completions" address. */
    static String chatCompletionsUrl(String base) {
        String url = base.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/chat/completions")) {
            return url;
        }
        return url + "/chat/completions";
    }

    private static String prompt(@Nullable String language) {
        final String target = languageName(language);
        return "You summarize one message from a messenger chat or channel.\n"
            + "Write the summary in " + target + ".\n"
            + "Use 1 to 4 short bullet points, each on its own line starting with \"• \". A short message needs only one.\n"
            + "Keep the names, numbers, dates and conclusions that matter. Do not add anything that is not in the message.\n"
            + "No introduction, no headings, no markdown, no closing remarks.";
    }

    /** Plain bullet lines: no reasoning blocks, markdown marks or list numbering. */
    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.replaceAll("(?s)<think>.*?</think>", "");
        s = s.replace("**", "").replace("__", "").replace("`", "");
        final StringBuilder out = new StringBuilder();
        for (String line : s.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) {
                continue;
            }
            l = l.replaceFirst("^#+\\s*", "");
            if (l.matches("^([-*•–·]|\\d+[.)])\\s+.*")) {
                l = "• " + l.replaceFirst("^([-*•–·]|\\d+[.)])\\s+", "");
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(l);
        }
        String result = out.toString().trim();
        if (result.length() > MAX_OUTPUT) {
            result = result.substring(0, MAX_OUTPUT).trim() + "…";
        }
        return result;
    }
}
