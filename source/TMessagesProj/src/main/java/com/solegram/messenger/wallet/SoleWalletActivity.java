/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.wallet;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.solegram.messenger.soledrop.SoleDropProtocol;
import com.solegram.messenger.ui.SolegramIconMotion;
import com.solegram.messenger.ui.SolegramMotion;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CameraScanActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.Gifts.GiftSheet;
import org.telegram.ui.Stars.StarsController;
import org.telegram.ui.Stars.StarsIntroActivity;

import java.util.ArrayList;

/**
 * SoleWallet's screen: the tour the first time, the wallet's code (set, then asked), and the wallet itself — the
 * 3D card, the Stars balance, sending a gift from the balance (to a contact or by a wallet code), receiving (the
 * card's back is the code), topping up through Telegram, and the history. The screen can't be captured.
 */
public class SoleWalletActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int MENU_CODE = 1, MENU_REMOVE_CODE = 2, MENU_STYLE = 3, MENU_TOUR = 4, MENU_LOCK = 5, MENU_BIO = 6;

    private FrameLayout root;
    private SoleWalletCardView card;
    private TextView balanceView;
    private View moneyRow;
    private TextView moneyValue;
    private LinearLayout history;
    private LinearLayout goalsBox;
    private TextView asideView, collectionInfo;
    private org.telegram.ui.ActionBar.ActionBarMenuSubItem bioItem;
    private boolean showingWallet;

    public static void open(BaseFragment from) {
        if (from != null) {
            from.presentFragment(new SoleWalletActivity());
        }
    }

    /** Set when this screen is only the lock in front of Telegram's wallet: once unlocked, the wallet takes its place. */
    private boolean thenMoney;

    /** Whether SoleWallet's code stands in front of Telegram's wallet right now (a code is set and it is locked). */
    public static boolean moneyLocked(int account) {
        return com.solegram.messenger.SolegramConfig.unifiedWallet && SoleWallet.hasCode(account) && !SoleWallet.isUnlocked(account);
    }

    /**
     * Opens Telegram's wallet: straight away when SoleWallet is open (or has no code), else through SoleWallet's lock,
     * which gives way to the wallet once the code or the fingerprint is right.
     */
    public static void openTelegramWallet(BaseFragment from) {
        if (from == null) {
            return;
        }
        if (moneyLocked(from.getCurrentAccount())) {
            final SoleWalletActivity lock = new SoleWalletActivity();
            lock.thenMoney = true;
            from.presentFragment(lock);
        } else {
            from.presentFragment(new org.telegram.ui.Wallet.WalletActivity());
        }
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.starBalanceUpdated);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.starTransactionsLoaded);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.starBalanceUpdated);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.starTransactionsLoaded);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (showingWallet) {
            updateBalance();
            fillHistory();
            fillGoals();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // what the wallet shows stays out of screenshots, recordings and the recent apps' picture
        // (not while Solegram Studio records the scenes the person started: the video would be black)
        if (getParentActivity() != null && !com.solegram.messenger.studio.SolegramStudio.isRunning()) {
            getParentActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        if (showingWallet && !SoleWallet.isUnlocked(currentAccount)) {
            showLock();
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
    public boolean solegramContentUnderHeader() {
        return true; // the pages are padded for the clear header and blur under it (HeaderBlurRoot)
    }

    @Override
    public boolean isLightStatusBar() {
        // the header is in the page's colour: dark status bar icons on a light page
        return org.telegram.messenger.AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_windowBackgroundGray)) > 0.721f;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("SoleWallet");
        // no bar on top at all: a clear header, the page blurs out of focus under it (see the root below)
        actionBar.setBackgroundColor(Color.TRANSPARENT);
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setItemsBackgroundColor(getThemedColor(Theme.key_listSelector), false);
        actionBar.setTitleColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setCastShadows(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_CODE) {
                    showCreateCode(true);
                } else if (id == MENU_REMOVE_CODE) {
                    askRemoveCode();
                } else if (id == MENU_STYLE) {
                    pickStyle();
                } else if (id == MENU_TOUR) {
                    showIntro(0);
                } else if (id == MENU_LOCK) {
                    SoleWallet.lock();
                    showLock();
                } else if (id == MENU_BIO) {
                    toggleBiometric();
                }
            }
        });
        final ActionBarMenuItem other = actionBar.createMenu().addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(MENU_STYLE, R.drawable.msg_palette, getString(R.string.SG_WalletCardStyle));
        other.addSubItem(MENU_CODE, R.drawable.msg_permissions, getString(R.string.SG_WalletChangeCode));
        other.addSubItem(MENU_LOCK, R.drawable.msg_secret, getString(R.string.SG_WalletLockNow));
        if (SoleWallet.biometricAvailable(context)) {
            bioItem = other.addSubItem(MENU_BIO, R.drawable.fingerprint, biometricLabel());
        }
        other.addSubItem(MENU_TOUR, R.drawable.msg_help, getString(R.string.SG_WalletTour));
        other.addSubItem(MENU_REMOVE_CODE, R.drawable.msg_delete, getString(R.string.SG_WalletRemoveCode));

        root = new HeaderBlurRoot(context, getThemedColor(Theme.key_windowBackgroundGray));
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        if (!SoleWallet.introSeen(currentAccount)) {
            showIntro(0);
        } else if (!SoleWallet.hasCode(currentAccount)) {
            showCreateCode(false);
        } else if (!SoleWallet.isUnlocked(currentAccount)) {
            showLock();
        } else {
            showWallet();
        }
        return root;
    }

    private void swap(View page) {
        final View old = root.getChildCount() > 0 ? root.getChildAt(0) : null;
        root.addView(page, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        page.setAlpha(0f);
        page.setTranslationX(dp(24));
        page.animate().alpha(1f).translationX(0).setDuration(260).setUpdateListener(a -> root.invalidate()).start();
        if (old != null) {
            old.animate().alpha(0f).translationX(-dp(24)).setDuration(200).withEndAction(() -> root.removeView(old)).start();
        }
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

    private TextView bigButton(Context context, int res, boolean primary) {
        final TextView b = new TextView(context);
        b.setText(getString(res));
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        b.setTypeface(AndroidUtilities.bold());
        b.setTextColor(primary ? Color.WHITE : accent());
        b.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(16), primary ? accent() : Theme.multAlpha(accent(), 0.12f),
            primary ? getThemedColor(Theme.key_featuredStickers_addButtonPressed) : Theme.multAlpha(accent(), 0.2f)));
        SolegramMotion.pressable(b, SolegramMotion.PRESS_BUTTON);
        return b;
    }

    // ------------------------------------------------------------------ the tour

    private static final int[][] TOUR = {
        {R.string.SG_WalletTour1Title, R.string.SG_WalletTour1Text},
        {R.string.SG_WalletTour2Title, R.string.SG_WalletTour2Text},
        {R.string.SG_WalletTour3Title, R.string.SG_WalletTour3Text},
        {R.string.SG_WalletTour5Title, R.string.SG_WalletTour5Text},
        {R.string.SG_WalletTour4Title, R.string.SG_WalletTour4Text},
    };
    private static final int[] TOUR_ICONS = {0, R.drawable.msg_premium_liststar, R.drawable.msg_gift_premium, R.drawable.msg_qrcode, R.drawable.msg_secret};

    /** The tour: pages that slide with the finger, their pictures moving at their own pace, dots that flow. */
    private void showIntro(int startPage) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        showingWallet = false;
        final LinearLayout screen = new LinearLayout(context);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(0, ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight, 0, dp(24) + AndroidUtilities.navigationBarHeight);

        final androidx.viewpager.widget.ViewPager pager = new androidx.viewpager.widget.ViewPager(context);
        pager.setAdapter(new androidx.viewpager.widget.PagerAdapter() {
            @Override
            public int getCount() {
                return TOUR.length;
            }

            @Override
            public boolean isViewFromObject(@androidx.annotation.NonNull View view, @androidx.annotation.NonNull Object object) {
                return view == object;
            }

            @androidx.annotation.NonNull
            @Override
            public Object instantiateItem(@androidx.annotation.NonNull android.view.ViewGroup container, int page) {
                final View v = tourPage(context, page);
                container.addView(v);
                return v;
            }

            @Override
            public void destroyItem(@androidx.annotation.NonNull android.view.ViewGroup container, int position, @androidx.annotation.NonNull Object object) {
                container.removeView((View) object);
            }
        });
        // the picture moves slower than the text and fades and shrinks a little: a soft parallax
        pager.setPageTransformer(false, (page, position) -> {
            final View art = page.findViewWithTag("art");
            final float p = Math.max(-1f, Math.min(1f, position));
            if (art != null) {
                art.setTranslationX(-p * page.getWidth() * 0.45f);
                art.setAlpha(1f - Math.abs(p) * 0.9f);
                art.setScaleX(1f - Math.abs(p) * 0.12f);
                art.setScaleY(1f - Math.abs(p) * 0.12f);
            }
            final View texts = page.findViewWithTag("texts");
            if (texts != null) {
                texts.setAlpha(1f - Math.abs(p) * 1.2f);
                texts.setTranslationX(-p * page.getWidth() * 0.12f);
            }
        });
        screen.addView(pager, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        final TourDots dots = new TourDots(context, TOUR.length);
        screen.addView(dots, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 24, 0, 8, 0, 18));

        final TextView next = bigButton(context, R.string.SG_WalletTourNext, true);
        screen.addView(next, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 28, 0, 28, 0));
        final boolean[] last = {false};
        pager.addOnPageChangeListener(new androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float offset, int px) {
                dots.setProgress(position + offset);
            }

            @Override
            public void onPageSelected(int position) {
                final boolean isLast = position == TOUR.length - 1;
                if (isLast != last[0]) {
                    last[0] = isLast;
                    next.animate().alpha(0f).setDuration(110).withEndAction(() -> {
                        next.setText(getString(isLast ? R.string.SG_WalletTourStart : R.string.SG_WalletTourNext));
                        next.animate().alpha(1f).setDuration(160).start();
                    }).start();
                }
            }
        });
        next.setOnClickListener(v -> {
            final int at = pager.getCurrentItem();
            if (at < TOUR.length - 1) {
                pager.setCurrentItem(at + 1, true);
            } else {
                SoleWallet.setIntroSeen(currentAccount);
                if (SoleWallet.hasCode(currentAccount)) {
                    showWallet();
                } else {
                    showCreateCode(false);
                }
            }
        });
        pager.setCurrentItem(Math.max(0, Math.min(TOUR.length - 1, startPage)), false);
        swap(screen);
    }

    private View tourPage(Context context, int page) {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(28), dp(8), dp(28), 0);
        column.setClipChildren(false);

        final FrameLayout art = new FrameLayout(context);
        art.setTag("art");
        art.setClipChildren(false);
        if (page == 0) {
            final SoleWalletCardView demo = new SoleWalletCardView(context, false);
            demo.set(SoleWallet.cardStyle(currentAccount), SoleWallet.cardNumberGrouped(currentAccount, false), SoleWallet.cardNumberGrouped(currentAccount, true), myName());
            art.addView(demo, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        } else {
            final ImageView icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            icon.setImageResource(TOUR_ICONS[page]);
            icon.setScaleX(2.4f);
            icon.setScaleY(2.4f);
            icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            final FrameLayout tile = new FrameLayout(context);
            final GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, SoleWalletCardView.STYLES[(SoleWallet.cardStyle(currentAccount) + page) % SoleWalletCardView.DESIGNS]);
            g.setCornerRadius(dp(40));
            tile.setBackground(g);
            tile.addView(icon, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            art.addView(tile, LayoutHelper.createFrame(140, 140, Gravity.CENTER));
            // a slow float, so the picture is alive
            final android.animation.ObjectAnimator floatAnim = android.animation.ObjectAnimator.ofFloat(tile, View.TRANSLATION_Y, -dp(5), dp(5));
            floatAnim.setDuration(2400);
            floatAnim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            floatAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            floatAnim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            floatAnim.start();
        }
        column.addView(art, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 250));

        final LinearLayout texts = new LinearLayout(context);
        texts.setTag("texts");
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView title = new TextView(context);
        title.setText(getString(TOUR[page][0]));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setTextColor(text());
        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        final TextView body = new TextView(context);
        body.setText(getString(TOUR[page][1]));
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(dp(2), 1f);
        body.setTextColor(gray());
        texts.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        column.addView(texts);
        return column;
    }

    /** The tour's dots: the current one is a pill that flows to the next as the page slides. */
    private class TourDots extends View {
        private final int count;
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private float progress;

        TourDots(Context context, int count) {
            super(context);
            this.count = count;
        }

        void setProgress(float progress) {
            this.progress = progress;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            final float dot = dp(8), pill = dp(24), gap = dp(8);
            final float[] widths = new float[count];
            float total = 0;
            for (int i = 0; i < count; i++) {
                final float k = Math.max(0f, 1f - Math.abs(i - progress));
                widths[i] = dot + (pill - dot) * k;
                total += widths[i];
            }
            total += gap * (count - 1);
            float x = (getWidth() - total) / 2f;
            final float cy = getHeight() / 2f;
            for (int i = 0; i < count; i++) {
                final float k = Math.max(0f, 1f - Math.abs(i - progress));
                paint.setColor(androidx.core.graphics.ColorUtils.blendARGB(Theme.multAlpha(text(), 0.18f), accent(), k));
                r.set(x, cy - dot / 2f, x + widths[i], cy + dot / 2f);
                canvas.drawRoundRect(r, dot / 2f, dot / 2f, paint);
                x += widths[i] + gap;
            }
        }
    }

    private String myName() {
        final TLRPC.User me = getUserConfig().getCurrentUser();
        return me == null ? "" : UserObject.getUserName(me);
    }

    // ------------------------------------------------------------------ the code

    private void showCreateCode(boolean changing) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        if (changing && SoleWallet.hasCode(currentAccount) && !SoleWallet.isUnlocked(currentAccount)) {
            showLock();
            return;
        }
        showingWallet = false;
        final String[] first = {null};
        final PinPad pad = new PinPad(context, getString(R.string.SG_WalletCreateCode), getString(R.string.SG_WalletCreateCodeInfo));
        pad.setOnComplete(code -> {
            if (first[0] == null) {
                first[0] = code;
                pad.reset(getString(R.string.SG_WalletRepeatCode), getString(R.string.SG_WalletRepeatCodeInfo));
            } else if (first[0].equals(code)) {
                SoleWallet.setCode(currentAccount, code);
                SoleWallet.check(currentAccount, code);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.SG_WalletCodeSet)).show();
                showWallet();
            } else {
                first[0] = null;
                pad.fail(getString(R.string.SG_WalletCodesDiffer));
                pad.reset(getString(R.string.SG_WalletCreateCode), getString(R.string.SG_WalletCreateCodeInfo));
            }
        });
        if (!changing) {
            pad.setSkip(getString(R.string.SG_WalletSkipCode), this::showWallet);
        }
        swap(pad);
    }

    private void showLock() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        showingWallet = false;
        final PinPad pad = new PinPad(context, getString(R.string.SG_WalletEnterCode), "");
        pad.setOnComplete(code -> {
            final long wait = SoleWallet.waitLeft(currentAccount);
            if (wait > 0) {
                pad.fail(formatString(R.string.SG_WalletWait, (int) Math.ceil(wait / 1000.0)));
                return;
            }
            if (SoleWallet.check(currentAccount, code)) {
                showWallet();
            } else {
                final long w = SoleWallet.waitLeft(currentAccount);
                pad.fail(w > 0 ? formatString(R.string.SG_WalletWait, (int) Math.ceil(w / 1000.0)) : getString(R.string.SG_WalletWrongCode));
            }
        });
        if (SoleWallet.biometric(currentAccount) && SoleWallet.biometricAvailable(context)) {
            pad.setBiometric(this::askBiometric);
            AndroidUtilities.runOnUIThread(this::askBiometric, 350);
        }
        swap(pad);
    }

    // ------------------------------------------------------------------ the fingerprint

    private String biometricLabel() {
        return getString(SoleWallet.biometric(currentAccount) ? R.string.SG_WalletBiometricOff : R.string.SG_WalletBiometricOn);
    }

    private void toggleBiometric() {
        if (!SoleWallet.hasCode(currentAccount)) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.SG_WalletBiometricNeedsCode)).show();
            return;
        }
        if (!SoleWallet.isUnlocked(currentAccount)) {
            showLock();
            return;
        }
        final boolean on = !SoleWallet.biometric(currentAccount);
        if (!on) {
            SoleWallet.setBiometric(currentAccount, false);
            onBiometricChanged();
            return;
        }
        // turning it on asks for the finger once, so it is the owner's finger that opens the wallet
        prompt(() -> {
            SoleWallet.setBiometric(currentAccount, true);
            onBiometricChanged();
        });
    }

    private void onBiometricChanged() {
        if (bioItem != null) {
            bioItem.setText(biometricLabel());
        }
        BulletinFactory.of(this).createSimpleBulletin(R.raw.done,
            getString(SoleWallet.biometric(currentAccount) ? R.string.SG_WalletBiometricEnabled : R.string.SG_WalletBiometricDisabled)).show();
    }

    private void askBiometric() {
        if (showingWallet || !SoleWallet.biometric(currentAccount) || isFinishing() || getParentActivity() == null) {
            return;
        }
        prompt(() -> {
            SoleWallet.unlockByBiometric();
            showWallet();
        });
    }

    private void prompt(Runnable onSuccess) {
        if (!(getParentActivity() instanceof androidx.fragment.app.FragmentActivity)) {
            return;
        }
        try {
            final androidx.biometric.BiometricPrompt prompt = new androidx.biometric.BiometricPrompt((androidx.fragment.app.FragmentActivity) getParentActivity(),
                androidx.core.content.ContextCompat.getMainExecutor(getParentActivity()), new androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@androidx.annotation.NonNull androidx.biometric.BiometricPrompt.AuthenticationResult result) {
                        onSuccess.run();
                    }
                });
            prompt.authenticate(new androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("SoleWallet")
                .setSubtitle(getString(R.string.SG_WalletBiometricPrompt))
                .setNegativeButtonText(getString(R.string.SG_WalletUseCode))
                .setAllowedAuthenticators(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build());
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    private void askRemoveCode() {
        if (getParentActivity() == null || !SoleWallet.hasCode(currentAccount)) {
            return;
        }
        if (!SoleWallet.isUnlocked(currentAccount)) {
            showLock();
            return;
        }
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(R.string.SG_WalletRemoveCode))
            .setMessage(getString(R.string.SG_WalletRemoveCodeInfo))
            .setPositiveButton(getString(R.string.SG_WalletRemoveCodeShort), (d, w) -> {
                SoleWallet.removeCode(currentAccount);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.SG_WalletCodeRemoved)).show();
            })
            .setNegativeButton(getString(R.string.Cancel), null)
            .create());
    }

    /** A code pad: six dots and the keys; it shakes when the code is wrong. */
    private class PinPad extends LinearLayout {
        private static final int LENGTH = 6;
        private final TextView title, info, error;
        private final LinearLayout dots;
        private final StringBuilder code = new StringBuilder();
        private Utilities.Callback<String> onComplete;
        private final TextView skip;
        private TextView bioKey;

        PinPad(Context context, String titleText, String infoText) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setPadding(dp(24), ActionBar.getCurrentActionBarHeight() + dp(40), dp(24), dp(24));
            final ImageView lock = new ImageView(context);
            lock.setImageResource(R.drawable.msg_secret);
            lock.setScaleType(ImageView.ScaleType.CENTER);
            lock.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            final GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, SoleWalletCardView.STYLES[SoleWallet.cardStyle(currentAccount)]);
            g.setShape(GradientDrawable.OVAL);
            lock.setBackground(g);
            addView(lock, LayoutHelper.createLinear(64, 64));
            title = new TextView(context);
            title.setText(titleText);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
            title.setTypeface(AndroidUtilities.bold());
            title.setTextColor(text());
            title.setGravity(Gravity.CENTER);
            addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 0));
            info = new TextView(context);
            info.setText(infoText);
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            info.setTextColor(gray());
            info.setGravity(Gravity.CENTER);
            addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
            dots = new LinearLayout(context);
            dots.setGravity(Gravity.CENTER);
            addView(dots, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 24, 0, 22, 0, 0));
            error = new TextView(context);
            error.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            error.setTextColor(getThemedColor(Theme.key_text_RedRegular));
            error.setGravity(Gravity.CENTER);
            addView(error, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
            final View space = new View(context);
            addView(space, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
            final String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫"};
            for (int r = 0; r < 4; r++) {
                final LinearLayout row = new LinearLayout(context);
                row.setGravity(Gravity.CENTER);
                for (int c = 0; c < 3; c++) {
                    final String key = keys[r * 3 + c];
                    final TextView k = new TextView(context);
                    k.setText(key);
                    k.setGravity(Gravity.CENTER);
                    k.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
                    k.setTextColor(text());
                    if (!key.isEmpty()) {
                        k.setBackground(Theme.createCircleSelectorDrawable(Theme.multAlpha(text(), 0.1f), 0, 0));
                        k.setOnClickListener(v -> press(key));
                    } else {
                        bioKey = k;
                    }
                    row.addView(k, LayoutHelper.createLinear(76, 76, 10, 4, 10, 4));
                }
                addView(row);
            }
            skip = new TextView(context);
            skip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            skip.setTextColor(accent());
            skip.setGravity(Gravity.CENTER);
            skip.setPadding(dp(16), dp(12), dp(16), dp(12));
            skip.setVisibility(GONE);
            addView(skip, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
            updateDots();
        }

        /** The empty key left of 0 becomes the fingerprint. */
        void setBiometric(Runnable ask) {
            if (bioKey == null) {
                return;
            }
            final android.graphics.drawable.Drawable d = androidx.core.content.ContextCompat.getDrawable(getContext(), R.drawable.fingerprint).mutate();
            d.setColorFilter(new PorterDuffColorFilter(text(), PorterDuff.Mode.SRC_IN));
            d.setBounds(0, 0, dp(30), dp(30));
            bioKey.setCompoundDrawables(null, d, null, null);
            bioKey.setPadding(0, dp(23), 0, 0);
            bioKey.setBackground(Theme.createCircleSelectorDrawable(Theme.multAlpha(text(), 0.1f), 0, 0));
            bioKey.setContentDescription(getString(R.string.SG_WalletBiometricPrompt));
            bioKey.setOnClickListener(v -> ask.run());
        }

        void setOnComplete(Utilities.Callback<String> c) {
            onComplete = c;
        }

        void setSkip(String text, Runnable r) {
            skip.setText(text);
            skip.setVisibility(VISIBLE);
            skip.setOnClickListener(v -> r.run());
        }

        void reset(String t, String i) {
            code.setLength(0);
            title.setText(t);
            info.setText(i);
            updateDots();
        }

        void fail(String message) {
            error.setText(message);
            code.setLength(0);
            updateDots();
            AndroidUtilities.shakeViewSpring(dots, 6);
            try {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            } catch (Exception ignore) {
            }
        }

        private void press(String key) {
            error.setText("");
            if ("⌫".equals(key)) {
                if (code.length() > 0) {
                    code.setLength(code.length() - 1);
                }
            } else if (code.length() < LENGTH) {
                code.append(key);
            }
            updateDots();
            if (code.length() == LENGTH && onComplete != null) {
                final String c = code.toString();
                postDelayed(() -> onComplete.run(c), 120);
            }
        }

        private void updateDots() {
            dots.removeAllViews();
            for (int i = 0; i < LENGTH; i++) {
                final View d = new View(getContext());
                final GradientDrawable dot = new GradientDrawable();
                dot.setShape(GradientDrawable.OVAL);
                if (i < code.length()) {
                    dot.setColor(accent());
                } else {
                    dot.setStroke(dp(2), Theme.multAlpha(text(), 0.3f));
                }
                d.setBackground(dot);
                dots.addView(d, LayoutHelper.createLinear(14, 14, 7, 0, 7, 0));
            }
        }
    }

    // ------------------------------------------------------------------ the wallet

    private void showWallet() {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        if (thenMoney) {
            // only the lock in front of Telegram's wallet: the wallet takes this screen's place
            thenMoney = false;
            AndroidUtilities.runOnUIThread(() -> presentFragment(new org.telegram.ui.Wallet.WalletActivity(), true));
        }
        showingWallet = true;
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setPadding(0, ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight + dp(4), 0, dp(24));

        card = new SoleWalletCardView(context);
        card.set(SoleWallet.cardStyle(currentAccount), SoleWallet.cardNumberGrouped(currentAccount, false), SoleWallet.cardNumberGrouped(currentAccount, true), myName());
        loadCardGift();
        column.addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final TextView flipHint = new TextView(context);
        flipHint.setText(getString(R.string.SG_WalletFlipHint));
        flipHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        flipHint.setTextColor(gray());
        flipHint.setGravity(Gravity.CENTER);
        column.addView(flipHint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView balanceLabel = new TextView(context);
        balanceLabel.setText(getString(R.string.SG_WalletBalance));
        balanceLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        balanceLabel.setAllCaps(true);
        balanceLabel.setLetterSpacing(0.08f);
        balanceLabel.setTextColor(gray());
        balanceLabel.setGravity(Gravity.CENTER);
        column.addView(balanceLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 0));
        balanceView = new TextView(context);
        balanceView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 42);
        balanceView.setLetterSpacing(-0.02f);
        balanceView.setTypeface(AndroidUtilities.bold());
        balanceView.setTextColor(text());
        balanceView.setGravity(Gravity.CENTER);
        column.addView(balanceView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        asideView = new TextView(context);
        asideView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        asideView.setTextColor(gray());
        asideView.setGravity(Gravity.CENTER);
        asideView.setVisibility(View.GONE);
        column.addView(asideView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        final LinearLayout actions = new LinearLayout(context);
        actions.setClipChildren(false);
        actions.setClipToPadding(false);
        actions.setPadding(dp(8), dp(16), dp(8), 0);
        actions.addView(action(context, R.drawable.msg_send, R.string.SG_WalletSend, this::send), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        actions.addView(action(context, R.drawable.msg_qrcode, R.string.SG_WalletReceive, this::receive), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        actions.addView(action(context, R.drawable.msg_add, R.string.SG_WalletTopUp, () -> topUp()), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        actions.addView(action(context, R.drawable.msg_gift_premium, R.string.SG_WalletGifts, this::openGifts), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        column.addView(actions);

        // Telegram's own wallet (Grams) lives here too: one wallet for Stars, Grams and gifts
        moneyRow = moneyRow(context);
        if (moneyRow != null) {
            column.addView(moneyRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 20, 12, 0));
        }

        column.addView(collectionCard(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, moneyRow != null ? 10 : 20, 12, 0));

        final LinearLayout goalsHead = new LinearLayout(context);
        goalsHead.setGravity(Gravity.CENTER_VERTICAL);
        final TextView goalsTitle = new TextView(context);
        goalsTitle.setText(getString(R.string.SG_GoalsTitle));
        goalsTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        goalsTitle.setTypeface(AndroidUtilities.bold());
        goalsTitle.setTextColor(text());
        goalsHead.addView(goalsTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        final TextView newGoal = new TextView(context);
        newGoal.setText(getString(R.string.SG_GoalsNew));
        newGoal.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        newGoal.setTextColor(accent());
        newGoal.setPadding(dp(8), dp(4), dp(8), dp(4));
        newGoal.setOnClickListener(v -> editGoal(null));
        goalsHead.addView(newGoal);
        column.addView(goalsHead, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 24, 12, 8));
        goalsBox = new LinearLayout(context);
        goalsBox.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable goalsPanel = new GradientDrawable();
        goalsPanel.setCornerRadius(dp(22));
        goalsPanel.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        goalsBox.setBackground(goalsPanel);
        column.addView(goalsBox, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 0));

        final TextView historyTitle = new TextView(context);
        historyTitle.setText(getString(R.string.SG_WalletHistory));
        historyTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        historyTitle.setTypeface(AndroidUtilities.bold());
        historyTitle.setTextColor(text());
        column.addView(historyTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 24, 20, 8));
        history = new LinearLayout(context);
        history.setOrientation(LinearLayout.VERTICAL);
        final GradientDrawable panel = new GradientDrawable();
        panel.setCornerRadius(dp(22));
        panel.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        history.setBackground(panel);
        column.addView(history, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 0, 12, 0));

        final ScrollView scroll = new ScrollView(context);
        scroll.setClipChildren(false);
        scroll.addView(column);
        swap(scroll);
        final StarsController sc = StarsController.getInstance(currentAccount);
        sc.getBalance(this::updateBalance);
        sc.invalidateTransactions(true);
        sc.loadTransactions(StarsController.ALL_TRANSACTIONS);
        updateBalance();
        fillHistory();
        fillGoals();
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        if (showingWallet) {
            updateCollection();
            fillGoals();
            updateMoney();
        }
    }

    /** Topping up: Telegram's Stars screen, or, where Telegram doesn't sell Stars in this app, its other ways. */
    private void topUp() {
        if (getMessagesController().starsPurchaseAvailable()) {
            presentFragment(new StarsIntroActivity());
        } else {
            com.solegram.messenger.SolegramStarsTopUp.show(getParentActivity(), getResourceProvider());
        }
    }

    private View action(Context context, int icon, int label, Runnable onClick) {
        final LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setClipChildren(false); // the button grows a little when pressed: it must not be cut
        cell.setClipToPadding(false);
        final ImageView circle = new ImageView(context);
        circle.setScaleType(ImageView.ScaleType.CENTER);
        circle.setImageResource(icon);
        circle.setColorFilter(new PorterDuffColorFilter(accent(), PorterDuff.Mode.SRC_IN));
        // a soft square in the accent's tint (the refreshed look), over the page's own card colour
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(androidx.core.graphics.ColorUtils.compositeColors(
            androidx.core.graphics.ColorUtils.setAlphaComponent(accent(), Theme.isCurrentThemeDark() ? 0x2E : 0x1F),
            getThemedColor(Theme.key_windowBackgroundWhite)));
        circle.setBackground(bg);
        cell.addView(circle, LayoutHelper.createLinear(60, 56));
        final TextView t = new TextView(context);
        t.setText(getString(label));
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        t.setTextColor(text());
        t.setGravity(Gravity.CENTER);
        cell.addView(t, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        cell.setOnClickListener(v -> {
            SolegramIconMotion.play(circle, SolegramIconMotion.POP);
            SoleWallet.touch();
            AndroidUtilities.runOnUIThread(onClick, SolegramIconMotion.MENU_DELAY);
        });
        return cell;
    }

    private void updateBalance() {
        if (balanceView == null) {
            return;
        }
        final TL_stars.StarsAmount amount = StarsController.getInstance(currentAccount).getBalance();
        balanceView.setText(StarsIntroActivity.replaceStars("⭐ " + LocaleController.formatNumber(amount == null ? 0 : amount.amount, ' '), 1.25f));
        balanceView.setTextColor(text());
        if (asideView != null) {
            // until Telegram has sent the balance, the goals are left as they are
            final boolean known = amount != null && StarsController.getInstance(currentAccount).balanceAvailable();
            final long aside = known ? SoleWallet.putAside(currentAccount, amount.amount) : 0;
            asideView.setVisibility(aside > 0 ? View.VISIBLE : View.GONE);
            if (aside > 0) {
                asideView.setText(StarsIntroActivity.replaceStars(formatString(R.string.SG_GoalsAside,
                    LocaleController.formatNumber(aside, ' ') + " ⭐", LocaleController.formatNumber(amount.amount - aside, ' ') + " ⭐"), 1.1f));
            }
        }
    }

    // ------------------------------------------------------------------ Telegram's wallet

    private static boolean moneyAvailable(int account) {
        return com.solegram.messenger.SolegramConfig.unifiedWallet && MessagesController.getInstance(account).config.walletAvailable.get();
    }

    /** A row for Telegram's wallet: its Grams balance, and a tap opens the wallet itself (sending, top-ups, on-chain gifts). */
    private View moneyRow(Context context) {
        if (!moneyAvailable(currentAccount)) {
            return null;
        }
        final LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        row.setBackground(bg);
        final ImageView icon = new ImageView(context);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setImageResource(Theme.isCurrentThemeDark() ? R.drawable.settings_wallet_dark : R.drawable.settings_wallet);
        row.addView(icon, LayoutHelper.createLinear(36, 36, 0, 0, 12, 0));
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView title = new TextView(context);
        title.setText(getString(R.string.SG_WalletMoney));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(text());
        texts.addView(title);
        final TextView sub = new TextView(context);
        sub.setText(getString(R.string.SG_WalletMoneyInfo));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        sub.setTextColor(gray());
        texts.addView(sub);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        moneyValue = new TextView(context);
        moneyValue.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        moneyValue.setTypeface(AndroidUtilities.bold());
        moneyValue.setTextColor(text());
        row.addView(moneyValue, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 8, 0, 6, 0));
        final ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.msg_arrowright);
        arrow.setColorFilter(new PorterDuffColorFilter(gray(), PorterDuff.Mode.SRC_IN));
        row.addView(arrow, LayoutHelper.createLinear(20, 20));
        row.setOnClickListener(v -> {
            SoleWallet.touch();
            presentFragment(new org.telegram.ui.Wallet.WalletActivity());
        });
        com.solegram.messenger.ui.SolegramMotion.pressable(row, com.solegram.messenger.ui.SolegramMotion.PRESS_CARD);
        updateMoney();
        return row;
    }

    private void updateMoney() {
        if (moneyValue == null) {
            return;
        }
        try {
            final long grams = org.telegram.ui.Wallet.GramWallet.getInstance(currentAccount).getBalance();
            moneyValue.setText(grams > 0 ? org.telegram.ui.Wallet.GramWallet.formatGrams(grams) : getString(R.string.SG_WalletMoneyOpen));
            moneyValue.setTextColor(grams > 0 ? text() : accent());
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
            moneyValue.setText(getString(R.string.SG_WalletMoneyOpen));
        }
    }

    // ------------------------------------------------------------------ the collection

    /** A card to the collection: its worth as last seen, and the vault. */
    private View collectionCard(Context context) {
        final LinearLayout card = new LinearLayout(context);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        card.setBackground(bg);
        final ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.msg_gift_premium);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        final GradientDrawable circle = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFFB443, 0xFFF2762E});
        circle.setShape(GradientDrawable.OVAL);
        icon.setBackground(circle);
        card.addView(icon, LayoutHelper.createLinear(42, 42, 0, 0, 14, 0));
        final LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        final TextView title = new TextView(context);
        title.setText(getString(R.string.SG_VaultTitle));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(text());
        texts.addView(title);
        collectionInfo = new TextView(context);
        collectionInfo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        collectionInfo.setTextColor(gray());
        texts.addView(collectionInfo);
        card.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        final ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.msg_arrowright);
        arrow.setColorFilter(new PorterDuffColorFilter(gray(), PorterDuff.Mode.SRC_IN));
        card.addView(arrow, LayoutHelper.createLinear(24, 24));
        card.setOnClickListener(v -> {
            SoleWallet.touch();
            presentFragment(new SoleVaultActivity());
        });
        SolegramMotion.pressable(card, SolegramMotion.PRESS_CARD);
        updateCollection();
        return card;
    }

    private void updateCollection() {
        if (collectionInfo == null) {
            return;
        }
        final long worth = SoleVault.lastWorth(currentAccount);
        collectionInfo.setText(worth > 0
            ? formatString(R.string.SG_VaultCardWorth, org.telegram.messenger.BillingController.getInstance().formatCurrency(worth, "USD"))
            : getString(SoleVault.channelId(currentAccount) != 0 ? R.string.SG_VaultCardReady : R.string.SG_VaultCardInfo));
    }

    // ------------------------------------------------------------------ savings goals

    private void fillGoals() {
        if (goalsBox == null || getParentActivity() == null) {
            return;
        }
        final Context context = getParentActivity();
        goalsBox.removeAllViews();
        final java.util.ArrayList<SoleWallet.Goal> goals = SoleWallet.goals(currentAccount);
        if (goals.isEmpty()) {
            final TextView empty = new TextView(context);
            empty.setText(getString(R.string.SG_GoalsEmpty));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            empty.setTextColor(gray());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(20), dp(20), dp(20));
            empty.setOnClickListener(v -> editGoal(null));
            goalsBox.addView(empty);
            return;
        }
        for (SoleWallet.Goal g : goals) {
            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(14));
            row.setBackground(Theme.getSelectorDrawable(false));
            final LinearLayout line = new LinearLayout(context);
            final TextView name = new TextView(context);
            name.setText(g.name);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            name.setTypeface(AndroidUtilities.bold());
            name.setTextColor(text());
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            line.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            final TextView amount = new TextView(context);
            final boolean reached = g.saved >= g.target;
            amount.setText(StarsIntroActivity.replaceStars(reached ? getString(R.string.SG_GoalsReached)
                : LocaleController.formatNumber(g.saved, ' ') + " / " + LocaleController.formatNumber(g.target, ' ') + " ⭐", 1.1f));
            amount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            amount.setTextColor(reached ? 0xFF35B262 : gray());
            amount.setTypeface(reached ? AndroidUtilities.bold() : null);
            line.addView(amount);
            row.addView(line);
            row.addView(new GoalBar(context, Math.min(1f, g.saved / (float) g.target), reached ? 0xFF35B262 : accent()),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8, 0, 10, 0, 0));
            row.setOnClickListener(v -> goalOptions(row, g));
            goalsBox.addView(row);
        }
    }

    /** A rounded bar that fills to the goal's progress. */
    private static class GoalBar extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private final int color;
        private float shown;
        private final float target;

        GoalBar(Context context, float progress, int color) {
            super(context);
            this.color = color;
            this.target = progress;
            final android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0, 1);
            a.setDuration(700);
            a.setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT_QUINT);
            a.addUpdateListener(x -> {
                shown = target * (float) x.getAnimatedValue();
                invalidate();
            });
            a.setStartDelay(120);
            a.start();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            final float h = getHeight();
            r.set(0, 0, getWidth(), h);
            paint.setColor(Theme.multAlpha(color, 0.15f));
            canvas.drawRoundRect(r, h / 2f, h / 2f, paint);
            if (shown > 0) {
                r.set(0, 0, Math.max(h, getWidth() * shown), h);
                paint.setColor(color);
                canvas.drawRoundRect(r, h / 2f, h / 2f, paint);
            }
        }
    }

    private long freeStars() {
        if (!StarsController.getInstance(currentAccount).balanceAvailable()) {
            return 0;
        }
        final TL_stars.StarsAmount amount = StarsController.getInstance(currentAccount).getBalance();
        final long balance = amount == null ? 0 : amount.amount;
        return Math.max(0, balance - SoleWallet.putAside(currentAccount, balance));
    }

    private void goalOptions(View anchor, SoleWallet.Goal g) {
        SoleWallet.touch();
        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        if (g.saved < g.target) {
            o.add(R.drawable.msg_add, getString(R.string.SG_GoalsPut), () -> askAmount(g, true));
        }
        if (g.saved > 0) {
            o.add(R.drawable.msg_go_up, getString(R.string.SG_GoalsTake), () -> askAmount(g, false));
        }
        o.add(R.drawable.msg_edit, getString(R.string.SG_GoalsEdit), () -> editGoal(g));
        o.add(R.drawable.msg_delete, getString(R.string.SG_GoalsDelete), true, () -> {
            final java.util.ArrayList<SoleWallet.Goal> list = SoleWallet.goals(currentAccount);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id == g.id) {
                    list.remove(i);
                    break;
                }
            }
            SoleWallet.saveGoals(currentAccount, list);
            updateBalance();
            fillGoals();
        });
        o.show();
    }

    /** A number dialog: how many Stars to put aside for the goal, or to take back. */
    private void askAmount(SoleWallet.Goal g, boolean put) {
        if (getParentActivity() == null) {
            return;
        }
        final long max = put ? Math.min(freeStars(), g.target - g.saved) : g.saved;
        if (max <= 0) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.info, getString(R.string.SG_GoalsNoFree),
                getString(R.string.SG_WalletTopUp), () -> topUp()).show();
            return;
        }
        final android.widget.EditText edit = numberField(String.valueOf(max));
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(put ? R.string.SG_GoalsPut : R.string.SG_GoalsTake))
            .setMessage(StarsIntroActivity.replaceStars(formatString(R.string.SG_GoalsUpTo, LocaleController.formatNumber(max, ' ') + " ⭐"), 1.1f))
            .setView(wrap(edit))
            .setPositiveButton(getString(R.string.OK), (d, w) -> {
                long n;
                try {
                    n = Long.parseLong(edit.getText().toString().trim());
                } catch (Exception e) {
                    n = 0;
                }
                n = Math.max(0, Math.min(max, n));
                if (n == 0) {
                    return;
                }
                final java.util.ArrayList<SoleWallet.Goal> list = SoleWallet.goals(currentAccount);
                for (SoleWallet.Goal x : list) {
                    if (x.id == g.id) {
                        x.saved += put ? n : -n;
                        if (put && x.saved >= x.target) {
                            BulletinFactory.of(this).createSimpleBulletin(R.raw.done, formatString(R.string.SG_GoalsDone, x.name)).show();
                        }
                    }
                }
                SoleWallet.saveGoals(currentAccount, list);
                updateBalance();
                fillGoals();
            })
            .setNegativeButton(getString(R.string.Cancel), null)
            .create());
        AndroidUtilities.runOnUIThread(() -> {
            edit.requestFocus();
            edit.selectAll();
            AndroidUtilities.showKeyboard(edit);
        }, 250);
    }

    /** A new goal, or a goal's name and target changed. */
    private void editGoal(SoleWallet.Goal goal) {
        if (getParentActivity() == null) {
            return;
        }
        SoleWallet.touch();
        final Context context = getParentActivity();
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(4), dp(24), 0);
        final android.widget.EditText name = new android.widget.EditText(context);
        name.setHint(getString(R.string.SG_GoalsNameHint));
        name.setSingleLine(true);
        name.setText(goal == null ? "" : goal.name);
        name.setTextColor(text());
        name.setHintTextColor(gray());
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        box.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        final android.widget.EditText target = numberField(goal == null ? "" : String.valueOf(goal.target));
        target.setHint(getString(R.string.SG_GoalsTargetHint));
        box.addView(target, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        showDialog(new AlertDialog.Builder(context, getResourceProvider())
            .setTitle(getString(goal == null ? R.string.SG_GoalsNewTitle : R.string.SG_GoalsEdit))
            .setView(box)
            .setPositiveButton(getString(R.string.Save), (d, w) -> {
                final String n = name.getText().toString().trim();
                long t;
                try {
                    t = Long.parseLong(target.getText().toString().trim());
                } catch (Exception e) {
                    t = 0;
                }
                if (TextUtils.isEmpty(n) || t <= 0) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.SG_GoalsInvalid)).show();
                    return;
                }
                final java.util.ArrayList<SoleWallet.Goal> list = SoleWallet.goals(currentAccount);
                if (goal == null) {
                    final SoleWallet.Goal g = new SoleWallet.Goal();
                    g.id = System.currentTimeMillis();
                    g.name = n;
                    g.target = Math.min(t, 100_000_000L);
                    list.add(g);
                } else {
                    for (SoleWallet.Goal x : list) {
                        if (x.id == goal.id) {
                            x.name = n;
                            x.target = Math.min(t, 100_000_000L);
                            x.saved = Math.min(x.saved, x.target);
                        }
                    }
                }
                SoleWallet.saveGoals(currentAccount, list);
                updateBalance();
                fillGoals();
            })
            .setNegativeButton(getString(R.string.Cancel), null)
            .create());
        AndroidUtilities.runOnUIThread(() -> {
            name.requestFocus();
            AndroidUtilities.showKeyboard(name);
        }, 250);
    }

    private android.widget.EditText numberField(String value) {
        final android.widget.EditText e = new android.widget.EditText(getParentActivity());
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setSingleLine(true);
        e.setText(value);
        e.setTextColor(text());
        e.setHintTextColor(gray());
        e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(9)});
        return e;
    }

    private View wrap(View v) {
        final FrameLayout f = new FrameLayout(getParentActivity());
        f.setPadding(dp(24), 0, dp(24), 0);
        f.addView(v);
        return f;
    }

    private void fillHistory() {
        if (history == null || getParentActivity() == null) {
            return;
        }
        history.removeAllViews();
        final StarsController sc = StarsController.getInstance(currentAccount);
        final ArrayList<TL_stars.StarsTransaction> list = sc.transactions[StarsController.ALL_TRANSACTIONS];
        if (list.isEmpty()) {
            final TextView empty = new TextView(getParentActivity());
            empty.setText(getString(R.string.SG_WalletNoHistory));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            empty.setTextColor(gray());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(24), dp(16), dp(24));
            history.addView(empty);
            return;
        }
        final int n = Math.min(30, list.size());
        for (int i = 0; i < n; i++) {
            final TL_stars.StarsTransaction t = list.get(i);
            final LinearLayout row = new LinearLayout(getParentActivity());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackground(Theme.getSelectorDrawable(false));
            final LinearLayout texts = new LinearLayout(getParentActivity());
            texts.setOrientation(LinearLayout.VERTICAL);
            final TextView title = new TextView(getParentActivity());
            CharSequence name;
            try {
                name = StarsIntroActivity.getTransactionTitle(currentAccount, false, t);
            } catch (Exception e) {
                name = "";
            }
            title.setText(TextUtils.isEmpty(name) ? getString(R.string.SG_WalletTransaction) : name);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTextColor(text());
            title.setSingleLine();
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            final TextView date = new TextView(getParentActivity());
            date.setText(LocaleController.formatDateAudio(t.date, false));
            date.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            date.setTextColor(gray());
            texts.addView(date);
            row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            final TextView amount = new TextView(getParentActivity());
            final long a = t.amount == null ? 0 : t.amount.amount;
            amount.setText(StarsIntroActivity.replaceStars((a > 0 ? "+" : "−") + LocaleController.formatNumber(Math.abs(a), ' ') + " ⭐", 1.15f));
            amount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            amount.setTypeface(AndroidUtilities.bold());
            amount.setTextColor(a > 0 ? 0xFF35B262 : text());
            row.addView(amount);
            row.setOnClickListener(v -> StarsIntroActivity.showTransactionSheet(getParentActivity(), false, 0, currentAccount, t, getResourceProvider()));
            history.addView(row);
        }
    }

    // ------------------------------------------------------------------ actions

    /** Sending: to a contact or by a wallet code, as a gift from the Stars balance (Telegram's own sheet). */
    private void send() {
        if (card == null) {
            return;
        }
        final ItemOptions o = ItemOptions.makeOptions(this, card);
        o.add(R.drawable.msg_contacts, getString(R.string.SG_WalletSendContact), this::pickContact);
        o.add(R.drawable.msg_qrcode, getString(R.string.SG_WalletSendCode), this::scanCode);
        // by a tap of the phones: SoleDrop finds the person, then Telegram's own gift sheet sends
        o.add(R.drawable.msg_soledrop, getString(R.string.SG_WalletSendTapStars),
            () -> com.solegram.messenger.soledrop.SoleDrop.open(this, com.solegram.messenger.soledrop.SoleDropItem.stars()));
        o.add(R.drawable.msg_gift_premium, getString(R.string.SG_WalletSendTapGift),
            () -> com.solegram.messenger.soledrop.SoleDrop.open(this, com.solegram.messenger.soledrop.SoleDropItem.gift()));
        o.setGravity(Gravity.CENTER_HORIZONTAL);
        o.show();
    }

    private void pickContact() {
        final Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_USERS_ONLY);
        args.putBoolean("allowGlobalSearch", false);
        final DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids != null && !dids.isEmpty() && dids.get(0).dialogId > 0) {
                final long id = dids.get(0).dialogId;
                fragment.finishFragment();
                AndroidUtilities.runOnUIThread(() -> openGiftFor(id), 300);
            }
            return true;
        });
        presentFragment(picker);
    }

    private void scanCode() {
        CameraScanActivity.showAsSheet(this, false, CameraScanActivity.TYPE_QR, new CameraScanActivity.CameraScanActivityDelegate() {
            @Override
            public void didFindQr(String text) {
                resolveCode(text);
            }
        });
    }

    /** A wallet code is a Telegram contact link (https://t.me/contact/…): Telegram finds the person by it. */
    private void resolveCode(String text) {
        final String token = SoleDropProtocol.tokenFromLink(text);
        if (token == null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.SG_WalletBadCode)).show();
            return;
        }
        final TLRPC.TL_contacts_importContactToken req = new TLRPC.TL_contacts_importContactToken();
        req.token = token;
        getConnectionsManager().sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (res instanceof TLRPC.User) {
                final TLRPC.User user = (TLRPC.User) res;
                getMessagesController().putUser(user, false);
                confirmPerson(user);
            } else {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.SG_WalletStaleCode)).show();
            }
        }), ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    /** Who the code belongs to, before anything is sent. */
    private void confirmPerson(TLRPC.User user) {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(R.string.SG_WalletSendTo))
            .setMessage(formatString(R.string.SG_WalletSendToInfo, UserObject.getUserName(user)))
            .setPositiveButton(getString(R.string.SG_WalletContinue), (d, w) -> openGiftFor(user.id))
            .setNegativeButton(getString(R.string.Cancel), null)
            .create());
    }

    private void openGiftFor(long userId) {
        if (getParentActivity() == null) {
            return;
        }
        new GiftSheet(getParentActivity(), currentAccount, userId, closeParent -> {
            StarsController.getInstance(currentAccount).invalidateBalance();
            StarsController.getInstance(currentAccount).invalidateTransactions(true);
        }).show();
    }

    /** Receiving: the wallet code (the account's contact link) as a QR in its own sheet, to show or share. */
    private void receive() {
        com.solegram.messenger.soledrop.SoleDropQrSheet.show(this, currentAccount);
    }

    private void openGifts() {
        final Bundle args = new Bundle();
        args.putLong("user_id", getUserConfig().getClientUserId());
        args.putBoolean("my_profile", true);
        args.putBoolean("open_gifts", true);
        presentFragment(new org.telegram.ui.ProfileActivity(args));
    }

    /** The card's design: a row of small cards, each in its design; a tap puts it on the card. */
    private void pickStyle() {
        if (card == null || getParentActivity() == null) {
            return;
        }
        final Context context = getParentActivity();
        final org.telegram.ui.ActionBar.BottomSheet sheet = new org.telegram.ui.ActionBar.BottomSheet(context, false, getResourceProvider());
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(8), 0, dp(16));
        final TextView title = new TextView(context);
        title.setText(getString(R.string.SG_WalletCardStyle));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 8, 20, 12));
        final android.widget.HorizontalScrollView h = new android.widget.HorizontalScrollView(context);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipToPadding(false);
        h.setPadding(dp(12), 0, dp(12), 0);
        final LinearLayout row = new LinearLayout(context);
        h.addView(row);
        for (int i = 0; i < SoleWalletCardView.DESIGNS; i++) {
            final int design = i;
            final LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(4), dp(4), dp(4), dp(6));
            final SoleWalletCardView mini = new SoleWalletCardView(context, false);
            mini.set(design, SoleWallet.cardNumberGrouped(currentAccount, false), SoleWallet.cardNumberGrouped(currentAccount, true), myName());
            cell.addView(mini, LayoutHelper.createLinear(176, LayoutHelper.WRAP_CONTENT));
            final TextView name = new TextView(context);
            name.setText(getString(SoleWalletCardView.DESIGN_NAMES[design]));
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            final boolean chosen = SoleWallet.cardStyle(currentAccount) == design;
            name.setTypeface(chosen ? AndroidUtilities.bold() : null);
            name.setTextColor(chosen ? accent() : getThemedColor(Theme.key_dialogTextBlack));
            cell.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            if (chosen) {
                final GradientDrawable ring = new GradientDrawable();
                ring.setCornerRadius(dp(16));
                ring.setStroke(dp(2), accent());
                cell.setBackground(ring);
            }
            cell.setOnClickListener(v -> {
                SoleWallet.setCardStyle(currentAccount, design);
                card.setDesign(design);
                card.spin();
                sheet.dismiss();
            });
            SolegramMotion.pressable(cell, SolegramMotion.PRESS_CARD);
            row.addView(cell, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 2, 0, 2, 0));
        }
        column.addView(h);
        addGiftPicker(context, column, sheet);
        sheet.setCustomView(column);
        showDialog(sheet);
    }

    // ------------------------------------------------------------------ a gift on the card

    private ArrayList<TL_stars.SavedStarGift> myCollectibles;

    /** The chosen collectible onto the card (found among the account's gifts by its slug). */
    private void loadCardGift() {
        final String slug = SoleWallet.cardGift(currentAccount);
        if (slug == null || card == null) {
            return;
        }
        SoleVault.loadGifts(currentAccount, getMessagesController().getInputPeer(getUserConfig().getClientUserId()), gifts -> {
            myCollectibles = collectiblesOf(gifts);
            for (TL_stars.SavedStarGift g : myCollectibles) {
                if (slug.equals(((TL_stars.TL_starGiftUnique) g.gift).slug)) {
                    if (card != null) {
                        card.setGift(g.gift, SoleWallet.cardGiftColor(currentAccount));
                    }
                    return;
                }
            }
            // the gift is gone (sold, sent, moved to the blockchain without being shown): the card goes back to its design
            SoleWallet.setCardGift(currentAccount, null);
        });
    }

    private static ArrayList<TL_stars.SavedStarGift> collectiblesOf(ArrayList<TL_stars.SavedStarGift> all) {
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

    /** In the design sheet: the account's collectible gifts to put on the card, and whether it takes their colour. */
    private void addGiftPicker(Context context, LinearLayout column, org.telegram.ui.ActionBar.BottomSheet sheet) {
        final TextView title = new TextView(context);
        title.setText(getString(R.string.SG_WalletCardGift));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 18, 20, 2));
        final TextView info = new TextView(context);
        info.setText(getString(R.string.SG_WalletCardGiftInfo));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        info.setTextColor(gray());
        column.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 10));
        final android.widget.HorizontalScrollView h = new android.widget.HorizontalScrollView(context);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipToPadding(false);
        h.setPadding(dp(16), 0, dp(16), 0);
        final LinearLayout row = new LinearLayout(context);
        h.addView(row);
        column.addView(h, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 76));
        final org.telegram.ui.Cells.TextCheckCell color = new org.telegram.ui.Cells.TextCheckCell(context, getResourceProvider());
        color.setTextAndCheck(getString(R.string.SG_WalletCardGiftColor), SoleWallet.cardGiftColor(currentAccount), false);
        color.setOnClickListener(v -> {
            final boolean on = !SoleWallet.cardGiftColor(currentAccount);
            SoleWallet.setCardGiftColor(currentAccount, on);
            color.setChecked(on);
            loadCardGift();
        });
        column.addView(color, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        final Runnable fill = () -> {
            row.removeAllViews();
            final String chosen = SoleWallet.cardGift(currentAccount);
            // "none" first
            final TextView none = new TextView(context);
            none.setText(getString(R.string.SG_WalletCardGiftNone));
            none.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            none.setGravity(Gravity.CENTER);
            none.setTextColor(chosen == null ? accent() : gray());
            final GradientDrawable noneBg = new GradientDrawable();
            noneBg.setCornerRadius(dp(16));
            noneBg.setColor(Theme.multAlpha(gray(), 0.12f));
            if (chosen == null) {
                noneBg.setStroke(dp(2), accent());
            }
            none.setBackground(noneBg);
            none.setOnClickListener(v -> {
                SoleWallet.setCardGift(currentAccount, null);
                if (card != null) {
                    card.setGift(null, false);
                    card.spin();
                }
                sheet.dismiss();
            });
            row.addView(none, LayoutHelper.createLinear(68, 68, 4, 0, 4, 0));
            if (myCollectibles == null || myCollectibles.isEmpty()) {
                final TextView empty = new TextView(context);
                empty.setText(getString(myCollectibles == null ? R.string.SG_VaultLoading : R.string.SG_WalletCardGiftEmpty));
                empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                empty.setTextColor(gray());
                empty.setGravity(Gravity.CENTER_VERTICAL);
                row.addView(empty, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 68, 10, 0, 10, 0));
                return;
            }
            for (TL_stars.SavedStarGift g : myCollectibles) {
                final TL_stars.TL_starGiftUnique u = (TL_stars.TL_starGiftUnique) g.gift;
                final org.telegram.ui.Components.BackupImageView image = new org.telegram.ui.Components.BackupImageView(context);
                StarsIntroActivity.setGiftImage(image.getImageReceiver(), g.gift, 56);
                image.setPadding(dp(6), dp(6), dp(6), dp(6));
                final GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(16));
                bg.setColor(Theme.multAlpha(gray(), 0.12f));
                if (u.slug != null && u.slug.equals(chosen)) {
                    bg.setStroke(dp(2), accent());
                }
                image.setBackground(bg);
                image.setOnClickListener(v -> {
                    SoleWallet.setCardGift(currentAccount, u.slug);
                    if (card != null) {
                        card.setGift(g.gift, SoleWallet.cardGiftColor(currentAccount));
                        card.spin();
                    }
                    sheet.dismiss();
                });
                SolegramMotion.pressable(image, SolegramMotion.PRESS_CARD);
                row.addView(image, LayoutHelper.createLinear(68, 68, 4, 0, 4, 0));
            }
        };
        fill.run();
        if (myCollectibles == null) {
            SoleVault.loadGifts(currentAccount, getMessagesController().getInputPeer(getUserConfig().getClientUserId()), gifts -> {
                myCollectibles = collectiblesOf(gifts);
                fill.run();
            });
        }
    }

    /**
     * The screen's root under a clear header: the page goes out of focus under the header (no bar on top), into the
     * page's colour at the very top. Android 12+; elsewhere the header is just clear.
     */
    static class HeaderBlurRoot extends FrameLayout {
        private final com.solegram.messenger.ui.SolegramEdgeBlur headerBlur = new com.solegram.messenger.ui.SolegramEdgeBlur();
        private final View[] pages = new View[2];
        private final android.view.ViewTreeObserver.OnScrollChangedListener onScroll = this::invalidate;
        private final int background;

        HeaderBlurRoot(Context context, int background) {
            super(context);
            this.background = background;
            headerBlur.setHost(this);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnScrollChangedListener(onScroll);
        }

        @Override
        protected void onDetachedFromWindow() {
            getViewTreeObserver().removeOnScrollChangedListener(onScroll);
            super.onDetachedFromWindow();
        }

        private boolean blurDrawn;

        @Override
        protected void dispatchDraw(@androidx.annotation.NonNull Canvas canvas) {
            blurDrawn = false;
            super.dispatchDraw(canvas);
            if (!blurDrawn) {
                drawHeaderBlur(canvas);
            }
        }

        @Override
        protected boolean drawChild(@androidx.annotation.NonNull Canvas canvas, View child, long drawingTime) {
            if (child instanceof ActionBar && !blurDrawn) {
                drawHeaderBlur(canvas); // a header of this root's own stays sharp over the blur
            }
            return super.drawChild(canvas, child, drawingTime);
        }

        private void drawHeaderBlur(Canvas canvas) {
            blurDrawn = true;
            int k = 0;
            for (int i = 0, n = getChildCount(); i < n && k < pages.length; i++) {
                final View child = getChildAt(i);
                if (!(child instanceof ActionBar)) {
                    pages[k++] = child;
                }
            }
            final int height = ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight + dp(12);
            headerBlur.draw(canvas, pages, getMeasuredWidth(), 0, height, true, background, 0, 1f);
            java.util.Arrays.fill(pages, null);
        }
    }
}
