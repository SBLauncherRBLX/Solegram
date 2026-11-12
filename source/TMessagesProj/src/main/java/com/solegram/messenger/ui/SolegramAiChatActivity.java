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
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.solegram.messenger.SolegramAI;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Solegram AI as an assistant, in a chat that looks as any Telegram chat: the chat's wallpaper, a date pill, message
 * bubbles in the chat colours with their tails and times, "bot" and "typing..." under the name in the header, a
 * description card while it is empty. The header floats (only its blur and its glass buttons), the input is a capsule
 * of Solegram's glass - exactly its own shape - holding the photo button, the field and the send button.
 * The conversation is kept while the app runs; nothing of it is stored.
 */
public class SolegramAiChatActivity extends BaseFragment {

    /** {"user"|"ai", text}: the conversation, while the app runs. */
    private static final ArrayList<String[]> TURNS = new ArrayList<>();
    /** The photo of each turn (null for none), in step with TURNS. */
    private static final ArrayList<android.graphics.Bitmap> PHOTOS = new ArrayList<>();
    /** When each turn was written, in step with TURNS. */
    private static final ArrayList<Long> TIMES = new ArrayList<>();
    private static final int PICK_PHOTO = 4021;
    private static final int MENU_NEW = 1;

    private LinearLayout list;
    private ScrollView scroll;
    private LinearLayout empty;
    private SolegramAiSheet.Spark emptyMark;
    private LinearLayout input;
    private EditTextBoldCursor field;
    private ImageView send;
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
        actionBar.setSubtitle(getString(R.string.Bot));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_NEW) {
                    TURNS.clear();
                    PHOTOS.clear();
                    TIMES.clear();
                    token++;
                    setWaiting(false);
                    rebuild(true);
                }
            }
        });
        final ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_NEW, R.drawable.msg_clear).setContentDescription(getString(R.string.SG_AiNewChat));

        final int[] c = SolegramAiSheet.brand();
        final Root root = new Root(context);

        // the conversation, full screen: the header floats over it, the input over its bottom
        scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(96));
        scroll.addView(list);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.scroll = scroll;

        // the empty chat: a bot's description card, in the chat's service colours
        empty = new LinearLayout(context);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(dp(20), dp(20), dp(20), dp(16));
        final GradientDrawable card = new GradientDrawable();
        card.setColor(getThemedColor(Theme.key_chat_serviceBackground));
        card.setCornerRadius(dp(20));
        empty.setBackground(card);
        final int serviceText = getThemedColor(Theme.key_chat_serviceText);
        emptyMark = new SolegramAiSheet.Spark(context).setPlain(true);
        empty.addView(emptyMark, LayoutHelper.createLinear(64, 64, Gravity.CENTER_HORIZONTAL));
        final TextView hello = new TextView(context);
        hello.setText(getString(R.string.SG_AiChatHello));
        hello.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        hello.setTypeface(AndroidUtilities.bold());
        hello.setTextColor(serviceText);
        hello.setGravity(Gravity.CENTER);
        empty.addView(hello, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));
        final TextView about = new TextView(context);
        about.setText(getString(R.string.SG_AiChatAbout));
        about.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        about.setTextColor(serviceText);
        about.setAlpha(0.85f);
        about.setGravity(Gravity.CENTER);
        empty.addView(about, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 14));
        for (int s : new int[]{R.string.SG_AiIdea1, R.string.SG_AiIdea2, R.string.SG_AiIdea3, R.string.SG_AiIdea4}) {
            // the ideas as a bot's keyboard buttons
            final TextView idea = new TextView(context);
            idea.setText(getString(s));
            idea.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            idea.setTypeface(AndroidUtilities.bold());
            idea.setTextColor(serviceText);
            idea.setGravity(Gravity.CENTER);
            idea.setPadding(dp(12), dp(10), dp(12), dp(10));
            idea.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(12), ColorUtils.setAlphaComponent(serviceText, 0x1E), ColorUtils.setAlphaComponent(serviceText, 0x34)));
            idea.setOnClickListener(v -> sendText(getString(s)));
            SolegramAiPageViews.pressSpring(idea);
            empty.addView(idea, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));
        }
        if (com.solegram.messenger.SolegramConfig.aiProvider != com.solegram.messenger.SolegramSummarizer.PROVIDER_OWN
            || android.text.TextUtils.isEmpty(com.solegram.messenger.SolegramConfig.aiBaseUrl)) {
            // the free model is a small one: the way to a far smarter assistant, right where it is used
            final TextView smarter = new TextView(context);
            smarter.setText(getString(R.string.SG_AiSmarterHint));
            smarter.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            smarter.setTextColor(serviceText);
            smarter.setAlpha(0.8f);
            smarter.setGravity(Gravity.CENTER);
            smarter.setPadding(dp(8), dp(8), dp(8), dp(2));
            smarter.setOnClickListener(v -> presentFragment(new SolegramSettingsActivity(SolegramSettingsActivity.PAGE_AI)));
            empty.addView(smarter, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        }
        root.addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 40, 0, 40, 40));

        // the input: a capsule of Solegram's glass with the photo button, the field and the send button inside
        input = new LinearLayout(context);
        input.setOrientation(LinearLayout.VERTICAL);
        input.setPadding(dp(8), dp(4), dp(8), dp(8));
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
        preview.addView(previewImage, LayoutHelper.createFrame(72, 72, Gravity.LEFT | Gravity.BOTTOM, 6, 6, 0, 0));
        final ImageView remove = new ImageView(context);
        remove.setImageResource(R.drawable.ic_close_white);
        remove.setScaleType(ImageView.ScaleType.CENTER);
        final GradientDrawable removeBg = new GradientDrawable();
        removeBg.setShape(GradientDrawable.OVAL);
        removeBg.setColor(0xB0000000);
        remove.setBackground(removeBg);
        remove.setOnClickListener(v -> setPendingPhoto(null));
        preview.addView(remove, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.TOP, 62, 0, 0, 0));
        preview.setVisibility(View.GONE);
        input.addView(preview, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        final int textColor = getThemedColor(Theme.key_chat_messagePanelText);
        final int hintColor = getThemedColor(Theme.key_chat_messagePanelHint);
        final int iconColor = getThemedColor(Theme.key_chat_messagePanelIcons);
        final LinearLayout capsule = new LinearLayout(context);
        capsule.setOrientation(LinearLayout.HORIZONTAL);
        capsule.setGravity(Gravity.BOTTOM);
        capsule.setPadding(dp(4), dp(4), dp(4), dp(4));
        final ImageView attach = new ImageView(context);
        attach.setImageResource(R.drawable.input_attach);
        attach.setScaleType(ImageView.ScaleType.CENTER);
        attach.setColorFilter(new android.graphics.PorterDuffColorFilter(iconColor, android.graphics.PorterDuff.Mode.SRC_IN));
        attach.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        attach.setContentDescription(getString(R.string.SG_AiAttachPhoto));
        attach.setOnClickListener(v -> pickPhoto());
        capsule.addView(attach, LayoutHelper.createLinear(42, 42));
        field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        field.setTextColor(textColor);
        field.setHintTextColor(hintColor);
        field.setHint(getString(R.string.TypeMessage));
        field.setCursorColor(getThemedColor(Theme.key_chat_messagePanelCursor));
        field.setCursorSize(dp(20));
        field.setMaxLines(6);
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setPadding(dp(4), dp(9), dp(8), dp(9));
        field.setBackground(null);
        capsule.addView(field, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        send = new ImageView(context);
        send.setImageResource(R.drawable.ic_send);
        send.setScaleType(ImageView.ScaleType.CENTER);
        send.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN));
        final GradientDrawable sendBg = new GradientDrawable();
        sendBg.setShape(GradientDrawable.OVAL);
        sendBg.setColor(getThemedColor(Theme.key_chat_messagePanelSend));
        send.setBackground(sendBg);
        send.setOnClickListener(v -> sendText(field.getText().toString()));
        SolegramAiPageViews.pressSpring(send);
        capsule.addView(send, LayoutHelper.createLinear(42, 42));
        root.glassFor(capsule, getResourceProvider());
        input.addView(capsule, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        // the conversation ends above the input, whatever its height (a photo waiting to go, several lines)
        input.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            final int room = (b - t) + dp(8);
            if (list.getPaddingBottom() != room) {
                list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), room);
            }
        });

        fragmentView = root;
        rebuild(false);
        if (TURNS.isEmpty()) {
            enterEmpty();
        }
        return fragmentView;
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

    // region Photos

    private void pickPhoto() {
        if (getParentActivity() == null) {
            return;
        }
        try {
            final android.content.Intent intent;
            if (Build.VERSION.SDK_INT >= 33) {
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
            if (Build.VERSION.SDK_INT >= 28) {
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

    // endregion

    // region The conversation

    /** The empty chat comes in: the card rises, the mark breathes. */
    private void enterEmpty() {
        empty.setAlpha(0f);
        empty.setTranslationY(dp(16));
        empty.animate().alpha(1f).translationY(0).setStartDelay(60).setDuration(460).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
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
        if (!TURNS.isEmpty()) {
            list.addView(datePill(TIMES.isEmpty() ? System.currentTimeMillis() : TIMES.get(0)));
        }
        for (int i = 0; i < TURNS.size(); i++) {
            final String[] t = TURNS.get(i);
            final android.graphics.Bitmap photo = i < PHOTOS.size() ? PHOTOS.get(i) : null;
            final long time = i < TIMES.size() ? TIMES.get(i) : System.currentTimeMillis();
            if (photo != null) {
                list.addView(photoBubble(photo, time, false));
            }
            if (!t[1].isEmpty()) {
                list.addView(bubble(t[0].equals("user"), t[1], time, false));
            }
        }
        final boolean none = TURNS.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        if (none && animateEmpty) {
            enterEmpty();
        }
        scrollToEnd();
    }

    private void setWaiting(boolean waiting) {
        this.waiting = waiting;
        send.animate().alpha(waiting ? 0.5f : 1f).setDuration(160).start();
        // as a bot in Telegram: "typing..." under its name while it answers
        actionBar.setSubtitle(getString(waiting ? R.string.Typing : R.string.Bot));
    }

    private void sendText(String text) {
        text = text == null ? "" : text.trim();
        final android.graphics.Bitmap photo = pendingPhoto;
        if (text.isEmpty() && photo == null || waiting) {
            return;
        }
        field.setText("");
        final long now = System.currentTimeMillis();
        if (TURNS.isEmpty()) {
            list.addView(datePill(now));
        }
        TURNS.add(new String[]{"user", text});
        PHOTOS.add(photo);
        TIMES.add(now);
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
            list.addView(photoBubble(photo, now, true));
        }
        if (!text.isEmpty()) {
            list.addView(bubble(true, text, now, true));
        }
        thinking = new Dots(getParentActivity());
        list.addView(thinking, LayoutHelper.createLinear(64, 36, Gravity.LEFT, 6, 6, 0, 0));
        thinking.setAlpha(0f);
        thinking.animate().alpha(1f).setStartDelay(150).setDuration(200).start();
        scrollToEnd();
        setWaiting(true);
        final int t = ++token;
        final SolegramAI.Result done = (answer, error, source) -> {
            if (t != token || fragmentView == null) {
                return;
            }
            setWaiting(false);
            if (thinking != null) {
                list.removeView(thinking);
                thinking = null;
            }
            if (answer == null) {
                // not answered: the question (and its photo) go back into the field
                final String[] last = TURNS.remove(TURNS.size() - 1);
                final android.graphics.Bitmap lastPhoto = PHOTOS.remove(PHOTOS.size() - 1);
                TIMES.remove(TIMES.size() - 1);
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
            final long at = System.currentTimeMillis();
            TURNS.add(new String[]{"ai", answer});
            PHOTOS.add(null);
            TIMES.add(at);
            list.addView(bubble(false, answer, at, true));
            scrollToEnd();
        };
        if (photo != null) {
            SolegramAI.askWithImage(new ArrayList<>(TURNS), photo, done);
        } else {
            SolegramAI.ask(new ArrayList<>(TURNS), done);
        }
    }

    private static String timeOf(long ms) {
        return LocaleController.getInstance().getFormatterDay().format(new Date(ms));
    }

    /** The date over the conversation, as Telegram's date pills. */
    private View datePill(long ms) {
        final TextView v = new TextView(getParentActivity());
        v.setText(LocaleController.formatDateChat(ms / 1000));
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        v.setTypeface(AndroidUtilities.bold());
        v.setTextColor(getThemedColor(Theme.key_chat_serviceText));
        v.setPadding(dp(10), dp(4), dp(10), dp(5));
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(getThemedColor(Theme.key_chat_serviceBackground));
        bg.setCornerRadius(dp(14));
        v.setBackground(bg);
        v.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 6));
        return v;
    }

    /** A message bubble in the chat's colours, with its tail and its time at the end of the text. */
    private View bubble(boolean mine, String text, long time, boolean animate) {
        final Context context = getParentActivity();
        final FrameLayout box = new FrameLayout(context);
        final int textColor = getThemedColor(mine ? Theme.key_chat_messageTextOut : Theme.key_chat_messageTextIn);
        final int timeColor = getThemedColor(mine ? Theme.key_chat_outTimeText : Theme.key_chat_inTimeText);
        box.setBackground(new Bubble(mine, getThemedColor(mine ? Theme.key_chat_outBubble : Theme.key_chat_inBubble),
            getThemedColor(mine ? Theme.key_chat_outBubbleShadow : Theme.key_chat_inBubbleShadow)));
        final TextView body = new TextView(context);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, SharedConfig.fontSize);
        body.setTextColor(textColor);
        body.setLinkTextColor(getThemedColor(mine ? Theme.key_chat_messageLinkOut : Theme.key_chat_messageLinkIn));
        body.setLineSpacing(dp(1), 1f);
        final String clock = timeOf(time);
        final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        timePaint.setTextSize(dp(12));
        final int reserve = (int) Math.ceil(timePaint.measureText(clock)) + dp(10);
        // room for the time on the last line, as Telegram keeps it
        final SpannableStringBuilder sb = new SpannableStringBuilder(text).append(" ").append(" ");
        sb.setSpan(new Space(reserve), sb.length() - 1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        body.setText(sb);
        body.setTextIsSelectable(!mine);
        if (!mine) {
            body.setOnLongClickListener(v -> {
                if (AndroidUtilities.addToClipboard(text)) {
                    BulletinFactory.of(this).createCopyBulletin(getString(R.string.TextCopied)).show();
                }
                return true;
            });
        }
        box.addView(body, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.TOP));
        final TextView when = new TextView(context);
        when.setText(clock);
        when.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        when.setTextColor(timeColor);
        box.addView(when, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 0, -2));
        // the tail side has the room for the tail
        box.setPadding(dp(mine ? 11 : 17), dp(7), dp(mine ? 17 : 11), dp(7));
        box.setLayoutParams(LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, mine ? Gravity.RIGHT : Gravity.LEFT,
            mine ? 56 : 0, 4, mine ? 0 : 56, 0));
        if (animate) {
            appear(box, mine);
        }
        return box;
    }

    /** A photo the user sent: rounded, in a bubble's corner shape, with its time over it. */
    private View photoBubble(android.graphics.Bitmap photo, long time, boolean animate) {
        final Context context = getParentActivity();
        final FrameLayout box = new FrameLayout(context);
        final ImageView v = new ImageView(context);
        v.setImageBitmap(photo);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final float r = dp(SharedConfig.bubbleRadius), small = dp(Math.min(6, SharedConfig.bubbleRadius));
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
        final int w = 240;
        final int h = Math.max(100, Math.min(320, Math.round(w * photo.getHeight() / (float) Math.max(1, photo.getWidth()))));
        box.addView(v, LayoutHelper.createFrame(w, h));
        final TextView when = new TextView(context);
        when.setText(timeOf(time));
        when.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        when.setTextColor(0xFFFFFFFF);
        when.setPadding(dp(6), dp(1), dp(6), dp(2));
        final GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x66000000);
        pill.setCornerRadius(dp(10));
        when.setBackground(pill);
        box.addView(when, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.BOTTOM, 0, 0, 6, 6));
        box.setLayoutParams(LayoutHelper.createLinear(w, h, Gravity.RIGHT, 56, 4, 6, 0));
        if (animate) {
            appear(box, true);
        }
        return box;
    }

    private static void appear(View v, boolean mine) {
        v.setAlpha(0f);
        v.setTranslationY(dp(14));
        v.setScaleX(0.94f);
        v.setScaleY(0.94f);
        v.setPivotX(mine ? dp(240) : 0);
        v.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.smoothScrollTo(0, list.getHeight()));
    }

    // endregion

    // region Views

    /** Room for the time on the last line of a message. */
    private static class Space extends ReplacementSpan {
        private final int width;

        Space(int width) {
            this.width = width;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
            return width;
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
        }
    }

    /** A Telegram message bubble: the chat's bubble radius, a small corner and a tail at the bottom of its side. */
    private static class Bubble extends Drawable {
        private final boolean out;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        Bubble(boolean out, int color, int shadowColor) {
            this.out = out;
            paint.setColor(color);
            shadow.setColor(shadowColor);
        }

        private void build() {
            path.rewind();
            final float l = getBounds().left, t = getBounds().top, r = getBounds().right, b = getBounds().bottom;
            final float tail = dp(6);
            final float big = dp(Math.max(6, SharedConfig.bubbleRadius)), small = dp(Math.min(6, SharedConfig.bubbleRadius));
            if (out) {
                rect.set(l, t, r - tail, b);
                final float[] radii = {big, big, big, big, small, small, big, big};
                path.addRoundRect(rect, radii, Path.Direction.CW);
                // the tail at the bottom right
                path.moveTo(r - tail - small, b);
                path.lineTo(r, b);
                path.quadTo(r - tail * 0.9f, b - dp(2), r - tail, b - dp(9));
                path.close();
            } else {
                rect.set(l + tail, t, r, b);
                final float[] radii = {big, big, big, big, big, big, small, small};
                path.addRoundRect(rect, radii, Path.Direction.CW);
                path.moveTo(l + tail + small, b);
                path.lineTo(l, b);
                path.quadTo(l + tail * 0.9f, b - dp(2), l + tail, b - dp(9));
                path.close();
            }
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            build();
            canvas.save();
            canvas.translate(0, dp(1));
            canvas.drawPath(path, shadow);
            canvas.restore();
            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * The screen: the chat's wallpaper, the conversation, and the input's capsule of Solegram's glass, which sees the
     * wallpaper and the messages under it (and only within its own shape).
     */
    private static class Root extends FrameLayout {
        private final Paint fill = new Paint();
        private org.telegram.utils.glass.source.GlassSourceRenderNode source;
        View scroll;

        Root(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        /** The chat's wallpaper, as the chat shows it: scaled to cover, or tiled, or a colour or gradient. */
        void drawWallpaper(Canvas canvas, int w, int h) {
            final Drawable wallpaper = Theme.getCachedWallpaper();
            if (wallpaper == null) {
                fill.setColor(Theme.getColor(Theme.key_chat_wallpaper));
                canvas.drawRect(0, 0, w, h, fill);
                return;
            }
            if (wallpaper instanceof BitmapDrawable && ((BitmapDrawable) wallpaper).getTileModeX() == null && ((BitmapDrawable) wallpaper).getBitmap() != null) {
                final android.graphics.Bitmap b = ((BitmapDrawable) wallpaper).getBitmap();
                final float k = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
                final int dw = Math.round(b.getWidth() * k), dh = Math.round(b.getHeight() * k);
                wallpaper.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
            } else {
                wallpaper.setBounds(0, 0, w, h);
            }
            wallpaper.draw(canvas);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            drawWallpaper(canvas, getWidth(), getHeight());
        }

        /** The capsule's background: Solegram's glass over what is under it. */
        void glassFor(View capsule, Theme.ResourcesProvider resourcesProvider) {
            if (Build.VERSION.SDK_INT >= 31) {
                // a plain panel where the picture cannot be drawn (a software canvas)
                final org.telegram.utils.glass.source.GlassSourceColor plain = new org.telegram.utils.glass.source.GlassSourceColor();
                plain.setColor(Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider));
                source = new org.telegram.utils.glass.source.GlassSourceRenderNode(plain);
                source.setupRenderer(new org.telegram.ui.Components.blur3.RenderNodeWithHash.Renderer() {
                    @Override
                    public void renderNodeUpdateDisplayList(Canvas canvas) {
                        drawWallpaper(canvas, getWidth(), getHeight());
                        if (scroll != null) {
                            canvas.save();
                            canvas.translate(scroll.getX(), scroll.getY());
                            scroll.draw(canvas);
                            canvas.restore();
                        }
                    }

                    @Override
                    public void renderNodeCalculateHash(org.telegram.ui.Components.blur3.capture.IBlur3Hash hash) {
                        hash.unsupported(); // the conversation moves and grows: recorded with every frame of the screen
                    }
                });
                final boolean liquid = org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS);
                source.setBlur(AndroidUtilities.dpf2(liquid ? 9f : 40f) * com.solegram.messenger.SolegramGlass.blurScale());
            }
            final org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory factory = SolegramFloatingHeader.factory(source,
                Theme.getColor(Theme.key_chat_messagePanelBackground, resourcesProvider), this);
            final BlurredBackgroundDrawable glass = factory.create(capsule, BlurredBackgroundProviderImpl.topPanel(resourcesProvider));
            glass.setRadius(dp(25));
            capsule.setBackground(glass);
        }

        /**
         * Before every frame (a scroll or a growing bubble redraws only itself, not this view): the picture under
         * the capsule is taken again.
         */
        private final android.view.ViewTreeObserver.OnPreDrawListener updateGlass = () -> {
            if (source != null && getWidth() > 0 && getHeight() > 0) {
                source.setSize(getWidth(), getHeight());
                source.updateDisplayListIfNeeded();
            }
            return true;
        };

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            getViewTreeObserver().addOnPreDrawListener(updateGlass);
        }

        @Override
        protected void onDetachedFromWindow() {
            getViewTreeObserver().removeOnPreDrawListener(updateGlass);
            super.onDetachedFromWindow();
        }
    }

    /** Three dots that breathe while Solegram AI thinks, in an incoming bubble. */
    private class Dots extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        private float phase;

        Dots(Context context) {
            super(context);
            setBackground(new Bubble(false, getThemedColor(Theme.key_chat_inBubble), getThemedColor(Theme.key_chat_inBubbleShadow)));
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
            final int color = getThemedColor(Theme.key_chat_inTimeText);
            for (int i = 0; i < 3; i++) {
                final float p = (float) (0.5 + 0.5 * Math.sin((phase - i * 0.18f) * Math.PI * 2));
                paint.setColor(color);
                paint.setAlpha((int) (90 + 165 * p));
                canvas.drawCircle(getWidth() / 2f + dp(3) + (i - 1) * dp(11), getHeight() / 2f - dp(2) * p, dp(3.5f), paint);
            }
        }
    }

    // endregion
}
