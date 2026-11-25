/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.solegram.messenger.SolegramConfig;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stars.StarsController;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;

/**
 * Solegram (betas): which test gifts float on the own profile and how many. A grid of Telegram's gift catalog: the
 * picked ones go first, in the order picked (their number on the tile); the count cuts or fills up the rest.
 */
public final class SolegramTestGiftsSheet {

    private SolegramTestGiftsSheet() {
    }

    public static void show(BaseFragment fragment) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) {
            return;
        }
        final StarsController stars = StarsController.getInstance(fragment.getCurrentAccount());
        final ArrayList<TL_stars.StarGift> catalog = new ArrayList<>();
        for (TL_stars.StarGift g : stars.gifts) {
            if (g != null && g.getDocument() != null) {
                catalog.add(g);
            }
        }
        if (catalog.isEmpty()) {
            stars.loadStarGifts();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.gift, getString(R.string.SG_TestGiftsLoading)).show();
            return;
        }
        final ArrayList<Long> picked = new ArrayList<>(SolegramConfig.devTestGiftIdList());
        final int[] count = {SolegramConfig.devTestGiftCount};

        final BottomSheet sheet = new BottomSheet(activity, false);
        sheet.fixNavigationBar();
        final LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(8), 0, dp(10));

        final TextView title = new TextView(activity);
        title.setText(getString(R.string.SG_TestGiftsPick));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 6, 20, 2));
        final TextView info = new TextView(activity);
        info.setText(getString(R.string.SG_TestGiftsPickInfo));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setGravity(Gravity.CENTER);
        info.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        column.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 12));

        // how many: − N +
        final LinearLayout counter = new LinearLayout(activity);
        counter.setOrientation(LinearLayout.HORIZONTAL);
        counter.setGravity(Gravity.CENTER_VERTICAL);
        final TextView countLabel = new TextView(activity);
        countLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        countLabel.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        final TextView minus = roundButton(activity, "−"), plus = roundButton(activity, "+");
        counter.addView(countLabel, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        counter.addView(minus, LayoutHelper.createLinear(40, 40, 0, 0, 10, 0));
        counter.addView(plus, LayoutHelper.createLinear(40, 40));
        column.addView(counter, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 10));
        final Runnable updateCount = () -> countLabel.setText(LocaleController.formatString(R.string.SG_TestGiftsCount, count[0]));
        updateCount.run();
        minus.setOnClickListener(v -> {
            count[0] = Math.max(1, count[0] - 1);
            updateCount.run();
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        });
        plus.setOnClickListener(v -> {
            count[0] = Math.min(9, count[0] + 1);
            updateCount.run();
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        });

        // the catalog
        final ScrollView scroll = new ScrollView(activity);
        final LinearLayout grid = new LinearLayout(activity);
        grid.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(grid);
        final ArrayList<Tile> tiles = new ArrayList<>();
        LinearLayout row = null;
        for (int i = 0; i < catalog.size(); i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 84));
            }
            final TL_stars.StarGift g = catalog.get(i);
            final Tile tile = new Tile(activity, g, picked);
            tiles.add(tile);
            tile.setOnClickListener(v -> {
                if (picked.contains(g.id)) {
                    picked.remove(g.id);
                } else {
                    picked.add(g.id);
                    if (picked.size() > count[0]) {
                        count[0] = Math.min(9, picked.size());
                        updateCount.run();
                    }
                }
                for (Tile t : tiles) t.invalidate();
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            });
            row.addView(tile, LayoutHelper.createLinear(0, 84, 1f));
        }
        column.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 340, 12, 0, 12, 0));

        final LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final ButtonWithCounterView reset = new ButtonWithCounterView(activity, false, null);
        reset.setText(getString(R.string.SG_TestGiftsReset), false);
        final ButtonWithCounterView done = new ButtonWithCounterView(activity, null);
        done.setText(getString(R.string.Done), false);
        buttons.addView(reset, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 5, 0));
        buttons.addView(done, LayoutHelper.createLinear(0, 48, 1f, 5, 0, 0, 0));
        column.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 14, 12, 14, 0));
        reset.setOnClickListener(v -> {
            picked.clear();
            for (Tile t : tiles) t.invalidate();
        });
        done.setOnClickListener(v -> {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < picked.size() && i < 9; i++) {
                if (sb.length() > 0) sb.append(',');
                sb.append(picked.get(i));
            }
            SolegramConfig.prefs().edit().putInt("devTestGiftCount", count[0]).putString("devTestGiftIds", sb.toString()).apply();
            SolegramConfig.putBoolean("devTestGifts", true);
            sheet.dismiss();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.gift, getString(R.string.SG_TestGiftsSaved)).show();
        });
        sheet.setCustomView(column);
        sheet.show();
    }

    private static TextView roundButton(Activity activity, String text) {
        final TextView v = new TextView(activity);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        v.setBackground(Theme.createRoundRectDrawable(dp(20), Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), 0.12f)));
        return v;
    }

    /** A gift of the catalog: its sticker; when picked, a ring and its place in the order. */
    private static final class Tile extends FrameLayout {
        private final TL_stars.StarGift gift;
        private final ArrayList<Long> picked;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint number = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        Tile(Activity activity, TL_stars.StarGift gift, ArrayList<Long> picked) {
            super(activity);
            this.gift = gift;
            this.picked = picked;
            setWillNotDraw(false);
            final BackupImageView image = new BackupImageView(activity);
            final TLRPC.Document doc = gift.getDocument();
            image.setImage(ImageLocation.getForDocument(doc), "60_60", null, 0, gift);
            addView(image, LayoutHelper.createFrame(56, 56, Gravity.CENTER));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2.5f));
            number.setTextSize(dp(12));
            number.setTypeface(AndroidUtilities.bold());
            number.setColor(0xFFFFFFFF);
        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            final int index = picked.indexOf(gift.id);
            final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
            if (index >= 0) {
                badge.setColor(Theme.multAlpha(accent, 0.12f));
                canvas.drawRoundRect(dp(4), dp(4), getWidth() - dp(4), getHeight() - dp(4), dp(16), dp(16), badge);
            }
            super.dispatchDraw(canvas);
            if (index >= 0) {
                ring.setColor(accent);
                canvas.drawRoundRect(dp(4), dp(4), getWidth() - dp(4), getHeight() - dp(4), dp(16), dp(16), ring);
                badge.setColor(accent);
                final float cx = getWidth() - dp(16), cy = dp(16);
                canvas.drawCircle(cx, cy, dp(10), badge);
                final String s = String.valueOf(index + 1);
                canvas.drawText(s, cx - number.measureText(s) / 2f, cy + dp(4.3f), number);
            }
        }
    }
}
