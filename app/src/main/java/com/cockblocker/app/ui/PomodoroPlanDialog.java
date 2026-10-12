package com.cockblocker.app.ui;

import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.cockblocker.app.data.PomodoroPlan;

/** Bounded editor for either a Pomodoro plan or a straight focus timer. */
final class PomodoroPlanDialog {
    interface Listener {
        void onPlanSelected(PomodoroPlan plan);
    }

    private PomodoroPlanDialog() { }

    static void show(Context context, PomodoroPlan initial, Listener listener) {
        if (context == null || initial == null || listener == null) {
            return;
        }
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Surface.dp(context, 22);
        root.setPadding(pad, Surface.dp(context, 20), pad, Surface.dp(context, 18));
        root.setBackground(Surface.card(context));

        TextView eyebrow = Surface.text(context, "FOCUS MODE", 11,
                Surface.textFaint(context), true);
        eyebrow.setLetterSpacing(0.16f);
        root.addView(eyebrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = Surface.text(context, "Choose your focus mode", 24,
                Surface.text(context), true);
        title.setPadding(0, Surface.dp(context, 5), 0, Surface.dp(context, 8));
        root.addView(title);

        TextView help = Surface.text(context, "", 13, Surface.textMuted(context), false);
        help.setLineSpacing(0f, 1.3f);
        root.addView(help);

        boolean[] straight = {initial.isStraight()};
        TextView pomodoroMode = modeButton(context, "Pomodoro");
        TextView straightMode = modeButton(context, "Straight timer");
        LinearLayout modes = new LinearLayout(context);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setPadding(0, Surface.dp(context, 14), 0, Surface.dp(context, 2));
        modes.addView(pomodoroMode, modeParams(context, 0, 5));
        modes.addView(straightMode, modeParams(context, 5, 0));
        root.addView(modes);

        int[] pomodoroFocus = {initial.isStraight() ? 25 : initial.getFocusMinutes()};
        int[] straightFocus = {initial.isStraight() ? initial.getFocusMinutes() : 12};
        Slider focus = addSlider(root, context, "Focus length · 1–90 min",
                initial.getFocusMinutes(), PomodoroPlan.MIN_FOCUS_MINUTES,
                PomodoroPlan.MAX_FOCUS_MINUTES, value -> value + " min");
        Slider pause = addSlider(root, context, "Break length · 1–30 min",
                initial.getBreakMinutes(), PomodoroPlan.MIN_BREAK_MINUTES,
                PomodoroPlan.MAX_BREAK_MINUTES, value -> value + " min");
        Slider sessions = addSlider(root, context, "Focus sessions · 1–12",
                initial.getTotalSessions(), PomodoroPlan.MIN_SESSIONS,
                PomodoroPlan.MAX_SESSIONS,
                value -> value == 1 ? "1 session" : value + " sessions");
        ExactDuration exactDuration = addExactDuration(root, context, straightFocus[0]);

        Runnable applyMode = () -> {
            if (straight[0]) {
                help.setText("Enter an exact duration from 1 minute to 12 hours. The timer unlocks automatically when it ends.");
                exactDuration.setText(formatClock(straightFocus[0]));
                setExpanded(focus.container, false);
                setExpanded(exactDuration.container, true);
                setExpanded(pause.container, false);
                setExpanded(sessions.container, false);
                styleMode(straightMode, true);
                styleMode(pomodoroMode, false);
            } else {
                help.setText("Focus blocks selected apps. Breaks unlock automatically between blocks, then the next focus begins. Focus cannot end early.");
                setExpanded(focus.container, true);
                setExpanded(exactDuration.container, false);
                setExpanded(pause.container, true);
                setExpanded(sessions.container, true);
                styleMode(pomodoroMode, true);
                styleMode(straightMode, false);
            }
        };
        pomodoroMode.setOnClickListener(view -> {
            if (straight[0]) {
                straightFocus[0] = parseClock(exactDuration.input, straightFocus[0]);
            } else {
                pomodoroFocus[0] = focus.value();
            }
            straight[0] = false;
            focus.setValue(pomodoroFocus[0]);
            applyMode.run();
        });
        straightMode.setOnClickListener(view -> {
            if (straight[0]) {
                straightFocus[0] = parseClock(exactDuration.input, straightFocus[0]);
            } else {
                pomodoroFocus[0] = focus.value();
            }
            straight[0] = true;
            applyMode.run();
        });
        applyMode.run();

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, Surface.dp(context, 8), 0, 0);

        TextView cancel = actionButton(context, "Cancel", false);
        cancel.setOnClickListener(view -> dialog.dismiss());
        actions.addView(cancel, actionParams(context, 0, 8));

        TextView save = actionButton(context, "Save plan", true);
        save.setOnClickListener(view -> {
            PomodoroPlan selected;
            if (straight[0]) {
                int minutes = parseClock(exactDuration.input, -1);
                if (minutes < 0) {
                    exactDuration.input.setError("Enter a duration from 1:00 to 12:00");
                    exactDuration.input.requestFocus();
                    return;
                }
                selected = PomodoroPlan.straight(minutes);
            } else {
                selected = PomodoroPlan.initial(focus.value(), pause.value(), sessions.value());
            }
            listener.onPlanSelected(selected);
            dialog.dismiss();
        });
        actions.addView(save, actionParams(context, 8, 0));
        root.addView(actions);

        dialog.setContentView(root);
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setDimAmount(0.72f);
            }
            root.setAlpha(0f);
            root.setTranslationY(Surface.dp(context, 8));
            root.animate().alpha(1f).translationY(0f).setDuration(220).start();
        });
        dialog.show();
    }

    private static TextView modeButton(Context context, String text) {
        TextView button = Surface.text(context, text, 13, Surface.textMuted(context), true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(Surface.dp(context, 42));
        button.setPadding(Surface.dp(context, 10), 0, Surface.dp(context, 10), 0);
        button.setContentDescription(text + " focus mode");
        button.setFocusable(true);
        return button;
    }

    private static void styleMode(TextView button, boolean selected) {
        button.setSelected(selected);
        button.setFocusable(true);
        button.setTextColor(selected ? Surface.onAccent(button.getContext())
                : Surface.textMuted(button.getContext()));
        button.setBackground(selected
                ? Surface.accentButton(button.getContext())
                : Surface.ghostButton(button.getContext()));
        button.setContentDescription(button.getText() + " focus mode"
                + (selected ? ", selected" : ""));
    }

    private static LinearLayout.LayoutParams modeParams(Context context, int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                Surface.dp(context, 42), 1f);
        params.leftMargin = Surface.dp(context, left);
        params.rightMargin = Surface.dp(context, right);
        return params;
    }

    private static ExactDuration addExactDuration(LinearLayout root, Context context, int minutes) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, Surface.dp(context, 14), 0, 0);
        TextView label = Surface.text(context, "Exact duration · 1 min–12 hr", 14,
                Surface.text(context), true);
        block.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(Surface.text(context));
        input.setHintTextColor(Surface.textFaint(context));
        input.setTextSize(18);
        input.setText(formatClock(minutes));
        input.setSelectAllOnFocus(true);
        input.setContentDescription("Exact straight timer duration, hours and minutes");
        input.setBackground(Surface.card(context, Surface.surfaceRaised(context),
                Surface.divider(context), true));
        input.setPadding(Surface.dp(context, 14), 0, Surface.dp(context, 14), 0);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Surface.dp(context, 52));
        inputParams.topMargin = Surface.dp(context, 8);
        block.addView(input, inputParams);
        root.addView(block);
        return new ExactDuration(block, input);
    }

    private static String formatClock(int minutes) {
        return String.format(java.util.Locale.US, "%d:%02d", minutes / 60, minutes % 60);
    }

    private static int parseClock(EditText input, int fallback) {
        String value = input.getText() == null ? "" : input.getText().toString().trim();
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            String[] parts = value.split(":");
            int total;
            if (parts.length == 1) {
                total = Integer.parseInt(parts[0]);
            } else if (parts.length == 2) {
                int hours = Integer.parseInt(parts[0]);
                int minutes = Integer.parseInt(parts[1]);
                if (hours < 0 || minutes < 0 || minutes >= 60 || hours > 12
                        || (hours == 12 && minutes > 0)) {
                    return -1;
                }
                total = hours * 60 + minutes;
            } else {
                return -1;
            }
            return total < PomodoroPlan.MIN_FOCUS_MINUTES
                    || total > PomodoroPlan.MAX_STRAIGHT_FOCUS_MINUTES ? -1 : total;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private static Slider addSlider(LinearLayout root, Context context, String label,
                                    int value, int minimum, int maximum, ValueFormatter formatter) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, Surface.dp(context, 14), 0, 0);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = Surface.text(context, label, 14, Surface.text(context), true);
        header.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueView = Surface.text(context, formatter.format(value), 14,
                Surface.accent(context), true);
        valueView.setGravity(Gravity.END);
        header.addView(valueView);
        block.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar seek = new SeekBar(context);
        seek.setMax(maximum - minimum);
        seek.setProgress(value - minimum);
        seek.setPadding(0, Surface.dp(context, 4), 0, 0);
        seek.setProgressTintList(ColorStateList.valueOf(Surface.accent(context)));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(Surface.surfaceRaised(context)));
        seek.setThumbTintList(ColorStateList.valueOf(Surface.accent(context)));
        seek.setContentDescription(label);
        block.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Surface.dp(context, 42)));

        Slider slider = new Slider(block, seek, name, valueView, minimum, formatter);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                slider.update(minimum + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) { }

            @Override
            public void onStopTrackingTouch(SeekBar bar) { }
        });
        root.addView(block);
        return slider;
    }

    private static void setExpanded(View view, boolean expanded) {
        view.animate().cancel();
        if (!view.isAttachedToWindow()) {
            view.setVisibility(expanded ? View.VISIBLE : View.GONE);
            view.setAlpha(expanded ? 1f : 0f);
            view.setTranslationY(0f);
            view.setImportantForAccessibility(expanded
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            return;
        }
        if (expanded) {
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            view.setVisibility(View.VISIBLE);
            view.animate().alpha(1f).translationY(0f).setDuration(180).start();
        } else {
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            view.animate().alpha(0f).translationY(-Surface.dp(view.getContext(), 4))
                    .setDuration(140).withEndAction(() -> {
                        view.setVisibility(View.GONE);
                        view.setAlpha(1f);
                        view.setTranslationY(0f);
                    }).start();
        }
    }

    private static TextView actionButton(Context context, String text, boolean primary) {
        TextView button = Surface.text(context, text, 14,
                primary ? Surface.onAccent(context) : Surface.textMuted(context), true);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setBackground(primary ? Surface.accentButton(context) : Surface.ghostButton(context));
        button.setPadding(Surface.dp(context, 18), 0, Surface.dp(context, 18), 0);
        button.setMinHeight(Surface.dp(context, 46));
        button.setContentDescription(text);
        return button;
    }

    private static LinearLayout.LayoutParams actionParams(Context context, int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Surface.dp(context, 46));
        params.leftMargin = Surface.dp(context, left);
        params.rightMargin = Surface.dp(context, right);
        return params;
    }

    private static final class ExactDuration {
        private final View container;
        private final EditText input;

        private ExactDuration(View container, EditText input) {
            this.container = container;
            this.input = input;
        }

        private void setText(String value) {
            if (!input.hasFocus()) {
                input.setText(value);
                input.setSelection(input.length());
            }
        }
    }

    private interface ValueFormatter {
        String format(int value);
    }

    private static final class Slider {
        private final View container;
        private final SeekBar seek;
        private final TextView label;
        private final TextView valueView;
        private final int minimum;
        private final ValueFormatter formatter;
        private int value;

        private Slider(View container, SeekBar seek, TextView label, TextView valueView,
                       int minimum, ValueFormatter formatter) {
            this.container = container;
            this.seek = seek;
            this.label = label;
            this.valueView = valueView;
            this.minimum = minimum;
            this.formatter = formatter;
            this.value = seek.getProgress() + minimum;
        }

        private void setLabel(String next) {
            label.setText(next);
            seek.setContentDescription(next);
        }

        private void setRange(int maximum) {
            seek.setMax(maximum - minimum);
            value = Math.max(minimum, Math.min(maximum, value));
            seek.setProgress(value - minimum);
            valueView.setText(formatter.format(value));
        }

        private void setValue(int next) {
            value = Math.max(minimum, Math.min(seek.getMax() + minimum, next));
            seek.setProgress(value - minimum);
            valueView.setText(formatter.format(value));
        }

        private void update(int next) {
            value = Math.max(minimum, Math.min(seek.getMax() + minimum, next));
            valueView.setText(formatter.format(value));
        }

        private int value() {
            return value;
        }
    }
}

