/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solegram.messenger.FeedController;
import com.solegram.messenger.SolegramConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.ViewGroupPartRenderer;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.HeaderShadowView;
import org.telegram.ui.MainTabsActivity;

import java.util.ArrayList;

/**
 * The Feed tab: posts of all followed channels in one list, newest first. Shown in the bottom bar
 * in place of Contacts (which moved to a swipe on the chat list); can also be opened on its own.
 */
public class FeedActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    private static final int MENU_REFRESH = 1;
    private static final int MENU_SKIP_MUTED = 2;
    private static final int MENU_CONTACTS = 3;

    private final int ADDITIONAL_LIST_HEIGHT_DP = Build.VERSION.SDK_INT >= 31 ? 48 : 0;

    private boolean hasMainTabs;
    private SizeNotifierFrameLayout contentView;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private Adapter adapter;
    private HeaderShadowView headerShadowView;
    private LinearLayout emptyView;
    private RadialProgressView progressView;
    private TextView emptyText;
    private org.telegram.ui.ActionBar.ActionBarMenuSubItem skipMutedItem;
    private ActionBarMenuItem refreshItem;

    private int navigationBarHeight;
    private int additionNavigationBarHeight;

    private final ArrayList<FeedController.Post> posts = new ArrayList<>();
    /** Posts hidden by the message filters that the user opened anyway (post keys). */
    private final java.util.HashSet<String> revealedPosts = new java.util.HashSet<>();

    public FeedActivity(Bundle args) {
        super(args);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            scrollableViewNoiseSuppressor = new DownscaleScrollableNoiseSuppressor();
            iBlur3SourceGlassFrosted = new BlurredBackgroundSourceRenderNode(null);
            iBlur3SourceGlass = new BlurredBackgroundSourceRenderNode(null);
        } else {
            scrollableViewNoiseSuppressor = null;
            iBlur3SourceGlassFrosted = null;
            iBlur3SourceGlass = null;
        }
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        if (arguments != null) {
            hasMainTabs = arguments.getBoolean("hasMainTabs", false);
        }
        additionNavigationBarHeight = hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0;
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.solegramFeedUpdated);
        FeedController.getInstance(currentAccount).loadIfStale();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.solegramFeedUpdated);
    }

    private boolean fullyShown, resumed;

    /** The Feed is really in front of the user (fully shown and the app is not in the background). */
    private boolean isSeen() {
        return fullyShown && resumed;
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        FeedController.getInstance(currentAccount).loadIfStale();
        if (isSeen()) {
            FeedController.getInstance(currentAccount).markSeen();
        }
        updateState();
    }

    @Override
    public void onPause() {
        super.onPause();
        resumed = false;
    }

    // the tab page resumes as soon as a bit of it slides in; posts count as seen only once it is fully shown
    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        fullyShown = true;
        if (isSeen()) {
            FeedController.getInstance(currentAccount).markSeen();
        }
    }

    @Override
    public void onBecomeFullyHidden() {
        super.onBecomeFullyHidden();
        fullyShown = false;
    }

    @Override
    public ActionBar createActionBar(Context context) {
        final ActionBar actionBar = super.createActionBar(context);
        actionBar.setUseContainerForTitles();
        actionBar.getTitlesContainer().setTranslationX(dp(4));
        actionBar.setAddToContainer(false);
        return actionBar;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.SG_Feed));
        if (!hasMainTabs) {
            actionBar.setBackButtonDrawable(new BackDrawable(false));
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_REFRESH) {
                    FeedController.getInstance(currentAccount).reload();
                } else if (id == MENU_SKIP_MUTED) {
                    SolegramConfig.putBoolean("feedSkipMuted", !SolegramConfig.feedSkipMuted);
                    updateSkipMutedText();
                    FeedController.getInstance(currentAccount).reload();
                } else if (id == MENU_CONTACTS) {
                    openContacts(FeedActivity.this);
                }
            }
        });
        final ActionBarMenu menu = actionBar.createMenu();
        refreshItem = menu.addItem(MENU_REFRESH, R.drawable.msg_retry);
        refreshItem.setContentDescription(getString(R.string.SG_FeedRefresh));
        final ActionBarMenuItem other = menu.addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(MENU_CONTACTS, R.drawable.msg_contacts, getString(R.string.Contacts));
        skipMutedItem = other.addSubItem(MENU_SKIP_MUTED, R.drawable.msg_mute, "");
        updateSkipMutedText();

        fragmentView = contentView = new SizeNotifierFrameLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                if (Build.VERSION.SDK_INT >= 31 && scrollableViewNoiseSuppressor != null) {
                    blur3_InvalidateBlur();
                    final int width = getMeasuredWidth();
                    final int height = getMeasuredHeight();
                    if (iBlur3SourceGlassFrosted != null && !iBlur3SourceGlassFrosted.inRecording()) {
                        final Canvas c = iBlur3SourceGlassFrosted.beginRecording(width, height);
                        c.drawColor(getThemedColor(Theme.key_windowBackgroundGray));
                        if (SharedConfig.chatBlurEnabled()) {
                            scrollableViewNoiseSuppressor.draw(c, DownscaleScrollableNoiseSuppressor.DRAW_FROSTED_GLASS);
                        }
                        iBlur3SourceGlassFrosted.endRecording();
                    }
                    if (iBlur3SourceGlass != null && !iBlur3SourceGlass.inRecording()) {
                        final Canvas c = iBlur3SourceGlass.beginRecording(width, height);
                        c.drawColor(getThemedColor(Theme.key_windowBackgroundGray));
                        if (SharedConfig.chatBlurEnabled()) {
                            scrollableViewNoiseSuppressor.draw(c, DownscaleScrollableNoiseSuppressor.DRAW_GLASS);
                        }
                        iBlur3SourceGlass.endRecording();
                    }
                }
                super.dispatchDraw(canvas);
            }

            @Override
            protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
                if (child == actionBar && headerFade != null) {
                    // floating header: the feed fades out under the status bar and the title
                    headerFade.draw(canvas, actionBar, getMeasuredWidth(), getThemedColor(Theme.key_windowBackgroundGray), 1f);
                }
                return super.drawChild(canvas, child, drawingTime);
            }

            @Override
            public void drawBlurRect(Canvas canvas, float y, Rect rectTmp, Paint blurScrimPaint, boolean top) {
                drawFrostedHeader(canvas, y, rectTmp, blurScrimPaint, 1f);
            }

            @Override
            public void drawBlurRect(Canvas canvas, float y, Rect rectTmp, Paint blurScrimPaint, boolean top, float alpha) {
                // the header is solid while the feed sits at the top and turns frosted once posts slide under it
                drawFrostedHeader(canvas, y, rectTmp, blurScrimPaint, alpha);
            }

            private void drawFrostedHeader(Canvas canvas, float y, Rect rectTmp, Paint blurScrimPaint, float frosted) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !SharedConfig.chatBlurEnabled() || iBlur3SourceGlassFrosted == null) {
                    canvas.drawRect(rectTmp, blurScrimPaint);
                    return;
                }
                canvas.save();
                canvas.translate(0, -y);
                iBlur3SourceGlassFrosted.draw(canvas, rectTmp.left, rectTmp.top + y, rectTmp.right, rectTmp.bottom + y);
                canvas.restore();
                final int veil = com.solegram.messenger.SolegramGlass.headerScrimAlpha(ChatActivity.ACTION_BAR_BLUR_ALPHA);
                final int oldScrimAlpha = blurScrimPaint.getAlpha();
                blurScrimPaint.setAlpha(Math.round(255 + (veil - 255) * Math.max(0f, Math.min(1f, frosted))));
                canvas.drawRect(rectTmp, blurScrimPaint);
                blurScrimPaint.setAlpha(oldScrimAlpha);
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                measureChildWithMargins(actionBar, widthMeasureSpec, 0, heightMeasureSpec, 0);
                ((MarginLayoutParams) headerShadowView.getLayoutParams()).topMargin = actionBar.getMeasuredHeight();
                ((MarginLayoutParams) emptyView.getLayoutParams()).topMargin = actionBar.getMeasuredHeight();
                checkUi_listViewPadding();
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context, resourceProvider);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        listView.setClipToPadding(false);
        listView.setItemAnimator(null);
        listView.setAdapter(adapter = new Adapter(context));
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                final int first = layoutManager.findFirstVisibleItemPosition();
                final View firstView = layoutManager.findViewByPosition(first);
                final boolean shadowVisible = !(first == 0 && firstView != null && firstView.getTop() >= listView.getPaddingTop());
                if (headerFade == null) {
                    headerShadowView.setShadowVisible(shadowVisible, true);
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                    scrollableViewNoiseSuppressor.onScrolled(dx, dy);
                    blur3_InvalidateBlur();
                }
            }
        });
        listView.addEdgeEffectListener(() -> listView.postOnAnimation(this::blur3_InvalidateBlur));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT, 0, -ADDITIONAL_LIST_HEIGHT_DP, 0, -ADDITIONAL_LIST_HEIGHT_DP));
        final IBlur3Capture listCapture = new ViewGroupPartRenderer(listView, contentView, listView::drawChild);
        headerFade = SolegramFloatingHeader.enabled() ? new SolegramFloatingHeader() : null;
        // the glass bubbles see the list as it is, so they are as clear as the rest of the app's glass
        iBlur3Capture = listCapture;

        emptyView = new LinearLayout(context);
        emptyView.setOrientation(LinearLayout.VERTICAL);
        emptyView.setGravity(Gravity.CENTER);
        progressView = new RadialProgressView(context, resourceProvider);
        progressView.setSize(dp(32));
        emptyView.addView(progressView, LayoutHelper.createLinear(48, 48, Gravity.CENTER_HORIZONTAL));
        emptyText = new TextView(context);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.addView(emptyText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 12, 32, 0));
        contentView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP));

        contentView.addView(actionBar);
        headerShadowView = new HeaderShadowView(context, parentLayout);
        headerShadowView.setShadowVisible(false, false);
        contentView.addView(headerShadowView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 5, Gravity.TOP));
        if (headerFade != null) {
            // floating header: no panel and no shadow; the buttons in a bubble of glass that sees the feed
            actionBar.setBackground(null);
            headerShadowView.setVisibility(View.GONE);
            SolegramFloatingHeader.setupButtons(actionBar,
                SolegramFloatingHeader.factory(iBlur3SourceGlass, getThemedColor(Theme.key_windowBackgroundGray), contentView), resourceProvider);
        } else {
            actionBar.setAdaptiveBackground(listView);
            actionBar.setDrawBlurBackground(contentView);
        }

        ViewCompat.setOnApplyWindowInsetsListener(fragmentView, this::onApplyWindowInsets);
        updateState();
        return fragmentView;
    }

    private void updateSkipMutedText() {
        if (skipMutedItem != null) {
            skipMutedItem.setText(getString(SolegramConfig.feedSkipMuted ? R.string.SG_FeedShowMuted : R.string.SG_FeedHideMuted));
        }
    }

    /** Opens the contact list as a regular screen (the tab it used to have now belongs to the Feed). */
    public static void openContacts(BaseFragment from) {
        final Bundle args = new Bundle();
        args.putBoolean("needPhonebook", true);
        args.putBoolean("needFinishFragment", false);
        from.presentFragment(new org.telegram.ui.ContactsActivity(args));
    }

    private void updateState() {
        final FeedController controller = FeedController.getInstance(currentAccount);
        posts.clear();
        // message filters: in "hide completely" mode filtered posts leave the Feed; otherwise they are folded (see the adapter)
        final boolean hideFiltered = com.solegram.messenger.SolegramMessageFilters.mode() == com.solegram.messenger.SolegramMessageFilters.MODE_HIDE;
        for (FeedController.Post post : controller.getPosts()) {
            if (!hideFiltered || filterRule(post) == null) {
                posts.add(post);
            }
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        if (emptyView == null) {
            return;
        }
        final boolean empty = posts.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            final boolean loading = controller.isLoading() || !controller.isEmptyAndLoaded();
            progressView.setVisibility(loading ? View.VISIBLE : View.GONE);
            emptyText.setText(getString(loading ? R.string.SG_FeedLoading : R.string.SG_FeedEmpty));
        }
        if (refreshItem != null) {
            refreshItem.setEnabled(!controller.isLoading());
            refreshItem.setAlpha(controller.isLoading() ? 0.5f : 1f);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.solegramFeedUpdated) {
            updateState();
            if (isSeen()) {
                FeedController.getInstance(currentAccount).markSeen();
            }
        }
    }

    private void openPost(FeedController.Post post, boolean atMessage) {
        final Bundle args = new Bundle();
        args.putLong("chat_id", post.channelId);
        if (atMessage) {
            args.putInt("message_id", post.message.getId());
        }
        if (getMessagesController().checkCanOpenChat(args, this)) {
            presentFragment(new ChatActivity(args));
        }
    }

    /** The filter hiding a post, or null when it shows (verdicts are cached per message, so this is cheap). */
    private com.solegram.messenger.SolegramFilterEngine.Rule filterRule(FeedController.Post post) {
        if (revealedPosts.contains(post.key)) {
            return null;
        }
        return com.solegram.messenger.SolegramMessageFilters.checkGroup(currentAccount, post.group, com.solegram.messenger.SolegramFilterEngine.WHERE_CHANNEL, true);
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == VIEW_FILTERED) {
                final SolegramFilteredCell filtered = new SolegramFilteredCell(context, SolegramFilteredCell.STYLE_FEED, resourceProvider);
                filtered.setOnClickListener(v -> {
                    final int position = listView.getChildAdapterPosition(v);
                    if (position >= 0 && position < posts.size()) {
                        revealedPosts.add(posts.get(position).key);
                        notifyItemChanged(position);
                    }
                });
                filtered.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(filtered);
            }
            final FeedPostCell cell = new FeedPostCell(context, new FeedPostCell.Delegate() {
                @Override
                public void onOpenPost(FeedController.Post post) {
                    openPost(post, true);
                }

                @Override
                public void onOpenChannel(FeedController.Post post) {
                    openPost(post, false);
                }
            });
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final FeedController.Post post = posts.get(position);
            if (holder.itemView instanceof SolegramFilteredCell) {
                final com.solegram.messenger.SolegramFilterEngine.Rule rule = filterRule(post);
                ((SolegramFilteredCell) holder.itemView).set(post.message, false, com.solegram.messenger.SolegramMessageFilters.titleOf(rule),
                    post.channel != null ? post.channel.title : null);
                return;
            }
            ((FeedPostCell) holder.itemView).setPost(post);
        }

        private static final int VIEW_FILTERED = 1;

        @Override
        public int getItemViewType(int position) {
            return filterRule(posts.get(position)) != null ? VIEW_FILTERED : 0;
        }

        @Override
        public int getItemCount() {
            return posts.size();
        }
    }

    /* Insets */

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @NonNull
    private WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
        final Insets systemInsets = AndroidUtilities.getDefaultWindowInsets(insets, false);
        navigationBarHeight = systemInsets.bottom;
        checkUi_listViewPadding();
        return WindowInsetsCompat.CONSUMED;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        navigationBarHeight = bottom;
        checkUi_listViewPadding();
    }

    private void checkUi_listViewPadding() {
        if (listView == null || actionBar == null) {
            return;
        }
        listView.setPadding(0, dp(ADDITIONAL_LIST_HEIGHT_DP) + actionBar.getMeasuredHeight(),
            0, dp(ADDITIONAL_LIST_HEIGHT_DP + 8) + navigationBarHeight + additionNavigationBarHeight);
        if (emptyView != null) {
            emptyView.setPadding(0, 0, 0, navigationBarHeight + additionNavigationBarHeight);
        }
    }

    /* Tabs */

    @Override
    public boolean canParentTabsSlide(android.view.MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public void onParentScrollToTop() {
        if (listView == null) {
            return;
        }
        if (layoutManager.findFirstVisibleItemPosition() < 10) {
            listView.smoothScrollToPosition(0);
        } else {
            layoutManager.scrollToPositionWithOffset(0, 0);
        }
    }

    /* Blur (same scheme as the other main tabs) */

    private final @Nullable DownscaleScrollableNoiseSuppressor scrollableViewNoiseSuppressor;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceGlassFrosted;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceGlass;
    private IBlur3Capture iBlur3Capture;
    private SolegramFloatingHeader headerFade;

    private final ArrayList<android.graphics.RectF> iBlur3Positions = new ArrayList<>();
    private final android.graphics.RectF iBlur3PositionActionBar = new android.graphics.RectF();
    private final android.graphics.RectF iBlur3PositionMainTabs = new android.graphics.RectF();

    {
        iBlur3Positions.add(iBlur3PositionActionBar);
        iBlur3Positions.add(iBlur3PositionMainTabs);
    }

    private void blur3_InvalidateBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || scrollableViewNoiseSuppressor == null || fragmentView == null || iBlur3Capture == null) {
            return;
        }
        final int additionalList = dp(48);
        final int mainTabBottom = fragmentView.getMeasuredHeight() - navigationBarHeight - dp(DialogsActivity.MAIN_TABS_MARGIN + DialogsActivity.MAIN_TABS_LIFT);
        final int mainTabTop = mainTabBottom - dp(DialogsActivity.MAIN_TABS_HEIGHT);
        iBlur3PositionActionBar.set(0, -additionalList, fragmentView.getMeasuredWidth(), actionBar.getMeasuredHeight() + additionalList);
        iBlur3PositionMainTabs.set(0, mainTabTop, fragmentView.getMeasuredWidth(), mainTabBottom);
        iBlur3PositionMainTabs.inset(0, LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0 : -dp(48));
        scrollableViewNoiseSuppressor.setupRenderNodes(iBlur3Positions, hasMainTabs ? 2 : 1);
        scrollableViewNoiseSuppressor.invalidateResultRenderNodes(iBlur3Capture, fragmentView.getMeasuredWidth(), fragmentView.getMeasuredHeight());
    }

    @Override
    public BlurredBackgroundSourceRenderNode getGlassSource() {
        return iBlur3SourceGlass;
    }
}
