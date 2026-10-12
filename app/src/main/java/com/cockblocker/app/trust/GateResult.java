package com.cockblocker.app.trust;

import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable aggregate result of the trust-anchor gate. */
public final class GateResult {
    public final boolean ok;
    public final List<Check> checks;
    public final String summary;

    public GateResult(boolean ok, List<Check> checks) {
        this(ok, checks, defaultSummary(ok, checks));
    }

    public GateResult(boolean ok, List<Check> checks, String summary) {
        this.ok = ok;
        this.checks = Collections.unmodifiableList(new ArrayList<>(checks == null
                ? Collections.<Check>emptyList() : checks));
        this.summary = summary == null ? "" : summary;
    }

    public boolean isOk() { return ok; }
    public List<Check> getChecks() { return checks; }
    public List<Check> checks() { return checks; }
    public String getSummary() { return summary; }
    public String summary() { return summary; }

    public Intent remediation(Context context, Check check) {
        return check == null ? null : check.remediation(context);
    }

    private static String defaultSummary(boolean ok, List<Check> checks) {
        if (ok) {
            return "All required trust anchors are available.";
        }
        if (checks == null || checks.isEmpty()) {
            return "Required trust anchors are unavailable.";
        }
        StringBuilder text = new StringBuilder("Setup required:");
        for (Check check : checks) {
            if (!check.satisfied) {
                text.append(' ').append(check.label);
            }
        }
        return text.toString();
    }
}
