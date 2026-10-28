package io.rfsj.weealert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

/**
 * Picks N sound patterns that are as far apart from each other as possible.
 * A pattern is 1-4 notes: the gaps between onsets (rhythm) and a pitch offset per note (contour).
 * Rhythm and contour are far easier to tell apart by ear than tiny pitch shifts.
 * Greedy farthest-point selection, fully deterministic: the first N patterns are the same for
 * every count, so asking for more sounds later never changes the ones you already learned.
 */
final class Codebook {
    static final int GRID_MS = 50;             // rhythm grid; gaps are multiples of this

    static final class Pattern {
        final int[] gaps, semis, on;           // on = onset position of each note on the grid
        Pattern(int[] gaps, int[] semis) {
            this.gaps = gaps;
            this.semis = semis;
            on = new int[semis.length];
            for (int i = 1; i < on.length; i++) on[i] = on[i - 1] + gaps[i - 1] / GRID_MS;
        }

        int onsetMs(int i) { return on[i] * GRID_MS; }

        private static String gap(int ms) { return ms % 100 == 0 ? String.valueOf(ms / 100) : (ms / 100) + ".5"; }

        /** Identity of the pattern; the file name is derived from it, so it must never change. */
        String key() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < semis.length; i++) {
                if (i > 0) b.append('_').append(gap(gaps[i - 1])).append('_');
                b.append(semis[i]);
            }
            return b.toString();
        }

        String describe() {
            StringBuilder b = new StringBuilder();
            b.append(semis.length).append(semis.length == 1 ? " note  " : " notes  ");
            for (int i = 0; i < semis.length; i++) {
                if (i > 0) b.append(' ');
                b.append(semis[i] > 0 ? "+" : "").append(semis[i]);
            }
            if (gaps.length > 0) {
                b.append("   gaps ");
                for (int i = 0; i < gaps.length; i++) { if (i > 0) b.append(' '); b.append(gaps[i]); }
                b.append(" ms");
            }
            return b.toString();
        }
    }

    private static int[] digits(int v, int[] alphabet, int len) {
        int[] r = new int[len];
        for (int i = len - 1; i >= 0; i--) { r[i] = alphabet[v % alphabet.length]; v /= alphabet.length; }
        return r;
    }

    private static int pow(int b, int e) { int r = 1; while (e-- > 0) r *= b; return r; }

    /** Distance between two patterns: rhythm mismatches cost w each, pitch differences cost 1 per semitone. */
    static int dist(Pattern a, Pattern b, int w) {
        int i = 0, j = 0, d = 0;
        while (i < a.on.length && j < b.on.length) {
            if (a.on[i] == b.on[j]) { d += Math.abs(a.semis[i] - b.semis[j]); i++; j++; }
            else if (a.on[i] < b.on[j]) { d += w; i++; }
            else { d += w; j++; }
        }
        return d + w * (a.on.length - i + b.on.length - j);
    }

    static int minDistance(Pattern[] ps, int w) {
        int m = Integer.MAX_VALUE;
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

    /** Every pattern the settings allow, in a fixed order, minus those that would exceed the max length. */
    static ArrayList<Pattern> candidates(Params prm, int srcMs) {
        ArrayList<Pattern> all = new ArrayList<>();
        for (int k = prm.minNotes; k <= prm.maxNotes; k++) {
            int gc = pow(prm.gaps.length, k - 1), sc = pow(prm.semis.length, k);
            for (int g = 0; g < gc; g++)
                for (int s = 0; s < sc; s++) {
                    Pattern c = new Pattern(digits(g, prm.gaps, k - 1), digits(s, prm.semis, k));
                    if (spanMs(c, srcMs, prm.cut) <= prm.maxMs) all.add(c);
                }
        }
        if (all.isEmpty()) // nothing fits the cap: fall back to single notes, the renderer trims them
            for (int s = 0; s < prm.semis.length; s++) all.add(new Pattern(new int[0], new int[]{prm.semis[s]}));
        return all;
    }

    static Pattern[] pick(Params prm, int srcMs, int n) {
        ArrayList<Pattern> all = candidates(prm, srcMs);

        int cur = 0;
        if (prm.seed != 0) {
            Collections.shuffle(all, new Random(prm.seed));
        } else {
            // seed pattern: two plain notes at the middle gap, if they are allowed
            int target = Math.max(prm.minNotes, Math.min(prm.maxNotes, 2));
            int mid = prm.gaps[prm.gaps.length / 2], zero = prm.semis[0];
            for (int s : prm.semis) if (Math.abs(s) < Math.abs(zero)) zero = s;
            for (int i = 0; i < all.size(); i++) {
                Pattern p = all.get(i);
                if (p.semis.length != target || (target > 1 && p.gaps[0] != mid)) continue;
                boolean plain = true;
                for (int s : p.semis) if (s != zero) plain = false;
                if (plain) { cur = i; break; }
            }
        }

        int c = all.size();
        Pattern[] arr = all.toArray(new Pattern[0]);
        int[] minD = new int[c];
        java.util.Arrays.fill(minD, Integer.MAX_VALUE);
        int m = Math.min(n, c);
        Pattern[] out = new Pattern[m];
        for (int i = 0; i < m; i++) {
            out[i] = arr[cur];
            int best = -1, bd = 0;
            for (int j = 0; j < c; j++) {
                int d = dist(arr[cur], arr[j], prm.rhythmW);
                if (d < minD[j]) minD[j] = d;
                if (minD[j] > bd) { bd = minD[j]; best = j; }
            }
            if (best < 0) return java.util.Arrays.copyOf(out, i + 1);
            cur = best;
        }
        return out;
    }
}
