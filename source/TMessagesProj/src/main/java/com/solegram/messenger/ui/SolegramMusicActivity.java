/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solegram.messenger.SolegramPlaylists;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;

import java.util.ArrayList;

/**
 * The Music tab: what plays now, the user's playlists, and the music from their chats. A track plays with the
 * list it is in, in Solegram's player; a long press adds it to a playlist.
 */
public class SolegramMusicActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, MainTabsActivity.TabFragmentDelegate {

    private static final int T_NOW = 0, T_HEADER = 1, T_NEW = 2, T_PLAYLIST = 3, T_TRACK = 4, T_NOTE = 5;

    private boolean hasMainTabs;
    private int navigationBarHeight;
    private RecyclerView listView;
    private LinearLayoutManager layoutManager;
    private Adapter adapter;

    private final ArrayList<MessageObject> chatMusic = new ArrayList<>();
    private boolean loading, endReached;
    private int nextRate, offsetId;
    private TLRPC.InputPeer offsetPeer = new TLRPC.TL_inputPeerEmpty();

    private final ArrayList<int[]> rows = new ArrayList<>(); // {type, index}

    // the glass of the tab bar and the header sees this list (the same scheme as the other main tabs)
    private final int ADDITIONAL_LIST_HEIGHT_DP = android.os.Build.VERSION.SDK_INT >= 31 ? 48 : 0;
    private final @androidx.annotation.Nullable org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor scrollableViewNoiseSuppressor;
    private final @androidx.annotation.Nullable org.telegram.utils.glass.source.GlassSourceRenderNode iBlur3SourceGlassFrosted;
    private final @androidx.annotation.Nullable org.telegram.utils.glass.source.GlassSourceRenderNode iBlur3SourceGlass;
    private org.telegram.ui.Components.blur3.capture.IBlur3Capture iBlur3Capture;
    private SolegramFloatingHeader headerFade;
    private org.telegram.ui.Components.SizeNotifierFrameLayout contentView;
    private final ArrayList<android.graphics.RectF> iBlur3Positions = new ArrayList<>();
    private final android.graphics.RectF iBlur3PositionActionBar = new android.graphics.RectF();
    private final android.graphics.RectF iBlur3PositionMainTabs = new android.graphics.RectF();

    public SolegramMusicActivity(Bundle args) {
        super(args);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            scrollableViewNoiseSuppressor = new org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor();
            iBlur3SourceGlassFrosted = new org.telegram.utils.glass.source.GlassSourceRenderNode(null);
            iBlur3SourceGlass = new org.telegram.utils.glass.source.GlassSourceRenderNode(null);
        } else {
            scrollableViewNoiseSuppressor = null;
            iBlur3SourceGlassFrosted = null;
            iBlur3SourceGlass = null;
        }
        iBlur3Positions.add(iBlur3PositionActionBar);
        iBlur3Positions.add(iBlur3PositionMainTabs);
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
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public org.telegram.utils.glass.source.GlassSourceRenderNode getGlassSource() {
        return iBlur3SourceGlass;
    }

    /** The frosted picture for the tab bar when liquid glass is off (Telegram 13.2's tab bar reads it from the page). */
    @Override
    public org.telegram.utils.glass.source.GlassSourceRenderNode getFrostedGlassSource() {
        return iBlur3SourceGlassFrosted;
    }

    private void blur3_InvalidateBlur() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S || scrollableViewNoiseSuppressor == null || fragmentView == null || iBlur3Capture == null) {
            return;
        }
        final int additionalList = dp(48);
        final int mainTabBottom = fragmentView.getMeasuredHeight() - navigationBarHeight - dp(DialogsActivity.MAIN_TABS_MARGIN + DialogsActivity.MAIN_TABS_LIFT);
        final int mainTabTop = mainTabBottom - dp(DialogsActivity.MAIN_TABS_HEIGHT);
        iBlur3PositionActionBar.set(0, -additionalList, fragmentView.getMeasuredWidth(), actionBar.getMeasuredHeight() + additionalList);
        iBlur3PositionMainTabs.set(0, mainTabTop, fragmentView.getMeasuredWidth(), mainTabBottom);
        iBlur3PositionMainTabs.inset(0, org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS) ? 0 : -dp(48));
        scrollableViewNoiseSuppressor.setupRenderNodes(iBlur3Positions, hasMainTabs ? 2 : 1);
        scrollableViewNoiseSuppressor.invalidateResultRenderNodes(iBlur3Capture, fragmentView.getMeasuredWidth(), fragmentView.getMeasuredHeight());
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        hasMainTabs = arguments != null && arguments.getBoolean("hasMainTabs", false);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagePlayingDidReset);
        loadMore();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidStart);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagePlayingDidReset);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        rebuild();
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuild();
    }

    @Override
    public View createView(Context context) {
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.SG_PlayerMusic));
        if (!hasMainTabs) {
            actionBar.setBackButtonDrawable(new BackDrawable(false));
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    createPlaylist(SolegramMusicActivity.this, null, p -> rebuild());
                }
            }
        });
        actionBar.createMenu().addItem(1, R.drawable.msg_add).setContentDescription(getString(R.string.SG_MusicNewPlaylist));

        final int bg = getThemedColor(Theme.key_windowBackgroundWhite);
        fragmentView = contentView = new org.telegram.ui.Components.SizeNotifierFrameLayout(context) {
            @Override
            protected void dispatchDraw(android.graphics.Canvas canvas) {
                if (android.os.Build.VERSION.SDK_INT >= 31 && scrollableViewNoiseSuppressor != null) {
                    blur3_InvalidateBlur();
                    final int width = getMeasuredWidth();
                    final int height = getMeasuredHeight();
                    if (iBlur3SourceGlassFrosted != null && !iBlur3SourceGlassFrosted.inRecording()) {
                        final android.graphics.Canvas c = iBlur3SourceGlassFrosted.beginRecording(width, height);
                        c.drawColor(bg);
                        if (org.telegram.messenger.SharedConfig.chatBlurEnabled()) {
                            scrollableViewNoiseSuppressor.draw(c, org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor.DRAW_FROSTED_GLASS);
                        }
                        iBlur3SourceGlassFrosted.endRecording();
                    }
                    if (iBlur3SourceGlass != null && !iBlur3SourceGlass.inRecording()) {
                        final android.graphics.Canvas c = iBlur3SourceGlass.beginRecording(width, height);
                        c.drawColor(bg);
                        if (org.telegram.messenger.SharedConfig.chatBlurEnabled()) {
                            scrollableViewNoiseSuppressor.draw(c, org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor.DRAW_GLASS);
                        }
                        iBlur3SourceGlass.endRecording();
                    }
                }
                super.dispatchDraw(canvas);
            }

            @Override
            protected boolean drawChild(android.graphics.Canvas canvas, View child, long drawingTime) {
                if (child == actionBar && headerFade != null) {
                    headerFade.draw(canvas, actionBar, getMeasuredWidth(), bg, 1f);
                }
                return super.drawChild(canvas, child, drawingTime);
            }

            @Override
            public void drawBlurRect(android.graphics.Canvas canvas, float y, android.graphics.Rect rectTmp, android.graphics.Paint blurScrimPaint, boolean top) {
                frosted(canvas, y, rectTmp, blurScrimPaint, 1f);
            }

            @Override
            public void drawBlurRect(android.graphics.Canvas canvas, float y, android.graphics.Rect rectTmp, android.graphics.Paint blurScrimPaint, boolean top, float alpha) {
                frosted(canvas, y, rectTmp, blurScrimPaint, alpha);
            }

            private void frosted(android.graphics.Canvas canvas, float y, android.graphics.Rect rectTmp, android.graphics.Paint blurScrimPaint, float amount) {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q || !org.telegram.messenger.SharedConfig.chatBlurEnabled() || iBlur3SourceGlassFrosted == null) {
                    canvas.drawRect(rectTmp, blurScrimPaint);
                    return;
                }
                canvas.save();
                canvas.translate(0, -y);
                iBlur3SourceGlassFrosted.draw(canvas, rectTmp.left, rectTmp.top + y, rectTmp.right, rectTmp.bottom + y);
                canvas.restore();
                final int veil = com.solegram.messenger.SolegramGlass.headerScrimAlpha(org.telegram.ui.ChatActivity.ACTION_BAR_BLUR_ALPHA);
                final int old = blurScrimPaint.getAlpha();
                blurScrimPaint.setAlpha(Math.round(255 + (veil - 255) * Math.max(0f, Math.min(1f, amount))));
                canvas.drawRect(rectTmp, blurScrimPaint);
                blurScrimPaint.setAlpha(old);
            }

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                measureChildWithMargins(actionBar, widthMeasureSpec, 0, heightMeasureSpec, 0);
                updatePadding();
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            }
        };
        contentView.setBackgroundColor(bg);
        final org.telegram.ui.Components.RecyclerListView list = new org.telegram.ui.Components.RecyclerListView(context, getResourceProvider());
        listView = list;
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setClipToPadding(false);
        listView.setAdapter(adapter = new Adapter());
        // taps go through the list itself, as in Telegram's lists: rows keep no click listeners of their own
        list.setOnItemClickListener((view, position) -> onRowClick(position));
        list.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= rows.size() || rows.get(position)[0] != T_TRACK) {
                return false;
            }
            addToPlaylist(SolegramMusicActivity.this, getParentActivity(), chatMusic.get(rows.get(position)[1]));
            return true;
        });
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (!loading && !endReached && layoutManager.findLastVisibleItemPosition() > rows.size() - 8) {
                    loadMore();
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                    scrollableViewNoiseSuppressor.onScrolled(dx, dy);
                    blur3_InvalidateBlur();
                }
            }
        });
        list.addEdgeEffectListener(() -> listView.postOnAnimation(this::blur3_InvalidateBlur));
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT, 0, -ADDITIONAL_LIST_HEIGHT_DP, 0, -ADDITIONAL_LIST_HEIGHT_DP));
        iBlur3Capture = new org.telegram.ui.Components.blur3.ViewGroupPartRenderer(listView, contentView, list::drawChild);
        headerFade = SolegramFloatingHeader.enabled() ? new SolegramFloatingHeader() : null;
        contentView.addView(actionBar);
        if (headerFade != null) {
            actionBar.setBackground(null);
            SolegramFloatingHeader.setupButtons(actionBar, SolegramFloatingHeader.factory(iBlur3SourceGlass, bg, contentView), getResourceProvider());
        } else {
            actionBar.setAdaptiveBackground(listView);
            actionBar.setDrawBlurBackground(contentView);
        }
        updatePadding();
        rebuild();
        return contentView;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        navigationBarHeight = bottom;
        updatePadding();
    }

    private void updatePadding() {
        if (listView != null && actionBar != null) {
            final int tabs = hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0;
            listView.setPadding(0, dp(ADDITIONAL_LIST_HEIGHT_DP + 4) + actionBar.getMeasuredHeight(), 0, dp(ADDITIONAL_LIST_HEIGHT_DP + 16) + navigationBarHeight + tabs);
        }
    }

    @Override
    public boolean canParentTabsSlide(android.view.MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null) {
            listView.smoothScrollToPosition(0);
        }
    }

    // ------------------------------------------------------------------ data

    private void loadMore() {
        if (loading || endReached) {
            return;
        }
        loading = true;
        final TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
        req.q = "";
        req.filter = new TLRPC.TL_inputMessagesFilterMusic();
        req.limit = 40;
        req.offset_rate = nextRate;
        req.offset_peer = offsetPeer;
        req.offset_id = offsetId;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            loading = false;
            if (!(response instanceof TLRPC.messages_Messages)) {
                endReached = true;
                rebuild();
                return;
            }
            final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
            final MessagesController mc = getMessagesController();
            mc.putUsers(res.users, false);
            mc.putChats(res.chats, false);
            int added = 0;
            for (TLRPC.Message m : res.messages) {
                final MessageObject mo = new MessageObject(currentAccount, m, false, true);
                if (mo.isMusic()) {
                    chatMusic.add(mo);
                    added++;
                }
            }
            if (!res.messages.isEmpty()) {
                final TLRPC.Message last = res.messages.get(res.messages.size() - 1);
                offsetId = last.id;
                offsetPeer = mc.getInputPeer(MessageObject.getDialogId(last));
            }
            if (res instanceof TLRPC.TL_messages_messagesSlice) {
                nextRate = ((TLRPC.TL_messages_messagesSlice) res).next_rate;
            }
            if (res.messages.size() < req.limit || added == 0 && res.messages.isEmpty()) {
                endReached = true;
            }
            rebuild();
        }));
    }

    private void onRowClick(int position) {
        if (position < 0 || position >= rows.size()) {
            return;
        }
        final int[] row = rows.get(position);
        switch (row[0]) {
            case T_NOW:
                SolegramPlayer.open(getParentActivity(), getResourceProvider());
                break;
            case T_NEW:
                createPlaylist(this, null, p -> {
                    rebuild();
                    presentFragment(new SolegramPlaylistActivity(p.id));
                });
                break;
            case T_PLAYLIST: {
                final ArrayList<SolegramPlaylists.Playlist> pls = SolegramPlaylists.all(currentAccount);
                if (row[1] < pls.size()) {
                    presentFragment(new SolegramPlaylistActivity(pls.get(row[1]).id));
                }
                break;
            }
            case T_TRACK:
                if (row[1] < chatMusic.size()) {
                    play(this, chatMusic, chatMusic.get(row[1]));
                }
                break;
        }
    }

    private void rebuild() {
        rows.clear();
        if (MediaController.getInstance().getPlayingMessageObject() != null && MediaController.getInstance().getPlayingMessageObject().isMusic()) {
            rows.add(new int[]{T_NOW, 0});
        }
        rows.add(new int[]{T_HEADER, R.string.SG_MusicPlaylists});
        final ArrayList<SolegramPlaylists.Playlist> pls = SolegramPlaylists.all(currentAccount);
        for (int i = 0; i < pls.size(); i++) {
            rows.add(new int[]{T_PLAYLIST, i});
        }
        rows.add(new int[]{T_NEW, 0});
        rows.add(new int[]{T_HEADER, R.string.SG_MusicFromChats});
        for (int i = 0; i < chatMusic.size(); i++) {
            rows.add(new int[]{T_TRACK, i});
        }
        if (chatMusic.isEmpty()) {
            rows.add(new int[]{T_NOTE, loading || !endReached ? R.string.SG_MusicLoading : R.string.SG_MusicNone});
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    // ------------------------------------------------------------------ actions

    /** Plays a list from a track, in Solegram's player (or Telegram's, as chosen). */
    public static void play(BaseFragment fragment, ArrayList<MessageObject> list, MessageObject from) {
        if (from == null || list.isEmpty() || fragment.getParentActivity() == null) {
            return;
        }
        if (MediaController.getInstance().setPlaylist(new ArrayList<>(list), from, 0)) {
            SolegramPlayer.open(fragment.getParentActivity(), fragment.getResourceProvider());
        } else {
            BulletinFactory.of(fragment).createErrorBulletin(getString(R.string.SG_MusicCantPlay)).show();
        }
    }

    public static void createPlaylist(BaseFragment fragment, MessageObject first, org.telegram.messenger.Utilities.Callback<SolegramPlaylists.Playlist> done) {
        askName(fragment, getString(R.string.SG_MusicNewPlaylist), "", name -> {
            final SolegramPlaylists.Playlist p = SolegramPlaylists.create(fragment.getCurrentAccount(), name);
            if (first != null) {
                SolegramPlaylists.add(fragment.getCurrentAccount(), p, first);
            }
            if (done != null) {
                done.run(p);
            }
        });
    }

    static void askName(BaseFragment fragment, String title, String value, org.telegram.messenger.Utilities.Callback<String> done) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final EditText edit = new EditText(context);
        edit.setText(value);
        edit.setHint(getString(R.string.SG_MusicPlaylistName));
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        edit.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, fragment.getResourceProvider()));
        edit.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, fragment.getResourceProvider()));
        final FrameLayout box = new FrameLayout(context);
        box.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 22, 4, 22, 0));
        fragment.showDialog(new AlertDialog.Builder(context, fragment.getResourceProvider())
            .setTitle(title)
            .setView(box)
            .setPositiveButton(getString(R.string.Save), (d, w) -> {
                final String n = edit.getText().toString().trim();
                if (!TextUtils.isEmpty(n)) {
                    done.run(n);
                }
            })
            .setNegativeButton(getString(R.string.Cancel), null)
            .create());
        AndroidUtilities.runOnUIThread(() -> {
            edit.requestFocus();
            AndroidUtilities.showKeyboard(edit);
        }, 250);
    }

    /** "Add to playlist": the playlists, or a new one. */
    public static void addToPlaylist(BaseFragment fragment, Context context, MessageObject track) {
        if (track == null || context == null) {
            return;
        }
        final int account = fragment != null ? fragment.getCurrentAccount() : track.currentAccount;
        final BottomSheet sheet = new BottomSheet(context, false, fragment != null ? fragment.getResourceProvider() : null);
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(8), 0, dp(12));
        final TextView head = new TextView(context);
        head.setText(getString(R.string.SG_MusicAddTo));
        head.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        head.setTypeface(AndroidUtilities.bold());
        head.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        column.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 8, 20, 8));
        for (SolegramPlaylists.Playlist p : SolegramPlaylists.all(account)) {
            final TextView row = sheetRow(context, p.name + "  ·  " + LocaleController.formatPluralString("SG_Tracks", p.tracks.size()));
            row.setOnClickListener(v -> {
                final boolean added = SolegramPlaylists.add(account, p, track);
                sheet.dismiss();
                toast(fragment, added ? formatString(R.string.SG_MusicAdded, p.name) : getString(R.string.SG_MusicAlready));
            });
            column.addView(row);
        }
        final TextView create = sheetRow(context, "+  " + getString(R.string.SG_MusicNewPlaylist));
        create.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        create.setOnClickListener(v -> {
            sheet.dismiss();
            if (fragment != null) {
                createPlaylist(fragment, track, p -> toast(fragment, formatString(R.string.SG_MusicAdded, p.name)));
            }
        });
        column.addView(create);
        final android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(column);
        sheet.setCustomView(scroll);
        sheet.show();
    }

    private static TextView sheetRow(Context context, String text) {
        final TextView row = new TextView(context);
        row.setText(text);
        row.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        row.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        row.setSingleLine();
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));
        row.setBackground(Theme.getSelectorDrawable(false));
        return row;
    }

    private static void toast(BaseFragment fragment, String text) {
        if (fragment != null) {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.done, text).show();
        }
    }

    // ------------------------------------------------------------------ list

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return rows.get(position)[0];
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            final Context context = parent.getContext();
            View v;
            switch (type) {
                case T_NOW:
                    v = new NowPlayingCard(context);
                    break;
                case T_HEADER: {
                    final TextView t = new TextView(context);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
                    t.setTypeface(AndroidUtilities.bold());
                    t.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                    t.setPadding(dp(18), dp(18), dp(18), dp(8));
                    v = t;
                    break;
                }
                case T_NEW: {
                    final TextView t = new TextView(context);
                    t.setText("+  " + getString(R.string.SG_MusicNewPlaylist));
                    t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                    t.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText4));
                    t.setPadding(dp(18), dp(12), dp(18), dp(12));
                    v = t;
                    break;
                }
                case T_NOTE: {
                    final TextView t = new TextView(context);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                    t.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                    t.setPadding(dp(18), dp(8), dp(18), dp(8));
                    v = t;
                    break;
                }
                case T_PLAYLIST:
                    v = new PlaylistRow(context);
                    break;
                default:
                    v = new TrackRow(context, getResourceProvider());
            }
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(v) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final int[] row = rows.get(position);
            switch (row[0]) {
                case T_NOW:
                    ((NowPlayingCard) holder.itemView).bind();
                    break;
                case T_HEADER:
                case T_NOTE:
                    ((TextView) holder.itemView).setText(getString(row[1]));
                    break;
                case T_PLAYLIST: {
                    final SolegramPlaylists.Playlist p = SolegramPlaylists.all(currentAccount).get(row[1]);
                    ((PlaylistRow) holder.itemView).bind(p);
                    break;
                }
                case T_TRACK: {
                    final MessageObject m = chatMusic.get(row[1]);
                    final TrackRow r = (TrackRow) holder.itemView;
                    r.bind(m);
                    break;
                }
            }
        }
    }

    /** What plays now: tap opens the player. */
    private class NowPlayingCard extends LinearLayout {
        final TrackRow row;

        NowPlayingCard(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(12), dp(6), dp(12), dp(4));
            row = new TrackRow(context, getResourceProvider());
            final GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(Theme.multAlpha(getThemedColor(Theme.key_featuredStickers_addButton), 0.12f));
            row.setBackground(bg);
            addView(row);
        }

        void bind() {
            row.bind(MediaController.getInstance().getPlayingMessageObject());
        }
    }

    private class PlaylistRow extends LinearLayout {
        final TextView name, info;
        final ImageView art;
        final GradientDrawable artBackground;

        PlaylistRow(Context context) {
            super(context);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), dp(8), dp(16), dp(8));
            setBackground(Theme.getSelectorDrawable(false));
            art = new ImageView(context);
            artBackground = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFF9F43, 0xFFEE5A6F});
            artBackground.setCornerRadius(dp(12));
            addView(art, LayoutHelper.createLinear(52, 52, 0, 0, 14, 0));
            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            name.setTypeface(AndroidUtilities.bold());
            name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(name);
            info = new TextView(context);
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            info.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            texts.addView(info);
            addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        }

        void bind(SolegramPlaylists.Playlist p) {
            final android.graphics.Bitmap c = SolegramPlaylists.cover(p);
            if (c != null) {
                final androidx.core.graphics.drawable.RoundedBitmapDrawable d = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(getResources(), c);
                d.setCornerRadius(dp(12));
                art.setImageDrawable(d);
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.clearColorFilter();
                art.setBackground(null);
            } else {
                art.setImageResource(R.drawable.files_music);
                art.setScaleType(ImageView.ScaleType.CENTER);
                art.setColorFilter(Color.WHITE);
                art.setBackground(artBackground);
            }
            name.setText(p.name);
            info.setText(LocaleController.formatPluralString("SG_Tracks", p.tracks.size()) + (p.tracks.isEmpty() ? "" : " · " + AndroidUtilities.formatShortDuration(p.seconds())));
        }
    }

    /** A track: its cover, title, artist and length; the playing one in the accent colour. */
    public static class TrackRow extends LinearLayout {
        final BackupImageView cover;
        final TextView title, artist, length;
        private final Theme.ResourcesProvider rp;

        public TrackRow(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.rp = rp;
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16), dp(7), dp(16), dp(7));
            setBackground(Theme.getSelectorDrawable(false));
            cover = new BackupImageView(context);
            cover.setRoundRadius(dp(10));
            addView(cover, LayoutHelper.createLinear(46, 46, 0, 0, 14, 0));
            final LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            artist = new TextView(context);
            artist.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            artist.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
            artist.setSingleLine();
            artist.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(artist);
            addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            length = new TextView(context);
            length.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            length.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
            addView(length, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 10, 0, 0, 0));
        }

        public void bind(MessageObject m) {
            if (m == null) {
                return;
            }
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            final boolean isPlaying = playing != null && playing.getId() == m.getId() && playing.getDialogId() == m.getDialogId();
            title.setText(m.getMusicTitle());
            title.setTextColor(Theme.getColor(isPlaying ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundWhiteBlackText, rp));
            title.setTypeface(isPlaying ? AndroidUtilities.bold() : null);
            artist.setText(m.getMusicAuthor());
            length.setText(AndroidUtilities.formatShortDuration((int) Math.round(m.getDuration())));
            final TLRPC.Document doc = m.getDocument();
            final TLRPC.PhotoSize thumb = doc != null ? FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 90) : null;
            final GradientDrawable placeholder = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF8E9AAF, 0xFF5B6478});
            placeholder.setCornerRadius(dp(10));
            if (thumb != null) {
                cover.setBackground(null);
                cover.setImage(ImageLocation.getForDocument(thumb, doc), "92_92", placeholder, m);
            } else if (m.getArtworkUrl(true) != null) {
                cover.setBackground(null);
                cover.setImage(ImageLocation.getForPath(m.getArtworkUrl(true)), "92_92", placeholder, m);
            } else {
                cover.setImageDrawable(null);
                cover.setBackground(placeholder);
            }
        }
    }

    // ------------------------------------------------------------------ a playlist

    public static class SolegramPlaylistActivity extends BaseFragment {
        private final long playlistId;
        private ArrayList<MessageObject> loaded;
        private LinearLayout column;

        public SolegramPlaylistActivity(long playlistId) {
            this.playlistId = playlistId;
        }

        private SolegramPlaylists.Playlist playlist() {
            return SolegramPlaylists.get(currentAccount, playlistId);
        }

        @Override
        public View createView(Context context) {
            final SolegramPlaylists.Playlist p = playlist();
            actionBar.setBackButtonDrawable(new BackDrawable(false));
            actionBar.setAllowOverlayTitle(true);
            actionBar.setTitle(p != null ? p.name : "");
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        finishFragment();
                    } else if (id == 1) {
                        final SolegramPlaylists.Playlist pl = playlist();
                        if (pl != null) {
                            askName(SolegramPlaylistActivity.this, getString(R.string.SG_MusicRename), pl.name, n -> {
                                SolegramPlaylists.rename(currentAccount, pl, n);
                                actionBar.setTitle(pl.name);
                            });
                        }
                    } else if (id == 3) {
                        pickCover();
                    } else if (id == 2) {
                        final SolegramPlaylists.Playlist pl = playlist();
                        if (pl != null && getParentActivity() != null) {
                            showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                                .setTitle(getString(R.string.SG_MusicDelete))
                                .setMessage(formatString(R.string.SG_MusicDeleteInfo, pl.name))
                                .setPositiveButton(getString(R.string.Delete), (d, w) -> {
                                    SolegramPlaylists.delete(currentAccount, pl);
                                    finishFragment();
                                })
                                .setNegativeButton(getString(R.string.Cancel), null)
                                .create());
                        }
                    }
                }
            });
            final org.telegram.ui.ActionBar.ActionBarMenuItem other = actionBar.createMenu().addItem(0, R.drawable.ic_ab_other);
            other.addSubItem(3, R.drawable.msg_photos, getString(R.string.SG_MusicCover));
            other.addSubItem(1, R.drawable.msg_edit, getString(R.string.SG_MusicRename));
            other.addSubItem(2, R.drawable.msg_delete, getString(R.string.SG_MusicDelete));

            final android.widget.ScrollView scroll = new android.widget.ScrollView(context);
            scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(0, dp(8), 0, dp(24));
            scroll.addView(column);
            fragmentView = scroll;
            fill();
            return scroll;
        }

        @Override
        public void onResume() {
            super.onResume();
            if (column != null) {
                fill();
            }
        }

        private void fill() {
            final SolegramPlaylists.Playlist p = playlist();
            column.removeAllViews();
            if (p == null) {
                return;
            }
            final Context context = column.getContext();
            // the cover, large: tap to change it
            final ImageView cover = new ImageView(context);
            final android.graphics.Bitmap bmp = SolegramPlaylists.cover(p);
            if (bmp != null) {
                final androidx.core.graphics.drawable.RoundedBitmapDrawable d = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(context.getResources(), bmp);
                d.setCornerRadius(dp(22));
                cover.setImageDrawable(d);
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                final GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFF9F43, 0xFFEE5A6F});
                g.setCornerRadius(dp(22));
                cover.setBackground(g);
                cover.setImageResource(R.drawable.msg_photos);
                cover.setColorFilter(0xCCFFFFFF);
                cover.setScaleType(ImageView.ScaleType.CENTER);
            }
            cover.setContentDescription(getString(R.string.SG_MusicCover));
            cover.setOnClickListener(v -> pickCover());
            SolegramMotion.pressable(cover, SolegramMotion.PRESS_CARD);
            column.addView(cover, LayoutHelper.createLinear(176, 176, Gravity.CENTER_HORIZONTAL, 0, 8, 0, 6));
            final TextView name = new TextView(context);
            name.setText(p.name);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
            name.setTypeface(AndroidUtilities.bold());
            name.setGravity(Gravity.CENTER);
            name.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            column.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 0));
            final TextView info = new TextView(context);
            info.setText(LocaleController.formatPluralString("SG_Tracks", p.tracks.size()) + (p.tracks.isEmpty() ? "" : " · " + AndroidUtilities.formatShortDuration(p.seconds())));
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            info.setGravity(Gravity.CENTER);
            info.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            column.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 2, 24, 4));
            final LinearLayout buttons = new LinearLayout(context);
            buttons.setPadding(dp(14), dp(6), dp(14), dp(10));
            buttons.addView(bigButton(context, R.string.SG_MusicPlay, false), LayoutHelper.createLinear(0, 46, 1f, 0, 0, 6, 0));
            buttons.addView(bigButton(context, R.string.SG_MusicShuffle, true), LayoutHelper.createLinear(0, 46, 1f, 6, 0, 0, 0));
            column.addView(buttons);
            if (p.tracks.isEmpty()) {
                final TextView empty = new TextView(context);
                empty.setText(getString(R.string.SG_MusicEmptyPlaylist));
                empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                empty.setGravity(Gravity.CENTER);
                empty.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                empty.setPadding(dp(30), dp(30), dp(30), dp(30));
                column.addView(empty);
                return;
            }
            for (int i = 0; i < p.tracks.size(); i++) {
                final SolegramPlaylists.Track t = p.tracks.get(i);
                final int index = i;
                final LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(18), dp(10), dp(18), dp(10));
                row.setBackground(Theme.getSelectorDrawable(false));
                final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
                final boolean isPlaying = playing != null && t.same(playing);
                final TextView title = new TextView(context);
                title.setText((i + 1) + ".  " + t.title);
                title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
                title.setTextColor(getThemedColor(isPlaying ? Theme.key_featuredStickers_addButton : Theme.key_windowBackgroundWhiteBlackText));
                title.setTypeface(isPlaying ? AndroidUtilities.bold() : null);
                title.setSingleLine();
                title.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(title);
                final TextView sub = new TextView(context);
                sub.setText(t.artist + "  ·  " + AndroidUtilities.formatShortDuration(t.seconds));
                sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                sub.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                sub.setSingleLine();
                sub.setEllipsize(TextUtils.TruncateAt.END);
                row.addView(sub);
                row.setOnClickListener(v -> playFrom(index, false));
                row.setOnLongClickListener(v -> {
                    final ItemOptions o = ItemOptions.makeOptions(this, row);
                    if (index > 0) {
                        o.add(R.drawable.msg_go_up, getString(R.string.SG_MusicUp), () -> {
                            SolegramPlaylists.move(currentAccount, p, index, index - 1);
                            loaded = null;
                            fill();
                        });
                    }
                    if (index < p.tracks.size() - 1) {
                        o.add(R.drawable.msg_go_down, getString(R.string.SG_MusicDown), () -> {
                            SolegramPlaylists.move(currentAccount, p, index, index + 1);
                            loaded = null;
                            fill();
                        });
                    }
                    o.add(R.drawable.msg_delete, getString(R.string.SG_MusicRemove), true, () -> {
                        SolegramPlaylists.remove(currentAccount, p, index);
                        loaded = null;
                        fill();
                    });
                    o.show();
                    return true;
                });
                column.addView(row);
            }
        }

        private static final int PICK_COVER = 9461;

        private void pickCover() {
            final SolegramPlaylists.Playlist p = playlist();
            if (p == null) {
                return;
            }
            if (SolegramPlaylists.cover(p) != null && getParentActivity() != null) {
                final ItemOptions o = ItemOptions.makeOptions(this, fragmentView);
                o.add(R.drawable.msg_photos, getString(R.string.SG_MusicCoverChange), this::openPicker);
                o.add(R.drawable.msg_delete, getString(R.string.SG_MusicCoverRemove), true, () -> {
                    SolegramPlaylists.removeCover(p);
                    fill();
                });
                o.setGravity(Gravity.CENTER_HORIZONTAL);
                o.show();
            } else {
                openPicker();
            }
        }

        private void openPicker() {
            try {
                final android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
                startActivityForResult(android.content.Intent.createChooser(intent, getString(R.string.SG_MusicCover)), PICK_COVER);
            } catch (Exception e) {
                org.telegram.messenger.FileLog.e(e);
            }
        }

        @Override
        public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
            super.onActivityResultFragment(requestCode, resultCode, data);
            if (requestCode != PICK_COVER || resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
                return;
            }
            final SolegramPlaylists.Playlist p = playlist();
            if (p == null) {
                return;
            }
            final android.net.Uri uri = data.getData();
            org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
                final boolean ok = SolegramPlaylists.setCover(p, uri);
                AndroidUtilities.runOnUIThread(() -> {
                    if (ok) {
                        fill();
                    } else {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.SG_MusicCoverFailed)).show();
                    }
                });
            });
        }

        private TextView bigButton(Context context, int text, boolean shuffle) {
            final TextView b = new TextView(context);
            b.setText(getString(text));
            b.setGravity(Gravity.CENTER);
            b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            b.setTypeface(AndroidUtilities.bold());
            final int accent = getThemedColor(Theme.key_featuredStickers_addButton);
            b.setTextColor(shuffle ? accent : Color.WHITE);
            b.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14), shuffle ? Theme.multAlpha(accent, 0.12f) : accent,
                shuffle ? Theme.multAlpha(accent, 0.2f) : getThemedColor(Theme.key_featuredStickers_addButtonPressed)));
            b.setOnClickListener(v -> playFrom(0, shuffle));
            return b;
        }

        private void playFrom(int index, boolean shuffle) {
            final SolegramPlaylists.Playlist p = playlist();
            if (p == null || p.tracks.isEmpty()) {
                return;
            }
            final Runnable go = () -> {
                if (loaded == null || loaded.isEmpty()) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.SG_MusicCantLoad)).show();
                    return;
                }
                if (shuffle != org.telegram.messenger.SharedConfig.shuffleMusic) {
                    MediaController.getInstance().setPlaybackOrderType(shuffle ? 2 : 0);
                }
                final SolegramPlaylists.Track t = p.tracks.get(Math.min(index, p.tracks.size() - 1));
                MessageObject from = loaded.get(0);
                if (!shuffle) {
                    for (MessageObject m : loaded) {
                        if (t.same(m)) {
                            from = m;
                            break;
                        }
                    }
                } else {
                    from = loaded.get((int) (Math.random() * loaded.size()));
                }
                play(this, loaded, from);
            };
            if (loaded != null) {
                go.run();
                return;
            }
            final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
            progress.showDelayed(250);
            SolegramPlaylists.load(currentAccount, p, list -> {
                try {
                    progress.dismiss();
                } catch (Exception ignore) {
                }
                loaded = list;
                go.run();
            });
        }
    }
}
