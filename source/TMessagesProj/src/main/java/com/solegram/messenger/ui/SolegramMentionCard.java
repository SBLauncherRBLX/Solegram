/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solegram.messenger.SolegramBadges;
import com.solegram.messenger.SolegramNameEmojiShare;
import com.solegram.messenger.SolegramProfileCard;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.ProfileActivity;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Typing someone's @username in a chat shows their profile in small above the field: the banner (their Solegram
 * banner, or their Telegram profile colours), the avatar in its shape, the name with their emoji status (the Solegram
 * one too) and Solegram badge, the username, the last seen, and the bio. A tap opens the full profile; typing on
 * hides it.
 */
public class SolegramMentionCard extends FrameLayout {

    private static final Pattern TOKEN = Pattern.compile("(?:^|[\\s(])@([A-Za-z0-9_]{4,32}) ?$");

    /** Runs the card for one chat: call {@link #onText} as the text in the field changes. */
    public static final class Controller {
        private final ChatActivity chat;
        private PopupWindow popup;
        private SolegramMentionCard card;
        private String shownFor;
        private String pending;
        private final Runnable resolveLater = this::resolvePending;

        public Controller(ChatActivity chat) {
            this.chat = chat;
        }

        public void onText(CharSequence text, int cursor) {
            // someone without a username, picked from the suggestions, is a name link in the field
            final long linked = linkedAt(text, cursor);
            if (linked != 0) {
                final String key = "#" + linked;
                if (key.equals(shownFor)) {
                    return;
                }
                final TLRPC.User u = chat.getMessagesController().getUser(linked);
                if (u != null) {
                    hide();
                    show(key, u);
                    return;
                }
            }
            final String name = tokenAt(text, cursor);
            if (name == null) {
                hide();
                return;
            }
            if (name.equalsIgnoreCase(shownFor)) {
                return;
            }
            hide();
            pending = name;
            AndroidUtilities.cancelRunOnUIThread(resolveLater);
            // a short pause, so the card does not jump at every letter while the name is still being typed
            AndroidUtilities.runOnUIThread(resolveLater, chat.getMessagesController().getUserOrChat(name) != null ? 300 : 500);
        }

        private void resolvePending() {
            final String name = pending;
            if (name == null) {
                return;
            }
            final TLObject known = chat.getMessagesController().getUserOrChat(name);
            if (known != null) {
                show(name, known);
                return;
            }
            final TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
            req.username = name;
            chat.getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (!name.equals(pending) || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                    return;
                }
                final TLRPC.TL_contacts_resolvedPeer r = (TLRPC.TL_contacts_resolvedPeer) response;
                chat.getMessagesController().putUsers(r.users, false);
                chat.getMessagesController().putChats(r.chats, false);
                final TLObject found = chat.getMessagesController().getUserOrChat(name);
                if (found != null) {
                    show(name, found);
                }
            }), ConnectionsManager.RequestFlagFailOnServerErrors);
        }

        private void show(String name, TLObject peer) {
            pending = null;
            final View anchor = chat.getChatActivityEnterView();
            if (anchor == null || chat.getParentActivity() == null || !anchor.isAttachedToWindow()) {
                return;
            }
            hide();
            chat.sgCloseMentionSuggestions();
            shownFor = name;
            card = new SolegramMentionCard(chat, peer, this::hide);
            final int width = Math.min(anchor.getWidth() - dp(24), dp(420));
            card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            popup = new PopupWindow(card, width, card.getMeasuredHeight(), false);
            popup.setBackgroundDrawable(new ColorDrawable(0));
            popup.setOutsideTouchable(false);
            popup.setClippingEnabled(true);
            popup.setAnimationStyle(org.telegram.messenger.R.style.PopupAnimation);
            final int[] at = new int[2];
            anchor.getLocationInWindow(at);
            final int x = at[0] + (anchor.getWidth() - width) / 2;
            final int y = at[1] - card.getMeasuredHeight() - dp(8);
            try {
                popup.showAtLocation(anchor, Gravity.LEFT | Gravity.TOP, x, Math.max(dp(56), y));
            } catch (Throwable e) {
                popup = null;
            }
        }

        public void hide() {
            shownFor = null;
            AndroidUtilities.cancelRunOnUIThread(resolveLater);
            pending = null;
            if (popup != null) {
                try {
                    popup.dismiss();
                } catch (Throwable ignore) {
                }
                popup = null;
            }
            card = null;
        }
    }

    /** A name link (a mention of someone without a username) that ends at the cursor, or a space before it. */
    static long linkedAt(CharSequence text, int cursor) {
        if (!(text instanceof Spanned) || cursor <= 0 || cursor > text.length()) {
            return 0;
        }
        final Spanned sp = (Spanned) text;
        final org.telegram.ui.Components.URLSpanUserMention[] spans = sp.getSpans(Math.max(0, cursor - 2), cursor, org.telegram.ui.Components.URLSpanUserMention.class);
        if (spans == null) {
            return 0;
        }
        for (org.telegram.ui.Components.URLSpanUserMention span : spans) {
            final int end = sp.getSpanEnd(span);
            if (end == cursor || end == cursor - 1 && text.charAt(cursor - 1) == ' ') {
                try {
                    return Long.parseLong(span.getURL());
                } catch (Exception ignore) {
                }
            }
        }
        return 0;
    }

    /** The username being typed right at the cursor (the whole word up to it), or null. */
    @Nullable
    static String tokenAt(CharSequence text, int cursor) {
        if (text == null || cursor <= 0 || cursor > text.length()) {
            return null;
        }
        // the cursor must end the word: "@name|" or "@name| more", not inside it
        if (cursor < text.length()) {
            final char next = text.charAt(cursor);
            if (Character.isLetterOrDigit(next) || next == '_') {
                return null;
            }
        }
        final int from = Math.max(0, cursor - 40);
        final Matcher m = TOKEN.matcher(text.subSequence(from, cursor));
        return m.find() ? m.group(1) : null;
    }

    private final ChatActivity chat;
    private final Paint bannerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final BackupImageView banner;
    private final int[] bannerColors;
    private AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable statusDrawable;

    @SuppressLint("ViewConstructor")
    private SolegramMentionCard(ChatActivity chat, TLObject peer, Runnable close) {
        super(chat.getParentActivity());
        this.chat = chat;
        final Context context = chat.getParentActivity();
        final int account = chat.getCurrentAccount();
        final Theme.ResourcesProvider rp = chat.getResourceProvider();
        setWillNotDraw(false);
        setElevation(dp(6));
        setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(20));
            }
        });

        final TLRPC.User user = peer instanceof TLRPC.User ? (TLRPC.User) peer : null;
        final TLRPC.Chat chatPeer = peer instanceof TLRPC.Chat ? (TLRPC.Chat) peer : null;
        final long dialogId = user != null ? user.id : chatPeer != null ? -chatPeer.id : 0;

        // the banner: their Solegram banner, else their Telegram profile colours, else their avatar colours
        banner = new BackupImageView(context);
        banner.setRoundRadius(0);
        addView(banner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 76, Gravity.TOP));
        final int colorId = user != null ? UserObject.getProfileColorId(user) : chatPeer != null ? ChatObject.getProfileColorId(chatPeer) : -1;
        final MessagesController.PeerColors colors = MessagesController.getInstance(account).profilePeerColors;
        final MessagesController.PeerColor pc = colors != null && colorId >= 0 ? colors.getColor(colorId) : null;
        final boolean dark = Theme.isCurrentThemeDark();
        if (pc != null) {
            bannerColors = new int[]{pc.getBgColor1(dark), pc.getBgColor2(dark)};
        } else {
            final int idx = AvatarDrawable.getColorIndex(dialogId);
            bannerColors = new int[]{Theme.getColor(Theme.keys_avatar_background[idx], rp), Theme.getColor(Theme.keys_avatar_background2[idx], rp)};
        }
        if (user != null) {
            final SolegramProfileCard.Card sc = SolegramProfileCard.get(user.id);
            showBanner(sc);
            final TLRPC.UserFull full = chat.getMessagesController().getUserFull(user.id);
            if (full == null) {
                chat.getMessagesController().loadFullUser(user, chat.getClassGuid(), false, f -> AndroidUtilities.runOnUIThread(() -> {
                    fillBio(f);
                    SolegramProfileCard.load(account, user.id, f, () -> showBanner(SolegramProfileCard.get(user.id)));
                }));
            } else {
                SolegramProfileCard.load(account, user.id, full, () -> showBanner(SolegramProfileCard.get(user.id)));
            }
        }

        // the avatar, in the avatar shape, over the banner's edge
        final BackupImageView avatar = new BackupImageView(context);
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatar.setRoundRadius(com.solegram.messenger.SolegramUi.avatarRadius(dp(60)));
        if (user != null) {
            avatarDrawable.setInfo(account, user);
            avatar.setForUserOrChat(user, avatarDrawable);
        } else if (chatPeer != null) {
            avatarDrawable.setInfo(account, chatPeer);
            avatar.setForUserOrChat(chatPeer, avatarDrawable);
        }
        final FrameLayout avatarFrame = new FrameLayout(context) {
            private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                ring.setColor(Theme.getColor(Theme.key_dialogBackground, rp));
                final float r = com.solegram.messenger.SolegramUi.avatarRadius(getWidth()) + 0f;
                rect.set(0, 0, getWidth(), getHeight());
                canvas.drawRoundRect(rect, r, r, ring);
                super.dispatchDraw(canvas);
            }
        };
        avatarFrame.addView(avatar, LayoutHelper.createFrame(60, 60, Gravity.CENTER));
        addView(avatarFrame, LayoutHelper.createFrame(66, 66, Gravity.TOP | Gravity.LEFT, 14, 42, 0, 0));

        // name, badges and status; username and last seen; bio
        final LinearLayout text = new LinearLayout(context);
        text.setOrientation(LinearLayout.VERTICAL);
        addView(text, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 16, 112, 16, 14));

        final TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setCompoundDrawablePadding(dp(4));
        CharSequence title = user != null ? UserObject.getUserName(user) : chatPeer != null ? chatPeer.title : "";
        title = org.telegram.messenger.Emoji.replaceEmoji(title, name.getPaint().getFontMetricsInt(), false);
        name.setText(title);
        // the Solegram badge, then the emoji status (a Solegram user's shared emoji arrives the same way)
        final int role = SolegramBadges.roleOf(dialogId);
        final long statusId = user != null ? DialogObject.getEmojiStatusDocumentId(user.emoji_status) : chatPeer != null ? DialogObject.getEmojiStatusDocumentId(chatPeer.emoji_status) : 0;
        android.graphics.drawable.Drawable right = null;
        if (statusId != 0) {
            statusDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(name, dp(20));
            statusDrawable.set(statusId, false);
            statusDrawable.setColor(Theme.getColor(Theme.key_profile_verifiedBackground, rp));
            right = statusDrawable;
        }
        final android.graphics.drawable.Drawable badge = role != SolegramBadges.NONE ? new SolegramBadgeDrawable(role, dp(18)) : null;
        if (badge != null) {
            badge.setBounds(0, 0, dp(18), dp(18));
        }
        if (right != null) {
            right.setBounds(0, 0, dp(20), dp(20));
        }
        if (badge != null && right != null) {
            final SpannableStringBuilder sb = new SpannableStringBuilder(title).append("  ");
            sb.setSpan(new android.text.style.ImageSpan(badge, android.text.style.DynamicDrawableSpan.ALIGN_CENTER), sb.length() - 1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            name.setText(sb);
            name.setCompoundDrawables(null, null, right, null);
        } else {
            name.setCompoundDrawables(null, null, badge != null ? badge : right, null);
        }
        text.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        final TextView sub = new TextView(context);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        sub.setTextColor(Theme.getColor(Theme.key_dialogTextGray2, rp));
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        String username = user != null ? UserObject.getPublicUsername(user) : chatPeer != null ? ChatObject.getPublicUsername(chatPeer) : null;
        String status;
        if (user != null) {
            status = user.bot ? LocaleController.getString(org.telegram.messenger.R.string.Bot) : LocaleController.formatUserStatus(account, user);
        } else if (chatPeer != null) {
            status = chatPeer.participants_count > 0 ? LocaleController.formatPluralStringComma(ChatObject.isChannelAndNotMegaGroup(chatPeer) ? "Subscribers" : "Members", chatPeer.participants_count)
                : LocaleController.getString(ChatObject.isChannelAndNotMegaGroup(chatPeer) ? org.telegram.messenger.R.string.ChannelPublic : org.telegram.messenger.R.string.MegaPublic).toLowerCase(Locale.getDefault());
        } else {
            status = "";
        }
        sub.setText((username != null ? "@" + username + "  ·  " : "") + status);
        text.addView(sub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        bio = new TextView(context);
        bio.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        bio.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        bio.setMaxLines(2);
        bio.setEllipsize(TextUtils.TruncateAt.END);
        bio.setVisibility(GONE);
        text.addView(bio, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        if (user != null) {
            fillBio(chat.getMessagesController().getUserFull(user.id));
        } else if (chatPeer != null) {
            final TLRPC.ChatFull cf = chat.getMessagesController().getChatFull(chatPeer.id);
            if (cf != null && !TextUtils.isEmpty(cf.about)) {
                bio.setText(cf.about);
                bio.setVisibility(VISIBLE);
            }
        }

        setOnClickListener(v -> {
            close.run();
            if (dialogId != 0) {
                chat.presentFragment(ProfileActivity.of(dialogId));
            }
        });
    }

    private final TextView bio;

    private void fillBio(@Nullable TLRPC.UserFull full) {
        if (full == null || TextUtils.isEmpty(full.about)) {
            return;
        }
        final String about = SolegramProfileCard.strip(SolegramNameEmojiShare.strip(full.about));
        if (TextUtils.isEmpty(about == null ? null : about.trim())) {
            return;
        }
        bio.setText(org.telegram.messenger.Emoji.replaceEmoji(about, bio.getPaint().getFontMetricsInt(), false));
        if (bio.getVisibility() != VISIBLE) {
            bio.setVisibility(VISIBLE);
            requestLayout();
        }
    }

    private void showBanner(@Nullable SolegramProfileCard.Card sc) {
        if (sc == null || !sc.hasBanner() || sc.post == null) {
            return;
        }
        final TLRPC.Document doc = sc.post.getDocument();
        if (doc != null) {
            // a GIF or a video plays as it does in the profile; a sticker-like image just shows
            final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 320);
            if (MessageObject.isGifDocument(doc) || "image/webp".equals(doc.mime_type)) {
                banner.setImage(ImageLocation.getForDocument(doc), "400_100", ImageLocation.getForDocument(thumb, doc), "400_100_b", null, doc.size, 1, sc.post);
            } else if (thumb != null) {
                banner.setImage(ImageLocation.getForDocument(thumb, doc), "400_100", (android.graphics.drawable.Drawable) null, sc.post);
            }
            return;
        }
        final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(sc.post.photoThumbs, 800);
        if (size != null) {
            banner.setImage(ImageLocation.getForObject(size, sc.post.photoThumbsObject), "400_100", (android.graphics.drawable.Drawable) null, sc.post);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (statusDrawable != null) {
            statusDrawable.attach();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (statusDrawable != null) {
            statusDrawable.detach();
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        final Theme.ResourcesProvider rp = chat.getResourceProvider();
        rect.set(0, 0, getWidth(), getHeight());
        clip.rewind();
        clip.addRoundRect(rect, dp(20), dp(20), Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        bannerPaint.setShader(null);
        bannerPaint.setColor(Theme.getColor(Theme.key_dialogBackground, rp));
        canvas.drawRect(rect, bannerPaint);
        bannerPaint.setShader(new LinearGradient(0, 0, getWidth(), dp(76), bannerColors[0], bannerColors[1], Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), dp(76), bannerPaint);
        super.dispatchDraw(canvas);
        canvas.restore();
    }
}
