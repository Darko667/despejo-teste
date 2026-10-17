package com.despejo.gravador;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Copia áudios recebidos pelo "Compartilhar" para Gravações/Despejo e registra na lista. */
final class Importer {

    /** Devolve quantos áudios foram importados. */
    static int importAll(Context c, List<Uri> uris) {
        int ok = 0;
        for (Uri src : uris) {
            try {
                importOne(c, src);
                ok++;
            } catch (Exception e) {
                Store.lastError = "Não consegui receber um dos áudios: " + e.getMessage();
            }
        }
        return ok;
    }

    private static void importOne(Context c, Uri src) throws Exception {
        ContentResolver cr = c.getContentResolver();
        String type = cr.getType(src);
        if (type == null || !type.startsWith("audio/")) type = "audio/mp4";
        String name = displayName(cr, src);
        if (name == null) {
            name = "despejo-recebido-" + new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(new Date());
        }

        ContentValues v = new ContentValues();
        v.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Audio.Media.MIME_TYPE, type);
        v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RECORDINGS + "/Despejo");
        v.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri dst = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (dst == null) throw new IllegalStateException("sem espaço para salvar");

        try (InputStream in = cr.openInputStream(src); OutputStream out = cr.openOutputStream(dst)) {
            if (in == null || out == null) throw new IllegalStateException("arquivo indisponível");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            cr.delete(dst, null, null);
            throw e;
        }

        ContentValues done = new ContentValues();
        done.put(MediaStore.Audio.Media.IS_PENDING, 0);
        cr.update(dst, done, null, null);

        long ms = RecorderService.readDuration(c, dst);
        Store.add(c, new Store.Rec(dst.toString(), System.currentTimeMillis(), Math.max(ms, 0), ms, true));
    }

    private static String displayName(ContentResolver cr, Uri u) {
        try (Cursor cur = cr.query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) { }
        return null;
    }

    private Importer() { }
}
