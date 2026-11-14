/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramBadges;
import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramNameEmojiShare;
import com.solegram.messenger.SolegramProfileCard;
import com.solegram.messenger.SolegramProfileLinks;
import com.solegram.messenger.SolegramUi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AvatarDrawable;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * The profile card of someone a text message mentions (@name, or a name link), drawn in the bubble where a link
 * preview would be: a banner (their Solegram banner, else their profile colours), the avatar in the avatar shape
 * with a ring that follows that shape, the name with their emoji status (a Solegram one too) and Solegram badge,
 * the username and last seen, the bio, and the bio's links as buttons as in the profile. A message that is only the
 * mention shows the card instead of the text. Tap opens the profile, a link button opens the link.
 */
public class SolegramMentionBubble {

    private static final int BANNER = 64, AVATAR = 54, AVATAR_TOP = 36, RING = 3, SIDE = 12;
    private static final int CHIP_H = 28, CHIP_GAP = 6, CHIP_CIRCLE = 20, CHIP_ICON = 13;
    private static final int MAX_BIO_LINES = 3, MAX_CHIPS = 6;

    /** Usernames already asked for (not asked again in this run, found or not). */
    private static final HashSet<String> resolving = new HashSet<>();
    /** Users whose full info and Solegram card were already asked for. */
    private static final HashSet<Long> loadingFull = new HashSet<>();

    private final ChatMessageCell parent;
    private final ImageReceiver avatarImage;
    private final ImageReceiver bannerImage;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bioPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint chipPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final RectF cardRect = new RectF();

    private MessageObject messageObject;
    private boolean active, replacesText, attached;
    private long dialogId;
    @Nullable
    private TLRPC.User user;
    @Nullable
    private TLRPC.Chat chat;
    private int width, height;
    private StaticLayout nameLayout, subLayout, bioLayout;
    private int nameY, subY, bioY, chipsY, textX;
    private int[] bannerColors = new int[2];
    private boolean hasBannerImage;
    private AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable statusDrawable;
    private long statusId;
    @Nullable
    private Drawable badge;
    private final ArrayList<Chip> chips = new ArrayList<>();

    private boolean pressed;
    private int pressedChip = -1;

    private static final class Chip {
        SolegramProfileLinks.Link link;
        Drawable icon;
        int brand;
        StaticLayout label;
        final RectF rect = new RectF();
    }

    public SolegramMentionBubble(ChatMessageCell parent) {
        this.parent = parent;
        avatarImage = new ImageReceiver(parent);
        avatarImage.setAllowLoadingOnAttachedOnly(true);
        bannerImage = new ImageReceiver(parent);
        bannerImage.setAllowLoadingOnAttachedOnly(true);
        namePaint.setTypeface(AndroidUtilities.bold());
        chipPaint.setTypeface(AndroidUtilities.bold());
    }

    public boolean isActive() {
        return active;
    }

    /** The message is only the mention: the card takes the text's place. */
    public boolean replacesText() {
        return active && replacesText;
    }

    public int getMeasuredWidth() {
        return active ? width : 0;
    }

    public int getMeasuredHeight() {
        return active ? height : 0;
    }

    // region Finding the mention

    /** Whether a message gets a card at all (without looking anyone up): a plain text that mentions someone. */
    public static boolean candidate(MessageObject m) {
        if (!SolegramConfig.mentionCards || m == null || m.messageOwner == null || m.type != MessageObject.TYPE_TEXT
            || m.isSponsored() || m.isRepostPreview || m.isRestrictedMessage || TextUtils.isEmpty(m.messageOwner.message)
            || m.messageOwner.entities == null || m.messageOwner.entities.isEmpty()) {
            return false;
        }
        final TLRPC.MessageMedia media = m.messageOwner.media;
        if (media != null && !(media instanceof TLRPC.TL_messageMediaEmpty)) {
            return false;
        }
        for (TLRPC.MessageEntity e : m.messageOwner.entities) {
            if (e instanceof TLRPC.TL_messageEntityMention || e instanceof TLRPC.TL_messageEntityMentionName || e instanceof TLRPC.TL_inputMessageEntityMentionName) {
                return true;
            }
        }
        return false;
    }

    /** Measures the card for a message, or turns it off; the width is what the bubble can give. */
    public void setMessageContent(MessageObject m, int maxWidth) {
        messageObject = m;
        active = false;
        replacesText = false;
        user = null;
        chat = null;
        dialogId = 0;
        if (!candidate(m)) {
            return;
        }
        final int account = m.currentAccount;
        final MessagesController controller = MessagesController.getInstance(account);
        final String text = m.messageOwner.message;
        TLRPC.MessageEntity found = null;
        TLObject peer = null;
        for (TLRPC.MessageEntity e : m.messageOwner.entities) {
            if (e.offset < 0 || e.length <= 0 || e.offset + e.length > text.length()) {
                continue;
            }
            if (e instanceof TLRPC.TL_messageEntityMentionName) {
                peer = controller.getUser(((TLRPC.TL_messageEntityMentionName) e).user_id);
            } else if (e instanceof TLRPC.TL_inputMessageEntityMentionName && ((TLRPC.TL_inputMessageEntityMentionName) e).user_id != null) {
                peer = controller.getUser(((TLRPC.TL_inputMessageEntityMentionName) e).user_id.user_id);
            } else if (e instanceof TLRPC.TL_messageEntityMention) {
                final String name = text.substring(e.offset, e.offset + e.length).replace("@", "").trim();
                if (name.length() < 4) {
                    continue;
                }
                peer = controller.getUserOrChat(name);
                if (peer == null) {
                    resolve(account, name);
                    continue;
                }
            } else {
                continue;
            }
            if (peer != null) {
                found = e;
                break;
            }
        }
        if (found == null) {
            return;
        }
        if (peer instanceof TLRPC.User) {
            user = (TLRPC.User) peer;
            dialogId = user.id;
        } else if (peer instanceof TLRPC.Chat) {
            chat = (TLRPC.Chat) peer;
            dialogId = -chat.id;
        } else {
            return;
        }
        replacesText = text.trim().length() == found.length && text.trim().equals(text.substring(found.offset, found.offset + found.length).trim());
        active = true;
        layout(account, Math.max(dp(220), Math.min(maxWidth, dp(300))));
    }

    private void resolve(int account, String name) {
        final String key = account + ":" + name.toLowerCase();
        if (!resolving.add(key)) {
            return;
        }
        final MessageObject waitingFor = messageObject;
        MessagesController.getInstance(account).getUserNameResolver().resolve(name, id ->
            AndroidUtilities.runOnUIThread(() -> {
                if (id != null && id != 0) {
                    refresh(waitingFor);
                }
            }));
    }

    /** The cell lays the message out again once something the card shows arrived (still the same message). */
    private void refresh(MessageObject forMessage) {
        // never from inside a layout pass (a cache can answer at once): on the next loop
        AndroidUtilities.runOnUIThread(() -> {
            if (forMessage == null || parent.getMessageObject() != forMessage || parent.getDelegate() == null) {
                return;
            }
            parent.getDelegate().forceUpdate(parent, false);
        });
    }

    // endregion

    // region Layout

    private void layout(int account, int w) {
        width = w;
        final Theme.ResourcesProvider rp = parent.getResourcesProvider();
        namePaint.setTextSize(dp(15));
        subPaint.setTextSize(dp(13));
        bioPaint.setTextSize(dp(14));
        chipPaint.setTextSize(dp(13));

        // banner: the Solegram banner, else the profile colours, else the avatar colours
        final MessagesController controller = MessagesController.getInstance(account);
        final int colorId = user != null ? UserObject.getProfileColorId(user) : chat != null ? ChatObject.getProfileColorId(chat) : -1;
        final MessagesController.PeerColors colors = controller.profilePeerColors;
        final MessagesController.PeerColor pc = colors != null && colorId >= 0 ? colors.getColor(colorId) : null;
        final boolean dark = rp != null ? rp.isDark() : Theme.isCurrentThemeDark();
        if (pc != null) {
            bannerColors = new int[]{pc.getBgColor1(dark), pc.getBgColor2(dark)};
        } else {
            final int idx = AvatarDrawable.getColorIndex(dialogId);
            bannerColors = new int[]{Theme.getColor(Theme.keys_avatar_background[idx], rp), Theme.getColor(Theme.keys_avatar_background2[idx], rp)};
        }
        hasBannerImage = false;
        TLRPC.UserFull full = null;
        if (user != null) {
            full = controller.getUserFull(user.id);
            final SolegramProfileCard.Card card = SolegramProfileCard.get(user.id);
            if (card != null && card.hasBanner() && card.post != null) {
                hasBannerImage = setBanner(card.post);
            }
            if (loadingFull.add(user.id)) {
                final MessageObject waitingFor = messageObject;
                final TLRPC.User u = user;
                if (full == null) {
                    controller.loadFullUser(u, 0, false, f -> AndroidUtilities.runOnUIThread(() -> {
                        SolegramProfileCard.load(account, u.id, f, () -> refresh(waitingFor));
                        refresh(waitingFor);
                    }));
                } else {
                    SolegramProfileCard.load(account, u.id, full, () -> refresh(waitingFor));
                }
            }
        }
        if (!hasBannerImage) {
            bannerImage.clearImage();
        }

        // avatar: the profile shape (Telegram's radius scaled by the avatar corners option)
        avatarImage.solegramShape = SolegramUi.SHAPE_DEFAULT;
        avatarDrawable.solegramShape = SolegramUi.SHAPE_DEFAULT;
        avatarImage.setRoundRadius(dp(AVATAR) / 2);
        if (user != null) {
            avatarDrawable.setInfo(account, user);
            avatarImage.setForUserOrChat(user, avatarDrawable);
        } else {
            avatarDrawable.setInfo(account, chat);
            avatarImage.setForUserOrChat(chat, avatarDrawable);
        }

        textX = dp(SIDE + AVATAR + 10);
        final int textWidth = width - textX - dp(SIDE);

        // the name, then the emoji status and the Solegram badge after it
        statusId = user != null ? DialogObject.getEmojiStatusDocumentId(user.emoji_status) : chat != null ? DialogObject.getEmojiStatusDocumentId(chat.emoji_status) : 0;
        final boolean self = user != null && UserObject.isUserSelf(user);
        final int role = SolegramBadges.roleOf(dialogId);
        badge = role != SolegramBadges.NONE ? new SolegramBadgeDrawable(role, dp(17)) : null;
        final int extras = (statusId != 0 ? dp(24) : 0) + (badge != null ? dp(21) : 0);
        CharSequence title = user != null ? UserObject.getUserName(user) : chat.title;
        title = Emoji.replaceEmoji(title == null ? "" : title, namePaint.getFontMetricsInt(), false);
        final CharSequence nameText = TextUtils.ellipsize(title, namePaint, Math.max(dp(40), textWidth - extras), TextUtils.TruncateAt.END);
        nameLayout = new StaticLayout(nameText, namePaint, Math.max(dp(40), textWidth), Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
        if (statusId != 0) {
            if (statusDrawable == null) {
                statusDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(parent, dp(20));
            }
            statusDrawable.set(statusId, false);
            if (attached) {
                statusDrawable.attach();
            }
        }

        // the username and last seen / members
        final String username = user != null ? UserObject.getPublicUsername(user) : ChatObject.getPublicUsername(chat);
        String status;
        if (user != null) {
            status = self ? LocaleController.getString(R.string.Online) : user.bot ? LocaleController.getString(R.string.Bot) : LocaleController.formatUserStatus(account, user);
        } else if (chat.participants_count > 0) {
            status = LocaleController.formatString(ChatObject.isChannelAndNotMegaGroup(chat) ? R.string.SG_MentionCardSubscribers : R.string.SG_MentionCardMembers, LocaleController.formatNumber(chat.participants_count, ','));
        } else {
            status = LocaleController.getString(ChatObject.isChannelAndNotMegaGroup(chat) ? R.string.ChannelPublic : R.string.MegaPublic);
        }
        final String sub = (username != null ? "@" + username + "  ·  " : "") + status;
        subLayout = new StaticLayout(TextUtils.ellipsize(sub, subPaint, textWidth, TextUtils.TruncateAt.END), subPaint, Math.max(dp(40), textWidth), Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);

        nameY = dp(BANNER + 6);
        subY = nameY + nameLayout.getHeight() + dp(1);
        int y = Math.max(dp(AVATAR_TOP + AVATAR), subY + subLayout.getHeight()) + dp(8);

        // the bio, its links as buttons (as in the profile)
        String about = null;
        if (full != null) {
            about = full.about;
        } else if (chat != null) {
            final TLRPC.ChatFull cf = controller.getChatFull(chat.id);
            about = cf != null ? cf.about : null;
        }
        bioLayout = null;
        chips.clear();
        if (!TextUtils.isEmpty(about)) {
            about = SolegramProfileCard.strip(SolegramNameEmojiShare.strip(about));
            final SolegramProfileLinks.Parsed parsed = SolegramProfileLinks.parse(about);
            final String bio = parsed.text != null ? parsed.text.trim() : "";
            if (!bio.isEmpty()) {
                final int bioWidth = width - dp(SIDE * 2);
                final CharSequence bioText = Emoji.replaceEmoji(bio, bioPaint.getFontMetricsInt(), false);
                bioLayout = StaticLayout.Builder.obtain(bioText, 0, bioText.length(), bioPaint, bioWidth)
                    .setMaxLines(MAX_BIO_LINES).setEllipsize(TextUtils.TruncateAt.END).build();
                bioY = y;
                y += bioLayout.getHeight() + dp(8);
            }
            if (parsed.links != null && !parsed.links.isEmpty()) {
                chipsY = y;
                y = layoutChips(parsed.links, y);
            }
        }
        height = y + dp(4);
    }

    private boolean setBanner(MessageObject post) {
        final TLRPC.Document doc = post.getDocument();
        if (doc != null) {
            final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 320);
            if (MessageObject.isGifDocument(doc) || "image/webp".equals(doc.mime_type)) {
                bannerImage.setImage(ImageLocation.getForDocument(doc), null, ImageLocation.getForDocument(thumb, doc), "b", null, doc.size, null, post, 1);
                bannerImage.setAutoRepeat(1);
                bannerImage.setAllowStartAnimation(true);
                return true;
            } else if (thumb != null) {
                bannerImage.setImage(ImageLocation.getForDocument(thumb, doc), null, null, null, 0, null, post, 1);
                return true;
            }
            return false;
        }
        final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(post.photoThumbs, 800);
        if (size == null) {
            return false;
        }
        bannerImage.setImage(ImageLocation.getForObject(size, post.photoThumbsObject), null, null, null, 0, null, post, 1);
        return true;
    }

    private int layoutChips(java.util.List<SolegramProfileLinks.Link> links, int y) {
        final int maxRight = width - dp(SIDE);
        int x = dp(SIDE);
        int rows = 1;
        for (int i = 0; i < links.size() && chips.size() < MAX_CHIPS; i++) {
            final SolegramProfileLinks.Link link = links.get(i);
            final Chip c = new Chip();
            c.link = link;
            final Drawable d = ContextCompat.getDrawable(parent.getContext(), link.platform.icon);
            c.icon = d == null ? null : d.mutate();
            c.brand = !link.platform.isWebsite() ? link.platform.color : Theme.getColor(Theme.key_featuredStickers_addButton, parent.getResourcesProvider());
            if (c.icon != null) {
                c.icon.setColorFilter(new PorterDuffColorFilter(ColorUtils.calculateLuminance(c.brand) > 0.6 ? 0xFF000000 : 0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
            }
            final int labelMax = maxRight - dp(SIDE) - dp(4 + CHIP_CIRCLE + 6 + 10);
            final CharSequence label = TextUtils.ellipsize(link.label(), chipPaint, Math.max(dp(40), labelMax), TextUtils.TruncateAt.END);
            c.label = new StaticLayout(label, chipPaint, (int) Math.ceil(chipPaint.measureText(label, 0, label.length())) + 1, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
            final int w = dp(4 + CHIP_CIRCLE + 6) + c.label.getWidth() + dp(10);
            if (x + w > maxRight && x > dp(SIDE)) {
                if (rows == 2) {
                    break;
                }
                rows++;
                x = dp(SIDE);
                y += dp(CHIP_H + CHIP_GAP);
            }
            c.rect.set(x, y, x + w, y + dp(CHIP_H));
            x += w + dp(CHIP_GAP);
            chips.add(c);
        }
        return chips.isEmpty() ? y : y + dp(CHIP_H) + dp(8);
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
        final int bubble = Theme.getColor(out ? Theme.key_chat_outBubble : Theme.key_chat_inBubble, rp);
        final int surface = ColorUtils.blendARGB(bubble | 0xFF000000, textColor | 0xFF000000, 0.07f);
        final float r = dp(14);

        cardRect.set(left, top, left + width, top + height);
        canvas.save();
        clip.rewind();
        clip.addRoundRect(cardRect, r, r, Path.Direction.CW);
        canvas.clipPath(clip);

        paint.setShader(null);
        paint.setColor(surface);
        canvas.drawRect(cardRect, paint);

        // banner
        final float bannerBottom = top + dp(BANNER);
        if (hasBannerImage) {
            bannerImage.setImageCoords(left, top, width, dp(BANNER));
            bannerImage.draw(canvas);
        } else {
            paint.setShader(new LinearGradient(left, top, left + width, bannerBottom, bannerColors[0], bannerColors[1], Shader.TileMode.CLAMP));
            canvas.drawRect(left, top, left + width, bannerBottom, paint);
            paint.setShader(null);
        }

        // avatar with a ring that follows its shape: the same corners, grown by the ring's width
        final float ax = left + dp(SIDE), ay = top + dp(AVATAR_TOP), size = dp(AVATAR), ring = dp(RING);
        final float avatarRadius = SolegramUi.avatarRadius(dp(AVATAR));
        paint.setColor(surface);
        rect.set(ax - ring, ay - ring, ax + size + ring, ay + size + ring);
        canvas.drawRoundRect(rect, avatarRadius + ring, avatarRadius + ring, paint);
        avatarImage.setImageCoords(ax, ay, size, size);
        avatarImage.draw(canvas);

        // name, status, badge
        namePaint.setColor(textColor);
        canvas.save();
        canvas.translate(left + textX, top + nameY);
        final com.solegram.messenger.SolegramNameEffect.Renderer nameFx = user != null
            ? com.solegram.messenger.SolegramNameEffect.renderer(com.solegram.messenger.SolegramNameEffect.of(messageObject.currentAccount, user.id)) : null;
        if (nameFx != null) {
            nameFx.draw(parent, canvas, nameLayout, namePaint);
        } else {
            nameLayout.draw(canvas);
        }
        canvas.restore();
        float x = left + textX + (nameLayout.getLineCount() > 0 ? nameLayout.getLineWidth(0) : 0) + dp(4);
        final float midY = top + nameY + nameLayout.getHeight() / 2f;
        if (badge != null) {
            badge.setBounds((int) x, (int) (midY - dp(17) / 2f), (int) x + dp(17), (int) (midY + dp(17) / 2f));
            badge.draw(canvas);
            x += dp(21);
        }
        if (statusId != 0 && statusDrawable != null) {
            statusDrawable.setColor(Theme.getColor(Theme.key_chat_inPreviewInstantText, rp));
            statusDrawable.setBounds((int) x, (int) (midY - dp(10)), (int) x + dp(20), (int) (midY + dp(10)));
            statusDrawable.draw(canvas);
        }

        subPaint.setColor(grayColor);
        canvas.save();
        canvas.translate(left + textX, top + subY);
        subLayout.draw(canvas);
        canvas.restore();

        if (bioLayout != null) {
            bioPaint.setColor(textColor);
            canvas.save();
            canvas.translate(left + dp(SIDE), top + bioY);
            bioLayout.draw(canvas);
            canvas.restore();
        }

        for (int i = 0; i < chips.size(); i++) {
            final Chip c = chips.get(i);
            rect.set(c.rect);
            rect.offset(left, top);
            paint.setColor(Theme.multAlpha(textColor, pressedChip == i ? 0.16f : 0.08f));
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint);
            final float cx = rect.left + dp(4) + dp(CHIP_CIRCLE) / 2f, cy = rect.centerY();
            paint.setColor(c.brand);
            canvas.drawCircle(cx, cy, dp(CHIP_CIRCLE) / 2f, paint);
            if (c.icon != null) {
                final int half = dp(CHIP_ICON) / 2;
                c.icon.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
                c.icon.draw(canvas);
            }
            chipPaint.setColor(textColor);
            canvas.save();
            canvas.translate(rect.left + dp(4 + CHIP_CIRCLE + 6), cy - c.label.getHeight() / 2f);
            c.label.draw(canvas);
            canvas.restore();
        }

        if (pressed && pressedChip < 0) {
            paint.setColor(Theme.multAlpha(textColor, 0.06f));
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
            pressedChip = -1;
            for (int i = 0; i < chips.size(); i++) {
                rect.set(chips.get(i).rect);
                rect.offset(cardRect.left, cardRect.top);
                if (rect.contains(x, y)) {
                    pressedChip = i;
                    break;
                }
            }
            parent.invalidate();
            return true;
        }
        if (!pressed) {
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (!cardRect.contains(x, y)) {
                pressed = false;
                pressedChip = -1;
                parent.invalidate();
            }
            return pressed;
        }
        if (action == MotionEvent.ACTION_UP) {
            final int chip = pressedChip;
            pressed = false;
            pressedChip = -1;
            parent.invalidate();
            parent.playSoundEffect(SoundEffectConstants.CLICK);
            if (chip >= 0 && chip < chips.size()) {
                Browser.openUrl(parent.getContext(), chips.get(chip).link.url());
            } else if (parent.getDelegate() != null) {
                if (user != null) {
                    parent.getDelegate().didPressUserAvatar(parent, user, x, y, false);
                } else if (chat != null) {
                    parent.getDelegate().didPressChannelAvatar(parent, chat, 0, x, y, false);
                }
            }
            return true;
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            pressed = false;
            pressedChip = -1;
            parent.invalidate();
        }
        return false;
    }

    // endregion

    public void onAttachedToWindow() {
        attached = true;
        avatarImage.onAttachedToWindow();
        bannerImage.onAttachedToWindow();
        if (statusDrawable != null) {
            statusDrawable.attach();
        }
    }

    public void onDetachedFromWindow() {
        attached = false;
        avatarImage.onDetachedFromWindow();
        bannerImage.onDetachedFromWindow();
        if (statusDrawable != null) {
            statusDrawable.detach();
        }
    }
}
