package org.telegram.ui.Components.blur3.drawable.color;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.Theme;

public class BlurredBackgroundColorProviderThemed implements BlurredBackgroundColorProvider {

    private final Theme.ResourcesProvider resourcesProvider;
    private final int backgroundColorId;
    private float alpha;

    public BlurredBackgroundColorProviderThemed(Theme.ResourcesProvider resourcesProvider, int backgroundColorId) {
        this(resourcesProvider, backgroundColorId, LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f);
        // Solegram: the glass is as see-through as the "Glass" settings say, like the header's (it used to stay at
        // Telegram's fixed 85% here: the chat's bottom bar was darker than its top)
        followSolegram = true;
        updateColors();
    }

    private boolean followSolegram;

    public BlurredBackgroundColorProviderThemed(Theme.ResourcesProvider resourcesProvider, int backgroundColorId, float alpha) {
        this.resourcesProvider = resourcesProvider;
        this.backgroundColorId = backgroundColorId;
        this.alpha = alpha;

        updateColors();
    }

    public void setAlpha(float alpha) {
        this.alpha = alpha;
        followSolegram = false;
        updateColors();
    }

    private int backgroundColor, shadowColor, strokeColorTop, strokeColorBottom;

    public boolean isDark() {
        final int color = Theme.getColor(backgroundColorId, resourcesProvider);
        return AndroidUtilities.computePerceivedBrightness(color) < .721f;
    }

    public void updateColors() {
        final int color = Theme.getColor(backgroundColorId, resourcesProvider);
        backgroundColor = Theme.multAlpha(color, followSolegram ? com.solegram.messenger.SolegramGlass.alpha(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)) : alpha);

        if (isDark()) {
            strokeColorTop = 0x28FFFFFF;
            strokeColorBottom = 0x14FFFFFF;
            shadowColor = 0;
        } else {
            strokeColorTop = 0xFFFFFFFF;
            strokeColorBottom = 0xFFFFFFFF;
            shadowColor = 0x20000000; //0x19000000;
        }
    }

    @Override
    public int getShadowColor() {
        return shadowColor;
    }

    @Override
    public int getBackgroundColor() {
        if (followSolegram) {
            // read on every draw: a change in the glass settings shows at once
            return Theme.multAlpha(Theme.getColor(backgroundColorId, resourcesProvider), com.solegram.messenger.SolegramGlass.alpha(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)));
        }
        return backgroundColor;
    }

    @Override
    public int getStrokeColorTop() {
        return strokeColorTop;
    }

    @Override
    public int getStrokeColorBottom() {
        return strokeColorBottom;
    }
}

