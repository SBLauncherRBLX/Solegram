/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/**
 * A mini preview of the page a text message links to, for links Telegram shows no preview for (sent without one,
 * or the server made none): the page's picture (or the site's letter), the site, the page's title and its address,
 * in the bubble where a link preview would be. The page is asked for once per run; until it comes (or when there is
 * nothing to tell) the card shows the site and the address. Tap opens the link.
 */
public class SolegramLinkBubble {

    private static final int THUMB = 46, PAD = 10, GAP = 10;

    /** What each address gave (null: asked, nothing to show), shared by all cells. */
    private static final HashMap<String, TLRPC.WebPage> pages = new HashMap<>();
    private static final HashSet<String> loading = new HashSet<>();
    private static final HashMap<String, ArrayList<java.lang.ref.WeakReference<SolegramLinkBubble>>> waiters = new HashMap<>();

    private final ChatMessageCell parent;
    private final ImageReceiver thumb;
    private final TextPaint sitePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hostPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint letterPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF cardRect = new RectF();
    private final RectF rect = new RectF();

    private MessageObject messageObject;
    private boolean active, pressed, hasThumb;
    private String url, host;
    private int width, height;
    private StaticLayout siteLayout, titleLayout, hostLayout;
    private int siteY, titleY, hostY;
    private String letter = "";

    public SolegramLinkBubble(ChatMessageCell parent) {
        this.parent = parent;
        thumb = new ImageReceiver(parent);
        thumb.setAllowLoadingOnAttachedOnly(true);
        thumb.setRoundRadius(dp(10));
        sitePaint.setTypeface(AndroidUtilities.bold());
        titlePaint.setTypeface(AndroidUtilities.bold());
        letterPaint.setTypeface(AndroidUtilities.bold());
    }

    public boolean isActive() {
        return active;
    }

    public int getMeasuredWidth() {
        return active ? width : 0;
    }

    public int getMeasuredHeight() {
        return active ? height : 0;
    }

    // region Finding the link

    /** The first web address of a plain text message that has no link preview, or null. */
    @Nullable
    private static String linkOf(MessageObject m) {
        if (!SolegramConfig.linkCards || m == null || m.messageOwner == null || m.type != MessageObject.TYPE_TEXT
            || m.isSponsored() || m.isRepostPreview || m.isRestrictedMessage || TextUtils.isEmpty(m.messageOwner.message)
            || m.messageOwner.entities == null || m.messageOwner.entities.isEmpty()) {
            return null;
        }
        final TLRPC.MessageMedia media = m.messageOwner.media;
        if (media != null && !(media instanceof TLRPC.TL_messageMediaEmpty)) {
            // a page Telegram couldn't make a preview of is the only kind of media that still gets the card
            if (!(media instanceof TLRPC.TL_messageMediaWebPage) || !(media.webpage instanceof TLRPC.TL_webPageEmpty)) {
                return null;
            }
        }
        final String text = m.messageOwner.message;
        for (TLRPC.MessageEntity e : m.messageOwner.entities) {
            String candidate = null;
            if (e instanceof TLRPC.TL_messageEntityTextUrl) {
                candidate = e.url;
            } else if (e instanceof TLRPC.TL_messageEntityUrl && e.offset >= 0 && e.length > 0 && e.offset + e.length <= text.length()) {
                candidate = text.substring(e.offset, e.offset + e.length);
            }
            final String normal = normalize(candidate);
            if (normal != null) {
                return normal;
            }
        }
        return null;
    }

    @Nullable
    private static String normalize(@Nullable String link) {
        if (TextUtils.isEmpty(link)) {
            return null;
        }
        link = link.trim();
        final String lower = link.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return link;
        }
        if (lower.contains("://") || lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith("tg:")) {
            return null;
        }
        return "https://" + link;
    }

    public static boolean candidate(MessageObject m) {
        return linkOf(m) != null;
    }

    // endregion

    // region Loading

    private void waitFor(String key) {
        ArrayList<java.lang.ref.WeakReference<SolegramLinkBubble>> list = waiters.get(key);
        if (list == null) {
            waiters.put(key, list = new ArrayList<>());
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            final SolegramLinkBubble b = list.get(i).get();
            if (b == null) {
                list.remove(i);
            } else if (b == this) {
                return;
            }
        }
        list.add(new java.lang.ref.WeakReference<>(this));
    }

    private static void load(int account, String url) {
        if (!loading.add(url)) {
            return;
        }
        final TL_account.getWebPagePreview req = new TL_account.getWebPagePreview();
        req.message = url;
        ConnectionsManager.getInstance(account).sendRequestTyped(req, AndroidUtilities::runOnUIThread, (res, err) -> {
            TLRPC.WebPage page = null;
            if (res != null) {
                MessagesController.getInstance(account).putUsers(res.users, false);
                MessagesController.getInstance(account).putChats(res.chats, false);
                if (res.media != null && res.media.webpage instanceof TLRPC.TL_webPage) {
                    page = res.media.webpage;
                }
            }
            pages.put(url, page);
            loading.remove(url);
            final ArrayList<java.lang.ref.WeakReference<SolegramLinkBubble>> list = waiters.remove(url);
            if (list != null && page != null) {
                for (java.lang.ref.WeakReference<SolegramLinkBubble> ref : list) {
                    final SolegramLinkBubble b = ref.get();
                    if (b != null && b.messageObject != null && url.equals(b.url)) {
                        b.refresh(b.messageObject);
                    }
                }
            }
        });
    }

    private void refresh(MessageObject forMessage) {
        AndroidUtilities.runOnUIThread(() -> {
            if (forMessage == null || parent.getMessageObject() != forMessage || parent.getDelegate() == null) {
                return;
            }
            parent.getDelegate().forceUpdate(parent, false);
        });
    }

    // endregion

    // region Layout

    /** Measures the card for a message, or turns it off; the width is what the bubble can give. */
    public void setMessageContent(MessageObject m, int maxWidth) {
        messageObject = m;
        active = false;
        url = linkOf(m);
        if (url == null) {
            return;
        }
        try {
            host = Uri.parse(url).getHost();
        } catch (Exception e) {
            host = null;
        }
        if (TextUtils.isEmpty(host)) {
            return;
        }
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        TLRPC.WebPage page = pages.get(url);
        if (page == null && !pages.containsKey(url)) {
            waitFor(url);
            load(m.currentAccount, url);
        }
        active = true;
        layout(page, Math.max(dp(220), Math.min(maxWidth, dp(300))));
    }

    private void layout(@Nullable TLRPC.WebPage page, int w) {
        width = w;
        sitePaint.setTextSize(dp(13));
        titlePaint.setTextSize(dp(14));
        hostPaint.setTextSize(dp(12.5f));
        letterPaint.setTextSize(dp(20));

        // the picture: the page's own, else the site's first letter
        hasThumb = false;
        if (page != null && page.photo != null) {
            final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(page.photo.sizes, dp(THUMB) * 2);
            final TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(page.photo.sizes, 40);
            if (size != null) {
                thumb.setImage(ImageLocation.getForPhoto(size, page.photo), THUMB + "_" + THUMB,
                    small != null && small != size ? ImageLocation.getForPhoto(small, page.photo) : null, THUMB + "_" + THUMB + "_b",
                    0, null, messageObject, 1);
                hasThumb = true;
            }
        }
        if (!hasThumb) {
            thumb.clearImage();
        }
        letter = host.substring(0, 1).toUpperCase(Locale.ROOT);

        final String site = page != null && !TextUtils.isEmpty(page.site_name) ? page.site_name : host;
        String title = page != null && !TextUtils.isEmpty(page.title) ? page.title : null;
        if (title == null && page != null && !TextUtils.isEmpty(page.description)) {
            title = page.description;
        }
        if (title == null) {
            // no page to tell about: the address itself, without the scheme
            title = url.replaceFirst("(?i)^https?://(www\\.)?", "");
        }
        final int textWidth = width - dp(PAD) * 2 - dp(THUMB) - dp(GAP);
        siteLayout = oneLine(site, sitePaint, textWidth);
        titleLayout = lines(title.replace('\n', ' '), titlePaint, textWidth, 2);
        hostLayout = oneLine(host + "  ↗", hostPaint, textWidth);

        siteY = dp(PAD);
        titleY = siteY + siteLayout.getHeight() + dp(1);
        hostY = titleY + titleLayout.getHeight() + dp(2);
        final int textHeight = hostY + hostLayout.getHeight() + dp(PAD);
        height = Math.max(textHeight, dp(THUMB) + dp(PAD) * 2);
        // the text sits in the middle of the card when the picture is taller than it
        final int shift = (height - textHeight) / 2;
        siteY += shift;
        titleY += shift;
        hostY += shift;
    }

    private static StaticLayout oneLine(CharSequence text, TextPaint paint, int width) {
        final CharSequence shown = TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END);
        return new StaticLayout(shown, paint, Math.max(1, width), Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
    }

    private static StaticLayout lines(CharSequence text, TextPaint paint, int width, int max) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, Math.max(1, width))
            .setMaxLines(max)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build();
    }

    // endregion

    // region Drawing

    public void draw(Canvas canvas, float left, float top, boolean out) {
        if (!active) {
            return;
        }
        final Theme.ResourcesProvider rp = parent.getResourcesProvider();
        final int textColor = Theme.getColor(out ? Theme.key_chat_messageTextOut : Theme.key_chat_messageTextIn, rp);
        final int grayColor = Theme.getColor(out ? Theme.key_chat_outTimeText : Theme.key_chat_inTimeText, rp);
        final int accent = Theme.getColor(out ? Theme.key_chat_outPreviewLine : Theme.key_chat_inPreviewLine, rp);
        final int bubble = Theme.getColor(out ? Theme.key_chat_outBubble : Theme.key_chat_inBubble, rp);
        final int surface = ColorUtils.blendARGB(bubble | 0xFF000000, accent | 0xFF000000, 0.09f);
        final float r = dp(14);

        cardRect.set(left, top, left + width, top + height);
        canvas.save();
        clip.rewind();
        clip.addRoundRect(cardRect, r, r, Path.Direction.CW);
        canvas.clipPath(clip);
        paint.setColor(surface);
        canvas.drawRect(cardRect, paint);

        // the picture, or the site's letter on a soft tile of its colour
        final float tx = left + dp(PAD), ty = top + (height - dp(THUMB)) / 2f;
        if (hasThumb) {
            thumb.setImageCoords(tx, ty, dp(THUMB), dp(THUMB));
            thumb.draw(canvas);
        } else {
            rect.set(tx, ty, tx + dp(THUMB), ty + dp(THUMB));
            paint.setColor(Theme.multAlpha(accent, 0.18f));
            canvas.drawRoundRect(rect, dp(10), dp(10), paint);
            letterPaint.setColor(accent);
            final float lw = letterPaint.measureText(letter);
            final Paint.FontMetrics fm = letterPaint.getFontMetrics();
            canvas.drawText(letter, rect.centerX() - lw / 2f, rect.centerY() - (fm.ascent + fm.descent) / 2f, letterPaint);
        }

        final float textX = tx + dp(THUMB) + dp(GAP);
        sitePaint.setColor(accent);
        canvas.save();
        canvas.translate(textX, top + siteY);
        siteLayout.draw(canvas);
        canvas.restore();

        titlePaint.setColor(textColor);
        canvas.save();
        canvas.translate(textX, top + titleY);
        titleLayout.draw(canvas);
        canvas.restore();

        hostPaint.setColor(grayColor);
        canvas.save();
        canvas.translate(textX, top + hostY);
        hostLayout.draw(canvas);
        canvas.restore();

        if (pressed) {
            paint.setColor(Theme.multAlpha(textColor, 0.07f));
            canvas.drawRect(cardRect, paint);
        }
        canvas.restore();
    }

    // endregion

    // region Touch

    public boolean checkMotionEvent(MotionEvent event, float x, float y) {
        if (!active) {
            return false;
        }
        final int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!cardRect.contains(x, y)) {
                return false;
            }
            pressed = true;
            parent.invalidate();
            return true;
        }
        if (!pressed) {
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (!cardRect.contains(x, y)) {
                pressed = false;
                parent.invalidate();
            }
            return pressed;
        }
        if (action == MotionEvent.ACTION_UP) {
            pressed = false;
            parent.invalidate();
            parent.playSoundEffect(SoundEffectConstants.CLICK);
            Browser.openUrl(parent.getContext(), url);
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            pressed = false;
            parent.invalidate();
        }
        return false;
    }

    // endregion

    public void onAttachedToWindow() {
        thumb.onAttachedToWindow();
    }

    public void onDetachedFromWindow() {
        thumb.onDetachedFromWindow();
    }
}
