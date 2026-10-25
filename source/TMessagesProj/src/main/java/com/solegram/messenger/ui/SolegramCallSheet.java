/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramGlass;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.utils.RenderNodeEffects;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.voip.VoIpGradientLayout;

/**
 * The call screen's glass sheet ("Шторка"): a panel of the app's glass at the bottom of the screen
 * holding the call controls. Telegram's own row of buttons (speaker, video, microphone) sits on it,
 * under it a wide "End call"; for an incoming call wide "Decline" / "Accept" and quick replies.
 * The glass is the same as the tab bar's: what lies under the sheet (the call background, the video)
 * is blurred and, on Android 13+, refracted at the edges, with the density, tint, highlight and shadow
 * of the glass settings.
 */
@SuppressLint("ViewConstructor")
public class SolegramCallSheet extends FrameLayout {

    public static final int MODE_HIDDEN = 0;
    public static final int MODE_ACTIVE = 1;
    public static final int MODE_INCOMING = 2;

    /** Room around the glass panel for its shadow; the panel itself is inset by this much from the screen edges. */
    private static final int EDGE = 10;
    private static final int RADIUS = 34;
    private static final int GRABBER_ZONE = 28;
    private static final int SIDE = 18;
    private static final int BOTTOM = 18;
    private static final int END_HEIGHT = 56;
    private static final int GAP_TO_END = 14;
    private static final int WIDE_HEIGHT = 58;
    private static final int LABEL_TOP = 12;
    private static final int LABEL_HEIGHT = 20;
    private static final int REPLY_HEIGHT = 44;
    private static final int REPLY_GAP = 8;
    private static final int GAP_TO_INCOMING = 8;
    private static final int REPLIES_HEIGHT = LABEL_TOP + LABEL_HEIGHT + 3 * (REPLY_GAP + REPLY_HEIGHT);

    public interface Delegate {
        void onEndCall();

        void onAccept();

        void onDecline();

        void onQuickReply(String text);

        /** The sheet changed its size: Telegram's buttons row has to be moved onto it. */
        void onSheetLayoutChanged();

        /** Before every frame of the call screen, also after the window was re-created (picture-in-picture). */
        void onBeforeFrame();
    }

    public static boolean enabled() {
        return SolegramConfig.glassCallScreen;
    }

    private final Delegate delegate;
    private final View[] backgroundViews;
    private final View grabber;
    private final TextView endButton;
    private final LinearLayout incomingPanel;
    private final TextView declineButton;
    private final TextView acceptButton;
    private final TextView replyLabel;
    private final TextView[] replyButtons = new TextView[3];
    private final BlurredBackgroundDrawable glass;
    private final BlurredBackgroundSourceRenderNode liveSource;
    private final Matrix tmpMatrix = new Matrix();
    private final float[] tmpValues = new float[9];

    private int mode = MODE_HIDDEN;
    private boolean retryMode;
    private int rowHeight;
    /** Decline or a quick reply was tapped: the incoming buttons do nothing more. */
    private boolean incomingHandled;
    /** Accept was tapped: quick replies would decline a call that is being answered. */
    private boolean acceptPressed;
    /** Alpha of the call controls the sheet goes with (they fade out in video calls and for picture-in-picture). */
    private float hostAlpha = 1f;
    /** 0..1: the sheet's own appearance, 0 while hidden. */
    private float shown;
    private ValueAnimator shownAnimator;
    /** Height change to new content: 0..1 from the old height and row position to the new ones. */
    private float morph = 1f;
    private int morphFromHeight;
    private int morphFromRowOffset;
    private ValueAnimator morphAnimator;

    private final ViewTreeObserver.OnPreDrawListener frameListener = () -> {
        onBeforeFrame();
        return true;
    };

    /**
     * @param root            the call screen's root: the glass looks at everything drawn in it
     * @param backgroundViews what lies under the sheet (background, cover, video), drawn into the glass in this order
     */
    public SolegramCallSheet(Context context, ViewGroup root, View[] backgroundViews, Delegate delegate) {
        super(context);
        this.delegate = delegate;
        this.backgroundViews = backgroundViews;
        setPadding(dp(EDGE), dp(EDGE), dp(EDGE), dp(EDGE));
        // children are clipped while the sheet grows or shrinks to new content
        setClipToPadding(false);
        setClipChildren(true);
        setWillNotDraw(false);
        // taps on the glass between the buttons do not reach the call screen (which hides the controls on tap)
        setClickable(true);

        // the same glass as the tab bar: a live picture of the call screen under the sheet
        BlurredBackgroundDrawableViewFactory factory;
        if (Build.VERSION.SDK_INT >= 31) {
            liveSource = new BlurredBackgroundSourceRenderNode(null);
            liveSource.setupRenderer(new RenderNodeWithHash.Renderer() {
                @Override
                public void renderNodeUpdateDisplayList(Canvas canvas) {
                    drawBackgroundViews(canvas);
                }

                @Override
                public void renderNodeCalculateHash(IBlur3Hash hash) {
                    hashBackgroundViews(hash);
                }
            });
            // the same blur as the app's glass sources: light for liquid glass (the lens refracts it),
            // heavy and saturated for frosted glass; both follow the glass blur and vibrancy settings
            final boolean liquid = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS);
            final float radius = AndroidUtilities.dpf2(liquid ? 9f : 40f) * SolegramGlass.blurScale();
            if (!liquid || SolegramGlass.vibrancy()) {
                liveSource.setBlur(radius, RenderNodeEffects.getSaturationX3RenderEffect());
            } else {
                liveSource.setBlur(radius);
            }
            factory = new BlurredBackgroundDrawableViewFactory(liveSource);
        } else {
            liveSource = null;
            final BlurredBackgroundSourceColor color = new BlurredBackgroundSourceColor();
            color.setColor(0xFF1E2433);
            factory = new BlurredBackgroundDrawableViewFactory(color);
        }
        factory.setSourceRootView(new ViewPositionWatcher(root), root);
        factory.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
        glass = factory.create(this, BlurredBackgroundProviderImpl.solegramCallSheet(null));
        glass.setRadius(dp(RADIUS));
        glass.setPadding(dp(EDGE));
        setBackground(glass);

        grabber = new View(context);
        grabber.setBackground(Theme.createRoundRectDrawable(dp(3), 0x73FFFFFF));
        addView(grabber, LayoutHelper.createFrame(40, 5, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 10, 0, 0));

        endButton = wideButton(context, LocaleController.getString(R.string.VoipEndCall2), 0xE6EB4040, R.drawable.calls_decline);
        endButton.setOnClickListener(v -> delegate.onEndCall());
        addView(endButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, END_HEIGHT, Gravity.BOTTOM, SIDE, 0, SIDE, BOTTOM));

        incomingPanel = new LinearLayout(context);
        incomingPanel.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout answerRow = new LinearLayout(context);
        answerRow.setOrientation(LinearLayout.HORIZONTAL);
        declineButton = wideButton(context, LocaleController.getString(R.string.DeclineCall), 0xE6EB4040, 0);
        declineButton.setOnClickListener(v -> {
            if (!incomingHandled) {
                incomingHandled = true;
                updateRepliesEnabled();
                delegate.onDecline();
            }
        });
        acceptButton = wideButton(context, LocaleController.getString(R.string.AcceptCall), 0xE634C759, 0);
        acceptButton.setOnClickListener(v -> {
            if (!incomingHandled) {
                // Accept may be tapped again after the microphone permission prompt, so only the replies lock
                acceptPressed = !retryMode;
                updateRepliesEnabled();
                delegate.onAccept();
            }
        });
        answerRow.addView(declineButton, LayoutHelper.createLinear(0, WIDE_HEIGHT, 1f, 0, 0, 5, 0));
        answerRow.addView(acceptButton, LayoutHelper.createLinear(0, WIDE_HEIGHT, 1f, 5, 0, 0, 0));
        incomingPanel.addView(answerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, WIDE_HEIGHT));

        replyLabel = new TextView(context);
        replyLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        replyLabel.setTextColor(0xBFFFFFFF);
        replyLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        replyLabel.setSingleLine(true);
        replyLabel.setText(LocaleController.getString(R.string.SG_CallReplyWithMessage));
        incomingPanel.addView(replyLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LABEL_HEIGHT, 4, LABEL_TOP, 4, 0));

        for (int i = 0; i < replyButtons.length; i++) {
            final TextView reply = new TextView(context);
            reply.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            reply.setTextColor(0xFFFFFFFF);
            reply.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            reply.setSingleLine(true);
            reply.setEllipsize(TextUtils.TruncateAt.END);
            reply.setPadding(dp(14), 0, dp(14), 0);
            reply.setBackground(Theme.createRoundRectDrawable(dp(16), 0x1AFFFFFF));
            reply.setOnClickListener(v -> {
                if (!incomingHandled && !acceptPressed && !retryMode) {
                    incomingHandled = true;
                    updateRepliesEnabled();
                    delegate.onQuickReply(((TextView) v).getText().toString());
                }
            });
            ScaleStateListAnimator.apply(reply, .03f, 1.2f);
            replyButtons[i] = reply;
            incomingPanel.addView(reply, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, REPLY_HEIGHT, 0, REPLY_GAP, 0, 0));
        }
        addView(incomingPanel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM, SIDE, 0, SIDE, BOTTOM));

        applyMode(false);
    }

    private static TextView wideButton(Context context, String text, int color, int iconRes) {
        final TextView button = new TextView(context);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(0xFFFFFFFF);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setText(text);
        button.setBackground(Theme.createRoundRectDrawable(dp(29), color));
        if (iconRes != 0) {
            final Drawable icon = context.getResources().getDrawable(iconRes).mutate();
            icon.setBounds(0, 0, dp(26), dp(26));
            button.setCompoundDrawables(icon, null, null, null);
            button.setCompoundDrawablePadding(dp(8));
        }
        button.setPadding(dp(16), 0, dp(16), 0);
        ScaleStateListAnimator.apply(button, .03f, 1.2f);
        return button;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        // the window is re-created around picture-in-picture: the listener goes with the current one
        getViewTreeObserver().addOnPreDrawListener(frameListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(frameListener);
        super.onDetachedFromWindow();
    }

    private void onBeforeFrame() {
        delegate.onBeforeFrame();
    }

    private static boolean drawable(View view) {
        return view != null && view.getVisibility() == View.VISIBLE && view.getAlpha() > 0f && view.getWidth() > 0;
    }

    /** The glass picture is recorded again only when something under the sheet changed. */
    private void hashBackgroundViews(IBlur3Hash hash) {
        for (View view : backgroundViews) {
            if (!drawable(view)) {
                hash.add(0);
                continue;
            }
            if (view.isDirty()) {
                hash.unsupported();
                return;
            }
            hash.add(view.getLeft());
            hash.add(view.getTop());
            hash.add(view.getWidth());
            hash.add(view.getHeight());
            hash.addF(view.getAlpha());
            view.getMatrix().getValues(tmpValues);
            hash.add(tmpValues);
        }
    }

    private void drawBackgroundViews(Canvas canvas) {
        for (View view : backgroundViews) {
            if (!drawable(view)) {
                continue;
            }
            final int count = canvas.save();
            canvas.translate(view.getLeft(), view.getTop());
            final Matrix matrix = view.getMatrix();
            if (!matrix.isIdentity()) {
                // translation, scale and rotation, as the parent draws it
                tmpMatrix.set(matrix);
                canvas.concat(tmpMatrix);
            }
            if (view.getAlpha() < 1f) {
                canvas.saveLayerAlpha(0, 0, view.getWidth(), view.getHeight(), (int) (255 * view.getAlpha()));
            }
            if (view instanceof VoIpGradientLayout) {
                // only the picture: the call screen's own drawing already updates the background's shared bitmaps
                final VoIpGradientLayout gradient = (VoIpGradientLayout) view;
                gradient.solegramMirrorDraw = true;
                try {
                    view.draw(canvas);
                } finally {
                    gradient.solegramMirrorDraw = false;
                }
            } else {
                view.draw(canvas);
            }
            canvas.restoreToCount(count);
        }
    }

    /** Quick replies: the ones set in Telegram's call settings, else Telegram's defaults. */
    public void setReplies(String[] replies) {
        for (int i = 0; i < replyButtons.length; i++) {
            final String text = i < replies.length ? replies[i] : null;
            replyButtons[i].setText(text);
            replyButtons[i].setVisibility(TextUtils.isEmpty(text) ? View.INVISIBLE : View.VISIBLE);
        }
    }

    public void setMode(int mode, boolean retry, boolean animated) {
        if (this.mode == mode && this.retryMode == retry) {
            return;
        }
        final int fromHeight = fullHeight();
        final int fromRowOffset = rowBottomOffset();
        final boolean wasShown = this.mode != MODE_HIDDEN;
        this.mode = mode;
        this.retryMode = retry;
        incomingHandled = false;
        acceptPressed = false;
        declineButton.setText(LocaleController.getString(retry ? R.string.Cancel : R.string.DeclineCall));
        acceptButton.setText(LocaleController.getString(retry ? R.string.RetryCall : R.string.AcceptCall));
        applyMode(animated);
        if (wasShown && mode != MODE_HIDDEN) {
            startMorph(fromHeight, fromRowOffset, animated);
        }
        requestLayout();
        delegate.onSheetLayoutChanged();
    }

    public int getMode() {
        return mode;
    }

    public boolean isRetry() {
        return retryMode;
    }

    public int getRowHeight() {
        return rowHeight;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // hidden together with the call controls (video calls hide them on tap): taps go to the call screen
        if (!isEnabled() || getAlpha() <= 0.01f) {
            return false;
        }
        return super.dispatchTouchEvent(ev);
    }

    private void applyMode(boolean animated) {
        final boolean active = mode == MODE_ACTIVE;
        final boolean incoming = mode == MODE_INCOMING;
        if (mode != MODE_HIDDEN) {
            show(endButton, active, animated);
            show(incomingPanel, incoming, animated);
        }
        // quick replies make no sense when the call is the user's own and failed (retry)
        replyLabel.setVisibility(retryMode ? View.GONE : View.VISIBLE);
        for (TextView reply : replyButtons) {
            if (retryMode) {
                reply.setVisibility(View.GONE);
            } else if (reply.getVisibility() == View.GONE) {
                reply.setVisibility(TextUtils.isEmpty(reply.getText()) ? View.INVISIBLE : View.VISIBLE);
            }
        }
        updateRepliesEnabled();
        animateShown(mode != MODE_HIDDEN, animated);
    }

    private void updateRepliesEnabled() {
        final boolean enabled = mode == MODE_INCOMING && !retryMode && !incomingHandled && !acceptPressed;
        for (View reply : replyButtons) {
            reply.setEnabled(enabled);
            reply.setAlpha(enabled ? 1f : 0.5f);
        }
        declineButton.setEnabled(!incomingHandled);
        acceptButton.setEnabled(!incomingHandled);
    }

    private static void show(View view, boolean show, boolean animated) {
        // new content springs in (fade, slight scale and rise); the old one goes at once, the sheet's height
        // change (startMorph) is what shows it going
        SolegramMotion.setVisible(view, show, show && animated);
    }

    private void animateShown(boolean show, boolean animated) {
        if (shownAnimator != null) {
            shownAnimator.cancel();
            shownAnimator = null;
        }
        final float target = show ? 1f : 0f;
        if (show) {
            setVisibility(View.VISIBLE);
        }
        if (!animated || getVisibility() != View.VISIBLE || shown == target) {
            shown = target;
            applyAlpha();
            if (!show) {
                setVisibility(View.GONE);
            }
            return;
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(shown, target);
        animator.addUpdateListener(a -> {
            shown = (float) a.getAnimatedValue();
            applyAlpha();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (shownAnimator == animation) {
                    shownAnimator = null;
                    if (!show) {
                        setVisibility(View.GONE);
                    }
                }
            }
        });
        animator.setDuration(show ? SolegramMotion.NORMAL : SolegramMotion.FAST + 20);
        animator.setInterpolator(show ? SolegramMotion.EASE_OUT : SolegramMotion.EASE_IN);
        shownAnimator = animator;
        animator.start();
    }

    /** The sheet grows or shrinks to new content smoothly; the row of buttons on it moves along. */
    private void startMorph(int fromHeight, int fromRowOffset, boolean animated) {
        if (morphAnimator != null) {
            morphAnimator.cancel();
            morphAnimator = null;
        }
        if (!animated || fromHeight <= 0 || getVisibility() != View.VISIBLE || !isLaidOut()) {
            morph = 1f;
            return;
        }
        morphFromHeight = fromHeight;
        morphFromRowOffset = fromRowOffset;
        morph = 0f;
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.addUpdateListener(a -> {
            morph = (float) a.getAnimatedValue();
            requestLayout();
            delegate.onSheetLayoutChanged();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (morphAnimator == animation) {
                    morphAnimator = null;
                    morph = 1f;
                    requestLayout();
                    delegate.onSheetLayoutChanged();
                }
            }
        });
        animator.setDuration(300); // it relayouts the call screen each frame: kept short
        animator.setInterpolator(SolegramMotion.EMPHASIZED);
        morphAnimator = animator;
        animator.start();
    }

    /** The sheet fades together with Telegram's call controls. */
    public void setHostAlpha(float alpha) {
        if (hostAlpha != alpha) {
            hostAlpha = alpha;
            applyAlpha();
        }
    }

    private void applyAlpha() {
        setAlpha(hostAlpha * shown);
    }

    /** Height of Telegram's buttons row placed on the sheet (0 when the row is empty). */
    public void setRowHeight(int height, boolean animated) {
        if (rowHeight != height) {
            final int fromHeight = fullHeight();
            final int fromRowOffset = rowBottomOffset();
            rowHeight = height;
            startMorph(fromHeight, fromRowOffset, animated);
            requestLayout();
            delegate.onSheetLayoutChanged();
        }
    }

    /** Distance from the side of this view to the content inside the glass panel. */
    public int contentInset() {
        return dp(EDGE + SIDE);
    }

    private int incomingHeight() {
        return dp(WIDE_HEIGHT + (retryMode ? 0 : REPLIES_HEIGHT));
    }

    private int targetRowBottomOffset() {
        if (mode == MODE_INCOMING) {
            return dp(EDGE + BOTTOM + GAP_TO_INCOMING) + incomingHeight();
        }
        return dp(EDGE + BOTTOM + END_HEIGHT + GAP_TO_END);
    }

    private int targetFullHeight() {
        if (mode == MODE_HIDDEN) {
            return 0;
        }
        int height = dp(EDGE + GRABBER_ZONE) + rowHeight;
        if (mode == MODE_INCOMING) {
            height += (rowHeight > 0 ? dp(GAP_TO_INCOMING) : 0) + incomingHeight() + dp(BOTTOM + EDGE);
        } else {
            height += dp(GAP_TO_END + END_HEIGHT + BOTTOM + EDGE);
        }
        return height;
    }

    /** Distance from the bottom of this view to the bottom of Telegram's buttons row on it. */
    public int rowBottomOffset() {
        final int target = targetRowBottomOffset();
        return morph >= 1f ? target : Math.round(AndroidUtilities.lerp(morphFromRowOffset, target, morph));
    }

    /** Height of the sheet with its shadow room, 0 when hidden. */
    public int fullHeight() {
        final int target = targetFullHeight();
        return morph >= 1f || target == 0 ? target : Math.round(AndroidUtilities.lerp(morphFromHeight, target, morph));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Math.max(fullHeight(), dp(EDGE * 2)), MeasureSpec.EXACTLY));
    }

    /** Called before every frame of the call screen: the glass shows what is under it right now. */
    public void syncGlass(int rootWidth, int rootHeight) {
        if (getVisibility() != View.VISIBLE || getAlpha() <= 0f) {
            return;
        }
        // the glass draws the source by reference: a new recording shows up in this very frame
        if (liveSource != null && rootWidth > 0 && rootHeight > 0) {
            liveSource.setSize(rootWidth, rootHeight);
            liveSource.updateDisplayListIfNeeded();
        }
    }

    public void updateColors() {
        glass.updateColors();
        invalidate();
    }
}
