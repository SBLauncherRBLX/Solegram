/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.soledrop;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramScamGuard;
import com.solegram.messenger.SolegramScamRules;
import com.solegram.messenger.ui.SolegramMotion;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CameraScanActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.ContactAddActivity;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;

/**
 * SoleDrop's screen, for both phones.
 * <p>
 * Sending: the card of what is sent breathes in the middle, "hold your phone to your friend's" above it. When
 * the phones touch a strong knock and a wave of liquid run out from the top edge; the friend appears in a bubble
 * of glass that grows out of the wave. "Send" lifts the card, it shrinks and flies into the bubble with a trail
 * of light, the ring around the avatar fills while Telegram sends it, and turns into a check.
 * <p>
 * Receiving: the wave comes in from the top, the sender drops into the glass bubble, the preview of what comes
 * materialises from a blur. "Accept" waits for it in Telegram and opens the chat when it is there.
 * <p>
 * Contact exchange ("My profile", the NameDrop of iOS): both phones show the two cards trading places with a
 * glow, the friend's card stays with "Add to contacts".
 */
public class SoleDropActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, SoleDropReader.Listener, SoleDropCardView.Delegate {

    private static final int MODE_SEND = 0, MODE_RECEIVE = 1;

    private static final int S_IDLE = 0;
    private static final int S_CONTACT = 1;
    private static final int S_FOUND = 2;
    private static final int S_READY = 3;
    private static final int S_SENDING = 4;
    private static final int S_DONE = 5;
    private static final int S_EXCHANGED = 6;
    private static final int S_ASK = 10;
    private static final int S_WAITING = 11;
    private static final int S_ARRIVED = 12;
    private static final int S_TIMEOUT = 13;
    /** Receiving "always": the owner accepted, the second tap is awaited (only then this phone tells who it is). */
    private static final int S_APPROVED = 14;

    private static final int REQUEST_PHOTO = 7421, REQUEST_LOCATION = 7422;

    private final int mode;
    /** Opened without an item: the tiles to choose one, and another tap after a sending. */
    private final boolean hub;
    private SoleDropItem item;
    @Nullable
    private final SoleDropProtocol.Packet offer;
    private final int sendAccount;
    /** The first tap of receiving "always" with the app closed: the other phone got nothing yet, the owner decides. */
    private final boolean confirmFirst;

    private int state = S_IDLE;
    private SoleDropReader reader;
    @Nullable
    private SoleDropSender sender;
    @Nullable
    private TLRPC.User peer;
    private long peerId;
    private boolean arrived;
    private int session;

    // views
    private Root root;
    private SoleDropStage stage;
    private SoleDropWave wave;
    private SoleDropGlass glass;
    private SoleDropCardView card;
    @Nullable
    private SoleDropCardView contactCard;
    private SoleDropPeerView peerView;
    private LinearLayout hintBlock;
    private SoleDropPhonesView phones;
    private TextView hintTitle, hintText;
    private LinearLayout pill;
    private TextView pillText, pillAction;
    private Runnable pillActionRunnable;
    private FrameLayout bottom;
    private HorizontalScrollView tilesScroll;
    private LinearLayout tilesRow;
    private final ArrayList<Tile> tiles = new ArrayList<>();
    private LinearLayout buttons;
    private SoleDropButton primaryButton, secondaryButton;
    private View closeButton, qrButton;
    private TextView titleView;

    private SoleDropActivity(int mode, int account, @Nullable SoleDropItem item, @Nullable SoleDropProtocol.Packet offer, boolean confirmFirst) {
        this.mode = mode;
        this.confirmFirst = confirmFirst;
        this.hub = mode == MODE_SEND && item == null;
        this.item = item != null ? item : SoleDropItem.myAccount();
        this.offer = offer;
        this.sendAccount = account;
        if (offer != null) {
            peerId = offer.userId;
        }
        if (account != UserConfig.selectedAccount) {
            setCurrentAccount(account);
        }
    }

    static SoleDropActivity send(@Nullable SoleDropItem item) {
        return new SoleDropActivity(MODE_SEND, UserConfig.selectedAccount, item, null, false);
    }

    static SoleDropActivity receive(int account, SoleDropProtocol.Packet offer, boolean confirmFirst) {
        return new SoleDropActivity(MODE_RECEIVE, account, null, offer, confirmFirst);
    }

    // region Lifecycle

    @Override
    public boolean onFragmentCreate() {
        getNotificationCenter().addObserver(this, NotificationCenter.didReceiveNewMessages);
        SoleDropIdentity.refresh(currentAccount, this::updateOffer);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.didReceiveNewMessages);
        if (sender != null) {
            sender.cancel();
        }
        if (wave != null) {
            wave.cancel();
        }
        AndroidUtilities.cancelRunOnUIThread(waitTimeout);
        AndroidUtilities.cancelRunOnUIThread(rearm);
        AndroidUtilities.cancelRunOnUIThread(approvalTimeout);
        if (state == S_APPROVED) {
            SoleDrop.clearApproval();
        }
        if (root != null) {
            root.getViewTreeObserver().removeOnPreDrawListener(glassSync);
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mode == MODE_SEND) {
            SoleDrop.setActiveScreen(this);
            updateNfc();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mode == MODE_SEND) {
            SoleDrop.setActiveScreen(null);
            if (reader != null) {
                reader.stop();
            }
        }
    }

    @Override
    public boolean isSwipeBackEnabled(android.view.MotionEvent event) {
        return state == S_IDLE || state == S_ASK || state == S_DONE || state == S_EXCHANGED || state == S_TIMEOUT || state == S_APPROVED;
    }

    @Override
    public boolean isLightStatusBar() {
        return !SoleDropPalette.dark(getResourceProvider());
    }

    @Override
    public AnimatorSet onCustomTransitionAnimation(boolean isOpen, Runnable callback) {
        if (fragmentView == null || !SolegramMotion.enabled()) {
            return null;
        }
        // a sheet that rises and clears, then settles; it sinks away when closed
        final AnimatorSet set = new AnimatorSet();
        final float h = Math.max(dp(200), fragmentView.getHeight() > 0 ? fragmentView.getHeight() : AndroidUtilities.displaySize.y);
        if (isOpen) {
            fragmentView.setAlpha(0f);
            fragmentView.setTranslationY(h * 0.08f);
            set.playTogether(
                ObjectAnimator.ofFloat(fragmentView, View.ALPHA, 0f, 1f).setDuration(SolegramMotion.NORMAL),
                ObjectAnimator.ofFloat(fragmentView, View.TRANSLATION_Y, h * 0.08f, 0f).setDuration(SolegramMotion.SPRING));
            set.setInterpolator(SolegramMotion.SPRING_SMOOTH);
        } else {
            set.playTogether(
                ObjectAnimator.ofFloat(fragmentView, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(fragmentView, View.TRANSLATION_Y, 0f, h * 0.06f));
            set.setDuration(SolegramMotion.NORMAL - 40);
            set.setInterpolator(SolegramMotion.EASE_IN);
        }
        set.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                callback.run();
            }
        });
        set.start();
        return set;
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen && !backward && mode == MODE_RECEIVE && state == S_ASK && fragmentView != null) {
            // the receiving screen begins with the wave from the edge the other phone touched
            wave.start();
        }
    }
    // endregion

    // region Views

    private final ViewTreeObserver.OnPreDrawListener glassSync = () -> {
        if (glass != null) {
            glass.sync();
        }
        return true;
    };

    @Override
    public View createView(Context context) {
        final Theme.ResourcesProvider rp = getResourceProvider();
        actionBar.setAddToContainer(false);
        root = new Root(context);
        fragmentView = root;

        stage = new SoleDropStage(context, rp);
        root.addView(stage);
        glass = new SoleDropGlass(root, stage, rp);
        wave = new SoleDropWave(root, stage);
        root.getViewTreeObserver().addOnPreDrawListener(glassSync);

        // header: close, the name with its drop, and the code (QR) for phones without NFC
        closeButton = circleButton(context, R.drawable.ic_close_white, getString(R.string.Close));
        closeButton.setOnClickListener(v -> finishFragment());
        root.addView(closeButton);
        titleView = new TextView(context);
        titleView.setText(getString(R.string.SG_SoleDrop));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(SoleDropPalette.text(rp));
        titleView.setGravity(Gravity.CENTER);
        titleView.setCompoundDrawablePadding(dp(6));
        final Drawable drop = ContextCompat.getDrawable(context, R.drawable.sg_soledrop_logo);
        if (drop != null) {
            drop.setBounds(0, 0, dp(22), dp(22));
            titleView.setCompoundDrawables(drop, null, null, null);
        }
        root.addView(titleView);
        if (mode == MODE_SEND) {
            qrButton = circleButton(context, R.drawable.msg_qrcode, getString(R.string.SG_SoleDropCode));
            qrButton.setOnClickListener(v -> showCodeOptions());
            root.addView(qrButton);
        }

        // the hint at the top: two phones that touch, and what to do
        hintBlock = new LinearLayout(context);
        hintBlock.setOrientation(LinearLayout.VERTICAL);
        hintBlock.setGravity(Gravity.CENTER_HORIZONTAL);
        phones = new SoleDropPhonesView(context, rp);
        hintBlock.addView(phones, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        hintTitle = new TextView(context);
        hintTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        hintTitle.setTypeface(AndroidUtilities.bold());
        hintTitle.setTextColor(SoleDropPalette.text(rp));
        hintTitle.setGravity(Gravity.CENTER);
        hintBlock.addView(hintTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 10, 32, 0));
        hintText = new TextView(context);
        hintText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        hintText.setTextColor(SoleDropPalette.gray(rp));
        hintText.setGravity(Gravity.CENTER);
        hintBlock.addView(hintText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 40, 4, 40, 0));
        hintTitle.setText(getString(R.string.SG_SoleDropHold));
        hintText.setText(getString(R.string.SG_SoleDropHoldInfo));
        root.addView(hintBlock);

        // the other person, in glass, appears where the hint was
        peerView = new SoleDropPeerView(context, rp);
        peerView.setBackground(glass.create(peerView, dp(30), dp(10)));
        peerView.setVisibility(View.INVISIBLE);
        peerView.setPivotY(0);
        root.addView(peerView);

        card = new SoleDropCardView(context, currentAccount, rp);
        card.setDelegate(this);
        root.addView(card);
        stage.setAuraTarget(card);

        pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(18), dp(10), dp(18), dp(10));
        pill.setBackground(glass.create(pill, dp(24), dp(8)));
        pillText = new TextView(context);
        pillText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        pillText.setTextColor(SoleDropPalette.text(rp));
        pillText.setMaxLines(3);
        pill.addView(pillText, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        pillAction = new TextView(context);
        pillAction.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        pillAction.setTypeface(AndroidUtilities.bold());
        pillAction.setTextColor(SoleDropPalette.PINK);
        pillAction.setPadding(dp(12), dp(4), 0, dp(4));
        pillAction.setOnClickListener(v -> {
            if (pillActionRunnable != null) {
                pillActionRunnable.run();
            }
        });
        pill.addView(pillAction, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        pill.setVisibility(View.GONE);
        root.addView(pill);

        bottom = new FrameLayout(context);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        root.addView(bottom);
        buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setClipChildren(false);
        secondaryButton = new SoleDropButton(context, false, null, rp);
        secondaryButton.setBackground(glass.create(secondaryButton, dp(26), dp(6)));
        primaryButton = new SoleDropButton(context, true, null, rp);
        buttons.addView(secondaryButton, LayoutHelper.createLinear(0, 64, 1f, 0, 0, 4, 0));
        buttons.addView(primaryButton, LayoutHelper.createLinear(0, 64, 1f, 4, 0, 0, 0));
        buttons.setVisibility(View.GONE);
        bottom.addView(buttons, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 64, Gravity.BOTTOM, 14, 0, 14, 0));
        if (hub) {
            buildTiles(context);
        }

        if (mode == MODE_SEND) {
            reader = new SoleDropReader(this);
            setItem(item, false);
        } else {
            setupReceive();
        }
        return root;
    }

    private View circleButton(Context context, int icon, CharSequence description) {
        final ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER);
        final Drawable d = ContextCompat.getDrawable(context, icon);
        if (d != null) {
            d.mutate();
            d.setColorFilter(new PorterDuffColorFilter(SoleDropPalette.text(getResourceProvider()), PorterDuff.Mode.SRC_IN));
        }
        view.setImageDrawable(d);
        view.setBackground(glass.create(view, dp(22), dp(6)));
        view.setContentDescription(description);
        SolegramMotion.pressable(view, SolegramMotion.PRESS_BUTTON);
        return view;
    }

    /** The screen's own layout: header, the hint (or the person) at the top, the card in the space left, the controls at the bottom. */
    @SuppressLint("ViewConstructor")
    private class Root extends FrameLayout {
        private int insetTop, insetBottom;

        Root(Context context) {
            super(context);
            setClipChildren(false);
            setOnApplyWindowInsetsListener((v, insets) -> {
                insetTop = insets.getSystemWindowInsetTop();
                insetBottom = insets.getSystemWindowInsetBottom();
                requestLayout();
                return insets;
            });
        }

        int top() {
            return Math.max(insetTop, AndroidUtilities.statusBarHeight);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int w = MeasureSpec.getSize(widthMeasureSpec), h = MeasureSpec.getSize(heightMeasureSpec);
            setMeasuredDimension(w, h);
            final int exactW = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
            stage.measure(exactW, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            final int circle = MeasureSpec.makeMeasureSpec(dp(44) + dp(12), MeasureSpec.EXACTLY);
            closeButton.measure(circle, circle);
            if (qrButton != null) {
                qrButton.measure(circle, circle);
            }
            titleView.measure(MeasureSpec.makeMeasureSpec(w - dp(140), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(dp(44), MeasureSpec.EXACTLY));
            hintBlock.measure(exactW, MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
            peerView.measure(MeasureSpec.makeMeasureSpec(dp(232), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
            final int cardW = Math.min(w - dp(64), dp(300));
            final int cardSpec = MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY);
            card.measure(cardSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
            if (contactCard != null) {
                contactCard.measure(cardSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
            }
            pill.measure(MeasureSpec.makeMeasureSpec(Math.min(w - dp(32), dp(420)), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
            bottom.measure(exactW, MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            final int w = r - l, h = b - t;
            stage.layout(0, 0, w, h);
            final int top = top();
            final int bar = dp(56);
            layoutAt(closeButton, dp(10), top + (bar - closeButton.getMeasuredHeight()) / 2);
            if (qrButton != null) {
                layoutAt(qrButton, w - dp(10) - qrButton.getMeasuredWidth(), top + (bar - qrButton.getMeasuredHeight()) / 2);
            }
            layoutAt(titleView, (w - titleView.getMeasuredWidth()) / 2, top + (bar - titleView.getMeasuredHeight()) / 2);
            final int zoneTop = top + bar;
            layoutAt(hintBlock, 0, zoneTop + dp(4));
            layoutAt(peerView, (w - peerView.getMeasuredWidth()) / 2, zoneTop);
            final int zoneBottom = zoneTop + Math.max(hintBlock.getMeasuredHeight() + dp(4), peerView.getMeasuredHeight());
            final int bottomTop = h - Math.max(insetBottom, dp(8)) - dp(12) - bottom.getMeasuredHeight();
            layoutAt(bottom, 0, bottomTop);
            final int pillTop = bottomTop - dp(10) - pill.getMeasuredHeight();
            layoutAt(pill, (w - pill.getMeasuredWidth()) / 2, pillTop);
            // the card in the middle of the space left; it may reach under the hint on small screens rather than be cut
            final int spaceTop = zoneBottom + dp(18), spaceBottom = pillTop - dp(6);
            int cardTop = spaceTop + (spaceBottom - spaceTop - card.getMeasuredHeight()) / 2;
            cardTop = Math.max(zoneTop + dp(8), cardTop);
            layoutAt(card, (w - card.getMeasuredWidth()) / 2, cardTop);
            if (contactCard != null) {
                int contactTop = spaceTop + (spaceBottom - spaceTop - contactCard.getMeasuredHeight()) / 2;
                contactTop = Math.max(zoneTop + dp(8), contactTop);
                layoutAt(contactCard, (w - contactCard.getMeasuredWidth()) / 2, contactTop);
            }
        }

        private void layoutAt(View v, int x, int y) {
            v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
        }
    }
    // endregion

    // region Tiles (the SoleDrop screen opened by itself)

    private static final int[] TILE_TYPES = {
        SoleDropProtocol.ITEM_MY_ACCOUNT, SoleDropProtocol.ITEM_STARS, SoleDropProtocol.ITEM_GIFT,
        SoleDropProtocol.ITEM_PHOTO, SoleDropProtocol.ITEM_LOCATION, SoleDropProtocol.ITEM_LINK
    };
    private static final int[] TILE_NAMES = {
        R.string.SG_SoleDropMyProfile, R.string.SG_SoleDropStars, R.string.SG_SoleDropGift,
        R.string.SG_SoleDropPhoto, R.string.SG_SoleDropLocation, R.string.SG_SoleDropLink
    };

    private final class Tile extends LinearLayout {
        final int type;
        final ImageView icon;
        final TextView label;
        private boolean selected;

        Tile(Context context, int type, int name) {
            super(context);
            this.type = type;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setBackground(glass.create(this, dp(22), dp(6)));
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            final Drawable d = ContextCompat.getDrawable(context, SoleDropItem.icon(type));
            if (d != null) {
                icon.setImageDrawable(d.mutate());
            }
            addView(icon, LayoutHelper.createLinear(40, 40, Gravity.CENTER_HORIZONTAL));
            label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setText(getString(name));
            addView(label, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 4, 4, 4, 0));
            setContentDescription(getString(name));
            SolegramMotion.pressable(this, SolegramMotion.PRESS_BUTTON);
            setOnClickListener(v -> chooseTile(this.type));
            setSelectedTile(false);
        }

        void setSelectedTile(boolean selected) {
            this.selected = selected;
            final Theme.ResourcesProvider rp = getResourceProvider();
            final android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR, selected ? SoleDropPalette.BUTTON
                : new int[]{ColorUtils.setAlphaComponent(SoleDropPalette.PINK, 0x22), ColorUtils.setAlphaComponent(SoleDropPalette.VIOLET, 0x22)});
            bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            icon.setBackground(bg);
            if (icon.getDrawable() != null) {
                icon.getDrawable().setColorFilter(new PorterDuffColorFilter(selected ? 0xFFFFFFFF : SoleDropPalette.PINK, PorterDuff.Mode.SRC_IN));
            }
            label.setTextColor(selected ? SoleDropPalette.text(rp) : SoleDropPalette.gray(rp));
            label.setTypeface(selected ? AndroidUtilities.bold() : android.graphics.Typeface.DEFAULT);
            setSelected(selected);
        }
    }

    private void buildTiles(Context context) {
        tilesScroll = new HorizontalScrollView(context);
        tilesScroll.setHorizontalScrollBarEnabled(false);
        tilesScroll.setClipToPadding(false);
        tilesScroll.setPadding(dp(10), 0, dp(10), 0);
        tilesRow = new LinearLayout(context);
        tilesRow.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < TILE_TYPES.length; i++) {
            final Tile tile = new Tile(context, TILE_TYPES[i], TILE_NAMES[i]);
            tiles.add(tile);
            tilesRow.addView(tile, LayoutHelper.createLinear(88, 92, 2, 0, 2, 0));
        }
        tilesScroll.addView(tilesRow);
        bottom.addView(tilesScroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 92, Gravity.BOTTOM));
        updateTiles();
        // the row comes up tile after tile
        tilesRow.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                tilesRow.getViewTreeObserver().removeOnPreDrawListener(this);
                SolegramMotion.staggerIn(tilesRow);
                return true;
            }
        });
    }

    private void updateTiles() {
        for (Tile tile : tiles) {
            tile.setSelectedTile(tile.type == item.type);
        }
    }

    private void chooseTile(int type) {
        if (state != S_IDLE) {
            return;
        }
        SoleDrop.hapticTick(fragmentView);
        if (type == item.type) {
            if (!item.isReady()) {
                onPick(item);
            }
            return;
        }
        final SoleDropItem next;
        switch (type) {
            case SoleDropProtocol.ITEM_STARS:
                next = SoleDropItem.stars();
                break;
            case SoleDropProtocol.ITEM_GIFT:
                next = SoleDropItem.gift();
                break;
            case SoleDropProtocol.ITEM_PHOTO:
                next = SoleDropItem.photo(null);
                break;
            case SoleDropProtocol.ITEM_LOCATION:
                next = SoleDropItem.location();
                break;
            case SoleDropProtocol.ITEM_LINK:
                next = SoleDropItem.link(null);
                break;
            default:
                next = SoleDropItem.myAccount();
        }
        setItem(next, true);
        if (!next.isReady()) {
            onPick(next);
        }
    }
    // endregion

    // region Sending: the item

    private void setItem(SoleDropItem next, boolean animated) {
        item = next;
        updateTiles();
        if (animated && SolegramMotion.enabled() && card.isLaidOut()) {
            // the old card sinks and fades, the new one rises in its place
            card.animate().cancel();
            card.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(SolegramMotion.FAST).setInterpolator(SolegramMotion.EASE_IN)
                .withEndAction(() -> {
                    card.setItem(item);
                    card.setScaleX(0.9f);
                    card.setScaleY(0.9f);
                    card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(SolegramMotion.SPRING).setInterpolator(SolegramMotion.SPRING_SNAPPY).start();
                }).start();
        } else {
            card.setItem(item);
        }
        final CharSequence problem = SoleDropSender.problem(currentAccount, item);
        if (problem != null) {
            showPill(problem, null, null);
        } else {
            updateNfc();
        }
        if (reader != null) {
            reader.setAlternate(item.isNameDrop() && SoleDrop.hasHce());
        }
        if (item.type == SoleDropProtocol.ITEM_LOCATION && !item.hasLocation) {
            requestLocation();
        }
        updateOffer();
    }

    /** The offer of the next tap, with a new session (the other phone shows one card per session). */
    private void updateOffer() {
        if (mode != MODE_SEND || reader == null || item == null) {
            return;
        }
        session = Utilities.random.nextInt();
        final SoleDropProtocol.Packet packet = SoleDropProtocol.Packet.offer(session, item.type, item.title(currentAccount), item.detail(currentAccount));
        if (!SoleDropIdentity.fill(currentAccount, packet)) {
            reader.setOffer(null);
            return;
        }
        if (item.isNameDrop()) {
            packet.flags |= SoleDropProtocol.FLAG_NAMEDROP;
        }
        reader.setOffer(packet);
        reader.setArmed(state == S_IDLE && item.isReady() && SoleDropSender.problem(currentAccount, item) == null);
    }

    /** NFC state: off, missing, or ready (the reader runs while the screen is open and idle). */
    private void updateNfc() {
        if (mode != MODE_SEND || reader == null || getParentActivity() == null || isPaused()) {
            return;
        }
        if (!SoleDrop.hasNfc()) {
            hintTitle.setText(getString(R.string.SG_SoleDropNoNfc));
            hintText.setText(getString(R.string.SG_SoleDropNoNfcInfo));
            showPill(getString(R.string.SG_SoleDropScanHint), getString(R.string.SG_SoleDropScan), this::scanCode);
            return;
        }
        if (!SoleDrop.nfcEnabled()) {
            reader.stop();
            showPill(getString(R.string.SG_SoleDropNfcOff), getString(R.string.SG_SoleDropNfcTurnOn), () -> {
                try {
                    getParentActivity().startActivity(SoleDrop.nfcSettingsIntent());
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
            return;
        }
        if (state == S_IDLE && SoleDropSender.problem(currentAccount, item) == null) {
            hidePill();
        }
        reader.start(getParentActivity());
        updateOffer();
    }

    @Override
    public void onPick(SoleDropItem it) {
        if (state != S_IDLE || getParentActivity() == null) {
            return;
        }
        if (it.type == SoleDropProtocol.ITEM_PHOTO) {
            try {
                final Intent intent = new Intent(Intent.ACTION_PICK);
                intent.setType("image/*");
                getParentActivity().startActivityForResult(intent, REQUEST_PHOTO);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        } else if (it.type == SoleDropProtocol.ITEM_LINK) {
            askLink();
        } else if (it.type == SoleDropProtocol.ITEM_LOCATION) {
            requestLocation();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != REQUEST_PHOTO || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        setItem(SoleDropItem.photo(uri), true);
        // a small copy for the card, decoded away from the UI thread
        Utilities.globalQueue.postRunnable(() -> {
            final Bitmap bitmap = decodeThumb(uri, dp(300));
            AndroidUtilities.runOnUIThread(() -> {
                if (item.photo == uri) {
                    card.setPhotoBitmap(bitmap);
                }
            }, SolegramMotion.FAST + 20);
        });
    }

    @Nullable
    private static Bitmap decodeThumb(Uri uri, int size) {
        try {
            final android.content.ContentResolver resolver = ApplicationLoader.applicationContext.getContentResolver();
            if (Build.VERSION.SDK_INT >= 29) {
                return resolver.loadThumbnail(uri, new android.util.Size(size, size), null);
            }
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (java.io.InputStream in = resolver.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, Math.min(bounds.outWidth, bounds.outHeight) / size);
            try (java.io.InputStream in = resolver.openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, options);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private void askLink() {
        final Context context = getParentActivity();
        final EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        field.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, getResourceProvider()));
        field.setHint(getString(R.string.SG_SoleDropLinkHint));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_URI);
        field.setMaxLines(4);
        field.setBackground(null);
        field.setLineColors(Theme.getColor(Theme.key_dialogInputField, getResourceProvider()),
            Theme.getColor(Theme.key_dialogInputFieldActivated, getResourceProvider()), Theme.getColor(Theme.key_text_RedRegular, getResourceProvider()));
        try {
            final ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null && clipboard.getPrimaryClip().getItemCount() > 0) {
                final CharSequence clip = clipboard.getPrimaryClip().getItemAt(0).getText();
                if (clip != null && clip.length() < 1000 && android.util.Patterns.WEB_URL.matcher(clip).find()) {
                    field.setText(clip);
                    field.setSelection(field.length());
                }
            }
        } catch (Throwable ignore) {
        }
        final FrameLayout frame = new FrameLayout(context);
        frame.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 24, 6, 24, 0));
        final AlertDialog dialog = new AlertDialog.Builder(context, getResourceProvider())
            .setTitle(getString(R.string.SG_SoleDropLink))
            .setView(frame)
            .setPositiveButton(getString(R.string.OK), (d, w) -> {
                final String text = field.getText() == null ? "" : field.getText().toString().trim();
                if (!TextUtils.isEmpty(text)) {
                    setItem(SoleDropItem.link(text.length() > 4000 ? text.substring(0, 4000) : text), true);
                }
            })
            .setNegativeButton(getString(R.string.Cancel), null)
            .create();
        showDialog(dialog);
        field.requestFocus();
        AndroidUtilities.runOnUIThread(() -> AndroidUtilities.showKeyboard(field), 200);
    }

    private void requestLocation() {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
            && activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        findLocation();
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResultFragment(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCATION) {
            return;
        }
        boolean granted = false;
        for (int r : grantResults) {
            granted |= r == PackageManager.PERMISSION_GRANTED;
        }
        if (granted) {
            findLocation();
        } else {
            showPill(getString(R.string.SG_SoleDropLocationDenied), null, null);
        }
    }

    @SuppressLint("MissingPermission")
    private void findLocation() {
        final SoleDropItem it = item;
        if (it.type != SoleDropProtocol.ITEM_LOCATION) {
            return;
        }
        try {
            final LocationManager lm = (LocationManager) ApplicationLoader.applicationContext.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return;
            }
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                final Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
            if (best != null) {
                applyLocation(it, best);
            }
            // a fresh fix, when the last known one is old or missing
            if (Build.VERSION.SDK_INT >= 30 && (best == null || System.currentTimeMillis() - best.getTime() > 120_000)) {
                final String provider = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER
                    : lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : null;
                if (provider != null) {
                    lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(ApplicationLoader.applicationContext), l -> {
                        if (l != null) {
                            applyLocation(it, l);
                        }
                    });
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void applyLocation(SoleDropItem it, Location l) {
        if (item != it || state != S_IDLE && state != S_CONTACT && state != S_FOUND && state != S_READY) {
            return;
        }
        it.latitude = l.getLatitude();
        it.longitude = l.getLongitude();
        it.accuracy = l.hasAccuracy() ? l.getAccuracy() : 0;
        it.hasLocation = true;
        card.updateLocation();
        updateOffer();
    }
    // endregion

    // region Sending: the tap

    /** For the card service: this screen gives its contact (two phones on "My profile" both act as cards in turns). */
    boolean isNameDropSender() {
        return mode == MODE_SEND && state == S_IDLE && item != null && item.isNameDrop();
    }

    /**
     * A tap came in through the card service while this screen is open: two phones exchanging contacts read each
     * other, whichever was faster. Other offers open the receiving screen over this one.
     */
    boolean takeIncoming(SoleDropProtocol.Packet incoming) {
        if (mode == MODE_RECEIVE) {
            if (offer == null || fragmentView == null || incoming.userId != offer.userId || state == S_TIMEOUT) {
                return false;
            }
            if (state == S_APPROVED) {
                secondTap();
            }
            return true; // the same sender again while this screen is open: no second screen
        }
        if (!isNameDropSender() || !incoming.isNameDrop() || fragmentView == null) {
            return false;
        }
        onContact();
        foundPeer(incoming.userId, incoming.name, incoming.username, incoming.token);
        return true;
    }

    @Override
    public void onContact() {
        if (state != S_IDLE || fragmentView == null) {
            return;
        }
        state = S_CONTACT;
        if (reader != null) {
            reader.setArmed(false);
        }
        SoleDrop.hapticContact(fragmentView);
        wave.start();
        stage.flashAura();
        hidePill();
        // the hint makes way; the card gives a little start
        fadeOut(hintBlock);
        if (SolegramMotion.enabled()) {
            card.animate().cancel();
            card.animate().scaleX(1.035f).scaleY(1.035f).setDuration(120).setInterpolator(SolegramMotion.EASE_OUT)
                .withEndAction(() -> card.animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(SolegramMotion.SPRING_BOUNCY).start()).start();
        }
        if (tilesScroll != null) {
            fadeOut(tilesScroll);
        }
    }

    @Override
    public void onExchanged(SoleDropProtocol.Packet identity) {
        if (state == S_IDLE) {
            onContact();
        }
        if (state != S_CONTACT) {
            return;
        }
        foundPeer(identity.userId, identity.name, identity.username, identity.token);
    }

    @Override
    public void onFailed(int reason, boolean contactMade) {
        if (fragmentView == null || state != S_IDLE && state != S_CONTACT) {
            return;
        }
        showPill(getString(reason == SoleDropReader.FAIL_CONFIRM ? R.string.SG_SoleDropConfirmOther
            : reason == SoleDropReader.FAIL_REFUSED ? R.string.SG_SoleDropRefused : R.string.SG_SoleDropHoldLonger), null, null);
        if (state == S_CONTACT) {
            backToIdle(false);
        }
    }

    private void foundPeer(long userId, @Nullable String name, @Nullable String username, @Nullable String token) {
        if (userId != 0 && userId == getUserConfig().getClientUserId()) {
            showPill(getString(R.string.SG_SoleDropSelf), null, null);
            backToIdle(false);
            return;
        }
        state = S_FOUND;
        peerId = userId;
        peerView.resetRing();
        peerView.setPeer(userId, name, null);
        peerView.setStatus(getString(R.string.SG_SoleDropFinding));
        peerView.setRing(SoleDropPeerView.RING_SPIN);
        showPeer();
        resolve(userId, username, token, user -> {
            if (state != S_FOUND || fragmentView == null) {
                return;
            }
            if (user == null) {
                peerView.setRing(SoleDropPeerView.RING_FAIL);
                peerView.setStatus(getString(R.string.SG_SoleDropNotFound));
                AndroidUtilities.runOnUIThread(() -> backToIdle(true), 1800);
                return;
            }
            peer = user;
            peerId = user.id;
            peerView.setPeer(user.id, null, user);
            peerView.setRing(SoleDropPeerView.RING_NONE);
            fragmentView.announceForAccessibility(LocaleController.formatString(R.string.SG_SoleDropFoundA11y, UserObject.getUserName(user)));
            if (mode == MODE_SEND && item.isNameDrop()) {
                exchangeContacts();
            } else {
                state = S_READY;
                peerView.setStatus(getString(item.usesTelegramSheet() ? R.string.SG_SoleDropReadyGift : R.string.SG_SoleDropReady));
                showButtons(getString(R.string.Cancel), () -> backToIdle(true), getString(R.string.SG_SoleDropSend), this::sendNow);
            }
        });
    }

    /** Telegram finds the account behind the tap: the contact token, else the username, else a user already known. */
    private void resolve(long userId, @Nullable String username, @Nullable String token, Utilities.Callback<TLRPC.User> done) {
        final MessagesController mc = getMessagesController();
        final Runnable byUsername = () -> {
            if (username != null && SoleDropProtocol.isUsername(username)) {
                mc.getUserNameResolver().resolve(username, id -> {
                    final TLRPC.User user = id != null && id > 0 ? mc.getUser(id) : null;
                    done.run(user != null ? user : known(userId));
                });
            } else {
                done.run(known(userId));
            }
        };
        if (token == null) {
            byUsername.run();
            return;
        }
        final TLRPC.TL_contacts_importContactToken req = new TLRPC.TL_contacts_importContactToken();
        req.token = token;
        getConnectionsManager().sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (res instanceof TLRPC.User) {
                final TLRPC.User user = (TLRPC.User) res;
                mc.putUser(user, false);
                done.run(user);
            } else {
                byUsername.run();
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    @Nullable
    private TLRPC.User known(long userId) {
        final TLRPC.User user = userId > 0 ? getMessagesController().getUser(userId) : null;
        return user != null && (user.access_hash != 0 || user.contact) ? user : null;
    }

    /** The per-tap confirmation is done: the card flies to the person, Telegram sends it. */
    private void sendNow() {
        if (state != S_READY || peer == null) {
            return;
        }
        state = S_SENDING;
        hideButtons();
        peerView.setStatus(getString(R.string.SG_SoleDropSending));
        final boolean sheetItem = item.usesTelegramSheet();
        final SoleDropSender.Listener listener = new SoleDropSender.Listener() {
            @Override
            public void onSheetOpened() {
                peerView.setRing(SoleDropPeerView.RING_SPIN);
                peerView.setStatus(getString(R.string.SG_SoleDropFinishInTelegram));
            }

            @Override
            public void onProgress(float progress) {
                peerView.setRing(SoleDropPeerView.RING_PROGRESS);
                peerView.setProgress(progress);
            }

            @Override
            public void onDone() {
                sent();
            }

            @Override
            public void onFailed(@Nullable CharSequence reason) {
                notSent(reason);
            }
        };
        flyCard(() -> {
            peerView.absorb();
            stage.burst(peerView.getX() + peerView.avatarCenterX(), peerView.getY() + peerView.avatarCenterY(), 26);
            if (state != S_SENDING) {
                return;
            }
            if (sender == null) {
                peerView.setRing(SoleDropPeerView.RING_SPIN);
            }
            if (sheetItem) {
                // Telegram's own sheet, after the card arrived (so the flight is seen)
                sender = SoleDropSender.send(this, currentAccount, item, peer, listener);
            }
        });
        if (!sheetItem) {
            // the network goes while the card flies
            sender = SoleDropSender.send(this, currentAccount, item, peer, listener);
        }
    }

    private void sent() {
        if (fragmentView == null) {
            return;
        }
        state = S_DONE;
        sender = null;
        peerView.setRing(SoleDropPeerView.RING_DONE);
        peerView.setStatus(getString(R.string.SG_SoleDropSent));
        SoleDrop.hapticSuccess(fragmentView);
        fragmentView.announceForAccessibility(getString(R.string.SG_SoleDropSent));
        AndroidUtilities.runOnUIThread(() -> {
            if (state != S_DONE || isFinished) {
                return;
            }
            if (hub) {
                backToIdle(true);
            } else {
                finishFragment();
            }
        }, 1900);
    }

    private void notSent(@Nullable CharSequence reason) {
        if (fragmentView == null) {
            return;
        }
        sender = null;
        peerView.setRing(SoleDropPeerView.RING_FAIL);
        peerView.setStatus(getString(R.string.SG_SoleDropNotSent));
        if (reason != null) {
            showPill(reason, null, null);
        }
        // the card comes back down from the person, the choice is offered again
        returnCard();
        state = S_READY;
        AndroidUtilities.runOnUIThread(() -> {
            if (state == S_READY && fragmentView != null) {
                peerView.setRing(SoleDropPeerView.RING_NONE);
                peerView.setStatus(getString(R.string.SG_SoleDropReady));
                showButtons(getString(R.string.Cancel), () -> backToIdle(true), getString(R.string.SG_SoleDropRetry), this::sendNow);
            }
        }, 900);
    }

    private final Runnable rearm = () -> {
        if (state == S_IDLE) {
            updateOffer();
        }
    };

    /** Back to waiting for a tap: the person floats away, the card comes back, the hint returns. */
    private void backToIdle(boolean animated) {
        if (sender != null) {
            sender.cancel();
            sender = null;
        }
        final boolean cardHidden = card.getVisibility() != View.VISIBLE || card.getAlpha() < 1f;
        state = S_IDLE;
        peer = null;
        hideButtons();
        hidePeer();
        if (contactCard != null) {
            final View old = contactCard;
            contactCard = null;
            old.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(SolegramMotion.NORMAL).withEndAction(() -> root.removeView(old)).start();
            stage.setAuraTarget(card);
            card.setItem(item);
        }
        if (cardHidden) {
            returnCard();
        }
        fadeIn(hintBlock);
        if (tilesScroll != null) {
            fadeIn(tilesScroll);
        }
        stage.setEmitting(false);
        AndroidUtilities.cancelRunOnUIThread(rearm);
        // a moment before the next tap: phones still held together must not start again at once
        AndroidUtilities.runOnUIThread(rearm, animated ? 1200 : 900);
    }
    // endregion

    // region Contact exchange

    private void exchangeContacts() {
        state = S_EXCHANGED;
        SoleDropSender.send(this, currentAccount, SoleDropItem.myAccount(), peer, new SoleDropSender.Listener() {
            @Override
            public void onSheetOpened() {
            }

            @Override
            public void onProgress(float progress) {
            }

            @Override
            public void onDone() {
            }

            @Override
            public void onFailed(@Nullable CharSequence reason) {
            }
        });
        swapCards();
    }

    /** My card goes up to the person, theirs comes down into the middle: the two cards trade places with a glow. */
    private void swapCards() {
        final Context context = getParentActivity();
        if (context == null || peer == null) {
            return;
        }
        if (contactCard != null) {
            root.removeView(contactCard);
        }
        contactCard = new SoleDropCardView(context, currentAccount, getResourceProvider());
        contactCard.setContact(peer, getString(R.string.SG_SoleDropKindContact));
        root.addView(contactCard, root.indexOfChild(card));
        final SoleDropCardView mine = card, theirs = contactCard;
        // after layout: both centres are known
        theirs.setAlpha(0f);
        root.post(() -> {
            if (contactCard != theirs) {
                return;
            }
            final float peerCx = peerView.getX() + peerView.avatarCenterX(), peerCy = peerView.getY() + peerView.avatarCenterY();
            final float mineCx = mine.getLeft() + mine.getWidth() / 2f, mineCy = mine.getTop() + mine.getHeight() / 2f;
            final float theirsCx = theirs.getLeft() + theirs.getWidth() / 2f, theirsCy = theirs.getTop() + theirs.getHeight() / 2f;
            final float upX = peerCx - mineCx, upY = peerCy - mineCy;
            final float fromX = peerCx - theirsCx, fromY = peerCy - theirsCy;
            SoleDrop.hapticSuccess(fragmentView);
            fadeOutPeer();
            if (!SolegramMotion.enabled()) {
                mine.setVisibility(View.INVISIBLE);
                theirs.setAlpha(1f);
                stage.setAuraTarget(theirs);
                contactsSwapped();
                return;
            }
            mine.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            theirs.setLayerType(View.LAYER_TYPE_HARDWARE, null);
            stage.setEmitters(mine, theirs);
            stage.setEmitting(true);
            final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            final CubicBezierInterpolator curve = new CubicBezierInterpolator(0.45, 0, 0.15, 1);
            a.addUpdateListener(v -> {
                final float t = curve.getInterpolation((float) v.getAnimatedValue());
                final float arc = (float) Math.sin(t * Math.PI) * dp(70);
                // mine: up to the person, curving left; theirs: down from the person, curving right
                mine.setTranslationX(upX * t - arc);
                mine.setTranslationY(upY * t);
                final float ms = 1f - 0.72f * t;
                mine.setScaleX(ms);
                mine.setScaleY(ms);
                mine.setAlpha(t < 0.6f ? 1f : 1f - (t - 0.6f) / 0.4f);
                mine.setRotation(-6f * (float) Math.sin(t * Math.PI));
                theirs.setTranslationX(fromX * (1f - t) + arc);
                theirs.setTranslationY(fromY * (1f - t));
                final float ts = 0.28f + 0.72f * t;
                theirs.setScaleX(ts);
                theirs.setScaleY(ts);
                theirs.setAlpha(Math.min(1f, t * 2.2f));
                theirs.setRotation(6f * (float) Math.sin(t * Math.PI));
                stage.setAuraAlpha(Math.abs(1f - 2f * t));
                if (t > 0.5f) {
                    stage.setAuraTarget(theirs);
                }
            });
            a.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mine.setVisibility(View.INVISIBLE);
                    resetTransform(mine);
                    mine.setLayerType(View.LAYER_TYPE_NONE, null);
                    theirs.setLayerType(View.LAYER_TYPE_NONE, null);
                    theirs.setRotation(0);
                    stage.setEmitting(false);
                    stage.setAuraTarget(theirs);
                    stage.setAuraAlpha(1f);
                    stage.flashAura();
                    stage.burst(theirs.getLeft() + theirs.getWidth() / 2f, theirs.getTop() + theirs.getHeight() / 2f, 30);
                    contactsSwapped();
                }
            });
            a.setDuration(980);
            a.start();
        });
    }

    private void contactsSwapped() {
        if (peer == null || fragmentView == null) {
            return;
        }
        state = S_EXCHANGED;
        final TLRPC.User user = peer;
        final boolean isContact = user.contact || ContactsController.getInstance(currentAccount).isContact(user.id);
        showPill(LocaleController.formatString(R.string.SG_SoleDropExchanged, UserObject.getFirstName(user)), null, null);
        fragmentView.announceForAccessibility(LocaleController.formatString(R.string.SG_SoleDropExchanged, UserObject.getFirstName(user)));
        showButtons(getString(R.string.SG_SoleDropMessage), () -> openChat(user.id),
            getString(isContact ? R.string.SG_SoleDropOpenProfile : R.string.SG_SoleDropAddContact), () -> {
                if (isContact) {
                    presentFragment(ProfileActivity.of(user.id));
                } else {
                    final Bundle args = new Bundle();
                    args.putLong("user_id", user.id);
                    args.putBoolean("addContact", true);
                    presentFragment(new ContactAddActivity(args));
                }
            });
    }
    // endregion

    // region Card motion

    /** Lift, then a flight into the person's bubble along an arc: it shrinks, tilts and leaves a trail of light. */
    private void flyCard(Runnable arrived) {
        if (!SolegramMotion.enabled()) {
            card.setVisibility(View.INVISIBLE);
            stage.setAuraAlpha(0f);
            arrived.run();
            return;
        }
        final float targetX = peerView.getX() + peerView.avatarCenterX(), targetY = peerView.getY() + peerView.avatarCenterY();
        final float cx = card.getLeft() + card.getWidth() / 2f, cy = card.getTop() + card.getHeight() / 2f;
        final float dx = targetX - cx, dy = targetY - cy;
        final float lift = -dp(12);
        card.animate().cancel();
        card.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        stage.setEmitters(card, null);
        final CubicBezierInterpolator flight = new CubicBezierInterpolator(0.55, 0, 0.25, 1);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        final boolean[] emitting = {false};
        a.addUpdateListener(v -> {
            final float t = (float) v.getAnimatedValue();
            if (t < 0.2f) {
                // lift: it rises a little towards the finger and grows
                final float u = SolegramMotion.EASE_OUT.getInterpolation(t / 0.2f);
                card.setTranslationY(lift * u);
                card.setScaleX(1f + 0.05f * u);
                card.setScaleY(1f + 0.05f * u);
                stage.setAuraAlpha(1f - u * 0.6f);
                return;
            }
            if (!emitting[0]) {
                emitting[0] = true;
                stage.setEmitting(true);
            }
            final float u = flight.getInterpolation((t - 0.2f) / 0.8f);
            // a quadratic arc from the lifted card to the avatar, bowing to the side
            final float ctrlX = dx * 0.15f + dp(64), ctrlY = lift + (dy - lift) * 0.25f;
            final float iu = 1f - u;
            final float x = 2 * iu * u * ctrlX + u * u * dx;
            final float y = iu * iu * lift + 2 * iu * u * ctrlY + u * u * dy;
            card.setTranslationX(x);
            card.setTranslationY(y);
            final float s = 1.05f + (0.08f - 1.05f) * (float) Math.pow(u, 0.85);
            card.setScaleX(s);
            card.setScaleY(s * (1f + 0.08f * (float) Math.sin(u * Math.PI))); // stretched by the speed
            card.setRotation(-9f * (float) Math.sin(u * Math.PI));
            card.setAlpha(u < 0.72f ? 1f : Math.max(0f, 1f - (u - 0.72f) / 0.28f));
            stage.setAuraAlpha(0.4f * (1f - u));
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                stage.setEmitting(false);
                card.setVisibility(View.INVISIBLE);
                card.setLayerType(View.LAYER_TYPE_NONE, null);
                resetTransform(card);
                stage.setAuraAlpha(0f);
                arrived.run();
            }
        });
        a.setDuration(860);
        a.start();
    }

    /** The card comes back from the top and settles with a soft bounce. */
    private void returnCard() {
        card.animate().cancel();
        card.setVisibility(View.VISIBLE);
        if (!SolegramMotion.enabled()) {
            resetTransform(card);
            stage.setAuraAlpha(1f);
            return;
        }
        final float fromY = peerView.getY() + peerView.avatarCenterY() - (card.getTop() + card.getHeight() / 2f);
        card.setTranslationY(peerView.getVisibility() == View.VISIBLE ? fromY : -dp(60));
        card.setScaleX(0.3f);
        card.setScaleY(0.3f);
        card.setAlpha(0f);
        card.setRotation(0);
        card.animate().translationY(0).scaleX(1f).scaleY(1f).alpha(1f).setDuration(640).setInterpolator(SolegramMotion.SPRING_BOUNCY)
            .setUpdateListener(v -> stage.setAuraAlpha(v.getAnimatedFraction())).withEndAction(() -> card.animate().setUpdateListener(null)).start();
    }

    /** What comes in materialises: from a blur to sharp, from a little smaller to its size. */
    private void materialize(View view, long delay) {
        if (!SolegramMotion.enabled()) {
            return;
        }
        view.setAlpha(0f);
        view.setScaleX(0.9f);
        view.setScaleY(0.9f);
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        final boolean blur = Build.VERSION.SDK_INT >= 31 && SolegramMotion.rich();
        a.addUpdateListener(v -> {
            final float t = (float) v.getAnimatedValue();
            final float s = SolegramMotion.SPRING_SNAPPY.getInterpolation(t);
            view.setScaleX(0.9f + 0.1f * s);
            view.setScaleY(0.9f + 0.1f * s);
            view.setAlpha(Math.min(1f, t * 1.8f));
            if (blur && Build.VERSION.SDK_INT >= 31) {
                final float r = dp(22) * (1f - SolegramMotion.EASE_OUT.getInterpolation(t));
                view.setRenderEffect(r > 0.5f ? RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL) : null);
            }
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (Build.VERSION.SDK_INT >= 31) {
                    view.setRenderEffect(null);
                }
                view.setAlpha(1f);
                view.setScaleX(1f);
                view.setScaleY(1f);
            }
        });
        a.setStartDelay(delay);
        a.setDuration(620);
        a.start();
    }

    private static void resetTransform(View v) {
        v.setTranslationX(0);
        v.setTranslationY(0);
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setRotation(0);
        v.setAlpha(1f);
    }
    // endregion

    // region The person's bubble

    /** The bubble grows out of the wave at the top edge: flat and wide first, then round, with the avatar dropping in. */
    private void showPeer() {
        peerView.animate().cancel();
        peerView.setVisibility(View.VISIBLE);
        if (!SolegramMotion.enabled()) {
            resetTransform(peerView);
            peerView.content.setAlpha(1f);
            return;
        }
        final float fromY = -(peerView.getTop() - root.top() + dp(40));
        peerView.setTranslationY(fromY);
        peerView.setScaleX(1.9f);
        peerView.setScaleY(0.18f);
        peerView.setAlpha(0f);
        peerView.content.setAlpha(0f);
        final AnimatorSet set = new AnimatorSet();
        final ObjectAnimator y = ObjectAnimator.ofFloat(peerView, View.TRANSLATION_Y, fromY, 0f);
        y.setInterpolator(SolegramMotion.SPRING_GENTLE);
        y.setDuration(700);
        final ObjectAnimator sx = ObjectAnimator.ofFloat(peerView, View.SCALE_X, 1.9f, 1f);
        sx.setInterpolator(SolegramMotion.SPRING_SMOOTH);
        sx.setDuration(620);
        final ObjectAnimator sy = ObjectAnimator.ofFloat(peerView, View.SCALE_Y, 0.18f, 1f);
        sy.setInterpolator(SolegramMotion.SPRING_BOUNCY);
        sy.setDuration(760);
        final ObjectAnimator alpha = ObjectAnimator.ofFloat(peerView, View.ALPHA, 0f, 1f);
        alpha.setDuration(200);
        final ObjectAnimator content = ObjectAnimator.ofFloat(peerView.content, View.ALPHA, 0f, 1f);
        content.setStartDelay(160);
        content.setDuration(300);
        final ObjectAnimator drop = ObjectAnimator.ofFloat(peerView.avatarView, View.TRANSLATION_Y, -dp(30), 0f);
        drop.setStartDelay(120);
        drop.setDuration(700);
        drop.setInterpolator(SolegramMotion.SPRING_BOUNCY);
        set.playTogether(y, sx, sy, alpha, content, drop);
        set.start();
    }

    private void hidePeer() {
        if (peerView.getVisibility() != View.VISIBLE) {
            return;
        }
        peerView.animate().cancel();
        if (!SolegramMotion.enabled()) {
            peerView.setVisibility(View.INVISIBLE);
            return;
        }
        peerView.animate().translationY(-dp(50)).scaleX(0.86f).scaleY(0.86f).alpha(0f).setDuration(SolegramMotion.NORMAL).setInterpolator(SolegramMotion.EASE_IN)
            .withEndAction(() -> {
                peerView.setVisibility(View.INVISIBLE);
                resetTransform(peerView);
                peerView.resetRing();
            }).start();
    }

    private void fadeOutPeer() {
        peerView.animate().cancel();
        peerView.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(SolegramMotion.SLOW).setInterpolator(SolegramMotion.EASE_IN)
            .withEndAction(() -> {
                peerView.setVisibility(View.INVISIBLE);
                resetTransform(peerView);
            }).start();
    }
    // endregion

    // region Bottom: buttons and the pill

    private void showButtons(CharSequence secondary, Runnable onSecondary, CharSequence primary, Runnable onPrimary) {
        secondaryButton.setText(secondary);
        secondaryButton.setOnClickListener(v -> onSecondary.run());
        primaryButton.setText(primary);
        primaryButton.setOnClickListener(v -> {
            SoleDrop.hapticTick(v);
            onPrimary.run();
        });
        primaryButton.setEnabled(true);
        if (tilesScroll != null && tilesScroll.getVisibility() == View.VISIBLE) {
            fadeOut(tilesScroll);
        }
        if (buttons.getVisibility() != View.VISIBLE) {
            buttons.setVisibility(View.VISIBLE);
            if (SolegramMotion.enabled()) {
                for (int i = 0; i < buttons.getChildCount(); i++) {
                    final View b = buttons.getChildAt(i);
                    b.setAlpha(0f);
                    b.setTranslationY(dp(24));
                    b.animate().alpha(1f).translationY(0).setStartDelay(60L * i).setDuration(SolegramMotion.SPRING).setInterpolator(SolegramMotion.SPRING_SNAPPY).start();
                }
            }
        }
    }

    private void hideButtons() {
        if (buttons.getVisibility() != View.VISIBLE) {
            return;
        }
        if (!SolegramMotion.enabled()) {
            buttons.setVisibility(View.GONE);
            return;
        }
        for (int i = 0; i < buttons.getChildCount(); i++) {
            final View b = buttons.getChildAt(i);
            final boolean last = i == buttons.getChildCount() - 1;
            b.animate().alpha(0f).translationY(dp(16)).setStartDelay(0).setDuration(SolegramMotion.FAST).setInterpolator(SolegramMotion.EASE_IN)
                .withEndAction(last ? () -> {
                    buttons.setVisibility(View.GONE);
                    for (int k = 0; k < buttons.getChildCount(); k++) {
                        buttons.getChildAt(k).setAlpha(1f);
                        buttons.getChildAt(k).setTranslationY(0);
                    }
                } : null).start();
        }
    }

    private void showPill(CharSequence text, @Nullable CharSequence action, @Nullable Runnable onAction) {
        if (pill == null) {
            return;
        }
        pillText.setText(text);
        pillText.setTextColor(SoleDropPalette.text(getResourceProvider()));
        pillAction.setText(action);
        pillAction.setVisibility(action != null ? View.VISIBLE : View.GONE);
        pillActionRunnable = onAction;
        pill.setContentDescription(action != null ? text + ". " + action : text);
        pill.setOnClickListener(onAction != null ? v -> onAction.run() : null);
        pill.setClickable(onAction != null);
        SolegramMotion.setVisible(pill, true, true);
        if (fragmentView != null) {
            fragmentView.announceForAccessibility(text);
        }
    }

    private void hidePill() {
        if (pill != null && pill.getVisibility() == View.VISIBLE) {
            SolegramMotion.setVisible(pill, false, true);
        }
    }

    private static void fadeOut(View v) {
        v.animate().cancel();
        if (!SolegramMotion.enabled()) {
            v.setVisibility(View.INVISIBLE);
            return;
        }
        v.animate().alpha(0f).translationY(-dp(10)).setDuration(SolegramMotion.FAST).setInterpolator(SolegramMotion.EASE_IN)
            .withEndAction(() -> v.setVisibility(View.INVISIBLE)).start();
    }

    private static void fadeIn(View v) {
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        if (!SolegramMotion.enabled()) {
            v.setAlpha(1f);
            v.setTranslationY(0);
            return;
        }
        v.animate().alpha(1f).translationY(0).setStartDelay(120).setDuration(SolegramMotion.SPRING).setInterpolator(SolegramMotion.SPRING_SMOOTH).start();
    }
    // endregion

    // region The code (QR), for phones without NFC

    private void showCodeOptions() {
        if (qrButton == null || state == S_SENDING || getParentActivity() == null) {
            return;
        }
        // my code can be shown at any moment; scanning starts a new transfer, so only while nothing is going on
        final ItemOptions options = ItemOptions.makeOptions(this, qrButton);
        if (state == S_IDLE) {
            options.add(R.drawable.msg_qrcode, getString(R.string.SG_SoleDropScan), this::scanCode);
        }
        options.add(R.drawable.msg_soledrop, getString(R.string.SG_SoleDropMyCode), () -> SoleDropQrSheet.show(this, currentAccount))
            .setGravity(Gravity.RIGHT)
            .show();
    }

    private void scanCode() {
        if (state != S_IDLE || getParentActivity() == null) {
            return;
        }
        CameraScanActivity.solegramGlassNext = true;
        CameraScanActivity.showAsSheet(this, false, CameraScanActivity.TYPE_QR, new CameraScanActivity.CameraScanActivityDelegate() {
            @Override
            public void didFindQr(String text) {
                final String token = SoleDropProtocol.tokenFromLink(text);
                AndroidUtilities.runOnUIThread(() -> {
                    if (token == null) {
                        showPill(getString(R.string.SG_SoleDropBadCode), null, null);
                        return;
                    }
                    if (state != S_IDLE || !item.isReady()) {
                        return;
                    }
                    // the code is the tap: the same wave and the same person in glass
                    onContact();
                    foundPeer(0, null, null, token);
                }, 250);
            }
        });
    }
    // endregion

    // region Receiving

    private void setupReceive() {
        if (offer == null) {
            return;
        }
        state = S_ASK;
        hintBlock.setVisibility(View.INVISIBLE);
        card.setOffer(offer);
        stage.setAuraTarget(card);
        peerView.setPeer(offer.userId, offer.name, getMessagesController().getUser(offer.userId));
        updateReceiveStatus();
        showButtons(getString(R.string.SG_SoleDropDecline), this::decline, getString(R.string.SG_SoleDropAccept), confirmFirst ? this::approve : this::accept);
        if (confirmFirst) {
            showPill(getString(R.string.SG_SoleDropAsk), null, null);
        }
        root.post(() -> {
            showPeer();
            materialize(card, 260);
        });
        SoleDrop.hapticContact(root);
        resolve(offer.userId, offer.username, offer.token, user -> {
            if (fragmentView == null) {
                return;
            }
            if (user != null) {
                peer = user;
                peerId = user.id;
                peerView.setPeer(user.id, null, user);
                updateReceiveStatus();
                warnIfRisky(user);
            }
        });
    }

    private void updateReceiveStatus() {
        if (offer == null) {
            return;
        }
        final String kind = SoleDropItem.kindName(offer.item);
        peerView.setStatus(LocaleController.formatString(R.string.SG_SoleDropSends, kind.toLowerCase()));
        final CharSequence name = peerView.nameView.getText();
        peerView.setContentDescription(LocaleController.formatString(R.string.SG_SoleDropSendsA11y, name, kind));
    }

    /** Solegram's anti-scam: a stranger who looks risky gets its warning on the card. */
    private void warnIfRisky(TLRPC.User user) {
        try {
            final SolegramScamGuard.AccountRisk risk = SolegramScamGuard.accountRisk(currentAccount, user.id, null);
            if (risk == null || risk.level == SolegramScamRules.SAFE) {
                return;
            }
            final String reasons = risk.summary(2);
            showPill(TextUtils.isEmpty(reasons) ? getString(R.string.SG_SoleDropScam) : getString(R.string.SG_SoleDropScam) + "\n" + reasons, null, null);
            pillText.setTextColor(SolegramScamGuard.levelColor(risk.level));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Receiving "always": the owner accepted on the first tap. This phone now answers the same sender's next tap
     * (within a minute) with who it is; until then the other phone knows nothing about it.
     */
    private void approve() {
        if (state != S_ASK || offer == null) {
            return;
        }
        state = S_APPROVED;
        SoleDrop.approve(offer);
        hideButtons();
        peerView.setRing(SoleDropPeerView.RING_SPIN);
        peerView.setStatus(getString(R.string.SG_SoleDropTapAgain));
        showPill(getString(R.string.SG_SoleDropTapAgainInfo), null, null);
        AndroidUtilities.runOnUIThread(approvalTimeout, 60_000);
    }

    private final Runnable approvalTimeout = () -> {
        if (state != S_APPROVED || fragmentView == null) {
            return;
        }
        SoleDrop.clearApproval();
        state = S_TIMEOUT;
        peerView.setRing(SoleDropPeerView.RING_NONE);
        peerView.setStatus(getString(R.string.SG_SoleDropExpired));
        hidePill();
        showButtons(getString(R.string.Close), this::finishFragment, getString(R.string.SG_SoleDropOpenChat), () -> openChat(peerId));
    };

    /** The second tap after the owner accepted: the exchange went through, now it is as after any accepted tap. */
    private void secondTap() {
        AndroidUtilities.cancelRunOnUIThread(approvalTimeout);
        SoleDrop.clearApproval();
        hidePill();
        SoleDrop.hapticContact(fragmentView);
        wave.start();
        stage.flashAura();
        state = S_ASK;
        accept();
    }

    private void accept() {
        if (state != S_ASK || offer == null) {
            return;
        }
        hideButtons();
        if (offer.isNameDrop() || offer.item == SoleDropProtocol.ITEM_MY_ACCOUNT) {
            if (peer == null) {
                peerView.setRing(SoleDropPeerView.RING_SPIN);
                peerView.setStatus(getString(R.string.SG_SoleDropFinding));
                state = S_FOUND;
                AndroidUtilities.runOnUIThread(this::acceptContactWhenFound, 600);
                return;
            }
            acceptContact();
            return;
        }
        state = S_WAITING;
        peerView.setRing(SoleDropPeerView.RING_SPIN);
        peerView.setStatus(LocaleController.formatString(R.string.SG_SoleDropWaiting, firstName()));
        if (arrived) {
            onArrived();
            return;
        }
        AndroidUtilities.runOnUIThread(waitTimeout, offer.item == SoleDropProtocol.ITEM_STARS || offer.item == SoleDropProtocol.ITEM_GIFT ? 120_000 : 30_000);
    }

    private int contactTries;

    private void acceptContactWhenFound() {
        if (peer != null) {
            acceptContact();
        } else if (++contactTries < 10 && fragmentView != null) {
            AndroidUtilities.runOnUIThread(this::acceptContactWhenFound, 600);
        } else {
            peerView.setRing(SoleDropPeerView.RING_FAIL);
            peerView.setStatus(getString(R.string.SG_SoleDropNotFound));
        }
    }

    /** The contact exchange on the receiving side: my card appears and trades places with theirs, as on the other phone. */
    private void acceptContact() {
        state = S_EXCHANGED;
        peerView.setRing(SoleDropPeerView.RING_NONE);
        card.setItem(SoleDropItem.myAccount());
        materialize(card, 0);
        AndroidUtilities.runOnUIThread(this::swapCards, SolegramMotion.enabled() ? 520 : 0);
    }

    private String firstName() {
        if (peer != null) {
            return UserObject.getFirstName(peer);
        }
        return offer != null && !TextUtils.isEmpty(offer.name) ? offer.name : "";
    }

    private final Runnable waitTimeout = () -> {
        if (state != S_WAITING || fragmentView == null) {
            return;
        }
        state = S_TIMEOUT;
        peerView.setRing(SoleDropPeerView.RING_NONE);
        peerView.setStatus(getString(R.string.SG_SoleDropNothingYet));
        showButtons(getString(R.string.Close), this::finishFragment, getString(R.string.SG_SoleDropOpenChat), () -> openChat(peerId));
    };

    private void decline() {
        if (state != S_ASK) {
            return;
        }
        SoleDrop.clearApproval();
        state = S_TIMEOUT;
        hideButtons();
        if (SolegramMotion.enabled()) {
            card.animate().translationY(dp(80)).alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(SolegramMotion.NORMAL).setInterpolator(SolegramMotion.EASE_IN)
                .setUpdateListener(v -> stage.setAuraAlpha(1f - v.getAnimatedFraction())).start();
        }
        hidePeer();
        AndroidUtilities.runOnUIThread(this::finishFragment, SolegramMotion.enabled() ? SolegramMotion.NORMAL : 0);
    }

    /** It is in the chat: the ring fills and checks, the card settles down, the chat opens. */
    private void onArrived() {
        if (state != S_WAITING || fragmentView == null) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(waitTimeout);
        state = S_ARRIVED;
        peerView.setRing(SoleDropPeerView.RING_DONE);
        peerView.setStatus(getString(R.string.SG_SoleDropReceived));
        SoleDrop.hapticSuccess(fragmentView);
        fragmentView.announceForAccessibility(getString(R.string.SG_SoleDropReceived));
        stage.burst(peerView.getX() + peerView.avatarCenterX(), peerView.getY() + peerView.avatarCenterY(), 22);
        if (SolegramMotion.enabled()) {
            card.animate().translationY(dp(24)).scaleX(0.94f).scaleY(0.94f).setStartDelay(200).setDuration(SolegramMotion.SLOW)
                .setInterpolator(SolegramMotion.EMPHASIZED).setUpdateListener(v -> stage.setAuraAlpha(1f - 0.7f * v.getAnimatedFraction())).start();
        }
        AndroidUtilities.runOnUIThread(() -> openChat(peerId), 1100);
    }

    private void openChat(long userId) {
        if (userId == 0 || isFinished) {
            return;
        }
        final Bundle args = new Bundle();
        args.putLong("user_id", userId);
        presentFragment(new ChatActivity(args), true);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages || mode != MODE_RECEIVE || peerId == 0 || args.length < 2) {
            return;
        }
        if (!(args[0] instanceof Long) || (Long) args[0] != peerId || !(args[1] instanceof ArrayList)) {
            return;
        }
        if (args.length > 2 && Boolean.TRUE.equals(args[2])) {
            return;
        }
        for (Object o : (ArrayList<?>) args[1]) {
            if (o instanceof MessageObject && !((MessageObject) o).isOut()) {
                arrived = true;
                onArrived();
                return;
            }
        }
    }
    // endregion
}
