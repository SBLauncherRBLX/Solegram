package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.ui.Components.ForegroundDetector;

import java.util.Calendar;

public class LauncherIconController {
    public static void tryFixLauncherIconIfNeeded() {
        boolean any = false;
        for (LauncherIcon icon : LauncherIcon.values()) {
            if (isEnabled(icon)) {
                any = true;
                break;
            }
        }
        if (!any) {
            setIcon(LauncherIcon.DEFAULT);
        }
        // Solegram: the seasonal icon is checked once per start, no background job
        try {
            checkSeasonal();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static boolean isEnabled(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        int i = ctx.getPackageManager().getComponentEnabledSetting(icon.getComponentName(ctx));
        return i == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || i == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == LauncherIcon.DEFAULT;
    }

    /** The user picked an icon: it becomes their own choice, which the seasonal icon gives way to after the season. */
    public static void setIcon(LauncherIcon icon) {
        pendingIcon = null;
        prefs().edit().putString("choice", icon.key).apply();
        applyIcon(icon);
    }

    private static void applyIcon(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        PackageManager pm = ctx.getPackageManager();
        for (LauncherIcon i : LauncherIcon.values()) {
            pm.setComponentEnabledSetting(i.getComponentName(ctx), i == icon ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }

    // region Solegram: seasonal icon

    private static LauncherIcon pendingIcon;
    private static boolean listening;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("solegram_launcher_icon", Context.MODE_PRIVATE);
    }

    public static boolean isSeasonalEnabled() {
        return prefs().getBoolean("seasonal", false);
    }

    public static void setSeasonalEnabled(boolean enabled) {
        prefs().edit().putBoolean("seasonal", enabled).apply();
        checkSeasonal();
    }

    /** Halloween from Oct 25 to Nov 1, New Year from Dec 20 to Jan 10, otherwise null. */
    public static LauncherIcon seasonIcon(Calendar c) {
        int m = c.get(Calendar.MONTH), d = c.get(Calendar.DAY_OF_MONTH);
        if (m == Calendar.OCTOBER && d >= 25 || m == Calendar.NOVEMBER && d <= 1) {
            return LauncherIcon.HALLOWEEN;
        }
        if (m == Calendar.DECEMBER && d >= 20 || m == Calendar.JANUARY && d <= 10) {
            return LauncherIcon.NEW_YEAR;
        }
        return null;
    }

    /** One tag per season ("NewYearIcon-2026" lasts into January 2027), so a season is applied once. */
    private static String seasonTag(Calendar c, LauncherIcon icon) {
        int year = c.get(Calendar.YEAR) - (c.get(Calendar.MONTH) == Calendar.JANUARY ? 1 : 0);
        return icon.key + "-" + year;
    }

    private static LauncherIcon byKey(String key) {
        for (LauncherIcon i : LauncherIcon.values()) {
            if (i.key.equals(key)) {
                return i;
            }
        }
        return null;
    }

    private static LauncherIcon current() {
        for (LauncherIcon i : LauncherIcon.values()) {
            if (isEnabled(i)) {
                return i;
            }
        }
        return LauncherIcon.DEFAULT;
    }

    /**
     * Works out whether the seasonal icon should come or go. The season's icon is put once per season (if the
     * user picks another icon during the season, it stays), and when the season is over (or the option is turned
     * off) the user's own icon comes back, but only if the seasonal one is still there.
     */
    public static void checkSeasonal() {
        SharedPreferences p = prefs();
        String applied = p.getString("applied", "");
        LauncherIcon season = isSeasonalEnabled() ? seasonIcon(Calendar.getInstance()) : null;
        if (season != null) {
            String tag = seasonTag(Calendar.getInstance(), season);
            if (!tag.equals(applied)) {
                LauncherIcon cur = current();
                if (!p.contains("choice") && cur != season) {
                    p.edit().putString("choice", cur.key).apply();
                }
                if (cur != season) {
                    schedule(season);
                }
                p.edit().putString("applied", tag).apply();
            }
        } else if (!applied.isEmpty()) {
            LauncherIcon was = byKey(applied.substring(0, Math.max(0, applied.indexOf('-'))));
            LauncherIcon choice = byKey(p.getString("choice", LauncherIcon.DEFAULT.key));
            if (was != null && was == current() && choice != null && choice != was) {
                schedule(choice);
            } else {
                pendingIcon = null;
            }
            p.edit().remove("applied").apply();
        }
    }

    /**
     * Some launchers restart the app or drop its shortcut when the launcher alias changes, so the switch waits
     * until the app leaves the screen (the same moment Telegram uses for its own launcher changes).
     */
    private static void schedule(LauncherIcon icon) {
        pendingIcon = icon;
        if (listening) {
            return;
        }
        ForegroundDetector detector = ForegroundDetector.getInstance();
        if (detector == null) {
            return;
        }
        listening = true;
        detector.addListener(new ForegroundDetector.Listener() {
            @Override
            public void onBecameForeground() {
            }

            @Override
            public void onBecameBackground() {
                LauncherIcon icon = pendingIcon;
                if (icon == null) {
                    return;
                }
                pendingIcon = null;
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        if (!isEnabled(icon)) {
                            applyIcon(icon);
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }, 300);
            }
        });
    }

    // endregion

    public enum LauncherIcon {
        // Solegram: every launcher slot carries a Solegram palette, none of them is Premium-only.
        DEFAULT("DefaultIcon", R.drawable.sg_icon_bg_sunset, R.drawable.sg_icon_fg_sunset, R.string.SG_IconSunset),
        VINTAGE("VintageIcon", R.drawable.sg_icon_bg_gold, R.drawable.sg_icon_fg_gold, R.string.SG_IconGold),
        AQUA("AquaIcon", R.drawable.sg_icon_bg_ocean, R.drawable.sg_icon_fg_ocean, R.string.SG_IconOcean),
        PREMIUM("PremiumIcon", R.drawable.sg_icon_bg_aurora, R.drawable.sg_icon_fg_aurora, R.string.SG_IconAurora),
        TURBO("TurboIcon", R.drawable.sg_icon_bg_ember, R.drawable.sg_icon_fg_ember, R.string.SG_IconEmber),
        NOX("NoxIcon", R.drawable.sg_icon_bg_midnight, R.drawable.sg_icon_fg_midnight, R.string.SG_IconMidnight),
        // Solegram 1.3.0
        SAKURA("SakuraIcon", R.drawable.sg_icon_bg_sakura, R.drawable.sg_icon_fg_sakura, R.string.SG_IconSakura),
        MINT("MintIcon", R.drawable.sg_icon_bg_mint, R.drawable.sg_icon_fg_mint, R.string.SG_IconMint),
        LAVENDER("LavenderIcon", R.drawable.sg_icon_bg_lavender, R.drawable.sg_icon_fg_lavender, R.string.SG_IconLavender),
        NEON("NeonIcon", R.drawable.sg_icon_bg_neon, R.drawable.sg_icon_fg_neon, R.string.SG_IconNeon),
        SNOW("SnowIcon", R.drawable.sg_icon_bg_snow, R.drawable.sg_icon_fg_snow, R.string.SG_IconSnow),
        GRAPHITE("GraphiteIcon", R.drawable.sg_icon_bg_graphite, R.drawable.sg_icon_fg_graphite, R.string.SG_IconGraphite),
        // Solegram 2.5: themed icons (raster layers in mipmap), shown in their own row of the picker
        HALLOWEEN("HalloweenIcon", R.mipmap.sg_icon_bg_halloween, R.mipmap.sg_icon_fg_halloween, R.string.SG_IconHalloween, false, true),
        NEW_YEAR("NewYearIcon", R.mipmap.sg_icon_bg_newyear, R.mipmap.sg_icon_fg_newyear, R.string.SG_IconNewYear, false, true),
        SPRING("SpringIcon", R.mipmap.sg_icon_bg_spring, R.mipmap.sg_icon_fg_spring, R.string.SG_IconSpring, false, true),
        SUMMER("SummerIcon", R.mipmap.sg_icon_bg_summer, R.mipmap.sg_icon_fg_summer, R.string.SG_IconSummer, false, true),
        SPACE("SpaceIcon", R.mipmap.sg_icon_bg_space, R.mipmap.sg_icon_fg_space, R.string.SG_IconSpace, false, true),
        NOIR("NoirIcon", R.mipmap.sg_icon_bg_noir, R.mipmap.sg_icon_fg_noir, R.string.SG_IconNoir, false, true);

        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final boolean premium;
        public final boolean themed;

        private ComponentName componentName;

        public ComponentName getComponentName(Context ctx) {
            if (componentName == null) {
                componentName = new ComponentName(ctx.getPackageName(), "org.telegram.messenger." + key);
            }
            return componentName;
        }

        LauncherIcon(String key, int background, int foreground, int title) {
            this(key, background, foreground, title, false);
        }

        LauncherIcon(String key, int background, int foreground, int title, boolean premium) {
            this(key, background, foreground, title, premium, false);
        }

        LauncherIcon(String key, int background, int foreground, int title, boolean premium, boolean themed) {
            this.themed = themed;
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.title = title;
            this.premium = premium;
        }
    }
}
