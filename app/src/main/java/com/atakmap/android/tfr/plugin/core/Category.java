package com.atakmap.android.tfr.plugin.core;

import java.util.Locale;

/**
 * TFR category, from the regulation the NOTAM cites (TfrNot/codeType in the
 * XML), falling back to the type word in the FAA list. Colors are ARGB
 * strokes; fills use the same color at low alpha.
 */
public enum Category {
    SECURITY("Security", "14 CFR 99.7 Special Security Instructions", 0xFFE53935),
    VIP("VIP", "14 CFR 91.141 Proximity of the President and other parties", 0xFFD50000),
    HAZARD("Hazard / disaster", "14 CFR 91.137 / 91.138 Disaster and hazard areas", 0xFFFF8F00),
    EMERGENCY_TRAFFIC("Emergency air traffic", "14 CFR 91.139 Emergency air traffic rules", 0xFFFF8F00),
    SPACE("Space operations", "14 CFR 91.143 Space flight operations", 0xFF1E88E5),
    SPORTS("Air show / sports", "14 CFR 91.145 Aerial demonstrations and major sporting events", 0xFFFDD835),
    UAS("UAS public gathering", "49 USC 44812 UAS restrictions over public gatherings", 0xFF8E24AA),
    PRESSURE("High barometric pressure", "14 CFR 91.144 Abnormally high barometric pressure", 0xFF757575),
    SPECIAL("Special", "Special notice", 0xFF757575),
    OTHER("Other", "Other temporary flight restriction", 0xFF757575);

    public final String label;
    public final String regulation;
    public final int color;

    Category(String label, String regulation, int color) {
        this.label = label;
        this.regulation = regulation;
        this.color = color;
    }

    /** Security and VIP: the restrictions the plugin cares about most. */
    public boolean isSecurity() {
        return this == SECURITY || this == VIP;
    }

    /** From the cited regulation, then the FAA list type word. */
    public static Category of(String cfr, String listType) {
        String c = cfr == null ? "" : cfr.trim().toUpperCase(Locale.US);
        if (c.startsWith("99.7"))
            return SECURITY;
        if (c.startsWith("91.141"))
            return VIP;
        if (c.startsWith("91.137") || c.startsWith("91.138"))
            return HAZARD;
        if (c.startsWith("91.139"))
            return EMERGENCY_TRAFFIC;
        if (c.startsWith("91.143"))
            return SPACE;
        if (c.startsWith("91.145"))
            return SPORTS;
        if (c.startsWith("91.144"))
            return PRESSURE;
        if (c.startsWith("44812"))
            return UAS;
        String t = listType == null ? "" : listType.trim().toUpperCase(Locale.US);
        if (t.contains("SECURITY"))
            return SECURITY;
        if (t.contains("VIP"))
            return VIP;
        if (t.contains("HAZARD"))
            return HAZARD;
        if (t.contains("SPACE"))
            return SPACE;
        if (t.contains("SPORT") || t.contains("AIR SHOW"))
            return SPORTS;
        if (t.contains("UAS"))
            return UAS;
        if (t.contains("PRESSURE"))
            return PRESSURE;
        if (c.equals("SPECIAL") || t.contains("SPECIAL"))
            return SPECIAL;
        return OTHER;
    }
}
