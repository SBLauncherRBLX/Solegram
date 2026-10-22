/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramReminders;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberPicker;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * "Reminders" in Solegram's settings: what is coming (overdue, today, tomorrow, later), what is done, and how
 * reminders are made (AI text, the morning and evening of the quick choices, exact time, sound).
 */
public class SolegramRemindersActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    public static final int ID_AI = 1;
    public static final int ID_MORNING = 2;
    public static final int ID_EVENING = 3;
    public static final int ID_EXACT = 4;
    public static final int ID_SOUND = 5;
    private static final int ID_CLEAR_DONE = 6;
    private static final int ID_REMINDER_BASE = 1000;

    private static final int[] TILE_RED = {0xFFFF3B30, 0xFFFF453A};
    private static final int[] TILE_ORANGE = {0xFFFF9500, 0xFFFF9F0A};
    private static final int[] TILE_BLUE = {0xFF007AFF, 0xFF0A84FF};
    private static final int[] TILE_INDIGO = {0xFF5856D6, 0xFF5E5CE6};
    private static final int[] TILE_TEAL = {0xFF30B0C7, 0xFF40C8E0};
    private static final int[] TILE_GRAY = {0xFF8E8E93, 0xFF8E8E93};
    private static final int[] TILE_GREEN = {0xFF34C759, 0xFF30D158};
    private static final int[] TILE_YELLOW = {0xFFFFB800, 0xFFFFC70A};

    /** The reminders as listed now; row ids point into it. */
    private ArrayList<SolegramReminders.Reminder> shown = new ArrayList<>();
    private int pendingHighlight;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.solegramRemindersChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.solegramRemindersChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.SG_Reminders);
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        listView.setSections();
        if (pendingHighlight != 0) {
            final int id = pendingHighlight;
            pendingHighlight = 0;
            AndroidUtilities.runOnUIThread(() -> highlightItem(id), 300);
        }
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // back from the system settings: exact alarms may be allowed now
        SolegramReminders.schedule();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        shown = SolegramReminders.list();
        if (shown.isEmpty()) {
            items.add(UItem.asTopView(getString(R.string.SG_RemindersEmpty), getString(R.string.SG_RemindersEmptyInfo), R.raw.ic_unmute));
        }
        int group = -1;
        boolean anyDone = false;
        for (int i = 0; i < shown.size(); i++) {
            final SolegramReminders.Reminder r = shown.get(i);
            final int g = groupOf(r);
            if (g != group) {
                if (group != -1) {
                    items.add(UItem.asShadow(null));
                }
                items.add(UItem.asHeader(getString(groupTitle(g))));
                group = g;
            }
            anyDone |= g == 4;
            items.add(row(i, r, g));
        }
        if (anyDone) {
            items.add(UItem.asButton(ID_CLEAR_DONE, R.drawable.msg_clear_recent, getString(R.string.SG_RemindersClearDone)).red());
        }
        if (!shown.isEmpty()) {
            items.add(UItem.asShadow(getString(R.string.SG_RemindersListInfo)));
        }

        items.add(UItem.asHeader(getString(R.string.SG_RemindersSettings)));
        items.add(UItem.asButtonCheck(ID_AI, getString(R.string.SG_ReminderAiSetting), getString(R.string.SG_ReminderAiSettingInfo))
            .setChecked(SolegramConfig.reminderAi));
        items.add(tile(UItem.asButton(ID_MORNING, R.drawable.msg_calendar2, getString(R.string.SG_ReminderMorningTime), clock(SolegramConfig.reminderMorning)), TILE_YELLOW));
        items.add(tile(UItem.asButton(ID_EVENING, R.drawable.msg_calendar2, getString(R.string.SG_ReminderEveningTime), clock(SolegramConfig.reminderEvening)), TILE_INDIGO));
        items.add(UItem.asShadow(getString(R.string.SG_ReminderTimesInfo)));

        if (Build.VERSION.SDK_INT >= 31) {
            final boolean exact = SolegramReminders.canScheduleExact();
            final UItem exactItem = tile(UItem.asButton(ID_EXACT, R.drawable.msg_mute_1h, getString(R.string.SG_ReminderExact),
                getString(exact ? R.string.SG_ReminderExactOn : R.string.SG_ReminderExactOff)), exact ? TILE_GREEN : TILE_ORANGE);
            items.add(exact ? exactItem : exactItem.accent());
        }
        items.add(tile(UItem.asButton(ID_SOUND, R.drawable.msg_notifications, getString(R.string.SG_ReminderSound)), TILE_RED));
        items.add(UItem.asShadow(getString(Build.VERSION.SDK_INT >= 31 && !SolegramReminders.canScheduleExact()
            ? R.string.SG_ReminderExactInfoShort : R.string.SG_ReminderSoundInfo)));
    }

    /** 0 overdue, 1 today, 2 tomorrow, 3 later, 4 done. */
    private static int groupOf(SolegramReminders.Reminder r) {
        if (r.state == SolegramReminders.STATE_DONE) {
            return 4;
        }
        final long t = r.nextTime();
        if (t == 0) {
            return 0;
        }
        final int days = SolegramReminders.daysFromToday(t);
        return days <= 0 ? 1 : days == 1 ? 2 : 3;
    }

    private static int groupTitle(int group) {
        switch (group) {
            case 0:
                return R.string.SG_RemindersOverdue;
            case 1:
                return R.string.SG_RemindersToday;
            case 2:
                return R.string.SG_RemindersTomorrow;
            case 3:
                return R.string.SG_RemindersLater;
            default:
                return R.string.SG_RemindersDone;
        }
    }

    private UItem row(int index, SolegramReminders.Reminder r, int group) {
        final long t = group == 4 ? r.doneAt : group == 0 ? r.firedAt : r.nextTime();
        final String value = group == 4 || group == 0 ? SolegramReminders.formatShort(t, false) : SolegramReminders.formatShort(t, true);
        final int[] color = group == 0 ? TILE_ORANGE : group == 1 ? TILE_BLUE : group == 2 ? TILE_INDIGO : group == 3 ? TILE_TEAL : TILE_GRAY;
        final int icon = group == 4 ? R.drawable.msg_select : r.repeat != SolegramReminders.REPEAT_NONE ? R.drawable.msg_retry : R.drawable.msg_notifications;
        final StringBuilder sub = new StringBuilder(r.chatName);
        if (r.repeat != SolegramReminders.REPEAT_NONE && group != 4) {
            sub.append(" · ").append(getString(r.repeat == SolegramReminders.REPEAT_DAILY ? R.string.SG_ReminderRepeatDaily : R.string.SG_ReminderRepeatWeekly));
        }
        if (r.account != currentAccount && UserConfig.isValidAccount(r.account)) {
            // another account's reminder: whose it is
            sub.append(" · ").append(UserObject.getFirstName(UserConfig.getInstance(r.account).getCurrentUser()));
        }
        return tile(UItem.asButton(ID_REMINDER_BASE + index, icon, r.title, value), color).setSolegramSubtitle(sub.toString());
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_AI: {
                final boolean value = SolegramConfig.toggle("reminderAi");
                if (view instanceof org.telegram.ui.Cells.NotificationsCheckCell) {
                    ((org.telegram.ui.Cells.NotificationsCheckCell) view).setChecked(value);
                } else if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                }
                return;
            }
            case ID_MORNING:
                pickClock(R.string.SG_ReminderMorningTime, "reminderMorning", SolegramConfig.reminderMorning);
                return;
            case ID_EVENING:
                pickClock(R.string.SG_ReminderEveningTime, "reminderEvening", SolegramConfig.reminderEvening);
                return;
            case ID_EXACT:
                if (getParentActivity() != null) {
                    SolegramReminderSheet.openExactSettings(getParentActivity());
                }
                return;
            case ID_SOUND:
                if (getParentActivity() != null) {
                    SolegramReminderSheet.openNotificationSettings(getParentActivity());
                }
                return;
            case ID_CLEAR_DONE:
                confirm(getString(R.string.SG_RemindersClearDone), getString(R.string.SG_RemindersClearDoneConfirm), getString(R.string.SG_RemindersClear),
                    SolegramReminders::clearDone);
                return;
            default:
                final SolegramReminders.Reminder r = reminder(item);
                if (r != null) {
                    SolegramReminders.open(this, r);
                }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        final SolegramReminders.Reminder r = reminder(item);
        if (r == null || getParentActivity() == null) {
            return false;
        }
        final boolean done = r.state == SolegramReminders.STATE_DONE;
        final CharSequence[] options = {
            getString(R.string.SG_ReminderOpen),
            getString(R.string.SG_ReminderChangeTime),
            getString(done ? R.string.SG_ReminderRestore : R.string.SG_ReminderMarkDone),
            getString(R.string.Delete)
        };
        final int[] icons = {R.drawable.msg_message, R.drawable.msg_calendar2, done ? R.drawable.msg_retry : R.drawable.msg_select, R.drawable.msg_delete};
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(r.title);
        builder.setItems(options, icons, (d, which) -> {
            if (which == 0) {
                SolegramReminders.open(this, r);
            } else if (which == 1 || which == 2 && done && r.due <= System.currentTimeMillis()) {
                // a new time; a done one that would be overdue right away gets one as well
                pickTime(r);
            } else if (which == 2) {
                SolegramReminders.setDone(r.id, !done);
            } else {
                confirm(getString(R.string.SG_ReminderDelete), getString(R.string.SG_ReminderDeleteConfirm), getString(R.string.Delete),
                    () -> SolegramReminders.delete(r.id));
            }
        });
        final AlertDialog dialog = builder.create();
        showDialog(dialog);
        dialog.setItemColor(3, Theme.getColor(Theme.key_text_RedBold), Theme.getColor(Theme.key_text_RedRegular));
        return true;
    }

    private SolegramReminders.Reminder reminder(UItem item) {
        final int index = item.id - ID_REMINDER_BASE;
        return item.id >= ID_REMINDER_BASE && index < shown.size() ? shown.get(index) : null;
    }

    private void pickTime(SolegramReminders.Reminder r) {
        final long now = System.currentTimeMillis();
        final long current = r.due > now ? r.due : SolegramReminders.roundToMinute(now + SolegramReminders.HOUR);
        AlertsCreator.createDatePickerDialog(getParentActivity(), getString(R.string.SG_ReminderPickTitle), getString(R.string.SG_ReminderPickButton),
            current / 1000, (notify, date, repeatPeriod) -> {
                final long due = Math.max(SolegramReminders.roundToMinute(date * 1000L), SolegramReminders.roundToMinute(System.currentTimeMillis()) + SolegramReminders.MINUTE);
                SolegramReminders.setTime(r.id, due, r.repeat);
            });
    }

    /** Hours and minutes (five-minute steps) in a small dialog. */
    private void pickClock(int title, String key, int minutes) {
        if (getParentActivity() == null) {
            return;
        }
        final Context context = getParentActivity();
        final NumberPicker hours = new NumberPicker(context, getResourceProvider());
        hours.setMinValue(0);
        hours.setMaxValue(23);
        hours.setValue(minutes / 60);
        hours.setFormatter(v -> String.format(java.util.Locale.US, "%02d", v));
        final NumberPicker mins = new NumberPicker(context, getResourceProvider());
        mins.setMinValue(0);
        mins.setMaxValue(11);
        mins.setValue(Math.round((minutes % 60) / 5f) % 12);
        mins.setFormatter(v -> String.format(java.util.Locale.US, "%02d", v * 5));
        for (NumberPicker p : new NumberPicker[]{hours, mins}) {
            p.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
            p.setItemCount(5);
        }
        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER);
        layout.addView(hours, LayoutHelper.createLinear(72, 54 * 5));
        layout.addView(mins, LayoutHelper.createLinear(72, 54 * 5, 12, 0, 0, 0));
        new AlertDialog.Builder(context, getResourceProvider())
            .setTitle(getString(title))
            .setView(layout)
            .setPositiveButton(getString(R.string.OK), (d, w) -> {
                SolegramConfig.putInt(key, hours.getValue() * 60 + mins.getValue() * 5);
                listView.adapter.update(true);
            })
            .setNegativeButton(getString(R.string.Cancel), null)
            .show();
    }

    private static String clock(int minutes) {
        final java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(java.util.Calendar.HOUR_OF_DAY, minutes / 60);
        c.set(java.util.Calendar.MINUTE, minutes % 60);
        return SolegramReminders.time(c.getTimeInMillis());
    }

    /** Shows one row (opened from the settings search). */
    public void highlightItem(int itemId) {
        if (listView == null) {
            pendingHighlight = itemId;
            return;
        }
        final int position = listView.findPositionByItemId(itemId);
        if (position < 0) {
            return;
        }
        listView.highlightRow(() -> {
            listView.layoutManager.scrollToPositionWithOffset(position, AndroidUtilities.dp(60));
            return position;
        });
    }

    private void confirm(String title, String message, String action, Runnable onConfirm) {
        final AlertDialog dialog = new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action, (d, w) -> onConfirm.run())
            .setNegativeButton(getString(R.string.Cancel), null)
            .create();
        showDialog(dialog);
        final android.widget.TextView button = (android.widget.TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
        }
    }

    private static UItem tile(UItem item, int[] color) {
        return SolegramFiltersActivity.tile(item, color);
    }
}
