package com.despejo.gravador;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Abre qualquer áudio que o Android saiba tocar (m4a, mp3, ogg, wav…) e entrega
 * em pedaços de áudio mono a 16 kHz, que é o formato que o Whisper espera.
 */
final class AudioDecoder {

    static final int RATE = 16000;

    interface Sink {
        /** Recebe um pedaço; o array é reaproveitado depois que o método volta. */
        void accept(float[] pcm, int n) throws Exception;
    }

    static void decode(Context c, Uri uri, int chunkSamples, Sink sink) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(c, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new IllegalStateException("o arquivo não tem áudio");
            ex.selectTrack(track);

            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            Resampler rs = new Resampler(chunkSamples, sink);
            rs.setInput(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                    AudioFormat.ENCODING_PCM_16BIT);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            while (true) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int n = ex.readSampleData(buf, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10_000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    int enc = of.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ? of.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    rs.setInput(of.getInteger(MediaFormat.KEY_SAMPLE_RATE), of.getInteger(MediaFormat.KEY_CHANNEL_COUNT), enc);
                } else if (out >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(out);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset).limit(info.offset + info.size);
                        rs.feed(buf.slice().order(ByteOrder.nativeOrder()));
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            rs.flush();
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                codec.release();
            }
            ex.release();
        }
    }

    /** Junta os canais em mono, converte para 16 kHz e corta em pedaços. */
    private static final class Resampler {
        private final float[] chunk;
        private final Sink sink;
        private int filled;
        private int channels = 1;
        private int encoding = AudioFormat.ENCODING_PCM_16BIT;
        private double step = 1;     // amostras de entrada por amostra de saída
        private double next = 0;     // posição (na entrada) da próxima amostra de saída
        private long index = -1;     // índice da última amostra de entrada lida
        private float prev = 0;
        private double acc = 0;      // média das amostras desde a última saída (filtro simples)
        private int accN = 0;

        Resampler(int chunkSamples, Sink sink) {
            this.chunk = new float[chunkSamples];
            this.sink = sink;
        }

        void setInput(int rate, int channels, int encoding) {
            this.channels = Math.max(1, channels);
            this.encoding = encoding;
            this.step = rate / (double) RATE;
        }

        void feed(ByteBuffer b) throws Exception {
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                java.nio.FloatBuffer f = b.asFloatBuffer();
                while (f.remaining() >= channels) {
                    float s = 0;
                    for (int ch = 0; ch < channels; ch++) s += f.get();
                    push(s / channels);
                }
            } else {
                java.nio.ShortBuffer sb = b.asShortBuffer();
                while (sb.remaining() >= channels) {
                    float s = 0;
                    for (int ch = 0; ch < channels; ch++) s += sb.get();
                    push(s / channels / 32768f);
                }
            }
        }

        private void push(float x) throws Exception {
            index++;
            acc += x;
            accN++;
            while (next <= index) {
                float y;
                if (step > 1.5) {
                    // Diminuindo a taxa: usa a média do trecho para não embaralhar os agudos.
                    y = (float) (acc / accN);
                } else {
                    double frac = next - (index - 1);
                    y = (float) (prev + (x - prev) * frac);
                }
                emit(y);
                next += step;
                acc = 0;
                accN = 0;
            }
            prev = x;
        }

        private void emit(float y) throws Exception {
            chunk[filled++] = y;
            if (filled == chunk.length) {
                sink.accept(chunk, filled);
                filled = 0;
            }
        }

        void flush() throws Exception {
            if (filled > 0) sink.accept(chunk, filled);
            filled = 0;
        }
    }

    private AudioDecoder() { }
}
