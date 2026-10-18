package com.example.hercules;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.OnTouchListener;

/**
 * On-screen D-pad + face-buttons overlay drawn above the game surface.
 *
 * Touch gestures are mapped to PSX buttons and reported through JniBridge.setTouchPad(), which the C++ side
 * forwards into psx-recomp's pad hook (`PadExtHooks.capture_local()`). Physical/BT gamepads take precedence —
 * see HerculesActivity / pad_external_input.c::pad_ext_resolve() for the AND-masking gate.
 */
public class HerculesGameView extends SurfaceView implements OnTouchListener {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int pressedButtons = 0;

    // Rectangular tap regions (fractions of the view, set in onSizeChanged).
    private final RectF dpad     = new RectF();   // D-pad — lower-left corner
    private final RectF faceBtns = new RectF();    // △ ○ ✕ □ — lower-right corner

    public HerculesGameView(Context context) {
        super(context);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setOnTouchListener(this);
        paint.setColor(0x55FFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(6f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float inset = Math.min(w, h) * 0.08f;
        dpad.set(inset, h - inset * 5f, inset * 3f, h - inset);          // vertical D-pad column
        faceBtns.set(w - inset * 4f, h - inset * 4f, w - inset, h - inset); // square button cluster
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = dpad.centerX(), cy = dpad.centerY();
        float bw = dpad.width() * 0.34f, bh = dpad.height() * 0.30f;
        canvas.drawRect(cx - bw, cy - bh / 3f, cx + bw, cy + bh / 3f, paint);   // horizontal bar
        canvas.drawRect(cx - bw / 3f, cy - bh, cx + bw / 3f, cy + bh, paint);  // vertical bar

        float r = faceBtns.width() * 0.16f;
        float fx = faceBtns.centerX(), fy = faceBtns.centerY();
        canvas.drawCircle(fx, fy - r, r, paint); // △ top
        canvas.drawCircle(fx, fy + r, r, paint); // ○ bottom
        canvas.drawCircle(fx - r, fy, r, paint); // ✕ left
        canvas.drawCircle(fx + r, fy, r, paint); // □ right
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                update(maskFor(event.getX(), event.getY()));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                update(0);
                return true;
        }
        return false;
    }

    /** Compute which face-button / dpad bits are held for a given pointer location. */
    private int maskFor(float x, float y) {
        int m = 0;
        if (dpad.contains(x, y - dpad.height() * 0.25f)) m |= JniBridge.DPAD_UP;
        else if (dpad.contains(x, y + dpad.height() * 0.25f)) m |= JniBridge.DPAD_DOWN;
        else if (dpad.contains(x - dpad.width() * 0.25f, y)) m |= JniBridge.DPAD_LEFT;
        else if (dpad.contains(x + dpad.width() * 0.25f, y)) m |= JniBridge.DPAD_RIGHT;

        if (faceBtns.contains(x, y - faceBtns.height() * 0.35f)) m |= JniBridge.TRIANGLE;
        else if (faceBtns.contains(x, y + faceBtns.height() * 0.35f)) m |= JniBridge.CIRCLE;
        else if (faceBtns.contains(x - faceBtns.width() * 0.35f, y)) m |= JniBridge.CROSS;
        else if (faceBtns.contains(x + faceBtns.width() * 0.35f, y)) m |= JniBridge.SQUARE;
        return m;
    }

    private void update(int newMask) {
        pressedButtons = newMask;
        JniBridge.setTouchPad(newMask, 0f, 0f);
    }
}
