package com.kokonaharness.android.akizuki;

/**
 * User-visible wording that has to stay consistent across surfaces.
 *
 * <p>Everything KHA says about the runtime is a <em>DSH 提醒</em>; the old
 * "WebView 提醒" name leaked from implementation detail into the UI and is gone.
 * Keeping the title here (instead of a literal at each call site) is what makes
 * that checkable: {@code grep -rn "DSH 提醒"} in the APK sources finds one
 * definition plus its uses, not a dozen copies drifting apart.</p>
 */
public final class Notices {
    /** Title for every notice KHA raises on the runtime's behalf. */
    public static final String TITLE = "DSH 提醒";

    private Notices() {
    }
}
