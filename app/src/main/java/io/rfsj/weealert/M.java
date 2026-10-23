package io.rfsj.weealert;

import android.app.Activity;
import android.content.ContentResolver;
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
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;

public class M extends Activity {
    private static final int RC_SRC = 1, RC_DIR = 2, MIN_N = 2, MAX_N = 40;

    /** One generated set, swapped in as a whole so the UI thread never sees a half-built one. */
    private static final class Set {
        Codebook.Pattern[] pats;
        String[] names;
        short[][] pcm;
        int rate;
    }

    private SharedPreferences sp;
    private float[] src;           // prepared source note
    private int srcRate;
    private volatile Set set;
    private volatile int gen;
    private int count = 8;
    private Uri dirUri;
    private AudioTrack track;
    private TextView srcLabel, countLabel, dirLabel, status;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("weealert", MODE_PRIVATE);
        count = Math.max(MIN_N, Math.min(MAX_N, sp.getInt("n", 8)));
        String d = sp.getString("dir", null);
        if (d != null) dirUri = Uri.parse(d);
        buildUi();
        String s = sp.getString("src", null);
        if (s != null) loadSource(Uri.parse(s)); else useBuiltin();
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
        srcLabel = tv("", 15, Color.WHITE, false);
        root.addView(srcLabel);
        root.addView(row(btn("Choose file", v -> pickSource()), btn("Built-in bell", v -> useBuiltin())));

        section(root, "How many sounds");
        countLabel = tv("", 20, Color.WHITE, true);
        countLabel.setGravity(android.view.Gravity.CENTER);
        root.addView(row(btn("-", v -> setCount(count - 1)), countLabel, btn("+", v -> setCount(count + 1))));
        root.addView(tv("Under about 12 stays easy to learn. Asking for more later keeps the earlier ones unchanged.", 12, 0xff888888, false));

        section(root, "Sounds (tap to hear)");
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

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

    private void useBuiltin() {
        sp.edit().remove("src").apply();
        src = Synth.bell();
        srcRate = Synth.BELL_RATE;
        srcLabel.setText("Built-in bell");
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
                float[] prepared = Synth.prepare(p.data, p.rate);
                if (prepared.length < 64) throw new IOException("the sound is silent");
                src = prepared;
                srcRate = p.rate;
                runOnUiThread(() -> srcLabel.setText(name));
                say("");
                rebuild();
            } catch (Exception e) {
                say("Could not use that file: " + e.getMessage());
                sp.edit().remove("src").apply();
                runOnUiThread(this::useBuiltin);
            }
        }).start();
    }

    // ---------------------------------------------------------------- generate

    private void rebuild() {
        final int g = ++gen;
        final float[] s = src;
        final int rate = srcRate, n = count;
        new Thread(() -> {
            Set x = new Set();
            x.pats = Codebook.pick(n);
            x.names = Names.forPatterns(x.pats);
            x.rate = rate;
            x.pcm = new short[x.pats.length][];
            for (int i = 0; i < x.pats.length; i++) x.pcm[i] = Synth.render(s, rate, x.pats[i]);
            if (g != gen) return;
            set = x;
            runOnUiThread(() -> { if (g == gen) showList(x); });
        }).start();
    }

    private void showList(Set x) {
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
        stopPlay();
        short[] d = x.pcm[i];
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(x.rate)
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
                    String file = x.names[i] + ".wav";
                    Uri u;
                    if (have.containsKey(file)) u = DocumentsContract.buildDocumentUriUsingTree(tree, have.get(file));
                    else u = DocumentsContract.createDocument(cr, parent, "audio/x-wav", file);
                    if (u == null) throw new IOException("could not create " + file);
                    try (OutputStream o = cr.openOutputStream(u, "wt")) {
                        if (o == null) throw new IOException("could not write " + file);
                        o.write(Synth.wav(x.pcm[i], x.rate));
                    }
                }
                say("Exported " + x.names.length + " files.");
            } catch (Exception e) {
                say("Export failed: " + e.getMessage());
            }
        }).start();
    }
}
