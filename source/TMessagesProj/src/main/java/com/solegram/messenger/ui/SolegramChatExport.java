/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioColorCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Solegram: a chat saved to a file. The whole history (or the last days) is asked from Telegram page by page, gently
 * (a pause between pages, the waits Telegram asks for are kept), turned into HTML (looks like the chat, opens in any
 * browser), plain text or JSON, and optionally packed into a ZIP with the media already downloaded on this phone. The
 * file goes to Downloads, to another app, or to Saved Messages. Chats that forbid copying, secret chats and protected
 * messages are not exported.
 */
public final class SolegramChatExport {

    private SolegramChatExport() {
    }

    public static final int FORMAT_HTML = 0, FORMAT_TXT = 1, FORMAT_JSON = 2;
    private static final int PAGE = 100;
    private static final long PAGE_PAUSE = 350;

    /** Whether the chat can be exported at all. */
    public static boolean available(ChatActivity chat) {
        return chat != null && chat.getChatMode() == 0 && !DialogObject.isEncryptedDialog(chat.getDialogId()) && !chat.isPeerNoForwards();
    }

    // region the sheet

    public static void show(ChatActivity chat) {
        final Activity activity = chat.getParentActivity();
        if (activity == null) {
            return;
        }
        if (!available(chat)) {
            BulletinFactory.of(chat).createSimpleBulletin(R.raw.error, getString(R.string.SG_ExportProtected)).show();
            return;
        }
        final Theme.ResourcesProvider rp = chat.getResourceProvider();
        final BottomSheet sheet = new BottomSheet(activity, false, rp);
        sheet.fixNavigationBar();
        final LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(8), 0, dp(10));

        final TextView title = new TextView(activity);
        title.setText(getString(R.string.SG_ExportTitle));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 4, 20, 0));
        final TextView name = new TextView(activity);
        name.setText(chatName(chat.getCurrentAccount(), chat.getDialogId()));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
        column.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 2, 24, 6));

        final LinearLayout options = new LinearLayout(activity);
        options.setOrientation(LinearLayout.VERTICAL);
        column.addView(options, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final int[] format = {FORMAT_HTML}, period = {0};
        final boolean[] media = {false};
        final HeaderCell formatHeader = new HeaderCell(activity, rp);
        formatHeader.setText(getString(R.string.SG_ExportFormat));
        options.addView(formatHeader);
        final RadioColorCell[] formats = new RadioColorCell[3];
        final int[] formatTitles = {R.string.SG_ExportHtml, R.string.SG_ExportTxt, R.string.SG_ExportJson};
        final int[] formatInfos = {R.string.SG_ExportHtmlInfo, R.string.SG_ExportTxtInfo, R.string.SG_ExportJsonInfo};
        for (int i = 0; i < 3; i++) {
            final int f = i;
            formats[i] = radio(activity, rp);
            formats[i].setTextAndText2AndValue(getString(formatTitles[i]), getString(formatInfos[i]), i == format[0]);
            formats[i].setOnClickListener(v -> {
                format[0] = f;
                for (int j = 0; j < 3; j++) formats[j].setChecked(j == f, true);
            });
            options.addView(formats[i]);
        }
        final HeaderCell periodHeader = new HeaderCell(activity, rp);
        periodHeader.setText(getString(R.string.SG_ExportPeriod));
        options.addView(periodHeader);
        final RadioColorCell[] periods = new RadioColorCell[3];
        final int[] periodTitles = {R.string.SG_ExportAll, R.string.SG_ExportMonth, R.string.SG_ExportWeek};
        for (int i = 0; i < 3; i++) {
            final int p = i;
            periods[i] = radio(activity, rp);
            periods[i].setTextAndValue(getString(periodTitles[i]), i == period[0]);
            periods[i].setOnClickListener(v -> {
                period[0] = p;
                for (int j = 0; j < 3; j++) periods[j].setChecked(j == p, true);
            });
            options.addView(periods[i]);
        }
        final TextCheckCell mediaCell = new TextCheckCell(activity, rp);
        mediaCell.setTextAndValueAndCheck(getString(R.string.SG_ExportMedia), getString(R.string.SG_ExportMediaInfo), false, true, false);
        mediaCell.setOnClickListener(v -> {
            media[0] = !media[0];
            mediaCell.setChecked(media[0]);
        });
        options.addView(mediaCell);

        final TextView status = new TextView(activity);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        status.setGravity(Gravity.CENTER);
        status.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        status.setVisibility(View.GONE);
        column.addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 18, 24, 18));

        final ButtonWithCounterView start = new ButtonWithCounterView(activity, rp);
        start.setText(getString(R.string.SG_ExportStart), false);
        column.addView(start, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 14, 10, 14, 0));
        final LinearLayout done = new LinearLayout(activity);
        done.setOrientation(LinearLayout.VERTICAL);
        done.setVisibility(View.GONE);
        column.addView(done, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 14, 4, 14, 0));

        final Job job = new Job(chat.getCurrentAccount(), chat.getDialogId());
        start.setOnClickListener(v -> {
            if (job.started) return;
            job.format = format[0];
            job.media = media[0];
            job.since = period[0] == 0 ? 0 : (int) (System.currentTimeMillis() / 1000) - (period[0] == 1 ? 30 : 7) * 86400;
            job.title = chatName(chat.getCurrentAccount(), chat.getDialogId());
            options.setVisibility(View.GONE);
            status.setVisibility(View.VISIBLE);
            status.setText(LocaleController.formatString(R.string.SG_ExportLoading, 0));
            start.setLoading(true);
            start.setEnabled(false);
            job.onProgress = text -> status.setText(text);
            job.onDone = (file, count) -> {
                start.setVisibility(View.GONE);
                if (file == null) {
                    status.setText(getString(count == 0 ? R.string.SG_ExportEmpty : R.string.SG_ExportFailed));
                    return;
                }
                status.setText(LocaleController.formatString(R.string.SG_ExportDone, count, AndroidUtilities.formatFileSize(file.length())));
                done.setVisibility(View.VISIBLE);
                done.addView(button(activity, rp, R.string.SG_ExportSave, true, () -> {
                    MediaController.saveFile(file.getAbsolutePath(), activity, 2, file.getName(), job.mime(), uri -> {
                        try {
                            BulletinFactory.of(sheet.getContainer(), rp).createSimpleBulletin(R.raw.ic_download, getString(R.string.SG_ExportSaved)).show();
                        } catch (Throwable e) {
                            FileLog.e(e);
                        }
                    });
                }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 8));
                done.addView(button(activity, rp, R.string.SG_ExportShare, false, () -> share(activity, file, job.mime())),
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 8));
                done.addView(button(activity, rp, R.string.SG_ExportToSaved, false, () -> {
                    final int account = chat.getCurrentAccount();
                    SendMessagesHelper.prepareSendingDocument(AccountInstance.getInstance(account), file.getAbsolutePath(), file.getAbsolutePath(),
                        null, null, job.mime(), UserConfig.getInstance(account).getClientUserId(), null, null, null, null, null, true, 0, null, null, false);
                    sheet.dismiss();
                    BulletinFactory.of(chat).createSimpleBulletin(R.raw.saved_messages, getString(R.string.SG_ExportSentSaved)).show();
                }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
            };
            job.start();
        });
        sheet.setOnDismissListener(d -> job.cancelled = true);
        sheet.setCustomView(column);
        sheet.show();
    }

    private static RadioColorCell radio(Activity activity, Theme.ResourcesProvider rp) {
        final RadioColorCell cell = new RadioColorCell(activity, rp);
        cell.setCheckColor(Theme.getColor(Theme.key_radioBackground, rp), Theme.getColor(Theme.key_dialogRadioBackgroundChecked, rp));
        cell.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, rp), 2));
        return cell;
    }

    private static View button(Activity activity, Theme.ResourcesProvider rp, int text, boolean filled, Runnable click) {
        final ButtonWithCounterView b = filled ? new ButtonWithCounterView(activity, rp) : new ButtonWithCounterView(activity, false, rp);
        b.setText(getString(text), false);
        b.setOnClickListener(v -> click.run());
        return b;
    }

    private static void share(Activity activity, File file, String mime) {
        try {
            final Uri uri = androidx.core.content.FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", file);
            final Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mime);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(Intent.createChooser(intent, getString(R.string.SG_ExportShare)));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    static String chatName(int account, long dialogId) {
        final MessagesController mc = MessagesController.getInstance(account);
        if (dialogId > 0) {
            final TLRPC.User u = mc.getUser(dialogId);
            if (u != null && UserObject.isUserSelf(u)) {
                return getString(R.string.SavedMessages);
            }
            return u != null ? UserObject.getUserName(u) : String.valueOf(dialogId);
        }
        final TLRPC.Chat c = mc.getChat(-dialogId);
        return c != null && c.title != null ? c.title : String.valueOf(dialogId);
    }

    // endregion

    // region the export

    interface Progress {
        void on(String text);
    }

    interface Done {
        void on(File file, int count);
    }

    private static final DispatchQueue queue = new DispatchQueue("sgChatExport");

    /** One export: pages of history in, part files out (newest page first), then the parts in the order of time. */
    static final class Job {
        final int account;
        final long dialogId;
        int format, since;
        boolean media;
        String title;
        volatile boolean cancelled;
        boolean started;
        Progress onProgress;
        Done onDone;

        private final HashMap<Long, TLRPC.User> users = new HashMap<>();
        private final HashMap<Long, TLRPC.Chat> chats = new HashMap<>();
        private File dir;
        private final ArrayList<File> parts = new ArrayList<>();
        /** For each part: the day of its first and of its last message (to put a date line between parts). */
        private final ArrayList<long[]> partDays = new ArrayList<>();
        private final ArrayList<String[]> mediaFiles = new ArrayList<>(); // {path on the phone, name in the ZIP}
        private final HashSet<String> mediaNames = new HashSet<>();
        private int count, offsetId;
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault());
        private final SimpleDateFormat dayFormat = new SimpleDateFormat("d MMMM yyyy", Locale.getDefault());
        private final SimpleDateFormat isoFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);

        Job(int account, long dialogId) {
            this.account = account;
            this.dialogId = dialogId;
        }

        String extension() {
            return format == FORMAT_HTML ? ".html" : format == FORMAT_TXT ? ".txt" : ".json";
        }

        String mime() {
            if (media) return "application/zip";
            return format == FORMAT_HTML ? "text/html" : format == FORMAT_TXT ? "text/plain" : "application/json";
        }

        void start() {
            started = true;
            queue.postRunnable(() -> {
                try {
                    dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "sg_exports");
                    // the exports of before (only ours, in our own folder) are not kept
                    final File[] old = dir.listFiles();
                    if (old != null) {
                        for (File f : old) deleteAll(f);
                    }
                    final File work = new File(dir, "work");
                    work.mkdirs();
                    AndroidUtilities.runOnUIThread(this::nextPage);
                } catch (Throwable e) {
                    FileLog.e(e);
                    finish(null);
                }
            });
        }

        private void progress(String text) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!cancelled && onProgress != null) onProgress.on(text);
            });
        }

        private void finish(File file) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!cancelled && onDone != null) onDone.on(file, count);
            });
        }

        /** UI thread: asks for the next page (older than the oldest one so far). */
        private void nextPage() {
            if (cancelled) {
                cleanup();
                return;
            }
            final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = MessagesController.getInstance(account).getInputPeer(dialogId);
            req.offset_id = offsetId;
            req.limit = PAGE;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                if (cancelled) {
                    cleanup();
                    return;
                }
                if (error != null) {
                    final String text = error.text != null ? error.text : "";
                    if (text.startsWith("FLOOD_WAIT_")) {
                        int seconds = 5;
                        try {
                            seconds = Math.max(1, Integer.parseInt(text.substring("FLOOD_WAIT_".length())));
                        } catch (Exception ignore) {
                        }
                        progress(LocaleController.formatString(R.string.SG_ExportWait, seconds));
                        AndroidUtilities.runOnUIThread(this::nextPage, seconds * 1000L + 500);
                        return;
                    }
                    FileLog.e("chat export: " + text);
                    if (count > 0) {
                        queue.postRunnable(this::assemble); // what came so far is kept
                    } else {
                        finish(null);
                    }
                    return;
                }
                if (!(response instanceof TLRPC.messages_Messages)) {
                    finish(null);
                    return;
                }
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                queue.postRunnable(() -> takePage(res));
            });
        }

        /** Export queue: one page into a part file. */
        private void takePage(TLRPC.messages_Messages res) {
            if (cancelled) {
                cleanup();
                return;
            }
            try {
                for (TLRPC.User u : res.users) if (u != null) users.put(u.id, u);
                for (TLRPC.Chat c : res.chats) if (c != null) chats.put(c.id, c);
                final ArrayList<TLRPC.Message> page = new ArrayList<>();
                boolean reachedStart = res.messages.isEmpty();
                for (TLRPC.Message m : res.messages) {
                    if (m == null || m instanceof TLRPC.TL_messageEmpty) continue;
                    if (since != 0 && m.date < since) {
                        reachedStart = true;
                        continue;
                    }
                    page.add(m);
                }
                if (!res.messages.isEmpty()) {
                    offsetId = res.messages.get(res.messages.size() - 1).id;
                }
                if (!page.isEmpty()) {
                    Collections.reverse(page); // oldest first inside the part
                    writePart(page);
                }
                progress(LocaleController.formatString(R.string.SG_ExportLoading, count));
                // the start of the chat (or of the period): an empty page, or the whole history in one answer
                if (reachedStart || res.messages.isEmpty() || res instanceof TLRPC.TL_messages_messages) {
                    assemble();
                } else {
                    AndroidUtilities.runOnUIThread(this::nextPage, PAGE_PAUSE);
                }
            } catch (Throwable e) {
                FileLog.e(e);
                finish(null);
            }
        }

        private void writePart(ArrayList<TLRPC.Message> page) throws Exception {
            final File part = new File(new File(dir, "work"), "part" + parts.size());
            long firstDay = -1, lastDay = -1;
            try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(part), StandardCharsets.UTF_8))) {
                boolean first = true;
                for (TLRPC.Message m : page) {
                    final long day = dayOf(m.date);
                    if (firstDay < 0) firstDay = day;
                    if (format == FORMAT_HTML && lastDay >= 0 && day != lastDay) {
                        w.write(daySeparator(m.date));
                    }
                    lastDay = day;
                    final String item = render(m);
                    if (item == null) continue;
                    if (format == FORMAT_JSON && !first) w.write(",\n");
                    w.write(item);
                    first = false;
                    count++;
                }
            }
            parts.add(part);
            partDays.add(new long[]{firstDay, lastDay});
        }

        /** Export queue: the parts in the order of time (the last page asked is the oldest), wrapped, maybe zipped. */
        private void assemble() {
            if (cancelled) {
                cleanup();
                return;
            }
            if (count == 0) {
                cleanup();
                finish(null);
                return;
            }
            progress(getString(R.string.SG_ExportPacking));
            try {
                final String base = safeName(title) + "_" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                final File doc = new File(dir, base + extension());
                try (Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(doc), StandardCharsets.UTF_8))) {
                    w.write(header());
                    long prevDay = -1;
                    boolean firstPart = true;
                    for (int i = parts.size() - 1; i >= 0; i--) {
                        final long[] days = partDays.get(i);
                        if (format == FORMAT_HTML && days[0] >= 0 && days[0] != prevDay) {
                            w.write(daySeparatorOfDay(days[0]));
                        }
                        if (format == FORMAT_JSON && !firstPart && parts.get(i).length() > 0) {
                            w.write(",\n");
                        }
                        if (parts.get(i).length() > 0) {
                            firstPart = false;
                        }
                        if (days[1] >= 0) prevDay = days[1];
                        copy(parts.get(i), w);
                    }
                    w.write(footer());
                }
                File out = doc;
                if (media) {
                    out = new File(dir, base + ".zip");
                    zip(doc, out);
                    doc.delete();
                }
                deleteAll(new File(dir, "work"));
                finish(out);
            } catch (Throwable e) {
                FileLog.e(e);
                cleanup();
                finish(null);
            }
        }

        private void zip(File doc, File out) throws Exception {
            try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(out))) {
                z.putNextEntry(new ZipEntry(doc.getName()));
                pipe(new FileInputStream(doc), z);
                z.closeEntry();
                z.setLevel(java.util.zip.Deflater.NO_COMPRESSION); // photos and videos are packed already
                for (int i = 0; i < mediaFiles.size(); i++) {
                    if (cancelled) return;
                    final String[] f = mediaFiles.get(i);
                    final File src = new File(f[0]);
                    if (!src.exists()) continue;
                    z.putNextEntry(new ZipEntry("media/" + f[1]));
                    pipe(new FileInputStream(src), z);
                    z.closeEntry();
                    if (i % 10 == 0) {
                        progress(LocaleController.formatString(R.string.SG_ExportPackingMedia, i + 1, mediaFiles.size()));
                    }
                }
            }
        }

        private void cleanup() {
            queue.postRunnable(() -> {
                if (dir != null && cancelled) deleteAll(dir);
            });
        }

        // region one message

        private long dayOf(int date) {
            final java.util.Calendar c = java.util.Calendar.getInstance();
            c.setTimeInMillis(date * 1000L);
            return c.get(java.util.Calendar.YEAR) * 1000L + c.get(java.util.Calendar.DAY_OF_YEAR);
        }

        private String senderName(TLRPC.Message m) {
            final long id = senderId(m);
            if (id > 0) {
                final TLRPC.User u = users.get(id);
                return u != null ? UserObject.getUserName(u) : String.valueOf(id);
            } else if (id < 0) {
                final TLRPC.Chat c = chats.get(-id);
                return c != null && c.title != null ? c.title : String.valueOf(-id);
            }
            return title;
        }

        private long senderId(TLRPC.Message m) {
            final TLRPC.Peer p = m.from_id != null ? m.from_id : m.peer_id;
            if (p == null) return 0;
            if (p.user_id != 0) return p.user_id;
            if (p.chat_id != 0) return -p.chat_id;
            if (p.channel_id != 0) return -p.channel_id;
            return 0;
        }

        private String forwardedFrom(TLRPC.Message m) {
            if (m.fwd_from == null) return null;
            if (m.fwd_from.from_name != null) return m.fwd_from.from_name;
            final TLRPC.Peer p = m.fwd_from.from_id;
            if (p != null) {
                if (p.user_id != 0) {
                    final TLRPC.User u = users.get(p.user_id);
                    if (u != null) return UserObject.getUserName(u);
                } else {
                    final TLRPC.Chat c = chats.get(p.channel_id != 0 ? p.channel_id : p.chat_id);
                    if (c != null && c.title != null) return c.title;
                }
            }
            return "?";
        }

        /** The label of a message's media ("Фото", a file's name, the song...), or null. */
        private String mediaLabel(MessageObject mo) {
            final TLRPC.Message m = mo.messageOwner;
            if (m.media == null || m.media instanceof TLRPC.TL_messageMediaEmpty || m.media instanceof TLRPC.TL_messageMediaWebPage) {
                return null;
            }
            if (mo.isMusic()) {
                final String author = mo.getMusicAuthor(), song = mo.getMusicTitle();
                return getString(R.string.AttachMusic) + ": " + (TextUtils.isEmpty(author) ? "" : author + " — ") + (song != null ? song : "");
            }
            if (mo.getDocument() != null && !mo.isSticker() && !mo.isAnimatedSticker() && !mo.isGif() && !mo.isVideo() && !mo.isVoice() && !mo.isRoundVideo()) {
                final String doc = mo.getDocumentName();
                if (!TextUtils.isEmpty(doc)) return getString(R.string.AttachDocument) + ": " + doc;
            }
            if (mo.isSticker() || mo.isAnimatedSticker()) {
                final String emoji = mo.getStickerEmoji();
                return getString(R.string.AttachSticker) + (TextUtils.isEmpty(emoji) ? "" : " " + emoji);
            }
            final CharSequence text = mo.messageText;
            return text != null && text.length() > 0 && TextUtils.isEmpty(m.message) ? text.toString() : mediaFallback(mo);
        }

        private String mediaFallback(MessageObject mo) {
            if (mo.isPhoto()) return getString(R.string.AttachPhoto);
            if (mo.isRoundVideo()) return getString(R.string.AttachRound);
            if (mo.isVideo()) return getString(R.string.AttachVideo);
            if (mo.isVoice()) return getString(R.string.AttachAudio);
            if (mo.isGif()) return getString(R.string.AttachGif);
            return getString(R.string.AttachDocument);
        }

        /** A downloaded media file of the message, noted for the ZIP: its name there, or null. */
        private String takeMedia(MessageObject mo) {
            if (!media || mo.messageOwner.noforwards || mo.needDrawBluredPreview() || mo.isVoiceOnce() || mo.isRoundOnce()) {
                return null;
            }
            try {
                File f = FileLoader.getInstance(account).getPathToMessage(mo.messageOwner);
                if (f == null || !f.exists()) {
                    if (mo.messageOwner.attachPath != null && !mo.messageOwner.attachPath.isEmpty()) {
                        f = new File(mo.messageOwner.attachPath);
                    }
                }
                if (f == null || !f.exists() || f.isDirectory()) {
                    return null;
                }
                String name = mo.getId() + "_" + f.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
                while (!mediaNames.add(name)) name = "_" + name;
                mediaFiles.add(new String[]{f.getAbsolutePath(), name});
                return name;
            } catch (Throwable e) {
                return null;
            }
        }

        private String render(TLRPC.Message m) {
            MessageObject mo;
            try {
                mo = new MessageObject(account, m, users, chats, false, false);
            } catch (Throwable e) {
                mo = null;
            }
            final boolean service = m instanceof TLRPC.TL_messageService;
            final boolean secret = m.noforwards;
            final String text = secret ? getString(R.string.SG_ExportProtectedMessage)
                : service ? (mo != null && mo.messageText != null ? mo.messageText.toString() : "")
                : m.message != null ? m.message : "";
            final String label = secret || service || mo == null ? null : mediaLabel(mo);
            final String file = secret || service || mo == null || label == null ? null : takeMedia(mo);
            switch (format) {
                case FORMAT_TXT: return renderTxt(m, service, text, label, file);
                case FORMAT_JSON: return renderJson(m, mo, service, text, label, file, secret);
                default: return renderHtml(m, mo, service, text, label, file, secret);
            }
        }

        private String renderTxt(TLRPC.Message m, boolean service, String text, String label, String file) {
            final StringBuilder sb = new StringBuilder();
            sb.append('[').append(dateFormat.format(new Date(m.date * 1000L))).append("] ");
            if (service) {
                sb.append("— ").append(text).append('\n');
                return sb.toString();
            }
            sb.append(senderName(m));
            if (m.post_author != null && !m.post_author.isEmpty()) sb.append(" (").append(m.post_author).append(')');
            sb.append(':');
            final String fwd = forwardedFrom(m);
            if (fwd != null) sb.append(" [").append(LocaleController.formatString(R.string.SG_ExportForwarded, fwd)).append(']');
            if (m.reply_to != null && m.reply_to.reply_to_msg_id != 0) sb.append(" [").append(getString(R.string.SG_ExportReply)).append(" #").append(m.reply_to.reply_to_msg_id).append(']');
            if (label != null) sb.append(" [").append(label).append(file != null ? " → media/" + file : "").append(']');
            if (!text.isEmpty()) sb.append(' ').append(text.replace("\n", "\n    "));
            if (m.edit_date != 0 && !m.edit_hide) sb.append(" (").append(getString(R.string.SG_ExportEdited)).append(')');
            sb.append('\n');
            return sb.toString();
        }

        private String renderJson(TLRPC.Message m, MessageObject mo, boolean service, String text, String label, String file, boolean secret) {
            try {
                final JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("type", service ? "service" : "message");
                o.put("date", isoFormat.format(new Date(m.date * 1000L)));
                o.put("date_unixtime", m.date);
                if (m.edit_date != 0) o.put("edited_unixtime", m.edit_date);
                o.put("from", senderName(m));
                final long sid = senderId(m);
                o.put("from_id", sid > 0 ? "user" + sid : "channel" + (-sid));
                if (m.out) o.put("out", true);
                if (m.post_author != null && !m.post_author.isEmpty()) o.put("author", m.post_author);
                final String fwd = forwardedFrom(m);
                if (fwd != null) o.put("forwarded_from", fwd);
                if (m.reply_to != null && m.reply_to.reply_to_msg_id != 0) o.put("reply_to_message_id", m.reply_to.reply_to_msg_id);
                o.put("text", text);
                if (secret) o.put("protected", true);
                if (label != null) o.put("media", label);
                if (file != null) o.put("file", "media/" + file);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        private static final String[] NAME_COLORS = {"#e17076", "#eda86c", "#a695e7", "#7bc862", "#6ec9cb", "#65aadd", "#ee7aae"};

        private String renderHtml(TLRPC.Message m, MessageObject mo, boolean service, String text, String label, String file, boolean secret) {
            final StringBuilder sb = new StringBuilder();
            if (service) {
                sb.append("<div class=\"service\" id=\"m").append(m.id).append("\"><span>").append(esc(text)).append("</span></div>\n");
                return sb.toString();
            }
            final long sid = senderId(m);
            sb.append("<div class=\"msg").append(m.out ? " out" : "").append("\" id=\"m").append(m.id).append("\"><div class=\"bubble\">");
            if (!m.out || dialogId < 0) {
                sb.append("<div class=\"name\" style=\"color:").append(NAME_COLORS[(int) (Math.abs(sid) % NAME_COLORS.length)]).append("\">")
                    .append(esc(senderName(m)));
                if (m.post_author != null && !m.post_author.isEmpty()) sb.append(" · ").append(esc(m.post_author));
                sb.append("</div>");
            }
            final String fwd = forwardedFrom(m);
            if (fwd != null) sb.append("<div class=\"fwd\">").append(esc(LocaleController.formatString(R.string.SG_ExportForwarded, fwd))).append("</div>");
            if (m.reply_to != null && m.reply_to.reply_to_msg_id != 0) {
                sb.append("<a class=\"reply\" href=\"#m").append(m.reply_to.reply_to_msg_id).append("\">").append(esc(getString(R.string.SG_ExportReply))).append("</a>");
            }
            if (label != null) {
                if (file != null && mo != null && mo.isPhoto()) {
                    sb.append("<a href=\"media/").append(escAttr(file)).append("\"><img class=\"photo\" loading=\"lazy\" src=\"media/").append(escAttr(file)).append("\"></a>");
                } else if (file != null && mo != null && (mo.isVideo() || mo.isRoundVideo() || mo.isGif())) {
                    sb.append("<video class=\"photo\" controls preload=\"none\" src=\"media/").append(escAttr(file)).append("\"></video>");
                } else if (file != null) {
                    sb.append("<a class=\"media\" href=\"media/").append(escAttr(file)).append("\">📎 ").append(esc(label)).append("</a>");
                } else {
                    sb.append("<div class=\"media\">").append(esc(label)).append("</div>");
                }
            }
            if (!text.isEmpty()) {
                sb.append("<div class=\"text\">").append(secret ? "<i>" + esc(text) + "</i>" : formatted(m, text)).append("</div>");
            }
            sb.append("<div class=\"time\">");
            if (m.edit_date != 0 && !m.edit_hide) sb.append(esc(getString(R.string.SG_ExportEdited))).append(" · ");
            sb.append(esc(dateFormat.format(new Date(m.date * 1000L)))).append("</div></div></div>\n");
            return sb.toString();
        }

        /** The text with its bold, italic, links, code... as HTML (entities in UTF-16 offsets, as Java strings are). */
        private String formatted(TLRPC.Message m, String text) {
            if (m.entities == null || m.entities.isEmpty()) {
                return esc(text).replace("\n", "<br>");
            }
            final int n = text.length();
            final ArrayList<String>[] open = new ArrayList[n + 1], close = new ArrayList[n + 1];
            for (TLRPC.MessageEntity e : m.entities) {
                final int s = Math.max(0, Math.min(n, e.offset)), end = Math.max(s, Math.min(n, e.offset + e.length));
                if (end <= s) continue;
                String a = null, b = null;
                if (e instanceof TLRPC.TL_messageEntityBold) { a = "<b>"; b = "</b>"; }
                else if (e instanceof TLRPC.TL_messageEntityItalic) { a = "<i>"; b = "</i>"; }
                else if (e instanceof TLRPC.TL_messageEntityUnderline) { a = "<u>"; b = "</u>"; }
                else if (e instanceof TLRPC.TL_messageEntityStrike) { a = "<s>"; b = "</s>"; }
                else if (e instanceof TLRPC.TL_messageEntityCode) { a = "<code>"; b = "</code>"; }
                else if (e instanceof TLRPC.TL_messageEntityPre) { a = "<pre>"; b = "</pre>"; }
                else if (e instanceof TLRPC.TL_messageEntitySpoiler) { a = "<span class=\"spoiler\">"; b = "</span>"; }
                else if (e instanceof TLRPC.TL_messageEntityBlockquote) { a = "<blockquote>"; b = "</blockquote>"; }
                else if (e instanceof TLRPC.TL_messageEntityTextUrl && e.url != null && safeUrl(e.url)) { a = "<a href=\"" + escAttr(e.url) + "\">"; b = "</a>"; }
                else if (e instanceof TLRPC.TL_messageEntityUrl) {
                    final String url = text.substring(s, end);
                    final String href = url.contains("://") ? url : "https://" + url;
                    if (safeUrl(href)) { a = "<a href=\"" + escAttr(href) + "\">"; b = "</a>"; }
                }
                if (a == null) continue;
                if (open[s] == null) open[s] = new ArrayList<>();
                open[s].add(a);
                if (close[end] == null) close[end] = new ArrayList<>();
                close[end].add(0, b);
            }
            final StringBuilder sb = new StringBuilder(n + 32);
            for (int i = 0; i <= n; i++) {
                if (close[i] != null) for (String t : close[i]) sb.append(t);
                if (open[i] != null) for (String t : open[i]) sb.append(t);
                if (i < n) {
                    final char c = text.charAt(i);
                    switch (c) {
                        case '<': sb.append("&lt;"); break;
                        case '>': sb.append("&gt;"); break;
                        case '&': sb.append("&amp;"); break;
                        case '\n': sb.append("<br>"); break;
                        default: sb.append(c);
                    }
                }
            }
            return sb.toString();
        }

        private static boolean safeUrl(String url) {
            final String u = url.trim().toLowerCase(Locale.ROOT);
            return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("tg://") || u.startsWith("mailto:");
        }

        private String daySeparator(int date) {
            return "<div class=\"day\"><span>" + esc(dayFormat.format(new Date(date * 1000L))) + "</span></div>\n";
        }

        private String daySeparatorOfDay(long day) {
            final java.util.Calendar c = java.util.Calendar.getInstance();
            c.clear();
            c.set(java.util.Calendar.YEAR, (int) (day / 1000));
            c.set(java.util.Calendar.DAY_OF_YEAR, (int) (day % 1000));
            return "<div class=\"day\"><span>" + esc(dayFormat.format(c.getTime())) + "</span></div>\n";
        }

        // endregion

        // region the file around the messages

        private String header() {
            final String when = dateFormat.format(new Date());
            switch (format) {
                case FORMAT_TXT:
                    return title + "\n" + LocaleController.formatString(R.string.SG_ExportedAt, when) + " · "
                        + LocaleController.formatString(R.string.SG_ExportMessages, count) + "\n\n";
                case FORMAT_JSON:
                    final String type = dialogId > 0 ? "personal_chat" : ChatObject.isChannelAndNotMegaGroup(chats.get(-dialogId)) ? "channel" : "group";
                    return "{\n\"name\": " + JSONObject.quote(title) + ",\n\"type\": \"" + type + "\",\n\"id\": " + Math.abs(dialogId)
                        + ",\n\"exported_by\": \"Solegram\",\n\"exported_at\": " + JSONObject.quote(isoFormat.format(new Date()))
                        + ",\n\"messages\": [\n";
                default:
                    return "<!doctype html>\n<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                        + "<title>" + esc(title) + "</title><style>" + CSS + "</style></head><body>\n<header><h1>" + esc(title) + "</h1><p>"
                        + esc(LocaleController.formatString(R.string.SG_ExportedAt, when)) + " · "
                        + esc(LocaleController.formatString(R.string.SG_ExportMessages, count)) + "</p></header>\n<main>\n";
            }
        }

        private String footer() {
            switch (format) {
                case FORMAT_TXT: return "\n— " + getString(R.string.SG_ExportFooter) + "\n";
                case FORMAT_JSON: return "\n]\n}\n";
                default: return "</main>\n<footer>" + esc(getString(R.string.SG_ExportFooter)) + "</footer>\n</body></html>\n";
            }
        }

        private static final String CSS =
            ":root{--bg:#dfe7d6;--in:#fff;--out:#e3fcd2;--text:#111;--muted:#6d7a85;--link:#2a7bd1;--service:rgba(0,0,0,.28)}"
            + "@media(prefers-color-scheme:dark){:root{--bg:#0e1621;--in:#182533;--out:#2b5278;--text:#e9eef3;--muted:#8a9bab;--link:#71b7ff;--service:rgba(0,0,0,.4)}}"
            + "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.4 -apple-system,Roboto,'Segoe UI',sans-serif}"
            + "header{position:sticky;top:0;z-index:2;padding:12px 16px;background:var(--in);box-shadow:0 1px 3px rgba(0,0,0,.15)}"
            + "header h1{margin:0;font-size:18px}header p{margin:2px 0 0;color:var(--muted);font-size:13px}"
            + "main{max-width:760px;margin:0 auto;padding:10px 10px 30px}"
            + ".msg{display:flex;margin:3px 0}.msg.out{justify-content:flex-end}"
            + ".bubble{max-width:82%;padding:6px 10px 5px;border-radius:16px;background:var(--in);box-shadow:0 1px 1px rgba(0,0,0,.12);overflow-wrap:anywhere}"
            + ".out .bubble{background:var(--out)}.name{font-weight:600;font-size:14px;margin-bottom:1px}"
            + ".fwd,.reply,.media{display:block;font-size:13px;color:var(--link);text-decoration:none;margin:2px 0}"
            + ".reply{border-left:2px solid var(--link);padding-left:6px}"
            + ".photo{display:block;max-width:100%;max-height:420px;border-radius:12px;margin:4px 0}"
            + ".time{text-align:right;font-size:11px;color:var(--muted);margin-top:2px}"
            + ".text a{color:var(--link)}pre,code{font-family:ui-monospace,Menlo,monospace;font-size:13px}pre{white-space:pre-wrap;margin:4px 0}"
            + "blockquote{margin:4px 0;padding-left:8px;border-left:3px solid var(--link)}"
            + ".spoiler{background:var(--muted);color:transparent;border-radius:4px}.spoiler:hover{color:inherit;background:none}"
            + ".service,.day{text-align:center;margin:8px 0}.service span,.day span{display:inline-block;padding:3px 10px;border-radius:12px;"
            + "background:var(--service);color:#fff;font-size:13px}"
            + "footer{text-align:center;color:var(--muted);font-size:12px;padding:16px}";

        // endregion

        // region files

        private static String esc(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        private static String escAttr(String s) {
            return esc(s).replace("\"", "&quot;").replace("'", "&#39;");
        }

        private static String safeName(String s) {
            String n = s == null ? "chat" : s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
            if (n.isEmpty()) n = "chat";
            if (n.length() > 60) n = n.substring(0, 60);
            return n.replace(' ', '_');
        }

        private static void copy(File from, Writer to) throws Exception {
            try (java.io.Reader r = new java.io.InputStreamReader(new FileInputStream(from), StandardCharsets.UTF_8)) {
                final char[] buf = new char[16384];
                int n;
                while ((n = r.read(buf)) > 0) to.write(buf, 0, n);
            }
        }

        private static void pipe(InputStream in, java.io.OutputStream out) throws Exception {
            try (InputStream i = in) {
                final byte[] buf = new byte[65536];
                int n;
                while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
            }
        }

        private static void deleteAll(File f) {
            if (f == null || !f.exists()) return;
            final File[] kids = f.isDirectory() ? f.listFiles() : null;
            if (kids != null) for (File k : kids) deleteAll(k);
            f.delete();
        }

        // endregion
    }

    // endregion
}
