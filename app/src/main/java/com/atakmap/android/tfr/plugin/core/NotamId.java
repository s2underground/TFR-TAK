package com.atakmap.android.tfr.plugin.core;

import java.util.regex.Pattern;

/**
 * FDC NOTAM numbers as the TFR list writes them: year digit, slash, sequence
 * ("6/4199", "0/0367"). Validated before any use in a URL or file name.
 */
public final class NotamId {

    private NotamId() {
    }

    private static final Pattern P = Pattern.compile("^\\d/\\d{1,5}$");

    public static boolean isValid(String id) {
        return id != null && P.matcher(id).matches();
    }

    /** Same NOTAM, ignoring zero padding of the sequence. */
    public static boolean same(String a, String b) {
        if (!isValid(a) || !isValid(b))
            return false;
        String[] x = a.split("/"), y = b.split("/");
        return x[0].equals(y[0])
                && Integer.parseInt(x[1]) == Integer.parseInt(y[1]);
    }

    /** Sequence padded to at least four digits, as the FAA writes it. */
    static String padSeq(String seq) {
        StringBuilder sb = new StringBuilder(seq);
        while (sb.length() < 4)
            sb.insert(0, '0');
        return sb.toString();
    }

    /** "6_4199": the form used in FAA file names and in our map item ids. */
    public static String fileKey(String id) {
        if (!isValid(id))
            throw new IllegalArgumentException("invalid NOTAM id");
        return id.replace('/', '_');
    }
}
