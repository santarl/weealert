package io.rfsj.weealert;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Decodes the first few seconds of any audio file Android can play into mono float PCM. */
final class Decode {
    static final int MAX_SEC = 30;

    static final class Pcm {
        float[] data;
        int rate;
    }

    static Pcm read(Context c, Uri uri) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(c, uri, null);
            int track = -1;
            MediaFormat fmt = null;
            String mime = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) { track = i; fmt = f; mime = m; break; }
            }
            if (track < 0) throw new IOException("no audio track");
            ex.selectTrack(track);
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int enc = fmt.containsKey(MediaFormat.KEY_PCM_ENCODING) ? fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) : 2;
            float[] out = new float[rate * MAX_SEC];
            int n = 0;
            long deadline = SystemClock.uptimeMillis() + 15000;

            if ("audio/raw".equals(mime)) {
                ByteBuffer buf = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
                while (n < out.length) {
                    int sz = ex.readSampleData(buf, 0);
                    if (sz < 0) break;
                    buf.position(0);
                    buf.limit(sz);
                    n = append(buf, enc, ch, out, n);
                    ex.advance();
                }
            } else {
                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(fmt, null, null, 0);
                codec.start();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inDone = false, outDone = false;
                while (!outDone && n < out.length) {
                    if (SystemClock.uptimeMillis() > deadline) throw new IOException("decode timeout");
                    if (!inDone) {
                        int ii = codec.dequeueInputBuffer(10000);
                        if (ii >= 0) {
                            int sz = ex.readSampleData(codec.getInputBuffer(ii), 0);
                            if (sz < 0) {
                                codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inDone = true;
                            } else {
                                codec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0);
                                ex.advance();
                            }
                        }
                    }
                    int oi = codec.dequeueOutputBuffer(info, 10000);
                    if (oi >= 0) {
                        if (info.size > 0) {
                            ByteBuffer ob = codec.getOutputBuffer(oi);
                            ob.order(ByteOrder.LITTLE_ENDIAN);
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            n = append(ob, enc, ch, out, n);
                        }
                        codec.releaseOutputBuffer(oi, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = codec.getOutputFormat();
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        enc = of.containsKey(MediaFormat.KEY_PCM_ENCODING) ? of.getInteger(MediaFormat.KEY_PCM_ENCODING) : 2;
                    }
                }
            }
            if (n == 0) throw new IOException("empty audio");
            Pcm p = new Pcm();
            p.data = java.util.Arrays.copyOf(out, n);
            p.rate = rate;
            return p;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) { }
                codec.release();
            }
            ex.release();
        }
    }

    /** Appends interleaved PCM (16-bit or float) as mono; returns the new frame count. */
    private static int append(ByteBuffer b, int enc, int ch, float[] out, int n) throws IOException {
        if (ch < 1) ch = 1;
        if (enc == 4) {
            java.nio.FloatBuffer fb = b.asFloatBuffer();
            while (fb.remaining() >= ch && n < out.length) {
                float s = 0;
                for (int i = 0; i < ch; i++) s += fb.get();
                out[n++] = s / ch;
            }
        } else if (enc == 2) {
            java.nio.ShortBuffer sb = b.asShortBuffer();
            while (sb.remaining() >= ch && n < out.length) {
                float s = 0;
                for (int i = 0; i < ch; i++) s += sb.get();
                out[n++] = s / ch / 32768f;
            }
        } else {
            throw new IOException("unsupported audio format, try a 16-bit WAV, MP3, OGG or M4A");
        }
        return n;
    }
}
