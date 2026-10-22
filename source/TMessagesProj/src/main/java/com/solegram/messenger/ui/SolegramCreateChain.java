/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.AccountFrozenAlert;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionIntroActivity;
import org.telegram.ui.ChannelCreateActivity;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.ContactsActivity;
import org.telegram.ui.GroupCreateActivity;

/**
 * The main screen's new-chat «+» as liquid glass. With liquid motion on, the tab bar's capsule, the «+» beside
 * it, the stories drop above the «+» and the create chain are one piece of glass drawn here (the tab bar keeps
 * only its tabs): the «+» flows out of the capsule's end while the capsule makes room for it, and flows back
 * into it wherever Telegram hid its floating button (scrolling down, search, other tabs). A tap on the «+» lets
 * the create items flow out of it upwards, each out of the one before; they flow back in the reverse order.
 * <p>
 * Two layers in the tabs screen: {@link #glassLayer} under the tab bar draws the glass (and the dim behind the
 * chain), {@link #contentLayer} over it holds what rides on the drops and takes their touches. Drops are in the
 * host's coordinates as laid out; the tab bar's own show/hide translation is applied when drawing.
 */
public class SolegramCreateChain {

    public static final int ACTION_MESSAGE = 0;
    public static final int ACTION_GROUP = 1;
    public static final int ACTION_CHANNEL = 2;
    public static final int ACTION_SECRET = 3;

    /** Between the capsule and the «+»: over k * 0.75, so the two never melt while at rest. */
    public static final int GAP_DP = 16;
    /** How close the capsule and the «+» come to the screen edges while the «+» is out. */
    public static final int SIDE_DP = 10;
    private static final int STORIES_DP = 40;
    private static final int ITEM_HEIGHT_DP = 48;
    private static final int ITEM_GAP_DP = 16;

    private static final int[] ITEM_ACTIONS = {ACTION_MESSAGE, ACTION_GROUP, ACTION_CHANNEL, ACTION_SECRET};
    private static final int[] ITEM_ICONS = {R.drawable.msg_message, R.drawable.msg_groups, R.drawable.msg_channel, R.drawable.msg_secret};
    private static final int[] ITEM_TITLES = {R.string.NewMessageTitle, R.string.NewGroup, R.string.NewChannel, R.string.NewSecretChat};

    public interface Delegate {
        /** The tab capsule: its glass is drawn here while this is active. */
        View getTabsView();

        /** The tab bar's container (its translation hides and shows the bar). */
        View getTabsWrapper();

        /** Room between the tab view's bounds and its glass. */
        int getGlassPadding();

        /** The tabs ride on the capsule while it narrows and shifts for the «+». */
        void onTabsShift(float dx);

        /** The tabs make room for the «+» beside them, or take the whole width back. */
        void onPlusRoom(boolean out);

        void onAction(int action);

        void onStories();

        /** Where live blur is needed for drops above the tab bar (host coordinates); null when nothing is there. */
        void onGlassRegion(@Nullable RectF region);
    }

    private final Delegate delegate;
    private final Theme.ResourcesProvider resourcesProvider;

    /** Under the tab bar: the dim behind the chain and the glass of all the drops. */
    public final View glassLayer;
    /** Over the tab bar: the «+» glyph, the stories icon and the items, riding on their drops. */
    public final ContentLayer contentLayer;

    private final SolegramLiquid.Group group = new SolegramLiquid.Group();
    private final SolegramLiquid.Blob capsule, plus, stories;
    private final SolegramLiquid.Blob[] items = new SolegramLiquid.Blob[ITEM_ACTIONS.length];
    /** The «+» and the items: what flows back into the capsule when the «+» hides with the chain out. */
    private final SolegramLiquid.Blob[] plusAndItems = new SolegramLiquid.Blob[ITEM_ACTIONS.length + 1];

    private final PlusView plusView;
    private final IconView storiesView;
    private final ItemView[] itemViews = new ItemView[ITEM_ACTIONS.length];

    /** The «+» turning into «×» (0..1) and the dim behind the chain. */
    private final SolegramLiquid.Spring turn = new SolegramLiquid.Spring(0), dim = new SolegramLiquid.Spring(0);
    private long lastFrame;

    private boolean active;
    private boolean plusWanted, storiesWanted, roomWanted;
    private float lift;
    private boolean open;
    private boolean laidOut;
    private boolean roomOut;
    private boolean capsuleScaled;
    private int lastWidth, lastHeight;
    private float lastAvailL, lastAvailR;
    private boolean lastRoomOut;
    private boolean snapNext;
    /** The capsule's resting geometry (unscaled) and where the tabs are laid out. */
    private float capX, capY, capW, capH, tabsLeft;
    private float plusD;

    // glass
    @Nullable
    private final BlurredBackgroundDrawable liveDrawable;
    private final BlurredBackgroundSourceColor colorSource;
    private final Paint colorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SolegramLiquid.GlassFill fill = this::fillGlass;
    private float fillOffsetY;
    private int dimColor;

    private final RectF region = new RectF(), lastRegion = new RectF();
    private boolean regionPosted;
    private final Runnable regionRunnable = () -> {
        regionPosted = false;
        pushRegion();
    };

    /**
     * @param liveSource the tab bar's live glass source (Android 12+), or null: then the glass is the plain
     *                   colour of {@code colorSource}, as the tab bar has it
     */
    public SolegramCreateChain(Context context, Delegate delegate, Theme.ResourcesProvider resourcesProvider,
                               @Nullable BlurredBackgroundSourceRenderNode liveSource, BlurredBackgroundSourceColor colorSource) {
        this.delegate = delegate;
        this.resourcesProvider = resourcesProvider;
        this.colorSource = colorSource;

        group.k = dp(20);
        group.springs(0.46f, 0.8f);
        capsule = group.add();
        plus = group.add();
        stories = group.add();
        plusAndItems[0] = plus;
        for (int i = 0; i < items.length; i++) {
            items[i] = group.add();
            springs(items[i], 0.4f, 0.8f);
            plusAndItems[i + 1] = items[i];
        }

        if (liveSource != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // only the blur: tint, rim and shadow come from the liquid group (no refraction: it follows a rect)
            liveDrawable = liveSource.createDrawable();
            liveDrawable.setColorProvider(new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> 0)
                .setStrokeColorTop(0, 0)
                .setStrokeColorBottom(0, 0)
                .setShadowColor(0, 0)
                .setShadowLayer(0, 0, 0)
                .setStrokeWidth(0, 0)
                .build());
        } else {
            liveDrawable = null;
        }

        glassLayer = new View(context) {
            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                drawGlass(canvas);
            }
        };
        glassLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        glassLayer.setVisibility(View.GONE);

        contentLayer = new ContentLayer(context);
        contentLayer.setVisibility(View.GONE);

        plusView = new PlusView(context);
        plusView.setContentDescription(LocaleController.getString(R.string.NewMessageTitle));
        plusView.setOnClickListener(v -> {
            if (open) {
                close();
            } else {
                openChain();
            }
        });
        bind(plus, plusView);

        storiesView = new IconView(context, R.drawable.outline_fab_story_24);
        storiesView.setContentDescription(LocaleController.getString(R.string.StoryPrivacyButtonPost));
        storiesView.setOnClickListener(v -> {
            if (!open && stories.isOut()) {
                delegate.onStories();
            }
        });
        bind(stories, storiesView);

        for (int i = 0; i < itemViews.length; i++) {
            final int index = i;
            itemViews[i] = new ItemView(context, i);
            itemViews[i].setOnClickListener(v -> onItemClick(index));
            bind(items[i], itemViews[i]);
        }

        updateColors();
    }

    private void bind(SolegramLiquid.Blob blob, View view) {
        blob.view = view;
        view.setAlpha(0);
        view.setVisibility(View.INVISIBLE);
        contentLayer.addView(view);
    }

    /** As {@link SolegramLiquid.Group#springs}, for one drop: size a little slower than position. */
    private static void springs(SolegramLiquid.Blob b, float response, float damping) {
        b.x.params(response, damping);
        b.y.params(response, damping);
        b.w.params(response * 1.16f, damping);
        b.h.params(response * 1.16f, damping);
        b.r.params(response * 1.16f, damping);
    }

    // region host

    /** Liquid glass on: the tab bar's own glass is off and drawn here. Off: everything here is hidden at once. */
    public void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        glassLayer.setVisibility(active ? View.VISIBLE : View.GONE);
        contentLayer.setVisibility(active ? View.VISIBLE : View.GONE);
        laidOut = false;
        snapNext = false;
        if (!active) {
            open = false;
            turn.set(0);
            dim.set(0);
            setRoom(false);
            delegate.onTabsShift(0);
            pushRegion();
        }
    }

    public boolean isActive() {
        return active;
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * Where Telegram would show its floating buttons; {@code lift} raises the stories drop over a bulletin.
     * {@code room}: the tabs keep their room for the «+» (chats tab, bar shown) even while it is in, so
     * scrolling up and down only melts the «+» in and out of the capsule's end, without laying the tabs out.
     */
    public void setPlusWanted(boolean plus, boolean stories, boolean room, float lift) {
        final boolean liftChanged = this.lift != lift;
        if (plusWanted == plus && storiesWanted == stories && roomWanted == room && !liftChanged) {
            return;
        }
        roomWanted = room;
        plusWanted = plus;
        storiesWanted = stories;
        this.lift = lift;
        if (liftChanged && laidOut) {
            layoutUpper();
        }
        update();
    }

    public void invalidate() {
        glassLayer.invalidate();
    }

    /** The tab bar was laid out: the capsule takes its place (springs there when it moves by itself). */
    public void onHostLayout() {
        if (!active) {
            return;
        }
        final View tabs = delegate.getTabsView(), wrap = delegate.getTabsWrapper();
        final int width = contentLayer.getWidth(), height = contentLayer.getHeight();
        if (tabs.getWidth() <= 0 || tabs.getHeight() <= 0 || width <= 0) {
            return;
        }
        final int pad = delegate.getGlassPadding();
        final float l = wrap.getLeft() + tabs.getLeft() + pad;
        final float t = wrap.getTop() + tabs.getTop() + pad;
        final float w = tabs.getWidth() - pad * 2;
        final float h = tabs.getHeight() - pad * 2;
        final float availL = wrap.getLeft() + wrap.getPaddingLeft(), availR = wrap.getRight() - wrap.getPaddingRight();
        if (laidOut && !snapNext && l == tabsLeft && w == capW && t + h / 2 == capY && width == lastWidth && height == lastHeight
                && availL == lastAvailL && availR == lastAvailR && roomOut == lastRoomOut) {
            // the screen was laid out for something else: nothing here moved
            return;
        }
        final boolean first = !laidOut;
        if (first) {
            // the screen appears with the «+» already where it belongs (the host measured the tabs for it)
            roomOut = active && (roomWanted || plusWanted);
            if (roomOut) {
                delegate.onPlusRoom(true);
            }
        }
        // a new size (rotation, insets): everything jumps to its place instead of sweeping over the screen;
        // so does the pass that lays the tabs out beside the «+» right after the first one
        final boolean snap = first || snapNext || width != lastWidth || height != lastHeight;
        snapNext = first && roomOut;
        lastWidth = width;
        lastHeight = height;
        lastAvailL = availL;
        lastAvailR = availR;
        lastRoomOut = roomOut;

        tabsLeft = l;
        capW = w;
        capH = h;
        capY = t + h / 2;
        plusD = h;
        // the capsule and the «+» side by side, centred together; the capsule is as wide as the tabs allow
        final float gap = dp(GAP_DP);
        final float cw = roomOut ? w : Math.max(0, Math.min(w, availR - availL - dp(SIDE_DP) * 2 - gap - plusD));
        final float groupLeft = availL + (availR - availL - (cw + gap + plusD)) / 2;
        final boolean rtl = LocaleController.isRTL;
        final float px = rtl ? groupLeft + plusD / 2 : groupLeft + cw + gap + plusD / 2;
        capX = roomOut ? (rtl ? groupLeft + plusD + gap + cw / 2 : groupLeft + cw / 2) : l + w / 2;

        if (!capsuleScaled || snap) {
            capsule.rest(capX, capY, capW, capH, capH / 2);
            if (snap) {
                capsule.show();
                capsuleScaled = false;
            }
        }
        plus.rest(px, capY, plusD, plusD, plusD / 2);
        layoutUpper();
        if (snap) {
            for (SolegramLiquid.Blob b : group.blobs) {
                if (b != capsule && b.isOut()) {
                    b.place(b.finX, b.finY, b.finW, b.finH, b.finR);
                }
            }
        }
        laidOut = true;
        if (first) {
            update();
            finishAnimations();
        }
        glassLayer.invalidate();
        scheduleRegion();
    }

    /** Before the first layout: whether the tabs should already be measured narrower for the «+». */
    public boolean wantsRoomAtStart() {
        return active && !laidOut && (roomWanted || plusWanted);
    }

    /** The stories drop and the items above the «+», lined up with its outer edge. */
    private void layoutUpper() {
        final boolean rtl = LocaleController.isRTL;
        final float px = plus.finX;
        final float plusTop = capY - plusD / 2;
        final float itemGap = dp(ITEM_GAP_DP);
        final float sd = dp(STORIES_DP);
        stories.rest(px, plusTop - itemGap - sd / 2 - lift, sd, sd, sd / 2);

        final float edge = rtl ? px - plusD / 2 : px + plusD / 2;
        final float room = rtl ? lastWidth - dp(SIDE_DP) - edge : edge - dp(SIDE_DP);
        final float ih = dp(ITEM_HEIGHT_DP);
        float bottom = plusTop - itemGap - lift;
        for (int i = 0; i < items.length; i++) {
            final ItemView v = itemViews[i];
            final float iw = Math.max(ih, Math.min(v.wantedWidth, room));
            v.setSize((int) Math.ceil(iw), (int) ih);
            items[i].rest(rtl ? edge + iw / 2 : edge - iw / 2, bottom - ih / 2, iw, ih, ih / 2);
            bottom -= ih + itemGap;
        }
    }

    /** Leaves the screen: the chain flows back. */
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        turn.to(0);
        dim.to(0);
        group.chainIn(plus, this::onChainIn, items);
        plusView.setContentDescription(LocaleController.getString(R.string.NewMessageTitle));
        glassLayer.invalidate();
    }

    /** Hidden behind another screen: whatever still moves is finished at once. */
    public void finishAnimations() {
        if (!group.isAnimating() && !turn.isActive() && !dim.isActive()) {
            return;
        }
        long t = System.nanoTime();
        for (int i = 0; i < 90 && group.isAnimating(); i++) {
            t += 33_333_333L;
            group.step(t);
        }
        turn.set(turn.target);
        dim.set(dim.target);
        lastFrame = 0;
        if (laidOut) {
            applyFrame(delegate.getTabsWrapper());
        }
        glassLayer.invalidate();
    }

    /** The centre of the stories drop (host coordinates) while it is there. */
    public boolean getStoriesCenter(float[] xy) {
        if (!active || !laidOut || !(plusWanted && storiesWanted)) {
            return false;
        }
        xy[0] = stories.finX;
        xy[1] = stories.finY;
        return true;
    }

    public void updateColors() {
        SolegramLiquidGlass.applyColors(group, resourcesProvider);
        final boolean dark = Theme.isCurrentThemeDark();
        dimColor = dark ? 0x52000000 : 0x24000000;
        plusView.paint.setColor(Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider));
        storiesView.setColor(Theme.getColor(Theme.key_glass_tabUnselected, resourcesProvider));
        final int text = Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, resourcesProvider);
        final int icon = Theme.getColor(Theme.key_actionBarDefaultSubmenuItemIcon, resourcesProvider);
        final int selector = Theme.getColor(Theme.key_listSelector, resourcesProvider);
        for (ItemView v : itemViews) {
            v.setColors(text, icon, selector);
        }
        if (liveDrawable != null) {
            liveDrawable.updateColors();
        }
        plusView.invalidate();
        glassLayer.invalidate();
    }

    // endregion

    // region choreography

    private void update() {
        if (!laidOut) {
            return;
        }
        if (!(active && plusWanted)) {
            if (open) {
                // the chain flows back into the «+» (last item first), then the «+» into the capsule
                open = false;
                turn.to(0);
                dim.to(0);
                plusView.setContentDescription(LocaleController.getString(R.string.NewMessageTitle));
                group.chainIn(plus, null, stories);
                group.chainIn(capsule, this::onPlusIn, plusAndItems);
            } else if (plus.isOut()) {
                group.chainIn(capsule, this::onPlusIn, plus, stories);
            }
            setRoom(active && roomWanted);
        } else {
            setRoom(true);
            final boolean wantStories = storiesWanted && !open;
            if (!plus.isOut()) {
                if (wantStories) {
                    group.chainOut(capsule, 0.12f, plus, stories);
                } else {
                    group.chainOut(capsule, 0, plus);
                }
            } else if (wantStories && !stories.isOut()) {
                group.chainOut(plus, 0, stories);
            }
            if (!wantStories && stories.isOut()) {
                group.chainIn(plus, null, stories);
            }
        }
        glassLayer.invalidate();
        scheduleRegion();
    }

    private void setRoom(boolean out) {
        if (roomOut == out) {
            return;
        }
        roomOut = out;
        delegate.onPlusRoom(out);
        if (laidOut) {
            // the host lays the tabs out again before the next frame; until then the capsule aims with today's width
            onHostLayout();
        }
    }

    private void openChain() {
        if (open || !active || !laidOut || !plus.isOut()) {
            return;
        }
        open = true;
        for (ItemView v : itemViews) {
            v.prepare();
        }
        layoutUpper();
        contentLayer.requestLayout();
        // the stories drop makes way, the items flow out of the «+», each out of the one before
        group.chainIn(plus, null, stories);
        group.chainOut(plus, 0.045f, items);
        turn.to(1);
        dim.to(1);
        plusView.setContentDescription(LocaleController.getString(R.string.AccDescrCloseMenu));
        glassLayer.invalidate();
        scheduleRegion();
    }

    private void onItemClick(int index) {
        if (!open) {
            return;
        }
        // the screen opens as the chain starts flowing back
        close();
        delegate.onAction(ITEM_ACTIONS[index]);
    }

    private void onPlusPressed(boolean pressed) {
        if (!laidOut) {
            return;
        }
        // a soft squeeze under the finger
        final float d = plusD * (pressed ? 0.92f : 1f);
        plus.rest(plus.finX, plus.finY, d, d, d / 2);
        glassLayer.invalidate();
    }

    // runs inside the group's step (while drawing): the rest waits for the next loop
    private void onChainIn() {
        AndroidUtilities.runOnUIThread(() -> {
            update();
            scheduleRegion();
        });
    }

    private void onPlusIn() {
        scheduleRegion();
    }

    // endregion

    // region drawing

    private void drawGlass(Canvas canvas) {
        if (!active || !laidOut) {
            return;
        }
        final View tabs = delegate.getTabsView(), wrap = delegate.getTabsWrapper();
        followTabsScale(tabs);
        final long now = System.nanoTime();
        boolean moving = group.step(now);
        final float dt = lastFrame == 0 ? 1f / 60f : Math.max(0, Math.min(1f / 30f, (now - lastFrame) / 1e9f));
        lastFrame = now;
        if (turn.step(dt)) {
            moving = true;
        }
        if (dim.step(dt)) {
            moving = true;
        }
        if (!moving) {
            lastFrame = 0;
        }
        applyFrame(wrap);

        final float alpha = tabs.getVisibility() == View.VISIBLE ? tabs.getAlpha() : 0;
        contentLayer.setAlpha(alpha);
        contentLayer.setVisibility(alpha > 0 ? View.VISIBLE : View.INVISIBLE);
        if (dim.value > 0.003f) {
            canvas.drawColor(Theme.multAlpha(dimColor, Math.min(1, dim.value)));
        }
        if (alpha > 0) {
            fillOffsetY = wrap.getTranslationY();
            canvas.save();
            canvas.translate(0, fillOffsetY);
            group.draw(canvas, fill, alpha);
            canvas.restore();
        }
        if (moving) {
            glassLayer.postInvalidateOnAnimation();
        }
    }

    /** What rides on the drops follows them; the tabs follow the capsule. */
    private void applyFrame(View wrap) {
        final float ty = wrap.getTranslationY();
        follow(plusView, plus, ty);
        follow(storiesView, stories, ty);
        for (int i = 0; i < items.length; i++) {
            follow(itemViews[i], items[i], ty);
        }
        plusView.setTurn(turn.value);
        if (plusView.getVisibility() == View.VISIBLE && plusD > 0) {
            // the glyph shows only on a drop that has left the capsule: never over the tabs
            final boolean rtl = LocaleController.isRTL;
            final float capEdge = rtl ? capsule.x.value - capsule.w.value / 2 : capsule.x.value + capsule.w.value / 2;
            final float plusEdge = rtl ? plus.x.value + plus.w.value / 2 : plus.x.value - plus.w.value / 2;
            final float gap = rtl ? capEdge - plusEdge : plusEdge - capEdge;
            final float apart = Math.max(0, Math.min(1, (gap + plusD * 0.3f) / (plusD * 0.3f)));
            if (apart < 1) {
                plusView.setAlpha(plusView.getAlpha() * apart);
            }
        }
        final float left = capsuleScaled ? capX - capW / 2 : capsule.x.value - capsule.w.value / 2;
        delegate.onTabsShift(left - tabsLeft);
        if (group.isAnimating()) {
            // the content is clipped to the outline while it changes
            contentLayer.invalidate();
        }
    }

    private static void follow(View v, SolegramLiquid.Blob b, float ty) {
        if (v.getVisibility() != View.VISIBLE) {
            return;
        }
        final int w = v.getLayoutParams() != null && v.getLayoutParams().width > 0 ? v.getLayoutParams().width : v.getMeasuredWidth();
        final int h = v.getLayoutParams() != null && v.getLayoutParams().height > 0 ? v.getLayoutParams().height : v.getMeasuredHeight();
        v.setTranslationX(b.x.value - w / 2f);
        v.setTranslationY(b.y.value - h / 2f + ty);
    }

    /** A long press grows the tab bar a little around the finger: its glass grows with it (only while at rest). */
    private void followTabsScale(View tabs) {
        final float sx = tabs.getScaleX(), sy = tabs.getScaleY();
        final boolean scaled = Math.abs(sx - 1f) > 0.0005f || Math.abs(sy - 1f) > 0.0005f;
        if (!scaled && !capsuleScaled) {
            return;
        }
        if (capsule.x.isActive() || capsule.y.isActive() || capsule.w.isActive() || capsule.h.isActive()) {
            return;
        }
        if (!scaled) {
            capsule.place(capX, capY, capW, capH, capH / 2);
            capsuleScaled = false;
            return;
        }
        final int pad = delegate.getGlassPadding();
        final float px = capX - capW / 2 - pad + tabs.getPivotX();
        final float py = capY - capH / 2 - pad + tabs.getPivotY();
        capsule.place(px + (capX - px) * sx, py + (capY - py) * sy, capW * sx, capH * sy, capH * Math.min(sx, sy) / 2);
        capsuleScaled = true;
    }

    private void fillGlass(Canvas canvas, Path path, RectF bounds) {
        if (liveDrawable != null && canvas.isHardwareAccelerated()) {
            canvas.save();
            canvas.clipPath(path);
            // the canvas is moved with the tab bar: the blur is taken from where the glass is now
            liveDrawable.setSourceOffset(glassLayer.getX(), glassLayer.getY() + fillOffsetY);
            liveDrawable.setBounds((int) Math.floor(bounds.left), (int) Math.floor(bounds.top), (int) Math.ceil(bounds.right), (int) Math.ceil(bounds.bottom));
            liveDrawable.draw(canvas);
            canvas.restore();
        } else {
            colorPaint.setColor(colorSource.getColor());
            canvas.drawPath(path, colorPaint);
        }
    }

    // endregion

    // region live blur region

    private void scheduleRegion() {
        if (!regionPosted) {
            regionPosted = true;
            AndroidUtilities.runOnUIThread(regionRunnable);
        }
    }

    /** The chat list keeps a live blur under the drops above the tab bar (the bar's own strip has one already). */
    private void pushRegion() {
        region.setEmpty();
        if (active && laidOut) {
            if (stories.isVisible() || plusWanted && storiesWanted && !open) {
                union(stories);
            }
            for (SolegramLiquid.Blob item : items) {
                if (open || item.isVisible()) {
                    union(item);
                }
            }
            if (!region.isEmpty()) {
                // down into the «+», for the necks
                union(plus);
                region.inset(-dp(12), -dp(12));
            }
        }
        if (!region.equals(lastRegion)) {
            lastRegion.set(region);
            delegate.onGlassRegion(region.isEmpty() ? null : new RectF(region));
        }
    }

    private void union(SolegramLiquid.Blob b) {
        final float l = b.finX - b.finW / 2, t = b.finY - b.finH / 2, r = b.finX + b.finW / 2, bt = b.finY + b.finH / 2;
        if (region.isEmpty()) {
            region.set(l, t, r, bt);
        } else {
            region.union(l, t, r, bt);
        }
    }

    // endregion

    // region create

    /** Opens what a create item stands for (except a new message, which the chat list opens itself). */
    public static void open(BaseFragment fragment, int action) {
        final int account = fragment.getCurrentAccount();
        if (MessagesController.getInstance(account).isFrozen()) {
            AccountFrozenAlert.show(account);
            return;
        }
        if (action == ACTION_GROUP) {
            fragment.presentFragment(new GroupCreateActivity(new Bundle()));
        } else if (action == ACTION_CHANNEL) {
            final SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            if (!BuildVars.DEBUG_VERSION && preferences.getBoolean("channel_intro", false)) {
                Bundle args = new Bundle();
                args.putInt("step", 0);
                fragment.presentFragment(new ChannelCreateActivity(args));
            } else {
                fragment.presentFragment(new ActionIntroActivity(ActionIntroActivity.ACTION_TYPE_CHANNEL_CREATE));
                preferences.edit().putBoolean("channel_intro", true).commit();
            }
        } else if (action == ACTION_SECRET) {
            Bundle args = new Bundle();
            args.putBoolean("onlyUsers", true);
            args.putBoolean("destroyAfterSelect", true);
            args.putBoolean("createSecretChat", true);
            args.putBoolean("allowBots", false);
            args.putBoolean("allowSelf", false);
            fragment.presentFragment(new ContactsActivity(args));
        }
    }

    // endregion

    // region views

    /** Holds what rides on the drops; while the chain is out it also takes the taps that miss it (to close it). */
    public final class ContentLayer extends FrameLayout {

        ContentLayer(Context context) {
            super(context);
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
            for (int i = 0; i < getChildCount(); i++) {
                final View child = getChildAt(i);
                final LayoutParams lp = (LayoutParams) child.getLayoutParams();
                child.measure(MeasureSpec.makeMeasureSpec(Math.max(0, lp.width), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, lp.height), MeasureSpec.EXACTLY));
            }
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            for (int i = 0; i < getChildCount(); i++) {
                final View child = getChildAt(i);
                child.layout(0, 0, child.getMeasuredWidth(), child.getMeasuredHeight());
            }
        }

        @Override
        public boolean hasOverlappingRendering() {
            // faded with the tab bar child by child, never through a screen-sized layer
            return false;
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            if (group.isAnimating() && !group.path.isEmpty()) {
                // nothing sticks out of a drop that is still small
                final float ty = delegate.getTabsWrapper().getTranslationY();
                canvas.save();
                canvas.translate(0, ty);
                canvas.clipPath(group.path);
                canvas.translate(0, -ty);
                super.dispatchDraw(canvas);
                canvas.restore();
            } else {
                super.dispatchDraw(canvas);
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // reached here: no drop took it
            if (open) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    close();
                }
                return true;
            }
            return false;
        }
    }

    /** The «+» glyph on its drop: turns into «×» while the chain is out. */
    private final class PlusView extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float turnValue;
        private boolean pressedNow;

        PlusView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dpf2(2.4f));
            final int size = dp(60);
            setLayoutParams(new FrameLayout.LayoutParams(size, size));
        }

        void setTurn(float value) {
            if (turnValue != value) {
                turnValue = value;
                invalidate();
            }
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final float cx = getWidth() / 2f, cy = getHeight() / 2f, arm = dpf2(10.5f);
            canvas.save();
            canvas.rotate(45 * turnValue, cx, cy);
            canvas.drawLine(cx - arm, cy, cx + arm, cy, paint);
            canvas.drawLine(cx, cy - arm, cx, cy + arm, paint);
            canvas.restore();
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            final boolean p = isPressed();
            if (pressedNow != p) {
                pressedNow = p;
                onPlusPressed(p);
            }
        }
    }

    /** A plain icon on its drop (the stories drop). */
    private static final class IconView extends View {
        private final Drawable icon;

        IconView(Context context, int iconRes) {
            super(context);
            icon = ContextCompat.getDrawable(context, iconRes).mutate();
            final int size = dp(STORIES_DP);
            setLayoutParams(new FrameLayout.LayoutParams(size, size));
        }

        void setColor(int color) {
            icon.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final int w = icon.getIntrinsicWidth(), h = icon.getIntrinsicHeight();
            final int l = (getWidth() - w) / 2, t = (getHeight() - h) / 2;
            icon.setBounds(l, t, l + w, t + h);
            icon.draw(canvas);
        }
    }

    /** A create item: an icon and a title on a glass capsule. */
    private final class ItemView extends View {
        private final Drawable icon;
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final int index;
        private String title = "";
        private CharSequence shown = "";
        float wantedWidth;
        private int selectorColor;

        ItemView(Context context, int index) {
            super(context);
            this.index = index;
            icon = ContextCompat.getDrawable(context, ITEM_ICONS[index]).mutate();
            textPaint.setTextSize(dp(15));
            setLayoutParams(new FrameLayout.LayoutParams(dp(ITEM_HEIGHT_DP), dp(ITEM_HEIGHT_DP)));
            prepare();
        }

        /** The title in today's language, and the width it wants. */
        void prepare() {
            title = LocaleController.getString(ITEM_TITLES[index]);
            setContentDescription(title);
            wantedWidth = dp(16) + dp(24) + dp(12) + textPaint.measureText(title) + dp(20);
            // measured at this size from the start: no second layout pass when the drops are placed
            final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            lp.width = (int) Math.ceil(Math.max(lp.height, wantedWidth));
        }

        void setSize(int w, int h) {
            final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            if (lp.width != w || lp.height != h || getMeasuredWidth() != w && getMeasuredWidth() != 0) {
                lp.width = w;
                lp.height = h;
                setLayoutParams(lp);
                applySelector();
            }
            final float textRoom = w - dp(16) - dp(24) - dp(12) - dp(20);
            shown = TextUtils.ellipsize(title, textPaint, Math.max(0, textRoom), TextUtils.TruncateAt.END);
            invalidate();
        }

        void setColors(int text, int iconColor, int selector) {
            textPaint.setColor(text);
            icon.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
            selectorColor = selector;
            applySelector();
            invalidate();
        }

        private void applySelector() {
            final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
            final int r = Math.max(1, lp.height / 2);
            setBackground(Theme.createRadSelectorDrawable(selectorColor, r, r));
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final boolean rtl = LocaleController.isRTL;
            final int iconSize = dp(24);
            final int iconLeft = rtl ? getWidth() - dp(16) - iconSize : dp(16);
            final int iconTop = (getHeight() - iconSize) / 2;
            icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
            icon.draw(canvas);
            final float textWidth = textPaint.measureText(shown, 0, shown.length());
            final float x = rtl ? iconLeft - dp(12) - textWidth : iconLeft + iconSize + dp(12);
            final Paint.FontMetrics fm = textPaint.getFontMetrics();
            final float baseline = getHeight() / 2f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(shown, 0, shown.length(), x, baseline, textPaint);
        }
    }

    // endregion
}
