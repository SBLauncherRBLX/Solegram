/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.updater;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.solegram.messenger.Solegram;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/** Update prompt: version, size, changelog, download progress and install. */
public class UpdateSheet extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    private final UpdateController.UpdateInfo info;
    private final ButtonWithCounterView button;
    private final TextView laterButton;

    public UpdateSheet(BaseFragment fragment, UpdateController.UpdateInfo info) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        this.info = info;
        Context context = fragment.getParentActivity();
        fixNavigationBar();

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, AndroidUtilities.dp(20), 0, AndroidUtilities.dp(8));

        ImageView logo = new ImageView(context);
        Drawable bg = ContextCompat.getDrawable(context, R.drawable.sg_icon_bg_sunset);
        Drawable fg = ContextCompat.getDrawable(context, R.drawable.sg_icon_fg_sunset);
        logo.setImageDrawable(new LayerDrawable(new Drawable[]{bg, fg}));
        logo.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getWidth() * 0.28f);
            }
        });
        logo.setClipToOutline(true);
        layout.addView(logo, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.SG_UpdateAvailable));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 14, 24, 0));

        TextView subtitle = new TextView(context);
        subtitle.setText(LocaleController.formatString(R.string.SG_UpdateVersionLine, info.versionName, info.build, AndroidUtilities.formatFileSize(info.size), Solegram.getVersionName()));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTextColor(getThemedColor(Theme.key_dialogTextGray3));
        layout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 0));

        if (!TextUtils.isEmpty(info.changelog)) {
            TextView header = new TextView(context);
            header.setText(LocaleController.getString(R.string.SG_WhatsNew));
            header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            header.setTypeface(AndroidUtilities.bold());
            header.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
            layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 18, 24, 6));

            ScrollView scroll = new ScrollView(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(240), MeasureSpec.AT_MOST));
                }
            };
            TextView changelog = new TextView(context);
            changelog.setText(info.changelog);
            changelog.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            changelog.setLineSpacing(AndroidUtilities.dp(2), 1f);
            changelog.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            changelog.setTextIsSelectable(true);
            scroll.addView(changelog);
            layout.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 0));
        }

        button = new ButtonWithCounterView(context, resourcesProvider);
        button.setOnClickListener(v -> onButton());
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 18, 16, 0));

        TextView browserButton = new TextView(context);
        browserButton.setText(LocaleController.getString(R.string.SG_UpdateInBrowser));
        browserButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        browserButton.setGravity(Gravity.CENTER);
        browserButton.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        browserButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
        browserButton.setOnClickListener(v -> UpdateController.getInstance().openInBrowser(context));
        layout.addView(browserButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 16, 6, 16, 0));

        laterButton = new TextView(context);
        laterButton.setText(LocaleController.getString(R.string.SG_SkipVersion));
        laterButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        laterButton.setGravity(Gravity.CENTER);
        laterButton.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
        laterButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
        laterButton.setOnClickListener(v -> {
            UpdateController.getInstance().skip(info);
            dismiss();
        });
        layout.addView(laterButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 16, 0, 16, 0));

        setCustomView(layout);
        solegramReadingGlass(); // release notes are read: a denser glass than other sheets
        updateButton(false);
    }

    private void onButton() {
        UpdateController uc = UpdateController.getInstance();
        if (uc.isDownloaded(info)) {
            uc.install(info);
        } else if (uc.isDownloading(info)) {
            uc.cancelDownload(info);
        } else {
            uc.download(info);
        }
        updateButton(true);
    }

    private void updateButton(boolean animated) {
        UpdateController uc = UpdateController.getInstance();
        if (uc.isDownloaded(info)) {
            button.setText(LocaleController.getString(R.string.SG_InstallUpdate), animated);
        } else if (uc.isDownloading(info)) {
            int percent = Math.round(uc.getDownloadProgress() * 100);
            button.setText(LocaleController.formatString(R.string.SG_Downloading, percent), animated);
        } else if (uc.isDownloadFailed()) {
            button.setText(LocaleController.getString(R.string.SG_DownloadRetry), animated);
        } else {
            button.setText(LocaleController.formatString(R.string.SG_DownloadUpdate, AndroidUtilities.formatFileSize(info.size)), animated);
        }
    }

    @Override
    public void show() {
        super.show();
        if (isShowing()) { // show() quietly does nothing for a finishing activity
            NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.solegramUpdateAvailable);
        }
    }

    @Override
    public void dismiss() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.solegramUpdateAvailable);
        super.dismiss();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.solegramUpdateAvailable) {
            updateButton(true);
        }
    }
}
