/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.Spannable;
import android.text.TextPaint;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.text.style.CharacterStyle;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;
import android.text.style.UpdateAppearance;
import android.widget.EditText;

import com.solegram.messenger.SolegramConfig;

import org.telegram.ui.CodeNumberField;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;

import java.util.ArrayList;

/**
 * Smooth typing: letters that appear fade in, letters that are erased fade out where they stood.
 * Only what really changed is animated, so keyboards that rewrite the whole word on every key press
 * (suggestions, autocorrect) do not make the word blink.
 */
public class TypingAnimator implements TextWatcher {

    private static final long FADE_IN_MS = 190;
    private static final long FADE_OUT_MS = 210;
    private static final int MAX_ANIMATED_INSERT = 64;
    private static final int MAX_ANIMATED_DELETE = 32;

    public static boolean enabled() {
        return SolegramConfig.typingAnimation;
    }

    /** A fading-in run of new letters; the span is re-set every frame so the text redraws with the new alpha. */
    private static final class FadeSpan extends CharacterStyle implements UpdateAppearance {
        final long startTime = SystemClock.uptimeMillis();
        float alpha;

        @Override
        public void updateDrawState(TextPaint tp) {
            tp.setAlpha((int) (tp.getAlpha() * alpha));
        }
    }

    /** Erased letters, drawn over the field while they fade out and shrink a little. */
    private static final class Ghost {
        final String text;
        final float x, baseline;
        /** x is the right edge of the text (right-to-left line), otherwise its left edge */
        final boolean alignRight;
        final long startTime = SystemClock.uptimeMillis();

        Ghost(String text, float x, float baseline, boolean alignRight) {
            this.text = text;
            this.x = x;
            this.baseline = baseline;
            this.alignRight = alignRight;
        }
    }

    private final EditText view;
    private final ArrayList<FadeSpan> spans = new ArrayList<>();
    private final ArrayList<Ghost> ghosts = new ArrayList<>();
    private final TextPaint ghostPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable tick = this::tick;
    private boolean ticking;

    // state between beforeTextChanged and onTextChanged
    private String replacedText;
    private float[] replacedX;
    private int[] replacedLine;
    private float[] lineBaselines;
    private boolean[] lineRtl;
    private boolean replacedStyled;
    // fading letters inside the replaced text: keyboards rewrite the whole word on every key press
    private final ArrayList<FadeSpan> carriedSpans = new ArrayList<>();
    private final ArrayList<int[]> carriedRanges = new ArrayList<>();

    public TypingAnimator(EditText view) {
        this.view = view;
    }

    private boolean active() {
        if (!enabled() || !view.isFocused() || view.getLayout() == null) {
            return false;
        }
        // code fields animate their digits themselves
        if (view instanceof CodeNumberField) {
            return false;
        }
        // never draw hidden text: PIN and CVV fields hide it with a transformation, not always by input type
        if (view.getTransformationMethod() instanceof PasswordTransformationMethod) {
            return false;
        }
        final int variation = view.getInputType() & InputType.TYPE_MASK_VARIATION;
        return variation != InputType.TYPE_TEXT_VARIATION_PASSWORD && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            && !((view.getInputType() & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        replacedText = null;
        if (!active() || count > 256) {
            return;
        }
        replacedText = s.subSequence(start, start + count).toString();
        replacedX = null;
        carriedSpans.clear();
        carriedRanges.clear();
        replacedStyled = false;
        if (s instanceof Spanned && count > 0) {
            final Spanned spanned = (Spanned) s;
            // emoji are pictures and styled letters use other fonts: a plain-text ghost would look wrong
            replacedStyled = spanned.getSpans(start, start + count, ReplacementSpan.class).length > 0
                || spanned.getSpans(start, start + count, MetricAffectingSpan.class).length > 0;
            for (FadeSpan span : spanned.getSpans(start, start + count, FadeSpan.class)) {
                final int st = spanned.getSpanStart(span), en = spanned.getSpanEnd(span);
                if (st >= start && en <= start + count) {
                    carriedSpans.add(span);
                    carriedRanges.add(new int[]{st - start, en - start});
                }
            }
        }
        if (count > 0 && count <= MAX_ANIMATED_DELETE + 32) {
            // where the replaced letters stand now; the layout changes as soon as the text does
            final Layout layout = view.getLayout();
            replacedX = new float[count + 1];
            replacedLine = new int[count + 1];
            lineBaselines = new float[layout.getLineCount()];
            lineRtl = new boolean[lineBaselines.length];
            for (int i = 0; i < lineBaselines.length; i++) {
                lineBaselines[i] = layout.getLineBaseline(i);
                lineRtl[i] = layout.getParagraphDirection(i) == Layout.DIR_RIGHT_TO_LEFT;
            }
            for (int i = 0; i <= count; i++) {
                final int offset = Math.min(start + i, s.length());
                replacedX[i] = layout.getPrimaryHorizontal(offset);
                replacedLine[i] = layout.getLineForOffset(offset);
            }
        }
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        final String old = replacedText;
        replacedText = null;
        if (old == null || !(s instanceof Spannable) || old.length() != before) {
            return;
        }
        // keep only the real change: the common start and end of the old and new text are not animated
        final int max = Math.min(before, count);
        int prefix = 0;
        while (prefix < max && old.charAt(prefix) == s.charAt(start + prefix)) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < max - prefix && old.charAt(before - 1 - suffix) == s.charAt(start + count - 1 - suffix)) {
            suffix++;
        }
        final int inserted = count - prefix - suffix;
        final int deleted = before - prefix - suffix;

        // letters that were still fading and did not change keep fading where they now stand
        final Spannable spannable = (Spannable) s;
        for (int i = 0; i < carriedSpans.size(); i++) {
            final FadeSpan span = carriedSpans.get(i);
            final int[] range = carriedRanges.get(i);
            int st = -1, en = -1;
            if (range[1] <= prefix) {
                st = start + range[0];
                en = start + range[1];
            } else if (range[0] >= before - suffix) {
                st = start + range[0] + (count - before);
                en = start + range[1] + (count - before);
            }
            if (st >= 0 && en > st && en <= s.length()) {
                spannable.setSpan(span, st, en, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        carriedSpans.clear();
        carriedRanges.clear();

        if (inserted > 0 && inserted <= MAX_ANIMATED_INSERT) {
            final FadeSpan span = new FadeSpan();
            spannable.setSpan(span, start + prefix, start + prefix + inserted, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            spans.add(span);
            startTicking();
        }
        if (deleted > 0 && deleted <= MAX_ANIMATED_DELETE && replacedX != null && !replacedStyled
                && replacedLine[prefix] == replacedLine[prefix + deleted - 1]) {
            final String text = old.substring(prefix, prefix + deleted);
            if (text.trim().length() > 0 && text.indexOf('\n') < 0) {
                final int line = replacedLine[prefix];
                final boolean rtl = lineRtl != null && line < lineRtl.length && lineRtl[line];
                final Ghost ghost;
                if (replacedLine[prefix + deleted] != line) {
                    // the text after the deleted letters starts on the next (wrapped) line: anchor on the first letter
                    ghost = new Ghost(text, replacedX[prefix], lineBaselines[line], rtl);
                } else {
                    ghost = new Ghost(text, Math.min(replacedX[prefix], replacedX[prefix + deleted]), lineBaselines[line], false);
                }
                ghosts.add(ghost);
                startTicking();
            }
        }
    }

    @Override
    public void afterTextChanged(Editable s) {
    }

    private void startTicking() {
        if (!ticking) {
            ticking = true;
            view.postOnAnimation(tick);
        }
        view.invalidate();
    }

    private void tick() {
        final long now = SystemClock.uptimeMillis();
        final CharSequence text = view.getText();
        for (int i = spans.size() - 1; i >= 0; i--) {
            final FadeSpan span = spans.get(i);
            final float t = Math.min(1f, (now - span.startTime) / (float) FADE_IN_MS);
            if (!(text instanceof Spannable)) {
                spans.remove(i);
                continue;
            }
            final Spannable spannable = (Spannable) text;
            final int st = spannable.getSpanStart(span), en = spannable.getSpanEnd(span);
            if (t >= 1f || st < 0 || en <= st) {
                // fully opaque first: text copied meanwhile may share this span object
                span.alpha = 1f;
                spannable.removeSpan(span);
                spans.remove(i);
                continue;
            }
            span.alpha = CubicBezierInterpolator.EASE_OUT.getInterpolation(t);
            if (!canInvalidateText()) {
                // setting the span again makes the text view redraw this part of the text
                spannable.setSpan(span, st, en, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        for (int i = ghosts.size() - 1; i >= 0; i--) {
            if (now - ghosts.get(i).startTime >= FADE_OUT_MS) {
                ghosts.remove(i);
            }
        }
        if (canInvalidateText()) {
            ((EditTextBoldCursor) view).invalidateForce(); // redraws the cached text without a layout pass
        } else {
            view.invalidate();
        }
        if (!spans.isEmpty() || !ghosts.isEmpty()) {
            view.postOnAnimation(tick);
        } else {
            ticking = false;
        }
    }

    /**
     * Draws the fading erased letters. The canvas must be in the coordinates the cursor is drawn in:
     * translated by the left padding and the top of the text.
     */
    public void drawGhosts(Canvas canvas) {
        if (ghosts.isEmpty()) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        ghostPaint.set(view.getPaint());
        final int color = view.getCurrentTextColor();
        for (int i = 0; i < ghosts.size(); i++) {
            final Ghost ghost = ghosts.get(i);
            final float t = Math.min(1f, (now - ghost.startTime) / (float) FADE_OUT_MS);
            final float e = CubicBezierInterpolator.EASE_OUT.getInterpolation(t);
            ghostPaint.setColor(color);
            ghostPaint.setAlpha((int) (((color >>> 24) & 0xFF) * (1f - e)));
            final float width = ghostPaint.measureText(ghost.text);
            final float left = ghost.alignRight ? ghost.x - width : ghost.x;
            final float scale = 1f - 0.18f * e;
            canvas.save();
            canvas.translate(0, -dp(3) * e);
            canvas.scale(scale, scale, left + width / 2f, ghost.baseline - ghostPaint.getTextSize() / 3f);
            canvas.drawText(ghost.text, left, ghost.baseline, ghostPaint);
            canvas.restore();
        }
    }

    private boolean canInvalidateText() {
        return view instanceof EditTextBoldCursor && EditTextBoldCursor.canInvalidateTextDisplayList();
    }

    public boolean hasGhosts() {
        return !ghosts.isEmpty();
    }
}
