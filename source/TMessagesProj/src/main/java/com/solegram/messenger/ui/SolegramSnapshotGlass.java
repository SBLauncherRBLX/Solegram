/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.view.View;

import com.solegram.messenger.SolegramGlass;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap;

import java.util.List;

/**
 * The app's glass for popup menus, which open in a window of their own: such a window cannot look into the
 * app's window the way the tab bar's glass does, so the glass is laid over a blurred picture of the screen
 * taken when the menu opens - frosted glass, with the blur and colours of Telegram's blurred menus. Tint,
 * density, outline and shadow come from the colour provider (the one menu glass, see SolegramGlass).
 */
public class SolegramSnapshotGlass {

    public final BlurredBackgroundDrawable drawable;
    private final BlurredBackgroundSourceBitmap bitmapSource = new BlurredBackgroundSourceBitmap();

    public SolegramSnapshotGlass(BlurredBackgroundColorProvider colorProvider) {
        drawable = bitmapSource.createDrawable();
        drawable.setColorProvider(colorProvider);
    }

    /**
     * Takes the blurred picture of the screen under the menu (the menu's own window left out).
     *
     * @return false when no picture could be taken; the menu should then keep its plain look
     */
    public boolean capture(List<View> exclude) {
        // the blur and colours of Telegram's blurred menus, so every menu has the same glass (SolegramGlass, Menus)
        final float downscale = SolegramGlass.MENU_BLUR_DOWNSCALE;
        final int blur = SolegramGlass.menuBlurRadius();
        final boolean[] done = new boolean[1];
        try {
            AndroidUtilities.makeGlobalBlurBitmap(bitmap -> {
                if (bitmap == null) {
                    return;
                }
                final Bitmap shown = AndroidUtilities.applyColorMatrix(bitmap, SolegramGlass.menuBackdropMatrix(Theme.isCurrentThemeDark()));
                if (shown != bitmap) {
                    bitmap.recycle();
                }
                bitmapSource.setBitmap(shown);
                final Matrix matrix = new Matrix();
                matrix.setScale(downscale, downscale);
                bitmapSource.setMatrix(matrix);
                drawable.invalidateSelf();
                done[0] = true;
            }, downscale, blur, null, exclude);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return done[0];
    }
}
