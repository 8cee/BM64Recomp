package com.eightcee.bm64recomp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
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

    private static final float STICK_DEADZONE = 0.08f;
    private static final float HIT_SCALE = 1.45f;
    private static final float EXIT_HYSTERESIS = 1.75f;
    private static final float PRESS_IN_MS = 90f;
    private static final float PRESS_OUT_MS = 140f;
    private static final float HUD_FADE_MS = 200f;
    private static final float KNOB_RETURN_TAU = 60f;

    private static final int GLASS_RGB = Color.rgb(10, 13, 18);
    private static final int GLASS_ALPHA = 140;
    private static final int SHADOW_COLOR = 0x59000000;
    private static final int BORDER_ALPHA = 110;
    private static final int LABEL_ALPHA = 217;

    // Same N64 identity used by the DK64 Android pad.
    private static final int ACCENT = 0xFF9BD32B;
    private static final int TINT_A = 0xFF7FB2E5;
    private static final int TINT_B = 0xFF63C46B;
    private static final int TINT_C = 0xFFF6DC7A;
    private static final int TINT_START = 0xFFF2707F;
    private static final int TINT_NEUTRAL = 0xFFE8ECEF;

    private static final int HILITE_TOP = 0x26FFFFFF;
    private static final int HILITE_BOT = 0x08FFFFFF;
    private static final int[] GLASS_STOPS = {HILITE_TOP, HILITE_BOT};
    private static final float[] TWO_STOPS = {0f, 1f};
    private static final float[] SHADOW_STOPS = {0.5f, 1f};
    private static final float[] GLOW_STOPS = {0.5f, 0.86f, 1f};

    private static final class RoundControl {
        final int id;
        final String label;
        final int tintIdle;
        final int tintPress;
        final boolean arrow;
        final float arrowDeg;
        final float labelScale;
        float x, y, r, hitR;
        int pointer = -1;
        float press = 0f;
        Shader glass, shadow, glow;

        RoundControl(int id, String label, int tintIdle, int tintPress,
                     float labelScale, boolean arrow, float arrowDeg) {
            this.id = id;
            this.label = label;
            this.tintIdle = tintIdle;
            this.tintPress = tintPress;
            this.labelScale = labelScale;
            this.arrow = arrow;
            this.arrowDeg = arrowDeg;
        }

        void layout(float x, float y, float r, float unit) {
            this.x = x; this.y = y; this.r = r;
            this.hitR = Math.max(r * HIT_SCALE, 44f * unit);
        }

        boolean hit(float px, float py) {
            return dist2(px, py, x, y) <= hitR * hitR;
        }

        boolean outsideExit(float px, float py) {
            float rr = hitR * EXIT_HYSTERESIS;
            return dist2(px, py, x, y) > rr * rr;
        }

        boolean down() { return pointer != -1; }
    }

    private static final class PillControl {
        final int id;
        final String label;
        final int tintIdle;
        final int tintPress;
        final RectF rect = new RectF();
        final RectF hitRect = new RectF();
        final RectF exitRect = new RectF();
        final RectF shadowRect = new RectF();
        int pointer = -1;
        float press = 0f;
        Shader glass, shadow;

        PillControl(int id, String label, int tintIdle, int tintPress) {
            this.id = id;
            this.label = label;
            this.tintIdle = tintIdle;
            this.tintPress = tintPress;
        }

        void layout(float l, float t, float r, float b) {
            rect.set(l, t, r, b);
            float m = rect.height() * 0.18f;
            hitRect.set(rect); hitRect.inset(-m, -m);
            exitRect.set(rect); exitRect.inset(-m * EXIT_HYSTERESIS, -m * EXIT_HYSTERESIS);
            shadowRect.set(rect); shadowRect.inset(-rect.height() * 0.14f, -rect.height() * 0.14f);
        }

        boolean hit(float x, float y) { return hitRect.contains(x, y); }
        boolean outsideExit(float x, float y) { return !exitRect.contains(x, y); }
        boolean down() { return pointer != -1; }
    }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrowPath = new Path();

    private final RoundControl btnA =
            new RoundControl(BTN_A, "A", TINT_A, TINT_A, .85f, false, 0f);
    private final RoundControl btnB =
            new RoundControl(BTN_B, "B", TINT_B, TINT_B, .85f, false, 0f);
    private final RoundControl btnStart =
            new RoundControl(BTN_START, "START", TINT_START, TINT_START, .55f, false, 0f);
    private final RoundControl btnMenu =
            new RoundControl(BTN_MENU, "", TINT_NEUTRAL, ACCENT, .8f, false, 0f);

    private final RoundControl btnCU =
            new RoundControl(BTN_C_UP, "", TINT_C, TINT_C, .8f, true, -90f);
    private final RoundControl btnCD =
            new RoundControl(BTN_C_DOWN, "", TINT_C, TINT_C, .8f, true, 90f);
    private final RoundControl btnCL =
            new RoundControl(BTN_C_LEFT, "", TINT_C, TINT_C, .8f, true, 180f);
    private final RoundControl btnCR =
            new RoundControl(BTN_C_RIGHT, "", TINT_C, TINT_C, .8f, true, 0f);

    private final RoundControl btnDU =
            new RoundControl(BTN_DPAD_UP, "", TINT_NEUTRAL, ACCENT, .8f, true, -90f);
    private final RoundControl btnDD =
            new RoundControl(BTN_DPAD_DOWN, "", TINT_NEUTRAL, ACCENT, .8f, true, 90f);
    private final RoundControl btnDL =
            new RoundControl(BTN_DPAD_LEFT, "", TINT_NEUTRAL, ACCENT, .8f, true, 180f);
    private final RoundControl btnDR =
            new RoundControl(BTN_DPAD_RIGHT, "", TINT_NEUTRAL, ACCENT, .8f, true, 0f);

    private final RoundControl[] roundButtons = {
            btnA, btnB, btnCU, btnCD, btnCL, btnCR,
            btnStart, btnMenu, btnDU, btnDD, btnDL, btnDR
    };

    private final PillControl pillL = new PillControl(BTN_L, "L", TINT_NEUTRAL, ACCENT);
    private final PillControl pillZ = new PillControl(BTN_Z, "Z", TINT_NEUTRAL, ACCENT);
    private final PillControl pillR = new PillControl(BTN_R, "R", TINT_NEUTRAL, ACCENT);
    private final PillControl[] pills = {pillL, pillZ, pillR};

    private final Map<Integer, Integer> pointerToRound = new HashMap<>();

    private float unit = 1f;
    private float safeL, safeT, safeR, safeB;

    private float stickX, stickY, stickOuterR, stickKnobR;
    private float stickDx, stickDy, stickKx, stickKy;
    private float sentStickX, sentStickY;
    private int stickPointer = -1;
    private float stickPress = 0f;
    private Shader stickGlass, stickShadow, stickGlow, stickKnobGrad;

    private float toggleX, toggleY, toggleR, toggleHitR;
    private int togglePointer = -1;
    private float togglePress = 0f;
    private float toggleFlash = 0f;
    private Shader toggleScrim;

    private boolean controlsVisible = true;
    private boolean nativeControlsActive = false;
    private boolean lifecycleActive = true;
    private float hudAlpha = 0f;
    private long lastFrame = 0L;

    private int lastStickTick = -1;
    private long lastStickHaptic = 0L;

    private final Runnable visibilityPoll = new Runnable() {
        @Override public void run() {
            boolean active = false;
            try {
                active = nativeControlsActive();
            } catch (UnsatisfiedLinkError ignored) { }
            active = active && lifecycleActive;

            if (nativeControlsActive != active) {
                nativeControlsActive = active;
                if (!active) releaseAll();
                invalidate();
            }
            postDelayed(this, 150L);
        }
    };

    public VirtualPadView(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(true);
        setBackgroundColor(Color.TRANSPARENT);

        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));

        post(visibilityPoll);
    }

    private native void nativeButton(int id, boolean pressed);
    private native void nativeAxis(float x, float y);
    private native boolean nativeControlsActive();

    public void setLifecycleActive(boolean active) {
        lifecycleActive = active;
        if (!active) {
            nativeControlsActive = false;
            releaseAll();
        }
        invalidate();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
            safeL = insets.getDisplayCutout().getSafeInsetLeft();
            safeT = insets.getDisplayCutout().getSafeInsetTop();
            safeR = insets.getDisplayCutout().getSafeInsetRight();
            safeB = insets.getDisplayCutout().getSafeInsetBottom();
            applyLayout();
        }
        return insets;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        applyLayout();
    }

    private void applyLayout() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        float fw = w, fh = h;
        float base = Math.min(fh / 720f, fw / 1280f);
        float extraSide = Build.VERSION.SDK_INT < 28 ? 12f * base : 0f;
        float margin = 6f * base;

        float left = safeL + margin + extraSide;
        float top = safeT + margin;
        float right = fw - safeR - margin - extraSide;
        float bottom = fh - safeB - margin;
        float aw = right - left;
        float ah = bottom - top;
        if (aw <= 0f || ah <= 0f) return;

        unit = Math.min(ah / 720f, aw / 1280f);

        float trigW = 185f * unit;
        float trigH = 90f * unit;
        float trigY = top + ah * .04f;
        pillZ.layout(left + aw * .035f, trigY,
                left + aw * .035f + trigW, trigY + trigH);
        pillL.layout(left + aw * .5f - trigW / 2f, trigY,
                left + aw * .5f + trigW / 2f, trigY + trigH);
        pillR.layout(left + aw * .965f - trigW, trigY,
                left + aw * .965f, trigY + trigH);

        stickX = left + aw * .12f;
        stickY = top + ah * .63f;
        stickOuterR = 96f * unit;
        stickKnobR = 47f * unit;

        btnStart.layout(left + aw * .475f, top + ah * .87f, 34f * unit, unit);
        btnMenu.layout(left + aw * .655f, top + ah * .88f, 30f * unit, unit);

        float ccx = left + aw * .76f;
        float ccy = top + ah * .42f;
        float spreadX = 84f * unit;
        float spreadY = 70f * unit;
        float cR = 33f * unit;
        btnCU.layout(ccx, ccy - spreadY, cR, unit);
        btnCD.layout(ccx, ccy + spreadY, cR, unit);
        btnCL.layout(ccx - spreadX, ccy, cR, unit);
        btnCR.layout(ccx + spreadX, ccy, cR, unit);

        btnA.layout(left + aw * .88f, top + ah * .58f, 52f * unit, unit);
        btnB.layout(left + aw * .79f, top + ah * .72f, 37f * unit, unit);

        // BM64 needs the D-pad, so keep it but use the same glass language.
        float dcx = left + aw * .31f;
        float dcy = top + ah * .69f;
        float dSpread = 52f * unit;
        float dR = 28f * unit;
        btnDU.layout(dcx, dcy - dSpread, dR, unit);
        btnDD.layout(dcx, dcy + dSpread, dR, unit);
        btnDL.layout(dcx - dSpread, dcy, dR, unit);
        btnDR.layout(dcx + dSpread, dcy, dR, unit);

        toggleX = left + aw * .94f;
        toggleY = top + ah * .89f;
        toggleR = 24f * unit;
        toggleHitR = Math.max(toggleR * 1.5f, 44f * unit);

        rebuildShaders();
        invalidate();
    }

    private void rebuildShaders() {
        for (RoundControl b : roundButtons) {
            b.glass = new LinearGradient(b.x, b.y - b.r, b.x, b.y + b.r,
                    GLASS_STOPS, TWO_STOPS, Shader.TileMode.CLAMP);
            b.shadow = new RadialGradient(b.x, b.y, b.r * 1.5f,
                    new int[]{SHADOW_COLOR, Color.TRANSPARENT},
                    SHADOW_STOPS, Shader.TileMode.CLAMP);
            b.glow = new RadialGradient(b.x, b.y, b.r * 1.38f,
                    new int[]{Color.TRANSPARENT, b.tintPress, Color.TRANSPARENT},
                    GLOW_STOPS, Shader.TileMode.CLAMP);
        }

        for (PillControl p : pills) {
            float cx = p.rect.centerX(), cy = p.rect.centerY();
            p.glass = new LinearGradient(cx, p.rect.top, cx, p.rect.bottom,
                    GLASS_STOPS, TWO_STOPS, Shader.TileMode.CLAMP);
            p.shadow = new RadialGradient(cx, cy,
                    Math.max(p.rect.width(), p.rect.height()) * .72f,
                    new int[]{SHADOW_COLOR, Color.TRANSPARENT},
                    SHADOW_STOPS, Shader.TileMode.CLAMP);
        }

        stickGlass = new LinearGradient(stickX, stickY - stickOuterR,
                stickX, stickY + stickOuterR,
                GLASS_STOPS, TWO_STOPS, Shader.TileMode.CLAMP);
        stickShadow = new RadialGradient(stickX, stickY, stickOuterR * 1.45f,
                new int[]{SHADOW_COLOR, Color.TRANSPARENT},
                SHADOW_STOPS, Shader.TileMode.CLAMP);
        stickGlow = new RadialGradient(stickX, stickY, stickOuterR * 1.3f,
                new int[]{Color.TRANSPARENT, ACCENT, Color.TRANSPARENT},
                GLOW_STOPS, Shader.TileMode.CLAMP);
        stickKnobGrad = new RadialGradient(stickX, stickY, stickKnobR,
                GLASS_STOPS, TWO_STOPS, Shader.TileMode.CLAMP);

        toggleScrim = new RadialGradient(toggleX, toggleY, toggleR * 2.4f,
                new int[]{0x5A000000, Color.TRANSPARENT},
                SHADOW_STOPS, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!nativeControlsActive) return;

        long now = SystemClock.elapsedRealtime();
        float dt = lastFrame == 0L ? 16f : Math.min(50f, Math.max(1f, now - lastFrame));
        lastFrame = now;

        boolean animating = advanceAnimations(dt);
        drawToggle(canvas);

        if (hudAlpha > .01f) {
            canvas.save();
            canvas.translate(0f, (1f - hudAlpha) * 24f * unit);
            for (PillControl p : pills) drawPill(canvas, p);
            drawStick(canvas);
            for (RoundControl b : roundButtons) drawRound(canvas, b);
            canvas.restore();
        }

        if (animating) postInvalidateOnAnimation();
    }

    private void drawRound(Canvas canvas, RoundControl b) {
        float e = ease(b.press);
        float scale = 1f - .06f * e;
        canvas.save();
        canvas.scale(scale, scale, b.x, b.y);

        if (b.shadow != null) {
            fill.setShader(b.shadow); fill.setAlpha((int)(255 * hudAlpha));
            canvas.drawCircle(b.x, b.y, b.r * 1.5f, fill);
            fill.setShader(null);
        }

        fill.setColor(GLASS_RGB); fill.setAlpha((int)(GLASS_ALPHA * hudAlpha));
        canvas.drawCircle(b.x, b.y, b.r, fill);

        if (b.glass != null) {
            fill.setShader(b.glass); fill.setAlpha((int)(255 * hudAlpha));
            canvas.drawCircle(b.x, b.y, b.r, fill);
            fill.setShader(null);
        }

        if (e > 0f) {
            fill.setColor(Color.WHITE); fill.setAlpha((int)(0x26 * e * hudAlpha));
            canvas.drawCircle(b.x, b.y, b.r, fill);
        }

        if (e > 0f && b.glow != null) {
            fill.setShader(b.glow); fill.setAlpha((int)(200 * e * hudAlpha));
            canvas.drawCircle(b.x, b.y, b.r * 1.38f, fill);
            fill.setShader(null);
        }

        stroke.setStrokeWidth(2.5f * unit);
        stroke.setColor(lerpColor(b.tintIdle, b.tintPress, e));
        stroke.setAlpha((int)((BORDER_ALPHA + (255 - BORDER_ALPHA) * e) * hudAlpha));
        canvas.drawCircle(b.x, b.y, b.r, stroke);

        if (b.arrow) {
            fill.setColor(lerpColor(b.tintIdle, Color.WHITE, e));
            fill.setAlpha((int)(labelAlpha(e) * hudAlpha));
            drawArrow(canvas, b.x, b.y, b.r * .55f, b.arrowDeg);
        } else if (b.id == BTN_MENU) {
            stroke.setStrokeWidth(b.r * .14f);
            stroke.setColor(lerpColor(b.tintIdle, Color.WHITE, e));
            stroke.setAlpha((int)(labelAlpha(e) * hudAlpha));
            float w = b.r * .5f, gap = b.r * .30f;
            for (int i = -1; i <= 1; i++) {
                canvas.drawLine(b.x - w, b.y + i * gap, b.x + w, b.y + i * gap, stroke);
            }
        } else {
            text.setTextSize(b.r * b.labelScale);
            text.setColor(lerpColor(b.tintIdle, Color.WHITE, e));
            text.setAlpha((int)(labelAlpha(e) * hudAlpha));
            canvas.drawText(b.label, b.x, b.y + text.getTextSize() * .35f, text);
        }

        canvas.restore();
        resetPaintAlpha();
    }

    private void drawPill(Canvas canvas, PillControl p) {
        float e = ease(p.press);
        float cx = p.rect.centerX(), cy = p.rect.centerY();
        float scale = 1f - .05f * e;
        float rad = p.rect.height() / 2f;

        canvas.save();
        canvas.scale(scale, scale, cx, cy);

        if (p.shadow != null) {
            fill.setShader(p.shadow); fill.setAlpha((int)(255 * hudAlpha));
            canvas.drawRoundRect(p.shadowRect, p.shadowRect.height()/2f,
                    p.shadowRect.height()/2f, fill);
            fill.setShader(null);
        }

        fill.setColor(GLASS_RGB); fill.setAlpha((int)(GLASS_ALPHA * hudAlpha));
        canvas.drawRoundRect(p.rect, rad, rad, fill);

        if (p.glass != null) {
            fill.setShader(p.glass); fill.setAlpha((int)(255 * hudAlpha));
            canvas.drawRoundRect(p.rect, rad, rad, fill);
            fill.setShader(null);
        }

        if (e > 0f) {
            fill.setColor(Color.WHITE); fill.setAlpha((int)(0x26 * e * hudAlpha));
            canvas.drawRoundRect(p.rect, rad, rad, fill);
        }

        stroke.setStrokeWidth(2.5f * unit);
        stroke.setColor(lerpColor(p.tintIdle, p.tintPress, e));
        stroke.setAlpha((int)((BORDER_ALPHA + (255 - BORDER_ALPHA) * e) * hudAlpha));
        canvas.drawRoundRect(p.rect, rad, rad, stroke);

        text.setTextSize(p.rect.height() * .40f);
        text.setColor(lerpColor(p.tintIdle, Color.WHITE, e));
        text.setAlpha((int)(labelAlpha(e) * hudAlpha));
        canvas.drawText(p.label, cx, cy + text.getTextSize() * .35f, text);

        canvas.restore();
        resetPaintAlpha();
    }

    private void drawStick(Canvas canvas) {
        float e = ease(stickPress);

        if (stickShadow != null) {
            fill.setShader(stickShadow); fill.setAlpha((int)(255 * hudAlpha));
            canvas.drawCircle(stickX, stickY, stickOuterR * 1.45f, fill);
            fill.setShader(null);
        }

        fill.setColor(GLASS_RGB); fill.setAlpha((int)(GLASS_ALPHA * hudAlpha));
        canvas.drawCircle(stickX, stickY, stickOuterR, fill);

        if (stickGlass != null) {
            fill.setShader(stickGlass); fill.setAlpha((int)(255 * hudAlpha));
            canvas.drawCircle(stickX, stickY, stickOuterR, fill);
            fill.setShader(null);
        }

        float tickIn = stickOuterR * .76f;
        float tickOut = stickOuterR * .88f;
        int activeTick = stickPointer != -1 && Math.hypot(stickDx, stickDy) > STICK_DEADZONE
                ? nearestTickIndex(stickDx, stickDy) : -1;

        stroke.setStrokeWidth(2f * unit);
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45f);
            float ca = (float)Math.cos(a), sa = (float)Math.sin(a);
            boolean on = i == activeTick;
            stroke.setColor(on ? ACCENT : Color.WHITE);
            stroke.setAlpha((int)((on ? 220 : 70) * hudAlpha));
            canvas.drawLine(stickX + ca * tickIn, stickY + sa * tickIn,
                    stickX + ca * tickOut, stickY + sa * tickOut, stroke);
        }

        stroke.setStrokeWidth(2.5f * unit);
        stroke.setColor(lerpColor(TINT_NEUTRAL, ACCENT, e));
        stroke.setAlpha((int)((BORDER_ALPHA + (255 - BORDER_ALPHA) * e) * hudAlpha));
        canvas.drawCircle(stickX, stickY, stickOuterR, stroke);

        if (e > 0f && stickGlow != null) {
            fill.setShader(stickGlow); fill.setAlpha((int)(160 * e * hudAlpha));
            canvas.drawCircle(stickX, stickY, stickOuterR * 1.3f, fill);
            fill.setShader(null);
        }

        float travel = (stickOuterR - stickKnobR) * .92f;
        float kx = stickX + stickKx * travel;
        float ky = stickY + stickKy * travel;

        fill.setColor(GLASS_RGB); fill.setAlpha((int)(210 * hudAlpha));
        canvas.drawCircle(kx, ky, stickKnobR, fill);

        // Move the highlight with the knob so it still feels physical.
        Shader knob = new RadialGradient(kx, ky, stickKnobR,
                GLASS_STOPS, TWO_STOPS, Shader.TileMode.CLAMP);
        fill.setShader(knob); fill.setAlpha((int)(255 * hudAlpha));
        canvas.drawCircle(kx, ky, stickKnobR, fill);
        fill.setShader(null);

        stroke.setStrokeWidth(2.2f * unit);
        stroke.setColor(lerpColor(TINT_NEUTRAL, ACCENT, e));
        stroke.setAlpha((int)((BORDER_ALPHA + 100 * e) * hudAlpha));
        canvas.drawCircle(kx, ky, stickKnobR, stroke);

        resetPaintAlpha();
    }

    private void drawToggle(Canvas canvas) {
        float e = ease(togglePress);
        float alpha = (.58f + .27f * (1f - hudAlpha)) * 255f;
        float scale = 1f - .06f * e;

        canvas.save();
        canvas.scale(scale, scale, toggleX, toggleY);

        if (toggleScrim != null) {
            fill.setShader(toggleScrim); fill.setAlpha(255);
            canvas.drawCircle(toggleX, toggleY, toggleR * 2.4f, fill);
            fill.setShader(null);
        }

        fill.setColor(GLASS_RGB);
        fill.setAlpha((int)(GLASS_ALPHA * alpha / 255f));
        canvas.drawCircle(toggleX, toggleY, toggleR, fill);

        stroke.setStrokeWidth(2f * unit);
        stroke.setColor(ACCENT);
        stroke.setAlpha((int)(alpha * Math.min(1f, toggleFlash + .35f)));
        canvas.drawCircle(toggleX, toggleY, toggleR, stroke);

        float w = toggleR * 1.05f, h = toggleR * .62f;
        stroke.setColor(Color.WHITE); stroke.setAlpha((int)(alpha * .9f));
        canvas.drawRoundRect(toggleX - w, toggleY - h, toggleX + w, toggleY + h, h, h, stroke);

        fill.setColor(Color.WHITE); fill.setAlpha((int)(alpha * .9f));
        canvas.drawCircle(toggleX - w * .42f, toggleY, toggleR * .10f, fill);
        canvas.drawCircle(toggleX + w * .42f, toggleY, toggleR * .10f, fill);

        if (controlsVisible) {
            float bx = toggleX + toggleR * .72f;
            float by = toggleY - toggleR * .72f;
            float br = toggleR * .42f;
            fill.setColor(GLASS_RGB); fill.setAlpha((int)(235 * alpha / 255f));
            canvas.drawCircle(bx, by, br, fill);
            stroke.setStrokeWidth(1.5f * unit);
            stroke.setColor(Color.WHITE); stroke.setAlpha((int)(alpha * .95f));
            canvas.drawCircle(bx, by, br, stroke);
            float k = br * .42f;
            canvas.drawLine(bx-k, by-k, bx+k, by+k, stroke);
            canvas.drawLine(bx-k, by+k, bx+k, by-k, stroke);
        }

        canvas.restore();
        resetPaintAlpha();
    }

    private void drawArrow(Canvas canvas, float x, float y, float size, float deg) {
        double a = Math.toRadians(deg);
        float tipX = x + size * (float)Math.cos(a);
        float tipY = y + size * (float)Math.sin(a);
        double a1 = a + 2.5, a2 = a - 2.5;

        arrowPath.reset();
        arrowPath.moveTo(tipX, tipY);
        arrowPath.lineTo(x + size * .45f * (float)Math.cos(a1),
                y + size * .45f * (float)Math.sin(a1));
        arrowPath.lineTo(x + size * .45f * (float)Math.cos(a2),
                y + size * .45f * (float)Math.sin(a2));
        arrowPath.close();
        canvas.drawPath(arrowPath, fill);
    }

    private boolean advanceAnimations(float dt) {
        boolean animating = false;

        for (RoundControl b : roundButtons) {
            float n = stepPress(b.press, b.down() ? 1f : 0f, dt);
            if (n != b.press) { b.press = n; animating = true; }
        }

        for (PillControl p : pills) {
            float n = stepPress(p.press, p.down() ? 1f : 0f, dt);
            if (n != p.press) { p.press = n; animating = true; }
        }

        float nStick = stepPress(stickPress, stickPointer != -1 ? 1f : 0f, dt);
        if (nStick != stickPress) { stickPress = nStick; animating = true; }

        float nToggle = stepPress(togglePress, togglePointer != -1 ? 1f : 0f, dt);
        if (nToggle != togglePress) { togglePress = nToggle; animating = true; }

        float k = 1f - (float)Math.exp(-dt / KNOB_RETURN_TAU);
        float nkx = stickKx + (stickDx - stickKx) * k;
        float nky = stickKy + (stickDy - stickKy) * k;
        if (Math.abs(nkx-stickKx) > .0005f || Math.abs(nky-stickKy) > .0005f) {
            stickKx = nkx; stickKy = nky; animating = true;
        }

        float target = controlsVisible ? 1f : 0f;
        if (hudAlpha != target) {
            float step = dt / HUD_FADE_MS;
            hudAlpha = hudAlpha < target ? Math.min(target, hudAlpha + step)
                    : Math.max(target, hudAlpha - step);
            if (Math.abs(target - hudAlpha) < .001f) hudAlpha = target;
            animating = true;
        }

        if (toggleFlash > 0f) {
            toggleFlash = Math.max(0f, toggleFlash - dt / 160f);
            animating = true;
        }

        return animating;
    }

    private static float stepPress(float current, float target, float dt) {
        if (Math.abs(target-current) <= .001f) return target;
        float rate = target > current ? dt / PRESS_IN_MS : dt / PRESS_OUT_MS;
        return target > current ? Math.min(target, current + rate)
                : Math.max(target, current - rate);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!nativeControlsActive) return false;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                int pid = e.getPointerId(i);
                if (claim(pid, e.getX(i), e.getY(i))) {
                    if (Build.VERSION.SDK_INT >= 21) requestUnbufferedDispatch(e);
                    postInvalidateOnAnimation();
                    return true;
                }
                return e.getActionMasked() != MotionEvent.ACTION_DOWN;
            }

            case MotionEvent.ACTION_MOVE: {
                boolean dirty = false;
                for (int i=0;i<e.getPointerCount();i++) {
                    int pid = e.getPointerId(i);
                    float x=e.getX(i), y=e.getY(i);

                    if (togglePointer == pid) {
                        if (!toggleHit(x,y)) togglePointer = -1;
                        dirty = true;
                    } else if (stickPointer == pid) {
                        updateStick(x,y);
                        dirty = true;
                    } else {
                        for (RoundControl b : roundButtons) {
                            if (b.pointer == pid && b.outsideExit(x,y)) {
                                b.pointer = -1;
                                pointerToRound.remove(pid);
                                nativeButton(b.id,false);
                                dirty = true;
                            }
                        }
                        for (PillControl p : pills) {
                            if (p.pointer == pid && p.outsideExit(x,y)) {
                                p.pointer = -1;
                                nativeButton(p.id,false);
                                dirty = true;
                            }
                        }
                    }
                }
                if (dirty) postInvalidateOnAnimation();
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                releasePointer(e.getPointerId(e.getActionIndex()));
                postInvalidateOnAnimation();
                return true;

            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                postInvalidateOnAnimation();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private boolean claim(int pid, float x, float y) {
        if (togglePointer == -1 && toggleHit(x,y)) {
            togglePointer = pid;
            return true;
        }
        if (!controlsVisible) return false;

        int kind = 0;
        float best = Float.MAX_VALUE;
        RoundControl bestRound = null;
        PillControl bestPill = null;

        if (stickPointer == -1 && dist2(x,y,stickX,stickY) <=
                (stickOuterR*1.35f)*(stickOuterR*1.35f)) {
            best = dist2(x,y,stickX,stickY);
            kind = 1;
        }

        for (RoundControl b : roundButtons) {
            if (!b.down() && b.hit(x,y)) {
                float d=dist2(x,y,b.x,b.y);
                if (d<best) { best=d; kind=2; bestRound=b; }
            }
        }

        for (PillControl p : pills) {
            if (!p.down() && p.hit(x,y)) {
                float d=dist2(x,y,p.rect.centerX(),p.rect.centerY());
                if (d<best) { best=d; kind=3; bestPill=p; }
            }
        }

        if (kind == 1) {
            stickPointer = pid;
            updateStick(x,y);
            haptic(HapticFeedbackConstants.CLOCK_TICK);
            return true;
        }
        if (kind == 2) {
            bestRound.pointer = pid;
            pointerToRound.put(pid,bestRound.id);
            nativeButton(bestRound.id,true);
            haptic(HapticFeedbackConstants.VIRTUAL_KEY);
            return true;
        }
        if (kind == 3) {
            bestPill.pointer = pid;
            nativeButton(bestPill.id,true);
            haptic(HapticFeedbackConstants.VIRTUAL_KEY);
            return true;
        }
        return false;
    }

    private void releasePointer(int pid) {
        if (togglePointer == pid) {
            togglePointer = -1;
            toggleFlash = 1f;
            haptic(HapticFeedbackConstants.VIRTUAL_KEY);
            controlsVisible = !controlsVisible;
            if (!controlsVisible) releaseGameplayControls();
            return;
        }

        if (stickPointer == pid) {
            stickPointer = -1;
            stickDx = stickDy = 0f;
            if (sentStickX != 0f || sentStickY != 0f) nativeAxis(0f,0f);
            sentStickX = sentStickY = 0f;
            lastStickTick = -1;
            return;
        }

        Integer id = pointerToRound.remove(pid);
        if (id != null) {
            for (RoundControl b : roundButtons) {
                if (b.id == id && b.pointer == pid) {
                    b.pointer = -1;
                    nativeButton(b.id,false);
                    return;
                }
            }
        }

        for (PillControl p : pills) {
            if (p.pointer == pid) {
                p.pointer = -1;
                nativeButton(p.id,false);
                return;
            }
        }
    }

    private void updateStick(float x, float y) {
        if (stickOuterR <= 0f) return;

        float dx=(x-stickX)/stickOuterR;
        float dy=(y-stickY)/stickOuterR;
        float mag=(float)Math.hypot(dx,dy);
        if (mag>1f) { dx/=mag; dy/=mag; mag=1f; }
        stickDx=dx; stickDy=dy;

        float ax=dx, ay=-dy;
        float aMag=(float)Math.hypot(ax,ay);
        if (aMag<=STICK_DEADZONE) {
            ax=ay=0f;
            lastStickTick=-1;
        } else {
            float scaled=Math.min((aMag-STICK_DEADZONE)/(1f-STICK_DEADZONE),1f);
            ax=ax/aMag*scaled;
            ay=ay/aMag*scaled;
            stickTickHaptic(nearestTickIndex(dx,dy));
        }

        nativeAxis(ax,ay);
        sentStickX=ax; sentStickY=ay;
    }

    private void stickTickHaptic(int idx) {
        if (idx < 0 || idx == lastStickTick) return;
        long now=SystemClock.elapsedRealtime();
        if (now-lastStickHaptic < 60L) return;
        lastStickHaptic=now;
        lastStickTick=idx;
        haptic(HapticFeedbackConstants.CLOCK_TICK);
    }

    private void releaseGameplayControls() {
        for (RoundControl b : roundButtons) {
            if (b.down()) nativeButton(b.id,false);
            b.pointer=-1;
        }
        for (PillControl p : pills) {
            if (p.down()) nativeButton(p.id,false);
            p.pointer=-1;
        }
        pointerToRound.clear();
        if (sentStickX!=0f || sentStickY!=0f) nativeAxis(0f,0f);
        stickPointer=-1;
        stickDx=stickDy=0f;
        sentStickX=sentStickY=0f;
        lastStickTick=-1;
    }

    private void releaseAll() {
        releaseGameplayControls();
        togglePointer=-1;
    }

    private boolean toggleHit(float x,float y) {
        return dist2(x,y,toggleX,toggleY) <= toggleHitR*toggleHitR;
    }

    private void haptic(int constant) {
        try { performHapticFeedback(constant); } catch (Throwable ignored) { }
    }

    private static float ease(float t) { return t*t*(3f-2f*t); }

    private static int labelAlpha(float e) {
        return (int)(LABEL_ALPHA + (255-LABEL_ALPHA)*e);
    }

    private static int lerpColor(int a,int b,float t) {
        int r=(int)(Color.red(a)+(Color.red(b)-Color.red(a))*t);
        int g=(int)(Color.green(a)+(Color.green(b)-Color.green(a))*t);
        int bl=(int)(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t);
        return Color.rgb(r,g,bl);
    }

    private static int nearestTickIndex(float dx,float dy) {
        if (dx==0f && dy==0f) return -1;
        float a=(float)Math.atan2(dy,dx);
        int idx=Math.round(a/((float)Math.PI/4f));
        return ((idx%8)+8)%8;
    }

    private void resetPaintAlpha() {
        fill.setShader(null); fill.setAlpha(255);
        stroke.setAlpha(255);
        text.setAlpha(255);
    }

    private static float dist2(float x1,float y1,float x2,float y2) {
        float dx=x1-x2, dy=y1-y2;
        return dx*dx+dy*dy;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) releaseAll();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(visibilityPoll);
        releaseAll();
        super.onDetachedFromWindow();
    }
}
