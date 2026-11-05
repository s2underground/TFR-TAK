package com.atakmap.android.tfr.plugin.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.atakmap.android.tfr.plugin.core.LatLon;
import com.atakmap.android.tfr.plugin.engine.Snapshot;

import java.util.List;
import java.util.TimeZone;

/**
 * The TFR-TAK pane, built in code: status, filters, the list of TFRs and a
 * detail view with the full NOTAM text. Renders what {@link Rows} computes.
 * Main thread only.
 */
public final class TfrPane {

    public interface Actions {
        void refresh();

        void setSecurityOnly(boolean on);

        void setShowEnded(boolean on);

        /** Show or remove every TFR shape on the map. */
        void setMapVisible(boolean on);

        /** Show or hide the TFR labels (shapes stay). */
        void setLabelsVisible(boolean on);

        boolean isCollapsed(Rows.Box box);

        void setCollapsed(Rows.Box box, boolean collapsed);

        /** Zoom the map to a TFR; false if it has nothing drawn. */
        boolean goTo(String id);

        /** Current self position, or null if unknown. */
        LatLon self();
    }

    private static final int BG = 0xFF1E1E1E;
    private static final int CARD = 0xFF2A2A2A;
    private static final int TEXT = 0xFFEEEEEE;
    private static final int DIM = 0xFFAAAAAA;
    private static final int AMBER = 0xFFFFC107;
    private static final int GREEN = 0xFF69F0AE;
    private static final int BTN = 0xFF3C3C3C;
    private static final int HEAD = 0xFF37474F;

    private final Context ctx;
    private final Actions actions;
    private final ScrollView root;
    private final LinearLayout listPage, detailPage;
    private final TextView status, warning, counts, detailText;
    private final LinearLayout rowsBox;
    private final CheckBox mapVisible, labelsVisible, securityOnly, showEnded;
    private final Button goToBtn;

    private Snapshot snap;
    private String detailId;
    private String lastRowsSig = null;

    public TfrPane(Context pluginContext, Actions actions, boolean onMap,
            boolean labels, boolean secOnly, boolean ended) {
        this.ctx = pluginContext;
        this.actions = actions;
        root = new ScrollView(ctx);
        root.setBackgroundColor(BG);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(8);
        box.setPadding(pad, pad, pad, pad);
        root.addView(box);

        // ---- list page
        listPage = vbox();
        status = text("", 15, TEXT, true);
        warning = text("", 13, AMBER, false);
        warning.setVisibility(View.GONE);
        listPage.addView(status);
        listPage.addView(warning);

        mapVisible = check("Show TFRs on map", onMap);
        mapVisible.setTypeface(Typeface.DEFAULT_BOLD);
        mapVisible.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                TfrPane.this.actions.setMapVisible(on);
                labelsVisible.setEnabled(on);
            }
        });
        listPage.addView(mapVisible);
        // For clean screenshots: shapes without their labels.
        labelsVisible = check("Show labels", labels);
        labelsVisible.setEnabled(onMap);
        labelsVisible.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                TfrPane.this.actions.setLabelsVisible(on);
            }
        });
        listPage.addView(labelsVisible);

        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(6), 0, dp(2));
        Button refresh = button("Refresh");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TfrPane.this.actions.refresh();
            }
        });
        bar.addView(refresh);
        securityOnly = check("Security & VIP only", secOnly);
        securityOnly.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                TfrPane.this.actions.setSecurityOnly(on);
                lastRowsSig = null;
                render();
            }
        });
        bar.addView(securityOnly);
        listPage.addView(bar);
        showEnded = check("Show ended", ended);
        showEnded.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                TfrPane.this.actions.setShowEnded(on);
                lastRowsSig = null;
                render();
            }
        });
        listPage.addView(showEnded);

        counts = text("", 13, DIM, false);
        counts.setPadding(0, dp(2), 0, dp(6));
        listPage.addView(counts);
        rowsBox = vbox();
        listPage.addView(rowsBox);
        listPage.addView(footer());
        box.addView(listPage);

        // ---- detail page
        detailPage = vbox();
        LinearLayout dbar = new LinearLayout(ctx);
        dbar.setOrientation(LinearLayout.HORIZONTAL);
        Button back = button("← All TFRs");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showList();
            }
        });
        dbar.addView(back);
        goToBtn = button("Show on map");
        goToBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (detailId != null)
                    TfrPane.this.actions.goTo(detailId);
            }
        });
        dbar.addView(goToBtn);
        detailPage.addView(dbar);
        detailText = text("", 14, TEXT, false);
        detailText.setTextIsSelectable(true);
        detailText.setPadding(0, dp(8), 0, dp(8));
        detailPage.addView(detailText);
        detailPage.addView(footer());
        detailPage.setVisibility(View.GONE);
        box.addView(detailPage);
    }

    public View getView() {
        return root;
    }

    public void update(Snapshot s) {
        snap = s;
        render();
    }

    public void showDetail(String id) {
        detailId = id;
        listPage.setVisibility(View.GONE);
        detailPage.setVisibility(View.VISIBLE);
        render();
        root.scrollTo(0, 0);
    }

    public void showList() {
        detailId = null;
        detailPage.setVisibility(View.GONE);
        listPage.setVisibility(View.VISIBLE);
        lastRowsSig = null;
        render();
    }

    private void render() {
        Snapshot s = snap;
        if (s == null) {
            status.setText("Starting…");
            return;
        }
        Format f = new Format(TimeZone.getDefault());
        if (detailId != null) {
            Snapshot.Item it = s.find(detailId);
            if (it == null) {
                detailText.setText("FDC " + detailId
                        + " is no longer in the FAA list.");
                goToBtn.setEnabled(false);
            } else {
                detailText.setText(Rows.detail(it, s.nowMs, f));
                goToBtn.setEnabled(it.tfr != null && it.tfr.hasOutline());
            }
            return;
        }
        status.setText(Rows.statusLine(s, f));
        status.setTextColor(Rows.statusIsWarning(s) ? AMBER : GREEN);
        String w = Rows.warningLine(s);
        warning.setText(w == null ? "" : w);
        warning.setVisibility(w == null ? View.GONE : View.VISIBLE);

        Rows.Filter filter = new Rows.Filter(securityOnly.isChecked(),
                showEnded.isChecked());
        List<Rows.Row> rows = Rows.build(s, filter, actions.self(), f);
        int drawn = 0, textOnly = 0;
        for (Snapshot.Item it : s.items)
            if (it.tfr != null) {
                if (it.tfr.hasOutline())
                    drawn++;
                else
                    textOnly++;
            }
        counts.setText(s.items.size() + " TFRs listed by the FAA · "
                + drawn + " with a map graphic · " + textOnly
                + " text-only · showing " + rows.size());

        List<Rows.Section> sections = Rows.sections(rows);
        // Rebuild only when the text or a box's open state changed (the
        // text changes each minute).
        StringBuilder sig = new StringBuilder();
        for (Rows.Section sec : sections) {
            sig.append(sec.box).append(actions.isCollapsed(sec.box)).append(':');
            for (Rows.Row r : sec.rows)
                sig.append(r.id).append(r.status).append(r.distance)
                        .append(r.notes.size()).append('|');
        }
        if (sig.toString().equals(lastRowsSig))
            return;
        lastRowsSig = sig.toString();
        rowsBox.removeAllViews();
        if (s.items.isEmpty() && s.lastListOkMs > 0) {
            rowsBox.addView(text("The FAA lists no TFRs.", 14, DIM, false));
            return;
        }
        for (Rows.Section sec : sections)
            rowsBox.addView(sectionView(sec));
    }

    private View sectionView(final Rows.Section sec) {
        LinearLayout box = vbox();
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.setMargins(0, dp(4), 0, dp(8));
        box.setLayoutParams(bl);
        final boolean collapsed = actions.isCollapsed(sec.box);
        TextView head = text((collapsed ? "\u25B6  " : "\u25BC  ")
                + sec.box.title + " (" + sec.rows.size() + ")", 15, TEXT, true);
        head.setBackgroundColor(HEAD);
        int p = dp(8);
        head.setPadding(p, p, p, p);
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.setCollapsed(sec.box, !collapsed);
                lastRowsSig = null;
                render();
            }
        });
        box.addView(head);
        if (collapsed)
            return box;
        if (sec.rows.isEmpty()) {
            TextView none = text(sec.box == Rows.Box.ACTIVE
                    ? "No TFR with a map graphic is active now."
                    : "None published.", 13, DIM, false);
            none.setPadding(dp(8), dp(6), 0, dp(2));
            box.addView(none);
        }
        for (Rows.Row r : sec.rows)
            box.addView(rowView(r));
        return box;
    }

    private View rowView(final Rows.Row r) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundColor(CARD);
        int p = dp(6);
        row.setPadding(p, p, p, p);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rl.setMargins(0, 0, 0, dp(4));
        row.setLayoutParams(rl);

        View sw = new View(ctx);
        sw.setBackgroundColor(r.category.color);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(6),
                ViewGroup.LayoutParams.MATCH_PARENT);
        sl.setMargins(0, 0, dp(8), 0);
        row.addView(sw, sl);

        LinearLayout col = vbox();
        col.addView(text(r.title, 15, TEXT, true));
        col.addView(text(r.status, 14, r.statusColor, false));
        col.addView(text(r.subtitle, 13, DIM, false));
        for (String n : r.notes)
            col.addView(text(n, 12, AMBER, false));
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = vbox();
        right.setGravity(Gravity.END);
        if (!r.distance.isEmpty())
            right.addView(text(r.distance, 14, TEXT, true));
        if (r.hasOutline) {
            Button go = button("Map");
            go.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    actions.goTo(r.id);
                }
            });
            right.addView(go);
        }
        row.addView(right);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDetail(r.id);
            }
        });
        return row;
    }

    private View footer() {
        TextView t = text("For awareness only. Not for navigation or flight "
                + "planning. The NOTAM text is the authority; the map is a "
                + "depiction. Verify at tfr.faa.gov or with Flight Service "
                + "(1-800-WX-BRIEF) before any flight, including drones.",
                11, DIM, false);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    // ---- view helpers ---------------------------------------------------

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold)
            t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setBackgroundColor(BTN);
        b.setMinHeight(dp(40));
        b.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(6), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private CheckBox check(String label, boolean on) {
        CheckBox c = new CheckBox(ctx);
        c.setText(label);
        c.setTextColor(TEXT);
        c.setChecked(on);
        return c;
    }
}
