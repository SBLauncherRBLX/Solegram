/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.updater;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BulletSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.Solegram;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

/**
 * Update prompt: version, size, changelog, download progress and install. The "what's new" of an installed
 * update ({@link #showChangelog}) is the same sheet without the download.
 */
public class UpdateSheet extends BottomSheet implements NotificationCenter.NotificationCenterDelegate {

    private final UpdateController.UpdateInfo info;
    private final ButtonWithCounterView button;
    private final TextView laterButton;
    // the download as the button shows it (a fill behind its text)
    private boolean downloading;
    private float downloadProgress;

    public UpdateSheet(BaseFragment fragment, UpdateController.UpdateInfo info) {
        super(fragment.getParentActivity(), false, fragment.getResourceProvider());
        this.info = info;
        Context context = fragment.getParentActivity();
        fixNavigationBar();
        com.solegram.messenger.ui.SolegramMotion.springSheet(this);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, AndroidUtilities.dp(20), 0, AndroidUtilities.dp(8));
        addTop(resourcesProvider, context, layout, LocaleController.getString(R.string.SG_UpdateAvailable),
            LocaleController.formatString(R.string.SG_UpdateVersionLine, info.versionName, info.build, AndroidUtilities.formatFileSize(info.size), Solegram.getVersionName()),
            info.changelog);

        button = new ButtonWithCounterView(context, resourcesProvider) {
            // the download fills the button from the left; the fill glides between the progress updates
            private final AnimatedFloat shownProgress = new AnimatedFloat(this, 0, 420, CubicBezierInterpolator.EASE_OUT_QUINT);
            private final AnimatedFloat shownFill = new AnimatedFloat(this, 0, 320, CubicBezierInterpolator.EASE_OUT_QUINT);
            private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path fillClip = new Path();

            @Override
            protected void onDraw(Canvas canvas) {
                final float shown = shownFill.set(downloading ? 1f : 0f);
                final float progress = shownProgress.set(downloadProgress, !downloading && shown <= 0f);
                if (shown > 0f && progress > 0f && getWidth() > 0) {
                    canvas.save();
                    fillClip.rewind();
                    fillClip.addRoundRect(0, 0, getWidth(), getHeight(), AndroidUtilities.dp(8), AndroidUtilities.dp(8), Path.Direction.CW);
                    canvas.clipPath(fillClip);
                    fillPaint.setColor(Color.argb((int) (0x2E * shown), 255, 255, 255));
                    canvas.drawRect(0, 0, getWidth() * progress, getHeight(), fillPaint);
                    canvas.restore();
                }
                super.onDraw(canvas);
            }
        };
        button.setOnClickListener(v -> onButton());
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 20, 16, 0));

        // the other ways out: quiet text buttons in the accent, rounded like the main one
        final LinearLayout secondary = new LinearLayout(context);
        secondary.setOrientation(LinearLayout.HORIZONTAL);
        TextView browserButton = textButton(resourcesProvider, context, LocaleController.getString(R.string.SG_UpdateInBrowser));
        browserButton.setOnClickListener(v -> UpdateController.getInstance().openInBrowser(context));
        secondary.addView(browserButton, LayoutHelper.createLinear(0, 44, 1f));

        laterButton = textButton(resourcesProvider, context, LocaleController.getString(R.string.SG_SkipVersion));
        secondary.addView(laterButton, LayoutHelper.createLinear(0, 44, 1f, 8, 0, 0, 0));
        layout.addView(secondary, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 16, 8, 16, 0));
        laterButton.setOnClickListener(v -> {
            UpdateController.getInstance().skip(info);
            dismiss();
        });

        setCustomView(layout);
        solegramReadingGlass(); // release notes are read: a denser glass than other sheets
        updateButton(false);
    }

    // region Shared look (the update prompt and the notes of an installed update)

    /** The app's icon, a title, a line under it and the release notes in a soft card. */
    private static void addTop(Theme.ResourcesProvider rp, Context context, LinearLayout layout, String titleText, String subtitleText, String notes) {
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
        logo.setElevation(AndroidUtilities.dp(2));
        layout.addView(logo, LayoutHelper.createLinear(68, 68, Gravity.CENTER_HORIZONTAL, 0, 2, 0, 0));

        TextView title = new TextView(context);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 14, 24, 0));

        TextView subtitle = new TextView(context);
        subtitle.setText(subtitleText);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(AndroidUtilities.dp(1), 1f);
        subtitle.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, rp));
        layout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 4, 24, 0));

        if (TextUtils.isEmpty(notes)) {
            return;
        }
        TextView header = new TextView(context);
        header.setText(LocaleController.getString(R.string.SG_WhatsNew));
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, rp));
        layout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 20, 22, 8));

        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(260), MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final int text = Theme.getColor(Theme.key_dialogTextBlack, rp);
        scroll.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16), ColorUtils.setAlphaComponent(text, Theme.isCurrentThemeDark() ? 0x12 : 0x0A)));
        scroll.setClipToOutline(true);
        TextView changelog = new TextView(context);
        changelog.setText(formatNotes(notes, Theme.getColor(Theme.key_featuredStickers_addButton, rp)));
        changelog.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        changelog.setLineSpacing(AndroidUtilities.dp(3), 1f);
        changelog.setTextColor(text);
        changelog.setTextIsSelectable(true);
        changelog.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        scroll.addView(changelog);
        layout.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 0, 16, 0));
    }

    private static TextView textButton(Theme.ResourcesProvider rp, Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        view.setTypeface(AndroidUtilities.bold());
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton, rp);
        view.setTextColor(accent);
        view.setBackground(Theme.createRadSelectorDrawable(ColorUtils.setAlphaComponent(accent, 0x1F), 10, 10));
        return view;
    }

    /**
     * Release notes as a list: lines starting with "-", "*" or "•" become bullets in the accent with a hanging
     * indent, lines starting with "#" become bold headings.
     */
    static CharSequence formatNotes(String notes, int bulletColor) {
        final SpannableStringBuilder sb = new SpannableStringBuilder();
        final String[] lines = notes.replace("\r", "").split("\n");
        boolean blank = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                blank = sb.length() > 0; // one empty line between paragraphs, however many there were
                continue;
            }
            if (sb.length() > 0) {
                sb.append(blank ? "\n\n" : "\n");
            }
            blank = false;
            final int start = sb.length();
            if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") || line.startsWith("— ")) {
                sb.append(line.substring(2).trim());
                sb.setSpan(new BulletSpan(AndroidUtilities.dp(10), bulletColor), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (line.startsWith("#")) {
                while (line.startsWith("#")) {
                    line = line.substring(1);
                }
                sb.append(line.trim());
                sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.append(line);
            }
        }
        return sb;
    }

    /** "Solegram updated to 2.4.1" and its notes, after the update was installed. */
    public static void showChangelog(BaseFragment fragment, String version, String changelog) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        final Context context = fragment.getParentActivity();
        final BottomSheet sheet = new BottomSheet(context, false, fragment.getResourceProvider());
        sheet.fixNavigationBar();
        com.solegram.messenger.ui.SolegramMotion.springSheet(sheet);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, AndroidUtilities.dp(20), 0, AndroidUtilities.dp(16));
        addTop(fragment.getResourceProvider(), context, layout, LocaleController.formatString(R.string.SG_UpdatedTo, version),
            LocaleController.getString(R.string.SG_UpdatedNotesSub), changelog);
        final com.solegram.messenger.ui.SolegramProfileLinksSheet.ActionButton ok = new com.solegram.messenger.ui.SolegramProfileLinksSheet.ActionButton(context, fragment.getResourceProvider());
        ok.setText(LocaleController.getString(R.string.GotIt), false);
        ok.setOnClickListener(v -> sheet.dismiss());
        layout.addView(ok, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 20, 16, 0));
        sheet.setCustomView(layout);
        sheet.solegramReadingGlass(); // notes to read: the denser glass of the update sheet
        fragment.showDialog(sheet);
    }
    // endregion

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
        downloading = !uc.isDownloaded(info) && uc.isDownloading(info);
        downloadProgress = downloading ? Math.max(0f, Math.min(1f, uc.getDownloadProgress())) : 0f;
        button.invalidate();
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
