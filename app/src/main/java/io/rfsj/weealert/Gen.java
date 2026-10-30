package io.rfsj.weealert;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds one complete set of sounds. Used by the app screen and by the file-picker provider. */
final class Gen {
    static final class Result {
        Synth.Note note;           // pattern modes only: the prepared source note
        Codebook.Pattern[] pats;   // pattern modes only: the original patterns (they give the names)
        String[] names;
        String[] details;          // one line per sound for the lists
        short[][] pcm;
        int rate;
        Params prm;
        String info;
        String key = "";
        float[] raw;               // the chosen source sound (null if it could not be read)
        int rawRate;
    }

    static boolean isContacts(String mode) {
        return "voices".equals(mode) || "blips".equals(mode) || "cpat".equals(mode) || "pblips".equals(mode);
    }

    private static String num(double v) { return String.format(Locale.US, "%.1f", v).replace(".0", ""); }

    // ---------------------------------------------------------------- tone patterns (anonymous)

    /** Returns null when the source holds no usable sound. ov holds per-sound tuning by pattern key (may be null). */
    static Result build(float[] raw, int rawRate, Params pr, int n, Map<String, String> ov) {
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
        int lo = Integer.MAX_VALUE, hi = 0, tuned = 0;
        for (int i = 0; i < x.pats.length; i++) {
            String kv = ov == null ? null : ov.get(x.pats[i].key());
            if (kv == null || kv.isEmpty()) {
                x.details[i] = x.pats[i].describe();
                x.pcm[i] = Synth.render(note, x.pats[i], pr);
            } else {
                Params pi = pr.overlay(kv);
                Codebook.Pattern pp = Over.pattern(x.pats[i], Over.parse(kv));
                Synth.Note ni = Synth.prepare(raw, rawRate, pi);
                if (ni.data.length < 64) ni = note;
                x.details[i] = pp.describe() + "   (tuned)";
                x.pcm[i] = Synth.render(ni, pp, pi);
                tuned++;
            }
            lo = Math.min(lo, x.pcm[i].length);
            hi = Math.max(hi, x.pcm[i].length);
        }
        StringBuilder s = new StringBuilder();
        s.append(x.pats.length).append(" sounds");
        if (x.pats.length < n) s.append(" (only that many fit these settings)");
        if (x.pats.length > 0) {
            s.append(String.format(Locale.US, ", %.2f-%.2f s", lo / (double) note.rate, hi / (double) note.rate));
            if (x.pats.length > 1)
                s.append(", closest pair ").append(num(Codebook.minDistance(x.pats, pr.rhythmW))).append(" (higher is easier)");
            if (tuned > 0) s.append(", ").append(tuned).append(" tuned");
        }
        x.info = s.toString();
        return x;
    }

    // ---------------------------------------------------------------- contact modes

    /** Resamples, caps the length, sets the level and makes sure there is something to play. */
    private static short[] finish(float[] w, int srcRate, Params pr, int outRate) {
        if (outRate != srcRate && w.length > 1) w = Synth.resample(w, srcRate, outRate);
        int maxS = (int) Math.min(Integer.MAX_VALUE / 4, (long) pr.maxMs * outRate / 1000);
        if (w.length > maxS) {
            w = java.util.Arrays.copyOf(w, maxS);
            int fo = Math.min(outRate / 40, maxS / 2);
            for (int j = 0; j < fo; j++) w[maxS - 1 - j] *= j / (float) fo;
        }
        Synth.normalize(w, (float) Math.pow(10, pr.levelDb / 20.0));
        if (w.length < 64) w = new float[64];                  // nothing to say: a short silence, not a crash
        short[] pcm = new short[w.length];
        for (int j = 0; j < pcm.length; j++) pcm[j] = Synth.s16(w[j]);
        return pcm;
    }

    private static Result contactResult(List<Contacts.C> list, Params pr, int rate) {
        Result x = new Result();
        x.prm = pr;
        x.rate = rate;
        x.names = Contacts.labels(list);
        x.details = new String[list.size()];
        x.pcm = new short[list.size()][];
        return x;
    }

    private static void contactInfo(Result x, int n, double closest, int tuned) {
        int lo = Integer.MAX_VALUE, hi = 0;
        for (short[] p : x.pcm) { lo = Math.min(lo, p.length); hi = Math.max(hi, p.length); }
        StringBuilder s = new StringBuilder();
        s.append(n).append(n == 1 ? " contact" : " contacts");
        if (n == 0) s.append(": add one with the buttons above");
        else {
            s.append(String.format(Locale.US, ", %.2f-%.2f s", lo / (double) x.rate, hi / (double) x.rate));
            if (n > 1) s.append(", closest pair ").append(num(closest)).append(" (higher is easier)");
            if (tuned > 0) s.append(", ").append(tuned).append(" tuned");
        }
        x.info = s.toString();
    }

    private static String tag(Contacts.C c) { return Over.has(c.ov) ? "   (tuned)" : ""; }

    /** Contact voices: every contact name is spoken as a fast mumble in that contact's own voice. */
    static Result buildVoices(List<Contacts.C> list, Params pr) {
        int rate = pr.rate > 0 ? pr.rate : Voice.RATE;
        Result x = contactResult(list, pr, rate);
        ArrayList<Contacts.C> eff = new ArrayList<>();
        int tuned = 0;
        for (int i = 0; i < list.size(); i++) {
            Contacts.C c = list.get(i), e = Over.effective(c);
            eff.add(e);
            Params pi = pr.overlay(c.ov);
            float[] w = Voice.render(Say.syllables(c.text(), pi.vFirst, pi.vMaxSyl), e.pitch, e.fsc, e.spd, e.con,
                    pi, Contacts.seed(c.text()));
            x.pcm[i] = finish(w, Voice.RATE, pi, rate);
            x.details[i] = e.describe() + tag(c);
            if (Over.has(c.ov)) tuned++;
        }
        contactInfo(x, list.size(), Contacts.minDistance(eff), tuned);
        return x;
    }

    /** Contact blips: one electronic blip per syllable from the chosen source, no speech model. Null if the source is empty. */
    static Result buildBlips(List<Contacts.C> list, float[] raw, int rawRate, Params pr) {
        float peak = 0;
        for (float v : raw) peak = Math.max(peak, Math.abs(v));
        if (peak <= 0) return null;
        int rate = pr.rate > 0 ? pr.rate : rawRate;
        Result x = contactResult(list, pr, rate);
        ArrayList<Contacts.C> eff = new ArrayList<>();
        int tuned = 0;
        for (int i = 0; i < list.size(); i++) {
            Contacts.C c = list.get(i), e = Over.effective(c);
            eff.add(e);
            Params pi = pr.overlay(c.ov);
            float[] w = Blip.render(Say.syllables(c.text(), pi.vFirst, pi.vMaxSyl), e, pi, Contacts.seed(c.text()), raw, rawRate);
            x.pcm[i] = finish(w, rawRate, pi, rate);
            x.details[i] = e.describeBlip() + tag(c);
            if (Over.has(c.ov)) tuned++;
        }
        contactInfo(x, list.size(), Contacts.minDistanceB(eff), tuned);
        return x;
    }

    /** Phoneme blips: one consistent voice, every sound of the name with its own pitch. Null if the source is empty. */
    static Result buildPBlips(List<Contacts.C> list, float[] raw, int rawRate, Params pr) {
        float peak = 0;
        for (float v : raw) peak = Math.max(peak, Math.abs(v));
        if (peak <= 0) return null;
        int rate = pr.rate > 0 ? pr.rate : rawRate;
        Result x = contactResult(list, pr, rate);
        int tuned = 0;
        for (int i = 0; i < list.size(); i++) {
            Contacts.C c = list.get(i);
            Params pi = pr.overlay(c.ov);
            String phones = Say.phoneString(Say.syllables(c.text(), pi.vFirst, pi.vMaxSyl));
            x.pcm[i] = finish(PBlip.render(phones, pi, raw, rawRate), rawRate, pi, rate);
            x.details[i] = "phonemes: " + PBlip.show(phones) + (c.say.isEmpty() ? "" : "   say: " + c.say) + tag(c);
            if (Over.has(c.ov)) tuned++;
        }
        contactInfo(x, list.size(), 0, tuned);
        StringBuilder same = new StringBuilder();
        int pairs = 0;
        for (int i = 0; i < x.pcm.length; i++)
            for (int j = i + 1; j < x.pcm.length; j++)
                if (java.util.Arrays.equals(x.pcm[i], x.pcm[j]) && pairs++ < 3) {
                    if (same.length() > 0) same.append(", ");
                    same.append(x.names[i]).append(" = ").append(x.names[j]);
                }
        x.info = x.info.replaceAll(", closest pair [^,]*", "") + (pairs > 0 ? ". Sounds identical (same pronunciation): " + same + (pairs > 3 ? ", ..." : "") : "");
        return x;
    }

    /** Name patterns: the old tone patterns, each contact's chosen to follow its name. Null if the source is empty. */
    static Result buildCpat(List<Contacts.C> list, float[] raw, int rawRate, Params pr) {
        Synth.Note note = Synth.prepare(raw, rawRate, pr);
        if (note.data.length < 64) return null;
        Result x = contactResult(list, pr, note.rate);
        x.note = note;
        x.pats = new Codebook.Pattern[list.size()];
        Codebook.Pattern[] eff = new Codebook.Pattern[list.size()];
        int tuned = 0;
        for (int i = 0; i < list.size(); i++) {
            Contacts.C c = list.get(i), e = Over.effective(c);
            Codebook.Pattern p = c.pattern();
            if (p == null) p = Contacts.target(c.text(), pr);
            Codebook.Pattern pe = e.pattern() != null ? e.pattern() : p;
            x.pats[i] = p;
            eff[i] = pe;
            Params pi = pr.overlay(c.ov);
            Synth.Note ni = Over.has(c.ov) ? Synth.prepare(raw, rawRate, pi) : note;
            if (ni.data.length < 64) ni = note;
            x.pcm[i] = Synth.render(ni, pe, pi);
            x.details[i] = pe.describe() + (c.say.isEmpty() ? "" : "   say: " + c.say) + tag(c);
            if (Over.has(c.ov)) tuned++;
        }
        contactInfo(x, list.size(), eff.length > 1 ? Codebook.minDistance(eff, pr.rhythmW) : 0, tuned);
        return x;
    }

    /** File name of sound i, the same for export, system tones and the file picker. */
    static String fileName(Params p, int i, String name) {
        return (p.numbered ? String.format(Locale.US, "%02d-", i + 1) : "") + p.prefix + name + ".wav";
    }

    /** File name of the ringtone version of sound i (kindSuffix is "-ringtone" or "-alarm"). */
    static String ringFileName(Params p, int i, String name, String kindSuffix) {
        return (p.numbered ? String.format(Locale.US, "%02d-", i + 1) : "") + p.prefix + name + kindSuffix + ".wav";
    }

    // ---------------------------------------------------------------- ringtone parts

    /** Typed text as a sound: phoneme blips, a contact voice or name blips, as chosen by rtTextStyle. Null if nothing came out. */
    static Synth.Note textNote(String text, Params p, float[] raw, int rawRate) {
        if (raw == null || raw.length == 0) { raw = Synth.builtin(0); rawRate = Synth.BUILTIN_RATE; }
        java.util.ArrayList<Say.Syl> syl = Say.syllables(text, false, 1000);
        if (syl.isEmpty()) return null;
        int seed = Contacts.seed(text);
        float[] w;
        int rate = rawRate;
        if (p.rtTextStyle == 1) {
            w = Voice.render(syl, 0, 100, 100, 0, p, seed);
            rate = Voice.RATE;
        } else if (p.rtTextStyle == 2) {
            Contacts.C neutral = new Contacts.C();
            neutral.bsrc = -1;
            w = Blip.render(syl, neutral, p, seed, raw, rawRate);
        } else {
            w = PBlip.render(Say.phoneString(syl), p, raw, rawRate);
        }
        if (w.length < 16) return null;
        Synth.normalize(w, 0.9f);
        Synth.Note n = new Synth.Note();
        n.data = w;
        n.rate = rate;
        return n;
    }

    /**
     * The prefix or suffix sound named by a setting ("b:<id>" built-in, "u:<uri>" file, "t:<text>" typed text),
     * ready to use; null for none or on failure. raw is the app's chosen source sound, used for typed text.
     */
    static Synth.Note loadAux(Context c, String spec, Params p, float[] srcRaw, int srcRate) {
        if (spec == null || spec.length() < 3) return null;
        try {
            if (spec.startsWith("t:")) return textNote(spec.substring(2), p, srcRaw, srcRate);
            float[] raw;
            int rate;
            if (spec.startsWith("b:")) {
                int id = Integer.parseInt(spec.substring(2).trim());
                if (!Synth.hasBuiltin(id)) return null;
                raw = Synth.builtin(id);
                rate = Synth.BUILTIN_RATE;
            } else if (spec.startsWith("u:")) {
                Decode.Pcm d = Decode.read(c, Uri.parse(spec.substring(2)));
                raw = d.data;
                rate = d.rate;
            } else {
                return null;
            }
            Params pb = p.copy();
            pb.noteMs = p.rtAuxMs;
            pb.rate = 0;
            pb.reverse = false;
            Synth.Note n = Synth.prepare(raw, rate, pb);
            return n.data.length < 16 ? null : n;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- from saved settings

    private static String lastKey;
    private static Result last;

    /** Rebuilds the set from the saved settings, for callers that run without the app screen. Cached. */
    static synchronized Result fromPrefs(Context c) throws IOException {
        SharedPreferences sp = c.getSharedPreferences("weealert", Context.MODE_PRIVATE);
        Params pr = Params.parse(sp.getString("params", ""));
        int n = Math.max(1, sp.getInt("n", 8));
        String src = sp.getString("src", null);
        int builtin = sp.getInt("builtin", 0);
        String mode = sp.getString("mode", "patterns");
        String contactsRaw = sp.getString("contacts", "");
        String ovRaw = sp.getString("ov", "");
        String key = mode + "|" + pr.serialize() + "|" + n + "|" + src + "|" + builtin
                + (isContacts(mode) ? "|" + contactsRaw : "|" + ovRaw);
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
        Result x;
        if ("voices".equals(mode) || "blips".equals(mode) || "cpat".equals(mode) || "pblips".equals(mode)) {
            ArrayList<Contacts.C> list = Contacts.parse(contactsRaw);
            Contacts.ensure(list, pr);
            x = "voices".equals(mode) ? buildVoices(list, pr) : "blips".equals(mode) ? buildBlips(list, raw, rate, pr)
                    : "pblips".equals(mode) ? buildPBlips(list, raw, rate, pr) : buildCpat(list, raw, rate, pr);
        } else {
            x = build(raw, rate, pr, n, Over.parseAll(ovRaw));
        }
        if (x == null) throw new IOException("the source sound is empty");
        x.raw = raw;
        x.rawRate = rate;
        x.key = Integer.toHexString(key.hashCode());
        lastKey = key;
        last = x;
        return x;
    }
}
