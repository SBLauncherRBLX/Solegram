/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.solegram.messenger.plugins.PluginGuard;
import com.solegram.messenger.plugins.PluginMeta;
import com.solegram.messenger.plugins.PluginSecurity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/** Row of the plugins list: sticker icon, name, version/author, description and an enable switch. */
public class PluginCell extends FrameLayout {

    private final BackupImageView imageView;
    private final TextView nameView;
    private final TextView infoView;
    private final TextView descriptionView;
    private final Switch switchView;
    private final int currentAccount;
    private boolean divider;
    private String boundIcon;
    /** Color of the security dot on the icon, 0 = not checked yet. */
    private int securityColor;
    private final android.graphics.Paint dotPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    public PluginCell(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.currentAccount = currentAccount;
        setWillNotDraw(false);

        imageView = new BackupImageView(context);
        imageView.setRoundRadius(AndroidUtilities.dp(10));
        addView(imageView, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.TOP, 16, 12, 0, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP, 72, 10, 70, 10));

        nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        texts.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        infoView = new TextView(context);
        infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        infoView.setSingleLine(true);
        infoView.setEllipsize(TextUtils.TruncateAt.END);
        infoView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        texts.addView(infoView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        descriptionView = new TextView(context);
        descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        descriptionView.setMaxLines(3);
        descriptionView.setEllipsize(TextUtils.TruncateAt.END);
        descriptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        texts.addView(descriptionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        switchView = new Switch(context, resourcesProvider);
        switchView.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
        addView(switchView, LayoutHelper.createFrame(37, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 20, 0));
    }

    public void set(PluginMeta meta, boolean divider) {
        this.divider = divider;
        nameView.setText(meta.name != null ? meta.name : meta.id);
        android.text.SpannableStringBuilder info = new android.text.SpannableStringBuilder();
        info.append("v").append(meta.version);
        if (!TextUtils.isEmpty(meta.author)) {
            info.append(" · ").append(meta.author);
        }
        // Solegram: the verdict of the security check, as a dot on the icon and words for anything but safe
        PluginSecurity.Report report = meta.report;
        boolean blocked = meta.blocked != PluginGuard.BLOCK_NONE;
        securityColor = report == null && !blocked ? 0 : PluginSecuritySheet.color(PluginSecuritySheet.verdictColors(report != null ? report.verdict : PluginSecurity.VERDICT_DANGER, meta.blocked == PluginGuard.BLOCK_LIST));
        String status = null;
        if (meta.blocked == PluginGuard.BLOCK_LIST) {
            status = LocaleController.getString(R.string.SG_SecStatusBlocklisted);
        } else if (meta.blocked == PluginGuard.BLOCK_DANGEROUS) {
            status = LocaleController.getString(R.string.SG_SecStatusDangerBlocked);
        } else if (report != null && report.verdict != PluginSecurity.VERDICT_SAFE) {
            status = PluginSecuritySheet.verdictTitle(report.verdict, false);
        }
        if (status != null) {
            info.append(" · ");
            int start = info.length();
            info.append(status);
            info.setSpan(new android.text.style.ForegroundColorSpan(securityColor), start, info.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        infoView.setText(info);
        if (!TextUtils.isEmpty(meta.error)) {
            descriptionView.setText(LocaleController.formatString(R.string.SG_PluginError, meta.error));
            descriptionView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        } else {
            descriptionView.setText(stripMarkdown(meta.description));
            descriptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }
        descriptionView.setVisibility(TextUtils.isEmpty(descriptionView.getText()) ? GONE : VISIBLE);
        switchView.setChecked(meta.enabled, false);
        bindIcon(meta.icon);
        setWillNotDraw(!divider);
    }

    public void setChecked(boolean checked) {
        switchView.setChecked(checked, true);
    }

    private void bindIcon(String icon) {
        if (TextUtils.equals(icon, boundIcon) && boundIcon != null) {
            return;
        }
        boundIcon = icon;
        imageView.clearImage();
        imageView.setImageResource(R.drawable.msg_bots);
        if (TextUtils.isEmpty(icon)) {
            return;
        }
        int slash = icon.lastIndexOf('/');
        if (slash <= 0) {
            return;
        }
        String set = icon.substring(0, slash);
        int index;
        try {
            index = Integer.parseInt(icon.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return;
        }
        MediaDataController.getInstance(currentAccount).setPlaceholderImageByIndex(imageView, set, index, "44_44");
    }

    static String stripMarkdown(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("**", "").replace("__", "").replace("`", "").trim();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (securityColor == 0) {
            return;
        }
        // a status dot on the corner of the icon, ringed with the background like an online badge
        float cx = imageView.getRight() - AndroidUtilities.dp(3);
        float cy = imageView.getBottom() - AndroidUtilities.dp(3);
        dotPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        canvas.drawCircle(cx, cy, AndroidUtilities.dp(7.5f), dotPaint);
        dotPaint.setColor(securityColor);
        canvas.drawCircle(cx, cy, AndroidUtilities.dp(5.5f), dotPaint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (divider) {
            canvas.drawLine(AndroidUtilities.dp(72), getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int min = AndroidUtilities.dp(68);
        if (getMeasuredHeight() < min) {
            setMeasuredDimension(getMeasuredWidth(), min);
        }
    }

    public static class Factory extends UItem.UItemFactory<PluginCell> {
        static {
            setup(new Factory());
        }

        @Override
        public PluginCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new PluginCell(context, currentAccount, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((PluginCell) view).set((PluginMeta) item.object, divider);
        }

        @Override
        public boolean contentsEquals(UItem a, UItem b) {
            return false;
        }

        public static UItem of(PluginMeta meta) {
            UItem item = UItem.ofFactory(Factory.class);
            item.object = meta;
            item.id = meta.id.hashCode();
            return item;
        }
    }
}
