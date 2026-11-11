/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.SparseIntArray;

import org.telegram.messenger.FileLog;

/**
 * Icon packs: Telegram's icons, or thin (Phosphor light) or filled (Phosphor fill) ones.
 * <p>
 * The swap happens where drawables load: while a pack is on, the app's and the main activity's Resources are
 * wrapped and {@code getDrawable} turns a Telegram id into the pack's id. Every way Telegram loads an icon ends
 * there ({@code Resources.getDrawable}, {@code Context.getDrawable}, {@code ContextCompat}/{@code ResourcesCompat},
 * {@code ImageView.setImageResource}), so the screens need no changes. With the standard icons nothing is wrapped.
 * Raw reads ({@code BitmapFactory.decodeResource}, notifications, shortcuts, widgets) keep Telegram's files.
 */
public final class SolegramIcons {

    public static final int PACK_STANDARD = 0;
    public static final int PACK_PHOSPHOR = 1;
    public static final int PACK_HUGEICONS = 2;
    public static final int PACK_MINGCUTE = 3;
    public static final int PACK_MAJESTICONS = 4;
    public static final int PACK_SOLAR = 5;
    public static final int PACK_COUNT = 6;

    /** Telegram id -> pack id of the active pack; null with the standard icons. */
    private static volatile SparseIntArray active;
    private static volatile int activePack = -1;
    private static final SparseIntArray[] maps = new SparseIntArray[PACK_COUNT];
    private static SparseIntArray lineMap;
    private static final int[][] tabs = new int[PACK_COUNT][], tabsSelected = new int[PACK_COUNT][];

    /** Called by SolegramConfig whenever settings are read. */
    static void setPack(int pack) {
        if (pack == activePack) {
            return;
        }
        activePack = pack;
        active = pack == PACK_STANDARD ? null : map(pack);
    }

    public static boolean isActive() {
        return active != null;
    }

    private static int[] pairsOf(int pack) {
        switch (pack) {
            case PACK_PHOSPHOR: return SolegramIconMaps.phosphor();
            case PACK_HUGEICONS: return SolegramIconMaps.hugeicons();
            case PACK_MINGCUTE: return SolegramIconMaps.mingcute();
            case PACK_MAJESTICONS: return SolegramIconMaps.majesticons();
            case PACK_SOLAR: return SolegramIconMaps.solar();
            default: return null;
        }
    }

    private static synchronized SparseIntArray map(int pack) {
        if (pack <= PACK_STANDARD || pack >= PACK_COUNT) {
            return null;
        }
        if (maps[pack] == null) {
            maps[pack] = build(pairsOf(pack));
        }
        return maps[pack];
    }

    private static SparseIntArray build(int[] pairs) {
        final SparseIntArray m = new SparseIntArray(pairs.length / 2);
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    /** The id the active pack draws for a Telegram drawable id. */
    public static int map(int id, SparseIntArray m) {
        if (m == null) {
            return id;
        }
        final int r = m.get(id);
        return r != 0 ? r : id;
    }

    /**
     * Resources to hand out for {@code base}: base itself with the standard icons, otherwise a wrapper that
     * swaps the icons. {@code current} is the wrapper handed out last; it is reused while it still matches base
     * (the system may give base new assets, e.g. when the WebView loads: then a fresh wrapper sees them).
     */
    public static Resources wrap(Resources base, Resources current) {
        if (base == null || active == null) {
            return base;
        }
        if (current instanceof Res) {
            final Res r = (Res) current;
            if (r.base == base && r.assets == base.getAssets()) {
                return r;
            }
        }
        try {
            return new Res(base);
        } catch (Throwable e) {
            FileLog.e(e);
            return base;
        }
    }

    /** Keeps a wrapper's configuration in step with its base (orientation, night mode, locale...). */
    public static void onConfigurationChanged(Resources resources) {
        if (resources instanceof Res) {
            ((Res) resources).sync();
        }
    }

    /** Resources without the swap, to draw Telegram's own icons (the standard pack in the picker). */
    public static Resources original(Resources resources) {
        return resources instanceof Res ? ((Res) resources).base : resources;
    }

    // region Settings icon style

    public static final int STYLE_TILES = 0;
    public static final int STYLE_LINES = 1;

    /**
     * Settings icons as thin lines in the theme's colour, without the coloured tiles (every settings screen:
     * Telegram's and Solegram's).
     */
    public static boolean lineStyle() {
        return SolegramConfig.settingsIconStyle == STYLE_LINES;
    }

    /** The colour of the line icons: the theme's accent, as its section headers have it. */
    public static int lineColor(org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider) {
        return org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider);
    }

    /**
     * The colour of a settings row's icon: Telegram's grey icons take the theme's colour with the line style on, so
     * every settings and feature icon is in one colour (red, blue... icons of special rows keep their own).
     */
    public static int iconColor(int colorKey, org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider) {
        if (lineStyle() && (colorKey == org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteGrayIcon || colorKey == org.telegram.ui.ActionBar.Theme.key_dialogIcon)) {
            return lineColor(resourcesProvider);
        }
        return org.telegram.ui.ActionBar.Theme.getColor(colorKey, resourcesProvider);
    }

    /**
     * The colour of an icon in a popup menu: Telegram's grey takes the theme's colour too (with the line style on),
     * a colour a menu item set for itself (red for "Delete") stays.
     */
    public static int menuIconColor(int color, org.telegram.ui.ActionBar.Theme.ResourcesProvider resourcesProvider) {
        if (lineStyle() && color == org.telegram.ui.ActionBar.Theme.getColor(org.telegram.ui.ActionBar.Theme.key_actionBarDefaultSubmenuItemIcon, resourcesProvider)) {
            return lineColor(resourcesProvider);
        }
        return color;
    }

    /** The line glyph for a Telegram icon (Solar's, or the thin one), tinted; Telegram's own when neither pack has it. */
    public static Drawable lineIcon(Context context, int id, int color) {
        final Resources res = original(context.getResources());
        Drawable d;
        try {
            final SparseIntArray line;
            synchronized (SolegramIcons.class) {
                if (lineMap == null) {
                    lineMap = build(SolegramIconMaps.line());
                }
                line = lineMap;
            }
            // Solar's glyph first (soft, rounded, a little larger) unless another pack was chosen; then the thin one
            final int solar = activePack == PACK_SOLAR || activePack == PACK_STANDARD ? map(id, map(PACK_SOLAR)) : id;
            final int own = solar != id ? solar : line.get(id);
            d = res.getDrawable(own != 0 ? own : map(id, map(PACK_PHOSPHOR)));
        } catch (Throwable e) {
            d = null;
        }
        if (d == null) {
            return null;
        }
        d = d.mutate();
        d.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
        return d;
    }
    // endregion

    /** A pack's icon for a Telegram drawable id, whatever pack is on now (the picker's previews). */
    public static Drawable preview(Context context, int pack, int id) {
        final Resources res = original(context.getResources());
        final Drawable d = res.getDrawable(map(id, map(pack)));
        return d == null ? null : d.mutate();
    }

    private static int dotsShift = -1, dotsShiftPack = -1;

    /**
     * How far to the left a message's "more" dots go. Telegram's stand upright, a thin column in the middle of the
     * icon; most packs draw them lying, and those end close to the bubble's edge. Measured from the glyph itself,
     * so its right end keeps the distance Telegram's dots have.
     */
    public static int messageDotsShift(Drawable dots) {
        if (active == null || dots == null) {
            return 0;
        }
        if (dotsShiftPack == activePack && dotsShift >= 0) {
            return dotsShift;
        }
        int shift = 0;
        try {
            final int w = dots.getIntrinsicWidth(), h = dots.getIntrinsicHeight();
            if (w > 0 && h > 0) {
                final android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                final Drawable copy = dots.getConstantState() != null ? dots.getConstantState().newDrawable().mutate() : dots;
                final android.graphics.Rect old = new android.graphics.Rect(copy.getBounds());
                copy.setBounds(0, 0, w, h);
                copy.setAlpha(255);
                copy.draw(new android.graphics.Canvas(bitmap));
                copy.setBounds(old);
                final int[] px = new int[w * h];
                bitmap.getPixels(px, 0, w, 0, 0, w, h);
                bitmap.recycle();
                int right = -1;
                for (int x = w - 1; x >= 0 && right < 0; x--) {
                    for (int y = 0; y < h; y++) {
                        if ((px[y * w + x] >>> 24) > 40) {
                            right = x;
                            break;
                        }
                    }
                }
                if (right >= 0) {
                    // Telegram's column of dots ends about 10dp before the icon's right side
                    final int gap = w - 1 - right;
                    shift = Math.max(0, org.telegram.messenger.AndroidUtilities.dp(10) - gap);
                }
            }
        } catch (Throwable ignore) {
        }
        dotsShift = shift;
        dotsShiftPack = activePack;
        return shift;
    }

    /**
     * The static icon a bottom or attach tab shows instead of its Lottie animation, or 0 with the standard pack
     * (or a tab the pack has no icon for). The selected tab gets the pack's filled glyph.
     */
    public static int tabIcon(String tabName, boolean selected) {
        final int pack = activePack;
        if (pack <= PACK_STANDARD || pack >= PACK_COUNT) {
            return 0;
        }
        final String[] names = SolegramIconMaps.TAB_NAMES;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(tabName)) {
                synchronized (SolegramIcons.class) {
                    if (tabs[pack] == null) {
                        switch (pack) {
                            case PACK_PHOSPHOR: tabs[pack] = SolegramIconMaps.phosphorTabs(); tabsSelected[pack] = SolegramIconMaps.phosphorTabsSelected(); break;
                            case PACK_HUGEICONS: tabs[pack] = SolegramIconMaps.hugeiconsTabs(); tabsSelected[pack] = SolegramIconMaps.hugeiconsTabsSelected(); break;
                            case PACK_MINGCUTE: tabs[pack] = SolegramIconMaps.mingcuteTabs(); tabsSelected[pack] = SolegramIconMaps.mingcuteTabsSelected(); break;
                            case PACK_MAJESTICONS: tabs[pack] = SolegramIconMaps.majesticonsTabs(); tabsSelected[pack] = SolegramIconMaps.majesticonsTabsSelected(); break;
                            default: tabs[pack] = SolegramIconMaps.solarTabs(); tabsSelected[pack] = SolegramIconMaps.solarTabsSelected(); break;
                        }
                    }
                }
                // filled when selected, as Telegram's own tab icons are
                final int filled = selected && tabsSelected[pack] != null && i < tabsSelected[pack].length ? tabsSelected[pack][i] : 0;
                return filled != 0 ? filled : tabs[pack][i];
            }
        }
        return 0;
    }

    /**
     * Resources sharing the base's assets and configuration; only drawable loading differs. The deprecated
     * constructor is the public way to make such a copy (it gets its own ResourcesImpl, kept in step by
     * {@link #sync()} and by forwarding {@link #updateConfiguration} to the base).
     */
    @SuppressWarnings("deprecation")
    static final class Res extends Resources {

        final Resources base;
        final AssetManager assets;

        Res(Resources base) {
            super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
            this.base = base;
            this.assets = base.getAssets();
        }

        @Override
        public Drawable getDrawable(int id) throws NotFoundException {
            return super.getDrawable(map(id, active));
        }

        @Override
        public Drawable getDrawable(int id, Theme theme) throws NotFoundException {
            return super.getDrawable(map(id, active), theme);
        }

        @Override
        public Drawable getDrawableForDensity(int id, int density) throws NotFoundException {
            return super.getDrawableForDensity(map(id, active), density);
        }

        @Override
        public Drawable getDrawableForDensity(int id, int density, Theme theme) {
            return super.getDrawableForDensity(map(id, active), density, theme);
        }

        // readers always see the live values of the base
        @Override
        public Configuration getConfiguration() {
            return base != null ? base.getConfiguration() : super.getConfiguration();
        }

        @Override
        public DisplayMetrics getDisplayMetrics() {
            return base != null ? base.getDisplayMetrics() : super.getDisplayMetrics();
        }

        @Override
        public void updateConfiguration(Configuration config, DisplayMetrics metrics) {
            // the locale change of LocaleController and similar go to both (base is null inside super's constructor)
            if (base != null) {
                base.updateConfiguration(config, metrics);
            }
            super.updateConfiguration(config, metrics);
        }

        void sync() {
            try {
                super.updateConfiguration(base.getConfiguration(), base.getDisplayMetrics());
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    private SolegramIcons() {
    }
}
