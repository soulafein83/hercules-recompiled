package com.example.hercules;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.libsdl.app.SDLActivity;

import java.util.HashMap;
import java.util.Map;

/**
 * On-screen D-pad + face buttons + Start/Select drawn on top of SDL's surface.
 *
 * Touches become Android key events through
 * {@link SDLActivity#onNativeKeyDown(int)} / {@link SDLActivity#onNativeKeyUp(int)},
 * which SDL forwards to the runtime. keybinds.ini maps them to the PSX pad
 * (X=cross, S=circle, Z=square, A=triangle, Return=start, ...).
 *
 * The runtime samples the pad once per guest frame (~20 ms), so every press is
 * held for at least {@link #MIN_HOLD_MS} before the key-up is sent; a very short
 * tap would otherwise be missed.
 *
 * Touches outside every control are not consumed, so they fall through to SDL.
 */
public class HerculesGameView extends View {

    /** Minimum time a synthetic key stays down, so the 50 Hz pad poll sees it. */
    private static final long MIN_HOLD_MS = 110;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final RectF dpad = new RectF();
    private final RectF face = new RectF();
    private final RectF startBtn = new RectF();
    private final RectF selectBtn = new RectF();

    private float faceRadius;
    private float faceSpacing;
    private float dpadBar;

    /** pointer id -> keycode currently held by that pointer */
    private final Map<Integer, Integer> pointerKeys = new HashMap<>();
    private final Map<Integer, Long> pointerDownAt = new HashMap<>();
    /** keycode -> generation, so a delayed key-up never cancels a newer press */
    private final Map<Integer, Integer> keyGen = new HashMap<>();

    public HerculesGameView(Context context) {
        super(context);
        setFocusable(false);
        setWillNotDraw(false);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x55FFFFFF);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(5f);
        stroke.setColor(0xBBFFFFFF);
        text.setColor(0xDDFFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);

        // Big, thumb-sized control clusters (~42% of the short edge).
        float u = Math.min(w, h) * 0.42f;
        float m = u * 0.10f;
        dpad.set(m, h - u - m, u + m, h - m);
        face.set(w - u - m, h - u - m, w - m, h - m);

        // Four circles in a diamond: neighbours on the diagonal are
        // spacing*sqrt(2) apart, so spacing must exceed 2r/sqrt(2) = 1.414r.
        // 1.75r leaves a clear gap.
        faceRadius = u * 0.17f;
        faceSpacing = faceRadius * 1.75f;

        dpadBar = u * 0.30f;

        float bw = u * 0.38f, bh = u * 0.14f;
        float cx = w / 2f;
        selectBtn.set(cx - bw - m, h - bh - m, cx - m, h - m);
        startBtn.set(cx + m, h - bh - m, cx + bw + m, h - m);
        text.setTextSize(bh * 0.60f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // D-pad cross.
        float dx = dpad.centerX(), dy = dpad.centerY();
        float halfLen = dpad.width() * 0.46f;
        float halfThick = dpadBar * 0.5f;
        canvas.drawRoundRect(dx - halfLen, dy - halfThick, dx + halfLen, dy + halfThick,
                halfThick, halfThick, fill);
        canvas.drawRoundRect(dx - halfThick, dy - halfLen, dx + halfThick, dy + halfLen,
                halfThick, halfThick, fill);

        // Face buttons: triangle up, cross down, square left, circle right.
        float fx = face.centerX(), fy = face.centerY();
        canvas.drawCircle(fx, fy - faceSpacing, faceRadius, fill);
        canvas.drawCircle(fx, fy + faceSpacing, faceRadius, fill);
        canvas.drawCircle(fx - faceSpacing, fy, faceRadius, fill);
        canvas.drawCircle(fx + faceSpacing, fy, faceRadius, fill);

        // Start / Select pills.
        float rr = startBtn.height() / 2f;
        canvas.drawRoundRect(startBtn, rr, rr, stroke);
        canvas.drawRoundRect(selectBtn, rr, rr, stroke);
        canvas.drawText("START", startBtn.centerX(), startBtn.centerY() + text.getTextSize() * 0.35f, text);
        canvas.drawText("SELECT", selectBtn.centerX(), selectBtn.centerY() + text.getTextSize() * 0.35f, text);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                int key = keyFor(e.getX(i), e.getY(i));
                if (key == 0) {
                    // Outside every control: let SDL's surface receive the gesture
                    // unless we are already holding a control (multi-touch).
                    return !pointerKeys.isEmpty();
                }
                press(e.getPointerId(i), key);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    int key = keyFor(e.getX(i), e.getY(i));
                    if (key == 0) release(id);
                    else press(id, key);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                release(e.getPointerId(e.getActionIndex()));
                return true;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                return true;
        }
        return false;
    }

    private void press(int pointerId, int key) {
        Integer old = pointerKeys.get(pointerId);
        if (old != null && old == key) return;
        if (old != null) release(pointerId);
        pointerKeys.put(pointerId, key);
        pointerDownAt.put(pointerId, SystemClock.uptimeMillis());
        keyGen.merge(key, 1, Integer::sum);
        SDLActivity.onNativeKeyDown(key);
    }

    private void release(int pointerId) {
        Integer key = pointerKeys.remove(pointerId);
        Long downAt = pointerDownAt.remove(pointerId);
        if (key == null) return;

        long held = downAt == null ? MIN_HOLD_MS : SystemClock.uptimeMillis() - downAt;
        long delay = Math.max(0L, MIN_HOLD_MS - held);
        final int k = key;
        final int gen = keyGen.getOrDefault(k, 0);
        if (delay == 0L) {
            SDLActivity.onNativeKeyUp(k);
        } else {
            handler.postDelayed(() -> {
                // Skip the stale up if the same button was pressed again.
                if (keyGen.getOrDefault(k, 0) == gen) SDLActivity.onNativeKeyUp(k);
            }, delay);
        }
    }

    private void releaseAll() {
        for (Integer k : pointerKeys.values()) SDLActivity.onNativeKeyUp(k);
        pointerKeys.clear();
        pointerDownAt.clear();
    }

    /** Resolve a screen position to an Android keycode, or 0 outside every control. */
    private int keyFor(float x, float y) {
        if (dpad.contains(x, y)) {
            float dx = x - dpad.centerX();
            float dy = y - dpad.centerY();
            if (Math.abs(dx) > Math.abs(dy)) {
                return dx < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
            }
            return dy < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
        }
        if (face.contains(x, y)) {
            float fx = face.centerX(), fy = face.centerY();
            if (within(x, y, fx, fy - faceSpacing)) return KeyEvent.KEYCODE_A; // triangle
            if (within(x, y, fx, fy + faceSpacing)) return KeyEvent.KEYCODE_X; // cross
            if (within(x, y, fx - faceSpacing, fy)) return KeyEvent.KEYCODE_Z; // square
            if (within(x, y, fx + faceSpacing, fy)) return KeyEvent.KEYCODE_S; // circle
            return 0;
        }
        if (startBtn.contains(x, y)) return KeyEvent.KEYCODE_ENTER;
        if (selectBtn.contains(x, y)) return KeyEvent.KEYCODE_SHIFT_RIGHT;
        return 0;
    }

    private boolean within(float x, float y, float cx, float cy) {
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= faceRadius * faceRadius;
    }
}