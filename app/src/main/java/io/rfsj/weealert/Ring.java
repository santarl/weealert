package io.rfsj.weealert;

/**
 * Builds a ringtone from a finished sound: an optional prefix sound, the sound itself (repeated), an
 * optional suffix sound, a filter over the whole thing and a silent gap at the end. Android repeats a
 * ringtone file by itself, with no pause, so the gap that separates the repeats is part of the file.
 * Pure Java.
 */
final class Ring {
    private static final long MAX_SAMPLES = 12000000;     // memory guard, about four minutes

    private static int ms(int ms, int rate) { return (int) Math.min(MAX_SAMPLES, (long) ms * rate / 1000); }

    private static float[] toFloat(short[] a) {
        float[] f = new float[a.length];
        for (int i = 0; i < a.length; i++) f[i] = a[i] / 32768f;
        return f;
    }

    /** The prefix or suffix at the ringtone's sample rate and volume. */
    private static float[] aux(Synth.Note n, int rate, Params p) {
        float[] d = n.rate == rate ? n.data.clone() : Synth.resample(n.data, n.rate, rate);
        float v = p.rtAuxVol / 100f;
        for (int i = 0; i < d.length; i++) d[i] *= v;
        return d;
    }

    static short[] compose(short[] core, int rate, Synth.Note pre, Synth.Note suf, Params p) {
        float[] c = toFloat(core);
        float[] a = pre == null || pre.data.length == 0 ? null : aux(pre, rate, p);
        float[] b = suf == null || suf.data.length == 0 ? null : aux(suf, rate, p);
        int pg = a == null ? 0 : ms(p.rtPreGap, rate), sg = b == null ? 0 : ms(p.rtSufGap, rate), rg = ms(p.rtRepGap, rate);
        long rep = Math.max(1, p.rtRepeat);
        while (rep > 1 && rep * c.length + (rep - 1) * (long) rg > MAX_SAMPLES) rep--;
        long total = (a == null ? 0 : a.length + (long) pg) + rep * c.length + (rep - 1) * (long) rg
                + (b == null ? 0 : sg + (long) b.length);
        if (total > MAX_SAMPLES) total = MAX_SAMPLES;
        float[] out = new float[(int) total];
        int pos = 0;
        if (a != null) { pos = copy(a, out, pos); pos += pg; }
        for (long r = 0; r < rep; r++) { pos = copy(c, out, pos); if (r < rep - 1) pos += rg; }
        if (b != null) { pos += sg; copy(b, out, pos); }
        filter(out, rate, p.rtFType, p.rtFFreq, p.rtFQ);
        float peak = 0;
        for (float v : out) peak = Math.max(peak, Math.abs(v));
        float g = peak > 0 ? (float) Math.pow(10, p.rtLevel / 20.0) / peak : 1;
        long len = total + ms(p.rtLoopGap, rate);
        if (p.rtTotal > 0) len = Math.max(len, ms(p.rtTotal, rate));
        len = Math.min(len, MAX_SAMPLES);
        short[] res = new short[(int) len];
        for (int i = 0; i < out.length && i < res.length; i++) res[i] = Synth.s16(out[i] * g);
        return res;
    }

    private static int copy(float[] src, float[] dst, int pos) {
        int n = Math.max(0, Math.min(src.length, dst.length - pos));
        if (n > 0) System.arraycopy(src, 0, dst, pos, n);
        return pos + src.length;
    }

    /** Biquad filter, in place. type 1 low-pass, 2 high-pass, 3 band-pass, 4 notch; anything else leaves it alone. */
    static void filter(float[] x, int rate, int type, double f0, double q) {
        if (type < 1 || type > 4 || f0 <= 0 || x.length == 0) return;
        f0 = Math.min(f0, rate * 0.49);
        if (q <= 0) q = 0.7071;
        double w = 2 * Math.PI * f0 / rate, cw = Math.cos(w), alpha = Math.sin(w) / (2 * q);
        double b0, b1, b2, a0 = 1 + alpha, a1 = -2 * cw, a2 = 1 - alpha;
        switch (type) {
            case 1: b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = b0; break;
            case 2: b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = b0; break;
            case 3: b0 = alpha; b1 = 0; b2 = -alpha; break;
            default: b0 = 1; b1 = -2 * cw; b2 = 1; break;
        }
        b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0;
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double in = x[i];
            double y = b0 * in + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = in; y2 = y1; y1 = y;
            x[i] = (float) y;
        }
    }
}
