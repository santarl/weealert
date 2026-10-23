package io.rfsj.weealert;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;

public class M extends Activity {
    private static final int RC_SRC = 1, RC_DIR = 2, MIN_N = 2, MAX_N = 40;

    /** One generated set, swapped in as a whole so the UI thread never sees a half-built one. */
    private static final class Set {
        Synth.Note note;
        Codebook.Pattern[] pats;
        String[] names;
        short[][] pcm;
        Params prm;
        String info;
    }

    private SharedPreferences sp;
    private Params prm = new Params();
    private float[] raw;           // decoded or built-in source, before trimming
    private int rawRate;
    private volatile Set set;
    private volatile int gen;
    private int count = 8;
    private Uri dirUri;
    private AudioTrack track;
    private TextView srcLabel, countLabel, dirLabel, status, info;
    private LinearLayout list, adv;
    private EditText fMax, fNote, fGaps, fMinN, fMaxN, fSemis, fRw, fSeed, fFade, fFall, fLevel, fRate, fPrefix;
    private CheckBox cCut, cRev, cNum;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("weealert", MODE_PRIVATE);
        count = Math.max(MIN_N, Math.min(MAX_N, sp.getInt("n", 8)));
        prm = Params.parse(sp.getString("params", ""));
        String d = sp.getString("dir", null);
        if (d != null) dirUri = Uri.parse(d);
        buildUi();
        fillFields(prm);
        String s = sp.getString("src", null);
        if (s != null) loadSource(Uri.parse(s)); else useBuiltin(sp.getInt("builtin", 0));
        refreshDirLabel();
    }

    @Override
    protected void onDestroy() {
        stopPlay();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- ui

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView tv(String t, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private Button btn(String t, View.OnClickListener l) {
        Button x = new Button(this);
        x.setText(t);
        x.setAllCaps(false);
        x.setOnClickListener(l);
        return x;
    }

    private LinearLayout row(View... vs) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : vs) r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return r;
    }

    private void section(LinearLayout root, String t) {
        TextView h = tv(t.toUpperCase(), 12, 0xff888888, true);
        h.setPadding(0, dp(22), 0, dp(4));
        root.addView(h);
    }

    private EditText field(LinearLayout parent, String label, boolean text) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = tv(label, 14, 0xffcccccc, false);
        EditText e = new EditText(this);
        e.setTextColor(Color.WHITE);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setSingleLine(true);
        e.setInputType(text ? InputType.TYPE_CLASS_TEXT : InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
        r.addView(e, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(r);
        return e;
    }

    private CheckBox check(LinearLayout parent, String label) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(0xffcccccc);
        parent.addView(c);
        return c;
    }

    private void note(LinearLayout parent, String t) {
        TextView v = tv(t, 12, 0xff777777, false);
        v.setPadding(0, 0, 0, dp(6));
        parent.addView(v);
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        sv.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(28));
        sv.addView(root);

        root.addView(tv("weealert", 26, Color.WHITE, true));
        root.addView(tv("a different sound for each contact, so you know who it is without looking", 13, 0xff999999, false));

        section(root, "Source sound");
        srcLabel = tv("", 16, 0xff9ad0ff, false);
        srcLabel.setOnClickListener(v -> previewSource());
        root.addView(srcLabel);
        root.addView(tv("tap the name to hear the source note", 12, 0xff777777, false));
        root.addView(row(btn("Choose file", v -> pickSource()), btn("Built-in sounds", v -> pickBuiltin())));

        section(root, "How many sounds");
        countLabel = tv("", 20, Color.WHITE, true);
        countLabel.setGravity(Gravity.CENTER);
        root.addView(row(btn("-", v -> setCount(count - 1)), countLabel, btn("+", v -> setCount(count + 1))));
        root.addView(tv("Under about 12 stays easy to learn. Asking for more later keeps the earlier ones unchanged.", 12, 0xff888888, false));

        section(root, "Sounds (tap to hear)");
        info = tv("", 12, 0xff9ad0ff, false);
        info.setPadding(0, 0, 0, dp(4));
        root.addView(info);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        section(root, "Settings");
        Button advBtn = btn("Show / hide advanced settings", v -> adv.setVisibility(adv.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        root.addView(advBtn);
        adv = new LinearLayout(this);
        adv.setOrientation(LinearLayout.VERTICAL);
        adv.setVisibility(View.GONE);
        root.addView(adv);

        adv.addView(tv("TIMING", 12, 0xff888888, true));
        fMax = field(adv, "Max length of a whole sound (ms)", false);
        note(adv, "Hard cap, 300-5000. Patterns that would run longer are left out.");
        fNote = field(adv, "Length of each note (ms)", false);
        note(adv, "How much of the source is used per note, 50-1500.");
        fGaps = field(adv, "Gaps between notes (ms)", true);
        note(adv, "Up to 4 values, multiples of 50, e.g. 200 300 400. Each gap in a pattern is one of these.");
        fMinN = field(adv, "Fewest notes per sound", false);
        fMaxN = field(adv, "Most notes per sound", false);
        cCut = check(adv, "Cut each note when the next one starts");

        adv.addView(tv("PITCH AND RHYTHM", 12, 0xff888888, true));
        fSemis = field(adv, "Pitch offsets (semitones)", true);
        note(adv, "Up to 6 values from -24 to 24, e.g. -5 -2 0 3 7. Wider steps are easier to tell apart.");
        fRw = field(adv, "Rhythm weight", false);
        note(adv, "How much a rhythm difference counts against 1 semitone, 1-20. Higher spreads sounds mostly by rhythm.");
        fSeed = field(adv, "Variation seed", false);
        note(adv, "0 is the standard set. Any other number gives a different, equally spread set. Names follow the pattern, not the seed.");

        adv.addView(tv("SHAPE AND LEVEL", 12, 0xff888888, true));
        fFade = field(adv, "Fade-out (ms)", false);
        fFall = field(adv, "Each note quieter by (%)", false);
        fLevel = field(adv, "Peak level (dBFS)", false);
        fRate = field(adv, "Sample rate (Hz, 0 = as source)", false);
        cRev = check(adv, "Play the source backwards");

        adv.addView(tv("FILES", 12, 0xff888888, true));
        fPrefix = field(adv, "File name prefix", true);
        cNum = check(adv, "Number the files (01-name.wav)");
        note(adv, "Numbers follow the pick order: the first few sounds are the most different from each other.");
        adv.addView(row(btn("Apply", v -> applyFields()), btn("Reset to defaults", v -> resetFields())));

        section(root, "Export");
        dirLabel = tv("", 15, Color.WHITE, false);
        root.addView(dirLabel);
        root.addView(row(btn("Choose folder", v -> pickDir()), btn("Export all", v -> export())));
        status = tv("", 13, 0xff9ad0ff, false);
        status.setPadding(0, dp(8), 0, 0);
        root.addView(status);
        root.addView(tv("Then set each file as a contact's tone in WhatsApp, Telegram, Signal, Contacts and so on. Picking the Notifications folder helps system tone pickers find them.", 12, 0xff888888, false));

        setContentView(sv);
        countLabel.setText(String.valueOf(count));
    }

    private void say(String s) { runOnUiThread(() -> status.setText(s)); }

    private void refreshDirLabel() {
        String t = "No folder chosen";
        if (dirUri != null) {
            try { t = Uri.decode(DocumentsContract.getTreeDocumentId(dirUri)); } catch (RuntimeException e) { t = dirUri.toString(); }
        }
        dirLabel.setText(t);
    }

    private void setCount(int n) {
        n = Math.max(MIN_N, Math.min(MAX_N, n));
        if (n == count) return;
        count = n;
        sp.edit().putInt("n", n).apply();
        countLabel.setText(String.valueOf(n));
        rebuild();
    }

    // ---------------------------------------------------------------- settings

    private void fillFields(Params p) {
        fMax.setText(String.valueOf(p.maxMs));
        fNote.setText(String.valueOf(p.noteMs));
        fGaps.setText(Params.list(p.gaps));
        fMinN.setText(String.valueOf(p.minNotes));
        fMaxN.setText(String.valueOf(p.maxNotes));
        fSemis.setText(Params.list(p.semis));
        fRw.setText(String.valueOf(p.rhythmW));
        fSeed.setText(String.valueOf(p.seed));
        fFade.setText(String.valueOf(p.fadeMs));
        fFall.setText(String.valueOf(p.falloff));
        fLevel.setText(String.valueOf(p.levelDb));
        fRate.setText(String.valueOf(p.rate));
        fPrefix.setText(p.prefix);
        cCut.setChecked(p.cut);
        cRev.setChecked(p.reverse);
        cNum.setChecked(p.numbered);
    }

    private static int num(EditText e, int dflt) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (NumberFormatException x) { return dflt; }
    }

    private Params readFields() {
        Params q = prm.copy();
        q.maxMs = num(fMax, q.maxMs);
        q.noteMs = num(fNote, q.noteMs);
        q.gaps = Params.parseList(fGaps.getText().toString());
        q.minNotes = num(fMinN, q.minNotes);
        q.maxNotes = num(fMaxN, q.maxNotes);
        q.semis = Params.parseList(fSemis.getText().toString());
        q.rhythmW = num(fRw, q.rhythmW);
        q.seed = num(fSeed, q.seed);
        q.fadeMs = num(fFade, q.fadeMs);
        q.falloff = num(fFall, q.falloff);
        q.levelDb = num(fLevel, q.levelDb);
        q.rate = num(fRate, q.rate);
        q.prefix = fPrefix.getText().toString();
        q.cut = cCut.isChecked();
        q.reverse = cRev.isChecked();
        q.numbered = cNum.isChecked();
        return q;
    }

    private void applyFields() {
        Params q = readFields();
        boolean changed = q.normalize();
        commit(q);
        say(changed ? "Applied. Some values were outside their limits and were adjusted." : "Applied.");
    }

    private void resetFields() {
        commit(new Params());
        say("Settings reset to defaults.");
    }

    private void commit(Params q) {
        prm = q;
        sp.edit().putString("params", q.serialize()).apply();
        fillFields(q);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(list.getWindowToken(), 0);
        rebuild();
    }

    // ---------------------------------------------------------------- source

    private void pickSource() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, RC_SRC);
    }

    private void pickDir() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, RC_DIR);
    }

    private void pickBuiltin() {
        new AlertDialog.Builder(this).setTitle("Built-in sounds")
                .setItems(Synth.BUILTIN, (d, which) -> useBuiltin(which)).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(u,
                    req == RC_DIR ? Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            : Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (RuntimeException ignored) { }
        if (req == RC_SRC) {
            sp.edit().putString("src", u.toString()).apply();
            loadSource(u);
        } else if (req == RC_DIR) {
            dirUri = u;
            sp.edit().putString("dir", u.toString()).apply();
            refreshDirLabel();
        }
    }

    private void useBuiltin(int idx) {
        if (idx < 0 || idx >= Synth.BUILTIN.length) idx = 0;
        sp.edit().remove("src").putInt("builtin", idx).apply();
        raw = Synth.builtin(idx);
        rawRate = Synth.BUILTIN_RATE;
        srcLabel.setText("Built-in: " + Synth.BUILTIN[idx]);
        rebuild();
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (RuntimeException ignored) { }
        return "chosen file";
    }

    private void loadSource(Uri u) {
        say("Reading the source sound...");
        final String name = displayName(u);
        new Thread(() -> {
            try {
                Decode.Pcm p = Decode.read(this, u);
                float peak = 0;
                for (float v : p.data) peak = Math.max(peak, Math.abs(v));
                if (peak < 1e-4f) throw new IOException("the sound is silent");
                raw = p.data;
                rawRate = p.rate;
                runOnUiThread(() -> srcLabel.setText(name));
                say("");
                rebuild();
            } catch (Exception e) {
                say("Could not use that file: " + e.getMessage());
                sp.edit().remove("src").apply();
                runOnUiThread(() -> useBuiltin(sp.getInt("builtin", 0)));
            }
        }).start();
    }

    private void previewSource() {
        Set x = set;
        if (x == null) return;
        short[] d = new short[x.note.data.length];
        for (int i = 0; i < d.length; i++) d[i] = (short) Math.round(x.note.data[i] * 32767f);
        playPcm(d, x.note.rate);
    }

    // ---------------------------------------------------------------- generate

    private void rebuild() {
        final int g = ++gen;
        final float[] r = raw;
        final int rr = rawRate, n = count;
        final Params pr = prm.copy();
        if (r == null) return;
        new Thread(() -> {
            Synth.Note note = Synth.prepare(r, rr, pr);
            if (note.data.length < 64) { say("The source sound is empty."); return; }
            int srcMs = (int) (1000L * note.data.length / note.rate);
            Set x = new Set();
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
            if (g != gen) return;
            set = x;
            runOnUiThread(() -> { if (g == gen) showList(x); });
        }).start();
    }

    private void showList(Set x) {
        info.setText(x.info);
        list.removeAllViews();
        for (int i = 0; i < x.names.length; i++) {
            final int idx = i;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(dp(4), dp(10), dp(4), dp(10));
            r.addView(tv(x.names[i], 18, Color.WHITE, false));
            r.addView(tv(x.pats[i].describe(), 12, 0xff777777, false));
            r.setOnClickListener(v -> play(idx));
            list.addView(r);
        }
    }

    // ---------------------------------------------------------------- playback

    private void stopPlay() {
        if (track != null) {
            try { track.stop(); } catch (RuntimeException ignored) { }
            track.release();
            track = null;
        }
    }

    private void play(int i) {
        Set x = set;
        if (x == null || i >= x.pcm.length) return;
        playPcm(x.pcm[i], x.note.rate);
    }

    private void playPcm(short[] d, int rate) {
        stopPlay();
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(d.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            t.write(d, 0, d.length);
            t.play();
            track = t;
        } catch (RuntimeException e) {
            say("Could not play: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- export

    private void export() {
        final Set x = set;
        final Uri tree = dirUri;
        if (x == null) { say("Nothing generated yet."); return; }
        if (tree == null) { say("Choose a folder first."); return; }
        say("Exporting...");
        new Thread(() -> {
            try {
                ContentResolver cr = getContentResolver();
                String treeId = DocumentsContract.getTreeDocumentId(tree);
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, treeId);
                HashMap<String, String> have = new HashMap<>();
                Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
                try (Cursor c = cr.query(kids, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                    while (c != null && c.moveToNext()) have.put(c.getString(1), c.getString(0));
                }
                for (int i = 0; i < x.names.length; i++) {
                    String file = (x.prm.numbered ? String.format(Locale.US, "%02d-", i + 1) : "")
                            + x.prm.prefix + x.names[i] + ".wav";
                    Uri u;
                    if (have.containsKey(file)) u = DocumentsContract.buildDocumentUriUsingTree(tree, have.get(file));
                    else u = DocumentsContract.createDocument(cr, parent, "audio/x-wav", file);
                    if (u == null) throw new IOException("could not create " + file);
                    try (OutputStream o = cr.openOutputStream(u, "wt")) {
                        if (o == null) throw new IOException("could not write " + file);
                        o.write(Synth.wav(x.pcm[i], x.note.rate));
                    }
                }
                say("Exported " + x.names.length + " files.");
            } catch (Exception e) {
                say("Export failed: " + e.getMessage());
            }
        }).start();
    }
}
