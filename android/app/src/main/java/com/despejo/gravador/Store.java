package com.despejo.gravador;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Lista das gravações feitas, guardada nas preferências do app. */
final class Store {

    static volatile String lastError;

    static final class Rec {
        final String uri;
        final long startedAt;
        final long wallMs;
        final long audioMs;

        Rec(String uri, long startedAt, long wallMs, long audioMs) {
            this.uri = uri;
            this.startedAt = startedAt;
            this.wallMs = wallMs;
            this.audioMs = audioMs;
        }

        /** Passou se o arquivo tem praticamente a mesma duração do tempo que ficou gravando. */
        boolean passed() {
            return audioMs >= 0 && wallMs - audioMs <= 3000;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("gravacoes", Context.MODE_PRIVATE);
    }

    static synchronized List<Rec> all(Context c) {
        List<Rec> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("lista", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Rec(o.getString("uri"), o.getLong("startedAt"), o.getLong("wallMs"), o.getLong("audioMs")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    static synchronized void add(Context c, Rec r) {
        List<Rec> list = all(c);
        list.add(0, r);
        save(c, list);
    }

    static synchronized void remove(Context c, String uri) {
        List<Rec> list = all(c);
        list.removeIf(r -> r.uri.equals(uri));
        save(c, list);
    }

    private static void save(Context c, List<Rec> list) {
        JSONArray a = new JSONArray();
        try {
            for (Rec r : list) {
                a.put(new JSONObject()
                        .put("uri", r.uri)
                        .put("startedAt", r.startedAt)
                        .put("wallMs", r.wallMs)
                        .put("audioMs", r.audioMs));
            }
        } catch (Exception ignored) { }
        prefs(c).edit().putString("lista", a.toString()).apply();
    }

    private Store() { }
}
