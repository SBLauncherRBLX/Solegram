/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.solegram.messenger.SolegramGlass;
import com.solegram.messenger.SolegramUi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.utils.RenderNodeEffects;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarLayout;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.LaunchActivity;
import org.telegram.utils.glass.source.GlassSourceRenderNode;

import java.util.ArrayList;

/**
 * Solegram: the side dock. Two fingers drawn to the right across a chat put it here (up to {@link #MAX} chats per
 * account); a tap opens it from any screen, a long press takes it out. A vertical pill of glass at the top right,
 * under the header's buttons, as far from the edge as the header's own pill.
 * <p>
 * It lies in the screens' layout over the screens, but under everything that opens over them: menus and sheets of
 * the layout come after it, the windows of popups and dialogs are above it anyway, and while a screen's own header
 * menu or an attached sheet is open (they are drawn inside the screen) it steps aside.
 */
@SuppressLint("ViewConstructor")
public final class SolegramChatDock extends FrameLayout implements NotificationCenter.NotificationCenterDelegate, ViewTreeObserver.OnPreDrawListener {

    public static final int MAX = 3;
    private static final String KEY = "sg_chat_dock";
    private static final String KEY_HINT = "sg_chat_dock_hint";

    private static final int AVATAR = 48, PAD = 5, GAP = 8, EDGE = 8, GLASS_PAD = 6;

    @Nullable
    private static SolegramChatDock instance;

    private final ActionBarLayout root;
    private final Pill pill;
    private final ArrayList<Long> dialogs = new ArrayList<>();
    private int account = -1;
    /** The account's user the list belongs to (another login in the same slot has its own). */
    private long userId;
    private boolean shown;
    private float shownProgress;
    private long current;

    @Nullable
    private GlassSourceRenderNode source;
    @Nullable
    private BlurredBackgroundDrawable glassShape;
    @Nullable
    private GradientDrawable plainShape;
    private float shapeRadius = -1;
    private final ArrayList<View> flying = new ArrayList<>();

    private SolegramChatDock(Context context, ActionBarLayout root) {
        super(context);
        this.root = root;
        setClipChildren(false);
        setClipToPadding(false);

        pill = new Pill(context);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setClipChildren(false);
        pill.setClipToPadding(false);
        pill.setPadding(dp(GLASS_PAD + PAD), dp(GLASS_PAD + PAD), dp(GLASS_PAD + PAD), dp(GLASS_PAD + PAD));
        pill.setBackground(createBackground());
        pill.setPivotX(dp(GLASS_PAD + PAD + AVATAR) + dp(PAD));
        pill.setPivotY(dp(GLASS_PAD));
        pill.setAlpha(0f);
        pill.setVisibility(INVISIBLE);
        addView(pill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT));
        updatePosition();
    }

    /** Puts the dock into the main layout of the activity (once). */
    public static void attach(ActionBarLayout layout) {
        if (layout == null || instance != null && instance.root == layout && instance.getParent() == layout) {
            return;
        }
        if (instance != null) {
            AndroidUtilities.removeFromParent(instance);
        }
        instance = new SolegramChatDock(layout.getContext(), layout);
        layout.solegramSetDock(instance);
    }

    // region Glass

    /**
     * The app's liquid glass (the tab bar's colours and the glass settings) over a live picture of the screens under
     * it, recorded each frame from the layout's screens; a plain rounded panel where that can't be (before Android 12).
     */
    private android.graphics.drawable.Drawable createBackground() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                final GlassSourceRenderNode glassSource = new GlassSourceRenderNode(null);
                glassSource.setupRenderer(new RenderNodeWithHash.Renderer() {
                    @Override
                    public void renderNodeUpdateDisplayList(Canvas canvas) {
                        drawScreens(canvas);
                    }
                });
                final float blur = dpf2(10f) * SolegramGlass.blurScale();
                if (SolegramGlass.vibrancy()) {
                    glassSource.setBlur(blur, RenderNodeEffects.getSaturationX2RenderEffect());
                } else {
                    glassSource.setBlur(blur);
                }
                final BlurredBackgroundDrawableViewFactory factory = new BlurredBackgroundDrawableViewFactory(glassSource);
                factory.setSourceRootView(new ViewPositionWatcher(root), root);
                factory.setLiquidGlassEffectAllowed(true);
                final BlurredBackgroundDrawable drawable = factory.create(pill, BlurredBackgroundProviderImpl.mainTabs(null));
                drawable.setPadding(dp(GLASS_PAD));
                source = glassSource;
                glassShape = drawable;
                applyShape();
                return drawable;
            } catch (Throwable e) {
                FileLog.e(e);
                source = null;
            }
        }
        final GradientDrawable plain = new GradientDrawable();
        plainShape = plain;
        applyShape();
        plain.setColor(Theme.multAlpha(Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground), 0.94f));
        plain.setStroke(Math.max(1, dp(0.66f)), Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.08f));
        return new android.graphics.drawable.InsetDrawable(plain, dp(GLASS_PAD));
    }

    /** The avatars' corner radius at a size (the "avatar corners" setting: a circle at 100%, a square at 0%). */
    static float avatarRadius(float size) {
        return size / 2f * SolegramUi.avatarPercent(SolegramUi.SHAPE_DEFAULT) / 100f;
    }

    /** The pill's corners follow the avatars' shape (the avatars' radius and the padding around them). */
    private void applyShape() {
        final float radius = avatarRadius(dp(AVATAR)) + dp(PAD);
        if (radius == shapeRadius) {
            return;
        }
        shapeRadius = radius;
        if (glassShape != null) {
            glassShape.setRadius(radius);
        }
        if (plainShape != null) {
            plainShape.setCornerRadius(radius);
        }
        for (int i = 0; i < pill.getChildCount(); i++) {
            pill.getChildAt(i).invalidate();
        }
        pill.invalidate();
    }

    /** What is under the dock: the layout's screens (the children before it), where they are now. */
    private void drawScreens(Canvas canvas) {
        final int index = root.indexOfChild(this);
        for (int i = 0; i < index; i++) {
            final View child = root.getChildAt(i);
            if (child.getVisibility() != VISIBLE || child.getAlpha() <= 0f || child.getWidth() <= 0) {
                continue;
            }
            final int count = canvas.save();
            canvas.translate(child.getLeft(), child.getTop());
            final android.graphics.Matrix matrix = child.getMatrix(); // its move and scale (a chat going into the dock)
            if (!matrix.isIdentity()) {
                canvas.concat(matrix);
            }
            child.draw(canvas);
            canvas.restoreToCount(count);
        }
    }

    // endregion

    // region State

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        root.getViewTreeObserver().addOnPreDrawListener(this);
        bindAccount(UserConfig.selectedAccount);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        root.getViewTreeObserver().removeOnPreDrawListener(this);
        if (account >= 0) {
            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.updateInterfaces);
            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.dialogsNeedReload);
        }
        account = -1;
    }

    private void bindAccount(int value) {
        final long user = UserConfig.getInstance(value).getClientUserId();
        if (account == value && userId == user) {
            return;
        }
        userId = user;
        if (account >= 0) {
            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.updateInterfaces);
            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.dialogsNeedReload);
        }
        account = value;
        if (user == 0) {
            dialogs.clear();
            rebuild();
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.updateInterfaces);
            NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.dialogsNeedReload);
            return;
        }
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.dialogsNeedReload);
        dialogs.clear();
        dialogs.addAll(load(account));
        rebuild();
    }

    private static String key(int account) {
        return KEY + "_" + UserConfig.getInstance(account).getClientUserId();
    }

    private static ArrayList<Long> load(int account) {
        final ArrayList<Long> list = new ArrayList<>();
        final String value = MessagesController.getMainSettings(account).getString(key(account), null);
        if (!TextUtils.isEmpty(value)) {
            for (String part : value.split(",")) {
                try {
                    final long did = Long.parseLong(part.trim());
                    if (did != 0 && !list.contains(did) && list.size() < MAX) {
                        list.add(did);
                    }
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return list;
    }

    private void save() {
        if (account < 0) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        for (long did : dialogs) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(did);
        }
        MessagesController.getMainSettings(account).edit().putString(key(account), sb.toString()).apply();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != this.account) {
            return;
        }
        if (id == NotificationCenter.updateInterfaces || id == NotificationCenter.dialogsNeedReload) {
            for (int i = 0; i < pill.getChildCount(); i++) {
                final View child = pill.getChildAt(i);
                if (child instanceof Item) {
                    ((Item) child).bind();
                }
            }
        }
    }

    /** Below the header (and a top panel of a chat under it), at the header pill's distance from the edge. */
    private void updatePosition() {
        final LayoutParams lp = (LayoutParams) pill.getLayoutParams();
        final int top = AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight() + dp(56) - dp(GLASS_PAD);
        final int right = dp(EDGE) - dp(GLASS_PAD);
        if (lp.topMargin != top || lp.rightMargin != right) {
            lp.topMargin = top;
            lp.rightMargin = right;
            pill.setLayoutParams(lp);
        }
    }

    @Override
    public boolean onPreDraw() {
        if (UserConfig.selectedAccount != account || UserConfig.getInstance(account).getClientUserId() != userId) {
            bindAccount(UserConfig.selectedAccount);
        }
        updatePosition();
        applyShape();
        // the chat open now is ringed
        final BaseFragment last = LaunchActivity.getLastFragment();
        final long open = last instanceof ChatActivity && ((ChatActivity) last).solegramCanDock() ? ((ChatActivity) last).getDialogId() : 0;
        if (open != current) {
            current = open;
            for (int i = 0; i < pill.getChildCount(); i++) {
                pill.getChildAt(i).invalidate();
            }
        }
        // in two panes (a tablet, a phone on its side) the chats are always on screen: no dock over them
        setShown(!dialogs.isEmpty() && UserConfig.getInstance(account).isClientActivated() && !blocked(last) && !AndroidUtilities.isTablet());
        if (source != null && shownProgress > 0f && getWidth() > 0 && getHeight() > 0) {
            source.setSize(root.getWidth(), root.getHeight());
            source.updateDisplayListIfNeeded();
        }
        return true;
    }

    /** Something of the screen itself is open where the dock is: its header menu, a sheet drawn in it. */
    private static boolean blocked(@Nullable BaseFragment fragment) {
        if (fragment == null) {
            return true;
        }
        if (fragment.solegramHasOpenOverlay()) {
            return true;
        }
        final BaseFragment tab = LaunchActivity.getLastFragmentIncludeMainTabs();
        return tab != null && tab != fragment && tab.solegramHasOpenOverlay();
    }

    private void setShown(boolean value) {
        if (shown == value) {
            return;
        }
        shown = value;
        pill.animate().cancel();
        if (value) {
            pill.setVisibility(VISIBLE);
        }
        if (value && pill.getAlpha() <= 0f) {
            // it grows out of its corner (the top right, by the header)
            pill.setScaleX(0.6f);
            pill.setScaleY(0.6f);
        }
        pill.animate()
            .alpha(value ? 1f : 0f)
            .scaleX(value ? 1f : 0.6f)
            .scaleY(value ? 1f : 0.6f)
            .setDuration(value ? 420 : 280)
            .setInterpolator(value ? CubicBezierInterpolator.EASE_OUT_QUINT : CubicBezierInterpolator.EASE_OUT)
            .setUpdateListener(a -> shownProgress = pill.getAlpha())
            .withEndAction(() -> {
                shownProgress = pill.getAlpha();
                if (!shown) {
                    pill.setVisibility(INVISIBLE);
                }
            })
            .start();
        if (value) {
            shownProgress = Math.max(shownProgress, 0.01f);
        }
    }

    private void rebuild() {
        pill.removeAllViews();
        for (int i = 0; i < dialogs.size(); i++) {
            addItem(dialogs.get(i), i);
        }
    }

    private Item addItem(long did, int index) {
        final Item item = new Item(getContext(), did);
        final LinearLayout.LayoutParams lp = LayoutHelper.createLinear(AVATAR, AVATAR);
        lp.topMargin = index > 0 ? dp(GAP) : 0;
        final int at = Math.min(index, pill.getChildCount());
        pill.addView(item, at, lp);
        fixMargins();
        // the ones after it make room smoothly (the pill grows with them)
        shift(at + 1, -(dp(AVATAR) + dp(GAP)));
        return item;
    }

    /** The places from {@code from} on were moved by {@code by} in the layout: they glide there from where they were. */
    private void shift(int from, float by) {
        if (pill.getVisibility() != VISIBLE || pill.getAlpha() <= 0f) {
            return;
        }
        for (int i = from; i < pill.getChildCount(); i++) {
            final View child = pill.getChildAt(i);
            child.setTranslationY(child.getTranslationY() + by);
            child.animate().translationY(0).setDuration(360).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }
    }

    private void fixMargins() {
        for (int i = 0; i < pill.getChildCount(); i++) {
            final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pill.getChildAt(i).getLayoutParams();
            final int top = i > 0 ? dp(GAP) : 0;
            if (lp.topMargin != top) {
                lp.topMargin = top;
                pill.getChildAt(i).setLayoutParams(lp);
            }
        }
    }

    // endregion

    // region Docking

    public static final int ACCEPT = 0, ALREADY = 1, FULL = 2, UNAVAILABLE = 3;

    /** Whether a chat can go into the dock now. */
    public static int state(long did) {
        final SolegramChatDock dock = instance;
        if (dock == null || dock.getParent() == null) {
            return UNAVAILABLE;
        }
        dock.bindAccount(UserConfig.selectedAccount);
        if (dock.dialogs.contains(did)) {
            return ALREADY;
        }
        return dock.dialogs.size() >= MAX ? FULL : ACCEPT;
    }

    /** The dock is full: says so (the chat stays). */
    public static void showFull(@Nullable BaseFragment fragment) {
        if (fragment == null) {
            return;
        }
        try {
            if (fragment.getFragmentView() != null) {
                fragment.getFragmentView().performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
        } catch (Throwable ignore) {
        }
        BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.SG_DockFull)).show();
    }

    /**
     * Where the chat's place in the dock is (its own, or the next free one), in the coordinates of the layout:
     * {@code out} = {centre x, centre y, size}. False when there is no dock or no room.
     */
    public static boolean slot(long did, float[] out) {
        final SolegramChatDock dock = instance;
        if (dock == null || dock.getParent() == null || dock.root.getWidth() <= 0) {
            return false;
        }
        int index = dock.dialogs.indexOf(did);
        if (index < 0) {
            if (dock.dialogs.size() >= MAX) {
                return false;
            }
            index = dock.dialogs.size();
        }
        dock.updatePosition();
        final LayoutParams lp = (LayoutParams) dock.pill.getLayoutParams();
        final float size = dp(AVATAR);
        final float right = dock.root.getWidth() - lp.rightMargin - dp(GLASS_PAD + PAD);
        out[0] = right - size / 2f;
        out[1] = lp.topMargin + dp(GLASS_PAD + PAD) + index * (size + dp(GAP)) + size / 2f;
        out[2] = size;
        return true;
    }

    /** Puts the chat into the dock (it flies there from the fingers). False: the dock is full, nothing changed. */
    public static boolean dock(ChatActivity chat, float x, float y) {
        return dock(chat, x, y, true, 0);
    }

    /**
     * Puts the chat into the dock. {@code fly}: a small picture flies there from (x, y); else the place fills in
     * after {@code revealDelay} ms (the chat itself shrinks into it). False: the dock is full, nothing changed.
     */
    public static boolean dock(ChatActivity chat, float x, float y, boolean fly, long revealDelay) {
        final SolegramChatDock dock = instance;
        if (dock == null || chat == null || dock.getParent() == null) {
            return false;
        }
        final long did = chat.getDialogId();
        final int state = state(did);
        if (state == ALREADY) {
            dock.postDelayed(() -> dock.pop(did), fly ? 0 : revealDelay);
            return true;
        }
        if (state != ACCEPT) {
            if (state == FULL) {
                showFull(chat);
            }
            return false;
        }
        dock.dialogs.add(did);
        dock.save();
        final Item item = dock.addItem(did, dock.dialogs.size() - 1);
        if (fly) {
            item.setVisibility(INVISIBLE);
            dock.fly(item, x, y);
        } else {
            // the chat shrinks into this place: it fills in as the chat arrives
            item.setAlpha(0f);
            item.setScaleX(0.5f);
            item.setScaleY(0.5f);
            item.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(revealDelay).setDuration(260)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
            dock.setShownNow();
        }
        try {
            dock.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Throwable ignore) {
        }
        final SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        if (!prefs.getBoolean(KEY_HINT, false)) {
            prefs.edit().putBoolean(KEY_HINT, true).apply();
            AndroidUtilities.runOnUIThread(() -> {
                final BaseFragment last = LaunchActivity.getLastFragment();
                if (last != null) {
                    BulletinFactory.of(last).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.SG_DockHint)).show();
                }
            }, 600);
        }
        return true;
    }

    /** The chat flies from the fingers to its place in the dock, then the place shows it. */
    private void fly(Item item, float x, float y) {
        final BackupImageView flyer = new BackupImageView(getContext());
        flyer.setRoundRadius(dp(28)); // scaled by the avatar corners setting as every avatar is
        item.bindImage(flyer);
        final int size = dp(56);
        addView(flyer, new LayoutParams(size, size));
        flyer.setTranslationX(x - size / 2f);
        flyer.setTranslationY(y - size / 2f);
        flyer.setScaleX(0.6f);
        flyer.setScaleY(0.6f);
        flyer.setAlpha(0f);
        flying.add(flyer);
        // the place is known once the pill is laid out again (it may be hidden until now: it shows as the chat flies)
        post(() -> {
            setShownNow();
            final float tx = pill.getLeft() + item.getLeft() + item.getWidth() / 2f - size / 2f;
            final float ty = pill.getTop() + item.getTop() + item.getHeight() / 2f - size / 2f;
            final float scale = item.getWidth() / (float) size;
            flyer.animate()
                .translationX(tx).translationY(ty)
                .scaleX(scale).scaleY(scale)
                .alpha(1f)
                .setDuration(420)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        item.setVisibility(VISIBLE);
                        removeView(flyer);
                        flying.remove(flyer);
                        item.setScaleX(1.12f);
                        item.setScaleY(1.12f);
                        item.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
                    }
                })
                .start();
        });
    }

    private void setShownNow() {
        if (!dialogs.isEmpty()) {
            onPreDraw();
        }
    }

    /** An already docked chat: its place nods. */
    private void pop(long did) {
        for (int i = 0; i < pill.getChildCount(); i++) {
            final View child = pill.getChildAt(i);
            if (child instanceof Item && ((Item) child).did == did) {
                child.animate().cancel();
                child.setScaleX(1.15f);
                child.setScaleY(1.15f);
                child.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
            }
        }
    }

    private void open(long did) {
        final BaseFragment last = LaunchActivity.getLastFragment();
        if (last == null || last.getParentActivity() == null) {
            return;
        }
        if (last instanceof ChatActivity && ((ChatActivity) last).solegramCanDock() && ((ChatActivity) last).getDialogId() == did) {
            pop(did);
            return;
        }
        final Bundle args = new Bundle();
        if (DialogObject.isEncryptedDialog(did)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(did));
        } else if (DialogObject.isUserDialog(did)) {
            args.putLong("user_id", did);
        } else {
            args.putLong("chat_id", -did);
        }
        if (!MessagesController.getInstance(account).checkCanOpenChat(args, last)) {
            return;
        }
        last.presentFragment(new ChatActivity(args));
    }

    /** A long press: out of the dock, with a way back. */
    private void remove(Item item) {
        final long did = item.did;
        final int index = dialogs.indexOf(did);
        if (index < 0) {
            return;
        }
        dialogs.remove(index);
        save();
        try {
            item.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (Throwable ignore) {
        }
        item.animate().cancel();
        if (dialogs.isEmpty()) {
            setShown(false); // the last one: the whole pill goes with it
        }
        item.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).setDuration(170).withEndAction(() -> {
            final int at = pill.indexOfChild(item);
            pill.removeView(item);
            fixMargins();
            // the ones after it close the gap smoothly, the pill shrinks with them
            shift(Math.max(0, at), dp(AVATAR) + dp(GAP));
        }).start();
        final BaseFragment last = LaunchActivity.getLastFragment();
        if (last != null) {
            BulletinFactory.of(last).createSimpleBulletin(R.raw.ic_delete, LocaleController.getString(R.string.SG_DockRemoved),
                LocaleController.getString(R.string.SG_DockUndo), () -> {
                    if (dialogs.contains(did) || dialogs.size() >= MAX) {
                        return;
                    }
                    final int at = Math.min(index, dialogs.size());
                    dialogs.add(at, did);
                    save();
                    final Item back = addItem(did, at);
                    back.setAlpha(0f);
                    back.setScaleX(0.4f);
                    back.setScaleY(0.4f);
                    back.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
                }).show();
        }
    }

    // endregion

    /**
     * The pill: its height changes smoothly when a chat comes or goes (the glass follows it), and what is in it stays
     * inside its rounded glass.
     */
    private final class Pill extends LinearLayout {
        private float shownHeight = -1;
        private int targetHeight = -1;
        @Nullable
        private android.animation.ValueAnimator heightAnimator;
        private final android.graphics.Path clip = new android.graphics.Path();

        Pill(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            final int target = getMeasuredHeight();
            final boolean animate = getVisibility() == VISIBLE && getAlpha() > 0f && isAttachedToWindow() && shownHeight >= 0;
            if (!animate) {
                if (heightAnimator != null) {
                    heightAnimator.cancel();
                    heightAnimator = null;
                }
                shownHeight = target;
                targetHeight = target;
            } else if (target != targetHeight) {
                targetHeight = target;
                if (heightAnimator != null) {
                    heightAnimator.cancel();
                }
                final float from = shownHeight;
                final android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(0f, 1f);
                animator.addUpdateListener(a -> {
                    shownHeight = AndroidUtilities.lerp(from, target, (float) a.getAnimatedValue());
                    requestLayout();
                });
                animator.setDuration(360);
                animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
                heightAnimator = animator;
                animator.start();
            }
            setMeasuredDimension(getMeasuredWidth(), Math.round(shownHeight));
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            // the glass is inset by its shadow: the avatars stay within it while it grows or shrinks
            final float inset = dp(GLASS_PAD);
            final float radius = Math.max(0, shapeRadius);
            clip.rewind();
            AndroidUtilities.rectTmp.set(inset, inset, getWidth() - inset, getHeight() - inset);
            clip.addRoundRect(AndroidUtilities.rectTmp, radius, radius, android.graphics.Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            super.dispatchDraw(canvas);
            canvas.restore();
        }
    }

    /** A chat in the dock: its picture, a dot for unread messages, a ring while it is open. */
    private final class Item extends FrameLayout {
        final long did;
        private final BackupImageView image;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Item(Context context, long did) {
            super(context);
            this.did = did;
            setWillNotDraw(false);
            image = new BackupImageView(context);
            image.setRoundRadius(dp(AVATAR) / 2); // the "avatar corners" setting makes it the avatars' shape
            addView(image, LayoutHelper.createFrame(AVATAR, AVATAR));
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(2));
            setOnClickListener(v -> open(did));
            setOnLongClickListener(v -> {
                remove(this);
                return true;
            });
            SolegramMotion.pressable(this, SolegramMotion.PRESS_BUTTON);
            bind();
        }

        @Nullable
        private TLObject peer() {
            final MessagesController controller = MessagesController.getInstance(account);
            if (DialogObject.isEncryptedDialog(did)) {
                final TLRPC.EncryptedChat encrypted = controller.getEncryptedChat(DialogObject.getEncryptedChatId(did));
                return encrypted != null ? controller.getUser(encrypted.user_id) : null;
            } else if (DialogObject.isUserDialog(did)) {
                return controller.getUser(did);
            } else {
                return controller.getChat(-did);
            }
        }

        void bind() {
            bindImage(image);
            final TLObject peer = peer();
            String name = null;
            if (peer instanceof TLRPC.User) {
                name = UserObject.isUserSelf((TLRPC.User) peer) ? LocaleController.getString(R.string.SavedMessages) : UserObject.getUserName((TLRPC.User) peer);
            } else if (peer instanceof TLRPC.Chat) {
                name = ((TLRPC.Chat) peer).title;
            }
            setContentDescription(name);
            invalidate();
        }

        void bindImage(BackupImageView target) {
            final TLObject peer = peer();
            if (peer instanceof TLRPC.User && UserObject.isUserSelf((TLRPC.User) peer) && !DialogObject.isEncryptedDialog(did)) {
                avatarDrawable.setAvatarType(AvatarDrawable.AVATAR_TYPE_SAVED);
                target.setImageDrawable(avatarDrawable);
            } else if (peer != null) {
                avatarDrawable.setInfo(account, peer);
                target.setForUserOrChat(peer, avatarDrawable);
            } else {
                avatarDrawable.setInfo(did, "", "");
                target.setImageDrawable(avatarDrawable);
            }
        }

        private boolean unread() {
            final TLRPC.Dialog dialog = MessagesController.getInstance(account).dialogs_dict.get(did);
            return dialog != null && (dialog.unread_count > 0 || dialog.unread_mark);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            // the ring and the dot follow the avatar's shape (its corner radius)
            final float radius = Math.min(avatarRadius(getWidth()), getWidth() / 2f);
            if (current == did) {
                ringPaint.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                final float inset = dp(1);
                AndroidUtilities.rectTmp.set(inset, inset, getWidth() - inset, getHeight() - inset);
                final float r = Math.max(0, radius - inset);
                canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, ringPaint);
            }
            if (unread()) {
                final float r = dp(5.5f);
                // on the avatar's top right corner, where its edge turns
                final float corner = radius * (1f - 0.7071f);
                final float x = getWidth() - corner, y = corner;
                dotPaint.setColor(Color.WHITE);
                canvas.drawCircle(x, y, r + dp(1.5f), dotPaint);
                dotPaint.setColor(Theme.getColor(Theme.key_chats_unreadCounter));
                canvas.drawCircle(x, y, r, dotPaint);
            }
        }
    }

    /** Clears the dock's chats of an account that logged out. */
    public static void clear(int account) {
        MessagesController.getMainSettings(account).edit().remove(key(account)).apply();
        if (instance != null && instance.account == account) {
            instance.dialogs.clear();
            instance.rebuild();
        }
    }

    /** True when touches at (x, y) of the layout fall on the dock's pill. */
    public static boolean isOnDock(float x, float y) {
        final SolegramChatDock dock = instance;
        if (dock == null || dock.pill.getVisibility() != VISIBLE || dock.pill.getAlpha() <= 0f) {
            return false;
        }
        return x >= dock.pill.getLeft() && x <= dock.pill.getRight() && y >= dock.pill.getTop() && y <= dock.pill.getBottom();
    }
}
