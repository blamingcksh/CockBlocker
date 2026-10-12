package com.cockblocker.app.ui;

import android.content.ComponentName;
import android.net.Uri;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.cockblocker.app.R;
import com.cockblocker.app.BuildConfig;
import com.cockblocker.app.data.SessionRecord;
import com.cockblocker.app.data.SessionState;
import com.cockblocker.app.data.StateRepository;
import com.cockblocker.app.data.PomodoroPlan;
import com.cockblocker.app.data.PomodoroPlanStore;
import com.cockblocker.app.integrity.IntegrityGuard;
import com.cockblocker.app.platform.SafetySurfaceResolver;
import com.cockblocker.app.service.SessionWatchdogService;
import com.cockblocker.app.trust.Check;
import com.cockblocker.app.trust.GateResult;
import com.cockblocker.app.trust.TrustAnchorGate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Home surface. Owns no authoritative session state: it renders StateRepository
 * and forwards setup/activation requests to the watchdog.
 */
public final class MainActivity extends android.app.Activity {
    private static final String SELECTION_PREFS = "idle_app_selection";
    private static final int ACTIVATION_POLL_ATTEMPTS = 150;
    private static final long ACTIVATION_POLL_INTERVAL_MS = 100L;
    private static final int LIST_PAGE = 30;

    private final Handler ticker = new Handler(Looper.getMainLooper());

    private StateRepository repository;
    private SessionRecord displayedRecord;
    private PomodoroPlanStore planStore;
    private PomodoroPlan selectedPlan;
    private SessionRecord activationTarget;
    private int activationPollAttempts;
    private GateResult displayedGate;
    private Set<String> criticalPackages = Collections.emptySet();
    private Set<String> protectedRoutes = Collections.emptySet();
    private Set<String> draftAllowed = Collections.emptySet();
    private List<ResolveInfo> allApps = Collections.emptyList();
    private String appFilter = "";
    private int appLimit = LIST_PAGE;
    private int matchedAppCount;
    private int renderedAppCount;
    private boolean selectionLoaded;
    private boolean setupExpanded;
    private boolean idleState;
    private boolean canStart;
    private String dialEyebrow = "READY TO FOCUS";
    private String dialCaption = "MINUTES";
    private int dialColor;
    private boolean compactHeight;
    private SessionState lastAnimatedState;
    private boolean homeLaunch;
    private String lastPlanSignature;

    private ScrollView scroll;
    private LinearLayout content;
    private LinearLayout setupCard;
    private LinearLayout selectionCard;
    private LinearLayout setupContainer;
    private LinearLayout appContainer;
    private LinearLayout allowedAppsRow;
    private FrameLayout startButtonContainer;
    private TextView stateChip;
    private TextView mottoPrimary;
    private TextView mottoSecondary;
    private TextView healthView;
    private TextView actionHint;
    private TextView gateSummaryView;
    private TextView criticalSummaryView;
    private TextView editAllowedView;
    private TextView planSummary;
    private TextView allowedAppsSubtitle;
    private FocusDialView dialView;
    private Button startButton;
    private Button showMoreButton;
    private boolean starting;
    private String activationFailureMessage;

    private final Runnable activationPoll = this::pollActivation;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            SessionRecord current = safeRead();
            if (current == null) {
                if (displayedRecord != null) {
                    refresh();
                }
            } else if (displayedRecord == null
                    || current.getState() != displayedRecord.getState()
                    || current.getSequence() != displayedRecord.getSequence()) {
                refresh();
            } else {
                updateCountdown();
            }
            ticker.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        homeLaunch = isHomeIntent(getIntent());
        Surface.applySystemBarColors(this);
        repository = StateRepository.get(getApplicationContext());
        repository.initialize();
        planStore = new PomodoroPlanStore(this);
        selectedPlan = planStore.load();
        buildContent();
        refresh();
        forwardHomeIfIdle();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (repository != null) {
            repository.reconcileAfterBoundary();
            refresh();
        }
            forwardHomeIfIdle();
        ticker.post(clockTick);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        homeLaunch = isHomeIntent(intent);
        if (repository != null) {
            repository.reconcileAfterBoundary();
            refresh();
            forwardHomeIfIdle();
        }
    }

    @Override
    protected void onPause() {
        ticker.removeCallbacks(clockTick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        ticker.removeCallbacks(clockTick);
        ticker.removeCallbacks(activationPoll);
        super.onDestroy();
    }
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (selectionCard != null && selectionCard.getVisibility() == View.VISIBLE) {
            toggleSelectionCard();
            return;
        }
        if (setupExpanded) {
            setupExpanded = false;
            renderGate();
            return;
        }
        super.onBackPressed();
    }

    private boolean isHomeIntent(Intent intent) {
        return intent != null
                && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_HOME);
    }

    private void forwardHomeIfIdle() {
        if (!homeLaunch || displayedRecord == null || !idleState || starting) {
            return;
        }
        ResolveInfo alternative = findAlternativeHome();
        if (alternative == null || alternative.activityInfo == null) {
            return;
        }
        Intent forward = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setComponent(new ComponentName(alternative.activityInfo.packageName,
                        alternative.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(forward);
            homeLaunch = false;
            finish();
        } catch (RuntimeException ignored) {
            // Keep Cockblocker visible if Android refuses the alternative launcher.
        }
    }

    private ResolveInfo findAlternativeHome() {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addCategory(Intent.CATEGORY_DEFAULT);
        List<ResolveInfo> candidates;
        try {
            candidates = getPackageManager().queryIntentActivities(home, PackageManager.MATCH_ALL);
        } catch (RuntimeException unavailable) {
            return null;
        }
        if (candidates == null) {
            return null;
        }
        Collections.sort(candidates, Comparator.comparing(info ->
                info.activityInfo == null ? "" : info.activityInfo.packageName));
        for (ResolveInfo info : candidates) {
            if (info.activityInfo != null
                    && !getPackageName().equals(info.activityInfo.packageName)
                    && !"android".equals(info.activityInfo.packageName)
                    && !"com.android.settings".equals(info.activityInfo.packageName)
                    && info.activityInfo.name != null
                    && !info.activityInfo.name.isEmpty()) {
                return info;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- build

    private void buildContent() {
        compactHeight = getResources().getConfiguration().screenHeightDp < 760;
        dialColor = Surface.accent(this);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setBackground(Surface.pageBackground(this));
        Surface.applyContentInsets(scroll);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        int page = Surface.dp(this, 22);
        content.setPadding(page, Surface.dp(this, 8), page, Surface.dp(this, compactHeight ? 28 : 44));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(header(), wrap());
        content.addView(motto(), wrap(compactHeight ? 1 : 4));
        LinearLayout.LayoutParams dialParams = wrap();
        dialParams.height = Surface.dp(this, compactHeight ? 236 : 282);
        content.addView(dialBlock(), dialParams);
        content.addView(healthView(), wrapTop(compactHeight ? 0 : 1));
        content.addView(actionBlock(), wrapTop(compactHeight ? 0 : 2));
        content.addView(allowedAppsCard(), wrap(compactHeight ? 14 : 22));

        setupCard = setupCard();
        content.addView(setupCard, wrap(14));
        selectionCard = selectionCard();
        selectionCard.setVisibility(View.GONE);
        content.addView(selectionCard, wrap(14));
        content.addView(disclosureCard(), wrap(14));

        setContentView(scroll);
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Surface.dp(this, compactHeight ? 54 : 58));
        row.setPadding(0, Surface.dp(this, compactHeight ? 5 : 7), 0, Surface.dp(this, 3));

        FrameLayout logoBadge = new FrameLayout(this);
        logoBadge.setBackground(Surface.logoBadge(this));
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_cockblocker_mark);
        logo.setContentDescription("Cockblocker logo");
        logoBadge.addView(logo, new FrameLayout.LayoutParams(
                Surface.dp(this, 31), Surface.dp(this, 31), Gravity.CENTER));
        row.addView(logoBadge, new LinearLayout.LayoutParams(
                Surface.dp(this, 46), Surface.dp(this, 46)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = Surface.text(this, getString(R.string.main_title), 20,
                Surface.text(this), true);
        brand.addView(title);
        float fontScale = getResources().getDisplayMetrics().scaledDensity
                / getResources().getDisplayMetrics().density;
        if (fontScale > 1.3f) {
            title.setTextSize(18);
        }
        TextView tagline = Surface.text(this, "Focus mode", 12,
                Surface.textMuted(this), false);
        brand.addView(tagline, wrapBottom(Surface.dp(this, 1)));

        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        brandParams.leftMargin = Surface.dp(this, 12);
        row.addView(brand, brandParams);

        stateChip = Surface.text(this, "READY", 11, Surface.accent(this), true);
        stateChip.setGravity(Gravity.CENTER);
        stateChip.setLetterSpacing(0.1f);
        stateChip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Surface.dp(this, 11));
        stateChip.setPadding(Surface.dp(this, 10), Surface.dp(this, 6),
                Surface.dp(this, 10), Surface.dp(this, 6));
        stateChip.setMaxWidth(Surface.dp(this, 104));
        stateChip.setMaxLines(1);
        stateChip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        stateChip.setBackground(Surface.chip(this,
                Surface.mix(Surface.surface(this), Surface.accent(this), 0.13f),
                Surface.mix(Surface.divider(this), Surface.accent(this), 0.35f)));
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipParams.leftMargin = Surface.dp(this, 8);
        row.addView(stateChip, chipParams);

        ImageButton settings = new ImageButton(this);
        settings.setImageResource(R.drawable.ic_settings);
        settings.setColorFilter(Surface.textMuted(this));
        settings.setBackground(Surface.iconButton(this));
        settings.setPadding(Surface.dp(this, 10), Surface.dp(this, 10),
                Surface.dp(this, 10), Surface.dp(this, 10));
        settings.setContentDescription("Review focus setup");
        settings.setOnClickListener(view -> openSetup());
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(
                Surface.dp(this, 42), Surface.dp(this, 42));
        settingsParams.leftMargin = Surface.dp(this, 6);
        row.addView(settings, settingsParams);
        return row;
    }

    private View motto() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER);
        mottoPrimary = Surface.text(this, "Less distractions.", 16,
                Surface.textMuted(this), false);
        mottoPrimary.setGravity(Gravity.CENTER);
        mottoPrimary.setLetterSpacing(0.045f);
        block.addView(mottoPrimary);
        mottoSecondary = Surface.text(this, "More you.", 17,
                Surface.text(this), false);
        mottoSecondary.setGravity(Gravity.CENTER);
        mottoSecondary.setLetterSpacing(0.035f);
        block.addView(mottoSecondary, wrapTop(Surface.dp(this, 2)));
        return block;
    }

    private View dialBlock() {
        FrameLayout frame = new FrameLayout(this);
        frame.setClipChildren(false);
        dialView = new FocusDialView(this);
        dialView.setClickable(true);
        dialView.setFocusable(true);
        dialView.setContentDescription("Focus timer. Tap to edit the plan.");
        dialView.setOnClickListener(view -> {
            if (idleState && !starting) {
                showPlanEditor();
            }
        });
        dialView.setOnTouchListener((view, event) -> {
            if (!idleState || starting) {
                return false;
            }
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                view.animate().scaleX(0.985f).scaleY(0.985f).setDuration(90).start();
            } else if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            }
            return false;
        });
        dialView.setAlpha(0f);
        dialView.setTranslationY(Surface.dp(this, 8));
        dialView.post(() -> dialView.animate().alpha(1f).translationY(0f)
                .setDuration(320).start());
        frame.addView(dialView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private View healthView() {
        healthView = Surface.text(this, "", 12, Surface.textMuted(this), false);
        healthView.setGravity(Gravity.CENTER);
        healthView.setMinHeight(Surface.dp(this, compactHeight ? 26 : 30));
        healthView.setPadding(Surface.dp(this, 20), Surface.dp(this, 4),
                Surface.dp(this, 20), Surface.dp(this, 4));
        healthView.setLineSpacing(0f, 1.2f);
        return healthView;
    }

    private View actionBlock() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        planSummary = Surface.text(this, formatPlanSummary(), 12,
                Surface.textMuted(this), true);
        planSummary.setGravity(Gravity.CENTER);
        planSummary.setLetterSpacing(0.02f);
        planSummary.setPadding(Surface.dp(this, 12), Surface.dp(this, 5),
                Surface.dp(this, 12), Surface.dp(this, 5));
        planSummary.setOnClickListener(view -> showPlanEditor());
        block.addView(planSummary, wrapBottom(Surface.dp(this, 4)));

        startButtonContainer = new FrameLayout(this);
        startButtonContainer.setClipChildren(false);
        startButton = new Button(this);
        startButton.setAllCaps(false);
        startButton.setText("Start focus");
        startButton.setTextSize(16);
        startButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        startButton.setLetterSpacing(0.035f);
        startButton.setTextColor(Surface.onAccent(this));
        startButton.setBackground(Surface.accentButton(this));
        startButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0);
        startButton.setCompoundDrawableTintList(ColorStateList.valueOf(Surface.onAccent(this)));
        startButton.setCompoundDrawablePadding(Surface.dp(this, 9));
        startButton.setMinHeight(0);
        startButton.setMinimumHeight(0);
        startButton.setStateListAnimator(null);
        startButton.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        startButton.setShadowLayer(Surface.dp(this, 24), 0f, Surface.dp(this, 10),
                Surface.withAlpha(Surface.accent(this), 100));
        startButton.setOnClickListener(view -> beginSession());
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Surface.dp(this, compactHeight ? 58 : 64));
        buttonParams.leftMargin = Surface.dp(this, 36);
        buttonParams.rightMargin = Surface.dp(this, 36);
        buttonParams.topMargin = Surface.dp(this, compactHeight ? 7 : 9);
        buttonParams.bottomMargin = Surface.dp(this, compactHeight ? 7 : 9);
        startButtonContainer.addView(startButton, buttonParams);
        block.addView(startButtonContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Surface.dp(this, compactHeight ? 74 : 82)));

        actionHint = Surface.text(this, "Ready when you are", 12,
                Surface.textFaint(this), false);
        actionHint.setGravity(Gravity.CENTER);
        actionHint.setLetterSpacing(0.025f);
        actionHint.setPadding(Surface.dp(this, 20), Surface.dp(this, 7),
                Surface.dp(this, 20), Surface.dp(this, 7));
        block.addView(actionHint, wrap());
        return block;
    }

    private View allowedAppsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Surface.card(this));
        card.setMinimumHeight(Surface.dp(this, compactHeight ? 160 : 178));
        card.setPadding(Surface.dp(this, 20), Surface.dp(this, compactHeight ? 13 : 17),
                Surface.dp(this, 16), Surface.dp(this, compactHeight ? 11 : 15));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Surface.text(this, "Allowed apps", 17,
                Surface.text(this), true);
        header.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        editAllowedView = Surface.text(this, "Edit", 13, Surface.accent(this), true);
        editAllowedView.setGravity(Gravity.CENTER);
        editAllowedView.setCompoundDrawablesWithIntrinsicBounds(
                0, 0, R.drawable.ic_chevron_right, 0);
        editAllowedView.setCompoundDrawableTintList(ColorStateList.valueOf(Surface.accent(this)));
        editAllowedView.setCompoundDrawablePadding(Surface.dp(this, 3));
        editAllowedView.setPadding(Surface.dp(this, 10), Surface.dp(this, 8),
                Surface.dp(this, 6), Surface.dp(this, 8));
        editAllowedView.setBackground(Surface.iconButton(this));
        editAllowedView.setContentDescription("Edit allowed apps");
        editAllowedView.setOnClickListener(view -> toggleSelectionCard());
        header.addView(editAllowedView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(header, wrap());

        allowedAppsSubtitle = Surface.text(this,
                "Only these apps stay available during a session.", 12,
                Surface.textMuted(this), false);
        allowedAppsSubtitle.setLineSpacing(0f, 1.25f);
        card.addView(allowedAppsSubtitle, wrapBottom(Surface.dp(this, compactHeight ? 7 : 10)));

        HorizontalScrollView appStrip = new HorizontalScrollView(this);
        appStrip.setHorizontalScrollBarEnabled(false);
        appStrip.setOverScrollMode(View.OVER_SCROLL_NEVER);
        appStrip.setFillViewport(false);
        allowedAppsRow = new LinearLayout(this);
        allowedAppsRow.setOrientation(LinearLayout.HORIZONTAL);
        allowedAppsRow.setGravity(Gravity.TOP);
        appStrip.addView(allowedAppsRow, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(appStrip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Surface.dp(this, compactHeight ? 86 : 100)));
        return card;
    }

    private LinearLayout setupCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Surface.card(this));
        int pad = Surface.dp(this, 20);
        card.setPadding(pad, pad, pad, pad);

        card.addView(sectionTitle("Focus setup"), wrapBottom(Surface.dp(this, 8)));
        gateSummaryView = Surface.text(this, "", 14, Surface.textMuted(this), false);
        gateSummaryView.setLineSpacing(0f, 1.3f);
        card.addView(gateSummaryView, wrapBottom(Surface.dp(this, 12)));

        setupContainer = new LinearLayout(this);
        setupContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(setupContainer, wrap());
        return card;
    }

    private LinearLayout selectionCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Surface.card(this));
        int pad = Surface.dp(this, 20);
        card.setPadding(pad, pad, pad, pad);

        card.addView(sectionTitle("Choose exceptions"), wrapBottom(Surface.dp(this, 7)));
        TextView help = Surface.text(this,
                "Allowed apps open normally and can be launched from the card above. Everything "
                        + "else returns here during focus mode. Settings is locked while focus runs "
                        + "unless you allow it here. Safety-critical apps are always exempt.", 13,
                Surface.textMuted(this), false);
        help.setLineSpacing(0f, 1.35f);
        card.addView(help, wrapBottom(Surface.dp(this, 12)));

        EditText search = new EditText(this);
        search.setHint("Filter installed apps");
        search.setHintTextColor(Surface.textFaint(this));
        search.setTextColor(Surface.text(this));
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_DONE);
        search.setBackground(Surface.card(this, Surface.surfaceRaised(this),
                Surface.divider(this), true));
        search.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0);
        search.setCompoundDrawableTintList(ColorStateList.valueOf(Surface.textFaint(this)));
        search.setCompoundDrawablePadding(Surface.dp(this, 9));
        search.setPadding(Surface.dp(this, 14), Surface.dp(this, 11),
                Surface.dp(this, 14), Surface.dp(this, 11));
        search.setContentDescription("Filter installed apps");
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                appFilter = value == null ? "" : value.toString().trim().toLowerCase(Locale.US);
                appLimit = LIST_PAGE;
                renderAppSelection();
            }

            @Override
            public void afterTextChanged(Editable value) { }
        });
        search.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                InputMethodManager input = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (input != null) {
                    input.hideSoftInputFromWindow(view.getWindowToken(), 0);
                }
                view.clearFocus();
                return true;
            }
            return false;
        });
        card.addView(search, wrapBottom(Surface.dp(this, 10)));

        criticalSummaryView = Surface.text(this, "", 12,
                Surface.textMuted(this), false);
        criticalSummaryView.setLineSpacing(0f, 1.3f);
        card.addView(criticalSummaryView, wrapBottom(Surface.dp(this, 8)));

        appContainer = new LinearLayout(this);
        appContainer.setOrientation(LinearLayout.VERTICAL);
        card.addView(appContainer, wrap());

        showMoreButton = new Button(this);
        showMoreButton.setAllCaps(false);
        showMoreButton.setText("Show more");
        showMoreButton.setTextSize(13);
        showMoreButton.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        showMoreButton.setTextColor(Surface.accent(this));
        showMoreButton.setBackground(Surface.ghostButton(this));
        showMoreButton.setMinHeight(0);
        showMoreButton.setMinimumHeight(0);
        showMoreButton.setPadding(0, Surface.dp(this, 14), 0, Surface.dp(this, 14));
        showMoreButton.setVisibility(View.GONE);
        showMoreButton.setOnClickListener(view -> {
            appLimit += LIST_PAGE;
            renderAppSelection();
        });
        card.addView(showMoreButton, wrapTop(Surface.dp(this, 8)));
        return card;
    }

    private View disclosureCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Surface.card(this));
        int pad = Surface.dp(this, 20);
        card.setPadding(pad, pad, pad, pad);
        card.addView(sectionTitle("Before you focus"), wrapBottom(Surface.dp(this, 8)));
        TextView disclosure = Surface.text(this, getString(R.string.disclosure_body), 13,
                Surface.textMuted(this), false);
        disclosure.setLineSpacing(0f, 1.42f);
        card.addView(disclosure, wrap());
        return card;
    }

    private TextView sectionTitle(String title) {
        TextView view = Surface.text(this, title, 12, Surface.text(this), true);
        view.setLetterSpacing(0.13f);
        view.setAllCaps(true);
        return view;
    }

    // ---------------------------------------------------------------- render

    private void refresh() {
        displayedRecord = safeRead();
        if (displayedRecord == null) {
            idleState = false;
            canStart = false;
            setupExpanded = false;
            stateChip.setText("OFFLINE");
            stateChip.setTextColor(Surface.danger(this));
            stateChip.setBackground(Surface.chip(this,
                    Surface.mix(Surface.surface(this), Surface.danger(this), 0.13f),
                    Surface.mix(Surface.divider(this), Surface.danger(this), 0.35f)));
            mottoPrimary.setText("Session state unavailable.");
            mottoSecondary.setText("Reopen Cockblocker.");
            healthView.setText("Cockblocker cannot verify its signed session journal.");
            healthView.setTextColor(Surface.danger(this));
            dialView.render("STATE UNAVAILABLE", "—", "CHECK APP", 0f, Surface.danger(this));
            setupContainer.removeAllViews();
            appContainer.removeAllViews();
            allowedAppsRow.removeAllViews();
            selectionCard.setVisibility(View.GONE);
            startButtonContainer.setVisibility(View.GONE);
            setupCard.setVisibility(View.GONE);
            editAllowedView.setVisibility(View.GONE);
            return;
        }

        criticalPackages = new HashSet<>(safeSet(displayedRecord.getCriticalPackages()));
        Set<String> resolvedCritical = SafetySurfaceResolver.resolveCriticalPackages(this);
        if (resolvedCritical != null) {
            criticalPackages.addAll(resolvedCritical);
        }
        criticalPackages.add(getPackageName());
        criticalPackages.add("android");
        // Settings is offered as a normal exception, never as an always-exempt surface.
        criticalPackages.removeAll(SafetySurfaceResolver.resolveSettingsPackages(this));

        Set<String> resolvedRoutes = SafetySurfaceResolver.resolveProtectedRoutes(this);
        protectedRoutes = resolvedRoutes == null ? Collections.emptySet() : resolvedRoutes;

        idleState = displayedRecord.getState() == SessionState.IDLE
                || displayedRecord.getState() == SessionState.RELEASED;
        displayedGate = idleState
                ? TrustAnchorGate.evaluateForStart(this)
                : TrustAnchorGate.evaluateHealth(this);
        canStart = !starting && idleState && displayedGate != null && displayedGate.isOk();

        renderState();
        renderGate();

        allApps = queryLaunchableApps();
        if (idleState) {
            if (!selectionLoaded) {
                Set<String> persisted = loadSelection();
                draftAllowed = persisted.isEmpty()
                        ? new HashSet<>(criticalPackages)
                        : persisted;
                selectionLoaded = true;
            }
            draftAllowed = new HashSet<>(draftAllowed);
            draftAllowed.addAll(criticalPackages);
            appLimit = LIST_PAGE;
            renderAppSelection();
        } else {
            draftAllowed = new HashSet<>(safeSet(displayedRecord.getAllowed()));
            appContainer.removeAllViews();
            selectionCard.setVisibility(View.GONE);
        }
        renderAllowedSummary();
    }

    private void renderState() {
        SessionState state = displayedRecord.getState();
        PomodoroPlan activePlan = displayedRecord.getPomodoroPlan();
        boolean straightMode = activePlan != null && activePlan.isStraight();
        String planSignature = currentPlanSignature();
        boolean semanticChange = lastAnimatedState != null
                && (state != lastAnimatedState || !planSignature.equals(lastPlanSignature));
        String chip;
        int chipColor;
        int healthColor;
        switch (state) {
            case ACTIVE:
                if (displayedRecord.isRecoveryLock()) {
                    chip = "LOCK";
                    chipColor = Surface.danger(this);
                    healthView.setText("Signed journal unavailable. Blocking remains on until you recover the app state.");
                    healthColor = Surface.danger(this);
                    mottoPrimary.setText("Recovery lock.");
                    mottoSecondary.setText("Open app info to reset local state.");
                    dialEyebrow = "RECOVERY LOCK";
                    dialCaption = "NO EARLY RELEASE";
                    dialColor = Surface.danger(this);
                } else {
                    chip = "FOCUS";
                    chipColor = Surface.ready(this);
                    healthView.setText(R.string.health_active);
                    healthColor = Surface.textMuted(this);
                    mottoPrimary.setText(straightMode ? "Straight timer." : "Stay with it.");
                    mottoSecondary.setText(straightMode ? "Stay with the task." : "One thing at a time.");
                    dialEyebrow = straightMode ? "STRAIGHT TIMER" : "FOCUS IN PROGRESS";
                    dialCaption = "REMAINING";
                    dialColor = Surface.accent(this);
                }
                break;
            case DEGRADED:
                if (displayedRecord.isRecoveryLock()) {
                    chip = "LOCK";
                    chipColor = Surface.danger(this);
                    healthView.setText("Signed journal unavailable. Blocking remains on until you recover the app state.");
                    healthColor = Surface.danger(this);
                    mottoPrimary.setText("Recovery lock.");
                    mottoSecondary.setText("Open app info to reset local state.");
                    dialEyebrow = "RECOVERY LOCK";
                    dialCaption = "NO EARLY RELEASE";
                    dialColor = Surface.danger(this);
                } else {
                    chip = "CHECK";
                    chipColor = Surface.warning(this);
                    healthView.setText(R.string.health_degraded);
                    healthColor = Surface.warning(this);
                    mottoPrimary.setText("Stay focused.");
                    mottoSecondary.setText("One thing needs attention.");
                    dialEyebrow = "FOCUS IN PROGRESS";
                    dialCaption = "REMAINING";
                    dialColor = Surface.warning(this);
                }
                break;
            case TAMPERED:
                boolean recoveryLock = displayedRecord.isRecoveryLock();
                chip = recoveryLock ? "LOCK" : "ALERT";
                chipColor = Surface.danger(this);
                healthView.setText(recoveryLock
                        ? "Signed journal unavailable. Blocking remains on until you recover the app state."
                        : getString(R.string.health_tampered));
                healthColor = Surface.danger(this);
                mottoPrimary.setText(recoveryLock ? "Recovery lock." : "Session protected.");
                mottoSecondary.setText(recoveryLock
                        ? "Open app info to reset local state." : "Review the warning below.");
                dialEyebrow = recoveryLock ? "RECOVERY LOCK" : "FOCUS IN PROGRESS";
                dialCaption = recoveryLock ? "NO EARLY RELEASE" : "REMAINING";
                dialColor = Surface.danger(this);
                break;
            case ARMING:
                chip = "PREP";
                chipColor = Surface.warning(this);
                healthView.setText(R.string.health_arming);
                healthColor = Surface.warning(this);
                mottoPrimary.setText("Preparing your space.");
                mottoSecondary.setText("Stay here for a moment.");
                dialEyebrow = "PREPARING SESSION";
                dialCaption = "REMAINING";
                dialColor = Surface.warning(this);
                break;
            case BREAK:
                chip = "BREAK";
                chipColor = Surface.ready(this);
                healthView.setText("Break — app blocking is off; the next focus starts automatically.");
                healthColor = Surface.textMuted(this);
                mottoPrimary.setText("Take a breath.");
                mottoSecondary.setText("Your next focus is ready.");
                dialEyebrow = "POMODORO BREAK";
                dialCaption = "REMAINING";
                dialColor = Surface.ready(this);
                break;
            case RELEASED:
                chip = "DONE";
                chipColor = Surface.ready(this);
                healthView.setText(R.string.health_released);
                healthColor = Surface.textMuted(this);
                mottoPrimary.setText("Session complete.");
                mottoSecondary.setText("You made space for focus.");
                dialEyebrow = "READY TO FOCUS";
                dialCaption = "MINUTES";
                dialColor = Surface.accent(this);
                break;
            case IDLE:
            default:
                chip = "READY";
                chipColor = Surface.accent(this);
                healthView.setText("A quiet, protected space for your next focus block.");
                healthColor = Surface.textMuted(this);
                mottoPrimary.setText("Less distractions.");
                mottoSecondary.setText("More you.");
                dialEyebrow = "READY TO FOCUS";
                dialCaption = "MINUTES";
                dialColor = Surface.accent(this);
                break;
        }

        stateChip.setText(chip);
        stateChip.setTextColor(chipColor);
        stateChip.setBackground(Surface.chip(this,
                Surface.mix(Surface.surface(this), chipColor, 0.13f),
                Surface.mix(Surface.divider(this), chipColor, 0.35f)));
        healthView.setTextColor(healthColor);
        startButtonContainer.setVisibility(idleState ? View.VISIBLE : View.GONE);
        editAllowedView.setVisibility(idleState ? View.VISIBLE : View.GONE);
        boolean dialEditable = idleState && !starting;
        dialView.setClickable(dialEditable);
        dialView.setFocusable(dialEditable);
        dialView.setActionHint(dialEditable ? "Tap to edit the focus plan." : "");
        updatePlanSummary();
        updateAllowedAppsSubtitle();
        updateStartButton();
        updateCountdown();
        if (semanticChange) {
            animateStateTransition();
        }
        lastAnimatedState = state;
        lastPlanSignature = planSignature;
    }
    private String currentPlanSignature() {
        PomodoroPlan plan = selectedPlan;
        if (displayedRecord != null && displayedRecord.getPomodoroPlan() != null
                && displayedRecord.getState() != SessionState.IDLE
                && displayedRecord.getState() != SessionState.RELEASED) {
            plan = displayedRecord.getPomodoroPlan();
        }
        return plan == null ? "none"
                : plan.isStraight() + ":" + plan.getFocusMinutes()
                + ":" + plan.getCurrentSession() + ":" + plan.getTotalSessions();
    }

    private void animateStateTransition() {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
            return;
        }
        if (stateChip != null) {
            stateChip.animate().cancel();
            stateChip.setAlpha(0.72f);
            stateChip.setScaleX(0.96f);
            stateChip.animate().alpha(1f).scaleX(1f).setDuration(220).start();
        }
        if (healthView != null) {
            healthView.animate().cancel();
            healthView.setAlpha(0.35f);
            healthView.setTranslationY(Surface.dp(this, 3));
            healthView.animate().alpha(1f).translationY(0f).setDuration(240).start();
        }
        if (dialView != null) {
            dialView.animate().cancel();
            dialView.setAlpha(0.86f);
            dialView.setTranslationY(Surface.dp(this, 2));
            dialView.animate().alpha(1f).translationY(0f).setDuration(260).start();
        }
    }

    private void updateCountdown() {
        if (displayedRecord == null || dialView == null) {
            return;
        }
        if (displayedRecord.isRecoveryLock()) {
            dialView.render(dialEyebrow, "—", dialCaption, 1f, dialColor);
            return;
        }
        long elapsedSinceCheckpoint = Math.max(0L,
                SystemClock.elapsedRealtime() - displayedRecord.getCheckpointElapsedMs());
        long remaining = Math.max(0L, displayedRecord.getRemainingMs() - elapsedSinceCheckpoint);
        boolean countdown = displayedRecord.isEnforcing()
                || displayedRecord.getState() == SessionState.ARMING
                || displayedRecord.getState() == SessionState.BREAK;
        if (!countdown) {
            long focusDuration = selectedPlan == null ? 15L * 60L * 1000L
                    : selectedPlan.getFocusDurationMs();
            dialView.render(dialEyebrow, formatDuration(focusDuration), dialCaption, 1f, dialColor);
            return;
        }
        long total = Math.max(1L, displayedRecord.getDurationMs());
        float fraction = Math.max(0f, Math.min(1f, (float) remaining / (float) total));
        dialView.render(dialEyebrow, formatDuration(remaining), dialCaption, fraction, dialColor);
    }

    private void updateStartButton() {
        if (!idleState) {
            return;
        }
        boolean enabled = canStart && !starting;
        startButton.setEnabled(enabled);
        startButton.setBackground(enabled
                ? Surface.accentButton(this)
                : Surface.accentButtonDisabled(this));
        startButton.setTextColor(enabled
                ? Surface.onAccent(this)
                : Surface.textMuted(this));
        startButton.setCompoundDrawableTintList(ColorStateList.valueOf(
                enabled ? Surface.onAccent(this) : Surface.textMuted(this)));
        startButton.setShadowLayer(enabled ? Surface.dp(this, 24) : 0f,
                0f, Surface.dp(this, 10),
                Surface.withAlpha(Surface.accent(this), enabled ? 100 : 0));
        if (starting) {
            startButton.setText(R.string.arming_notice);
        } else {
            startButton.setText(enabled ? "Start focus" : "Complete setup");
        }
    }

    private void renderGate() {
        if (setupContainer == null) {
            return;
        }
        setupContainer.removeAllViews();
        boolean allReady = displayedGate != null && displayedGate.isOk();
        int remaining = 0;
        if (displayedGate != null) {
            for (Check check : displayedGate.getChecks()) {
                if (!check.isOk()) {
                    remaining++;
                }
            }
        }
        gateSummaryView.setText(allReady
                ? getString(R.string.setup_complete_message)
                : remaining + " setup " + (remaining == 1 ? "item needs" : "items need")
                + " your attention.");
        gateSummaryView.setTextColor(allReady ? Surface.ready(this) : Surface.warning(this));
        if (displayedGate != null) {
            for (Check check : displayedGate.getChecks()) {
                setupContainer.addView(setupRow(check, displayedGate));
            }
        }
        boolean showSetup = setupExpanded || (!idleState && !allReady);
        setupCard.setVisibility(showSetup ? View.VISIBLE : View.GONE);

        PomodoroPlan activePlan = displayedRecord.getPomodoroPlan();
        boolean straightMode = activePlan != null && activePlan.isStraight();
        if (idleState) {
            if (starting) {
                setActionHint("Preparing your protected focus session…", false, Surface.warning(this));
            } else if (allReady) {
                String planText = selectedPlan == null ? "your focus plan"
                        : selectedPlan.isStraight()
                        ? selectedPlan.getFocusLabel() + " straight timer"
                        : selectedPlan.getFocusMinutes() + " min focus × "
                        + selectedPlan.getTotalSessions() + " sessions";
                setActionHint("Ready when you are • " + planText, false,
                        Surface.textFaint(this));
            } else {
                setActionHint(remaining + " setup " + (remaining == 1 ? "item" : "items")
                        + " left • tap to review", true, Surface.accent(this));
            }
        } else if (displayedRecord.getState() == SessionState.BREAK) {
            setActionHint("Break in progress • the next focus starts automatically", false,
                    Surface.textFaint(this));
        } else {
            setActionHint(straightMode
                    ? "Straight timer in progress • there is no early release"
                    : "Focus in progress • there is no early release", false,
                    Surface.textFaint(this));
        }
        if (displayedRecord.isRecoveryLock()) {
            setupExpanded = true;
            setupCard.setVisibility(View.VISIBLE);
            gateSummaryView.setText("Signed journal recovery is required before starting a new session.");
            gateSummaryView.setTextColor(Surface.danger(this));
            setActionHint("Tap to open Cockblocker's app info", true, Surface.danger(this));
            actionHint.setOnClickListener(view -> openAppInfo());
        }
    }

    private void openAppInfo() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, "App info is unavailable on this device.", Toast.LENGTH_LONG).show();
        }
    }

    private void setActionHint(CharSequence text, boolean clickable, int color) {
        actionHint.setText(text);
        actionHint.setTextColor(color);
        actionHint.setOnClickListener(clickable ? view -> openSetup() : null);
        actionHint.setClickable(clickable);
        actionHint.setFocusable(clickable);
        actionHint.setBackground(clickable
                ? Surface.rippleSurface(this, 0x00000000, 14,
                Surface.withAlpha(Surface.accent(this), 38))
                : null);
    }

    private View setupRow(Check check, GateResult gate) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Surface.card(this, Surface.surfaceRaised(this),
                Surface.divider(this), true));
        row.setPadding(Surface.dp(this, 13), Surface.dp(this, 11),
                Surface.dp(this, 13), Surface.dp(this, 11));

        boolean ok = check.isOk();
        View badge = new View(this);
        badge.setBackground(Surface.chip(this,
                ok ? Surface.ready(this) : Surface.warning(this), 0));
        row.addView(badge, new LinearLayout.LayoutParams(
                Surface.dp(this, 8), Surface.dp(this, 8)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.leftMargin = Surface.dp(this, 12);
        labelParams.rightMargin = Surface.dp(this, 8);
        TextView label = Surface.text(this, check.getLabel(), 14, Surface.text(this), true);
        labels.addView(label);
        if (!ok && check.getReason() != null && !check.getReason().isEmpty()) {
            TextView reason = Surface.text(this, check.getReason(), 12,
                    Surface.textMuted(this), false);
            reason.setLineSpacing(0f, 1.25f);
            reason.setPadding(0, Surface.dp(this, 3), 0, 0);
            labels.addView(reason);
        }
        row.addView(labels, labelParams);

        if (!ok) {
            Button fix = new Button(this);
            Intent remediation = gate.remediation(this, check);
            fix.setAllCaps(false);
            fix.setText(getString(R.string.setup_action));
            fix.setTextSize(13);
            fix.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            fix.setMinWidth(0);
            fix.setMinHeight(0);
            fix.setMinimumWidth(0);
            fix.setMinimumHeight(0);
            fix.setEnabled(remediation != null);
            fix.setTextColor(remediation != null
                    ? Surface.onAccent(this)
                    : Surface.textFaint(this));
            fix.setBackground(remediation != null
                    ? Surface.accentButton(this)
                    : Surface.accentButtonDisabled(this));
            fix.setPadding(Surface.dp(this, 18), 0, Surface.dp(this, 18), 0);
            fix.setOnClickListener(view -> launchRemediation(remediation));
            row.addView(fix, new LinearLayout.LayoutParams(
                    Surface.dp(this, 68), Surface.dp(this, 40)));
        }
        return row;
    }

    private void renderAppSelection() {
        if (appContainer == null || !idleState || displayedRecord == null) {
            return;
        }
        appContainer.removeAllViews();
        criticalSummaryView.setText(displayedRecord.getBlocked().containsAll(criticalPackages)
                ? "Safety-critical apps cannot be blocked"
                : "Safety check: critical apps are excluded from blocking");

        if (allApps.isEmpty()) {
            appContainer.addView(Surface.text(this, getString(R.string.selection_empty), 13,
                    Surface.textMuted(this), false), wrap());
            matchedAppCount = 0;
            renderedAppCount = 0;
            showMoreButton.setVisibility(View.GONE);
            return;
        }

        matchedAppCount = 0;
        renderedAppCount = 0;
        for (ResolveInfo info : allApps) {
            ActivityInfo activity = info.activityInfo;
            if (activity == null || activity.packageName.equals(getPackageName())) {
                continue;
            }
            String label = String.valueOf(info.loadLabel(getPackageManager()));
            if (!appFilter.isEmpty()
                    && !label.toLowerCase(Locale.US).contains(appFilter)
                    && !activity.packageName.toLowerCase(Locale.US).contains(appFilter)) {
                continue;
            }
            matchedAppCount++;
            if (renderedAppCount < appLimit) {
                appContainer.addView(appSelectionRow(info, label), wrapBottom(Surface.dp(this, 7)));
                renderedAppCount++;
            }
        }
        if (matchedAppCount == 0) {
            appContainer.addView(Surface.text(this, "No apps match your filter.", 13,
                    Surface.textMuted(this), false), wrap());
        }


        int remaining = matchedAppCount - renderedAppCount;
        showMoreButton.setVisibility(remaining > 0 ? View.VISIBLE : View.GONE);
        showMoreButton.setText("Show " + Math.min(LIST_PAGE, remaining) + " more");
    }

    private View appSelectionRow(ResolveInfo info, String label) {
        String packageName = info.activityInfo.packageName;
        boolean critical = criticalPackages.contains(packageName);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Surface.dp(this, 66));
        row.setPadding(Surface.dp(this, 11), Surface.dp(this, 9),
                Surface.dp(this, 11), Surface.dp(this, 9));
        row.setBackground(Surface.rippleSurface(this, Surface.surfaceRaised(this), 17,
                Surface.withAlpha(Surface.accent(this), 34)));
        row.setFocusable(true);

        row.addView(appIcon(info, 42, 29), new LinearLayout.LayoutParams(
                Surface.dp(this, 42), Surface.dp(this, 42)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.leftMargin = Surface.dp(this, 12);
        labelParams.rightMargin = Surface.dp(this, 8);
        TextView name = Surface.text(this, label, 14,
                critical ? Surface.textMuted(this) : Surface.text(this), true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(name);
        TextView detail = Surface.text(this,
                critical ? "Always available • cannot block" : packageName, 11,
                Surface.textFaint(this), false);
        detail.setMaxLines(1);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        detail.setPadding(0, Surface.dp(this, 3), 0, 0);
        labels.addView(detail);
        row.addView(labels, labelParams);

        Switch toggle = new Switch(this);
        toggle.setShowText(false);
        toggle.setChecked(critical || draftAllowed.contains(packageName));
        toggle.setEnabled(!critical);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        toggle.setThumbTintList(new ColorStateList(states, new int[]{
                Surface.accent(this), Surface.textFaint(this)
        }));
        toggle.setTrackTintList(new ColorStateList(states, new int[]{
                Surface.withAlpha(Surface.accent(this), 125),
                Surface.withAlpha(Surface.textFaint(this), 52)
        }));
        toggle.setContentDescription(label);
        toggle.setOnCheckedChangeListener((button, checked) -> {
            draftAllowed = new HashSet<>(draftAllowed);
            if (checked) {
                draftAllowed.add(packageName);
            } else {
                draftAllowed.remove(packageName);
                draftAllowed.addAll(criticalPackages);
            }
            saveSelection();
            row.setContentDescription(label + (checked ? ", allowed" : ", blocked"));
            renderAllowedSummary();
        });
        row.setOnClickListener(view -> {
            if (!critical) {
                toggle.toggle();
            }
        });
        row.setContentDescription(label + (toggle.isChecked() ? ", allowed" : ", blocked"));
        row.addView(toggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void renderAllowedSummary() {
        if (allowedAppsRow == null) {
            return;
        }
        allowedAppsRow.removeAllViews();
        int shown = 0;
        for (ResolveInfo info : allApps) {
            ActivityInfo activity = info.activityInfo;
            if (activity == null || activity.packageName.equals(getPackageName())
                    || !draftAllowed.contains(activity.packageName)) {
                continue;
            }
            allowedAppsRow.addView(allowedAppTile(info), tileParams());
            shown++;
        }
        if (idleState) {
            allowedAppsRow.addView(addAppTile(), tileParams());
        } else if (shown == 0) {
            TextView empty = Surface.text(this, "No launchable exceptions resolved",
                    12, Surface.textFaint(this), false);
            empty.setGravity(Gravity.CENTER_VERTICAL);
            allowedAppsRow.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Surface.dp(this, 74)));
        }
        editAllowedView.setText(selectionCard != null
                && selectionCard.getVisibility() == View.VISIBLE ? "Done" : "Edit");
    }

    private View allowedAppTile(ResolveInfo info) {
        String label = String.valueOf(info.loadLabel(getPackageManager()));
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setContentDescription(label + ", allowed");
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setBackground(Surface.rippleSurface(this, 0x00000000, 17,
                Surface.withAlpha(Surface.accent(this), 34)));
        tile.setOnClickListener(view -> launchAllowedApp(info));
        TextView name = Surface.text(this, label, 11, Surface.textMuted(this), false);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, Surface.dp(this, 7), 0, 0);
        tile.addView(name);
        return tile;
    }

    private View addAppTile() {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setContentDescription("Add allowed apps");
        tile.setOnClickListener(view -> toggleSelectionCard());

        FrameLayout icon = new FrameLayout(this);
        icon.setBackground(Surface.card(this, Surface.surfaceRaised(this),
                Surface.divider(this), true));
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.ic_add);
        plus.setColorFilter(Surface.textMuted(this));
        icon.addView(plus, new FrameLayout.LayoutParams(
                Surface.dp(this, 22), Surface.dp(this, 22), Gravity.CENTER));
        tile.addView(icon, new LinearLayout.LayoutParams(
                Surface.dp(this, 54), Surface.dp(this, 54)));
        TextView label = Surface.text(this, "Add", 11, Surface.textMuted(this), false);
        label.setGravity(Gravity.CENTER);
        label.setPadding(0, Surface.dp(this, 7), 0, 0);
        tile.addView(label);
        return tile;
    }

    private LinearLayout.LayoutParams tileParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                Surface.dp(this, 66), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = Surface.dp(this, 8);
        return params;
    }

    private View appIcon(ResolveInfo info, int containerDp, int iconDp) {
        FrameLayout container = new FrameLayout(this);
        container.setBackground(Surface.card(this, Surface.surfaceRaised(this),
                Surface.divider(this), true));
        ImageView icon = new ImageView(this);
        Drawable drawable;
        try {
            drawable = info.loadIcon(getPackageManager());
        } catch (RuntimeException unavailable) {
            drawable = null;
        }
        if (drawable == null) {
            drawable = getDrawable(R.drawable.ic_cockblocker_mark);
        }
        icon.setImageDrawable(drawable);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setContentDescription(null);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Surface.dp(this, iconDp), Surface.dp(this, iconDp), Gravity.CENTER);
        container.addView(icon, params);
        return container;
    }

    // ---------------------------------------------------------------- actions

    private String formatPlanSummary() {
        PomodoroPlan plan = selectedPlan;
        if (displayedRecord != null && displayedRecord.getPomodoroPlan() != null
                && displayedRecord.getState() != SessionState.IDLE
                && displayedRecord.getState() != SessionState.RELEASED) {
            plan = displayedRecord.getPomodoroPlan();
        }
        if (plan == null) {
            return "Focus plan  ·  Edit";
        }
        if (displayedRecord != null && displayedRecord.getState() == SessionState.BREAK) {
            return "Break  ·  next focus " + (plan.getCurrentSession() + 1)
                    + " of " + plan.getTotalSessions();
        }
        if (displayedRecord != null && displayedRecord.isEnforcing()) {
            return plan.isStraight()
                    ? "Straight timer  ·  " + plan.getFocusMinutes() + " min"
                    : "Focus " + plan.getCurrentSession() + " of " + plan.getTotalSessions()
                    + "  ·  " + plan.getFocusMinutes() + " min";
        }
        if (plan.isStraight()) {
            return plan.getFocusLabel() + " straight timer  ·  Edit";
        }
        return plan.getFocusMinutes() + "m focus  ·  " + plan.getBreakMinutes()
                + "m break  ·  " + plan.getTotalSessions() + " sessions  ·  Edit";
    }

    private void updatePlanSummary() {
        if (planSummary == null) {
            return;
        }
        planSummary.setText(formatPlanSummary());
        boolean editable = idleState && !starting;
        planSummary.setClickable(editable);
        planSummary.setFocusable(editable);
        planSummary.setTextColor(editable ? Surface.accent(this) : Surface.textMuted(this));
        planSummary.setBackground(editable
                ? Surface.rippleSurface(this, 0x00000000, 14,
                Surface.withAlpha(Surface.accent(this), 38))
                : null);
    }

    private void updateAllowedAppsSubtitle() {
        if (allowedAppsSubtitle == null) {
            return;
        }
        if (displayedRecord != null && displayedRecord.getState() == SessionState.BREAK) {
            allowedAppsSubtitle.setText("Break active — apps are available until the next focus.");
        } else {
            allowedAppsSubtitle.setText(
                    "Only these apps stay available during a session. Tap one to open it.");
        }
    }

    private void showPlanEditor() {
        if (!idleState || starting || selectedPlan == null) {
            return;
        }
        PomodoroPlanDialog.show(this, selectedPlan, plan -> {
            selectedPlan = plan;
            planStore.save(plan);
            updatePlanSummary();
            updateCountdown();
            animateStateTransition();
            lastPlanSignature = currentPlanSignature();
        });
    }

    private void openSetup() {
        setupExpanded = true;
        boolean wasVisible = setupCard.getVisibility() == View.VISIBLE;
        renderGate();
        if (!wasVisible) {
            animateSection(setupCard, true);
        }
        scrollToSection(setupCard);
    }

    private void toggleSelectionCard() {
        if (!idleState || selectionCard == null) {
            return;
        }
        boolean show = selectionCard.getVisibility() != View.VISIBLE;
        animateSection(selectionCard, show);
        if (show) {
            renderAppSelection();
            scrollToSection(selectionCard);
        } else {
            editAllowedView.requestFocus();
            scrollToTop();
        }
        renderAllowedSummary();
    }

    private void animateSection(View view, boolean show) {
        view.animate().cancel();
        if (show) {
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            view.setVisibility(View.VISIBLE);
            view.setAlpha(0f);
            view.setTranslationY(Surface.dp(this, 8));
            view.animate().alpha(1f).translationY(0f).setDuration(220).start();
        } else {
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            view.animate().alpha(0f).translationY(Surface.dp(this, -4))
                    .setDuration(160).withEndAction(() -> {
                        view.setVisibility(View.GONE);
                        view.setAlpha(1f);
                        view.setTranslationY(0f);
                    }).start();
        }
    }
    private void scrollToSection(View section) {
        if (section == null) {
            return;
        }
        scroll.post(() -> {
            int destination = Math.max(0, section.getTop() - Surface.dp(this, 12));
            scroll.smoothScrollTo(0, destination);
        });
    }

    private void scrollToTop() {
        scroll.post(() -> scroll.smoothScrollTo(0, 0));
    }

    private void showActivationFailure(String message) {
        Toast.makeText(this, message == null || message.isEmpty()
                ? getString(R.string.activation_failed) : message, Toast.LENGTH_LONG).show();
        setupExpanded = true;
        renderGate();
        scrollToSection(setupCard);
    }
    private String describeGateFailure(GateResult gate) {
        if (gate == null) {
            return "Setup checks are unavailable on this device.";
        }
        for (Check check : gate.getChecks()) {
            if (!check.isSatisfied()) {
                return "Setup required: " + check.getLabel() + " — " + check.getReason();
            }
        }
        return gate.getSummary();
    }

    /**
     * Why the journal refused to arm the session, using the cause the repository
     * recorded at the moment it declined. Never claims the journal "rejected"
     * anything: the reported reason is the precondition that actually fired.
     */
    private String activationFailureMessage() {
        GateResult gateFailure = repository.getLastGateFailure();
        if (gateFailure != null) {
            return describeGateFailure(gateFailure);
        }
        String reason = repository.getLastFailureReason();
        if (reason == null || reason.isEmpty()) {
            return getString(R.string.activation_failed);
        }
        return "Session start was refused: " + reason + ".";
    }

    private void beginSession() {
        if (starting || displayedRecord == null) {
            return;
        }
        if (!idleState) {
            showActivationFailure(displayedRecord.isRecoveryLock()
                    ? "Recover the signed journal before starting another session."
                    : "A session is already active. Wait for it to finish.");
            return;
        }
        if (BuildConfig.DEBUG) {
            Toast.makeText(this, R.string.debug_build_cannot_start, Toast.LENGTH_LONG).show();
            refresh();
            return;
        }
        activationFailureMessage = "";
        repository.clearLastFailure();
        SessionRecord beforeStart = displayedRecord;
        GateResult startGate = TrustAnchorGate.evaluateForStart(this);
        if (startGate == null || !startGate.isOk()) {
            showActivationFailure(describeGateFailure(startGate));
            refresh();
            return;
        }

        Set<String> allowed = new HashSet<>(draftAllowed);
        allowed.addAll(criticalPackages);
        Set<String> launchable = new LinkedHashSet<>();
        for (ResolveInfo info : allApps) {
            ActivityInfo activity = info.activityInfo;
            if (activity != null && !activity.packageName.equals(getPackageName())) {
                launchable.add(activity.packageName);
            }
        }
        // Settings is blockable even when the device ships no launcher entry for it.
        launchable.addAll(SafetySurfaceResolver.resolveSettingsPackages(this));
        Set<String> blocked = new HashSet<>(launchable);
        blocked.removeAll(allowed);
        Set<String> critical = new HashSet<>(criticalPackages);
        Set<String> routes = new HashSet<>(protectedRoutes);

        starting = true;
        setupExpanded = false;
        selectionCard.animate().cancel();
        selectionCard.setAlpha(1f);
        selectionCard.setTranslationY(0f);
        selectionCard.setVisibility(View.GONE);
        startButton.setEnabled(false);
        startButton.setText(R.string.arming_notice);
        updateStartButton();
        SessionRecord arming = null;
        boolean watchdogRequested = false;
        try {
            arming = repository.start(allowed, blocked, critical, routes, selectedPlan);
            if (arming == null || arming.getState() != SessionState.ARMING
                    || arming.getSequence() != beforeStart.getSequence() + 1L
                    || arming.getSessionId().equals(beforeStart.getSessionId())) {
                activationFailureMessage = activationFailureMessage();
                repository.clearLastFailure();
                throw new IllegalStateException("session start did not produce the expected arming record");
            }
            if (!IntegrityGuard.verify(this, arming)) {
                activationFailureMessage = "Release signing check failed on "
                        + Build.MANUFACTURER + " / Android " + Build.VERSION.RELEASE
                        + ". Install app-release.apk.";
                throw new IllegalStateException("integrity verification failed");
            }
            GateResult activationGate = TrustAnchorGate.evaluateForActivation(this);
            if (activationGate == null || !activationGate.isOk()) {
                activationFailureMessage = describeGateFailure(activationGate);
                throw new IllegalStateException("activation prerequisites failed");
            }

            SessionWatchdogService.startForActivation(this, arming.getSessionId(), arming.getSequence());
            watchdogRequested = true;
            activationTarget = arming;
            activationPollAttempts = ACTIVATION_POLL_ATTEMPTS;
            ticker.postDelayed(activationPoll, ACTIVATION_POLL_INTERVAL_MS);
        } catch (RuntimeException error) {
            abortPendingActivation(arming);
            if (watchdogRequested) {
                stopWatchdog();
            }
            starting = false;
            activationTarget = null;
            refresh();
            showActivationFailure(activationFailureMessage);
        }
    }

    private void completeActivation(SessionRecord current) {
        ticker.removeCallbacks(activationPoll);
        starting = false;
        activationFailureMessage = "";
        activationTarget = null;
        displayedRecord = current;
        setupExpanded = false;
        refresh();
        PomodoroPlan startedPlan = current.getPomodoroPlan();
        Toast.makeText(this, startedPlan != null && startedPlan.isStraight()
                ? "Straight timer started" : "Pomodoro focus started", Toast.LENGTH_SHORT).show();
    }

    private void pollActivation() {
        SessionRecord target = activationTarget;
        if (!starting || target == null) {
            return;
        }
        SessionRecord current = safeRead();
        if (isExpectedActivationResult(target, current) && current.getState() == SessionState.ACTIVE) {
            completeActivation(current);
            return;
        }
        boolean terminalFailure = current != null
                && (current.getState() == SessionState.IDLE
                || current.getState() == SessionState.RELEASED
                || current.getState() == SessionState.DEGRADED
                || current.getState() == SessionState.TAMPERED);
        if (terminalFailure || --activationPollAttempts <= 0) {
            SessionRecord afterAbort = abortPendingActivation(target);
            if (isExpectedActivationResult(target, afterAbort)
                    && afterAbort.getState() == SessionState.ACTIVE) {
                completeActivation(afterAbort);
                return;
            }
            stopWatchdog();
            starting = false;
            activationTarget = null;
            activationFailureMessage = (terminalFailure
                    ? "The watchdog reported that the session did not activate."
                    : "The watchdog did not confirm activation in time.")
                    + " (" + Build.MANUFACTURER + " / Android "
                    + Build.VERSION.RELEASE + ")";
            refresh();
            showActivationFailure(activationFailureMessage);
            return;
        }
        ticker.postDelayed(activationPoll, ACTIVATION_POLL_INTERVAL_MS);
    }

    private SessionRecord abortPendingActivation(SessionRecord target) {
        if (target == null) {
            return safeRead();
        }
        try {
            repository.abortArming(target.getSessionId(), target.getSequence(),
                    "activation failed in MainActivity");
        } catch (RuntimeException ignored) {
            // The authenticated journal remains authoritative.
        }
        return safeRead();
    }

    private void stopWatchdog() {
        try {
            SessionWatchdogService.stop(this);
        } catch (RuntimeException ignored) {
            // The authenticated journal remains authoritative.
        }
    }

    private static boolean isExpectedActivationResult(SessionRecord target, SessionRecord current) {
        return target != null && current != null
                && target.getSessionId().equals(current.getSessionId())
                && target.getSequence() == current.getSequence();
    }

    /**
     * Opens an allowed app from this Home surface. The resolved launcher component is
     * used directly so the launch cannot resolve to a different, blocked activity, and
     * the package is exempt from the redirect for the whole session.
     */
    private void launchAllowedApp(ResolveInfo info) {
        ActivityInfo activity = info == null ? null : info.activityInfo;
        if (activity == null || activity.packageName == null || activity.name == null) {
            Toast.makeText(this, "That app is no longer installed.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(activity.packageName, activity.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(launch);
        } catch (RuntimeException unavailable) {
            Toast.makeText(this, "That app could not be opened.", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchRemediation(Intent remediation) {
        if (remediation == null) {
            Toast.makeText(this, "This Settings page is unavailable on this device.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(remediation);
        } catch (RuntimeException denied) {
            Toast.makeText(this, "Android did not allow that Settings page.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private List<ResolveInfo> queryLaunchableApps() {
        PackageManager packageManager = getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved;
        try {
            resolved = packageManager.queryIntentActivities(
                    launcherIntent, PackageManager.MATCH_ALL);
        } catch (RuntimeException unavailable) {
            return Collections.emptyList();
        }
        Map<String, ResolveInfo> unique = new LinkedHashMap<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo != null) {
                unique.put(info.activityInfo.packageName, info);
            }
        }
        List<ResolveInfo> apps = new ArrayList<>(unique.values());
        Collections.sort(apps, Comparator.comparing(
                info -> String.valueOf(info.loadLabel(packageManager)),
                String.CASE_INSENSITIVE_ORDER));
        return apps;
    }

    private SessionRecord safeRead() {
        try {
            return repository.read();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private Set<String> loadSelection() {
        Set<String> stored = getSharedPreferences(SELECTION_PREFS, MODE_PRIVATE)
                .getStringSet("allowed_packages", Collections.emptySet());
        return stored == null ? new HashSet<>() : new HashSet<>(stored);
    }

    private void saveSelection() {
        getSharedPreferences(SELECTION_PREFS, MODE_PRIVATE).edit()
                .putStringSet("allowed_packages", new HashSet<>(draftAllowed)).apply();
    }

    private static Set<String> safeSet(Set<String> value) {
        return value == null ? Collections.emptySet() : value;
    }

    private static String formatDuration(long milliseconds) {
        long totalSeconds = (Math.max(0L, milliseconds) + 999L) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
    }

    // ---------------------------------------------------------------- layout

    private static LinearLayout.LayoutParams wrap() {
        return wrap(0);
    }

    private static LinearLayout.LayoutParams wrap(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private static LinearLayout.LayoutParams wrapBottom(int bottomMargin) {
        LinearLayout.LayoutParams params = wrap();
        params.bottomMargin = bottomMargin;
        return params;
    }

    private static LinearLayout.LayoutParams wrapTop(int topMargin) {
        return wrap(topMargin);
    }
}
