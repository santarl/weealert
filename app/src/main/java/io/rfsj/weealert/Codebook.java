package io.rfsj.weealert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

/**
 * Picks N sound patterns that are as far apart from each other as possible.
 * A pattern is a few notes: the gaps between onsets (rhythm) and a pitch offset per note (contour).
 * Rhythm and contour are far easier to tell apart by ear than tiny pitch shifts.
 * Greedy farthest-point selection, fully deterministic: the first N patterns are the same for
 * every count, so asking for more sounds later never changes the ones you already learned.
 */
final class Codebook {
    static final int TOL_MS = 25;              // onsets closer than this count as the same beat
    static final long LIMIT = 250000;          // memory guard: beyond this many combinations an even subset is used

    static final class Pattern {
        final int[] gaps, on;                  // on = onset time of each note in ms
        final double[] semis;
        Pattern(int[] gaps, double[] semis) {
            this.gaps = gaps;
            this.semis = semis;
            on = new int[semis.length];
            for (int i = 1; i < on.length; i++) on[i] = on[i - 1] + gaps[i - 1];
        }

        int onsetMs(int i) { return on[i]; }

        private static String gap(int ms) {
            if (ms % 100 == 0) return String.valueOf(ms / 100);
            if (ms % 50 == 0) return (ms / 100) + ".5";
            return "m" + ms;
        }

        /** Identity of the pattern; the file name is derived from it, so it must never change for existing patterns. */
        String key() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < semis.length; i++) {
                if (i > 0) b.append('_').append(gap(gaps[i - 1])).append('_');
                b.append(Params.fmt(semis[i]));
            }
            return b.toString();
        }

        String describe() {
            StringBuilder b = new StringBuilder();
            b.append(semis.length).append(semis.length == 1 ? " note  " : " notes  ");
            for (int i = 0; i < semis.length; i++) {
                if (i > 0) b.append(' ');
                b.append(semis[i] > 0 ? "+" : "").append(Params.fmt(semis[i]));
            }
            if (gaps.length > 0) {
                b.append("   gaps ");
                for (int i = 0; i < gaps.length; i++) { if (i > 0) b.append(' '); b.append(gaps[i]); }
                b.append(" ms");
            }
            return b.toString();
        }
    }

    private static int[] digits(long v, int[] alphabet, int len) {
        int[] r = new int[len];
        for (int i = len - 1; i >= 0; i--) { r[i] = alphabet[(int) (v % alphabet.length)]; v /= alphabet.length; }
        return r;
    }

    private static double[] digits(long v, double[] alphabet, int len) {
        double[] r = new double[len];
        for (int i = len - 1; i >= 0; i--) { r[i] = alphabet[(int) (v % alphabet.length)]; v /= alphabet.length; }
        return r;
    }

    private static long pow(long b, int e) {
        long r = 1;
        while (e-- > 0) { r *= b; if (r > 4000000000000L) return 4000000000000L; }
        return r;
    }

    /** Distance between two patterns: rhythm mismatches cost w each, pitch differences cost 1 per semitone. */
    static double dist(Pattern a, Pattern b, int w) {
        int i = 0, j = 0;
        double d = 0;
        while (i < a.on.length && j < b.on.length) {
            int diff = a.on[i] - b.on[j];
            if (Math.abs(diff) <= TOL_MS) { d += Math.abs(a.semis[i] - b.semis[j]); i++; j++; }
            else if (diff < 0) { d += w; i++; }
            else { d += w; j++; }
        }
        return d + w * (double) (a.on.length - i + b.on.length - j);
    }

    static double minDistance(Pattern[] ps, int w) {
        double m = Double.MAX_VALUE;
        for (int i = 0; i < ps.length; i++)
            for (int j = i + 1; j < ps.length; j++) m = Math.min(m, dist(ps[i], ps[j], w));
        return m;
    }

    /** Total length of the rendered sound, so patterns that exceed the cap can be left out. */
    private static double spanMs(Pattern p, int srcMs, boolean cut) {
        double span = 0;
        int k = p.semis.length;
        for (int i = 0; i < k; i++) {
            double len = srcMs / Math.pow(2, p.semis[i] / 12.0);
            if (cut && i < k - 1) len = Math.min(len, p.gaps[i]);
            span = Math.max(span, p.onsetMs(i) + len);
        }
        return span;
    }

    /**
     * Every pattern the settings allow, in a fixed order, minus those that would exceed the max length.
     * If the settings allow more than LIMIT combinations an evenly spread subset is used so memory stays safe.
     */
    static ArrayList<Pattern> candidates(Params prm, int srcMs) {
        ArrayList<Pattern> all = new ArrayList<>();
        long total = 0;
        for (int k = prm.minNotes; k <= prm.maxNotes; k++) total += count(prm, k);
        double keep = total > LIMIT ? LIMIT / (double) total : 1;
        for (int k = prm.minNotes; k <= prm.maxNotes; k++) {
            long sc = pow(prm.semis.length, k), cnt = count(prm, k);
            long take = keep < 1 ? Math.max(1, (long) (cnt * keep)) : cnt;
            for (long j = 0; j < take; j++) {
                long idx = keep < 1 ? (long) (j / keep) : j;
                if (idx >= cnt) break;
                Pattern c = new Pattern(digits(idx / sc, prm.gaps, Math.max(0, k - 1)), digits(idx % sc, prm.semis, k));
                if (spanMs(c, srcMs, prm.cut) <= prm.maxMs) all.add(c);
            }
        }
        if (all.isEmpty()) // nothing fits the cap: fall back to single notes, the renderer trims them
            for (double s : prm.semis) all.add(new Pattern(new int[0], new double[]{s}));
        return all;
    }

    private static long count(Params prm, int k) {
        long gc = pow(prm.gaps.length, Math.max(0, k - 1)), sc = pow(prm.semis.length, k);
        return gc > 4000000000000L / sc ? 4000000000000L : gc * sc;
    }

    static Pattern[] pick(Params prm, int srcMs, int n) {
        ArrayList<Pattern> all = candidates(prm, srcMs);

        int cur = 0;
        if (prm.seed != 0) {
            Collections.shuffle(all, new Random(prm.seed));
        } else {
            // seed pattern: two plain notes at the middle gap, if they are allowed
            int target = Math.max(prm.minNotes, Math.min(prm.maxNotes, 2));
            int mid = prm.gaps[prm.gaps.length / 2];
            double zero = prm.semis[0];
            for (double s : prm.semis) if (Math.abs(s) < Math.abs(zero)) zero = s;
            for (int i = 0; i < all.size(); i++) {
                Pattern p = all.get(i);
                if (p.semis.length != target || (target > 1 && p.gaps[0] != mid)) continue;
                boolean plain = true;
                for (double s : p.semis) if (s != zero) plain = false;
                if (plain) { cur = i; break; }
            }
        }

        int c = all.size();
        Pattern[] arr = all.toArray(new Pattern[0]);
        double[] minD = new double[c];
        java.util.Arrays.fill(minD, Double.MAX_VALUE);
        int m = Math.min(n, c);
        Pattern[] out = new Pattern[m];
        for (int i = 0; i < m; i++) {
            out[i] = arr[cur];
            int best = -1;
            double bd = 0;
            for (int j = 0; j < c; j++) {
                double d = dist(arr[cur], arr[j], prm.rhythmW);
                if (d < minD[j]) minD[j] = d;
                if (minD[j] > bd) { bd = minD[j]; best = j; }
            }
            if (best < 0) return java.util.Arrays.copyOf(out, i + 1);
            cur = best;
        }
        return out;
    }
}
