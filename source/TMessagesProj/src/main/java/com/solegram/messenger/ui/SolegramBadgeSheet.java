/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.solegram.messenger.SolegramBadges;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/** What a Solegram badge means: shown on a tap on it next to a name. */
public class SolegramBadgeSheet extends BottomSheet {

    public static void show(BaseFragment fragment, int role) {
        if (fragment == null || fragment.getParentActivity() == null || role == SolegramBadges.NONE) {
            return;
        }
        fragment.showDialog(new SolegramBadgeSheet(fragment, role));
    }

    public static int titleOf(int role) {
        switch (role) {
            case SolegramBadges.DEVELOPER:
                return R.string.SG_BadgeDeveloper;
            case SolegramBadges.TESTER:
                return R.string.SG_BadgeTester;
            default:
                return R.string.SG_BadgeOfficial;
        }
    }

    private static int infoOf(int role) {
        switch (role) {
            case SolegramBadges.DEVELOPER:
                return R.string.SG_BadgeDeveloperInfo;
            case SolegramBadges.TESTER:
                return R.string.SG_BadgeTesterInfo;
            default:
                return R.string.SG_BadgeOfficialInfo;
        }
    }

    private SolegramBadgeSheet(BaseFragment fragment, int role) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        final Context context = fragment.getParentActivity();
        fixNavigationBar();

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(24), dp(24), dp(24), dp(16));

        final ImageView icon = new ImageView(context);
        icon.setImageDrawable(new SolegramBadgeDrawable(role, dp(72)));
        column.addView(icon, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        title.setGravity(Gravity.CENTER);
        title.setText(getString(titleOf(role)));
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(getThemedColor(Theme.key_dialogTextGray2));
        info.setGravity(Gravity.CENTER);
        info.setLineSpacing(dp(2), 1f);
        info.setText(getString(infoOf(role)));
        column.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

        final TextView genuine = new TextView(context);
        genuine.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        genuine.setTextColor(getThemedColor(Theme.key_dialogTextGray3));
        genuine.setGravity(Gravity.CENTER);
        genuine.setText(getString(R.string.SG_BadgeGenuine));
        column.addView(genuine, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        final ButtonWithCounterView button = new ButtonWithCounterView(context, resourcesProvider);
        button.setText(getString(R.string.OK), false);
        button.setOnClickListener(v -> dismiss());
        column.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 22, 0, 0));

        setCustomView(column);
    }
}
