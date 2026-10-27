package io.rfsj.weealert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** The contact list for voice mode, and the choice of a different voice for each contact. Pure Java. */
final class Contacts {
    static final class C {
        String name = "", say = "";
        int pitch = 0, fsc = 100, spd = 100, con = 0, roll = 0;

        String text() { return say.isEmpty() ? name : say; }

        String describe() {
            String[] cn = {"wandering", "rising", "falling", "bouncy"};
            return "voice: pitch " + (pitch > 0 ? "+" : "") + pitch + ", size " + fsc + "%, speed " + spd + "%, "
                    + cn[con & 3] + (say.isEmpty() ? "" : "   say: " + say);
        }
    }

    static final int[] PITCH = {-6, -3, 0, 3, 6}, FSC = {85, 100, 115, 130}, SPD = {85, 100, 120};
    static final int CONTOURS = 4;

    private static double log2(double x) { return Math.log(x) / Math.log(2); }

    /** How different two voices sound: semitones of pitch, vocal tract size and speed in semitone-ish units. */
    static double dist(C a, C b) {
        return Math.abs(a.pitch - b.pitch) + 12 * Math.abs(log2(a.fsc / (double) b.fsc))
                + 20 * Math.abs(log2(a.spd / (double) b.spd)) + (a.con != b.con ? 4 : 0);
    }

    static double minDistance(List<C> l) {
        double m = Double.MAX_VALUE;
        for (int i = 0; i < l.size(); i++)
            for (int j = i + 1; j < l.size(); j++) m = Math.min(m, dist(l.get(i), l.get(j)));
        return m;
    }

    private static int hash(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) { h ^= s.charAt(i); h *= 16777619; }
        return h;
    }

    static int seed(String text) { return hash(text.toLowerCase()); }

    /**
     * Gives a contact the voice that is farthest from everyone else's. A new contact takes the best one;
     * asking again (reroll) walks down the ranking. The first contact gets the plain centre voice.
     */
    static void assign(List<C> all, final C c, boolean reroll) {
        ArrayList<C> others = new ArrayList<>();
        for (C o : all) if (o != c) others.add(o);
        if (others.isEmpty() && !reroll) { c.pitch = 0; c.fsc = 100; c.spd = 100; c.con = 0; return; }
        final ArrayList<C> cand = new ArrayList<>();
        final ArrayList<Double> score = new ArrayList<>();
        for (int p : PITCH) for (int f : FSC) for (int s : SPD) for (int k = 0; k < CONTOURS; k++) {
            C v = new C();
            v.pitch = p; v.fsc = f; v.spd = s; v.con = k;
            if (reroll && dist(v, c) < 6) continue;          // a new voice must sound clearly different
            double m = Double.MAX_VALUE;
            for (C o : others) m = Math.min(m, dist(v, o));
            cand.add(v);
            score.add(others.isEmpty() ? 0 : m);
        }
        ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < cand.size(); i++) order.add(i);
        final int salt = hash(c.name) + c.roll;
        Collections.sort(order, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                int d = Double.compare(score.get(b), score.get(a));
                return d != 0 ? d : Integer.compare(hash(a + ":" + salt), hash(b + ":" + salt));
            }
        });
        int pick = reroll ? c.roll % Math.min(order.size(), 12) : 0;
        C v = cand.get(order.get(pick));
        if (reroll) c.roll++;
        c.pitch = v.pitch; c.fsc = v.fsc; c.spd = v.spd; c.con = v.con;
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
                    .append(c.fsc).append('\t').append(c.spd).append('\t').append(c.con).append('\t').append(c.roll).append('\n');
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

    static String[] names(List<C> l) {
        String[] o = new String[l.size()];
        for (int i = 0; i < o.length; i++) o[i] = l.get(i).name;
        return o;
    }

    static String list(int[] a) { return Arrays.toString(a); }
}
