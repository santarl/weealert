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
        Synth.Note note;           // pattern mode only: the prepared source note
        Codebook.Pattern[] pats;   // pattern mode only
        String[] names;
        String[] details;          // one line per sound for the lists
        int rate;
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
        x.rate = note.rate;
        x.prm = pr;
        x.pats = Codebook.pick(pr, srcMs, n);
        x.names = Names.forPatterns(x.pats);
        x.pcm = new short[x.pats.length][];
        x.details = new String[x.pats.length];
        int lo = Integer.MAX_VALUE, hi = 0;
        for (int i = 0; i < x.pats.length; i++) {
            x.details[i] = x.pats[i].describe();
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

    /** Contact voices: every contact name is spoken as a fast mumble in that contact's own voice. */
    static Result buildVoices(java.util.List<Contacts.C> list, Params pr) {
        int n = list.size();
        Result x = new Result();
        x.prm = pr;
        x.rate = pr.rate > 0 ? pr.rate : Voice.RATE;
        x.names = Contacts.labels(list);
        x.details = new String[n];
        x.pcm = new short[n][];
        int lo = Integer.MAX_VALUE, hi = 0;
        for (int i = 0; i < n; i++) {
            Contacts.C c = list.get(i);
            float[] w = Voice.render(Say.syllables(c.text(), pr.vFirst, pr.vMaxSyl), c.pitch, c.fsc, c.spd, c.con,
                    pr, Contacts.seed(c.text()));
            if (pr.rate > 0 && pr.rate != Voice.RATE && w.length > 1) w = Synth.resample(w, Voice.RATE, pr.rate);
            int maxS = (int) ((long) pr.maxMs * x.rate / 1000);
            if (w.length > maxS) {
                w = java.util.Arrays.copyOf(w, maxS);
                int fo = Math.min(x.rate / 40, maxS / 2);
                for (int j = 0; j < fo; j++) w[maxS - 1 - j] *= j / (float) fo;
            }
            Synth.normalize(w, (float) Math.pow(10, pr.levelDb / 20.0));
            if (w.length < 64) w = new float[64];                 // nothing to say: a short silence, not a crash
            short[] pcm = new short[w.length];
            for (int j = 0; j < pcm.length; j++) pcm[j] = (short) Math.round(w[j] * 32767f);
            x.pcm[i] = pcm;
            x.details[i] = c.describe();
            lo = Math.min(lo, pcm.length);
            hi = Math.max(hi, pcm.length);
        }
        StringBuilder s = new StringBuilder();
        s.append(n).append(n == 1 ? " contact" : " contacts");
        if (n == 0) s.append(": add one with the buttons above");
        else {
            s.append(String.format(Locale.US, ", %.2f-%.2f s", lo / (double) x.rate, hi / (double) x.rate));
            if (n > 1) s.append(String.format(Locale.US, ", closest voices %.0f (higher is easier)", Contacts.minDistance(list)));
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
        String mode = sp.getString("mode", "patterns");
        String key = mode + "|" + pr.serialize() + "|" + n + "|" + src + "|" + builtin;
        if ("voices".equals(mode)) {
            String raw = sp.getString("contacts", "");
            key = "voices|" + pr.serialize() + "|" + raw;
            if (key.equals(lastKey) && last != null) return last;
            Result v = buildVoices(Contacts.parse(raw), pr);
            v.key = Integer.toHexString(key.hashCode());
            lastKey = key;
            last = v;
            return v;
        }
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
            if (!Synth.hasBuiltin(builtin)) builtin = 0;
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
