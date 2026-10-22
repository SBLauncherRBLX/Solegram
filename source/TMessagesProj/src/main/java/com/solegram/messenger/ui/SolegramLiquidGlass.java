/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.Nullable;

import com.solegram.messenger.SolegramGlass;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap;

import java.util.List;

/**
 * Glass for liquid outlines ({@link SolegramLiquid.Group}), matching the app's glass: the same tint, outline
 * and shadow settings, over a blurred picture of the screen (popups and menus, which open over a still
 * screen) or over a live blurred drawable (buttons that stay on screen while it scrolls).
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
     * Frosted glass over a blurred picture of the screen, taken now (the host's own window, or views in
     * {@code exclude}, left out). Null when no picture could be taken.
     */
    @Nullable
    public static SolegramLiquid.GlassFill snapshot(View host, @Nullable List<View> exclude, @Nullable Theme.ResourcesProvider resourcesProvider) {
        final SolegramSnapshotGlass glass = new SolegramSnapshotGlass(BlurredBackgroundProviderImpl.solegramMenu(resourcesProvider, false));
        if (!glass.capture(exclude)) {
            return null;
        }
        if (!(glass.drawable.getSource() instanceof BlurredBackgroundSourceBitmap)) {
            return null;
        }
        final BlurredBackgroundSourceBitmap source = (BlurredBackgroundSourceBitmap) glass.drawable.getSource();
        final Bitmap bitmap = source.getBitmap();
        if (bitmap == null) {
            return null;
        }
        return new SnapshotFill(host, bitmap, source.getMatrix());
    }

    /** Fills with the picture of the screen, placed where the host is on screen now. */
    private static final class SnapshotFill implements SolegramLiquid.GlassFill {
        private final View host;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final BitmapShader shader;
        private final Matrix base = new Matrix(), matrix = new Matrix();
        private final int[] location = new int[2];

        SnapshotFill(View host, Bitmap bitmap, Matrix screenMatrix) {
            this.host = host;
            shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            base.set(screenMatrix);
            paint.setShader(shader);
        }

        @Override
        public void fill(Canvas canvas, Path path, RectF bounds) {
            host.getLocationOnScreen(location);
            matrix.set(base);
            matrix.postTranslate(-location[0], -location[1]);
            shader.setLocalMatrix(matrix);
            canvas.drawPath(path, paint);
        }
    }

    /**
     * Frosted glass from a live blurred drawable (the blur of what scrolls under the tab bar, say): drawn
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
