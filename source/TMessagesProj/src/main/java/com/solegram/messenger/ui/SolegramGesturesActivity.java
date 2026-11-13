/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;

import com.solegram.messenger.SolegramGestures;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.DialogsActivity;

import java.util.ArrayList;

/**
 * Motion shortcuts: a movement of the phone for each action. While this page is open, a movement is not run but
 * shown here, so each one can be tried out.
 */
public class SolegramGesturesActivity extends UniversalFragment {

    private static final int ID_ENABLED = 1;
    private static final int ID_GESTURE_BASE = 100;

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.SG_Gestures);
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        listView.setSections();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // trying out: a gesture only shows which one it was
        SolegramGestures.setTester(gesture -> BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check,
            formatString(R.string.SG_GestureRecognized, getString(SolegramGestures.nameOf(gesture)))).show());
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        SolegramGestures.setTester(null);
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        SolegramGestures.setTester(null);
    }

    private String describe(int gesture) {
        final int action = SolegramGestures.actionOf(gesture);
        if (action == SolegramGestures.ACTION_CHAT) {
            final long peer = SolegramGestures.peerOf(gesture);
            if (peer > 0) {
                final TLRPC.User u = getMessagesController().getUser(peer);
                if (u != null) {
                    return UserObject.getUserName(u);
                }
            } else if (peer < 0) {
                final TLRPC.Chat c = getMessagesController().getChat(-peer);
                if (c != null) {
                    return c.title;
                }
            }
        } else if (action == SolegramGestures.ACTION_LINK) {
            final String link = SolegramGestures.linkOf(gesture);
            if (!TextUtils.isEmpty(link)) {
                return link;
            }
        }
        return getString(SolegramGestures.actionName(action));
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(ID_ENABLED, getString(R.string.SG_GesturesEnabled)).setChecked(SolegramGestures.enabled()));
        items.add(UItem.asShadow(getString(R.string.SG_GesturesInfo)));
        items.add(UItem.asHeader(getString(R.string.SG_GesturesList)));
        for (int g = 0; g < SolegramGestures.COUNT; g++) {
            final UItem item = UItem.asButton(ID_GESTURE_BASE + g, getString(SolegramGestures.nameOf(g)), describe(g));
            item.setSolegramSubtitle(getString(SolegramGestures.hintOf(g)));
            items.add(item.setEnabled(SolegramGestures.enabled()));
        }
        items.add(UItem.asShadow(getString(R.string.SG_GesturesTryInfo)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ENABLED) {
            SolegramGestures.setEnabled(!SolegramGestures.enabled());
            listView.adapter.update(true);
            return;
        }
        if (item.id >= ID_GESTURE_BASE && item.id < ID_GESTURE_BASE + SolegramGestures.COUNT) {
            if (!SolegramGestures.enabled()) {
                return;
            }
            chooseAction(item.id - ID_GESTURE_BASE);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private void chooseAction(int gesture) {
        final CharSequence[] names = new CharSequence[SolegramGestures.ACTION_COUNT];
        for (int a = 0; a < names.length; a++) {
            names[a] = getString(SolegramGestures.actionName(a));
        }
        new AlertDialog.Builder(getParentActivity(), getResourceProvider())
            .setTitle(getString(SolegramGestures.nameOf(gesture)))
            .setItems(names, (d, which) -> {
                switch (which) {
                    case SolegramGestures.ACTION_CHAT:
                        pickChat(gesture);
                        break;
                    case SolegramGestures.ACTION_LINK:
                        askLink(gesture);
                        break;
                    default:
                        SolegramGestures.set(gesture, which, 0, null);
                        SolegramGestures.onResume();
                        listView.adapter.update(true);
                }
            })
            .show();
    }

    private void pickChat(int gesture) {
        final Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putBoolean("allowGlobalSearch", true);
        args.putBoolean("checkCanWrite", false);
        final DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids != null && !dids.isEmpty()) {
                SolegramGestures.set(gesture, SolegramGestures.ACTION_CHAT, dids.get(0).dialogId, null);
                SolegramGestures.onResume();
                fragment.finishFragment();
            }
            return true;
        });
        presentFragment(picker);
    }

    private void askLink(int gesture) {
        final Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setHint("t.me/…");
        input.setText(SolegramGestures.linkOf(gesture));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, getResourceProvider()));
        input.setLineColors(Theme.getColor(Theme.key_dialogInputField, getResourceProvider()), Theme.getColor(Theme.key_dialogInputFieldActivated, getResourceProvider()), Theme.getColor(Theme.key_text_RedRegular, getResourceProvider()));
        input.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
        final FrameLayout frame = new FrameLayout(context);
        frame.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, 0, 24, 6, 24, 0));
        new AlertDialog.Builder(context, getResourceProvider())
            .setTitle(getString(R.string.SG_GestureActionLink))
            .setMessage(getString(R.string.SG_GestureLinkAsk))
            .setView(frame)
            .setPositiveButton(getString(R.string.OK), (d, w) -> {
                final String link = input.getText().toString().trim();
                SolegramGestures.set(gesture, TextUtils.isEmpty(link) ? SolegramGestures.ACTION_NONE : SolegramGestures.ACTION_LINK, 0, link);
                SolegramGestures.onResume();
                listView.adapter.update(true);
            })
            .setNegativeButton(getString(R.string.Cancel), null)
            .show();
        AndroidUtilities.runOnUIThread(() -> {
            input.requestFocus();
            AndroidUtilities.showKeyboard(input);
        }, 200);
    }
}
