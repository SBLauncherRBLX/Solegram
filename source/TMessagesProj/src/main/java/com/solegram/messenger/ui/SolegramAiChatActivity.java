/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramAI;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Solegram AI as an assistant: a conversation about anything - questions, ideas, writing, translations, Solegram
 * itself. Kept for as long as the app runs, nothing of it is stored.
 * <p>
 * The conversation fills the screen and scrolls under the floating header (only its blur and the glass of its
 * buttons, see SolegramFloatingScreen) and under the input, which floats on glass over a blur of its own, with a soft
 * glow of the theme's Solegram AI colours behind it all.
 */
public class SolegramAiChatActivity extends BaseFragment {

    /** {"user"|"ai", text}: the conversation, while the app runs. */
    private static final ArrayList<String[]> TURNS = new ArrayList<>();
    /** The photo of each turn (null for none), in step with TURNS. */
    private static final ArrayList<android.graphics.Bitmap> PHOTOS = new ArrayList<>();
    private static final int PICK_PHOTO = 4021;

    private static final int MENU_NEW = 1;

    private LinearLayout list;
    private ScrollView scroll;
    private LinearLayout empty;
    private SolegramAiSheet.Spark emptyMark;
    private LinearLayout input;
    private EditTextBoldCursor field;
    private ImageView send;
    private ImageView attach;
    private FrameLayout preview;
    private ImageView previewImage;
    private android.graphics.Bitmap pendingPhoto;
    private View thinking;
    private boolean waiting;
    private int token;
    private ValueAnimator breathe;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(getString(R.string.SG_AiTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_NEW) {
                    TURNS.clear();
                    PHOTOS.clear();
                    token++;
                    waiting = false;
                    send.setAlpha(1f);
                    rebuild(true);
                }
            }
        });
        final ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_NEW, R.drawable.msg_clear).setContentDescription(getString(R.string.SG_AiNewChat));

        final int textColor = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayColor = getThemedColor(Theme.key_windowBackgroundWhiteGrayText);
        final int background = getThemedColor(Theme.key_windowBackgroundWhite);
        final int[] c = SolegramAiSheet.brand();

        final Root root = new Root(context, background);

        // the conversation, full screen: the header floats over it
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(12), dp(12), dp(96)); // room for the floating input
        scroll.addView(list);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        // the empty state: the mark, a question, a few ideas to start with
        empty = new LinearLayout(context);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyMark = new SolegramAiSheet.Spark(context).setPlain(true);
        empty.addView(emptyMark, LayoutHelper.createLinear(84, 84, Gravity.CENTER_HORIZONTAL));
        final TextView hello = new TextView(context);
        hello.setText(getString(R.string.SG_AiChatHello));
        hello.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
        hello.setTypeface(AndroidUtilities.bold());
        hello.setTextColor(textColor);
        hello.setGravity(Gravity.CENTER);
        empty.addView(hello, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 18, 0, 0));
        final TextView about = new TextView(context);
        about.setText(getString(R.string.SG_AiChatAbout));
        about.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        about.setTextColor(grayColor);
        about.setGravity(Gravity.CENTER);
        empty.addView(about, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 6, 32, 20));
        for (int s : new int[]{R.string.SG_AiIdea1, R.string.SG_AiIdea2, R.string.SG_AiIdea3, R.string.SG_AiIdea4}) {
            final TextView idea = new TextView(context);
            idea.setText(getString(s));
            idea.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            idea.setTextColor(textColor);
            idea.setPadding(dp(16), dp(12), dp(16), dp(12));
            idea.setBackground(glassPill(dp(18), background, textColor, 0.62f));
            idea.setOnClickListener(v -> sendText(getString(s)));
            SolegramAiPageViews.pressSpring(idea);
            empty.addView(idea, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));
        }
        root.addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 0, 0, 40));

        // the input floats at the bottom: one capsule of glass holding the photo button, the field and the send button
        input = new LinearLayout(context);
        input.setOrientation(LinearLayout.VERTICAL);
        input.setPadding(dp(12), dp(6), dp(12), dp(12));
        preview = new FrameLayout(context);
        previewImage = new ImageView(context);
        previewImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewImage.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });
        previewImage.setClipToOutline(true);
        preview.addView(previewImage, LayoutHelper.createFrame(72, 72, Gravity.LEFT | Gravity.BOTTOM, 4, 6, 0, 0));
        final ImageView remove = new ImageView(context);
        remove.setImageResource(R.drawable.ic_close_white);
        remove.setScaleType(ImageView.ScaleType.CENTER);
        final GradientDrawable removeBg = new GradientDrawable();
        removeBg.setShape(GradientDrawable.OVAL);
        removeBg.setColor(0xB0000000);
        remove.setBackground(removeBg);
        remove.setOnClickListener(v -> setPendingPhoto(null));
        preview.addView(remove, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.TOP, 60, 0, 0, 0));
        preview.setVisibility(View.GONE);
        input.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        final LinearLayout capsule = new LinearLayout(context);
        capsule.setOrientation(LinearLayout.HORIZONTAL);
        capsule.setGravity(Gravity.BOTTOM);
        capsule.setPadding(dp(4), dp(4), dp(4), dp(4));
        capsule.setBackground(glassPill(dp(26), background, textColor, 0.74f));
        attach = new ImageView(context);
        attach.setImageResource(R.drawable.input_attach);
        attach.setScaleType(ImageView.ScaleType.CENTER);
        attach.setColorFilter(new android.graphics.PorterDuffColorFilter(grayColor, android.graphics.PorterDuff.Mode.SRC_IN));
        attach.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        attach.setContentDescription(getString(R.string.SG_AiAttachPhoto));
        attach.setOnClickListener(v -> pickPhoto());
        capsule.addView(attach, LayoutHelper.createLinear(42, 42));
        field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(textColor);
        field.setHintTextColor(grayColor);
        field.setHint(getString(R.string.SG_AiChatHint));
        field.setCursorColor(c[1]);
        field.setCursorSize(dp(20));
        field.setMaxLines(6);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setPadding(dp(4), dp(10), dp(8), dp(10));
        field.setBackground(null);
        capsule.addView(field, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        send = new ImageView(context);
        send.setImageResource(R.drawable.ic_send);
        send.setScaleType(ImageView.ScaleType.CENTER);
        send.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN));
        final GradientDrawable sendBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c[0], c[1]});
        sendBg.setShape(GradientDrawable.OVAL);
        send.setBackground(sendBg);
        send.setOnClickListener(v -> sendText(field.getText().toString()));
        SolegramAiPageViews.pressSpring(send);
        capsule.addView(send, LayoutHelper.createLinear(42, 42));
        input.addView(capsule, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        // the conversation ends above the input, whatever its height (a photo waiting to go, several lines)
        input.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            final int room = (b - t) + dp(8);
            if (list.getPaddingBottom() != room) {
                list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), room);
            }
        });
        root.input = input;
        root.scroll = scroll;

        fragmentView = root;
        rebuild(false);
        if (TURNS.isEmpty()) {
            enterEmpty();
        }
        return fragmentView;
    }

    private void pickPhoto() {
        if (getParentActivity() == null) {
            return;
        }
        try {
            final android.content.Intent intent;
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                intent = new android.content.Intent(android.provider.MediaStore.ACTION_PICK_IMAGES);
            } else {
                intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                intent.setType("image/*");
            }
            startActivityForResult(intent, PICK_PHOTO);
        } catch (Exception e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode != PICK_PHOTO || resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final android.net.Uri uri = data.getData();
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            final android.graphics.Bitmap bitmap = loadPhoto(uri);
            AndroidUtilities.runOnUIThread(() -> {
                if (bitmap == null) {
                    BulletinFactory.of(this).createErrorBulletin(getString(R.string.SG_AiPhotoLoadFailed)).show();
                } else {
                    setPendingPhoto(bitmap);
                }
            });
        });
    }

    /** The picked photo at most 1280 px on its long side, turned upright. */
    private android.graphics.Bitmap loadPhoto(android.net.Uri uri) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                return android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(getParentActivity().getContentResolver(), uri), (decoder, info, src) -> {
                    final int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    final float k = Math.min(1f, 1280f / Math.max(w, h));
                    decoder.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
                    decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            }
            final android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (java.io.InputStream in = getParentActivity().getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1280) {
                sample *= 2;
            }
            final android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            try (java.io.InputStream in = getParentActivity().getContentResolver().openInputStream(uri)) {
                return android.graphics.BitmapFactory.decodeStream(in, null, o2);
            }
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
            return null;
        }
    }

    private void setPendingPhoto(android.graphics.Bitmap photo) {
        pendingPhoto = photo;
        if (photo != null) {
            previewImage.setImageBitmap(photo);
            preview.setVisibility(View.VISIBLE);
            preview.setAlpha(0f);
            preview.setScaleX(0.85f);
            preview.setScaleY(0.85f);
            preview.setPivotX(dp(40));
            preview.setPivotY(dp(78));
            preview.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
            field.requestFocus();
        } else {
            preview.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(180).withEndAction(() -> {
                preview.setVisibility(View.GONE);
                previewImage.setImageDrawable(null);
            }).start();
        }
    }

    /** A pill of glass: the page's colour, see-through, with a light rim. */
    private static GradientDrawable glassPill(int radius, int background, int ink, float alpha) {
        final GradientDrawable d = new GradientDrawable();
        d.setColor(ColorUtils.setAlphaComponent(ColorUtils.blendARGB(background, ink, 0.05f), (int) (255 * alpha)));
        d.setCornerRadius(radius);
        d.setStroke(Math.max(1, dp(0.6f)), ColorUtils.setAlphaComponent(ink, 0x18));
        return d;
    }

    @Override
    public void onResume() {
        super.onResume();
        AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
    }

    @Override
    public void onFragmentDestroy() {
        if (breathe != null) {
            breathe.cancel();
        }
        super.onFragmentDestroy();
    }

    /** The empty state comes in: the mark breathes, the ideas rise one after another. */
    private void enterEmpty() {
        for (int i = 0; i < empty.getChildCount(); i++) {
            final View v = empty.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(dp(16));
            v.animate().alpha(1f).translationY(0).setStartDelay(40L + 55L * i).setDuration(460).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }
        if (breathe == null) {
            breathe = ValueAnimator.ofFloat(0, 1);
            breathe.setDuration(2200);
            breathe.setRepeatCount(ValueAnimator.INFINITE);
            breathe.setRepeatMode(ValueAnimator.REVERSE);
            breathe.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
            breathe.addUpdateListener(a -> emptyMark.setBreathe(0.86f + 0.24f * (float) a.getAnimatedValue()));
            breathe.start();
        }
    }

    private void rebuild(boolean animateEmpty) {
        list.removeAllViews();
        thinking = null;
        for (int i = 0; i < TURNS.size(); i++) {
            final String[] t = TURNS.get(i);
            final android.graphics.Bitmap photo = i < PHOTOS.size() ? PHOTOS.get(i) : null;
            if (photo != null) {
                list.addView(photoBubble(photo, false));
            }
            if (!t[1].isEmpty()) {
                list.addView(bubble(t[0].equals("user"), t[1], false));
            }
        }
        final boolean none = TURNS.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        if (none && animateEmpty) {
            enterEmpty();
        }
        scrollToEnd();
    }

    private void sendText(String text) {
        text = text == null ? "" : text.trim();
        final android.graphics.Bitmap photo = pendingPhoto;
        if (text.isEmpty() && photo == null || waiting) {
            return;
        }
        field.setText("");
        TURNS.add(new String[]{"user", text});
        PHOTOS.add(photo);
        if (photo != null) {
            pendingPhoto = null;
            preview.setVisibility(View.GONE);
            previewImage.setImageDrawable(null);
        }
        if (empty.getVisibility() == View.VISIBLE) {
            empty.animate().alpha(0f).translationY(-dp(20)).setDuration(220).withEndAction(() -> {
                empty.setVisibility(View.GONE);
                empty.setAlpha(1f);
                empty.setTranslationY(0);
            }).start();
        }
        if (photo != null) {
            list.addView(photoBubble(photo, true));
        }
        if (!text.isEmpty()) {
            list.addView(bubble(true, text, true));
        }
        thinking = new Dots(getParentActivity());
        list.addView(thinking, LayoutHelper.createLinear(68, 38, Gravity.LEFT, 4, 8, 0, 0));
        thinking.setAlpha(0f);
        thinking.animate().alpha(1f).setStartDelay(150).setDuration(200).start();
        scrollToEnd();
        waiting = true;
        send.animate().alpha(0.5f).setDuration(160).start();
        final int t = ++token;
        final SolegramAI.Result done = (answer, error, source) -> {
            if (t != token || fragmentView == null) {
                return;
            }
            waiting = false;
            send.animate().alpha(1f).setDuration(160).start();
            if (thinking != null) {
                list.removeView(thinking);
                thinking = null;
            }
            if (answer == null) {
                // not answered: the question (and its photo) go back into the field
                final String[] last = TURNS.remove(TURNS.size() - 1);
                final android.graphics.Bitmap lastPhoto = PHOTOS.remove(PHOTOS.size() - 1);
                rebuild(true);
                field.setText(last[1]);
                field.setSelection(field.length());
                if (lastPhoto != null) {
                    setPendingPhoto(lastPhoto);
                }
                if (error != null) {
                    BulletinFactory.of(this).createErrorBulletin(error).show();
                }
                return;
            }
            TURNS.add(new String[]{"ai", answer});
            PHOTOS.add(null);
            list.addView(bubble(false, answer, true));
            scrollToEnd();
        };
        if (photo != null) {
            SolegramAI.askWithImage(new ArrayList<>(TURNS), photo, done);
        } else {
            SolegramAI.ask(new ArrayList<>(TURNS), done);
        }
    }

    /** A photo the user sent: rounded, at most 220 dp wide, on the right. */
    private View photoBubble(android.graphics.Bitmap photo, boolean animate) {
        final ImageView v = new ImageView(getParentActivity());
        v.setImageBitmap(photo);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(20));
            }
        });
        v.setClipToOutline(true);
        final int w = 220;
        final int h = Math.max(90, Math.min(300, Math.round(w * photo.getHeight() / (float) Math.max(1, photo.getWidth()))));
        v.setLayoutParams(LayoutHelper.createLinear(w, h, Gravity.RIGHT, 48, 8, 0, 0));
        if (animate) {
            v.setAlpha(0f);
            v.setScaleX(0.9f);
            v.setScaleY(0.9f);
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
        }
        return v;
    }

    private View bubble(boolean mine, String text, boolean animate) {
        final int textColor = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        final int background = getThemedColor(Theme.key_windowBackgroundWhite);
        final int[] c = SolegramAiSheet.brand();
        final TextView v = new TextView(getParentActivity());
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        v.setLineSpacing(dp(2), 1f);
        v.setPadding(dp(15), dp(10), dp(15), dp(11));
        v.setTextIsSelectable(!mine);
        if (mine) {
            final GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{c[0], c[1]});
            bg.setCornerRadii(new float[]{dp(22), dp(22), dp(22), dp(22), dp(6), dp(6), dp(22), dp(22)});
            v.setBackground(bg);
            v.setTextColor(0xFFFFFFFF);
        } else {
            // glass: the page shows through, with a hint of the theme's colour and a light rim
            final GradientDrawable bg = new GradientDrawable();
            bg.setColor(ColorUtils.setAlphaComponent(ColorUtils.blendARGB(background, c[1], 0.1f), 0xD8));
            bg.setStroke(Math.max(1, dp(0.6f)), ColorUtils.setAlphaComponent(c[1], 0x30));
            bg.setCornerRadii(new float[]{dp(22), dp(22), dp(22), dp(22), dp(22), dp(22), dp(6), dp(6)});
            v.setBackground(bg);
            v.setTextColor(textColor);
            v.setOnLongClickListener(view -> {
                if (AndroidUtilities.addToClipboard(text)) {
                    BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
                }
                return true;
            });
        }
        v.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, mine ? Gravity.RIGHT : Gravity.LEFT,
            mine ? 48 : 0, 8, mine ? 0 : 48, 0));
        if (animate) {
            v.setAlpha(0f);
            v.setTranslationY(dp(14));
            v.setScaleX(0.94f);
            v.setScaleY(0.94f);
            v.setPivotX(mine ? dp(220) : 0);
            v.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
        }
        return v;
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.smoothScrollTo(0, list.getHeight()));
    }

    /**
     * The screen: a soft glow of the theme's colours at the top, and under the floating input a blur of the
     * conversation going out of focus towards the bottom edge (as under the header).
     */
    private static class Root extends FrameLayout {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int background;
        private final long born = SystemClock.uptimeMillis() - 4000;
        private final View[] blurred = new View[1];
        private SolegramEdgeBlur edgeBlur;
        View input, scroll;

        Root(Context context, int background) {
            super(context);
            this.background = background;
            setWillNotDraw(false);
            setBackgroundColor(background);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            final float w = getWidth();
            final int[] c = SolegramAiSheet.brand();
            final boolean dark = ColorUtils.calculateLuminance(background) < 0.4;
            final float k = dark ? 0.75f : 0.4f;
            final float t = (SystemClock.uptimeMillis() - born) / 1000f;
            final float d = dp(16);
            blob(canvas, w * 0.1f + d * (float) Math.sin(t * 0.3), dp(40), w * 0.7f, c[0], 0.3f * k);
            blob(canvas, w * 0.62f + d * (float) Math.cos(t * 0.25), -dp(20), w * 0.7f, c[1], 0.26f * k);
            blob(canvas, w * 0.95f + d * (float) Math.sin(t * 0.21 + 1), dp(80), w * 0.55f, c[3], 0.24f * k);
            // still: a moving glow would make the blurs under the header and the input redraw all the time
        }

        private void blob(Canvas canvas, float x, float y, float r, int color, float alpha) {
            glow.setShader(new RadialGradient(x, y, r, new int[]{ColorUtils.setAlphaComponent(color, (int) (255 * alpha)), ColorUtils.setAlphaComponent(color, 0)}, null, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, r, glow);
        }

        @Override
        protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
            if (child == input && scroll != null && input.getVisibility() == VISIBLE) {
                // the conversation blurs out under the input, towards the bottom edge
                if (edgeBlur == null) {
                    edgeBlur = new SolegramEdgeBlur();
                }
                final int top = (int) (input.getY() - dp(24));
                blurred[0] = scroll;
                edgeBlur.draw(canvas, blurred, getWidth(), top, getHeight() - top, false, background, 0, 1f);
                blurred[0] = null;
            }
            return super.drawChild(canvas, child, drawingTime);
        }
    }

    /** Three dots that breathe while Solegram AI thinks. */
    private class Dots extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        private float phase;

        Dots(Context context) {
            super(context);
            final int[] c = SolegramAiSheet.brand();
            final GradientDrawable bg = new GradientDrawable();
            bg.setColor(ColorUtils.setAlphaComponent(ColorUtils.blendARGB(getThemedColor(Theme.key_windowBackgroundWhite), c[1], 0.1f), 0xD8));
            bg.setStroke(Math.max(1, dp(0.6f)), ColorUtils.setAlphaComponent(c[1], 0x30));
            bg.setCornerRadius(dp(19));
            setBackground(bg);
            animator.setDuration(1100);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                invalidate();
            });
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            animator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            animator.cancel();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int[] c = SolegramAiSheet.brand();
            for (int i = 0; i < 3; i++) {
                final float p = (float) (0.5 + 0.5 * Math.sin((phase - i * 0.18f) * Math.PI * 2));
                paint.setShader(new LinearGradient(0, 0, getWidth(), 0, c[0], c[2], Shader.TileMode.CLAMP));
                paint.setAlpha((int) (110 + 145 * p));
                canvas.drawCircle(getWidth() / 2f + (i - 1) * dp(12), getHeight() / 2f - dp(2.5f) * p, dp(4), paint);
            }
        }
    }
}
