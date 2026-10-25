package io.rfsj.weealert;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.IOException;
import java.util.Locale;

/** Builds one complete set of sounds. Used by the app screen and by the file-picker provider. */
final class Gen {
    static final int MIN_N = 2, MAX_N = 40;

    static final class Result {
        Synth.Note note;
        Codebook.Pattern[] pats;
        String[] names;
        short[][] pcm;
        Params prm;
        String info;
        String key = "";
    }

    /** Returns null when the source holds no usable sound. */
    static Result build(float[] raw, int rawRate, Params pr, int n) {
        Synth.Note note = Synth.prepare(raw, rawRate, pr);
        if (note.data.length < 64) return null;
        int srcMs = (int) (1000L * note.data.length / note.rate);
        Result x = new Result();
        x.note = note;
        x.prm = pr;
        x.pats = Codebook.pick(pr, srcMs, n);
        x.names = Names.forPatterns(x.pats);
        x.pcm = new short[x.pats.length][];
        int lo = Integer.MAX_VALUE, hi = 0;
        for (int i = 0; i < x.pats.length; i++) {
            x.pcm[i] = Synth.render(note, x.pats[i], pr);
            lo = Math.min(lo, x.pcm[i].length);
            hi = Math.max(hi, x.pcm[i].length);
        }
        StringBuilder s = new StringBuilder();
        s.append(x.pats.length).append(" sounds");
        if (x.pats.length < n) s.append(" (only that many fit these settings)");
        if (x.pats.length > 0) {
            s.append(String.format(Locale.US, ", %.2f-%.2f s", lo / (double) note.rate, hi / (double) note.rate));
            if (x.pats.length > 1)
                s.append(", closest pair ").append(Codebook.minDistance(x.pats, pr.rhythmW)).append(" (higher is easier)");
        }
        x.info = s.toString();
        return x;
    }

    /** File name of sound i, the same for export, system tones and the file picker. */
    static String fileName(Params p, int i, String name) {
        return (p.numbered ? String.format(Locale.US, "%02d-", i + 1) : "") + p.prefix + name + ".wav";
    }

    // ---------------------------------------------------------------- from saved settings

    private static String lastKey;
    private static Result last;

    /** Rebuilds the set from the saved settings, for callers that run without the app screen. Cached. */
    static synchronized Result fromPrefs(Context c) throws IOException {
        SharedPreferences sp = c.getSharedPreferences("weealert", Context.MODE_PRIVATE);
        Params pr = Params.parse(sp.getString("params", ""));
        int n = Math.max(MIN_N, Math.min(MAX_N, sp.getInt("n", 8)));
        String src = sp.getString("src", null);
        int builtin = sp.getInt("builtin", 0);
        String key = pr.serialize() + "|" + n + "|" + src + "|" + builtin;
        if (key.equals(lastKey) && last != null) return last;
        float[] raw = null;
        int rate = Synth.BUILTIN_RATE;
        if (src != null) {
            try {
                Decode.Pcm p = Decode.read(c, Uri.parse(src));
                raw = p.data;
                rate = p.rate;
            } catch (IOException | RuntimeException ignored) { }
        }
        if (raw == null) {
            if (builtin < 0 || builtin >= Synth.BUILTIN.length) builtin = 0;
            raw = Synth.builtin(builtin);
            rate = Synth.BUILTIN_RATE;
        }
        Result x = build(raw, rate, pr, n);
        if (x == null) throw new IOException("the source sound is empty");
        x.key = Integer.toHexString(key.hashCode());
        lastKey = key;
        last = x;
        return x;
    }
}
