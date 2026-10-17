package com.despejo.gravador;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** O modelo do Whisper (~190 MB). É baixado uma vez e fica guardado no app. */
final class Model {

    static final String NAME = "ggml-small-q5_1.bin";
    private static final String URL_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    interface Progress {
        void update(long done, long total);
    }

    static File file(Context c) {
        return new File(c.getFilesDir(), NAME);
    }

    static boolean ready(Context c) {
        return file(c).isFile();
    }

    /** Baixa o modelo se ainda não tiver. Continua de onde parou se a internet cair no meio. */
    static File ensure(Context c, Progress p) throws IOException {
        File done = file(c);
        if (done.isFile()) return done;
        File part = new File(c.getFilesDir(), NAME + ".part");

        for (int attempt = 0; ; attempt++) {
            try {
                download(part, p);
                break;
            } catch (IOException e) {
                if (attempt >= 4) throw e;
                try { Thread.sleep(3000L << attempt); } catch (InterruptedException ie) { throw e; }
            }
        }
        if (!part.renameTo(done)) throw new IOException("não consegui salvar o modelo");
        return done;
    }

    private static void download(File part, Progress p) throws IOException {
        long have = part.isFile() ? part.length() : 0;
        HttpURLConnection http = (HttpURLConnection) new URL(URL_BASE + NAME).openConnection();
        http.setConnectTimeout(20_000);
        http.setReadTimeout(30_000);
        if (have > 0) http.setRequestProperty("Range", "bytes=" + have + "-");
        try {
            int code = http.getResponseCode();
            if (code == 416 && have >= 50_000_000) return;   // já estava tudo baixado
            boolean append = code == 206;
            if (code != 200 && code != 206) throw new IOException("servidor respondeu " + code);
            if (!append) have = 0;
            long len = http.getContentLengthLong();
            long total = len >= 0 ? have + len : -1;
            try (InputStream in = http.getInputStream(); FileOutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[256 * 1024];
                long last = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    have += n;
                    if (have - last > 1_000_000) { p.update(have, total); last = have; }
                }
            }
            if (total >= 0 && have != total) throw new IOException("download incompleto");
            if (have < 50_000_000) throw new IOException("arquivo do modelo pequeno demais");
        } finally {
            http.disconnect();
        }
    }

    private Model() { }
}
