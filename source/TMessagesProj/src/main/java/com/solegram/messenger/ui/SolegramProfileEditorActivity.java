/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramIcons;
import com.solegram.messenger.SolegramProfileCard;
import com.solegram.messenger.SolegramStreak;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The profile card editor: a live preview of the own profile (banner, avatar, name and the tiles under them) where
 * the tiles are dragged into place, hidden with the eye, the banner is picked from the personal channel's posts by
 * their pictures, and the darkening is set on the preview itself. "Save" puts the card on the chosen post
 * (SolegramProfileCard.publish).
 */
public class SolegramProfileEditorActivity extends BaseFragment {

    private static final String CHANNEL = "channel";
    private static final float DIM_MAX = 80f;

    private SolegramProfileCard.Card card = new SolegramProfileCard.Card();
    private MessageObject post;
    /** The tiles as shown, the personal channel among them (it stands either first or last). */
    private final ArrayList<String> rows = new ArrayList<>();
    private boolean busy, loaded;

    private BannerView banner;
    private TextView bannerHint;
    private RecyclerView list;
    private TilesAdapter adapter;
    private ItemTouchHelper dragger;
    private SeekBarView dimBar;
    private TextView dimValue;

    @Override
    public boolean onFragmentCreate() {
        loadPublished();
        return super.onFragmentCreate();
    }

    private TLRPC.UserFull ownFull() {
        return getMessagesController().getUserFull(getUserConfig().getClientUserId());
    }

    private TLRPC.Chat ownChannel() {
        final TLRPC.UserFull full = ownFull();
        if (full == null) {
            getMessagesController().loadFullUser(getUserConfig().getCurrentUser(), classGuid, true);
            return null;
        }
        return full.personal_channel_id == 0 ? null : getMessagesController().getChat(full.personal_channel_id);
    }

    private void loadPublished() {
        final long self = getUserConfig().getClientUserId();
        rebuildRows();
        SolegramProfileCard.forget(self);
        SolegramProfileCard.load(currentAccount, self, ownFull(), () -> {
            final SolegramProfileCard.Card published = SolegramProfileCard.get(self);
            loaded = true;
            if (published != null) {
                card = published.copy();
                post = published.post;
            }
            rebuildRows();
            if (fragmentView != null) {
                bind();
            }
        });
    }

    /** The rows of the preview: what the profile shows, top to bottom (the channel stands first or last). */
    private void rebuildRows() {
        rows.clear();
        final boolean channelShown = !SolegramProfileCard.CHANNEL_HIDDEN.equals(card.channel);
        if (channelShown && !SolegramProfileCard.CHANNEL_BOTTOM.equals(card.channel)) {
            rows.add(CHANNEL);
        }
        for (String t : card.order) {
            if (card.shown(t)) {
                rows.add(t);
            }
        }
        if (channelShown && SolegramProfileCard.CHANNEL_BOTTOM.equals(card.channel)) {
            rows.add(CHANNEL);
        }
    }

    /** What can be added back: the hidden tiles (and the channel). */
    private ArrayList<String> hiddenTiles() {
        final ArrayList<String> out = new ArrayList<>();
        if (SolegramProfileCard.CHANNEL_HIDDEN.equals(card.channel)) {
            out.add(CHANNEL);
        }
        for (String t : card.order) {
            if (!card.shown(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** The rows back into the card: shown tiles in their order, the hidden ones after them. */
    private void applyRows() {
        final ArrayList<String> hidden = new ArrayList<>();
        for (String t : card.order) {
            if (!card.shown(t)) {
                hidden.add(t);
            }
        }
        card.order.clear();
        for (String r : rows) {
            if (!CHANNEL.equals(r)) {
                card.order.add(r);
            }
        }
        card.order.addAll(hidden);
        if (!SolegramProfileCard.CHANNEL_HIDDEN.equals(card.channel)) {
            card.channel = rows.indexOf(CHANNEL) == 0 ? SolegramProfileCard.CHANNEL_TOP : SolegramProfileCard.CHANNEL_BOTTOM;
        }
    }

    private void removeTile(String t) {
        final int at = rows.indexOf(t);
        if (at < 0) {
            return;
        }
        if (CHANNEL.equals(t)) {
            card.channel = SolegramProfileCard.CHANNEL_HIDDEN;
        } else {
            card.hidden.add(t);
        }
        rows.remove(at);
        adapter.notifyItemRemoved(at + 1);
        adapter.notifyItemChanged(rows.size() + 1);
        applyRows();
    }

    private void addTile(String t) {
        if (rows.contains(t)) {
            return;
        }
        int at;
        if (CHANNEL.equals(t)) {
            card.channel = SolegramProfileCard.CHANNEL_TOP;
            at = 0;
        } else {
            card.hidden.remove(t);
            at = rows.size() > 0 && CHANNEL.equals(rows.get(rows.size() - 1)) && rows.size() > 1 ? rows.size() - 1 : rows.size();
        }
        rows.add(at, t);
        adapter.notifyItemInserted(at + 1);
        adapter.notifyItemChanged(rows.size() + 1);
        applyRows();
        list.smoothScrollToPosition(at + 1);
        if ("posts".equals(t) && card.posts.isEmpty()) {
            pickPosts();
        } else if ("streak".equals(t) && card.streakDays <= 0) {
            pickStreak();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.SG_ProfileCard));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == 1) {
                    askRemove();
                }
            }
        });
        actionBar.createMenu().addItem(1, R.drawable.msg_delete).setContentDescription(LocaleController.getString(R.string.SG_CardRemove));

        // one full-size list with the controls over its bottom: the screen gets Solegram's floating header
        // (the list scrolls on under a darkening instead of a grey bar), as every other settings screen
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(12));
        adapter = new TilesAdapter();
        list.setAdapter(adapter);
        dragger = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder holder) {
                return holder.getItemViewType() != 1 ? 0 : super.getMovementFlags(rv, holder);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
                final int a = from.getAdapterPosition() - 1, b = to.getAdapterPosition() - 1;
                if (to.getItemViewType() != 1 || a < 0 || b < 0 || a >= rows.size() || b >= rows.size()) {
                    return false;
                }
                rows.add(b, rows.remove(a));
                adapter.notifyItemMoved(a + 1, b + 1);
                SolegramMotion.haptic(rv);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }

            @Override
            public void onSelectedChanged(RecyclerView.ViewHolder holder, int state) {
                super.onSelectedChanged(holder, state);
                if (holder != null && state == ItemTouchHelper.ACTION_STATE_DRAG) {
                    holder.itemView.animate().scaleX(1.03f).scaleY(1.03f).translationZ(dp(6)).setDuration(160).start();
                }
            }

            @Override
            public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder holder) {
                super.clearView(rv, holder);
                holder.itemView.animate().scaleX(1f).scaleY(1f).translationZ(0).setDuration(200).start();
                // the channel cannot stand between tiles: it goes to the nearer end
                final int at = rows.indexOf(CHANNEL);
                if (at > 0 && at < rows.size() - 1) {
                    final int to = at < rows.size() / 2 ? 0 : rows.size() - 1;
                    rows.add(to, rows.remove(at));
                    adapter.notifyItemMoved(at + 1, to + 1);
                }
                applyRows();
            }
        });
        dragger.attachToRecyclerView(list);
        root.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // the bottom: darkening and save
        final LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        bottom.setPadding(dp(16), dp(10), dp(16), dp(12));
        final LinearLayout dimRow = new LinearLayout(context);
        dimRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView dimTitle = new TextView(context);
        dimTitle.setText(LocaleController.getString(R.string.SG_CardDim));
        dimTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        dimTitle.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        dimRow.addView(dimTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        dimValue = new TextView(context);
        dimValue.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        dimValue.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        dimRow.addView(dimValue, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        bottom.addView(dimRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 0));
        dimBar = new SeekBarView(context, getResourceProvider());
        dimBar.setReportChanges(true);
        dimBar.setDelegate((stop, progress) -> {
            card.dim = Math.round(progress * DIM_MAX);
            dimValue.setText(card.dim + "%");
            if (banner != null) {
                banner.invalidate();
            }
        });
        bottom.addView(dimBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38));
        final TextView save = new TextView(context);
        save.setText(LocaleController.getString(R.string.SG_CardPublish));
        save.setGravity(Gravity.CENTER);
        save.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        save.setTypeface(AndroidUtilities.bold());
        save.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        save.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14), getThemedColor(Theme.key_featuredStickers_addButton),
            getThemedColor(Theme.key_featuredStickers_addButtonPressed)));
        save.setOnClickListener(v -> publish());
        SolegramMotion.pressable(save, SolegramMotion.PRESS_BUTTON);
        bottom.addView(save, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 6, 0, 0));
        root.addView(bottom, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        bottom.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            final int pad = (b - t) + dp(12);
            if (list.getPaddingBottom() != pad) {
                list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), pad);
            }
        });

        fragmentView = root;
        bind();
        return root;
    }

    private void bind() {
        if (dimBar != null) {
            dimBar.setProgress(card.dim / DIM_MAX);
            dimValue.setText(card.dim + "%");
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
        updateBanner();
    }

    private void updateBanner() {
        if (banner == null) {
            return;
        }
        banner.setPost(post);
        if (bannerHint != null) {
            final boolean channel = ownChannel() != null;
            bannerHint.setText(LocaleController.getString(!channel ? R.string.SG_CardNeedChannel : post == null ? R.string.SG_CardTapBanner : R.string.SG_CardTapToChange));
        }
    }

    // region The preview's header

    /** The banner with the avatar and the name over it, as the profile shows them. */
    private class BannerView extends FrameLayout {
        private final ImageReceiver image = new ImageReceiver(this);
        private final Paint dim = new Paint();
        private final Paint empty = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private int dimFor = -1, dimHeight;

        BannerView(Context context) {
            super(context);
            setWillNotDraw(false);
            image.setAllowStartAnimation(true);
            image.setAutoRepeat(1);
            final TLRPC.User user = getUserConfig().getCurrentUser();
            final BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(42));
            final AvatarDrawable placeholder = new AvatarDrawable(user);
            avatar.setForUserOrChat(user, placeholder);
            addView(avatar, LayoutHelper.createFrame(84, 84, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 58, 0, 0));
            final TextView name = new TextView(context);
            name.setText(UserObject.getUserName(user));
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            name.setTypeface(AndroidUtilities.bold());
            name.setShadowLayer(dp(3), 0, dp(1), 0x66000000);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 24, 150, 24, 0));
            setOnClickListener(v -> pickBanner());
            SolegramMotion.pressable(this, SolegramMotion.PRESS_CARD);
        }

        void setPost(MessageObject p) {
            if (p == null) {
                image.clearImage();
            } else {
                final TLRPC.Document doc = p.getDocument();
                if (doc != null && !(p.messageOwner.media instanceof TLRPC.TL_messageMediaPhoto)) {
                    final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 320);
                    image.setImage(ImageLocation.getForDocument(doc), ImageLoader.AUTOPLAY_FILTER, null, null,
                        ImageLocation.getForDocument(thumb, doc), "b", null, doc.size, null, p, 0);
                } else {
                    final TLRPC.PhotoSize big = FileLoader.getClosestPhotoSizeWithSize(p.photoThumbs, 1280);
                    final TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(p.photoThumbs, 50);
                    image.setImage(ImageLocation.getForObject(big, p.photoThumbsObject), null, ImageLocation.getForObject(small, p.photoThumbsObject), "b",
                        big != null ? big.size : 0, null, p, 1);
                }
            }
            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            image.onAttachedToWindow();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            image.onDetachedFromWindow();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            rect.set(dp(12), dp(8), getWidth() - dp(12), getHeight() - dp(4));
            clip.rewind();
            clip.addRoundRect(rect, dp(22), dp(22), Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            if (image.hasImageSet()) {
                image.setImageCoords(rect.left, rect.top, rect.width(), rect.height());
                image.draw(canvas);
            } else {
                final int accent = getThemedColor(Theme.key_featuredStickers_addButton);
                empty.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    androidx.core.graphics.ColorUtils.blendARGB(accent, Color.WHITE, 0.25f), accent, Shader.TileMode.CLAMP));
                canvas.drawRect(rect, empty);
            }
            if (card.dim > 0 && image.hasImageSet()) {
                if (dimFor != card.dim || dimHeight != getHeight()) {
                    dimFor = card.dim;
                    dimHeight = getHeight();
                    dim.setShader(new LinearGradient(0, rect.top + rect.height() * 0.3f, 0, rect.bottom, 0,
                        Color.argb((int) (2.55f * card.dim), 0, 0, 0), Shader.TileMode.CLAMP));
                }
                canvas.drawRect(rect, dim);
            }
            canvas.restore();
            super.dispatchDraw(canvas);
        }
    }

    // endregion

    // region The tiles: the profile itself, its blocks moved with a finger

    private int tileIcon(String t) {
        switch (t) {
            case CHANNEL:
                return R.drawable.msg_channel;
            case "phone":
                return R.drawable.msg_calls;
            case "bio":
                return R.drawable.msg_info;
            case "links":
                return R.drawable.msg_link;
            case "music":
                return R.drawable.files_music;
            case "posts":
                return R.drawable.msg_media;
            case "streak":
                return R.drawable.notification;
            case "username":
                return R.drawable.msg_mention;
            case "id":
                return R.drawable.msg_copy;
            case "birthday":
                return R.drawable.msg_gift_premium;
            case "hours":
                return R.drawable.msg_recent;
            case "location":
                return R.drawable.msg_location;
            default:
                return R.drawable.msg_edit;
        }
    }

    private static final int[] TILE_NAMES = {R.string.SG_TilePhone, R.string.SG_TileBio, R.string.SG_TileLinks, R.string.SG_TileMusic,
        R.string.SG_TilePosts, R.string.SG_TileStreak, R.string.SG_TileUsername, R.string.SG_TileId, R.string.SG_TileBirthday, R.string.SG_TileHours, R.string.SG_TileLocation, R.string.SG_TileNote};

    private String tileTitle(String t) {
        if (CHANNEL.equals(t)) {
            return LocaleController.getString(R.string.SG_TileChannel);
        }
        final int i = Arrays.asList(SolegramProfileCard.TILES).indexOf(t);
        return i >= 0 ? LocaleController.getString(TILE_NAMES[i]) : t;
    }

    /** What the tile shows in the own profile now (or a word on what it would show). */
    private String tileValue(String t) {
        final TLRPC.User user = getUserConfig().getCurrentUser();
        final TLRPC.UserFull full = ownFull();
        switch (t) {
            case CHANNEL: {
                final TLRPC.Chat c = ownChannel();
                return c != null ? c.title : LocaleController.getString(R.string.SG_CardNoChannel);
            }
            case "phone":
                return user != null && !TextUtils.isEmpty(user.phone) ? PhoneFormat.getInstance().format("+" + user.phone) : "—";
            case "bio": {
                final String about = full != null ? com.solegram.messenger.SolegramNameEmojiShare.strip(full.about) : null;
                return TextUtils.isEmpty(about) ? "—" : about.replace('\n', ' ');
            }
            case "username": {
                final String u = UserObject.getPublicUsername(user);
                return u != null ? "@" + u : "—";
            }
            case "id":
                return user != null ? String.valueOf(user.id) : "";
            case "posts":
                return card.posts.isEmpty() ? LocaleController.getString(R.string.SG_CardPostsPick)
                    : LocaleController.formatString(R.string.SG_CardPostsChosen, card.posts.size(), SolegramProfileCard.MAX_POSTS);
            case "streak": {
                if (card.streakDays <= 0) {
                    return LocaleController.getString(R.string.SG_CardStreakPick);
                }
                final String d = LocaleController.formatPluralString("Days", card.streakDays);
                final TLRPC.User partner = card.streakPartner != 0 ? getMessagesController().getUser(card.streakPartner) : null;
                return partner != null ? d + " · " + UserObject.getFirstName(partner) : d;
            }
            case "birthday":
                return full != null && full.birthday != null ? full.birthday.day + "." + full.birthday.month + (full.birthday.year != 0 ? "." + full.birthday.year : "") : "—";
            default:
                return LocaleController.getString(R.string.SG_CardWhenSet);
        }
    }

    /** One block of the profile as the profile shows it, with a minus to take it out. Long press moves it. */
    private class TileView extends FrameLayout {
        final LinearLayout texts;
        final TextView title, value;
        final FrameLayout custom;
        final ImageView minus;
        String tile;

        TileView(Context context) {
            super(context);
            final GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(16));
            bg.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
            setBackground(bg);
            texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(21), dp(11), dp(48), dp(11));
            value = new TextView(context);
            value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            value.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            value.setMaxLines(3);
            value.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(value);
            title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            title.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            texts.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            custom = new FrameLayout(context);
            addView(custom, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            minus = new ImageView(context);
            minus.setScaleType(ImageView.ScaleType.CENTER);
            minus.setImageDrawable(new MinusDrawable());
            minus.setContentDescription(LocaleController.getString(R.string.SG_CardHide));
            minus.setOnClickListener(v -> {
                SolegramIconMotion.play(minus, SolegramIconMotion.POP);
                removeTile(tile);
            });
            addView(minus, LayoutHelper.createFrame(40, 40, Gravity.RIGHT | Gravity.TOP, 0, 4, 4, 0));
            setOnClickListener(v -> {
                if ("posts".equals(tile)) {
                    pickPosts();
                } else if ("streak".equals(tile)) {
                    pickStreak();
                } else if (CHANNEL.equals(tile) && ownChannel() == null) {
                    presentFragment(new org.telegram.ui.UserInfoActivity());
                } else {
                    SolegramIconMotion.play(this, SolegramIconMotion.NUDGE_UP);
                }
            });
        }
    }

    /** A red round minus, as on the home screen in edit mode. */
    private static class MinusDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        public void draw(@NonNull Canvas canvas) {
            final float cx = getBounds().exactCenterX(), cy = getBounds().exactCenterY();
            paint.setColor(0xFFFF3B30);
            canvas.drawCircle(cx, cy, dp(11), paint);
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(cx - dp(5.5f), cy - dp(1.2f), cx + dp(5.5f), cy + dp(1.2f), dp(1.2f), dp(1.2f), paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return dp(24);
        }

        @Override
        public int getIntrinsicHeight() {
            return dp(24);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    /** Posts picked for the profile, by id, as loaded (the published card's and those seen in the picker). */
    private final android.util.SparseArray<MessageObject> knownPosts = new android.util.SparseArray<>();

    private ArrayList<MessageObject> chosenPosts() {
        if (knownPosts.size() == 0) {
            for (MessageObject m : card.featured) {
                knownPosts.put(m.getId(), m);
            }
        }
        final ArrayList<MessageObject> out = new ArrayList<>();
        for (int id : card.posts) {
            final MessageObject m = knownPosts.get(id);
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }

    private void bindTile(TileView v) {
        final String t = v.tile;
        v.custom.removeAllViews();
        v.texts.setVisibility(View.VISIBLE);
        if ("posts".equals(t) && !chosenPosts().isEmpty()) {
            final SolegramProfilePostsView posts = new SolegramProfilePostsView(v.getContext(), getResourceProvider());
            posts.setPosts(chosenPosts());
            posts.setDelegate(p -> pickPosts());
            v.custom.addView(posts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            v.texts.setVisibility(View.INVISIBLE);
        } else if ("streak".equals(t) && card.streakDays > 0) {
            final SolegramProfileStreakView streak = new SolegramProfileStreakView(v.getContext(), getResourceProvider());
            streak.set(currentAccount, card);
            streak.setClickable(false);
            v.custom.addView(streak, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            v.texts.setVisibility(View.INVISIBLE);
        }
        final String val = tileValue(t);
        final boolean empty = "—".equals(val) || LocaleController.getString(R.string.SG_CardWhenSet).equals(val);
        v.value.setText(val);
        v.value.setTextColor(getThemedColor(empty || "posts".equals(t) || "streak".equals(t) && card.streakDays <= 0
            ? Theme.key_windowBackgroundWhiteGrayText : Theme.key_windowBackgroundWhiteBlackText));
        v.title.setText(tileTitle(t));
        v.minus.bringToFront();
    }

    private class TilesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return position == 0 ? 0 : position <= rows.size() ? 1 : 2;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final Context context = parent.getContext();
            if (viewType == 0) {
                final LinearLayout header = new LinearLayout(context);
                header.setOrientation(LinearLayout.VERTICAL);
                banner = new BannerView(context);
                header.addView(banner, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 196));
                bannerHint = new TextView(context);
                bannerHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                bannerHint.setGravity(Gravity.CENTER);
                bannerHint.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                header.addView(bannerHint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 4));
                final TextView tilesHint = new TextView(context);
                tilesHint.setText(LocaleController.getString(R.string.SG_CardDragHint));
                tilesHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                tilesHint.setGravity(Gravity.CENTER);
                tilesHint.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                header.addView(tilesHint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 2, 24, 8));
                header.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                updateBanner();
                return new RecyclerView.ViewHolder(header) {
                };
            }
            if (viewType == 2) {
                final LinearLayout tray = new LinearLayout(context);
                tray.setOrientation(LinearLayout.VERTICAL);
                tray.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(tray) {
                };
            }
            final TileView view = new TileView(context);
            final RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(12), dp(3), dp(12), dp(3));
            view.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(view) {
            };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final int type = getItemViewType(position);
            if (type == 0) {
                updateBanner();
                return;
            }
            if (type == 2) {
                bindTray((LinearLayout) holder.itemView);
                return;
            }
            final TileView v = (TileView) holder.itemView;
            v.tile = rows.get(position - 1);
            bindTile(v);
        }

        @Override
        public int getItemCount() {
            return rows.size() + 2;
        }
    }

    /** Under the profile: the blocks it does not show, a tap puts one back. */
    private void bindTray(LinearLayout tray) {
        tray.removeAllViews();
        final Context context = tray.getContext();
        final ArrayList<String> hidden = hiddenTiles();
        final TextView head = new TextView(context);
        head.setText(LocaleController.getString(hidden.isEmpty() ? R.string.SG_CardTrayEmpty : R.string.SG_CardTray));
        head.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        head.setTypeface(AndroidUtilities.bold());
        head.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        tray.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 18, 20, 8));
        final android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(14), 0, dp(14), 0);
        final LinearLayout chips = new LinearLayout(context);
        scroll.addView(chips);
        final int text = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        final int accent = getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader);
        for (String t : hidden) {
            final LinearLayout chip = new LinearLayout(context);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(10), 0, dp(14), 0);
            chip.setBackground(Theme.createRoundRectDrawable(dp(19), getThemedColor(Theme.key_windowBackgroundWhite)));
            final ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            final Drawable line = SolegramIcons.lineIcon(context, tileIcon(t), accent);
            if (line != null) {
                icon.setImageDrawable(line);
            } else {
                icon.setImageResource(tileIcon(t));
                icon.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
            }
            chip.addView(icon, LayoutHelper.createLinear(24, 24, 0, 0, 6, 0));
            final TextView name = new TextView(context);
            name.setText("+ " + tileTitle(t));
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            name.setTextColor(text);
            chip.addView(name);
            chip.setOnClickListener(v -> addTile(t));
            SolegramMotion.pressable(chip, SolegramMotion.PRESS_BUTTON);
            chips.addView(chip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 38, 3, 0, 3, 0));
        }
        if (!hidden.isEmpty()) {
            tray.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
    }

    // endregion

    // region Banner picker

    /** The personal channel's posts with a picture or GIF, as a grid of their pictures. */
    private void pickBanner() {
        final TLRPC.Chat channel = ownChannel();
        if (getParentActivity() == null) {
            return;
        }
        if (channel == null) {
            presentFragment(new org.telegram.ui.UserInfoActivity());
            return;
        }
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(200);
        SolegramProfileCard.loadOwnPosts(currentAccount, channel, posts -> {
            try {
                progress.dismiss();
            } catch (Exception ignore) {
            }
            if (getParentActivity() == null) {
                return;
            }
            if (posts.isEmpty()) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.SG_CardNoPosts), 5).show();
                return;
            }
            showPicker(posts);
        });
    }

    private void showPicker(List<MessageObject> posts) {
        final Context context = getParentActivity();
        final BottomSheet sheet = new BottomSheet(context, false, getResourceProvider());
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.SG_CardChoosePost));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 16, 20, 10));
        final RecyclerView grid = new RecyclerView(context);
        grid.setLayoutManager(new GridLayoutManager(context, 3));
        grid.setPadding(dp(10), 0, dp(10), dp(12));
        grid.setClipToPadding(false);
        grid.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                final FrameLayout cell = new FrameLayout(context) {
                    @Override
                    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                        super.onMeasure(widthMeasureSpec, widthMeasureSpec);
                    }
                };
                cell.setPadding(dp(2), dp(2), dp(2), dp(2));
                final BackupImageView image = new BackupImageView(context);
                // inside the ring of the chosen one, with the same rounding as the ring
                image.setRoundRadius(dp(11));
                image.getImageReceiver().setAllowStartAnimation(true);
                image.getImageReceiver().setAutoRepeat(1);
                cell.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 4, 4, 4, 4));
                final TextView badge = new TextView(context);
                badge.setText("GIF");
                badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                badge.setTypeface(AndroidUtilities.bold());
                badge.setTextColor(Color.WHITE);
                badge.setPadding(dp(6), dp(1), dp(6), dp(1));
                badge.setBackground(Theme.createRoundRectDrawable(dp(8), 0x88000000));
                cell.addView(badge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 10, 10, 0, 0));
                final View check = new View(context);
                cell.addView(check, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                SolegramMotion.pressable(cell, SolegramMotion.PRESS_CARD);
                return new RecyclerView.ViewHolder(cell) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                final FrameLayout cell = (FrameLayout) holder.itemView;
                final BackupImageView image = (BackupImageView) cell.getChildAt(0);
                final View badge = cell.getChildAt(1);
                final View check = cell.getChildAt(2);
                final MessageObject p = posts.get(position);
                final TLRPC.Document doc = p.getDocument();
                if (doc != null && !(p.messageOwner.media instanceof TLRPC.TL_messageMediaPhoto)) {
                    final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 320);
                    image.getImageReceiver().setImage(ImageLocation.getForDocument(doc), ImageLoader.AUTOPLAY_FILTER, null, null,
                        ImageLocation.getForDocument(thumb, doc), "200_200_b", null, doc.size, null, p, 0);
                    badge.setVisibility(View.VISIBLE);
                } else {
                    final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(p.photoThumbs, 320);
                    final TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(p.photoThumbs, 50);
                    image.getImageReceiver().setImage(ImageLocation.getForObject(size, p.photoThumbsObject), "200_200",
                        ImageLocation.getForObject(small, p.photoThumbsObject), "200_200_b", 0, null, p, 1);
                    badge.setVisibility(View.GONE);
                }
                final boolean chosen = post != null && post.getId() == p.getId();
                final GradientDrawable ring = new GradientDrawable();
                ring.setCornerRadius(dp(14));
                ring.setStroke(chosen ? (int) AndroidUtilities.dpf2(2.5f) : 0, getThemedColor(Theme.key_featuredStickers_addButton));
                ring.setColor(0);
                check.setBackground(ring);
                cell.setOnClickListener(v -> {
                    post = p;
                    updateBanner();
                    if (banner != null) {
                        SolegramIconMotion.play(banner, SolegramIconMotion.POP);
                    }
                    sheet.dismiss();
                });
            }

            @Override
            public int getItemCount() {
                return posts.size();
            }
        });
        content.addView(grid, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, Math.min(3, (posts.size() + 2) / 3) * 120 + 16));
        sheet.setCustomView(content);
        showDialog(sheet);
    }

    // endregion

    // region Posts and the streak

    /** Posts of the personal channel to show in the profile: tap to pick or drop, in the order picked. */
    private void pickPosts() {
        final TLRPC.Chat channel = ownChannel();
        if (getParentActivity() == null) {
            return;
        }
        if (channel == null) {
            presentFragment(new org.telegram.ui.UserInfoActivity());
            return;
        }
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(200);
        SolegramProfileCard.loadOwnPosts(currentAccount, channel, false, posts -> {
            try {
                progress.dismiss();
            } catch (Exception ignore) {
            }
            if (getParentActivity() == null) {
                return;
            }
            if (posts.isEmpty()) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.SG_CardNoPosts), 5).show();
                return;
            }
            for (MessageObject m : posts) {
                knownPosts.put(m.getId(), m);
            }
            showPostsPicker(posts);
        });
    }

    private void showPostsPicker(List<MessageObject> posts) {
        final Context context = getParentActivity();
        final BottomSheet sheet = new BottomSheet(context, false, getResourceProvider());
        final ArrayList<Integer> chosen = new ArrayList<>(card.posts);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        final TextView title = new TextView(context);
        title.setText(LocaleController.formatString(R.string.SG_CardPostsTitle, SolegramProfileCard.MAX_POSTS));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 16, 20, 10));
        final RecyclerView listView = new RecyclerView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        final RecyclerView.Adapter<RecyclerView.ViewHolder> postsAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                final LinearLayout cell = new LinearLayout(context);
                cell.setOrientation(LinearLayout.HORIZONTAL);
                cell.setGravity(Gravity.CENTER_VERTICAL);
                cell.setPadding(dp(16), dp(6), dp(16), dp(6));
                final BackupImageView image = new BackupImageView(context);
                image.setRoundRadius(dp(10));
                cell.addView(image, LayoutHelper.createLinear(48, 48, 0, 0, 12, 0));
                final TextView text = new TextView(context);
                text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                text.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
                text.setMaxLines(2);
                text.setEllipsize(TextUtils.TruncateAt.END);
                cell.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
                final TextView mark = new TextView(context);
                mark.setGravity(Gravity.CENTER);
                mark.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                mark.setTypeface(AndroidUtilities.bold());
                cell.addView(mark, LayoutHelper.createLinear(26, 26, 12, 0, 0, 0));
                cell.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
                cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerView.ViewHolder(cell) {
                };
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                final LinearLayout cell = (LinearLayout) holder.itemView;
                final BackupImageView image = (BackupImageView) cell.getChildAt(0);
                final TextView text = (TextView) cell.getChildAt(1);
                final TextView mark = (TextView) cell.getChildAt(2);
                final MessageObject p = posts.get(position);
                final TLRPC.PhotoSize thumb = p.photoThumbs != null ? FileLoader.getClosestPhotoSizeWithSize(p.photoThumbs, 160) : null;
                if (thumb != null) {
                    image.setVisibility(View.VISIBLE);
                    image.setImage(ImageLocation.getForObject(thumb, p.photoThumbsObject), "96_96", (ImageLocation) null, null, 0, p);
                } else {
                    image.setVisibility(View.GONE);
                }
                final String caption = SolegramProfileCard.strip(p.messageOwner.message).trim();
                text.setText(org.telegram.messenger.Emoji.replaceEmoji(TextUtils.isEmpty(caption) ? LocaleController.getString(R.string.AttachPhoto) : caption.replace('\n', ' '),
                    text.getPaint().getFontMetricsInt(), false));
                final int at = chosen.indexOf(p.getId());
                final int accent = getThemedColor(Theme.key_featuredStickers_addButton);
                if (at >= 0) {
                    mark.setText(String.valueOf(at + 1));
                    mark.setTextColor(Color.WHITE);
                    mark.setBackground(Theme.createCircleDrawable(dp(26), accent));
                } else {
                    mark.setText("");
                    final GradientDrawable ring = new GradientDrawable();
                    ring.setShape(GradientDrawable.OVAL);
                    ring.setStroke(dp(2), getThemedColor(Theme.key_checkboxDisabled));
                    mark.setBackground(ring);
                }
                cell.setOnClickListener(v -> {
                    final int i = chosen.indexOf(p.getId());
                    if (i >= 0) {
                        chosen.remove(i);
                    } else if (chosen.size() < SolegramProfileCard.MAX_POSTS) {
                        chosen.add(p.getId());
                    } else {
                        SolegramMotion.haptic(v);
                        return;
                    }
                    SolegramIconMotion.play(mark, SolegramIconMotion.POP);
                    notifyDataSetChanged();
                });
            }

            @Override
            public int getItemCount() {
                return posts.size();
            }
        };
        listView.setAdapter(postsAdapter);
        content.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, Math.min(posts.size(), 6) * 60 + 8));
        final TextView done = new TextView(context);
        done.setText(LocaleController.getString(R.string.Done));
        done.setGravity(Gravity.CENTER);
        done.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        done.setTypeface(AndroidUtilities.bold());
        done.setTextColor(getThemedColor(Theme.key_featuredStickers_buttonText));
        done.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14), getThemedColor(Theme.key_featuredStickers_addButton),
            getThemedColor(Theme.key_featuredStickers_addButtonPressed)));
        done.setOnClickListener(v -> {
            card.posts.clear();
            card.posts.addAll(chosen);
            if (!rows.contains("posts")) {
                addTile("posts");
            }
            adapter.notifyDataSetChanged();
            sheet.dismiss();
        });
        content.addView(done, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 8, 16, 12));
        sheet.setCustomView(content);
        showDialog(sheet);
    }

    /** The chat whose streak goes to the profile (picked in this editor, kept on save). */
    private long streakDialog = -1;

    /** A streak to pin to the profile: one of the current streaks, with or without the partner's name. */
    private void pickStreak() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final ArrayList<long[]> streaks = SolegramStreak.active(currentAccount);
        final BottomSheet sheet = new BottomSheet(context, false, getResourceProvider());
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(12));
        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.SG_CardStreakTitle));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 16, 20, 4));
        final TextView info = new TextView(context);
        info.setText(LocaleController.getString(streaks.isEmpty() ? R.string.SG_CardStreakNone : R.string.SG_CardStreakInfo));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(getThemedColor(Theme.key_dialogTextGray3));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 10));

        final org.telegram.ui.Cells.TextCheckCell partner = new org.telegram.ui.Cells.TextCheckCell(context, getResourceProvider());
        final boolean[] showPartner = {card.streakDays <= 0 || card.streakPartner != 0};
        partner.setTextAndCheck(LocaleController.getString(R.string.SG_CardStreakShowPartner), showPartner[0], true);
        partner.setBackground(Theme.getSelectorDrawable(false));
        partner.setOnClickListener(v -> {
            showPartner[0] = !showPartner[0];
            partner.setChecked(showPartner[0]);
        });

        for (long[] st : streaks) {
            final long did = st[0];
            final int days = (int) st[1];
            final TLRPC.User u = getMessagesController().getUser(did);
            if (u == null) {
                continue;
            }
            final LinearLayout cell = new LinearLayout(context);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setPadding(dp(16), dp(8), dp(16), dp(8));
            final BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(20));
            avatar.setForUserOrChat(u, new AvatarDrawable(u));
            cell.addView(avatar, LayoutHelper.createLinear(40, 40, 0, 0, 14, 0));
            final TextView name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            name.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setText(SolegramStreak.append(UserObject.getUserName(u), new SolegramStreak.Span(days, false, SolegramStreak.colorOf(currentAccount, did), SolegramStreak.STYLE_FULL)));
            cell.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            cell.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            cell.setOnClickListener(v -> {
                card.streakDays = days;
                card.streakColor = SolegramStreak.colorOf(currentAccount, did);
                card.streakPartner = showPartner[0] ? did : 0;
                card.streakDay = SolegramStreak.today();
                streakDialog = did;
                if (!rows.contains("streak")) {
                    addTile("streak");
                }
                adapter.notifyDataSetChanged();
                sheet.dismiss();
            });
            content.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        if (!streaks.isEmpty()) {
            content.addView(partner, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        }
        if (card.streakDays > 0) {
            final TextView unpin = new TextView(context);
            unpin.setText(LocaleController.getString(R.string.SG_CardStreakUnpin));
            unpin.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            unpin.setTextColor(getThemedColor(Theme.key_text_RedRegular));
            unpin.setPadding(dp(20), dp(14), dp(20), dp(14));
            unpin.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            unpin.setOnClickListener(v -> {
                card.streakDays = 0;
                card.streakPartner = 0;
                streakDialog = 0;
                adapter.notifyDataSetChanged();
                sheet.dismiss();
            });
            content.addView(unpin, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        final android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(content);
        sheet.setCustomView(scroll);
        showDialog(sheet);
    }

    // endregion

    private void askRemove() {
        final TLRPC.Chat channel = ownChannel();
        final SolegramProfileCard.Card published = SolegramProfileCard.get(getUserConfig().getClientUserId());
        if (channel == null || published == null || published.post == null || getParentActivity() == null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.SG_CardNothingToRemove)).show();
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.SG_CardRemove));
        builder.setMessage(LocaleController.getString(R.string.SG_CardRemoveInfo));
        builder.setPositiveButton(LocaleController.getString(R.string.SG_CardRemoveShort), (d, w) -> SolegramProfileCard.remove(currentAccount, channel, published.post, error -> {
            card = new SolegramProfileCard.Card();
            post = null;
            knownPosts.clear();
            rebuildRows();
            bind();
            BulletinFactory.of(this).createSimpleBulletin(error == null ? R.raw.done : R.raw.error,
                error == null ? LocaleController.getString(R.string.SG_CardRemoved) : LocaleController.formatString(R.string.SG_CardPublishFailed, error)).show();
        }));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void publish() {
        final TLRPC.Chat channel = ownChannel();
        if (busy) {
            return;
        }
        if (channel == null) {
            presentFragment(new org.telegram.ui.UserInfoActivity());
            return;
        }
        if (post == null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.SG_CardPickFirst)).show();
            pickBanner();
            return;
        }
        applyRows();
        busy = true;
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(200);
        SolegramProfileCard.publish(currentAccount, channel, post, card, error -> {
            busy = false;
            try {
                progress.dismiss();
            } catch (Exception ignore) {
            }
            if (error == null) {
                if (streakDialog >= 0) {
                    SolegramProfileCard.setPinnedStreakDialog(currentAccount, card.streakDays > 0 ? streakDialog : 0);
                }
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.SG_CardPublished)).show();
            } else {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.SG_CardPublishFailed, error)).show();
            }
        });
    }
}
