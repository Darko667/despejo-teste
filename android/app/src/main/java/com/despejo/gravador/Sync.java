package com.despejo.gravador;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Leva as transcrições para o repositório privado "despejos" no GitHub (pasta entrada/)
 * e traz de volta o que o Claude agendado organizou (pasta organizado/ e o mapa.md geral).
 * A chave do GitHub fica só nas preferências do app, no celular.
 */
final class Sync {

    static final String REPO = "despejos";
    private static final String API = "https://api.github.com";
    private static final String RAW = "application/vnd.github.raw+json";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("github", Context.MODE_PRIVATE);
    }

    static String login(Context c) {
        return prefs(c).getString("login", null);
    }

    static boolean connected(Context c) {
        return login(c) != null && prefs(c).getString("token", null) != null;
    }

    static String overallMap(Context c) {
        return prefs(c).getString("mapa", "");
    }

    static String overallMapJson(Context c) {
        return prefs(c).getString("mapaJson", "");
    }

    /** Confere a chave e se o repositório existe. Devolve null se deu certo, ou o problema em português. */
    static String connect(Context c, String token) {
        token = token.trim();
        try {
            Resp me = call("GET", API + "/user", token, null, null);
            if (me.code == 401) return "A chave não foi aceita pelo GitHub. Confira se copiou inteira.";
            if (me.code != 200) return "O GitHub respondeu " + me.code + ".";
            String login = new JSONObject(me.body).getString("login");
            Resp repo = call("GET", API + "/repos/" + login + "/" + REPO, token, null, null);
            if (repo.code == 404) return "Não achei o repositório \"" + REPO + "\" na sua conta, ou a chave não tem acesso a ele.";
            if (repo.code != 200) return "O GitHub respondeu " + repo.code + " ao abrir o repositório.";
            prefs(c).edit().putString("token", token).putString("login", login).commit();
            return null;
        } catch (Exception e) {
            return "Sem internet para falar com o GitHub agora.";
        }
    }

    static void disconnect(Context c) {
        prefs(c).edit().clear().commit();
    }

    /** Nome do arquivo de um despejo: despejo-2026-10-16-233012. */
    static String baseName(Store.Rec r) {
        return "despejo-" + new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(new Date(r.startedAt));
    }

    /** Envia o que falta e busca o que já foi organizado. Devolve quantos mapas novos chegaram. */
    static synchronized int run(Context c) {
        if (!connected(c)) return 0;
        String token = prefs(c).getString("token", null);
        String base = API + "/repos/" + login(c) + "/" + REPO + "/contents/";
        int arrived = 0;
        for (Store.Rec r : Store.all(c)) {
            try {
                if (Store.OK.equals(r.tx) && !r.sent) {
                    JSONObject body = new JSONObject()
                            .put("message", "Novo despejo " + baseName(r))
                            .put("content", Base64.encodeToString(markdown(r).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
                    Resp put = call("PUT", base + "entrada/" + baseName(r) + ".md", token, body.toString(), null);
                    // 422 = já existe lá (enviado antes e o app não ficou sabendo).
                    if (put.code == 201 || put.code == 200 || put.code == 422) {
                        Store.setSent(c, r.uri);
                        r.sent = true;
                    }
                }
                // Busca enquanto falta o texto ou o desenho (o desenho pode chegar numa rodada depois).
                if (r.sent && (r.map.isEmpty() || r.mapJson.isEmpty())) {
                    Resp get = call("GET", base + "organizado/" + baseName(r) + ".md", token, null, RAW);
                    if (get.code == 200 && !get.body.trim().isEmpty()) {
                        Resp js = call("GET", base + "organizado/" + baseName(r) + ".json", token, null, RAW);
                        String json = js.code == 200 ? js.body : "";
                        if (r.map.isEmpty()) arrived++;
                        if (r.map.isEmpty() || !json.isEmpty()) Store.setMap(c, r.uri, get.body, json);
                    }
                }
            } catch (Exception ignored) {
                // Sem internet: tenta de novo na próxima rodada.
            }
        }
        try {
            Resp mapa = call("GET", base + "mapa.md", token, null, RAW);
            if (mapa.code == 200) prefs(c).edit().putString("mapa", mapa.body).commit();
            Resp mj = call("GET", base + "mapa.json", token, null, RAW);
            if (mj.code == 200) prefs(c).edit().putString("mapaJson", mj.body).commit();
        } catch (Exception ignored) { }
        return arrived;
    }

    private static String markdown(Store.Rec r) {
        String when = new SimpleDateFormat("dd/MM/yyyy HH:mm", new Locale("pt", "BR")).format(new Date(r.startedAt));
        return "# Despejo de " + when + "\n\n"
                + "- Duração: " + MainActivity.fmt(Math.max(0, r.audioMs)) + "\n"
                + "- Origem: " + (r.shared ? "áudio compartilhado de outro app" : "gravado no Despejo") + "\n\n"
                + "## Transcrição\n\n" + r.text + "\n";
    }

    private static final class Resp {
        int code;
        String body;
    }

    private static Resp call(String method, String url, String token, String json, String accept) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod(method);
        h.setConnectTimeout(20_000);
        h.setReadTimeout(30_000);
        h.setRequestProperty("Authorization", "Bearer " + token);
        h.setRequestProperty("Accept", accept != null ? accept : "application/vnd.github+json");
        h.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        try {
            if (json != null) {
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = h.getOutputStream()) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
            }
            Resp r = new Resp();
            r.code = h.getResponseCode();
            InputStream in = r.code < 400 ? h.getInputStream() : h.getErrorStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (in != null) {
                try (InputStream i = in) {
                    byte[] b = new byte[16 * 1024];
                    int n;
                    while ((n = i.read(b)) > 0) bo.write(b, 0, n);
                }
            }
            r.body = bo.toString("UTF-8");
            return r;
        } finally {
            h.disconnect();
        }
    }

    private Sync() { }
}
