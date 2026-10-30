package org.telegram.ui.Components.blur3.drawable.color.impl;

import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Color;

import androidx.core.graphics.ColorUtils;
import androidx.core.math.MathUtils;

import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProviderBuilder;

public class BlurredBackgroundProviderImpl {
    public static BlurredBackgroundProvider mainTabs(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> solegramPanelColor(r))
            .setStrokeColorTop(0x11000000, 0x06FFFFFF)
            .setStrokeColorBottom(0x20000000, 0x11FFFFFF)
            .setShadowColor(0x20000000, 0x04FFFFFF)
            .setShadowLayer(dpf2(2.667f), 0, dpf2(0.85f))
            .setStrokeWidth(dpf2(0.4f), dpf2(0.4f))
            .build();
    }

    /**
     * Solegram: the glass sheet of the call screen. The call screen is always dark, so the glass is a light veil
     * over it (as the tab bar is in a dark theme), with the density of the glass settings; tint, highlight and
     * shadow settings are applied by the drawable as everywhere else.
     */
    public static BlurredBackgroundProvider solegramCallSheet(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                final float alpha = com.solegram.messenger.SolegramGlass.alpha(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
                return Theme.multAlpha(0xFF262833, 0.55f * alpha);
            })
            .setStrokeColorTop(0x40FFFFFF, 0x40FFFFFF)
            .setStrokeColorBottom(0x14FFFFFF, 0x14FFFFFF)
            .setShadowColor(0x38000000, 0x38000000)
            .setShadowLayer(dpf2(10f), 0, dpf2(2f))
            .setStrokeWidth(dpf2(1f), dpf2(0.8f))
            .build();
    }

    public static BlurredBackgroundProvider topPanel(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> solegramPanelColor(r))
            .setStrokeColorTop(0x11000000, 0x06FFFFFF)
            .setStrokeColorBottom(0x20000000, 0x11FFFFFF)
            .setShadowColor(0x20000000, 0x04FFFFFF)
            .setShadowLayer(dpf2(2.667f), 0, dpf2(0.85f))
            .setStrokeWidth(dpf2(0.4f), dpf2(0.4f))
            .build();
    }

    public static BlurredBackgroundProvider tabsPanelNoShadow(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) ->
                Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhite, r), 0.88f))
            .setStrokeColorTop(0, 0)
            .setStrokeColorBottom(0, 0)
            .setShadowColor(0, 0)
            .build();
    }

    public static BlurredBackgroundProvider emojiViewButton(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    final float alpha = com.solegram.messenger.SolegramGlass.alpha(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
                    final int colorBg = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                    return Theme.multAlpha(colorBg, alpha);
                })
                .setStrokeColorTop(0xFFFFFFFF, 0x28FFFFFF)
                .setStrokeColorBottom(0xFFFFFFFF, 0x14FFFFFF)
                .setShadowColor(0x40000000, 0)
                .setShadowLayer(dpf2(11 / 3f), 0, dpf2(2 / 3f))
                .setStrokeWidth(dpf2(0.5f), dpf2(0.5f))
                .build();
    }

    public static BlurredBackgroundProvider counterMini(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    final float alpha = 0.075f;
                    final int colorBg = Color.BLACK;
                    return Theme.multAlpha(colorBg, alpha);
                })
                .setStrokeColorTop(0x60FFFFFF, 0x50FFFFFF)
                .setStrokeColorBottom(0x24000000, 0x24000000)
                .setShadowColor(0, 0)
                .setShadowLayer(0, 0, 0)
                .setStrokeWidth(dpf2(0.43f), dpf2(0.43f))
                .build();
    }

    public static BlurredBackgroundProvider messageMenuReactionsBackground(Theme.ResourcesProvider resourcesProvider) {
        return messageMenuBackground(resourcesProvider);
    }

    public static BlurredBackgroundProvider messageMenuBackground(Theme.ResourcesProvider resourcesProvider) {
        // Solegram: every menu has the one menu glass (Telegram's own looks differed from menu to menu)
        return solegramMenu(resourcesProvider, true);
    }

    public static BlurredBackgroundProvider scrimMenuBackground(Theme.ResourcesProvider resourcesProvider) {
        return solegramMenu(resourcesProvider, false); // Solegram: see messageMenuBackground
    }

    /**
     * Solegram: the glass of every popup menu - Telegram's blurred menus, Solegram's glass menus and the liquid
     * take it (recipe in SolegramGlass, region Menus), so density, outline and shadow are the same everywhere.
     *
     * @param opaqueWithoutBlur a solid menu when the chat blur is turned off in the power settings
     */
    public static BlurredBackgroundProvider solegramMenu(Theme.ResourcesProvider resourcesProvider, boolean opaqueWithoutBlur) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                final int color = Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground, r);
                if (opaqueWithoutBlur && !LiteMode.isEnabled(LiteMode.FLAG_CHAT_BLUR)) {
                    return color;
                }
                return com.solegram.messenger.SolegramGlass.menuFill(color, isDark);
            })
            .setStrokeColorTop(com.solegram.messenger.SolegramGlass.MENU_RIM_TOP_LIGHT, com.solegram.messenger.SolegramGlass.MENU_RIM_TOP_DARK)
            .setStrokeColorBottom(com.solegram.messenger.SolegramGlass.menuEdgeBottomRaw(false), com.solegram.messenger.SolegramGlass.menuEdgeBottomRaw(true))
            .setShadowColor(com.solegram.messenger.SolegramGlass.MENU_SHADOW_LIGHT, com.solegram.messenger.SolegramGlass.MENU_SHADOW_DARK)
            .setShadowLayer(dpf2(com.solegram.messenger.SolegramGlass.MENU_SHADOW_RADIUS_DP), 0, dpf2(com.solegram.messenger.SolegramGlass.MENU_SHADOW_DY_DP))
            .setStrokeWidth(dpf2(com.solegram.messenger.SolegramGlass.MENU_RIM_WIDTH_DP), dpf2(com.solegram.messenger.SolegramGlass.MENU_EDGE_BOTTOM_WIDTH_DP))
            .build();
    }

    public static BlurredBackgroundProvider attachMenuSearch(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    final float alpha = com.solegram.messenger.SolegramGlass.alpha(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
                    final int colorBg = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                    return Theme.multAlpha(colorBg, alpha);
                })
                .setStrokeColorTop(0x17000000, 0x17FFFFFF)
                .setStrokeColorBottom(0x17000000, 0x17FFFFFF)
                .setShadowColor(0x11000000, 0x04FFFFFF)
                .setShadowLayer(dpf2(2), 0, dpf2(1 / 3f))
                .setStrokeWidth(dpf2(0.4f), dpf2(0.4f))
                .build();
    }

    public static BlurredBackgroundProvider searchFloatingDate(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> 0x33000000)
                .setStrokeColorTop(0x17000000, 0x17FFFFFF)
                .setStrokeColorBottom(0x17000000, 0x17FFFFFF)
                .setShadowColor(0, 0)
                .setStrokeWidth(1, 1)
                .build();
    }

    public static BlurredBackgroundProvider bottomPanelChatActivity(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    if (!checkBlurEnabled(resourcesProvider)) {
                        return ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_chat_messagePanelBackground, r), 255);
                    }

                    final float alpha = com.solegram.messenger.SolegramGlass.alpha(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
                    final int colorBg = Theme.getColor(Theme.key_chat_messagePanelBackground, r);
                    return Theme.multAlpha(colorBg, alpha);
                })
                .setStrokeColorTop(0xFFFFFFFF, 0x28FFFFFF)
                .setStrokeColorBottom(0xFFFFFFFF, 0x14FFFFFF)
                .setShadowColor(0x20000000, 0)
                //.setShadowLayer(dpf2(10 / 3f), 0, dpf2(2 / 3f))
                .setStrokeWidth(dpf2(0.5f), dpf2(0.5f))
                .build();
    }

    public static BlurredBackgroundProvider topPanelChatActivity(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    if (!checkBlurEnabled(resourcesProvider)) {
                        return ColorUtils.setAlphaComponent(Theme.getColor(isDark ?
                            Theme.key_actionBarDefault : Theme.key_chat_topPanelBackground, r), 255);
                    }

                    final float alpha = com.solegram.messenger.SolegramGlass.alpha(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
                    final int colorBg = Theme.getColor(Theme.key_chat_topPanelBackground, r);
                    return Theme.multAlpha(colorBg, alpha);
                })
                .setStrokeColorTop(0xFFFFFFFF, 0x20FFFFFF)
                .setStrokeColorBottom(0xFFFFFFFF, 0x14FFFFFF)
                .setShadowColor(0x20000000, 0)
                //.setShadowLayer(dpf2(10 / 3f), 0, dpf2(2 / 3f))
                .setStrokeWidth(dpf2(0.55f), dpf2(0.55f))
                .build();
    }

    public static BlurredBackgroundProvider topPanelChatActivityTags(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                if (!checkBlurEnabled(resourcesProvider)) {
                    return ColorUtils.setAlphaComponent(Theme.getColor(isDark ?
                            Theme.key_actionBarDefault : Theme.key_chat_topPanelBackground, r), 255);
                }

                final float alpha = com.solegram.messenger.SolegramGlass.alpha(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
                final int colorBg = Theme.getColor(Theme.key_chat_topPanelBackground, r);
                return Theme.multAlpha(colorBg, alpha);
            })
            .setStrokeColorTop(0, 0)
            .setStrokeColorBottom(0, 0)
            .setShadowColor(0, 0)
            .setShadowLayer(0, 0, 0)
            .setStrokeWidth(0, 0)
            .build();
    }

    public static BlurredBackgroundProvider topPanelChatActivitySearchListBg(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    final float alpha = 0.7f;
                    final int colorBg = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                    return Theme.multAlpha(colorBg, alpha);
                })
                .setStrokeColorTop(0, 0)
                .setStrokeColorBottom(0, 0)
                .setShadowColor(0, 0)
                .setShadowLayer(0, 0, 0)
                .setStrokeWidth(0, 0)
                .build();
    }

    public static BlurredBackgroundProvider bulletin(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) -> {
                final float alpha = com.solegram.messenger.SolegramGlass.alpha(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
                final int colorBg = Theme.getColor(Theme.key_undo_background, r);
                return Theme.multAlpha(colorBg, alpha);
            })
            //.setStrokeColorTop(0xFFFFFFFF, 0x28FFFFFF)
            //.setStrokeColorBottom(0xFFFFFFFF, 0x14FFFFFF)
            //.setShadowColor(0x20000000, 0)
            //.setShadowLayer(dpf2(10 / 3f), 0, dpf2(2 / 3f))
            .setStrokeWidth(dpf2(0.5f), dpf2(0.5f))
            .build();
    }

    public static BlurredBackgroundProvider inputFieldDialogActivity(Theme.ResourcesProvider resourcesProvider) {
        return topPanel(resourcesProvider);
    }

    public static BlurredBackgroundProvider inputFieldShareAlert(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    final float alpha = com.solegram.messenger.SolegramGlass.alpha(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
                    final int colorBg = Theme.getColor(Theme.key_windowBackgroundWhite, r);
                    final int colorTarget = Theme.getColor(Theme.key_chat_messagePanelBackground, r);
                    return solveSrcColor(colorBg, colorTarget, alpha);
                })
                .setStrokeColorTop(0x28FFFFFF, 0x28FFFFFF)
                .setStrokeColorBottom(0x14FFFFFF, 0x14FFFFFF)
                .setShadowColor(0x20000000, 0)
                .setShadowLayer(dpf2(10 / 3f), 0, dpf2(2 / 3f))
                .setStrokeWidth(dpf2(1), dpf2(2 / 3f))
                .build();
    }

    public static BlurredBackgroundProvider photoViewer(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> {
                    final float alpha = com.solegram.messenger.SolegramGlass.alpha(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
                    final int colorBg = 0xFF000000;
                    final int colorTarget = 0xFF1A1A1A;
                    return 0; // solveSrcColor(colorBg, colorTarget, alpha);
                })
                .setStrokeColorTop(0x28FFFFFF, 0x28FFFFFF)
                .setStrokeColorBottom(0x14FFFFFF, 0x14FFFFFF)
                .setStrokeWidth(dpf2(2 / 3f), dpf2(2 / 3f))
                .build();
    }

    public static BlurredBackgroundProvider photoViewerMenu(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
                .setBackgroundColor((r, isDark) -> 0x40000000)
                .setStrokeColorTop(0x28FFFFFF, 0x28FFFFFF)
                .setStrokeColorBottom(0x14FFFFFF, 0x14FFFFFF)
                .setStrokeWidth(dpf2(2 / 3f), dpf2(2 / 3f))
                .build();
    }

    public static BlurredBackgroundProvider premiumButton(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setBackgroundColor((r, isDark) ->
                Theme.multAlpha(Theme.getColor(Theme.key_dialogBackground, r), 0.78f))
            .setStrokeColorTop(0xFFFFFFFF, 0x20FFFFFF)
            .setStrokeColorBottom(0, 0x20FFFFFF)
            .setShadowColor(0x48000000, 0x04FFFFFF)
            .setShadowLayer(dpf2(2 / 3f), 0, 0)
            .setStrokeWidth(dpf2(0.67f), dpf2(0.67f))
            .build();
    }

    public static BlurredBackgroundProvider shadow(Theme.ResourcesProvider resourcesProvider) {
        return new BlurredBackgroundProviderBuilder(resourcesProvider)
            .setStrokeColorTop(0, 0x28FFFFFF)
            .setStrokeColorBottom(0, 0x14FFFFFF)
            .setShadowColor(0x30000000, 0x04FFFFFF)
            .setShadowLayer(dpf2(12 / 3f), 0, dpf2(1 / 3f))
            .setStrokeWidth(dpf2(0.4f), dpf2(0.4f))
            .build();
    }

    /**
     * Solegram: the tab bar, the search field and the header panels of the chats screen are the same glass as
     * every other one: the page colour at the glass density. Telegram aimed them at their own "target" colours,
     * which in many themes (dark ones, Monet) are lighter or greyer than the page, and turned out grey.
     */
    private static int solegramPanelColor(Theme.ResourcesProvider r) {
        final float alpha = com.solegram.messenger.SolegramGlass.alpha(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
        return Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhite, r), alpha);
    }

    public static int solveSrcColor(int bgColor, int outColor, float alpha) {
        alpha = MathUtils.clamp(alpha, 0, 1);

        // Edge cases
        if (alpha <= 0f) {
            return Color.argb(0, 0, 0, 0);
        }
        if (alpha >= 1f) {
            return Color.argb(255, Color.red(outColor), Color.green(outColor), Color.blue(outColor));
        }

        final int bgR = Color.red(bgColor);
        final int bgG = Color.green(bgColor);
        final int bgB = Color.blue(bgColor);

        final int outR = Color.red(outColor);
        final int outG = Color.green(outColor);
        final int outB = Color.blue(outColor);

        final float invA = 1f - alpha;

        final int srcR = MathUtils.clamp(Math.round((outR - bgR * invA) / alpha), 0, 255);
        final int srcG = MathUtils.clamp(Math.round((outG - bgG * invA) / alpha), 0, 255);
        final int srcB = MathUtils.clamp(Math.round((outB - bgB * invA) / alpha), 0, 255);

        final int a8 = MathUtils.clamp(Math.round(alpha * 255f), 0, 255);

        return Color.argb(a8, srcR, srcG, srcB);
    }

    public static boolean checkBlurEnabled(Theme.ResourcesProvider resourcesProvider) {
        return checkBlurEnabled(UserConfig.selectedAccount, resourcesProvider);
    }

    public static boolean checkBlurEnabled(int currentAccount, Theme.ResourcesProvider resourcesProvider) {
        final boolean isDark = resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark();
        final boolean isLight = !isDark;
        boolean blurEnabled = SharedConfig.chatBlurEnabled();
        if (blurEnabled && isLight) {
            // Solegram: frosted headers keep their blur in the light theme too
            if (!com.solegram.messenger.SolegramConfig.frostedHeaders && MessagesController.getInstance(currentAccount).config.disableBlurInLightTheme.get()) {
                blurEnabled = false;
            }
        }
        if (blurEnabled && isDark) {
            if (MessagesController.getInstance(currentAccount).config.disableBlurInDarkTheme.get()) {
                blurEnabled = false;
            }
        }
        return blurEnabled;
    }
}
