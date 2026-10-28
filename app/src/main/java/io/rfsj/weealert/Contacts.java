package io.rfsj.weealert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The contact list for the contact modes, and the choice of a different sound for each contact.
 * A contact keeps one assignment per style (voice, blip, name pattern), made when it is added, so
 * existing contacts never change when others are added. Pure Java.
 */
final class Contacts {
    static final class C {
        String name = "", say = "";
        int pitch = 0, fsc = 100, spd = 100, con = 0, roll = 0;     // contact voices
        int bsrc = -2, bpit = 0, bspd = 100, bcon = 0, broll = 0;   // blips; bsrc -2 = not assigned yet
        String pat = "";                                            // name patterns "gaps|semis"; "" = not assigned yet
        int proll = 0;

        String text() { return say.isEmpty() ? name : say; }

        private static String sign(int v) { return (v > 0 ? "+" : "") + v; }

        private String tail() { return say.isEmpty() ? "" : "   say: " + say; }

        String describe() {
            return "voice: pitch " + sign(pitch) + ", size " + fsc + "%, speed " + spd + "%, " + CONTOUR[con & 3] + tail();
        }

        String describeBlip() {
            String src = Synth.hasBuiltin(bsrc) ? Synth.BUILTIN[bsrc] : "bell";
            return "blips: " + src + ", pitch " + sign(bpit) + ", speed " + bspd + "%, " + CONTOUR[bcon & 3] + tail();
        }

        Codebook.Pattern pattern() {
            int bar = pat.indexOf('|');
            if (bar < 0) return null;
            int[] g = Params.parseList(pat.substring(0, bar)), s = Params.parseList(pat.substring(bar + 1));
            if (s.length == 0 || g.length != s.length - 1) return null;
            return new Codebook.Pattern(g, s);
        }

        void setPattern(Codebook.Pattern p) { pat = ints(p.gaps) + "|" + ints(p.semis); }
    }

    static final String[] CONTOUR = {"wandering", "rising", "falling", "bouncy"};
    static final int[] PITCH = {-6, -3, 0, 3, 6}, FSC = {85, 100, 115, 130}, SPD = {85, 100, 120};
    static final int CONTOURS = 4;

    private static String ints(int[] a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) b.append(','); b.append(a[i]); }
        return b.toString();
    }

    private static double log2(double x) { return Math.log(x) / Math.log(2); }

    private static int hash(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) { h ^= s.charAt(i); h *= 16777619; }
        return h;
    }

    static int seed(String text) { return hash(text.toLowerCase()); }

    // ---------------------------------------------------------------- distances

    /** Voices: semitones of pitch, vocal tract size and speed in semitone-ish units. */
    static double dist(C a, C b) {
        return Math.abs(a.pitch - b.pitch) + 12 * Math.abs(log2(a.fsc / (double) b.fsc))
                + 20 * Math.abs(log2(a.spd / (double) b.spd)) + (a.con != b.con ? 4 : 0);
    }

    /** Blips: a different source sound counts a lot, then pitch, speed and contour. */
    static double distB(C a, C b) {
        return Math.abs(a.bpit - b.bpit) + 20 * Math.abs(log2(a.bspd / (double) b.bspd))
                + (a.bcon != b.bcon ? 4 : 0) + (a.bsrc != b.bsrc ? 8 : 0);
    }

    static double minDistance(List<C> l) {
        double m = Double.MAX_VALUE;
        for (int i = 0; i < l.size(); i++)
            for (int j = i + 1; j < l.size(); j++) m = Math.min(m, dist(l.get(i), l.get(j)));
        return m;
    }

    static double minDistanceB(List<C> l) {
        double m = Double.MAX_VALUE;
        for (int i = 0; i < l.size(); i++)
            for (int j = i + 1; j < l.size(); j++) m = Math.min(m, distB(l.get(i), l.get(j)));
        return m;
    }

    // ---------------------------------------------------------------- assignment

    private static void rank(ArrayList<Integer> order, final ArrayList<Double> score, final int salt) {
        Collections.sort(order, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                int d = Double.compare(score.get(b), score.get(a));
                return d != 0 ? d : Integer.compare(hash(a + ":" + salt), hash(b + ":" + salt));
            }
        });
    }

    /**
     * Voices: gives a contact the voice farthest from everyone else's. A new contact takes the best one;
     * asking again (reroll) walks down the ranking among clearly different voices. The first contact gets
     * the plain centre voice.
     */
    static void assign(List<C> all, C c, boolean reroll) {
        ArrayList<C> others = new ArrayList<>();
        for (C o : all) if (o != c) others.add(o);
        if (others.isEmpty() && !reroll) { c.pitch = 0; c.fsc = 100; c.spd = 100; c.con = 0; return; }
        ArrayList<C> cand = new ArrayList<>();
        ArrayList<Double> score = new ArrayList<>();
        for (int p : PITCH) for (int f : FSC) for (int s : SPD) for (int k = 0; k < CONTOURS; k++) {
            C v = new C();
            v.pitch = p; v.fsc = f; v.spd = s; v.con = k;
            if (reroll && dist(v, c) < 6) continue;            // a new voice must sound clearly different
            double m = Double.MAX_VALUE;
            for (C o : others) m = Math.min(m, dist(v, o));
            cand.add(v);
            score.add(others.isEmpty() ? 0 : m);
        }
        ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < cand.size(); i++) order.add(i);
        rank(order, score, hash(c.name) + c.roll);
        C v = cand.get(order.get(reroll ? c.roll % Math.min(order.size(), 12) : 0));
        if (reroll) c.roll++;
        c.pitch = v.pitch; c.fsc = v.fsc; c.spd = v.spd; c.con = v.con;
    }

    /** Blips: the same idea over source sound, pitch, speed and contour. */
    static void assignBlips(List<C> all, C c, boolean reroll) {
        ArrayList<C> others = new ArrayList<>();
        for (C o : all) if (o != c && o.bsrc != -2) others.add(o);
        if (others.isEmpty() && !reroll) { c.bsrc = 0; c.bpit = 0; c.bspd = 100; c.bcon = 0; return; }
        ArrayList<C> cand = new ArrayList<>();
        ArrayList<Double> score = new ArrayList<>();
        for (int id = 0; id < Synth.BUILTIN.length; id++) {
            if (!Synth.hasBuiltin(id)) continue;
            for (int p : PITCH) for (int s : SPD) for (int k = 0; k < CONTOURS; k++) {
                C v = new C();
                v.bsrc = id; v.bpit = p; v.bspd = s; v.bcon = k;
                if (reroll && c.bsrc != -2 && distB(v, c) < 6) continue;
                double m = Double.MAX_VALUE;
                for (C o : others) m = Math.min(m, distB(v, o));
                cand.add(v);
                score.add(others.isEmpty() ? 0 : m);
            }
        }
        ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < cand.size(); i++) order.add(i);
        rank(order, score, hash(c.name) + c.broll);
        C v = cand.get(order.get(reroll ? c.broll % Math.min(order.size(), 12) : 0));
        if (reroll) c.broll++;
        c.bsrc = v.bsrc; c.bpit = v.bpit; c.bspd = v.bspd; c.bcon = v.bcon;
    }

    private static int nearest(int[] set, int v) {
        int best = set[0];
        for (int s : set) if (Math.abs(s - v) < Math.abs(best - v)) best = s;
        return best;
    }

    /**
     * What the name asks for: one note per syllable, each note's pitch from its vowel (bright vowels
     * high), the gap after it from the syllable's weight; all snapped to the allowed pitches and gaps.
     */
    static Codebook.Pattern target(String text, Params prm) {
        java.util.ArrayList<Say.Syl> sy = Say.syllables(text, prm.vFirst, 6);
        int k = Math.max(prm.minNotes, Math.min(prm.maxNotes, sy.isEmpty() ? 2 : sy.size()));
        int[] semis = new int[k], gaps = new int[k - 1];
        int mid = prm.gaps.length / 2;
        for (int i = 0; i < k; i++) {
            Say.Syl s = sy.isEmpty() ? null : sy.get(Math.min(i, sy.size() - 1));
            semis[i] = nearest(prm.semis, s == null ? 0 : (int) Math.round(Blip.vowelSemi(s.nuc) * 1.5));
            if (i < k - 1) {
                int g = mid;
                if (s != null) {
                    if (s.co.length() > 0 || s.on.length() > 1) g++;
                    else if (s.on.length() == 0) g--;
                }
                gaps[i] = prm.gaps[Math.max(0, Math.min(prm.gaps.length - 1, g))];
            }
        }
        return new Codebook.Pattern(gaps, semis);
    }

    /**
     * Name patterns: the allowed pattern closest to what the name asks for, as long as it stays at least
     * cSep away from every other contact's pattern; if the name's own pattern is taken, the nearest free one.
     */
    static void assignPattern(List<C> all, C c, Params prm, boolean reroll) {
        ArrayList<Codebook.Pattern> others = new ArrayList<>();
        for (C o : all) {
            Codebook.Pattern p = o == c ? null : o.pattern();
            if (p != null) others.add(p);
        }
        Codebook.Pattern cur = reroll ? c.pattern() : null;
        Codebook.Pattern t = target(c.text(), prm);
        ArrayList<Codebook.Pattern> cand = Codebook.candidates(prm, prm.noteMs);
        ArrayList<Integer> order = new ArrayList<>();
        ArrayList<Double> score = new ArrayList<>();
        for (int i = 0; i < cand.size(); i++) {
            Codebook.Pattern p = cand.get(i);
            if (cur != null && Codebook.dist(p, cur, prm.rhythmW) < 6) continue;
            int m = Integer.MAX_VALUE;
            for (Codebook.Pattern o : others) m = Math.min(m, Codebook.dist(p, o, prm.rhythmW));
            order.add(i);
            // free patterns rank by closeness to the name's own pattern, taken ones by how far from the others
            score.add(m >= prm.cSep ? -Codebook.dist(p, t, prm.rhythmW) : -1000.0 + Math.min(m, 999));
        }
        if (order.isEmpty()) { c.setPattern(t); return; }
        final ArrayList<Double> sc = score;
        final ArrayList<Integer> idx = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) idx.add(i);
        rank(idx, sc, hash(c.name) + c.proll);
        int pick = reroll ? c.proll % Math.min(idx.size(), 12) : 0;
        if (reroll) c.proll++;
        c.setPattern(cand.get(order.get(idx.get(pick))));
    }

    /** Gives a new contact an assignment for every style. */
    static void init(List<C> all, C c, Params prm) {
        assign(all, c, false);
        assignBlips(all, c, false);
        assignPattern(all, c, prm, false);
    }

    /** Fills in styles that older saved contacts do not have yet. */
    static boolean ensure(List<C> all, Params prm) {
        boolean changed = false;
        for (C c : all) {
            if (c.bsrc == -2) { assignBlips(all, c, false); changed = true; }
            if (c.pattern() == null) { assignPattern(all, c, prm, false); changed = true; }
        }
        return changed;
    }

    /** A new sound for one contact in the given mode ("voices", "blips" or "cpat"). */
    static void reroll(List<C> all, C c, String mode, Params prm) {
        if ("blips".equals(mode)) assignBlips(all, c, true);
        else if ("cpat".equals(mode)) assignPattern(all, c, prm, true);
        else assign(all, c, true);
    }

    // ---------------------------------------------------------------- text form

    static String clean(String s) {
        String t = s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() > 40 ? t.substring(0, 40).trim() : t;
    }

    static String serialize(List<C> l) {
        StringBuilder b = new StringBuilder();
        for (C c : l)
            b.append(clean(c.name)).append('\t').append(clean(c.say)).append('\t').append(c.pitch).append('\t')
                    .append(c.fsc).append('\t').append(c.spd).append('\t').append(c.con).append('\t').append(c.roll)
                    .append('\t').append(c.bsrc).append('\t').append(c.bpit).append('\t').append(c.bspd)
                    .append('\t').append(c.bcon).append('\t').append(c.broll).append('\t').append(c.pat)
                    .append('\t').append(c.proll).append('\n');
        return b.toString();
    }

    static ArrayList<C> parse(String s) {
        ArrayList<C> out = new ArrayList<>();
        if (s == null) return out;
        for (String line : s.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 7 || f[0].isEmpty()) continue;
            try {
                C c = new C();
                c.name = f[0];
                c.say = f[1];
                c.pitch = Integer.parseInt(f[2]);
                c.fsc = Math.max(50, Math.min(200, Integer.parseInt(f[3])));
                c.spd = Math.max(50, Math.min(200, Integer.parseInt(f[4])));
                c.con = Math.floorMod(Integer.parseInt(f[5]), CONTOURS);
                c.roll = Integer.parseInt(f[6]);
                if (f.length >= 14) {
                    c.bsrc = Integer.parseInt(f[7]);
                    c.bpit = Integer.parseInt(f[8]);
                    c.bspd = Math.max(50, Math.min(200, Integer.parseInt(f[9])));
                    c.bcon = Math.floorMod(Integer.parseInt(f[10]), CONTOURS);
                    c.broll = Integer.parseInt(f[11]);
                    c.pat = f[12];
                    c.proll = Integer.parseInt(f[13]);
                }
                out.add(c);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    /** File and tone names: safe characters only, unique within the list. */
    static String[] labels(List<C> l) {
        String[] out = new String[l.size()];
        ArrayList<String> used = new ArrayList<>();
        for (int i = 0; i < out.length; i++) {
            String s = l.get(i).name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
            if (s.isEmpty()) s = "contact";
            String u = s;
            int n = 2;
            while (used.contains(u.toLowerCase())) u = s + " " + n++;
            used.add(u.toLowerCase());
            out[i] = u;
        }
        return out;
    }
}
