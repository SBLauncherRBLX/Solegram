/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.graphics.Paint;

import androidx.annotation.Nullable;

import com.solegram.messenger.SolegramGlass;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;

/**
 * Glass for liquid outlines ({@link SolegramLiquid.Group}), matching the app's glass: the same tint, outline
 * and shadow settings, over a live glass drawable (menus: {@link SolegramLiveGlass}; buttons over the chat).
 */
public final class SolegramLiquidGlass {

    private SolegramLiquidGlass() {
    }

    /** The group gets the colours of the app's glass (as the tab bar has them). */
    public static void applyColors(SolegramLiquid.Group group, @Nullable Theme.ResourcesProvider resourcesProvider) {
        applyColors(group, BlurredBackgroundProviderImpl.mainTabs(resourcesProvider), Theme.isCurrentThemeDark());
    }

    public static void applyColors(SolegramLiquid.Group group, BlurredBackgroundColorProvider provider, boolean dark) {
        group.colors(
            SolegramGlass.tint(provider.getBackgroundColor()),
            SolegramGlass.menuRimTop(dark), // the one glass outline (SolegramGlass, region Menus)
            SolegramGlass.menuRimBottom(dark),
            SolegramGlass.menuLowEdge(dark),
            SolegramGlass.shadow(dark ? 0x4D000000 : 0x2E000000) // the group draws its own, wider shadow
        );
    }

    /** A plain colour (no blur available): the menu's own background colour. */
    public static SolegramLiquid.GlassFill color(int color) {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        return (canvas, path, bounds) -> canvas.drawPath(path, paint);
    }

    /**
     * Glass from a live drawable (the blur of what scrolls under the tab bar, the live glass of a menu): drawn
     * clipped to the outline. The drawable should have no outline or shadow of its own.
     */
    public static SolegramLiquid.GlassFill live(BlurredBackgroundDrawable drawable) {
        return (canvas, path, bounds) -> {
            canvas.save();
            canvas.clipPath(path);
            drawable.setBounds((int) Math.floor(bounds.left), (int) Math.floor(bounds.top), (int) Math.ceil(bounds.right), (int) Math.ceil(bounds.bottom));
            drawable.draw(canvas);
            canvas.restore();
        };
    }
}
