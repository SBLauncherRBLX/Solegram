package org.telegram.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

public class LauncherIconController {
    public static void tryFixLauncherIconIfNeeded() {
        for (LauncherIcon icon : LauncherIcon.values()) {
            if (isEnabled(icon)) {
                return;
            }
        }

        setIcon(LauncherIcon.DEFAULT);
    }

    public static boolean isEnabled(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        int i = ctx.getPackageManager().getComponentEnabledSetting(icon.getComponentName(ctx));
        return i == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || i == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && icon == LauncherIcon.DEFAULT;
    }

    public static void setIcon(LauncherIcon icon) {
        Context ctx = ApplicationLoader.applicationContext;
        PackageManager pm = ctx.getPackageManager();
        for (LauncherIcon i : LauncherIcon.values()) {
            pm.setComponentEnabledSetting(i.getComponentName(ctx), i == icon ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        }
    }

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
        GRAPHITE("GraphiteIcon", R.drawable.sg_icon_bg_graphite, R.drawable.sg_icon_fg_graphite, R.string.SG_IconGraphite);

        public final String key;
        public final int background;
        public final int foreground;
        public final int title;
        public final boolean premium;

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
            this.key = key;
            this.background = background;
            this.foreground = foreground;
            this.title = title;
            this.premium = premium;
        }
    }
}
