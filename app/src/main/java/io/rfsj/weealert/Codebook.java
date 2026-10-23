package io.rfsj.weealert;

import java.util.ArrayList;

/**
 * Picks N sound patterns that are as far apart from each other as possible.
 * A pattern is 1-3 notes: the gaps between onsets (rhythm) and a pitch offset per note (contour).
 * Rhythm and contour are far easier to tell apart by ear than tiny pitch shifts.
 * Greedy farthest-point selection, fully deterministic: the first N patterns are the same for
 * every count, so asking for more sounds later never changes the ones you already learned.
 */
final class Codebook {
    static final int UNIT_MS = 100;            // rhythm grid
    static final int SLOTS = 9;                // 2 gaps of at most 4 units, plus the first onset
    static final int[] GAPS = {2, 3, 4};       // 200 / 300 / 400 ms between onsets
    static final int[] SEMIS = {-5, -2, 0, 3, 7};
    static final int RHYTHM_W = 5;             // a rhythm mismatch weighs like 5 semitones

    static final class Pattern {
        final int[] gaps, semis;
        Pattern(int[] gaps, int[] semis) { this.gaps = gaps; this.semis = semis; }

        int onsetUnits(int i) {
            int t = 0;
            for (int j = 0; j < i; j++) t += gaps[j];
            return t;
        }

        byte[] slots() {
            byte[] v = new byte[SLOTS];
            for (int i = 0; i < semis.length; i++) v[onsetUnits(i)] = (byte) (semis[i] + 64);
            return v;
        }

        String key() {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < semis.length; i++) {
                if (i > 0) b.append('_').append(gaps[i - 1]).append('_');
                b.append(semis[i]);
            }
            return b.toString();
        }

        String describe() {
            StringBuilder b = new StringBuilder();
            b.append(semis.length).append(" notes  ");
            for (int i = 0; i < semis.length; i++) {
                if (i > 0) b.append(' ');
                b.append(semis[i] > 0 ? "+" : "").append(semis[i]);
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

    static Pattern[] pick(int n) {
        ArrayList<Pattern> all = new ArrayList<>();
        for (int k = 1; k <= 3; k++) {
            int gc = pow(GAPS.length, k - 1), sc = pow(SEMIS.length, k);
            for (int g = 0; g < gc; g++)
                for (int s = 0; s < sc; s++) all.add(new Pattern(digits(g, GAPS, k - 1), digits(s, SEMIS, k)));
        }
        int c = all.size();
        byte[] sl = new byte[c * SLOTS];
        int cur = 0;
        for (int i = 0; i < c; i++) {
            System.arraycopy(all.get(i).slots(), 0, sl, i * SLOTS, SLOTS);
            Pattern p = all.get(i);
            if (p.semis.length == 2 && p.gaps[0] == 3 && p.semis[0] == 0 && p.semis[1] == 0) cur = i; // the seed: the plain source twice
        }
        int[] minD = new int[c];
        java.util.Arrays.fill(minD, Integer.MAX_VALUE);
        int m = Math.min(n, c);
        Pattern[] out = new Pattern[m];
        for (int i = 0; i < m; i++) {
            out[i] = all.get(cur);
            int best = -1, bd = 0;
            for (int j = 0; j < c; j++) {
                int d = 0;
                for (int s = 0; s < SLOTS; s++) {
                    int a = sl[cur * SLOTS + s], b = sl[j * SLOTS + s];
                    if ((a == 0) != (b == 0)) d += RHYTHM_W;
                    else if (a != 0) d += Math.abs(a - b);
                }
                if (d < minD[j]) minD[j] = d;
                if (minD[j] > bd) { bd = minD[j]; best = j; }
            }
            if (best < 0) return java.util.Arrays.copyOf(out, i + 1);
            cur = best;
        }
        return out;
    }
}
