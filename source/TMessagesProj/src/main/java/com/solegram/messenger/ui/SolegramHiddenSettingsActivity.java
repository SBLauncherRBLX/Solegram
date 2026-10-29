/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramHiddenChats;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Solegram settings → Security → Hidden chats: how many there are and the way in (behind the phone's lock),
 * what their notifications show, the gesture on the chats screen, and what hiding does and does not do.
 */
public class SolegramHiddenSettingsActivity extends UniversalFragment implements NotificationCenter.NotificationCenterDelegate {

    public static final int ID_OPEN = 1;
    public static final int ID_NOTIFY_NORMAL = 2;
    public static final int ID_NOTIFY_NO_TEXT = 3;
    public static final int ID_NOTIFY_OFF = 4;
    public static final int ID_GESTURE = 5;

    private static final int[] TILE_INDIGO = {0xFF5856D6, 0xFF5E5CE6};

    private int pendingHighlight;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.solegramSettingsChanged);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.solegramSettingsChanged);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (args.length > 0 && "hiddenChats".equals(args[0]) && listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.SG_HiddenChats);
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        listView.setSections(); // rounded section cards, as on the other Solegram pages
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
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false); // the count, after a visit to the hidden chats
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        final int count = SolegramHiddenChats.count(currentAccount);
        items.add(UItem.asHeader(getString(R.string.SG_HiddenChats)));
        items.add(tile(UItem.asButton(ID_OPEN, R.drawable.msg_archive_hide, getString(R.string.SG_HiddenOpen),
            count == 0 ? getString(R.string.SG_HiddenCountNone) : String.valueOf(count)), TILE_INDIGO)
            .setSolegramSubtitle(getString(R.string.SG_HiddenOpenSub)));
        items.add(UItem.asShadow(getString(R.string.SG_HiddenHowToHide)));

        final int mode = SolegramHiddenChats.notifyMode();
        items.add(UItem.asHeader(getString(R.string.SG_HiddenNotify)));
        items.add(UItem.asRadio2(ID_NOTIFY_NORMAL, getString(R.string.SG_HiddenNotifyNormal), getString(R.string.SG_HiddenNotifyNormalInfo))
            .setChecked(mode == SolegramHiddenChats.NOTIFY_NORMAL));
        items.add(UItem.asRadio2(ID_NOTIFY_NO_TEXT, getString(R.string.SG_HiddenNotifyNoText), getString(R.string.SG_HiddenNotifyNoTextInfo))
            .setChecked(mode == SolegramHiddenChats.NOTIFY_NO_TEXT));
        items.add(UItem.asRadio2(ID_NOTIFY_OFF, getString(R.string.SG_HiddenNotifyOff), getString(R.string.SG_HiddenNotifyOffInfo))
            .setChecked(mode == SolegramHiddenChats.NOTIFY_OFF));
        items.add(UItem.asShadow(null));

        items.add(UItem.asButtonCheck(ID_GESTURE, getString(R.string.SG_HiddenGesture), getString(R.string.SG_HiddenGestureInfo))
            .setChecked(SolegramHiddenChats.gestureEnabled()));
        items.add(UItem.asShadow(getString(R.string.SG_HiddenAbout)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case ID_OPEN:
                SolegramHiddenChats.open(this);
                return;
            case ID_NOTIFY_NORMAL:
            case ID_NOTIFY_NO_TEXT:
            case ID_NOTIFY_OFF:
                SolegramHiddenChats.setNotifyMode(item.id == ID_NOTIFY_OFF ? SolegramHiddenChats.NOTIFY_OFF
                    : item.id == ID_NOTIFY_NO_TEXT ? SolegramHiddenChats.NOTIFY_NO_TEXT : SolegramHiddenChats.NOTIFY_NORMAL);
                listView.adapter.update(true);
                return;
            case ID_GESTURE: {
                final boolean value = !SolegramHiddenChats.gestureEnabled();
                SolegramHiddenChats.setGestureEnabled(value);
                // as on every Solegram page: the switch itself slides (liquid), the list is not rebuilt under it
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(value);
                } else if (view instanceof NotificationsCheckCell) {
                    ((NotificationsCheckCell) view).setChecked(value);
                }
                item.setChecked(value);
                return;
            }
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    /** Shows one row of the page (opened from the settings search). */
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

    private UItem tile(UItem item, int[] color) {
        final int base = Theme.isCurrentThemeDark() ? color[1] : color[0];
        return item.setIconTile(ColorUtils.blendARGB(base, 0xFFFFFFFF, 0.18f), base);
    }
}
