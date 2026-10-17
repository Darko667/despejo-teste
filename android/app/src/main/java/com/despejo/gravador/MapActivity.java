package com.despejo.gravador;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Tela cheia com o mapa mental de um despejo (ou o mapa geral). */
public class MapActivity extends Activity {

    static void open(Context c, String title, String json, String md) {
        c.startActivity(new Intent(c, MapActivity.class)
                .putExtra("titulo", title).putExtra("json", json).putExtra("md", md));
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        int bg = Color.parseColor("#12161C");
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        String title = getIntent().getStringExtra("titulo");
        String json = getIntent().getStringExtra("json");
        String md = getIntent().getStringExtra("md");

        MindMap map = null;
        if (json != null && !json.trim().isEmpty()) {
            try { map = MindMap.fromJson(json); } catch (Exception ignored) { }
        }
        if (map == null) map = MindMap.fromMarkdown(md == null ? "" : md, title);

        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(bg);
        MindMapView mv = new MindMapView(this);
        mv.setMap(map, n -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle((n.icon.isEmpty() ? "" : n.icon + " ") + shorten(n.label))
                .setMessage(n.note.isEmpty() ? n.label : n.note)
                .setPositiveButton("Fechar", null)
                .show());
        root.addView(mv, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(8);
        bar.setPadding(p, p, p, p);
        bar.addView(button("‹ Voltar", v -> finish()));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.parseColor("#8B95A3"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(button("Ajustar", v -> mv.fit()));
        if (md != null && !md.isEmpty()) bar.addView(button("Texto", v -> showText(md)));
        root.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        setContentView(root);
    }

    private void showText(String md) {
        TextView t = new TextView(this);
        t.setText(md);
        t.setTextColor(Color.parseColor("#E8ECF1"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextIsSelectable(true);
        t.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(sv).setPositiveButton("Fechar", null).show();
    }

    private static String shorten(String s) {
        return s.length() > 40 ? s.substring(0, 38) + "…" : s;
    }

    private Button button(String label, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.parseColor("#E8ECF1"));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(Color.argb(220, 27, 33, 42));
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        b.setOnClickListener(l);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
