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

    static final String FILA = "fila";
    static final String OK = "ok";
    static final String ERRO = "erro";

    static final class Rec {
        final String uri;
        final long startedAt;
        final long wallMs;
        final long audioMs;
        final boolean shared;   // veio pelo "Compartilhar" de outro app
        String tx = "";         // transcrição: "" (não pedida), FILA, OK ou ERRO
        String text = "";       // texto transcrito, ou o motivo do erro
        boolean sent;           // transcrição já foi para o repositório despejos
        String map = "";        // despejo organizado pelo Claude, quando já voltou
        String mapJson = "";    // o mesmo mapa em formato de árvore, para desenhar

        Rec(String uri, long startedAt, long wallMs, long audioMs) {
            this(uri, startedAt, wallMs, audioMs, false);
        }

        Rec(String uri, long startedAt, long wallMs, long audioMs, boolean shared) {
            this.uri = uri;
            this.startedAt = startedAt;
            this.wallMs = wallMs;
            this.audioMs = audioMs;
            this.shared = shared;
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
                Rec r = new Rec(o.getString("uri"), o.getLong("startedAt"), o.getLong("wallMs"), o.getLong("audioMs"), o.optBoolean("shared"));
                r.tx = o.optString("tx");
                r.text = o.optString("text");
                r.sent = o.optBoolean("sent");
                r.map = o.optString("map");
                r.mapJson = o.optString("mapJson");
                out.add(r);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static synchronized void add(Context c, Rec r) {
        List<Rec> list = all(c);
        list.add(0, r);
        save(c, list);
    }

    /** Muda o estado da transcrição de uma gravação. */
    static synchronized void setTranscript(Context c, String uri, String tx, String text) {
        List<Rec> list = all(c);
        for (Rec r : list) {
            if (r.uri.equals(uri)) { r.tx = tx; r.text = text; }
        }
        save(c, list);
    }

    static synchronized void setSent(Context c, String uri) {
        List<Rec> list = all(c);
        for (Rec r : list) if (r.uri.equals(uri)) r.sent = true;
        save(c, list);
    }

    static synchronized void setMap(Context c, String uri, String map, String json) {
        List<Rec> list = all(c);
        for (Rec r : list) if (r.uri.equals(uri)) { r.map = map; r.mapJson = json; }
        save(c, list);
    }

    /** A gravação mais antiga esperando transcrição, ou null. */
    static synchronized Rec nextInQueue(Context c) {
        List<Rec> list = all(c);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (FILA.equals(list.get(i).tx)) return list.get(i);
        }
        return null;
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
                        .put("audioMs", r.audioMs)
                        .put("shared", r.shared)
                        .put("tx", r.tx)
                        .put("text", r.text)
                        .put("sent", r.sent)
                        .put("map", r.map)
                        .put("mapJson", r.mapJson));
            }
        } catch (Exception ignored) { }
        prefs(c).edit().putString("lista", a.toString()).commit();
    }

    private Store() { }
}
