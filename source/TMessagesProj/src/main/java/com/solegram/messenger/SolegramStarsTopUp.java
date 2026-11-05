/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.content.Context;
import android.content.Intent;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

/**
 * When Telegram doesn't sell Stars in this app (its server says so, by country or by app), this says so plainly and
 * shows Telegram's own ways to top up: Fragment, or the official app on the same account (the balance is the
 * account's, so Stars bought there are here too).
 */
public final class SolegramStarsTopUp {

    private static final String FRAGMENT_STARS = "https://fragment.com/stars/buy";
    private static final String[] OFFICIAL = {"org.telegram.messenger", "org.telegram.messenger.web"};

    private SolegramStarsTopUp() {
    }

    private static Intent officialApp(Context context) {
        for (String p : OFFICIAL) {
            try {
                final Intent i = context.getPackageManager().getLaunchIntentForPackage(p);
                if (i != null) {
                    return i;
                }
            } catch (Exception ignore) {
            }
        }
        return null;
    }

    public static void show(Context context, Theme.ResourcesProvider resourcesProvider) {
        if (context == null) {
            return;
        }
        final Intent official = officialApp(context);
        final AlertDialog.Builder b = new AlertDialog.Builder(context, resourcesProvider)
            .setTitle(LocaleController.getString(R.string.SG_TopUpTitle))
            .setMessage(LocaleController.getString(official != null ? R.string.SG_TopUpTextOfficial : R.string.SG_TopUpText))
            .setPositiveButton(LocaleController.getString(R.string.SG_TopUpFragment), (d, w) -> Browser.openUrl(context, FRAGMENT_STARS))
            .setNegativeButton(LocaleController.getString(R.string.Close), null);
        if (official != null) {
            b.setNeutralButton(LocaleController.getString(R.string.SG_TopUpOfficial), (d, w) -> {
                try {
                    official.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(official);
                } catch (Exception ignore) {
                }
            });
        }
        b.show();
    }
}
