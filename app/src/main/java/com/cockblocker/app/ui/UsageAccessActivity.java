package com.cockblocker.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.cockblocker.app.R;
import com.cockblocker.app.data.StateRepository;

/** Narrow Settings entry point; it accepts no caller data and exposes no session action. */
public final class UsageAccessActivity extends android.app.Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Surface.applySystemBarColors(this);
        StateRepository.get(getApplicationContext()).initialize();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Surface.background(this));
        scroll.setFillViewport(true);
        scroll.setClipToPadding(true);
        Surface.applyContentInsets(scroll);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int page0 = Surface.dp(this, 20);
        page.setPadding(page0, Surface.dp(this, 16), page0, Surface.dp(this, 40));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView eyebrow = Surface.text(this, "USAGE ACCESS", 11,
                Surface.textFaint(this), true);
        eyebrow.setLetterSpacing(0.18f);
        page.addView(eyebrow, bottom(Surface.dp(this, 6)));

        TextView title = Surface.text(this, getString(R.string.usage_access_title), 26,
                Surface.text(this), true);
        title.setLineSpacing(0f, 1.1f);
        page.addView(title, bottom(Surface.dp(this, 12)));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Surface.card(this));
        int pad = Surface.dp(this, 18);
        card.setPadding(pad, pad, pad, pad);
        TextView explanation = Surface.text(this, getString(R.string.usage_access_body), 14,
                Surface.textMuted(this), false);
        explanation.setLineSpacing(0f, 1.45f);
        card.addView(explanation);
        page.addView(card, bottom(Surface.dp(this, 20)));

        TextView note = Surface.text(this, "Fallback only — never the sole enforcement layer.",
                12, Surface.textFaint(this), false);
        note.setGravity(Gravity.CENTER);
        note.setLetterSpacing(0.04f);
        page.addView(note, bottom(Surface.dp(this, 10)));

        Button settings = new Button(this);
        settings.setAllCaps(false);
        settings.setText(R.string.open_usage_access_settings);
        settings.setTextSize(15);
        settings.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        settings.setLetterSpacing(0.06f);
        settings.setTextColor(Surface.onAccent(this));
        settings.setBackground(Surface.accentButton(this));
        settings.setMinHeight(0);
        settings.setMinimumHeight(0);
        settings.setOnClickListener(view -> openUsageAccessSettings());
        page.addView(settings, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Surface.dp(this, 56)));

        Button home = new Button(this);
        home.setAllCaps(false);
        home.setText(R.string.return_home);
        home.setTextSize(14);
        home.setMinHeight(0);
        home.setMinimumHeight(0);
        home.setTextColor(Surface.textMuted(this));
        home.setBackground(Surface.ghostButton(this));
        home.setPadding(0, Surface.dp(this, 16), 0, Surface.dp(this, 16));
        home.setOnClickListener(view -> finish());
        page.addView(home, top(Surface.dp(this, 10)));

        setContentView(scroll);
    }

    private void openUsageAccessSettings() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, "Android did not make Usage Access settings available.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static LinearLayout.LayoutParams bottom(int margin) {
        LinearLayout.LayoutParams params = params();
        params.bottomMargin = margin;
        return params;
    }

    private static LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams params = params();
        params.topMargin = margin;
        return params;
    }

    private static LinearLayout.LayoutParams params() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
