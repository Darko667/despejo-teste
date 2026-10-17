package com.despejo.gravador;

import java.nio.charset.StandardCharsets;

/** Ponte para o whisper.cpp, que transcreve no próprio celular, sem internet. */
final class Whisper {

    static {
        System.loadLibrary("despejo");
    }

    /** Carrega o modelo. Devolve 0 se o arquivo não serviu. */
    static native long nInit(String modelPath);

    static native void nFree(long ctx);

    /** Transcreve áudio mono 16 kHz. Devolve o texto em UTF-8, ou null se falhou ou foi cancelado. */
    static native byte[] nTranscribe(long ctx, float[] pcm, int n, int threads, String language);

    /** Progresso da transcrição em andamento, 0 a 100. */
    static native int nProgress();

    static String transcribe(long ctx, float[] pcm, int n, int threads) {
        byte[] b = nTranscribe(ctx, pcm, n, threads, "pt");
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private Whisper() { }
}
