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
    public static final int PACK_THIN = 1;
    public static final int PACK_FILLED = 2;

    /** Telegram id -> pack id of the active pack; null with the standard icons. */
    private static volatile SparseIntArray active;
    private static volatile int activePack = -1;
    private static SparseIntArray thinMap, fillMap, lineMap;
    private static int[] thinTabs, fillTabs;

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

    private static synchronized SparseIntArray map(int pack) {
        if (pack == PACK_THIN) {
            if (thinMap == null) {
                thinMap = build(SolegramIconMaps.thin());
            }
            return thinMap;
        }
        if (pack == PACK_FILLED) {
            if (fillMap == null) {
                fillMap = build(SolegramIconMaps.fill());
            }
            return fillMap;
        }
        return null;
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

    /** The thin (line) glyph for a Telegram icon, whatever pack is on, tinted; Telegram's own when the thin pack has none. */
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
            final int own = line.get(id);
            d = res.getDrawable(own != 0 ? own : map(id, map(PACK_THIN)));
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

    /**
     * The static icon a bottom or attach tab shows instead of its Lottie animation, or 0 with the standard pack
     * (or a tab the pack has no icon for). Selected tabs show the filled glyph in both packs, as on iOS.
     */
    public static int tabIcon(String tabName, boolean selected) {
        final int pack = activePack;
        if (pack != PACK_THIN && pack != PACK_FILLED) {
            return 0;
        }
        final String[] names = SolegramIconMaps.TAB_NAMES;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(tabName)) {
                synchronized (SolegramIcons.class) {
                    if (thinTabs == null) {
                        thinTabs = SolegramIconMaps.thinTabs();
                        fillTabs = SolegramIconMaps.fillTabs();
                    }
                }
                return (selected || pack == PACK_FILLED ? fillTabs : thinTabs)[i];
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
