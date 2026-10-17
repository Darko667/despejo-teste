package com.despejo.gravador;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** De meia em meia hora (com internet), envia transcrições pendentes e busca os mapas que o Claude já organizou. */
public class SyncJob extends JobService {

    private static final int JOB_ID = 10;
    private static final String CHANNEL = "organizado";

    static void schedule(Context c) {
        try {
            JobScheduler js = c.getSystemService(JobScheduler.class);
            if (js.getPendingJob(JOB_ID) != null) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, SyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(30 * 60 * 1000L)
                .setPersisted(true)
                .build());
        } catch (Exception e) {
            // Sem o agendamento, a busca ainda acontece toda vez que o app abre.
            Store.lastError = "Não consegui agendar a busca automática dos mapas: " + e.getMessage();
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            notifyArrived(this, Sync.run(this));
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    static void notifyArrived(Context c, int n) {
        if (n <= 0) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Despejo organizado", NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        nm.notify(4, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
                .setContentTitle(n == 1 ? "Seu despejo foi organizado" : n + " despejos foram organizados")
                .setContentText("Toque para ver o mapa e as pendências.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
        } catch (Exception e) {
            // Sem o agendamento, a busca ainda acontece toda vez que o app abre.
            Store.lastError = "Não consegui agendar a busca automática dos mapas: " + e.getMessage();
        }
    }
}
