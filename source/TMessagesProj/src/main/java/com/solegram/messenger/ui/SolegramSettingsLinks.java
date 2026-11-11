/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Solegram's settings where Telegram keeps the same kind of settings: a "Solegram" block at the top of Telegram's
 * Chat settings, Privacy and security, Notifications and Data screens, each row opening Solegram's page. One row of
 * the screen's list holds the whole block, so the screens only need a row index and this view type.
 */
public final class SolegramSettingsLinks {

    /** The list view type of the block; Telegram's screens use small numbers. */
    public static final int VIEW_TYPE = 9701;

    public static final int CHATS = 1, PRIVACY = 2, NOTIFICATIONS = 3, DATA = 4;

    private SolegramSettingsLinks() {
    }

    private static final class Link {
        final int icon, title;
        final Runnable open;

        Link(int icon, int title, Runnable open) {
            this.icon = icon;
            this.title = title;
            this.open = open;
        }
    }

    private static ArrayList<Link> links(BaseFragment f, int where) {
        final ArrayList<Link> l = new ArrayList<>();
        switch (where) {
            case CHATS:
                l.add(new Link(R.drawable.msg_theme, R.string.SG_Appearance, () -> f.presentFragment(new SolegramSettingsActivity(SolegramSettingsActivity.PAGE_APPEARANCE))));
                l.add(new Link(R.drawable.msg_customize, R.string.SG_GlassSettings, () -> f.presentFragment(new SolegramSettingsActivity(SolegramSettingsActivity.PAGE_GLASS))));
                l.add(new Link(R.drawable.msg_home, R.string.SG_MainScreen, () -> f.presentFragment(new SolegramSettingsActivity(SolegramSettingsActivity.PAGE_MAIN_SCREEN))));
                l.add(new Link(R.drawable.msg_discussion, R.string.SG_Chats, () -> f.presentFragment(new SolegramSettingsActivity(SolegramSettingsActivity.PAGE_CHATS))));
                l.add(new Link(R.drawable.msg_archive_hide, R.string.SG_Filters, () -> f.presentFragment(new SolegramFiltersActivity())));
                break;
            case PRIVACY:
                l.add(new Link(R.drawable.msg_secret, R.string.SG_Privacy, () -> f.presentFragment(new SolegramSettingsActivity(SolegramSettingsActivity.PAGE_GHOST))));
                l.add(new Link(R.drawable.outline_shield_plain_24, R.string.SG_Scam, () -> f.presentFragment(new SolegramSettingsActivity(SolegramSettingsActivity.PAGE_SCAM))));
                l.add(new Link(R.drawable.outline_shield_check, R.string.SG_Security, () -> f.presentFragment(new SolegramSecurityActivity(false))));
                break;
            case NOTIFICATIONS:
                l.add(new Link(R.drawable.msg_notifications, R.string.SG_Reminders, () -> f.presentFragment(new SolegramRemindersActivity())));
                l.add(new Link(R.drawable.msg2_battery, R.string.SG_BackgroundAndSound, () -> f.presentFragment(new SolegramSettingsActivity(SolegramSettingsActivity.PAGE_GENERAL))));
                break;
            case DATA:
                l.add(new Link(R.drawable.msg_language, R.string.SG_Browser, () -> f.presentFragment(new SolegramSettingsActivity(SolegramSettingsActivity.PAGE_BROWSER))));
                l.add(new Link(R.drawable.msg_camera, R.string.SG_Camera, () -> f.presentFragment(new SolegramSettingsActivity(SolegramSettingsActivity.PAGE_CAMERA))));
                l.add(new Link(R.drawable.msg_location, R.string.SG_Maps, () -> f.presentFragment(new SolegramSettingsActivity(SolegramSettingsActivity.PAGE_MAPS))));
                break;
        }
        return l;
    }

    /** The block: a "Solegram" header, a row per page, and the section's shadow under it. */
    public static View block(BaseFragment f, Context context, int where, Theme.ResourcesProvider rp) {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);
        // the rows are clickable on their own: the list must not take their presses (it would cancel the click)
        section.setClickable(true);
        section.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, rp));
        final HeaderCell header = new HeaderCell(context, rp);
        header.setText("Solegram");
        section.addView(header);
        final ArrayList<Link> links = links(f, where);
        for (int i = 0; i < links.size(); i++) {
            final Link link = links.get(i);
            final TextCell cell = new TextCell(context, rp);
            cell.setTextAndIcon(org.telegram.messenger.LocaleController.getString(link.title), link.icon, i < links.size() - 1);
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setOnClickListener(v -> link.open.run());
            section.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        column.addView(section);
        column.addView(new ShadowSectionCell(context, rp), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return column;
    }
}
