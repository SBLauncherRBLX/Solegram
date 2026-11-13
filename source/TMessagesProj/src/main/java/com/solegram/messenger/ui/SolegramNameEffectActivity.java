/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.solegram.messenger.SolegramConfig;
import com.solegram.messenger.SolegramNameEffect;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/** The effect on the own name: the name as others see it on top, then the effect, its mode and its colour. */
public class SolegramNameEffectActivity extends UniversalFragment {

    private static final int ID_KIND = 100, ID_MODE = 200;

    private int kind, mode, color;
    private SimpleTextView preview;
    private ColorsView colors;

    @Override
    public boolean onFragmentCreate() {
        final int v = SolegramNameEffect.own();
        kind = SolegramNameEffect.kindOf(v);
        mode = SolegramNameEffect.modeOf(v);
        color = SolegramNameEffect.colorOf(v);
        return super.onFragmentCreate();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.SG_NameEffect);
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        listView.setSections();
        return view;
    }

    private int value() {
        return SolegramNameEffect.pack(kind, mode, color);
    }

    private void save() {
        SolegramNameEffect.setOwn(value());
        if (preview != null) {
            preview.sgNameEffect = SolegramNameEffect.renderer(value());
            preview.invalidate();
        }
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private View previewView(Context context) {
        final FrameLayout frame = new FrameLayout(context);
        preview = new SimpleTextView(context);
        preview.setTextSize(22);
        preview.setTypeface(AndroidUtilities.bold());
        preview.setGravity(Gravity.CENTER);
        preview.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
        final TLRPC.User self = getUserConfig().getCurrentUser();
        preview.setText(self != null ? UserObject.getUserName(self) : "Solegram");
        preview.sgNameEffect = SolegramNameEffect.renderer(value());
        frame.addView(preview, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER, 16, 0, 16, 0));
        return frame;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (getContext() == null) {
            return;
        }
        if (preview == null) {
            previewView(getContext());
        }
        items.add(UItem.asCustom((View) preview.getParent(), 96));
        items.add(UItem.asShadow(getString(SolegramConfig.nameEmojiShare ? R.string.SG_NameEffectInfo : R.string.SG_NameEffectInfoOff)));

        items.add(UItem.asHeader(getString(R.string.SG_NameEffectKind)));
        for (int k = 0; k < SolegramNameEffect.KIND_COUNT; k++) {
            items.add(UItem.asRadio(ID_KIND + k, getString(SolegramNameEffect.nameOf(k))).setChecked(kind == k));
        }
        final int[] modes = SolegramNameEffect.modesOf(kind);
        if (modes != null) {
            items.add(UItem.asShadow(null));
            items.add(UItem.asHeader(getString(R.string.SG_NameEffectMode)));
            for (int m = 0; m < modes.length; m++) {
                items.add(UItem.asRadio(ID_MODE + m, getString(modes[m])).setChecked(mode == m));
            }
        }
        if (kind != SolegramNameEffect.NONE && SolegramNameEffect.usesColor(kind, mode)) {
            items.add(UItem.asShadow(null));
            items.add(UItem.asHeader(getString(R.string.SG_NameEffectColor)));
            if (colors == null) {
                colors = new ColorsView(getContext());
            }
            items.add(UItem.asCustom(colors, 64));
        }
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= ID_KIND && item.id < ID_KIND + SolegramNameEffect.KIND_COUNT) {
            kind = item.id - ID_KIND;
            mode = 0;
            save();
        } else if (item.id >= ID_MODE && item.id < ID_MODE + 10) {
            mode = item.id - ID_MODE;
            save();
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    /** A row of colour circles; the chosen one has a ring. */
    @SuppressLint("ViewConstructor")
    private class ColorsView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        ColorsView(Context context) {
            super(context);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dp(2));
        }

        private float step() {
            return (getWidth() - dp(32)) / (float) SolegramNameEffect.PALETTE.length;
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final float step = step();
            final float r = Math.min(dp(13), step / 2f - dp(3));
            final float cy = getHeight() / 2f;
            ring.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
            for (int i = 0; i < SolegramNameEffect.PALETTE.length; i++) {
                final float cx = dp(16) + step * i + step / 2f;
                paint.setColor(SolegramNameEffect.PALETTE[i]);
                canvas.drawCircle(cx, cy, i == color ? r - dp(3.5f) : r, paint);
                if (i == color) {
                    canvas.drawCircle(cx, cy, r, ring);
                }
                if (SolegramNameEffect.PALETTE[i] == 0xFFFFFFFF) {
                    // white on a light page: a thin edge
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(1));
                    paint.setColor(0x33000000);
                    canvas.drawCircle(cx, cy, i == color ? r - dp(3.5f) : r, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                final int i = (int) ((event.getX() - dp(16)) / step());
                if (i >= 0 && i < SolegramNameEffect.PALETTE.length && i != color) {
                    color = i;
                    invalidate();
                    save();
                }
                return true;
            }
            return super.onTouchEvent(event);
        }
    }
}
