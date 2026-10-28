package io.rfsj.weealert;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
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
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
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
import java.util.ArrayList;
import java.util.HashMap;

public class M extends Activity {
    private static final int RC_SRC = 1, RC_DIR = 2, RC_CON = 3, MIN_N = Gen.MIN_N, MAX_N = Gen.MAX_N;

    private SharedPreferences sp;
    private Params prm = new Params();
    private float[] raw;           // decoded or built-in source, before trimming
    private int rawRate;
    private volatile Gen.Result set;
    private volatile int gen;
    private int count = 8;
    private String mode = "patterns";                     // "patterns", "voices", "blips" or "cpat"
    private ArrayList<Contacts.C> people = new ArrayList<>();
    private LinearLayout srcBox, countBox, voicesBox;
    private Button modeBtn;
    private TextView modeNote;
    private EditText fVSyll, fVPitch, fVBreath, fVMax, fBNote, fCSep;
    private CheckBox cFirst;
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
        mode = sp.getString("mode", "patterns");
        if (!"voices".equals(mode) && !"blips".equals(mode) && !"cpat".equals(mode)) mode = "patterns";
        people = Contacts.parse(sp.getString("contacts", ""));
        if (Contacts.ensure(people, prm)) sp.edit().putString("contacts", Contacts.serialize(people)).apply();
        String d = sp.getString("dir", null);
        if (d != null) dirUri = Uri.parse(d);
        buildUi();
        fillFields(prm);
        applyMode();
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

        section(root, "Mode");
        modeBtn = btn("", v -> chooseMode());
        root.addView(modeBtn);
        modeNote = tv("", 12, 0xff888888, false);
        root.addView(modeNote);

        srcBox = new LinearLayout(this);
        srcBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(srcBox);
        section(srcBox, "Source sound");
        srcLabel = tv("", 16, 0xff9ad0ff, false);
        srcLabel.setOnClickListener(v -> previewSource());
        srcBox.addView(srcLabel);
        srcBox.addView(tv("tap the name to hear the source note", 12, 0xff777777, false));
        srcBox.addView(row(btn("Choose file", v -> pickSource()), btn("Built-in sounds", v -> pickBuiltin())));

        countBox = new LinearLayout(this);
        countBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(countBox);
        section(countBox, "How many sounds");
        countLabel = tv("", 20, Color.WHITE, true);
        countLabel.setGravity(Gravity.CENTER);
        countBox.addView(row(btn("-", v -> setCount(count - 1)), countLabel, btn("+", v -> setCount(count + 1))));
        countBox.addView(tv("Under about 12 stays easy to learn. Asking for more later keeps the earlier ones unchanged.", 12, 0xff888888, false));

        voicesBox = new LinearLayout(this);
        voicesBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(voicesBox);
        section(voicesBox, "Contacts");
        voicesBox.addView(row(btn("Add from contacts", v -> pickContact()), btn("Add by name", v -> addByName())));
        voicesBox.addView(tv("Tap a contact below to hear it, long-press for: say it as, new sound, remove. The contact picker hands the app only the contact you choose, and nothing leaves the phone. Each contact gets a sound as different as possible from the others. Contacts are shared by the three contact modes; blips and name patterns use the source sound chosen above.", 12, 0xff888888, false));

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

        adv.addView(tv("CONTACT MODES", 12, 0xff888888, true));
        fVSyll = field(adv, "Syllable length (ms)", false);
        note(adv, "Voices and blips, 100-300. Shorter is faster.");
        fVPitch = field(adv, "Centre pitch (Hz)", false);
        note(adv, "150-500. Contact voices spread around this.");
        fVBreath = field(adv, "Breathiness (%)", false);
        fVMax = field(adv, "Longest name (syllables)", false);
        cFirst = check(adv, "Only the first word of a name");
        fBNote = field(adv, "Blip length (ms)", false);
        note(adv, "Blips mode, 50-400.");
        fCSep = field(adv, "Name pattern separation", false);
        note(adv, "Name patterns mode, 0-40: how far apart two contacts' patterns must be before one moves to the nearest free pattern.");

        adv.addView(tv("FILES", 12, 0xff888888, true));
        fPrefix = field(adv, "File name prefix", true);
        cNum = check(adv, "Number the files (01-name.wav)");
        note(adv, "Numbers follow the pick order: the first few sounds are the most different from each other.");
        adv.addView(row(btn("Apply", v -> applyFields()), btn("Reset to defaults", v -> resetFields())));

        section(root, "Export");
        dirLabel = tv("", 15, Color.WHITE, false);
        root.addView(dirLabel);
        root.addView(row(btn("Choose folder", v -> pickDir()), btn("Export all", v -> export())));
        root.addView(btn("Add to system tones", v -> installTones()));
        status = tv("", 13, 0xff9ad0ff, false);
        status.setPadding(0, dp(8), 0, 0);
        root.addView(status);
        root.addView(tv("Then set each sound as a contact's tone in WhatsApp, Telegram, Signal, Contacts and so on. \"Add to system tones\" (Android 10+) puts them in the list the system tone pickers show. weealert is also a source in the system file picker: look for it in the sidebar, no export needed.", 12, 0xff888888, false));

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
        fVSyll.setText(String.valueOf(p.vSyll));
        fVPitch.setText(String.valueOf(p.vPitch));
        fVBreath.setText(String.valueOf(p.vBreath));
        fVMax.setText(String.valueOf(p.vMaxSyl));
        cFirst.setChecked(p.vFirst);
        fBNote.setText(String.valueOf(p.bNote));
        fCSep.setText(String.valueOf(p.cSep));
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
        q.vSyll = num(fVSyll, q.vSyll);
        q.vPitch = num(fVPitch, q.vPitch);
        q.vBreath = num(fVBreath, q.vBreath);
        q.vMaxSyl = num(fVMax, q.vMaxSyl);
        q.vFirst = cFirst.isChecked();
        q.bNote = num(fBNote, q.bNote);
        q.cSep = num(fCSep, q.cSep);
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
        final ArrayList<Integer> ids = new ArrayList<>();
        final ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < Synth.BUILTIN.length; i++)
            if (Synth.hasBuiltin(i)) { ids.add(i); names.add(Synth.BUILTIN[i]); }
        new AlertDialog.Builder(this).setTitle("Built-in sounds")
                .setItems(names.toArray(new String[0]), (d, which) -> useBuiltin(ids.get(which))).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        if (req == RC_CON) {
            String nm = contactName(u);
            if (nm != null) addPerson(nm);
            return;
        }
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
        if (!Synth.hasBuiltin(idx)) idx = 0;
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
        Gen.Result x = set;
        if (x == null || x.note == null) return;
        short[] d = new short[x.note.data.length];
        for (int i = 0; i < d.length; i++) d[i] = (short) Math.round(x.note.data[i] * 32767f);
        playPcm(d, x.rate);
    }

    // ---------------------------------------------------------------- generate

    private void rebuild() {
        final int g = ++gen;
        if (Gen.isContacts(mode)) {
            final Params vp = prm.copy();
            final String md = mode;
            final float[] raw0 = raw;
            final int rate0 = rawRate;
            final ArrayList<Contacts.C> snap = Contacts.parse(Contacts.serialize(people));
            if (("cpat".equals(md) || "blips".equals(md)) && raw0 == null) return;
            new Thread(() -> {
                Gen.Result x = "voices".equals(md) ? Gen.buildVoices(snap, vp)
                        : "blips".equals(md) ? Gen.buildBlips(snap, raw0, rate0, vp) : Gen.buildCpat(snap, raw0, rate0, vp);
                if (x == null) { say("The source sound is empty."); return; }
                if (g != gen) return;
                set = x;
                runOnUiThread(() -> { if (g == gen) showList(x); });
            }).start();
            return;
        }
        final float[] r = raw;
        final int rr = rawRate, n = count;
        final Params pr = prm.copy();
        if (r == null) return;
        new Thread(() -> {
            Gen.Result x = Gen.build(r, rr, pr, n);
            if (x == null) { say("The source sound is empty."); return; }
            if (g != gen) return;
            set = x;
            runOnUiThread(() -> { if (g == gen) showList(x); });
        }).start();
    }

    private void showList(Gen.Result x) {
        info.setText(x.info);
        list.removeAllViews();
        for (int i = 0; i < x.names.length; i++) {
            final int idx = i;
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(dp(4), dp(10), dp(4), dp(10));
            r.addView(tv(x.names[i], 18, Color.WHITE, false));
            r.addView(tv(x.details[i], 12, 0xff777777, false));
            r.setOnClickListener(v -> play(idx));
            if (Gen.isContacts(mode)) r.setOnLongClickListener(v -> { personMenu(idx); return true; });
            list.addView(r);
        }
    }

    // ---------------------------------------------------------------- modes and contacts

    private static final String[] MODES = {"patterns", "voices", "blips", "cpat"};
    private static final String[] MODE_TITLES = {"tone patterns", "contact voices", "contact blips", "contact name patterns"};
    private static final String[] MODE_NOTES = {
            "Short tone patterns built from a source sound, not tied to any contact.",
            "Each contact's name is spoken as a fast mumble, in a voice of its own.",
            "Each contact's name becomes a few blips of the source sound, one per syllable. Synthesised, not spoken.",
            "The tone patterns of the first mode, but each contact's pattern follows the shape of its name."};

    private int modeIndex() {
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equals(mode)) return i;
        return 0;
    }

    private void chooseMode() {
        new AlertDialog.Builder(this).setTitle("Mode")
                .setSingleChoiceItems(MODE_TITLES, modeIndex(), (d, which) -> {
                    mode = MODES[which];
                    sp.edit().putString("mode", mode).apply();
                    applyMode();
                    rebuild();
                    d.dismiss();
                }).show();
    }

    private void applyMode() {
        boolean c = Gen.isContacts(mode);
        srcBox.setVisibility("voices".equals(mode) ? View.GONE : View.VISIBLE);
        countBox.setVisibility("patterns".equals(mode) ? View.VISIBLE : View.GONE);
        voicesBox.setVisibility(c ? View.VISIBLE : View.GONE);
        modeBtn.setText("Mode: " + MODE_TITLES[modeIndex()] + " (tap to change)");
        modeNote.setText(MODE_NOTES[modeIndex()] + " Switching modes loses nothing.");
        info.setText("");
        list.removeAllViews();
        set = null;
    }

    private void pickContact() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI), RC_CON);
        } catch (ActivityNotFoundException e) {
            say("No contacts app found. Use Add by name.");
        }
    }

    private String contactName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{ContactsContract.Contacts.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException e) {
            say("Could not read that contact: " + e.getMessage());
        }
        return null;
    }

    private void addByName() {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint("name");
        new AlertDialog.Builder(this).setTitle("Add a name").setView(e)
                .setPositiveButton("Add", (d, w) -> addPerson(e.getText().toString()))
                .setNegativeButton("Cancel", null).show();
    }

    private void addPerson(String raw) {
        final String name = Contacts.clean(raw);
        if (name.isEmpty()) return;
        if (people.size() >= MAX_N) { say("That is the most contacts for now (" + MAX_N + ")."); return; }
        final Contacts.C c = new Contacts.C();
        c.name = name;
        people.add(c);
        final ArrayList<Contacts.C> snap = new ArrayList<>(people);
        final Params pr = prm.copy();
        say("Adding " + name + "...");
        new Thread(() -> {
            Contacts.init(snap, c, pr);          // picking sounds can take a moment with extreme settings
            runOnUiThread(() -> { savePeople(); say("Added " + name + "."); rebuild(); });
        }).start();
    }

    private void savePeople() { sp.edit().putString("contacts", Contacts.serialize(people)).apply(); }

    private void personMenu(final int i) {
        if (i < 0 || i >= people.size()) return;
        final Contacts.C c = people.get(i);
        new AlertDialog.Builder(this).setTitle(c.name)
                .setItems(new String[]{"Say it as...", "New sound", "Remove"}, (d, which) -> {
                    if (which == 0) sayAs(c);
                    else if (which == 1) { Contacts.reroll(people, c, mode, prm); savePeople(); rebuild(); }
                    else { people.remove(c); savePeople(); rebuild(); }
                }).show();
    }

    private void sayAs(final Contacts.C c) {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint("spell it the way it sounds");
        e.setText(c.say);
        new AlertDialog.Builder(this).setTitle("Say " + c.name + " as")
                .setMessage("Empty means use the name itself. Spelling it as it sounds fixes odd pronunciations, e.g. jorj for George.")
                .setView(e)
                .setPositiveButton("OK", (d, w) -> { c.say = Contacts.clean(e.getText().toString()); savePeople(); rebuild(); })
                .setNegativeButton("Cancel", null).show();
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
        Gen.Result x = set;
        if (x == null || i >= x.pcm.length) return;
        playPcm(x.pcm[i], x.rate);
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
        final Gen.Result x = set;
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
                    String file = Gen.fileName(x.prm, i, x.names[i]);
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

    // ---------------------------------------------------------------- system tones

    private void installTones() {
        final Gen.Result x = set;
        if (x == null) { say("Nothing generated yet."); return; }
        if (Build.VERSION.SDK_INT < 29) { say("Adding to system tones needs Android 10 or newer. Use Export or the file picker instead."); return; }
        say("Adding to system tones...");
        new Thread(() -> {
            try {
                for (int i = 0; i < x.names.length; i++)
                    Tones.add(this, Gen.fileName(x.prm, i, x.names[i]), x.pcm[i], x.rate);
                say("Added " + x.names.length + " sounds to the system tones (Notifications).");
            } catch (Exception e) {
                say("Could not add them: " + e.getMessage());
            }
        }).start();
    }
}
