package com.cockblocker.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/** Draws the focus arc, progress handle, and centered session timer. */
final class FocusDialView extends View {
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix gradientRotation = new Matrix();
    private final Paint eyebrowPaint = textPaint(12, Surface.textMuted(getContext()));
    private final Paint valuePaint = textPaint(46, Surface.text(getContext()));
    private final Paint captionPaint = textPaint(11, Surface.textFaint(getContext()));
    private final RectF arcBounds = new RectF();
    private int gradientAccentColor = Color.TRANSPARENT;

    private SweepGradient progressGradient;
    private String eyebrow = "READY TO FOCUS";
    private String value = "15:00";
    private String caption = "MINUTES";
    private float progress = 1f;
    private int accentColor = Surface.accent(getContext());
    private String actionHint = "";

    FocusDialView(Context context) {
        this(context, null);
    }

    FocusDialView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(dp(9));
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(Surface.surfaceRaised(getContext()));

        progressGlowPaint.setStyle(Paint.Style.STROKE);
        progressGlowPaint.setStrokeWidth(dp(12));
        progressGlowPaint.setStrokeCap(Paint.Cap.ROUND);
        progressGlowPaint.setColor(accentColor);
        progressGlowPaint.setAlpha(105);
        progressGlowPaint.setShadowLayer(dp(13), 0f, dp(3), accentColor);

        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeWidth(dp(9));
        progressPaint.setStrokeCap(Paint.Cap.ROUND);

        knobPaint.setStyle(Paint.Style.FILL);
        knobPaint.setColor(accentColor);
        knobPaint.setShadowLayer(dp(10), 0f, dp(2), accentColor);
        knobCorePaint.setStyle(Paint.Style.FILL);
        valuePaint.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
    }

    private Paint textPaint(float sizeSp, int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTextSize(sizeSp * getResources().getDisplayMetrics().scaledDensity);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        return paint;
    }

    void render(String eyebrow, String value, String caption, float progress, int accentColor) {
        this.eyebrow = eyebrow;
        this.value = value;
        this.caption = caption;
        this.progress = Math.max(0f, Math.min(1f, progress));
        this.accentColor = accentColor;
        knobPaint.setColor(accentColor);
        knobPaint.setShadowLayer(dp(10), 0f, dp(2), accentColor);
        knobCorePaint.setColor(withAlpha(accentColor, 190));
        progressGlowPaint.setColor(accentColor);
        progressGlowPaint.setShadowLayer(dp(13), 0f, dp(3), accentColor);
        if (getWidth() > 0 && gradientAccentColor != accentColor) {
            updateGradient(getWidth(), getHeight());
        }
        updateContentDescription();
        invalidate();
    }
    void setActionHint(String hint) {
        actionHint = hint == null ? "" : hint;
        updateContentDescription();
    }

    private void updateContentDescription() {
        String description = eyebrow + ", " + value + " " + caption;
        if (!actionHint.isEmpty()) {
            description += ". " + actionHint;
        }
        setContentDescription(description);
    }

    private void updateGradient(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int[] colors = {
                Surface.accentSecondary(getContext()),
                accentColor,
                Surface.accentSecondary(getContext())
        };
        progressGradient = new SweepGradient(width / 2f, height / 2f, colors,
                new float[]{0f, 0.58f, 1f});
        progressPaint.setShader(progressGradient);
        progressGlowPaint.setShader(progressGradient);
        gradientAccentColor = accentColor;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desired = Math.round(dp(282));
        int width = resolveSize(desired, widthMeasureSpec);
        int height = resolveSize(desired, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        gradientAccentColor = Color.TRANSPARENT;
        updateGradient(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float radius = Math.min(getWidth(), getHeight()) * 0.405f;
        arcBounds.set(centerX - radius, centerY - radius,
                centerX + radius, centerY + radius);

        float startAngle = 135f;
        float trackSweep = 270f;
        float visibleSweep = 180f * progress;
        trackPaint.setColor(Surface.surfaceRaised(getContext()));
        canvas.drawArc(arcBounds, startAngle, trackSweep, false, trackPaint);

        if (visibleSweep > 0.5f) {
            gradientRotation.setRotate(startAngle, centerX, centerY);
            progressGradient.setLocalMatrix(gradientRotation);
            progressGlowPaint.setAlpha(82);
            canvas.drawArc(arcBounds, startAngle, visibleSweep, false, progressGlowPaint);
            canvas.drawArc(arcBounds, startAngle, visibleSweep, false, progressPaint);

            double knobAngle = Math.toRadians(startAngle + visibleSweep);
            float knobX = centerX + radius * (float) Math.cos(knobAngle);
            float knobY = centerY + radius * (float) Math.sin(knobAngle);
            canvas.drawCircle(knobX, knobY, dp(9), knobPaint);
            canvas.drawCircle(knobX, knobY, dp(5), knobCorePaint);
        }

        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;
        float availableHeight = getHeight();
        float maxValueSize = Math.max(dp(32), availableHeight * 0.27f);
        valuePaint.setTextSize(Math.min(46f * scaledDensity, maxValueSize));
        float maxValueWidth = getWidth() * 0.78f;
        float measuredValue = valuePaint.measureText(value);
        if (measuredValue > maxValueWidth) {
            valuePaint.setTextSize(valuePaint.getTextSize() * maxValueWidth / measuredValue);
        }
        eyebrowPaint.setTextSize(Math.min(12f * scaledDensity, availableHeight * 0.11f));
        captionPaint.setTextSize(Math.min(11f * scaledDensity, availableHeight * 0.10f));
        eyebrowPaint.setColor(Surface.textMuted(getContext()));
        valuePaint.setColor(Surface.text(getContext()));
        captionPaint.setColor(Surface.textFaint(getContext()));
        float eyebrowOffset = Math.max(dp(42), availableHeight * 0.20f);
        float captionOffset = Math.max(dp(42), availableHeight * 0.18f)
                + Math.max(0f, valuePaint.getTextSize() - dp(40)) * 0.25f;
        canvas.drawText(eyebrow, centerX, centerY - eyebrowOffset, eyebrowPaint);
        canvas.drawText(value, centerX, centerY + valuePaint.getTextSize() * 0.34f, valuePaint);
        canvas.drawText(caption, centerX, centerY + captionOffset, captionPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
