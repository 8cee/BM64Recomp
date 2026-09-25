package com.eightcee.bm64recomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.util.HashMap;
import java.util.Map;

public final class VirtualPadView extends View {
    public static final int BTN_A = 0;
    public static final int BTN_B = 1;
    public static final int BTN_Z = 2;
    public static final int BTN_L = 3;
    public static final int BTN_R = 4;
    public static final int BTN_START = 5;
    public static final int BTN_C_UP = 6;
    public static final int BTN_C_DOWN = 7;
    public static final int BTN_C_LEFT = 8;
    public static final int BTN_C_RIGHT = 9;
    public static final int BTN_DPAD_UP = 10;
    public static final int BTN_DPAD_DOWN = 11;
    public static final int BTN_DPAD_LEFT = 12;
    public static final int BTN_DPAD_RIGHT = 13;
    public static final int BTN_MENU = 14;

    private static final int GLASS = Color.argb(145, 14, 18, 24);
    private static final int BORDER = Color.argb(190, 235, 240, 245);
    private static final int A_BLUE = Color.rgb(76, 154, 255);
    private static final int B_GREEN = Color.rgb(63, 196, 101);
    private static final int C_YELLOW = Color.rgb(245, 211, 66);
    private static final int START_RED = Color.rgb(225, 77, 78);

    private static final class Button {
        final int id;
        final String label;
        final int tint;
        float x, y, r;
        int pointer = -1;
        Button(int id, String label, int tint) {
            this.id = id; this.label = label; this.tint = tint;
        }
        boolean hit(float px, float py) {
            float dx = px - x, dy = py - y;
            float hr = r * 1.55f;
            return dx * dx + dy * dy <= hr * hr;
        }
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Button[] buttons = {
            new Button(BTN_A, "A", A_BLUE),
            new Button(BTN_B, "B", B_GREEN),
            new Button(BTN_Z, "Z", Color.WHITE),
            new Button(BTN_L, "L", Color.WHITE),
            new Button(BTN_R, "R", Color.WHITE),
            new Button(BTN_START, "START", START_RED),
            new Button(BTN_C_UP, "▲", C_YELLOW),
            new Button(BTN_C_DOWN, "▼", C_YELLOW),
            new Button(BTN_C_LEFT, "◀", C_YELLOW),
            new Button(BTN_C_RIGHT, "▶", C_YELLOW),
            new Button(BTN_DPAD_UP, "▲", Color.WHITE),
            new Button(BTN_DPAD_DOWN, "▼", Color.WHITE),
            new Button(BTN_DPAD_LEFT, "◀", Color.WHITE),
            new Button(BTN_DPAD_RIGHT, "▶", Color.WHITE),
            new Button(BTN_MENU, "MENU", Color.WHITE)
    };

    private final Map<Integer, Integer> pointerToButton = new HashMap<>();
    private int stickPointer = -1;
    private float stickX, stickY, stickR, knobX, knobY;
    private float safeL, safeT, safeR, safeB;
    private boolean controlsVisible = true;
    private float toggleX, toggleY, toggleR;

    public VirtualPadView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(true);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2.2f);
        stroke.setColor(BORDER);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        setBackgroundColor(Color.TRANSPARENT);
    }

    private native void nativeButton(int id, boolean pressed);
    private native void nativeAxis(float x, float y);

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
            safeL = insets.getDisplayCutout().getSafeInsetLeft();
            safeT = insets.getDisplayCutout().getSafeInsetTop();
            safeR = insets.getDisplayCutout().getSafeInsetRight();
            safeB = insets.getDisplayCutout().getSafeInsetBottom();
            requestLayout();
        }
        return insets;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        layoutControls(w, h);
    }

    private void layoutControls(int w, int h) {
        float left = safeL + 12f, top = safeT + 8f;
        float right = w - safeR - 12f, bottom = h - safeB - 10f;
        float aw = right - left, ah = bottom - top;
        float u = Math.min(aw / 1280f, ah / 720f);
        if (u <= 0f) u = 1f;

        stickX = left + aw * 0.13f;
        stickY = top + ah * 0.67f;
        stickR = 100f * u;

        set(BTN_A, left + aw * .89f, top + ah * .63f, 54f*u);
        set(BTN_B, left + aw * .80f, top + ah * .76f, 48f*u);
        set(BTN_START, left + aw * .50f, top + ah * .88f, 38f*u);
        set(BTN_MENU, left + aw * .64f, top + ah * .89f, 34f*u);

        float cx = left + aw * .77f, cy = top + ah * .40f;
        set(BTN_C_UP, cx, cy - 73f*u, 34f*u);
        set(BTN_C_DOWN, cx, cy + 73f*u, 34f*u);
        set(BTN_C_LEFT, cx - 82f*u, cy, 34f*u);
        set(BTN_C_RIGHT, cx + 82f*u, cy, 34f*u);

        set(BTN_Z, left + aw * .05f, top + ah * .08f, 45f*u);
        set(BTN_L, left + aw * .42f, top + ah * .08f, 43f*u);
        set(BTN_R, left + aw * .94f, top + ah * .08f, 45f*u);

        float dx = left + aw * .32f, dy = top + ah * .68f, dr = 31f*u, ds = 59f*u;
        set(BTN_DPAD_UP, dx, dy-ds, dr);
        set(BTN_DPAD_DOWN, dx, dy+ds, dr);
        set(BTN_DPAD_LEFT, dx-ds, dy, dr);
        set(BTN_DPAD_RIGHT, dx+ds, dy, dr);

        toggleX = left + aw * .96f;
        toggleY = top + ah * .91f;
        toggleR = 28f*u;
        invalidate();
    }

    private void set(int id, float x, float y, float r) {
        for (Button b : buttons) if (b.id == id) {
            b.x=x; b.y=y; b.r=r; return;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        drawToggle(c);
        if (!controlsVisible) return;

        fill.setColor(Color.argb(80, 20, 24, 30));
        c.drawCircle(stickX, stickY, stickR, fill);
        stroke.setColor(BORDER);
        stroke.setStrokeWidth(Math.max(2f, stickR * .025f));
        c.drawCircle(stickX, stickY, stickR, stroke);
        float kr = stickR * .48f;
        fill.setColor(GLASS);
        c.drawCircle(stickX + knobX * stickR * .52f, stickY - knobY * stickR * .52f, kr, fill);
        c.drawCircle(stickX + knobX * stickR * .52f, stickY - knobY * stickR * .52f, kr, stroke);

        for (Button b : buttons) drawButton(c, b);
    }

    private void drawButton(Canvas c, Button b) {
        boolean down = b.pointer != -1;
        fill.setColor(down ? withAlpha(b.tint, 205) : GLASS);
        stroke.setColor(withAlpha(b.tint, down ? 255 : 175));
        c.drawCircle(b.x, b.y, b.r * (down ? .93f : 1f), fill);
        c.drawCircle(b.x, b.y, b.r * (down ? .93f : 1f), stroke);
        text.setColor(withAlpha(b.tint, 245));
        text.setTextSize(Math.max(14f, b.r * (b.label.length() > 2 ? .48f : .78f)));
        Paint.FontMetrics fm = text.getFontMetrics();
        float ty = b.y - (fm.ascent + fm.descent) / 2f;
        c.drawText(b.label, b.x, ty, text);
    }

    private void drawToggle(Canvas c) {
        fill.setColor(Color.argb(150, 12, 15, 20));
        stroke.setColor(BORDER);
        c.drawCircle(toggleX, toggleY, toggleR, fill);
        c.drawCircle(toggleX, toggleY, toggleR, stroke);
        text.setTextSize(Math.max(12f, toggleR * .8f));
        text.setColor(Color.WHITE);
        Paint.FontMetrics fm=text.getFontMetrics();
        c.drawText(controlsVisible ? "×" : "☰", toggleX,
                toggleY-(fm.ascent+fm.descent)/2f, text);
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int index = e.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int pid=e.getPointerId(index);
            float x=e.getX(index), y=e.getY(index);
            if (dist2(x,y,toggleX,toggleY) <= toggleR*toggleR*2.2f) {
                controlsVisible=!controlsVisible;
                if (!controlsVisible) releaseAll();
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                invalidate();
                return true;
            }
            if (!controlsVisible) return false;

            if (dist2(x,y,stickX,stickY) <= stickR*stickR*1.75f && stickPointer == -1) {
                stickPointer=pid;
                updateStick(x,y);
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                return true;
            }
            Button best=null; float bestD=Float.MAX_VALUE;
            for (Button b:buttons) if (b.pointer==-1 && b.hit(x,y)) {
                float d=dist2(x,y,b.x,b.y);
                if (d<bestD) { bestD=d; best=b; }
            }
            if (best!=null) {
                best.pointer=pid;
                pointerToButton.put(pid,best.id);
                nativeButton(best.id,true);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                invalidate();
                return true;
            }
            return false;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (!controlsVisible) return false;
            boolean used=false;
            for (int i=0;i<e.getPointerCount();i++) {
                int pid=e.getPointerId(i);
                if (pid==stickPointer) {
                    updateStick(e.getX(i),e.getY(i));
                    used=true;
                }
            }
            return used || !pointerToButton.isEmpty();
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int pid=e.getPointerId(index);
            releasePointer(pid);
            return true;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            releaseAll();
            return true;
        }
        return false;
    }

    private void updateStick(float x,float y) {
        float dx=(x-stickX)/stickR;
        float dy=-(y-stickY)/stickR;
        float mag=(float)Math.sqrt(dx*dx+dy*dy);
        if (mag>1f) { dx/=mag; dy/=mag; mag=1f; }
        final float dead=.08f;
        if (mag<dead) { dx=dy=0f; }
        else if (mag>0f) {
            float scaled=(mag-dead)/(1f-dead);
            dx=dx/mag*scaled; dy=dy/mag*scaled;
        }
        knobX=dx; knobY=dy;
        nativeAxis(dx,dy);
        invalidate();
    }

    private void releasePointer(int pid) {
        if (pid==stickPointer) {
            stickPointer=-1; knobX=knobY=0f; nativeAxis(0f,0f);
        }
        Integer id=pointerToButton.remove(pid);
        if (id!=null) for(Button b:buttons) if(b.id==id && b.pointer==pid) {
            b.pointer=-1; nativeButton(b.id,false); break;
        }
        invalidate();
    }

    private void releaseAll() {
        stickPointer=-1; knobX=knobY=0f; nativeAxis(0f,0f);
        pointerToButton.clear();
        for(Button b:buttons) if(b.pointer!=-1) {
            b.pointer=-1; nativeButton(b.id,false);
        }
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        releaseAll();
        super.onDetachedFromWindow();
    }

    private static float dist2(float x1,float y1,float x2,float y2) {
        float dx=x1-x2, dy=y1-y2; return dx*dx+dy*dy;
    }
}
