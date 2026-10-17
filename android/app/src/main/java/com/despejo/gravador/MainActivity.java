package com.despejo.gravador;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int BG = Color.parseColor("#12161C");
    private static final int SURFACE = Color.parseColor("#1B212A");
    private static final int LINE = Color.parseColor("#2A323E");
    private static final int FG = Color.parseColor("#E8ECF1");
    private static final int MUTED = Color.parseColor("#8B95A3");
    private static final int REC = Color.parseColor("#FF4D4D");
    private static final int OK = Color.parseColor("#3ECF8E");

    private static final String ACTION_GRAVAR = "com.despejo.gravador.GRAVAR";
    private static final int REQ_PERMS = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private View button;
    private View dot;
    private TextView timer;
    private TextView state;
    private TextView error;
    private LinearLayout list;
    private LinearLayout github;
    private boolean wasRecording;
    private boolean startWhenAllowed;
    private boolean shortcutPending;
    private boolean wasTranscribing;
    private String shownCurrent;
    private TextView currentStatus;   // linha de progresso do card que está sendo transcrito

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean rec = RecorderService.recording;
            if (rec) timer.setText(fmt(SystemClock.elapsedRealtime() - RecorderService.startedElapsed));
            if (rec != wasRecording) {
                wasRecording = rec;
                renderButton();
                renderList();
            }
            boolean tr = TranscribeService.running;
            String cur = TranscribeService.currentUri;
            if (tr != wasTranscribing || !java.util.Objects.equals(cur, shownCurrent)) {
                wasTranscribing = tr;
                renderList();
            } else if (currentStatus != null && TranscribeService.status != null) {
                currentStatus.setText(TranscribeService.status);
            }
            String err = Store.lastError;
            error.setVisibility(err == null ? View.GONE : View.VISIBLE);
            error.setText(err);
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        installCrashCatcher();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        setContentView(buildUi());
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    /** O atalho "Gravar agora" (segurar o ícone) já começa a gravar. */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION_GRAVAR.equals(action)) shortcutPending = true;
        else if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) receiveShared(intent);
    }

    /** Áudio enviado de outro app (ex.: Gravador da Samsung) pelo menu Compartilhar. */
    private void receiveShared(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else {
            ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) uris.addAll(many);
        }
        setIntent(new Intent(this, MainActivity.class));   // não importar de novo ao girar a tela
        if (uris.isEmpty()) {
            Store.lastError = "O compartilhar abriu o Despejo, mas não veio nenhum áudio.";
            return;
        }
        state.setText("Recebendo áudio…");
        new Thread(() -> {
            int n = Importer.importAll(getApplicationContext(), uris);
            if (n > 0) TranscribeService.start(getApplicationContext());
            runOnUiThread(() -> {
                state.setText(n == 1 ? "Áudio recebido." : n + " áudios recebidos.");
                renderList();
            });
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        wasRecording = RecorderService.recording;
        renderButton();
        renderList();
        handler.post(tick);
        renderGithub();
        SyncJob.schedule(this);
        refreshFromGithub();
        // Retoma a fila (ex.: o celular desligou no meio) e já deixa o modelo baixado.
        if (!TranscribeService.running && (Store.nextInQueue(this) != null || !Model.ready(this))) {
            TranscribeService.start(this, true);
        }
        // Começa só com a tela já visível: o Android exige isso para usar o microfone.
        if (shortcutPending) {
            shortcutPending = false;
            if (!RecorderService.recording) handler.post(this::toggle);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private void toggle() {
        if (RecorderService.recording) {
            RecorderService.stop(this);
            state.setText("Salvando…");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startWhenAllowed = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS);
            return;
        }
        Store.lastError = null;
        RecorderService.start(this);
        state.setText("Começando…");
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_PERMS) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (startWhenAllowed) toggle();
        } else {
            Store.lastError = "Sem permissão do microfone. Libere em Configurações > Apps > Despejo > Permissões.";
        }
        startWhenAllowed = false;
    }

    // ---------- Tela ----------

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFitsSystemWindows(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(40));
        scroll.addView(root);

        TextView title = text("Despejo", 22, FG, true);
        root.addView(title);
        TextView hint = text("Toque, fale e pode bloquear a tela. Quando parar, o texto aparece aqui sozinho.", 15, MUTED, false);
        hint.setPadding(0, dp(4), 0, dp(24));
        root.addView(hint);

        LinearLayout stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(stage, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout btn = new LinearLayout(this);
        btn.setGravity(Gravity.CENTER);
        btn.setContentDescription("Gravar");
        btn.setOnClickListener(v -> toggle());
        button = btn;
        dot = new View(this);
        btn.addView(dot);
        stage.addView(btn, new LinearLayout.LayoutParams(dp(190), dp(190)));

        timer = text("00:00", 40, FG, true);
        timer.setPadding(0, dp(16), 0, 0);
        timer.setFontFeatureSettings("tnum");
        stage.addView(timer);
        state = text("Toque para gravar", 15, MUTED, false);
        state.setPadding(0, dp(4), 0, dp(12));
        stage.addView(state);

        error = text("", 14, FG, false);
        error.setPadding(dp(12), dp(12), dp(12), dp(12));
        error.setBackground(box(Color.parseColor("#3A1D1D"), REC, 10));
        error.setVisibility(View.GONE);
        root.addView(error);

        TextView h = text("GRAVAÇÕES", 13, MUTED, true);
        h.setLetterSpacing(0.06f);
        h.setPadding(0, dp(24), 0, dp(10));
        root.addView(h);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        TextView g = text("ORGANIZAÇÃO AUTOMÁTICA", 13, MUTED, true);
        g.setLetterSpacing(0.06f);
        g.setPadding(0, dp(24), 0, dp(10));
        root.addView(g);
        github = new LinearLayout(this);
        github.setOrientation(LinearLayout.VERTICAL);
        root.addView(github);
        return scroll;
    }

    private void renderButton() {
        boolean rec = RecorderService.recording;
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(SURFACE);
        ring.setStroke(dp(6), rec ? REC : LINE);
        button.setBackground(ring);

        GradientDrawable d = new GradientDrawable();
        d.setColor(REC);
        int size;
        if (rec) {
            d.setShape(GradientDrawable.RECTANGLE);
            d.setCornerRadius(dp(10));
            size = dp(50);
        } else {
            d.setShape(GradientDrawable.OVAL);
            size = dp(70);
        }
        dot.setBackground(d);
        dot.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        button.setContentDescription(rec ? "Parar gravação" : "Gravar");
        if (!rec) timer.setText("00:00");
        state.setText(rec ? "Gravando. Pode bloquear a tela." : "Toque para gravar");
    }

    private void renderList() {
        list.removeAllViews();
        currentStatus = null;
        shownCurrent = TranscribeService.currentUri;
        List<Store.Rec> recs = Store.all(this);
        if (recs.isEmpty()) {
            list.addView(text("Nenhuma gravação ainda. Elas ficam salvas em Gravações/Despejo.", 14, MUTED, false));
            return;
        }
        String overall = Sync.overallMap(this);
        if (!overall.isEmpty()) {
            Button all = action("🧠  Ver mapa geral", OK,
                    v -> MapActivity.open(this, "Mapa geral", Sync.overallMapJson(this), overall));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
            lp.bottomMargin = dp(12);
            list.addView(all, lp);
        }
        SimpleDateFormat when = new SimpleDateFormat("dd/MM, HH:mm", new Locale("pt", "BR"));
        for (Store.Rec r : recs) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(14), dp(14), dp(14));
            card.setBackground(box(SURFACE, LINE, 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(12);

            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = text(when.format(new Date(r.startedAt)), 16, FG, true);
            top.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            boolean ok = r.shared || r.passed();
            TextView pill = text(r.shared ? "Recebido" : ok ? "Passou" : "Cortou", 13, ok ? OK : REC, true);
            pill.setPadding(dp(10), dp(2), dp(10), dp(2));
            pill.setBackground(box(ok ? Color.argb(50, 62, 207, 142) : Color.argb(50, 255, 77, 77), Color.TRANSPARENT, 99));
            top.addView(pill);
            card.addView(top);

            long lost = Math.max(0, r.wallMs - Math.max(0, r.audioMs));
            String info = r.shared
                    ? "Veio de outro app · duração " + (r.audioMs >= 0 ? fmt(r.audioMs) : "?")
                    : "Tempo total " + fmt(r.wallMs) + " · áudio salvo "
                      + (r.audioMs >= 0 ? fmt(r.audioMs) : "?") + " · perdido " + fmt(lost);
            TextView facts = text(info, 14, MUTED, false);
            facts.setPadding(0, dp(6), 0, dp(10));
            card.addView(facts);
            addTranscript(card, r);

            LinearLayout actions = new LinearLayout(this);
            Uri uri = Uri.parse(r.uri);
            actions.addView(action("Ouvir", FG, v -> open(uri)), weight());
            actions.addView(action("Enviar", FG, v -> share(uri)), weight());
            if (Store.OK.equals(r.tx)) actions.addView(action("Copiar", OK, v -> copy(r.text)), weight());
            Button del = action("Apagar", REC, null);
            del.setOnClickListener(v -> {
                if ("1".equals(del.getTag())) {
                    try { getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
                    Store.remove(this, r.uri);
                    renderList();
                } else {
                    del.setTag("1");
                    del.setText("Toque de novo");
                }
            });
            actions.addView(del, weight());
            card.addView(actions);
            list.addView(card, lp);
        }
    }

    /** Mostra o texto transcrito, ou em que pé está a transcrição. */
    private void addTranscript(LinearLayout card, Store.Rec r) {
        if (Store.OK.equals(r.tx)) {
            TextView body = text(r.text, 15, FG, false);
            body.setLineSpacing(0, 1.15f);
            body.setMaxLines(6);
            body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            body.setPadding(dp(12), dp(10), dp(12), dp(10));
            body.setBackground(box(BG, LINE, 8));
            body.setOnClickListener(v -> body.setMaxLines(body.getMaxLines() == 6 ? Integer.MAX_VALUE : 6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(10);
            card.addView(body, lp);
            if (!r.map.isEmpty()) {
                Button m = action("🧠  Ver mapa", OK, v -> MapActivity.open(this, "Despejo", r.mapJson, r.map));
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(44));
                bl.setMargins(dp(4), 0, dp(4), dp(10));
                card.addView(m, bl);
            } else if (Sync.connected(this)) {
                TextView t = text(r.sent ? "Enviado. O Claude organiza na próxima rodada e o mapa aparece aqui."
                        : "Esperando internet para enviar para organização.", 14, MUTED, false);
                t.setPadding(0, 0, 0, dp(10));
                card.addView(t);
            }
            return;
        }
        String msg;
        int color = MUTED;
        boolean button = false;
        if (Store.FILA.equals(r.tx)) {
            boolean now = r.uri.equals(TranscribeService.currentUri);
            msg = now && TranscribeService.status != null ? TranscribeService.status
                    : now ? "Transcrevendo…" : "Na fila para transcrever";
        } else if (Store.ERRO.equals(r.tx)) {
            msg = r.text;
            color = REC;
            button = true;
        } else {
            msg = "Ainda não transcrito";
            button = true;
        }
        TextView t = text(msg, 14, color, false);
        t.setPadding(0, 0, 0, dp(10));
        card.addView(t);
        if (Store.FILA.equals(r.tx) && r.uri.equals(TranscribeService.currentUri)) currentStatus = t;
        if (button) {
            Button go = action(Store.ERRO.equals(r.tx) ? "Tentar de novo" : "Transcrever", FG, v -> {
                Store.setTranscript(this, r.uri, Store.FILA, "");
                TranscribeService.start(this);
                renderList();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44));
            lp.setMargins(dp(4), 0, dp(4), dp(10));
            card.addView(go, lp);
        }
    }

    /** Envia transcrições pendentes e busca mapas novos, sem travar a tela. */
    private void refreshFromGithub() {
        if (!Sync.connected(this)) return;
        new Thread(() -> {
            int n = Sync.run(getApplicationContext());
            runOnUiThread(() -> {
                if (n > 0) state.setText(n == 1 ? "Chegou um despejo organizado." : n + " despejos organizados chegaram.");
                renderList();
            });
        }).start();
    }

    /** Onde o Gustavo cola a chave do GitHub uma única vez. */
    private void renderGithub() {
        github.removeAllViews();
        if (Sync.connected(this)) {
            TextView t = text("Conectado ao GitHub como " + Sync.login(this) + ". Cada transcrição vai para o repositório \""
                    + Sync.REPO + "\", o Claude organiza e o mapa volta para cá sozinho.", 14, MUTED, false);
            t.setPadding(0, 0, 0, dp(10));
            github.addView(t);
            Button off = action("Desconectar", REC, null);
            off.setOnClickListener(v -> {
                if ("1".equals(off.getTag())) { Sync.disconnect(this); renderGithub(); renderList(); }
                else { off.setTag("1"); off.setText("Toque de novo para desconectar"); }
            });
            github.addView(off, new LinearLayout.LayoutParams(-1, dp(44)));
            return;
        }
        TextView t = text("Cole aqui a chave do GitHub (token) que você criou para o repositório \"" + Sync.REPO
                + "\". Ela fica guardada só neste celular.", 14, MUTED, false);
        t.setPadding(0, 0, 0, dp(10));
        github.addView(t);
        EditText in = new EditText(this);
        in.setHint("github_pat_…");
        in.setHintTextColor(MUTED);
        in.setTextColor(FG);
        in.setSingleLine(true);
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        in.setPadding(dp(12), dp(10), dp(12), dp(10));
        in.setBackground(box(SURFACE, LINE, 8));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
        ilp.bottomMargin = dp(10);
        github.addView(in, ilp);
        Button go = action("Conectar", OK, null);
        go.setOnClickListener(v -> {
            String token = in.getText().toString();
            if (token.trim().isEmpty()) return;
            go.setEnabled(false);
            go.setText("Conferindo…");
            new Thread(() -> {
                String problem = Sync.connect(getApplicationContext(), token);
                runOnUiThread(() -> {
                    if (problem != null) {
                        Store.lastError = problem;
                        go.setEnabled(true);
                        go.setText("Conectar");
                    } else {
                        Store.lastError = null;
                        state.setText("GitHub conectado.");
                        renderGithub();
                        refreshFromGithub();
                    }
                });
            }).start();
        });
        github.addView(go, new LinearLayout.LayoutParams(-1, dp(44)));
    }

    private void copy(String s) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Despejo", s));
        state.setText("Texto copiado.");
    }

    private void open(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) {
            Store.lastError = "Nenhum app para tocar o áudio. Ele está em Gravações/Despejo.";
        }
    }

    private void share(Uri uri) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("audio/mp4")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Enviar gravação"));
    }

    /** Se o app fechar sozinho, guarda o motivo e mostra na próxima vez que abrir. */
    private void installCrashCatcher() {
        android.content.SharedPreferences p = getSharedPreferences("falha", MODE_PRIVATE);
        String last = p.getString("ultima", null);
        if (last != null) {
            Store.lastError = "O app fechou sozinho da última vez. Me mande um print disto:\n" + last;
            p.edit().remove("ultima").commit();
        }
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            String trace = sw.toString();
            p.edit().putString("ultima", "v" + BuildConfig.VERSION_NAME + " " + trace.substring(0, Math.min(1500, trace.length()))).commit();
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    // ---------- Utilidades ----------

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button action(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setBackground(box(Color.TRANSPARENT, LINE, 8));
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != Color.TRANSPARENT) g.setStroke(dp(1), stroke);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    static String fmt(long ms) {
        long s = Math.max(0, Math.round(ms / 1000.0));
        long h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, ss)
                     : String.format(Locale.US, "%02d:%02d", m, ss);
    }
}
