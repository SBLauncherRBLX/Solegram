/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.solegram.messenger.SolegramGlass;
import com.solegram.messenger.SolegramMeaningSearch;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;

/**
 * "✦ Искать по смыслу «...»": a glass pill at the top of the chat list search while something is typed. A tap
 * opens {@link SolegramMeaningSearchActivity} with the typed question. It floats over the results (the list is
 * given room for it), in the same glass as the search tabs, or a calm solid pill where glass is off.
 */
@SuppressLint("ViewConstructor")
public class SolegramMeaningPill extends FrameLayout {

    /** Height of the pill with its glass padding, and the room it takes above the results. */
    public static final int HEIGHT_DP = 46;
    public static final int SPACE_DP = HEIGHT_DP + 6;
    private static final int GLASS_PADDING_DP = 3;

    private final Utilities.Callback0Return<BlurredBackgroundDrawableViewFactory> factory;
    private final Utilities.Callback<String> onOpen;
    private final Utilities.Callback<Boolean> onShownChanged;
    private final LinearLayout content;
    private final TextView star;
    private final TextView title;
    private final TextView query;
    private BlurredBackgroundDrawable glass;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private String text;
    private boolean wanted;
    private int rimHeight;
    private boolean rimDark;

    /**
     * @param factory        the glass of the search screen (may give null while it is not set up)
     * @param onOpen         the question to search by meaning
     * @param onShownChanged the pill came (true) or went: the results make room for it or take it back
     */
    public SolegramMeaningPill(Context context, Utilities.Callback0Return<BlurredBackgroundDrawableViewFactory> factory,
                               Utilities.Callback<String> onOpen, Utilities.Callback<Boolean> onShownChanged) {
        super(context);
        this.factory = factory;
        this.onOpen = onOpen;
        this.onShownChanged = onShownChanged;
        setWillNotDraw(false);
        setVisibility(GONE);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(16), 0, dp(18), 0);
        addView(content, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        star = new TextView(context);
        star.setText("✦");
        star.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        star.setTypeface(AndroidUtilities.bold());
        content.addView(star, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 7, 0));

        title = new TextView(context);
        title.setText(LocaleController.getString(R.string.SG_MeaningPill));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        query = new TextView(context);
        query.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        query.setSingleLine(true);
        query.setEllipsize(TextUtils.TruncateAt.END);
        content.addView(query, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));

        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1, dp(0.66f)));
        setOnClickListener(v -> {
            if (!TextUtils.isEmpty(text)) {
                SolegramMotion.haptic(this);
                onOpen.run(text);
            }
        });
        SolegramMotion.pressable(this, SolegramMotion.PRESS_BUTTON);
        updateColors();
    }

    /** What is typed in the search now (null: nothing, or a search the pill does not fit, as one inside a chat). */
    public void setQuery(@Nullable String q) {
        final String trimmed = q == null ? "" : q.trim();
        // hashtags, usernames and links are found exactly by Telegram's own search
        final boolean show = SolegramMeaningSearch.enabled() && trimmed.length() >= 2 && trimmed.charAt(0) != '#' && trimmed.charAt(0) != '@'
            && !trimmed.contains("://");
        if (show) {
            text = trimmed;
            query.setText("«" + trimmed + "»");
        }
        if (show != wanted) {
            wanted = show;
            if (show) {
                SolegramMeaningSearch.preload();
            }
            SolegramMotion.setVisible(this, show, true);
            onShownChanged.run(show);
        }
    }

    public boolean isWanted() {
        return wanted;
    }

    public void updateColors() {
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        star.setTextColor(accent);
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        query.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        if (glass != null) {
            glass.updateColors();
        }
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // the question gets what the title leaves, so a long one ends with "…" instead of pushing the pill wider
        final int max = MeasureSpec.getSize(widthMeasureSpec) - dp(16 + 18 + 7 + 6) - dp(24);
        title.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        star.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        query.setMaxWidth(Math.max(dp(40), max - title.getMeasuredWidth() - star.getMeasuredWidth()));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        final float pad = dp(GLASS_PADDING_DP);
        // the pill is as wide as what it says, centred
        final float w = Math.min(getWidth(), content.getWidth() + pad * 2);
        final float left = (getWidth() - w) / 2f;
        if (glass == null && factory != null) {
            final BlurredBackgroundDrawableViewFactory f = factory.run();
            if (f != null) {
                glass = f.create(this, BlurredBackgroundProviderImpl.topPanel(null));
                glass.setPadding((int) pad);
            }
        }
        if (glass != null) {
            glass.setRadius((getHeight() - pad * 2) / 2f);
            glass.setBounds((int) left, 0, (int) (left + w), getHeight());
            glass.draw(canvas);
        } else {
            // no glass on this screen: a solid pill with the light edge Solegram's glass has
            final boolean dark = Theme.isCurrentThemeDark();
            rect.set(left + pad, pad, left + w - pad, getHeight() - pad);
            final float r = rect.height() / 2f;
            fill.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            fill.setShadowLayer(dp(4), 0, dp(1), SolegramGlass.menuShadow(dark));
            canvas.drawRoundRect(rect, r, r, fill);
            if (rimHeight != getHeight() || rimDark != dark) {
                rimHeight = getHeight();
                rimDark = dark;
                rim.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, SolegramGlass.menuRimTop(dark), SolegramGlass.menuRimBottom(dark), Shader.TileMode.CLAMP));
            }
            canvas.drawRoundRect(rect, r, r, rim);
        }
        super.onDraw(canvas);
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        // a touch on the glass lights it up a little, as the tab bar does
        content.setAlpha(pressed ? 0.75f : 1f);
    }

    @Override
    public boolean hasOverlappingRendering() {
        return true;
    }

    /** Hides it without animation (search closed). */
    public void reset() {
        text = null;
        if (wanted) {
            wanted = false;
            onShownChanged.run(false);
        }
        animate().cancel();
        setVisibility(View.GONE);
    }
}
