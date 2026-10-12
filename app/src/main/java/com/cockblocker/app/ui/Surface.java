package com.cockblocker.app.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.widget.TextView;

import com.cockblocker.app.R;

/**
 * Single source of truth for the Cockblocker visual language: dark navy surfaces,
 * one periwinkle accent, no external fonts or drawable assets. Everything is
 * built from GradientDrawable so release resource shrinking stays minimal.
 */
final class Surface {
    private Surface() { }

    // Palette
    static int background(Context c) { return color(c, R.color.cockblocker_background); }
    static int surface(Context c) { return color(c, R.color.cockblocker_surface); }
    static int surfaceRaised(Context c) { return color(c, R.color.cockblocker_surface_raised); }
    static int accent(Context c) { return color(c, R.color.cockblocker_primary); }
    static int accentSecondary(Context c) { return color(c, R.color.cockblocker_primary_secondary); }
    static int accentDim(Context c) { return color(c, R.color.cockblocker_primary_dim); }
    static int accentPressed(Context c) { return color(c, R.color.cockblocker_primary_pressed); }
    static int onAccent(Context c) { return color(c, R.color.cockblocker_on_primary); }
    static int text(Context c) { return color(c, R.color.cockblocker_text); }
    static int textMuted(Context c) { return color(c, R.color.cockblocker_secondary_text); }
    static int textFaint(Context c) { return color(c, R.color.cockblocker_tertiary_text); }
    static int divider(Context c) { return color(c, R.color.cockblocker_divider); }
    static int ready(Context c) { return color(c, R.color.cockblocker_ready); }
    static int warning(Context c) { return color(c, R.color.cockblocker_warning); }
    static int danger(Context c) { return color(c, R.color.cockblocker_danger); }
    static int disabled(Context c) { return color(c, R.color.cockblocker_disabled); }

    static int color(Context context, int resId) {
        return context.getResources().getColor(resId, context.getTheme());
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context context, CharSequence value, int sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif",
                Typeface.NORMAL));
        return view;
    }

    /** Near-black page with a restrained blue cast instead of a flat fill. */
    static Drawable pageBackground(Context context) {
        GradientDrawable shape = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{mix(background(context), accent(context), 0.055f),
                        background(context), background(context)});
        return shape;
    }

    /** Card with a one-pixel hairline border. */
    static Drawable card(Context context) {
        return card(context, surface(context), divider(context), true);
    }

    static Drawable card(Context context, int fill, int stroke, boolean rounded) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, rounded ? 24 : 16));
        if (stroke != 0) {
            shape.setStroke(Math.max(1, dp(context, 1)), stroke);
        }
        return shape;
    }

    static Drawable logoBadge(Context context) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(mix(surface(context), accent(context), 0.07f));
        shape.setStroke(Math.max(1, dp(context, 1)),
                mix(divider(context), accent(context), 0.45f));
        return shape;
    }

    /** Pill-shaped surface for status chips and progress tracks. */
    static Drawable chip(Context context, int fill, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, 100));
        if (stroke != 0) {
            shape.setStroke(Math.max(1, dp(context, 1)), stroke);
        }
        return shape;
    }

    /** Primary action with a blue-to-violet gradient and pressed state. */
    static Drawable accentButton(Context context) {
        GradientDrawable normal = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{accent(context), accentSecondary(context)});
        normal.setCornerRadius(dp(context, 31));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.RECTANGLE);
        pressed.setCornerRadius(dp(context, 31));
        pressed.setColor(accentPressed(context));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_enabled, android.R.attr.state_focused}, pressed);
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{android.R.attr.state_enabled}, normal);
        states.addState(new int[]{-android.R.attr.state_enabled}, accentButtonDisabled(context));
        return states;
    }

    static Drawable accentButtonDisabled(Context context) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(accentDim(context));
        shape.setCornerRadius(dp(context, 31));
        return shape;
    }

    static Drawable ghostButton(Context context) {
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setColor(0x00000000);
        normal.setCornerRadius(dp(context, 16));
        normal.setStroke(Math.max(1, dp(context, 1)), divider(context));
        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.RECTANGLE);
        pressed.setColor(surfaceRaised(context));
        pressed.setCornerRadius(dp(context, 16));
        pressed.setStroke(Math.max(1, dp(context, 1)), divider(context));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_enabled, android.R.attr.state_focused}, pressed);
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{android.R.attr.state_enabled}, normal);
        states.addState(new int[]{-android.R.attr.state_enabled}, normal);
        return states;
    }

    static Drawable rippleSurface(Context context, int fill, int cornerRadiusDp, int rippleColor) {
        GradientDrawable content = new GradientDrawable();
        content.setShape(GradientDrawable.RECTANGLE);
        content.setColor(fill);
        content.setCornerRadius(dp(context, cornerRadiusDp));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(context, cornerRadiusDp));
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    static Drawable iconButton(Context context) {
        GradientDrawable content = new GradientDrawable();
        content.setShape(GradientDrawable.OVAL);
        content.setColor(0x00000000);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(
                ColorStateList.valueOf(withAlpha(accent(context), 48)), content, mask);
    }
    static void applyContentInsets(View view) {
        if (view == null) {
            return;
        }
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                Insets ime = insets.getInsets(WindowInsets.Type.ime());
                top = bars.top;
                bottom = Math.max(bars.bottom, ime.bottom);
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            target.setPadding(target.getPaddingLeft(), top,
                    target.getPaddingRight(), bottom);
            return insets;
        });
        view.requestApplyInsets();
    }

    static void applySystemBarColors(Activity activity) {
        if (activity == null || activity.getWindow() == null) {
            return;
        }
        int background = background(activity);
        activity.getWindow().setStatusBarColor(background);
        activity.getWindow().setNavigationBarColor(background);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.getWindow().setNavigationBarDividerColor(divider(activity));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.getWindow().setStatusBarContrastEnforced(false);
            activity.getWindow().setNavigationBarContrastEnforced(false);
        }
    }

    static int mix(int from, int to, float t) {
        float amount = Math.max(0f, Math.min(1f, t));
        int fr = (from >> 16) & 0xFF;
        int fg = (from >> 8) & 0xFF;
        int fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF;
        int tg = (to >> 8) & 0xFF;
        int tb = to & 0xFF;
        return 0xFF000000
                | (Math.round(fr + (tr - fr) * amount) << 16)
                | (Math.round(fg + (tg - fg) * amount) << 8)
                | Math.round(fb + (tb - fb) * amount);
    }

    static int withAlpha(int color, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24)
                | (color & 0x00FFFFFF);
    }
}
