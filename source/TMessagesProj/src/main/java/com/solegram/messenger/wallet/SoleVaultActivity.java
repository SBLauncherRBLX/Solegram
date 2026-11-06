/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.wallet;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.solegram.messenger.ui.SolegramMotion;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BillingController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stars.StarGiftSheet;
import org.telegram.ui.Stars.StarsIntroActivity;

import java.util.ArrayList;

/**
 * The collection: what the collectible gifts are worth (Telegram's estimate, with the change over a week), and the
 * vault — the user's own private channel where gifts can be put away out of the profile and taken back.
 */
public class SoleVaultActivity extends BaseFragment {

    private LinearLayout column;
    private ArrayList<TL_stars.SavedStarGift> inProfile, inVault;
    private boolean loading, vaultOk;
    private boolean busy;

    @Override
    public void onResume() {
        super.onResume();
        // (not while Solegram Studio records the scenes the person started: the video would be black)
        if (getParentActivity() != null && !com.solegram.messenger.studio.SolegramStudio.isRunning()) {
            getParentActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        // the collection belongs to the wallet: it closes with it
        if (!SoleWallet.isUnlocked(currentAccount)) {
            finishFragment();
            return;
        }
        SoleWallet.touch();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getParentActivity() != null) {
            getParentActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    @Override
    public boolean isLightStatusBar() {
        // the header is in the page's colour: dark status bar icons on a light page
        return org.telegram.messenger.AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_windowBackgroundGray)) > 0.721f;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackgroundColor(android.graphics.Color.TRANSPARENT); // no bar: the page blurs under the header
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_listSelector), false);
        actionBar.setTitleColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setCastShadows(false);
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.SG_VaultTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        final FrameLayout root = new SoleWalletActivity.HeaderBlurRoot(context, getThemedColor(Theme.key_windowBackgroundGray));
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        final ScrollView scroll = new ScrollView(context);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight + dp(8), 0, dp(24));
        scroll.addView(column);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(actionBar);
        fragmentView = root;
        reload();
        return root;
    }

    private int text() {
        return getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
    }

    private int gray() {
        return getThemedColor(Theme.key_windowBackgroundWhiteGrayText);
    }

    private int accent() {
        return getThemedColor(Theme.key_featuredStickers_addButton);
    }

    // ------------------------------------------------------------------ data

    private void reload() {
        loading = true;
        render();
        final long me = getUserConfig().getClientUserId();
        SoleVault.loadGifts(currentAccount, getMessagesController().getInputPeer(me), mine -> {
            inProfile = mine;
            SoleVault.check(currentAccount, ok -> {
                vaultOk = ok;
                if (!ok) {
                    inVault = new ArrayList<>();
                    done();
                    return;
                }
                SoleVault.loadGifts(currentAccount, SoleVault.inputPeer(currentAccount), vault -> {
                    inVault = vault;
                    done();
                });
            });
        });
    }

    private void done() {
        loading = false;
        long cents = 0;
        for (TL_stars.SavedStarGift g : inProfile) {
            cents += SoleVault.usdCents(g);
        }
        for (TL_stars.SavedStarGift g : inVault) {
            cents += SoleVault.usdCents(g);
        }
        // a failed load must not be noted as a collection worth nothing
        if (!inProfile.isEmpty() || !inVault.isEmpty()) {
            SoleVault.noteWorth(currentAccount, cents);
        }
        render();
    }

    // ------------------------------------------------------------------ the screen

    private void render() {
        if (column == null || getParentActivity() == null) {
            return;
        }
        final Context context = getParentActivity();
        column.removeAllViews();
        if (loading && inProfile == null) {
            final TextView t = note(context, getString(R.string.SG_VaultLoading));
            t.setGravity(Gravity.CENTER);
            column.addView(t, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 40, 0, 0));
            return;
        }
        column.addView(worthCard(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 0));
        column.addView(vaultCard(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 12, 12, 0));

        final ArrayList<TL_stars.SavedStarGift> profileCollectibles = collectibles(inProfile);
        column.addView(header(context, getString(R.string.SG_VaultInProfile)));
        column.addView(list(context, profileCollectibles, false, getString(R.string.SG_VaultNoneInProfile)),
            LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 0));
        if (vaultOk) {
            column.addView(header(context, getString(R.string.SG_VaultInVault)));
            column.addView(list(context, collectibles(inVault), true, getString(R.string.SG_VaultNoneInVault)),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 0));
        }
        final TextView foot = note(context, getString(R.string.SG_VaultFootnote));
        column.addView(foot, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 12, 20, 0));
    }

    private static ArrayList<TL_stars.SavedStarGift> collectibles(ArrayList<TL_stars.SavedStarGift> all) {
        final ArrayList<TL_stars.SavedStarGift> out = new ArrayList<>();
        if (all != null) {
            for (TL_stars.SavedStarGift g : all) {
                if (SoleVault.collectible(g)) {
                    out.add(g);
                }
            }
        }
        return out;
    }

    private LinearLayout panel(Context context) {
        final LinearLayout p = new LinearLayout(context);
        p.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        p.setBackground(bg);
        return p;
    }

    private TextView note(Context context, String s) {
        final TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        t.setTextColor(gray());
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private TextView header(Context context, String s) {
        final TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        t.setTypeface(AndroidUtilities.bold());
        t.setTextColor(text());
        t.setPadding(dp(20), dp(22), dp(20), dp(8));
        return t;
    }

    private static String usd(long cents) {
        return BillingController.getInstance().formatCurrency(cents, "USD");
    }

    /** What the collection is worth, the change over a week, and what plain gifts could be exchanged for. */
    private View worthCard(Context context) {
        final LinearLayout p = panel(context);
        p.setPadding(dp(18), dp(16), dp(18), dp(16));
        long cents = 0;
        int count = 0;
        long convertible = 0;
        final ArrayList<TL_stars.SavedStarGift> all = new ArrayList<>(inProfile);
        if (inVault != null) {
            all.addAll(inVault);
        }
        for (TL_stars.SavedStarGift g : all) {
            if (SoleVault.collectible(g)) {
                cents += SoleVault.usdCents(g);
                count++;
            } else if (g.convert_stars > 0 && !g.refunded) {
                convertible += g.convert_stars;
            }
        }
        final TextView label = note(context, getString(R.string.SG_VaultWorth));
        p.addView(label);
        final TextView value = new TextView(context);
        value.setText(cents > 0 ? "≈ " + usd(cents) : "—");
        value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 34);
        value.setTypeface(AndroidUtilities.bold());
        value.setTextColor(text());
        p.addView(value, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        final long weekAgo = SoleVault.worthWeekAgo(currentAccount);
        if (weekAgo >= 0 && cents > 0) {
            final long d = cents - weekAgo;
            final TextView change = new TextView(context);
            change.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            change.setTypeface(AndroidUtilities.bold());
            if (d == 0) {
                change.setText(getString(R.string.SG_VaultNoChange));
                change.setTextColor(gray());
            } else {
                change.setText(formatString(R.string.SG_VaultChange, (d > 0 ? "▲ " : "▼ ") + usd(Math.abs(d))));
                change.setTextColor(d > 0 ? 0xFF35B262 : getThemedColor(Theme.key_text_RedRegular));
            }
            p.addView(change, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }
        final TextView info = note(context, LocaleController.formatPluralString("SG_VaultCollectibles", count));
        p.addView(info, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        if (convertible > 0) {
            final TextView c = note(context, "");
            c.setText(StarsIntroActivity.replaceStars(formatString(R.string.SG_VaultConvertible, LocaleController.formatNumber(convertible, ' ') + " ⭐"), 1.1f));
            p.addView(c, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }
        return p;
    }

    /** The vault: how it works, and making it, or opening it. */
    private View vaultCard(Context context) {
        final LinearLayout p = panel(context);
        p.setPadding(dp(18), dp(16), dp(18), dp(16));
        final TextView title = new TextView(context);
        title.setText(getString(R.string.SG_VaultName));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(text());
        p.addView(title);
        final TextView info = note(context, getString(vaultOk ? R.string.SG_VaultReadyInfo : R.string.SG_VaultCreateInfo));
        p.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        final TextView button = new TextView(context);
        button.setText(getString(vaultOk ? R.string.SG_VaultOpenChannel : R.string.SG_VaultCreate));
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setTextColor(vaultOk ? accent() : 0xFFFFFFFF);
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(vaultOk ? Theme.multAlpha(accent(), 0.12f) : accent());
        button.setBackground(bg);
        button.setOnClickListener(v -> {
            SoleWallet.touch();
            if (vaultOk) {
                final Bundle args = new Bundle();
                args.putLong("chat_id", SoleVault.channelId(currentAccount));
                presentFragment(new ChatActivity(args));
            } else {
                createVault();
            }
        });
        SolegramMotion.pressable(button, SolegramMotion.PRESS_CARD);
        p.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 14, 0, 0));
        return p;
    }

    private void createVault() {
        if (busy || getParentActivity() == null) {
            return;
        }
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(R.string.SG_VaultCreate))
            .setMessage(getString(R.string.SG_VaultCreateConfirm))
            .setPositiveButton(getString(R.string.SG_VaultCreateShort), (d, w) -> {
                busy = true;
                SoleVault.create(currentAccount, err -> {
                    busy = false;
                    if (err == null) {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.SG_VaultCreated)).show();
                        reload();
                    } else {
                        BulletinFactory.of(this).showForError(err);
                    }
                });
            })
            .setNegativeButton(getString(R.string.Cancel), null)
            .create());
    }

    private View list(Context context, ArrayList<TL_stars.SavedStarGift> gifts, boolean vault, String empty) {
        final LinearLayout p = panel(context);
        if (gifts.isEmpty()) {
            final TextView t = note(context, empty);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(16), dp(20), dp(16), dp(20));
            p.addView(t);
            return p;
        }
        for (TL_stars.SavedStarGift g : gifts) {
            p.addView(row(context, g, vault));
        }
        return p;
    }

    private View row(Context context, TL_stars.SavedStarGift g, boolean vault) {
        final LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        row.setBackground(Theme.getSelectorDrawable(false));
        final BackupImageView image = new BackupImageView(context);
        StarsIntroActivity.setGiftImage(image.getImageReceiver(), g.gift, 44);
        row.addView(image, LayoutHelper.createLinear(44, 44, 0, 0, 12, 0));
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView name = new TextView(context);
        name.setText(SoleVault.title(g));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(text());
        name.setSingleLine();
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        final long cents = SoleVault.usdCents(g);
        String where = cents > 0 ? "≈ " + usd(cents) : getString(R.string.SG_VaultNoValue);
        if (SoleVault.onChain(g)) {
            // moved to a blockchain wallet (and still shown here)
            where += " · " + getString(R.string.SG_VaultOnChain);
        }
        final TextView sub = note(context, where);
        texts.addView(sub);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        if (vault || vaultOk) {
            final TextView move = new TextView(context);
            move.setText(getString(vault ? R.string.SG_VaultTakeBack : R.string.SG_VaultPutAway));
            move.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            move.setTypeface(AndroidUtilities.bold());
            move.setTextColor(accent());
            move.setGravity(Gravity.CENTER);
            move.setPadding(dp(12), 0, dp(12), 0);
            final GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(15));
            bg.setColor(Theme.multAlpha(accent(), 0.12f));
            move.setBackground(bg);
            move.setOnClickListener(v -> askMove(g, vault));
            SolegramMotion.pressable(move, SolegramMotion.PRESS_CARD);
            row.addView(move, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, 8, 0, 0, 0));
        }
        final long owner = vault ? SoleVault.dialogId(currentAccount) : getUserConfig().getClientUserId();
        row.setOnClickListener(v -> new StarGiftSheet(context, currentAccount, owner, getResourceProvider()).set(g, null).show());
        return row;
    }

    /** Moving a gift: when it can move, what Telegram asks for it, then the transfer. */
    private void askMove(TL_stars.SavedStarGift g, boolean fromVault) {
        if (busy || getParentActivity() == null) {
            return;
        }
        SoleWallet.touch();
        final int now = getConnectionsManager().getCurrentTime();
        if (g.can_transfer_at > now) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.timer_3,
                formatString(R.string.SG_VaultLater, LocaleController.formatDateAudio(g.can_transfer_at, true))).show();
            return;
        }
        final String name = SoleVault.title(g);
        final CharSequence message;
        if (g.transfer_stars > 0) {
            message = StarsIntroActivity.replaceStars(formatString(fromVault ? R.string.SG_VaultTakeBackPaid : R.string.SG_VaultPutAwayPaid,
                name, LocaleController.formatNumber(g.transfer_stars, ' ') + " ⭐"), 1.1f);
        } else {
            message = formatString(fromVault ? R.string.SG_VaultTakeBackFree : R.string.SG_VaultPutAwayFree, name);
        }
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(fromVault ? R.string.SG_VaultTakeBack : R.string.SG_VaultPutAway))
            .setMessage(message)
            .setPositiveButton(getString(fromVault ? R.string.SG_VaultTakeBack : R.string.SG_VaultPutAway), (d, w) -> move(g, fromVault))
            .setNegativeButton(getString(R.string.Cancel), null)
            .create());
    }

    private void move(TL_stars.SavedStarGift g, boolean fromVault) {
        busy = true;
        final TLRPC.InputPeer to = fromVault ? getMessagesController().getInputPeer(getUserConfig().getClientUserId()) : SoleVault.inputPeer(currentAccount);
        SoleVault.transfer(currentAccount, g, to, err -> {
            busy = false;
            if (err == null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.forward,
                    formatString(fromVault ? R.string.SG_VaultTakenBack : R.string.SG_VaultPutAwayDone, SoleVault.title(g))).show();
                org.telegram.ui.Stars.StarsController.getInstance(currentAccount).invalidateProfileGifts(getUserConfig().getClientUserId());
                reload();
            } else if ("BALANCE_TOO_LOW".equals(err.text)) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.SG_VaultNoStars),
                    getString(R.string.SG_WalletTopUp), () -> {
                    if (getMessagesController().starsPurchaseAvailable()) {
                        presentFragment(new StarsIntroActivity());
                    } else {
                        com.solegram.messenger.SolegramStarsTopUp.show(getParentActivity(), getResourceProvider());
                    }
                }).show();
            } else {
                BulletinFactory.of(this).showForError(err);
            }
        });
    }
}
