/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.soledrop;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.ui.SolegramMotion;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;

import java.util.Locale;

/**
 * The card of what is being sent, in the middle of the screen: a message bubble as in the chat, an avatar, a
 * gift, the Stars, the photo, the place on a little map, a link. On the receiving phone the same card shows what
 * the offer said (its type and preview title). It lifts, shrinks and flies to the other person when sent.
 */
@SuppressLint("ViewConstructor")
public class SoleDropCardView extends FrameLayout {

    public interface Delegate {
        /** The card's empty state was tapped: pick a photo, paste a link... */
        void onPick(SoleDropItem item);
    }

    private static final int RADIUS = 28;

    private final Theme.ResourcesProvider resourcesProvider;
    private final int account;
    private final Paint surface = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    final LinearLayout column;
    private final TextView kindView;
    @Nullable
    private SoleDropItem item;
    @Nullable
    private Delegate delegate;
    private LocationGlyph locationGlyph;
    private ImageView photoView;
    private TextView photoHint;

    public SoleDropCardView(Context context, int account, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.account = account;
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);
        setClipChildren(false);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(dpf2(1f));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(RADIUS));
            }
        });
        setElevation(dp(10));
        if (Build.VERSION.SDK_INT >= 28) {
            // the card's shadow is a deep shade of the theme's accent
            final int shade = androidx.core.graphics.ColorUtils.blendARGB(SoleDropPalette.VIOLET, 0xFF000000, 0.45f);
            setOutlineSpotShadowColor(shade);
            setOutlineAmbientShadowColor(shade);
        }

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 18, 16, 18, 20));

        kindView = new TextView(context);
        kindView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        kindView.setTypeface(AndroidUtilities.bold());
        kindView.setLetterSpacing(0.04f);
        kindView.setGravity(Gravity.CENTER_VERTICAL);
        kindView.setCompoundDrawablePadding(dp(6));
        updateColors();
    }

    public void setDelegate(@Nullable Delegate delegate) {
        this.delegate = delegate;
    }

    public void updateColors() {
        surface.setColor(SoleDropPalette.surface(resourcesProvider));
        rim.setColor(SoleDropPalette.dark(resourcesProvider) ? 0x1FFFFFFF : 0x0F000000);
        kindView.setTextColor(SoleDropPalette.gray(resourcesProvider));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        rect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(rect, dp(RADIUS), dp(RADIUS), surface);
        rect.inset(dpf2(0.5f), dpf2(0.5f));
        canvas.drawRoundRect(rect, dp(RADIUS), dp(RADIUS), rim);
    }

    @Nullable
    public SoleDropItem getItem() {
        return item;
    }

    // region Building

    private void startBuild(int kindIcon, CharSequence kind) {
        column.removeAllViews();
        locationGlyph = null;
        photoView = null;
        photoHint = null;
        if (kindView.getParent() != null) {
            ((android.view.ViewGroup) kindView.getParent()).removeView(kindView);
        }
        final Drawable icon = ContextCompat.getDrawable(getContext(), kindIcon);
        if (icon != null) {
            icon.mutate();
            icon.setColorFilter(new PorterDuffColorFilter(SoleDropPalette.PINK, PorterDuff.Mode.SRC_IN));
            icon.setBounds(0, 0, dp(16), dp(16));
        }
        kindView.setCompoundDrawables(icon, null, null, null);
        kindView.setText(kind);
        column.addView(kindView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));
    }

    /** The card of an item to send. */
    public void setItem(SoleDropItem item) {
        this.item = item;
        final Context context = getContext();
        startBuild(SoleDropItem.icon(item.type), SoleDropItem.kindName(item.type));
        final String title = item.title(account), detail = item.detail(account);
        switch (item.type) {
            case SoleDropProtocol.ITEM_MESSAGES:
                buildMessages(context, item);
                break;
            case SoleDropProtocol.ITEM_MY_ACCOUNT:
                buildPeer(context, UserConfig.getInstance(account).getCurrentUser(), title, detail);
                break;
            case SoleDropProtocol.ITEM_PEER: {
                final MessagesController mc = MessagesController.getInstance(account);
                final TLObject peer = item.peerId > 0 ? mc.getUser(item.peerId) : mc.getChat(-item.peerId);
                buildPeer(context, peer, title, detail);
                break;
            }
            case SoleDropProtocol.ITEM_STARS: {
                column.addView(new StarGlyph(context), LayoutHelper.createLinear(104, 104, Gravity.CENTER_HORIZONTAL));
                addTitle(context, title, LocaleController.getString(R.string.SG_SoleDropStarsHint));
                break;
            }
            case SoleDropProtocol.ITEM_GIFT: {
                final RLottieImageView gift = new RLottieImageView(context);
                gift.setAnimation(R.raw.gift, 104, 104);
                gift.setAutoRepeat(SolegramMotion.rich());
                gift.playAnimation();
                column.addView(gift, LayoutHelper.createLinear(104, 104, Gravity.CENTER_HORIZONTAL));
                addTitle(context, title, LocaleController.getString(R.string.SG_SoleDropGiftHint));
                break;
            }
            case SoleDropProtocol.ITEM_PHOTO:
                buildPhoto(context, item);
                break;
            case SoleDropProtocol.ITEM_LOCATION:
                locationGlyph = new LocationGlyph(context);
                column.addView(locationGlyph, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 124, Gravity.CENTER_HORIZONTAL));
                addTitle(context, title, locationDetail(item));
                break;
            case SoleDropProtocol.ITEM_LINK: {
                column.addView(iconCircle(context, R.drawable.msg_link, 64), LayoutHelper.createLinear(64, 64, Gravity.CENTER_HORIZONTAL));
                final boolean empty = TextUtils.isEmpty(item.text);
                addTitle(context, empty ? LocaleController.getString(R.string.SG_SoleDropLinkPaste) : title, empty ? null : detail);
                if (empty) {
                    setPickable(true);
                }
                break;
            }
            case SoleDropProtocol.ITEM_STICKERS: {
                final BackupImageView thumb = new BackupImageView(context);
                if (item.stickerSet != null) {
                    final ImageLocation location = ImageLocation.getForStickerSet(item.stickerSet);
                    if (location != null) {
                        thumb.setImage(location, "100_100", (Drawable) null, item.stickerSet);
                    }
                }
                column.addView(thumb, LayoutHelper.createLinear(100, 100, Gravity.CENTER_HORIZONTAL));
                addTitle(context, title, detail);
                break;
            }
        }
        setContentDescription(SoleDropItem.kindName(item.type) + ": " + title + (TextUtils.isEmpty(detail) ? "" : ", " + detail));
    }

    /** The receiving phone's card: what the offer says (its type and short title), nothing more. */
    public void setOffer(SoleDropProtocol.Packet offer) {
        item = null;
        final Context context = getContext();
        startBuild(SoleDropItem.icon(offer.item), SoleDropItem.kindName(offer.item));
        column.addView(iconCircle(context, SoleDropItem.icon(offer.item), 72), LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL));
        final String title = TextUtils.isEmpty(offer.title) ? SoleDropItem.kindName(offer.item) : offer.title;
        addTitle(context, title, offer.detail);
        setContentDescription(SoleDropItem.kindName(offer.item) + ": " + title);
    }

    private void setPickable(boolean pickable) {
        if (pickable && item != null) {
            final SoleDropItem it = item;
            setOnClickListener(v -> {
                if (delegate != null) {
                    delegate.onPick(it);
                }
            });
            SolegramMotion.pressable(this, SolegramMotion.PRESS_CARD);
        } else {
            setOnClickListener(null);
            setClickable(false);
        }
    }

    private void addTitle(Context context, CharSequence title, @Nullable CharSequence detail) {
        final TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(SoleDropPalette.text(resourcesProvider));
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setText(title);
        column.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 14, 0, 0));
        if (!TextUtils.isEmpty(detail)) {
            final TextView detailView = new TextView(context);
            detailView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            detailView.setTextColor(SoleDropPalette.gray(resourcesProvider));
            detailView.setGravity(Gravity.CENTER);
            detailView.setMaxLines(2);
            detailView.setEllipsize(TextUtils.TruncateAt.END);
            detailView.setText(detail);
            column.addView(detailView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 0));
        }
    }

    private void buildPeer(Context context, @Nullable TLObject peer, CharSequence title, CharSequence detail) {
        final BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(dp(44));
        final AvatarDrawable drawable = new AvatarDrawable();
        if (peer instanceof TLRPC.User) {
            drawable.setInfo(account, (TLRPC.User) peer);
        } else if (peer instanceof TLRPC.Chat) {
            drawable.setInfo(account, (TLRPC.Chat) peer);
        }
        avatar.setForUserOrChat(peer, drawable);
        column.addView(avatar, LayoutHelper.createLinear(88, 88, Gravity.CENTER_HORIZONTAL));
        addTitle(context, title, detail);
    }

    /** The other person's contact card (the contact exchange). */
    public void setContact(TLRPC.User user, CharSequence kind) {
        item = null;
        startBuild(R.drawable.msg_contact, kind);
        final String username = org.telegram.messenger.UserObject.getPublicUsername(user);
        buildPeer(getContext(), user, org.telegram.messenger.UserObject.getUserName(user), username != null ? "@" + username : null);
        setContentDescription(kind + ": " + org.telegram.messenger.UserObject.getUserName(user));
    }

    private void buildMessages(Context context, SoleDropItem item) {
        final MessageObject first = item.messages.get(0);
        final FrameLayout stack = new FrameLayout(context);
        stack.setClipChildren(false);
        final int bubbleColor = Theme.getColor(Theme.key_chat_outBubble, resourcesProvider) | 0xFF000000;
        final int count = item.messages.size();
        // more than one: the bubbles behind peek out above, as a stack
        for (int i = Math.min(2, count - 1); i >= 1; i--) {
            final View ghost = new View(context);
            ghost.setBackground(bubble(ColorUtils.blendARGB(bubbleColor, SoleDropPalette.surface(resourcesProvider), 0.35f + 0.2f * i)));
            ghost.setScaleX(1f - 0.06f * i);
            ghost.setTranslationY(-dp(7) * i);
            stack.addView(ghost, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        final LinearLayout bubble = new LinearLayout(context);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackground(bubble(bubbleColor));
        bubble.setPadding(dp(6), dp(6), dp(6), dp(6));
        final TLRPC.PhotoSize size = first.photoThumbs != null ? FileLoader.getClosestPhotoSizeWithSize(first.photoThumbs, dp(320)) : null;
        if (size != null && (first.isPhoto() || first.isVideo() || first.isGif() || first.isRoundVideo())) {
            final BackupImageView media = new BackupImageView(context);
            media.setRoundRadius(dp(14));
            final TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(first.photoThumbs, 40);
            media.getImageReceiver().setImage(ImageLocation.getForObject(size, first.photoThumbsObject), "260_170",
                small != null && small != size ? ImageLocation.getForObject(small, first.photoThumbsObject) : null, "b", 0, null, first, 1);
            bubble.addView(media, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 160));
        } else if (first.isSticker() || first.isAnimatedSticker()) {
            final BackupImageView sticker = new BackupImageView(context);
            final TLRPC.Document document = first.getDocument();
            final TLRPC.PhotoSize thumb = document != null ? FileLoader.getClosestPhotoSizeWithSize(document.thumbs, 90) : null;
            if (document != null) {
                sticker.setImage(ImageLocation.getForDocument(document), "120_120", ImageLocation.getForDocument(thumb, document), "120_120", 0, first);
            }
            bubble.setBackground(null);
            bubble.addView(sticker, LayoutHelper.createLinear(130, 130, Gravity.CENTER_HORIZONTAL));
        }
        final String text = SoleDropItem.messageText(first);
        if (!TextUtils.isEmpty(text) || bubble.getChildCount() == 0) {
            final TextView textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            textView.setTextColor(Theme.getColor(Theme.key_chat_messageTextOut, resourcesProvider));
            textView.setMaxLines(5);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setText(TextUtils.isEmpty(text) ? SoleDropItem.messageKind(first) : text);
            textView.setPadding(dp(8), dp(4), dp(8), dp(4));
            bubble.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        stack.addView(bubble, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        // the stack's ghosts take the bubble's height
        stack.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            for (int i = 0; i < stack.getChildCount() - 1; i++) {
                final View ghost = stack.getChildAt(i);
                if (ghost.getHeight() != bubble.getHeight()) {
                    ghost.getLayoutParams().height = bubble.getHeight();
                    ghost.requestLayout();
                }
            }
        });
        column.addView(stack, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, count > 1 ? 14 : 0, 0, 0));
        if (count > 1) {
            final TextView more = new TextView(context);
            more.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            more.setTextColor(SoleDropPalette.gray(resourcesProvider));
            more.setGravity(Gravity.CENTER);
            more.setText(LocaleController.formatPluralString("messages", count));
            column.addView(more, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));
        }
    }

    private Drawable bubble(int color) {
        final GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        // a chat bubble: round, with the tail corner a little sharper
        d.setCornerRadii(new float[]{dp(18), dp(18), dp(18), dp(18), dp(6), dp(6), dp(18), dp(18)});
        return d;
    }

    private void buildPhoto(Context context, SoleDropItem item) {
        final FrameLayout frame = new FrameLayout(context);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
            }
        });
        frame.setClipToOutline(true);
        final GradientDrawable placeholder = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{
            ColorUtils.setAlphaComponent(SoleDropPalette.PINK, 0x26), ColorUtils.setAlphaComponent(SoleDropPalette.VIOLET, 0x26)});
        frame.setBackground(placeholder);
        photoView = new ImageView(context);
        photoView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(photoView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        photoHint = new TextView(context);
        photoHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        photoHint.setTypeface(AndroidUtilities.bold());
        photoHint.setTextColor(SoleDropPalette.PINK);
        photoHint.setGravity(Gravity.CENTER);
        photoHint.setCompoundDrawablePadding(dp(8));
        final Drawable icon = ContextCompat.getDrawable(context, R.drawable.msg_gallery);
        if (icon != null) {
            icon.mutate();
            icon.setColorFilter(new PorterDuffColorFilter(SoleDropPalette.PINK, PorterDuff.Mode.SRC_IN));
        }
        photoHint.setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null);
        photoHint.setText(LocaleController.getString(R.string.SG_SoleDropPhotoPick));
        frame.addView(photoHint, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        column.addView(frame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 200));
        photoHint.setVisibility(item.photo == null ? VISIBLE : GONE);
        setPickable(true);
    }

    /** The picked photo came (decoded small, off the UI thread). */
    public void setPhotoBitmap(@Nullable Bitmap bitmap) {
        if (photoView == null) {
            return;
        }
        photoView.setImageBitmap(bitmap);
        if (photoHint != null) {
            photoHint.setVisibility(bitmap == null ? VISIBLE : GONE);
        }
        if (bitmap != null && SolegramMotion.enabled()) {
            photoView.setAlpha(0f);
            photoView.setScaleX(1.06f);
            photoView.setScaleY(1.06f);
            photoView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(SolegramMotion.SLOW).setInterpolator(SolegramMotion.EASE_OUT).start();
        }
    }

    /** The location was found (or is still being looked for). */
    public void updateLocation() {
        if (item == null || item.type != SoleDropProtocol.ITEM_LOCATION || column.getChildCount() < 3) {
            return;
        }
        final View detail = column.getChildAt(column.getChildCount() - 1);
        if (detail instanceof TextView) {
            ((TextView) detail).setText(locationDetail(item));
        }
        if (locationGlyph != null) {
            locationGlyph.found = item.hasLocation;
            locationGlyph.invalidate();
        }
    }

    private static String locationDetail(SoleDropItem item) {
        if (!item.hasLocation) {
            return LocaleController.getString(R.string.SG_SoleDropLocating);
        }
        String s = String.format(Locale.US, "%.5f, %.5f", item.latitude, item.longitude);
        if (item.accuracy > 0) {
            s += " · ±" + Math.round(item.accuracy) + " " + LocaleController.getString(R.string.SG_SoleDropMeters);
        }
        return s;
    }

    private View iconCircle(Context context, int iconRes, int sizeDp) {
        final ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER);
        final Drawable icon = ContextCompat.getDrawable(context, iconRes);
        if (icon != null) {
            icon.mutate();
            icon.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        }
        view.setImageDrawable(icon);
        view.setScaleX(1f);
        final GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, SoleDropPalette.BUTTON);
        bg.setShape(GradientDrawable.OVAL);
        view.setBackground(bg);
        final int pad = dp(sizeDp) / 2 - dp(14);
        view.setPadding(pad, pad, pad, pad);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return view;
    }
    // endregion

    // region Little drawings

    /** A big golden star with a soft shine that slowly sweeps over it (Telegram Stars). */
    private static final class StarGlyph extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path star = new Path();
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();
        private final long start = SystemClock.elapsedRealtime();
        private int built;

        StarGlyph(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth(), h = getHeight();
            final float cx = w / 2f, cy = h / 2f, r = Math.min(w, h) * 0.40f;
            if (built != w) {
                built = w;
                star.reset();
                for (int i = 0; i < 10; i++) {
                    final double a = -Math.PI / 2 + i * Math.PI / 5;
                    final float rr = i % 2 == 0 ? r : r * 0.48f;
                    final float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
                    if (i == 0) {
                        star.moveTo(x, y);
                    } else {
                        star.lineTo(x, y);
                    }
                }
                star.close();
                fill.setShader(new LinearGradient(0, cy - r, 0, cy + r, 0xFFFFD84A, 0xFFFF9F0A, Shader.TileMode.CLAMP));
                fill.setPathEffect(new android.graphics.CornerPathEffect(dp(5)));
                shine.setPathEffect(fill.getPathEffect());
                shine.setShader(new LinearGradient(0, 0, r * 0.7f, 0, new int[]{0x00FFFFFF, 0x99FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                halo.setShader(new RadialGradient(cx, cy, r * 1.25f, new int[]{0x55FFC94A, 0x00FFC94A}, null, Shader.TileMode.CLAMP));
            }
            canvas.drawCircle(cx, cy, r * 1.25f, halo);
            canvas.drawPath(star, fill);
            if (SolegramMotion.rich()) {
                final float t = ((SystemClock.elapsedRealtime() - start) % 2600) / 2600f;
                matrix.setTranslate(cx - r * 1.6f + t * r * 3.2f, 0);
                matrix.postSkew(-0.4f, 0, cx, cy);
                shine.getShader().setLocalMatrix(matrix);
                canvas.drawPath(star, shine);
                postInvalidateOnAnimation();
            }
        }
    }

    /** A little map: streets on a soft field, and the pin with a halo that pulses once the place is found. */
    private final class LocationGlyph extends View {
        private final Paint field = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint street = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pin = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pulse = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path pinPath = new Path();
        private final RectF r = new RectF();
        private final long start = SystemClock.elapsedRealtime();
        boolean found;
        private int built;

        LocationGlyph(Context context) {
            super(context);
            street.setStyle(Paint.Style.STROKE);
            street.setStrokeCap(Paint.Cap.ROUND);
            pin.setColor(SoleDropPalette.PINK);
            pulse.setStyle(Paint.Style.STROKE);
            pulse.setColor(SoleDropPalette.PINK);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth(), h = getHeight();
            final boolean dark = SoleDropPalette.dark(resourcesProvider);
            if (built != w) {
                built = w;
                field.setShader(new LinearGradient(0, 0, w, h,
                    dark ? 0xFF233138 : 0xFFE6F2EA, dark ? 0xFF2A2638 : 0xFFEFE8F7, Shader.TileMode.CLAMP));
            }
            r.set(0, 0, w, h);
            canvas.drawRoundRect(r, dp(18), dp(18), field);
            street.setColor(dark ? 0x30FFFFFF : 0xB0FFFFFF);
            street.setStrokeWidth(dp(7));
            canvas.drawLine(-dp(10), h * 0.62f, w + dp(10), h * 0.38f, street);
            street.setStrokeWidth(dp(4));
            canvas.drawLine(w * 0.3f, -dp(10), w * 0.42f, h + dp(10), street);
            canvas.drawLine(w * 0.72f, -dp(10), w * 0.82f, h + dp(10), street);
            final float cx = w / 2f, cy = h / 2f + dp(6);
            final float t = ((SystemClock.elapsedRealtime() - start) % 1800) / 1800f;
            if (found) {
                pulse.setStrokeWidth(dp(2));
                pulse.setAlpha((int) (200 * (1f - t)));
                canvas.drawCircle(cx, cy, dp(8) + dp(26) * t, pulse);
            }
            // the pin
            final float pr = dp(12);
            final float bob = found || !SolegramMotion.rich() ? 0 : (float) Math.sin(t * Math.PI * 2) * dp(3);
            pinPath.reset();
            pinPath.addCircle(cx, cy - pr * 1.6f + bob, pr, Path.Direction.CW);
            pinPath.moveTo(cx - pr * 0.72f, cy - pr * 1.0f + bob);
            pinPath.lineTo(cx, cy + bob);
            pinPath.lineTo(cx + pr * 0.72f, cy - pr * 1.0f + bob);
            pinPath.close();
            canvas.drawPath(pinPath, pin);
            pulse.setAlpha(255);
            pin.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, cy - pr * 1.6f + bob, pr * 0.42f, pin);
            pin.setColor(SoleDropPalette.PINK);
            if (SolegramMotion.rich()) {
                postInvalidateOnAnimation();
            }
        }
    }
    // endregion
}
