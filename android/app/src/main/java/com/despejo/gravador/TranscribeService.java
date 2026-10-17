package com.despejo.gravador;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Transcreve, uma por uma, as gravações que estão na fila. Roda em primeiro plano,
 * então continua com a tela bloqueada. Não precisa de internet, só para baixar o modelo na primeira vez.
 */
public class TranscribeService extends Service {

    private static final String CHANNEL = "transcrevendo";
    private static final String CHANNEL_DONE = "transcrito";
    private static final int NOTIF_ID = 2;
    private static final int NOTIF_DONE_ID = 3;

    /** Pedaços de 10 minutos, para não ocupar memória demais em gravações longas. */
    private static final int CHUNK = AudioDecoder.RATE * 60 * 10;

    /** Estado lido pela tela. */
    static volatile boolean running = false;
    static volatile String currentUri = null;
    static volatile String status = null;      // texto curto: "Baixando o modelo… 40%", "Transcrevendo… 12%"

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private Thread worker;
    private boolean again;     // chegou pedido novo enquanto a fila rodava
    private volatile boolean prepare;
    private PowerManager.WakeLock wakeLock;
    private NotificationManager nm;

    static void start(Context c) {
        start(c, false);
    }

    /** Com prepare=true, baixa o modelo mesmo sem nada na fila, para a primeira transcrição já sair rápida. */
    static void start(Context c, boolean prepare) {
        try {
            c.startForegroundService(new Intent(c, TranscribeService.class).putExtra("preparar", prepare));
        } catch (Exception ignored) {
            // O Android pode recusar se o app estiver em segundo plano; a fila continua quando o app abrir.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Transcrevendo", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, "Transcrição pronta", NotificationManager.IMPORTANCE_DEFAULT));
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "despejo:transcrevendo");
        wakeLock.acquire(6 * 60 * 60 * 1000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, progressNotification("Preparando a transcrição…", -1),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (intent != null && intent.getBooleanExtra("preparar", false)) prepare = true;
        if (worker == null) startWorker();
        else again = true;
        return START_NOT_STICKY;
    }

    private void startWorker() {
        running = true;
        again = false;
        worker = new Thread(this::work, "transcricao");
        worker.start();
    }

    /** Roda na thread principal, junto com onStartCommand, então não perde pedido no meio do caminho. */
    private void afterWork() {
        worker = null;
        if (again && Store.nextInQueue(this) != null) {
            startWorker();
            return;
        }
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void work() {
        long ctx = 0;
        int done = 0;
        String lastText = null;
        try {
            if (prepare && !Model.ready(this)) {
                prepare = false;
                download();
            }
            Store.Rec r;
            while ((r = Store.nextInQueue(this)) != null) {
                if (ctx == 0) ctx = loadModel();
                if (ctx == 0) break;   // sem modelo: as gravações continuam na fila
                currentUri = r.uri;
                try {
                    String text = transcribeOne(ctx, r);
                    Store.setTranscript(this, r.uri, Store.OK, text);
                    lastText = text;
                    done++;
                } catch (Throwable e) {
                    Store.setTranscript(this, r.uri, Store.ERRO, "Não consegui transcrever: " + e.getMessage());
                }
                currentUri = null;
            }
        } finally {
            if (ctx != 0) Whisper.nFree(ctx);
            currentUri = null;
            status = null;
            if (done > 0) notifyDone(done, lastText);
            main.post(this::afterWork);
        }
    }

    /** Baixa o modelo se precisar. Devolve null se não deu (sem internet). */
    private java.io.File download() {
        try {
            status = "Baixando o modelo de transcrição (só na primeira vez)…";
            showProgress(status, -1);
            java.io.File f = Model.ensure(this, (have, total) -> {
                int pct = total > 0 ? (int) (have * 100 / total) : -1;
                status = "Baixando o modelo de transcrição (só na primeira vez)… "
                        + (pct >= 0 ? pct + "%" : (have / 1_000_000) + " MB");
                showProgress(status, pct);
            });
            status = null;
            return f;
        } catch (Exception e) {
            status = null;
            Store.lastError = "Sem internet para baixar o modelo de transcrição (só precisa na primeira vez). "
                    + "Suas gravações ficam na fila e são transcritas quando a internet voltar e você abrir o app.";
            return null;
        }
    }

    private long loadModel() {
        java.io.File f = download();
        if (f == null) return 0;
        try {
            status = "Carregando o modelo…";
            showProgress(status, -1);
            long ctx = Whisper.nInit(f.getAbsolutePath());
            if (ctx == 0) {
                f.delete();   // arquivo corrompido: baixa de novo na próxima
                Store.lastError = "O modelo de transcrição veio com defeito. Vou baixar de novo na próxima vez.";
            }
            return ctx;
        } catch (Throwable e) {
            Store.lastError = "Não consegui abrir o modelo de transcrição: " + e.getMessage();
            return 0;
        }
    }

    private String transcribeOne(long ctx, Store.Rec r) throws Exception {
        long totalSamples = r.audioMs > 0 ? r.audioMs * AudioDecoder.RATE / 1000 : -1;
        int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));
        StringBuilder sb = new StringBuilder();
        long[] before = {0};

        Thread ticker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                int pct = -1;
                if (totalSamples > 0) {
                    pct = (int) Math.min(99, (before[0] + (long) chunkLen[0] * Whisper.nProgress() / 100) * 100 / totalSamples);
                }
                status = "Transcrevendo…" + (pct >= 0 ? " " + pct + "%" : "");
                showProgress(status, pct);
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        });
        ticker.start();
        try {
            AudioDecoder.decode(this, Uri.parse(r.uri), CHUNK, (pcm, n) -> {
                // Um restinho de menos de 1 segundo no fim só faz o Whisper inventar palavras.
                if (n < AudioDecoder.RATE && before[0] > 0) return;
                chunkLen[0] = n;
                String part = Whisper.transcribe(ctx, pcm, n, threads);
                if (part == null) throw new IllegalStateException("o Whisper falhou");
                if (sb.length() > 0 && !part.isEmpty()) sb.append(' ');
                sb.append(part);
                before[0] += n;
            });
        } finally {
            ticker.interrupt();
        }
        String text = sb.toString().trim();
        return text.isEmpty() ? "(nenhuma fala reconhecida)" : text;
    }

    private final int[] chunkLen = {0};

    private void showProgress(String text, int pct) {
        nm.notify(NOTIF_ID, progressNotification(text, pct));
    }

    private Notification progressNotification(String text, int pct) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle("Despejo")
                .setContentText(text)
                .setContentIntent(openApp())
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        b.setProgress(100, Math.max(0, pct), pct < 0);
        return b.build();
    }

    private void notifyDone(int n, String lastText) {
        String title = n == 1 ? "Despejo transcrito" : n + " despejos transcritos";
        String body = lastText == null ? "" : lastText.length() > 140 ? lastText.substring(0, 140) + "…" : lastText;
        nm.notify(NOTIF_DONE_ID, new Notification.Builder(this, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build());
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
