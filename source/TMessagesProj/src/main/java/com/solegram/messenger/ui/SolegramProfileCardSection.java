/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.solegram.messenger.SolegramProfileCard;
import com.solegram.messenger.SolegramStreak;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Solegram's profile card, edited right on Telegram's profile customization screen: the banner (a post of the
 * personal channel, darkened under the name), the profile's blocks (shown or hidden, in order), the posts and the
 * streak pinned to the profile. Changes are saved on their own a moment after the last one (and when the screen
 * closes): the card goes onto the banner post, so it needs one.
 */
public class SolegramProfileCardSection {

    public static final String CHANNEL = "channel";

    private static final int[] TILE_NAMES = {R.string.SG_TilePhone, R.string.SG_TileBio, R.string.SG_TileLinks, R.string.SG_TileMusic,
        R.string.SG_TilePosts, R.string.SG_TileStreak, R.string.SG_TileUsername, R.string.SG_TileId, R.string.SG_TileBirthday, R.string.SG_TileHours, R.string.SG_TileLocation, R.string.SG_TileNote};

    private final BaseFragment fragment;
    private final int account;
    private final Runnable onChanged;

    private SolegramProfileCard.Card card = new SolegramProfileCard.Card();
    @Nullable
    private MessageObject post;
    /** Every block as the list shows it: the shown ones in the profile's order (the channel first or last), then the hidden ones. */
    private final ArrayList<String> rows = new ArrayList<>();
    private final android.util.SparseArray<MessageObject> knownPosts = new android.util.SparseArray<>();
    private long streakDialog = -1;
    private boolean loaded, dirty, publishing;
    private final Runnable publishSoon = this::publishNow;

    public SolegramProfileCardSection(BaseFragment fragment, int account, Runnable onChanged) {
        this.fragment = fragment;
        this.account = account;
        this.onChanged = onChanged;
        rebuild();
        load();
    }

    // region state

    private long self() {
        return UserConfig.getInstance(account).getClientUserId();
    }

    private MessagesController mc() {
        return MessagesController.getInstance(account);
    }

    @Nullable
    private TLRPC.UserFull ownFull() {
        return mc().getUserFull(self());
    }

    @Nullable
    private TLRPC.Chat ownChannel() {
        final TLRPC.UserFull full = ownFull();
        if (full == null) {
            mc().loadFullUser(UserConfig.getInstance(account).getCurrentUser(), 0, true);
            return null;
        }
        return full.personal_channel_id == 0 ? null : mc().getChat(full.personal_channel_id);
    }

    private void load() {
        final long self = self();
        SolegramProfileCard.forget(self);
        SolegramProfileCard.load(account, self, ownFull(), () -> {
            final SolegramProfileCard.Card published = SolegramProfileCard.get(self);
            loaded = true;
            if (published != null && !dirty) {
                card = published.copy();
                post = published.post;
            }
            rebuild();
            changed();
        });
    }

    private void changed() {
        if (onChanged != null) {
            onChanged.run();
        }
    }

    private void rebuild() {
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
        if (!channelShown) {
            rows.add(CHANNEL);
        }
        for (String t : card.order) {
            if (!card.shown(t)) {
                rows.add(t);
            }
        }
    }

    /** The shown blocks back into the card, in the list's order (the channel snaps to the top or the bottom). */
    private void applyRows() {
        final ArrayList<String> shown = new ArrayList<>();
        for (String r : rows) {
            if (isShown(r)) {
                shown.add(r);
            }
        }
        final ArrayList<String> hidden = new ArrayList<>();
        for (String t : card.order) {
            if (!card.shown(t)) {
                hidden.add(t);
            }
        }
        card.order.clear();
        for (String r : shown) {
            if (!CHANNEL.equals(r)) {
                card.order.add(r);
            }
        }
        card.order.addAll(hidden);
        if (!SolegramProfileCard.CHANNEL_HIDDEN.equals(card.channel)) {
            final int at = shown.indexOf(CHANNEL);
            card.channel = at <= (shown.size() - 1) / 2f ? SolegramProfileCard.CHANNEL_TOP : SolegramProfileCard.CHANNEL_BOTTOM;
        }
    }

    public List<String> rows() {
        return rows;
    }

    public boolean isShown(String t) {
        if (CHANNEL.equals(t)) {
            return !SolegramProfileCard.CHANNEL_HIDDEN.equals(card.channel);
        }
        return card.shown(t);
    }

    /** How many blocks are shown (they come first in {@link #rows()}): only these move. */
    public int shownCount() {
        int n = 0;
        for (String r : rows) {
            if (isShown(r)) {
                n++;
            }
        }
        return n;
    }

    public void toggle(String t) {
        if (CHANNEL.equals(t)) {
            card.channel = isShown(t) ? SolegramProfileCard.CHANNEL_HIDDEN : SolegramProfileCard.CHANNEL_TOP;
        } else if (card.shown(t)) {
            card.hidden.add(t);
        } else {
            card.hidden.remove(t);
        }
        applyRows();
        rebuild();
        edited();
    }

    /** A shown block moved within the shown ones (the list already moved it). */
    public void moved(int from, int to) {
        if (from < 0 || to < 0 || from >= rows.size() || to >= rows.size()) {
            return;
        }
        final String t = rows.remove(from);
        rows.add(to, t);
    }

    /** The drag ended: the order goes into the card. */
    public void moveDone() {
        applyRows();
        rebuild();
        edited();
    }

    public boolean hasBanner() {
        return post != null;
    }

    public int dim() {
        return card.dim;
    }

    public void setDim(int value) {
        card.dim = Math.max(0, Math.min(80, value));
        edited();
    }

    @Nullable
    public MessageObject bannerPost() {
        return post;
    }

    private void edited() {
        dirty = true;
        changed();
        AndroidUtilities.cancelRunOnUIThread(publishSoon);
        AndroidUtilities.runOnUIThread(publishSoon, 1500);
    }

    // endregion

    // region what the rows say

    public String tileTitle(String t) {
        if (CHANNEL.equals(t)) {
            return LocaleController.getString(R.string.SG_TileChannel);
        }
        final int i = Arrays.asList(SolegramProfileCard.TILES).indexOf(t);
        return i >= 0 ? LocaleController.getString(TILE_NAMES[i]) : t;
    }

    public String tileValue(String t) {
        final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
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
                return postsValue();
            case "streak":
                return streakValue();
            case "birthday":
                return full != null && full.birthday != null ? full.birthday.day + "." + full.birthday.month + (full.birthday.year != 0 ? "." + full.birthday.year : "") : "—";
            default:
                return LocaleController.getString(R.string.SG_CardWhenSet);
        }
    }

    public String postsValue() {
        return card.posts.isEmpty() ? LocaleController.getString(R.string.SG_CardPostsPick)
            : LocaleController.formatString(R.string.SG_CardPostsChosen, card.posts.size(), SolegramProfileCard.MAX_POSTS);
    }

    public String streakValue() {
        if (card.streakDays <= 0) {
            return LocaleController.getString(R.string.SG_CardStreakPick);
        }
        final String d = LocaleController.formatPluralString("Days", card.streakDays);
        final TLRPC.User partner = card.streakPartner != 0 ? mc().getUser(card.streakPartner) : null;
        return partner != null ? d + " · " + UserObject.getFirstName(partner) : d;
    }

    public String bannerValue() {
        return post != null ? LocaleController.getString(R.string.SG_CardTapToChangeShort) : LocaleController.getString(R.string.SG_CardNoPost);
    }

    // endregion

    // region pickers

    private int color(int key) {
        return Theme.getColor(key, fragment.getResourceProvider());
    }

    /** No personal channel: say what is needed and where to add it. */
    private boolean needChannel() {
        if (ownChannel() != null) {
            return false;
        }
        if (fragment.getParentActivity() == null) {
            return true;
        }
        final AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.SG_CardSetChannel));
        b.setMessage(LocaleController.getString(R.string.SG_CardNeedChannel));
        b.setPositiveButton(LocaleController.getString(R.string.SG_CardSetChannel), (d, w) -> fragment.presentFragment(new org.telegram.ui.UserInfoActivity()));
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(b.create());
        return true;
    }

    public void pickBanner() {
        if (needChannel() || fragment.getParentActivity() == null) {
            return;
        }
        final AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(200);
        SolegramProfileCard.loadOwnPosts(account, ownChannel(), posts -> {
            try {
                progress.dismiss();
            } catch (Exception ignore) {
            }
            if (fragment.getParentActivity() == null) {
                return;
            }
            if (posts.isEmpty()) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.SG_CardNoPosts), 5).show();
                return;
            }
            showBannerPicker(posts);
        });
    }

    public void removeBanner() {
        final TLRPC.Chat channel = ownChannel();
        final SolegramProfileCard.Card published = SolegramProfileCard.get(self());
        if (channel == null || published == null || published.post == null) {
            post = null;
            changed();
            return;
        }
        SolegramProfileCard.remove(account, channel, published.post, error -> {
            card = new SolegramProfileCard.Card();
            post = null;
            dirty = false;
            knownPosts.clear();
            rebuild();
            changed();
            BulletinFactory.of(fragment).createSimpleBulletin(error == null ? R.raw.done : R.raw.error,
                error == null ? LocaleController.getString(R.string.SG_CardRemoved) : LocaleController.formatString(R.string.SG_CardPublishFailed, error)).show();
        });
    }

    private void showBannerPicker(List<MessageObject> posts) {
        final Context context = fragment.getParentActivity();
        final BottomSheet sheet = new BottomSheet(context, false, fragment.getResourceProvider());
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(title(context, LocaleController.getString(R.string.SG_CardChoosePost)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 16, 20, 10));
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
                image.setRoundRadius(dp(11));
                image.getImageReceiver().setAllowStartAnimation(true);
                image.getImageReceiver().setAutoRepeat(1);
                cell.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 4, 4, 4, 4));
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
                final View check = cell.getChildAt(1);
                final MessageObject p = posts.get(position);
                final TLRPC.Document doc = p.getDocument();
                if (doc != null && !(p.messageOwner.media instanceof TLRPC.TL_messageMediaPhoto)) {
                    final TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 320);
                    image.getImageReceiver().setImage(ImageLocation.getForDocument(doc), ImageLoader.AUTOPLAY_FILTER, null, null,
                        ImageLocation.getForDocument(thumb, doc), "200_200_b", null, doc.size, null, p, 0);
                } else {
                    final TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(p.photoThumbs, 320);
                    final TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(p.photoThumbs, 50);
                    image.getImageReceiver().setImage(ImageLocation.getForObject(size, p.photoThumbsObject), "200_200",
                        ImageLocation.getForObject(small, p.photoThumbsObject), "200_200_b", 0, null, p, 1);
                }
                final boolean chosen = post != null && post.getId() == p.getId();
                final GradientDrawable ring = new GradientDrawable();
                ring.setCornerRadius(dp(14));
                ring.setStroke(chosen ? (int) AndroidUtilities.dpf2(2.5f) : 0, color(Theme.key_featuredStickers_addButton));
                ring.setColor(0);
                check.setBackground(ring);
                cell.setOnClickListener(v -> {
                    post = p;
                    sheet.dismiss();
                    edited();
                });
            }

            @Override
            public int getItemCount() {
                return posts.size();
            }
        });
        content.addView(grid, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, Math.min(3, (posts.size() + 2) / 3) * 120 + 16));
        sheet.setCustomView(content);
        fragment.showDialog(sheet);
    }

    private TextView title(Context context, CharSequence text) {
        final TextView title = new TextView(context);
        title.setText(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(color(Theme.key_dialogTextBlack));
        return title;
    }

    public void pickPosts() {
        if (needChannel() || fragment.getParentActivity() == null) {
            return;
        }
        final AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(200);
        SolegramProfileCard.loadOwnPosts(account, ownChannel(), false, posts -> {
            try {
                progress.dismiss();
            } catch (Exception ignore) {
            }
            if (fragment.getParentActivity() == null) {
                return;
            }
            if (posts.isEmpty()) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.SG_CardNoPosts), 5).show();
                return;
            }
            for (MessageObject m : posts) {
                knownPosts.put(m.getId(), m);
            }
            showPostsPicker(posts);
        });
    }

    private void showPostsPicker(List<MessageObject> posts) {
        final Context context = fragment.getParentActivity();
        final BottomSheet sheet = new BottomSheet(context, false, fragment.getResourceProvider());
        final ArrayList<Integer> chosen = new ArrayList<>(card.posts);
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(title(context, LocaleController.formatString(R.string.SG_CardPostsTitle, SolegramProfileCard.MAX_POSTS)),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 16, 20, 10));
        final RecyclerView list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
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
                text.setTextColor(color(Theme.key_dialogTextBlack));
                text.setMaxLines(2);
                text.setEllipsize(TextUtils.TruncateAt.END);
                cell.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
                final TextView mark = new TextView(context);
                mark.setGravity(Gravity.CENTER);
                mark.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                mark.setTypeface(AndroidUtilities.bold());
                cell.addView(mark, LayoutHelper.createLinear(26, 26, 12, 0, 0, 0));
                cell.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
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
                final int accent = color(Theme.key_featuredStickers_addButton);
                if (at >= 0) {
                    mark.setText(String.valueOf(at + 1));
                    mark.setTextColor(Color.WHITE);
                    mark.setBackground(Theme.createCircleDrawable(dp(26), accent));
                } else {
                    mark.setText("");
                    final GradientDrawable ring = new GradientDrawable();
                    ring.setShape(GradientDrawable.OVAL);
                    ring.setStroke(dp(2), color(Theme.key_checkboxDisabled));
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
                    notifyDataSetChanged();
                });
            }

            @Override
            public int getItemCount() {
                return posts.size();
            }
        });
        content.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, Math.min(posts.size(), 6) * 60 + 8));
        final TextView done = new TextView(context);
        done.setText(LocaleController.getString(R.string.Done));
        done.setGravity(Gravity.CENTER);
        done.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        done.setTypeface(AndroidUtilities.bold());
        done.setTextColor(color(Theme.key_featuredStickers_buttonText));
        done.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(14), color(Theme.key_featuredStickers_addButton), color(Theme.key_featuredStickers_addButtonPressed)));
        done.setOnClickListener(v -> {
            card.posts.clear();
            card.posts.addAll(chosen);
            if (!chosen.isEmpty()) {
                card.hidden.remove("posts");
                rebuild();
            }
            sheet.dismiss();
            edited();
        });
        content.addView(done, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 8, 16, 12));
        sheet.setCustomView(content);
        fragment.showDialog(sheet);
    }

    public void pickStreak() {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final ArrayList<long[]> streaks = SolegramStreak.active(account);
        final BottomSheet sheet = new BottomSheet(context, false, fragment.getResourceProvider());
        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(12));
        content.addView(title(context, LocaleController.getString(R.string.SG_CardStreakTitle)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 16, 20, 4));
        final TextView info = new TextView(context);
        info.setText(LocaleController.getString(streaks.isEmpty() ? R.string.SG_CardStreakNone : R.string.SG_CardStreakInfo));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(color(Theme.key_dialogTextGray3));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 10));

        final org.telegram.ui.Cells.TextCheckCell partner = new org.telegram.ui.Cells.TextCheckCell(context, fragment.getResourceProvider());
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
            final TLRPC.User u = mc().getUser(did);
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
            name.setTextColor(color(Theme.key_dialogTextBlack));
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setText(SolegramStreak.append(UserObject.getUserName(u), new SolegramStreak.Span(days, false, SolegramStreak.colorOf(account, did), SolegramStreak.STYLE_FULL)));
            cell.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            cell.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            cell.setOnClickListener(v -> {
                card.streakDays = days;
                card.streakColor = SolegramStreak.colorOf(account, did);
                card.streakPartner = showPartner[0] ? did : 0;
                card.streakDay = SolegramStreak.today();
                card.hidden.remove("streak");
                streakDialog = did;
                rebuild();
                sheet.dismiss();
                edited();
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
            unpin.setTextColor(color(Theme.key_text_RedRegular));
            unpin.setPadding(dp(20), dp(14), dp(20), dp(14));
            unpin.setBackground(Theme.createSelectorDrawable(color(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
            unpin.setOnClickListener(v -> {
                card.streakDays = 0;
                card.streakPartner = 0;
                streakDialog = 0;
                sheet.dismiss();
                edited();
            });
            content.addView(unpin, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        final android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(content);
        sheet.setCustomView(scroll);
        fragment.showDialog(sheet);
    }

    // endregion

    // region saving

    /** Saves now if something is waiting (the screen is closing). */
    public void flush() {
        AndroidUtilities.cancelRunOnUIThread(publishSoon);
        if (dirty) {
            publishNow();
        }
    }

    private void publishNow() {
        if (!dirty || publishing) {
            return;
        }
        final TLRPC.Chat channel = ownChannel();
        if (channel == null || post == null) {
            // the card lives on the banner post: it is saved once a banner is chosen
            return;
        }
        applyRows();
        publishing = true;
        dirty = false;
        SolegramProfileCard.publish(account, channel, post, card, error -> {
            publishing = false;
            if (error == null) {
                if (streakDialog >= 0) {
                    SolegramProfileCard.setPinnedStreakDialog(account, card.streakDays > 0 ? streakDialog : 0);
                }
            } else {
                dirty = true;
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.error, LocaleController.formatString(R.string.SG_CardPublishFailed, error)).show();
            }
        });
    }

    /** Whether a change waits for a banner to be saved with. */
    public boolean waitsForBanner() {
        return dirty && post == null;
    }

    // endregion
}
