/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Solegram AI: the assistant built into Solegram. One engine for its tasks in chats - what one missed in a chat,
 * replies to a message, what a message means, a better wording of one's own message, questions about a chat, and the
 * assistant itself.
 * <p>
 * What makes it good at them: instructions written for each task in a messenger, and the chat given to the model the
 * way a person reads it - who wrote what and when, what answers what, which lines are the user's own - within a
 * budget that keeps the newest lines. Where the work goes is {@link SolegramSummarizer#compose}: Gemini Nano on the
 * phone, the free service or the user's own one (a Google Gemini key, for one), as the user chose; short tasks try
 * the phone first, long and open ones the service (in the auto mode).
 * <p>
 * Secret chats and chats that forbid copying never go to it.
 */
public final class SolegramAI {

    private SolegramAI() {
    }

    /** What a task gives back: the answer, or null and an error (both null when the user declined to send). */
    public interface Result {
        void done(@Nullable String answer, @Nullable String error, int source);
    }

    // region Rules

    private static final String BASE = "You are Solegram AI, the assistant built into Solegram, a Telegram app. "
        + "You help the user with their chats and messages: you are precise, warm and brief.\n"
        + "Rules:\n"
        + "- Write in " + SolegramSummarizer.LANGUAGE + ".\n"
        + "- Never invent anything that is not in the given messages; if something is unclear, say so briefly.\n"
        + "- Keep names, numbers, dates, times, prices and links exactly as written.\n"
        + "- No greetings, no sign-offs, no headings, no talk about being an AI. Plain text; use \"• \" for list items.\n"
        + "- Do not repeat phone numbers, card numbers, codes or passwords from the messages.\n";

    /** Whether a chat may be handed to Solegram AI at all. */
    public static boolean allowedIn(@Nullable ChatActivity chat) {
        if (chat == null || chat.getCurrentEncryptedChat() != null || DialogObject.isEncryptedDialog(chat.getDialogId())) {
            return false;
        }
        return !MessagesController.getInstance(chat.getCurrentAccount()).isPeerNoForwards(chat.getDialogId());
    }

    /** A message Solegram AI can work on (reply to, explain): text of someone's, not protected. */
    public static boolean canUseMessage(@Nullable ChatActivity chat, @Nullable MessageObject m) {
        return allowedIn(chat) && m != null && m.messageOwner != null && m.getId() > 0 && !m.isSponsored()
            && !m.messageOwner.noforwards && !TextUtils.isEmpty(textOf(m)) && m.messageOwner.action == null;
    }

    // endregion

    // region The chat as text

    private static final int CHAT_BUDGET = 7600;

    /** The text a message stands for: its text or caption, or what it is ("[photo]"). */
    static String textOf(MessageObject m) {
        if (m == null || m.messageOwner == null) {
            return "";
        }
        final String text = m.messageOwner.message;
        String kind = null;
        if (m.isPhoto()) kind = "photo";
        else if (m.isVideo() || m.isRoundVideo()) kind = m.isRoundVideo() ? "video message" : "video";
        else if (m.isVoice()) kind = "voice message";
        else if (m.isMusic()) kind = "music: " + m.getMusicAuthor() + " - " + m.getMusicTitle();
        else if (m.isSticker() || m.isAnimatedSticker()) kind = "sticker " + (m.getStickerEmoji() != null ? m.getStickerEmoji() : "");
        else if (m.isGif()) kind = "GIF";
        else if (m.type == MessageObject.TYPE_POLL && m.messageOwner.media instanceof TLRPC.TL_messageMediaPoll) {
            final TLRPC.Poll poll = ((TLRPC.TL_messageMediaPoll) m.messageOwner.media).poll;
            kind = "poll: " + (poll != null && poll.question != null ? poll.question.text : "");
        } else if (m.type == MessageObject.TYPE_GEO) kind = "location";
        else if (m.type == MessageObject.TYPE_CONTACT) kind = "contact";
        else if (m.isDocument() && m.getDocumentName() != null) kind = "file " + m.getDocumentName();
        if (kind == null) {
            return text == null ? "" : text.trim();
        }
        return "[" + kind.trim() + "]" + (TextUtils.isEmpty(text) ? "" : " " + text.trim());
    }

    private static String nameOf(MessageObject m) {
        if (m.isOutOwner()) {
            return "You";
        }
        final MessagesController mc = MessagesController.getInstance(m.currentAccount);
        final long from = m.getFromChatId();
        if (from > 0) {
            final TLRPC.User u = mc.getUser(from);
            if (u != null) {
                return UserObject.getFirstName(u, false);
            }
        } else if (from < 0) {
            final TLRPC.Chat c = mc.getChat(-from);
            if (c != null) {
                return c.title;
            }
        }
        return "Someone";
    }

    private static String clip(String s, int max) {
        s = s.replace('\n', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** The lines of a chat, oldest first: "[12:40] Anna (replying to You: «...»): text", day marks between days. */
    static String transcript(List<MessageObject> oldestFirst, @Nullable MessageObject marked) {
        final SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.US);
        final SimpleDateFormat day = new SimpleDateFormat("d MMM yyyy", Locale.US);
        final ArrayList<String> lines = new ArrayList<>();
        String lastDay = null;
        for (MessageObject m : oldestFirst) {
            final String text = textOf(m);
            if (TextUtils.isEmpty(text) || m.messageOwner.action != null) {
                continue;
            }
            final Date date = new Date(m.messageOwner.date * 1000L);
            final String d = day.format(date);
            if (!d.equals(lastDay)) {
                lines.add("— " + d + " —");
                lastDay = d;
            }
            final StringBuilder b = new StringBuilder();
            if (marked != null && m.getId() == marked.getId()) {
                b.append(">>> ");
            }
            b.append('[').append(time.format(date)).append("] ").append(nameOf(m));
            final MessageObject reply = m.replyMessageObject;
            if (reply != null && reply.messageOwner != null) {
                b.append(" (replying to ").append(nameOf(reply)).append(": «").append(clip(textOf(reply), 60)).append("»)");
            }
            b.append(": ").append(clip(text, 700));
            lines.add(b.toString());
        }
        // the newest lines within the budget
        int size = 0, from = lines.size();
        while (from > 0 && size + lines.get(from - 1).length() + 1 <= CHAT_BUDGET) {
            size += lines.get(--from).length() + 1;
        }
        return TextUtils.join("\n", lines.subList(from, lines.size()));
    }

    /** The chat's loaded text messages, oldest first: the unread ones (with a few before them) or the last ones. */
    public static List<MessageObject> recent(ChatActivity chat, boolean unreadOnly, int max) {
        final ArrayList<MessageObject> all = new ArrayList<>();
        for (MessageObject m : chat.messages) { // newest first
            if (m != null && m.messageOwner != null && m.getId() > 0 && !m.isSponsored() && m.messageOwner.action == null) {
                all.add(m);
            }
        }
        int take = Math.min(max, all.size());
        if (unreadOnly) {
            int unread = 0;
            for (MessageObject m : all) {
                if (!m.isOutOwner() && m.isUnread()) {
                    unread++;
                } else if (unread > 0) {
                    break;
                }
            }
            take = Math.min(all.size(), Math.min(max, unread + 6));
        }
        final ArrayList<MessageObject> list = new ArrayList<>(all.subList(0, take));
        Collections.reverse(list);
        return list;
    }

    /** Unread messages among the loaded ones (a catch-up is worth it from a few). */
    public static int unreadCount(ChatActivity chat) {
        int n = 0;
        for (MessageObject m : chat.messages) {
            if (m != null && m.messageOwner != null && m.getId() > 0 && !m.isOutOwner() && m.isUnread()) {
                n++;
            } else if (n > 0) {
                break;
            }
        }
        return n;
    }

    private static String chatTitle(ChatActivity chat) {
        if (chat.getCurrentChat() != null) {
            return "the group or channel «" + chat.getCurrentChat().title + "»";
        }
        if (chat.getCurrentUser() != null) {
            return "a private chat with " + UserObject.getUserName(chat.getCurrentUser());
        }
        return "a chat";
    }

    private static String appLanguage() {
        return LocaleController.getInstance().getCurrentLocaleInfo() != null ? LocaleController.getInstance().getCurrentLocaleInfo().pluralLangCode : "en";
    }

    // endregion

    // region Tasks

    /** What happened in a chat: the unread part when there is one, else its last messages. */
    public static void catchUp(ChatActivity chat, Result result) {
        final boolean unread = unreadCount(chat) >= 3;
        final List<MessageObject> messages = recent(chat, unread, 120);
        if (messages.isEmpty()) {
            result.done(null, LocaleController.getString(R.string.SG_AiNothingToRead), 0);
            return;
        }
        final String task = BASE + "\nBelow are messages from " + chatTitle(chat) + " (oldest first). Each line is \"[time] Name: text\"; "
            + "\"You\" is the user, \"replying to\" shows what a message answers.\n"
            + (unread ? "The user has not read the newest of them yet. " : "")
            + "Tell the user what happened, so they do not need to read it all:\n"
            + "• 3 to 7 points about the main topics and their outcome - decisions, plans, dates, places, prices, links - the most important first; "
            + "say who said what when it matters.\n"
            + "• Then, only if there are any, a line \"" + LocaleController.getString(R.string.SG_AiForYou) + "\" with the questions and requests "
            + "addressed to the user and what is expected of them.\n"
            + "Each point one or two short sentences. Skip greetings, stickers, emoji, reactions and small talk; mention a date only "
            + "when it matters (a plan, a deadline).";
        run(task, transcript(messages, null), "", appLanguage(), 600, true, new SolegramSummarizer.ChatOptions(null, "medium"), result);
    }

    /** An answer to a question about a chat, from its messages. */
    public static void askAboutChat(ChatActivity chat, String question, Result result) {
        final List<MessageObject> messages = recent(chat, false, 200);
        final String task = BASE + "\nBelow are the latest messages from " + chatTitle(chat) + " (oldest first; \"You\" is the user). "
            + "Answer the user's question about this chat using only these messages; quote the key words, and say who wrote it and when. "
            + "If the messages do not answer it, say so in one sentence.";
        run(task, transcript(messages, null) + "\n\nQuestion: " + question, question, appLanguage(), 500, true, new SolegramSummarizer.ChatOptions(null, "medium"), result);
    }

    /** Three replies to a message, in the user's voice and the chat's tone (one per line in the answer). */
    public static void replies(ChatActivity chat, MessageObject message, Result result) {
        final List<MessageObject> context = around(chat, message, 24);
        final String task = BASE + "\nBelow is the end of " + chatTitle(chat) + " (oldest first; \"You\" is the user); the message marked >>> is "
            + "the one to answer. Suggest exactly 3 replies the user could send to it, in the user's own voice and the tone of this chat "
            + "(casual stays casual, the user's usual way of writing - the lines marked You - is a good guide). Make them different: "
            + "for example agree, ask or decline, and a warmer or funny one. They must fit the facts of the chat (who, what, when). "
            + "Each reply on its own line, no numbering, no quotes, "
            + "at most 15 words and at most one emoji. Output only the 3 lines.";
        run(task, transcript(context, message), textOf(message), appLanguage(), 160, true, new SolegramSummarizer.ChatOptions(null, "low"), result);
    }

    /** What a message means, with slang, references and terms explained, from the chat around it. */
    public static void explain(ChatActivity chat, MessageObject message, Result result) {
        final List<MessageObject> context = around(chat, message, 16);
        final String task = BASE + "\nBelow is a part of " + chatTitle(chat) + " (oldest first; \"You\" is the user). Explain to the user the "
            + "message marked >>>: what it means and implies, in plain words, using the lines around it. Explain any slang, abbreviations, "
            + "memes, references, jargon or foreign words in it. 2 to 5 short sentences or points. If it is plain and clear, say that in one "
            + "sentence and add only what really helps.";
        run(task, transcript(context, message), "", appLanguage(), 350, true, new SolegramSummarizer.ChatOptions(null, "medium"), result);
    }

    /** A short summary of one message (long posts). */
    public static void summarizeMessage(MessageObject message, Result result) {
        final String task = BASE + "\nSum up this message from a Telegram chat or channel for the user: its main points in 2 to 5 short "
            + "bullet points, the most important first, with any dates, prices, places and links that matter.";
        run(task, textOf(message), textOf(message), appLanguage(), 350, true, new SolegramSummarizer.ChatOptions(null, "low"), result);
    }

    public static final int WRITE_FIX = 0, WRITE_SHORTER = 1, WRITE_POLITE = 2, WRITE_FRIENDLY = 3, WRITE_FORMAL = 4, WRITE_TRANSLATE = 5, WRITE_LONGER = 6;

    /** The user's own message, rewritten; translated into {@code targetLanguage} for {@link #WRITE_TRANSLATE}. */
    public static void rewrite(String text, int mode, @Nullable String targetLanguage, Result result) {
        final String keep = " Keep its meaning, its facts, names and links, its emoji and the person it speaks to. Output only the new message, nothing else.";
        final String how;
        switch (mode) {
            case WRITE_SHORTER:
                how = "Make the user's message shorter and clearer, without losing anything that matters, in the same language and tone." + keep;
                break;
            case WRITE_LONGER:
                how = "Develop the user's message a little: clearer and more complete, a few sentences at most, in the same language and tone." + keep;
                break;
            case WRITE_POLITE:
                how = "Rewrite the user's message to be polite, considerate and respectful, in the same language." + keep;
                break;
            case WRITE_FRIENDLY:
                how = "Rewrite the user's message to sound warm, friendly and casual, in the same language; one fitting emoji is fine." + keep;
                break;
            case WRITE_FORMAL:
                how = "Rewrite the user's message in a formal, business-like tone, in the same language." + keep;
                break;
            case WRITE_TRANSLATE:
                how = "Translate the user's message into " + SolegramSummarizer.LANGUAGE + ", natural and idiomatic, as a native speaker would write it "
                    + "in a messenger; keep the tone, the formatting and the emoji. Output only the translation.";
                break;
            default:
                how = "Correct the spelling, grammar and punctuation of the user's message. Keep its words, language, tone, emoji and line "
                    + "breaks; change nothing else. Output only the corrected message.";
                break;
        }
        final String task = "You are Solegram AI, the writing assistant in Solegram, a Telegram app. " + how;
        final boolean translate = mode == WRITE_TRANSLATE;
        run(task, text, translate ? "" : text, translate ? (targetLanguage != null ? targetLanguage : "en") : appLanguage(), 500, true, new SolegramSummarizer.ChatOptions(null, "low"), result);
    }

    /** The assistant: an answer to the last question of a conversation (lines "User: ..." / "Solegram AI: ..."). */
    public static void ask(List<String[]> turns, Result result) {
        final String last = turns.isEmpty() ? "" : turns.get(turns.size() - 1)[1];
        run(assistantInstruction(), conversationOf(turns), last, appLanguage(), 700, true, new SolegramSummarizer.ChatOptions(new ArrayList<>(turns), "medium"), result);
    }

    private static String conversationOf(List<String[]> turns) {
        final StringBuilder conversation = new StringBuilder();
        for (String[] t : turns.subList(Math.max(0, turns.size() - 12), turns.size())) {
            conversation.append(t[0].equals("user") ? "User: " : "Solegram AI: ").append(TextUtils.isEmpty(t[1]) ? "(a photo)" : t[1]).append("\n\n");
        }
        return conversation.toString().trim();
    }

    private static String assistantInstruction() {
        final java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH);
        return "You are Solegram AI, the assistant built into Solegram, an unofficial Telegram app for Android. You write in "
            + SolegramSummarizer.LANGUAGE + " (or the language the user writes in), naturally, as a smart and friendly person would.\n"
            + "Now it is " + day.format(new java.util.Date()) + " (the user's time).\n"
            + "How you answer:\n"
            + "• First understand what the user really wants, using the whole conversation (\"it\", \"this\", \"again\" refer to earlier "
            + "messages). If a request is truly ambiguous, ask one short question instead of guessing.\n"
            + "• Think it through before answering: for maths, logic, code, plans and comparisons work step by step and check the result; "
            + "give the answer first, then the short reasoning.\n"
            + "• Fit the length to the question: a fact or a yes/no in a sentence or two; an explanation, a text to write, a plan or a "
            + "how-to as complete as it needs to be. Do not pad, do not repeat the question, no filler like \"great question\".\n"
            + "• Be concrete and useful: real steps, examples, numbers, names. When asked to write something (a message, a post, a "
            + "letter), write the finished text, ready to send.\n"
            + "• Be honest: you have no internet access and do not know events after your training; for news, prices, weather or "
            + "schedules say so and tell how to check. If you are not sure, say so; never make up facts, links, quotes or numbers.\n"
            + "• Formatting: plain text; \"• \" for lists, numbered lines for steps; no markdown headings, tables or bold marks.\n"
            + "You can help with anything: questions, ideas, writing and editing messages, translations, explanations, studies, code, "
            + "plans, advice.\n"
            + "About Solegram, if asked: it adds to Telegram liquid glass design, themes and app icons, a music player, translation of "
            + "messages in place, AI summaries, catch-up of chats and smart replies (Solegram AI), search by meaning, reminders, message "
            + "filters, anti-scam warnings, SoleDrop file sharing nearby, SoleWallet, plugins and a proxy page. Its settings are under "
            + "Settings → Solegram. Do not claim features you are not sure of.";
    }

    // region Photos

    private static final java.util.concurrent.ExecutorService photoPool = java.util.concurrent.Executors.newSingleThreadExecutor();

    private static final String PHOTO = "The user attached a photo to their last message. Look at it carefully: answer their question "
        + "about it, or, without a question, say what is in it and what matters - read out any text it has (translate it if it is in "
        + "another language), name places, things, dishes, plants, animals, problems or mistakes you see, and help with what the user "
        + "probably wants. Be concrete and do not guess what you cannot see.";

    /**
     * The assistant with a photo: the user's own service looks at it when it is set up, else Gemini Nano on the phone
     * when its model sees pictures, else the phone recognises what is in it and the cloud answers from that (and says
     * it saw the photo only in part).
     */
    public static void askWithImage(List<String[]> turns, android.graphics.Bitmap image, Result result) {
        final String conversation = conversationOf(turns);
        final String question = turns.isEmpty() ? "" : turns.get(turns.size() - 1)[1];
        final String instruction = assistantInstruction() + "\n" + PHOTO;
        photoPool.execute(() -> {
            final android.graphics.Bitmap scaled = scaleDown(image, 1024);
            final int provider = SolegramConfig.aiProvider;
            if (provider == SolegramSummarizer.PROVIDER_OWN && !TextUtils.isEmpty(SolegramConfig.aiBaseUrl)) {
                try {
                    final String answer = SolegramSummarizer.chatWithImage(withLanguage(instruction, question), conversation, jpegBase64(scaled));
                    org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> result.done(TextUtils.isEmpty(answer) ? null : clean(answer),
                        TextUtils.isEmpty(answer) ? LocaleController.getString(R.string.SG_AiFailed) : null, SolegramSummarizer.SOURCE_OWN));
                } catch (Throwable e) {
                    org.telegram.messenger.FileLog.e(e);
                    org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> result.done(null, LocaleController.getString(R.string.SG_AiPhotoOwnFailed), 0));
                }
                return;
            }
            if (provider != SolegramSummarizer.PROVIDER_FREE && SolegramGeminiNano.isAvailable()) {
                try {
                    final String answer = SolegramGeminiNano.generateWithImage(withLanguage(instruction, question), conversation, scaled, 600);
                    org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> result.done(clean(answer), null, SolegramSummarizer.SOURCE_NANO));
                    return;
                } catch (Throwable ignore) {
                    // this phone's model does not see pictures (or is busy): what it shows, recognised on the phone
                }
            }
            if (provider == SolegramSummarizer.PROVIDER_NANO) {
                org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> result.done(null, LocaleController.getString(R.string.SG_AiPhotoNanoNo), 0));
                return;
            }
            final String seen = labels(scaled);
            final String task = assistantInstruction() + "\nThe user attached a photo. You cannot see it yourself: the phone recognised these things on it: "
                + (seen.isEmpty() ? "nothing it could name" : seen) + ". Answer from that as well as you can, and say in one short sentence that "
                + "for a full look at photos the user can connect their own AI key (for example a free Google Gemini key) in Solegram AI settings.";
            org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> run(task, conversation, question, appLanguage(), 600, true, new SolegramSummarizer.ChatOptions(new ArrayList<>(turns), "medium"), result));
        });
    }

    /** The language to answer a photo question in: the one of the question, or the app's when there is none. */
    private static String withLanguage(String instruction, String question) {
        return instruction.replace(SolegramSummarizer.LANGUAGE, TextUtils.isEmpty(question)
            ? SolegramSummarizer.languageName(appLanguage()) : "the language of the user's last message");
    }

    /** What ML Kit recognises on a picture, on the phone: "cat (93%), sofa (71%)". */
    private static String labels(android.graphics.Bitmap bitmap) {
        try {
            final List<com.google.mlkit.vision.label.ImageLabel> found = com.google.android.gms.tasks.Tasks.await(
                com.google.mlkit.vision.label.ImageLabeling.getClient(com.google.mlkit.vision.label.defaults.ImageLabelerOptions.DEFAULT_OPTIONS)
                    .process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)), 15, java.util.concurrent.TimeUnit.SECONDS);
            final StringBuilder b = new StringBuilder();
            for (com.google.mlkit.vision.label.ImageLabel l : found) {
                if (l.getConfidence() < 0.55f) {
                    continue;
                }
                if (b.length() > 0) {
                    b.append(", ");
                }
                b.append(l.getText().toLowerCase(Locale.ROOT)).append(" (").append(Math.round(l.getConfidence() * 100)).append("%)");
            }
            return b.toString();
        } catch (Throwable e) {
            return "";
        }
    }

    private static android.graphics.Bitmap scaleDown(android.graphics.Bitmap b, int max) {
        final int w = b.getWidth(), h = b.getHeight();
        if (Math.max(w, h) <= max) {
            return b;
        }
        final float k = max / (float) Math.max(w, h);
        return android.graphics.Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
    }

    private static String jpegBase64(android.graphics.Bitmap b) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
    }

    // endregion

    /** The messages around one (up to {@code max}, oldest first), from the chat's loaded ones. */
    private static List<MessageObject> around(ChatActivity chat, MessageObject message, int max) {
        final ArrayList<MessageObject> list = new ArrayList<>();
        final ArrayList<MessageObject> all = chat.messages; // newest first
        int index = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) != null && all.get(i).getId() == message.getId()) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            list.add(message);
            return list;
        }
        final int newer = Math.max(0, index - 3); // a few after it: an answer may already be there
        for (int i = Math.min(all.size() - 1, index + max - 4); i >= newer; i--) {
            final MessageObject m = all.get(i);
            if (m != null && m.messageOwner != null && m.getId() > 0 && m.messageOwner.action == null) {
                list.add(m);
            }
        }
        return list;
    }

    /**
     * In the auto mode every task goes to the cloud model first (it is far smarter than the small model on the phone);
     * Gemini Nano answers when the cloud cannot (no network, busy) or the user did not agree to it.
     */
    private static void run(String instruction, String input, String sample, String fallbackLanguage, int maxTokens, boolean cloudFirst,
                            SolegramSummarizer.ChatOptions options, Result result) {
        SolegramSummarizer.compose(instruction, input, sample, fallbackLanguage, maxTokens, true,
            R.string.SG_AiTitle, R.string.SG_AiConsentAssistant, cloudFirst, options, (answer, code) -> {
                if (answer != null) {
                    result.done(clean(answer), null, code);
                    return;
                }
                switch (code) {
                    case SolegramSummarizer.COMPOSE_NEEDS_CONSENT:
                        result.done(null, null, 0);
                        break;
                    case SolegramSummarizer.COMPOSE_NO_SERVICE:
                        result.done(null, LocaleController.getString(R.string.SG_SummaryNoService), 0);
                        break;
                    case SolegramSummarizer.COMPOSE_NANO_MISSING:
                        result.done(null, LocaleController.getString(R.string.SG_AiNanoMissing), 0);
                        break;
                    default:
                        result.done(null, LocaleController.getString(R.string.SG_AiFailed), 0);
                        break;
                }
            });
    }

    /** The model's answer without its scaffolding: markdown marks, quotes round the whole answer, stray labels. */
    static String clean(String answer) {
        String s = answer.replaceAll("(?s)<think>.*?</think>", "").trim();
        s = s.replaceAll("(?m)^#{1,6}\\s*", "");
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
        s = s.replaceAll("(?m)^\\s*[-*]\\s+", "• ");
        if (s.length() > 1 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("«") && s.endsWith("»"))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /** The replies in an answer of {@link #replies}: up to three non-empty lines. */
    public static List<String> parseReplies(String answer) {
        final ArrayList<String> out = new ArrayList<>();
        for (String line : answer.split("\n")) {
            String l = line.trim().replaceAll("^(\\d+[.)]|•|-)\\s*", "").trim();
            if (l.length() > 1 && (l.startsWith("\"") && l.endsWith("\"") || l.startsWith("«") && l.endsWith("»"))) {
                l = l.substring(1, l.length() - 1).trim();
            }
            if (!l.isEmpty() && out.size() < 3) {
                out.add(l);
            }
        }
        return out;
    }

    /** Who answered ("Gemini Nano · on the phone", "Solegram AI · cloud", ...), for the line under an answer. */
    public static String sourceText(int source) {
        switch (source) {
            case SolegramSummarizer.SOURCE_NANO:
                return LocaleController.getString(R.string.SG_AiSourceNano);
            case SolegramSummarizer.SOURCE_OWN:
                return LocaleController.getString(R.string.SG_AiSourceOwn);
            case SolegramSummarizer.SOURCE_FREE:
                return LocaleController.getString(R.string.SG_AiSourceCloud);
            default:
                return "";
        }
    }

    /** The user's account id, for whether a message is theirs. */
    static long me(int account) {
        return UserConfig.getInstance(account).getClientUserId();
    }
}
