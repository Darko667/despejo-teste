package com.despejo.gravador;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Grava em um serviço em primeiro plano (tipo "microfone"), que o Android não pausa
 * quando a tela é bloqueada. O áudio vai para Gravações/Despejo no armazenamento do celular.
 */
public class RecorderService extends Service {

    static final String ACTION_START = "com.despejo.gravador.START";
    static final String ACTION_STOP = "com.despejo.gravador.STOP";

    private static final String CHANNEL = "gravando";
    private static final int NOTIF_ID = 1;

    /** Estado lido pela tela. Só existe uma gravação por vez. */
    static volatile boolean recording = false;
    static volatile long startedAt = 0;          // relógio de parede, para mostrar
    static volatile long startedElapsed = 0;     // relógio monotônico, para medir

    private MediaRecorder recorder;
    private ParcelFileDescriptor pfd;
    private Uri uri;
    private PowerManager.WakeLock wakeLock;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, RecorderService.class).setAction(ACTION_START));
    }

    static void stop(Context c) {
        c.startService(new Intent(c, RecorderService.class).setAction(ACTION_STOP));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            finishRecording();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && !recording) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            try {
                beginRecording();
            } catch (Exception e) {
                Store.lastError = "Não consegui começar a gravar: " + e.getMessage();
                cleanupAfterFailure();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void beginRecording() throws Exception {
        String name = "despejo-" + new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(new Date()) + ".m4a";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RECORDINGS + "/Despejo");
        v.put(MediaStore.Audio.Media.IS_PENDING, 1);
        ContentResolver cr = getContentResolver();
        uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (uri == null) throw new IllegalStateException("sem espaço para salvar");
        pfd = cr.openFileDescriptor(uri, "w");

        recorder = new MediaRecorder(this);
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioChannels(1);
        recorder.setAudioSamplingRate(44100);
        recorder.setAudioEncodingBitRate(64000);
        recorder.setOutputFile(pfd.getFileDescriptor());
        recorder.prepare();
        recorder.start();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "despejo:gravando");
        wakeLock.acquire(60 * 60 * 1000L);

        startedAt = System.currentTimeMillis();
        startedElapsed = SystemClock.elapsedRealtime();
        recording = true;
        Store.lastError = null;
    }

    private void finishRecording() {
        if (!recording) return;
        long wallMs = SystemClock.elapsedRealtime() - startedElapsed;
        recording = false;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            Store.lastError = "A gravação terminou com erro: " + e.getMessage();
        }
        recorder.release();
        recorder = null;
        closePfd();
        releaseWakeLock();

        ContentValues v = new ContentValues();
        v.put(MediaStore.Audio.Media.IS_PENDING, 0);
        getContentResolver().update(uri, v, null, null);

        long audioMs = readDuration(this, uri);
        Store.Rec r = new Store.Rec(uri.toString(), startedAt, wallMs, audioMs);
        r.tx = Store.FILA;
        Store.add(this, r);
        uri = null;
        TranscribeService.start(this);
    }

    private void cleanupAfterFailure() {
        recording = false;
        if (recorder != null) { recorder.release(); recorder = null; }
        closePfd();
        releaseWakeLock();
        if (uri != null) { getContentResolver().delete(uri, null, null); uri = null; }
    }

    static long readDuration(Context c, Uri u) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(c, u);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d != null ? Long.parseLong(d) : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
    }

    private void closePfd() {
        if (pfd != null) {
            try { pfd.close(); } catch (Exception ignored) { }
            pfd = null;
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Gravando", NotificationManager.IMPORTANCE_LOW));

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, RecorderService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Despejo está gravando")
                .setContentText("Pode bloquear a tela. Toque em Parar para salvar.")
                .setContentIntent(open)
                .setOngoing(true)
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis())
                .addAction(new Notification.Action.Builder(null, "Parar", stop).build())
                .build();
    }

    @Override
    public void onDestroy() {
        finishRecording();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
