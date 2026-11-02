package com.atakmap.android.tfr.plugin.engine;

/** Every timing and threshold in one place. */
public final class Tuning {

    private Tuning() {
    }

    private static final long MIN = 60000L;

    /** Normal polling interval of the FAA list. */
    public static final long POLL_MS = 10 * MIN;
    /** Retry delays after a failed poll (then POLL_MS). */
    public static final long[] RETRY_MS = { MIN, 2 * MIN, 5 * MIN };
    /** Don't poll on start if the cache is younger than this. */
    public static final long STARTUP_FRESH_MS = 2 * MIN;
    /** Minimum gap between manual refreshes. */
    public static final long MANUAL_MIN_GAP_MS = 15000L;
    /** Re-read each TFR's detail at least this often (amendments). */
    public static final long DETAIL_REFRESH_MS = 60 * MIN;
    /** Max detail re-reads per poll (new TFRs are never limited). */
    public static final int MAX_REFRESHES_PER_POLL = 15;
    /** Pause between detail requests, to be polite to the FAA server. */
    public static final long DETAIL_GAP_MS = 250L;
    /** Polls a TFR must be missing from the list before it is removed. */
    public static final int MISSING_POLLS_TO_REMOVE = 2;
    /** An empty list when we had at least this many is suspect... */
    public static final int SUSPECT_EMPTY_MIN_PREVIOUS = 3;
    /** ...and is accepted only after this many empty answers in a row. */
    public static final int SUSPECT_EMPTY_CONFIRMATIONS = 3;
    /** A drop to under half of at least this many is suspect... */
    public static final int SUSPECT_DROP_MIN_PREVIOUS = 10;
    /** ...and is accepted after this many such answers in a row. */
    public static final int SUSPECT_DROP_CONFIRMATIONS = 2;
    /** Data older than this is shown as stale. */
    public static final long STALE_MS = 30 * MIN;
    /** Re-evaluate active/pending states at least this often. */
    public static final long TICK_MS = MIN;
}
