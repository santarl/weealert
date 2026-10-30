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
import java.util.LinkedHashMap;
import java.util.Map;

public class M extends Activity {
    private static final int RC_SRC = 1, RC_DIR = 2, RC_CON = 3, RC_PRE = 4, RC_SUF = 5;

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
    private CheckBox cFirst, cRingPrev;
    private EditText fRtPreGap, fRtSufGap, fRtAuxMs, fRtAuxVol, fRtRepeat, fRtRepGap, fRtLoop, fRtTotal, fRtFType, fRtFFreq, fRtFQ, fRtLevel;
    private Button btnPre, btnSuf;
    private EditText fPbPitch, fPbSpeed, fPbLen, fPbVDur, fPbCDur, fPbCLen, fPbTable, fRtText;
    private CheckBox cPbFx;
    private LinkedHashMap<String, String> ov = new LinkedHashMap<>();   // per-sound tuning of anonymous sounds, by pattern key
    private int loopingIdx = -1;
    private String auxKey = "";
    private Synth.Note auxPre, auxSuf;
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
        count = Math.max(1, sp.getInt("n", 8));
        ov = Over.parseAll(sp.getString("ov", ""));
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

    private EditText fieldD(LinearLayout parent, String label) {
        EditText e = field(parent, label, false);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED | InputType.TYPE_NUMBER_FLAG_DECIMAL);
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
        countLabel.setOnClickListener(v -> askCount());
        countBox.addView(row(btn("-", v -> setCount(count - 1)), countLabel, btn("+", v -> setCount(count + 1))));
        countBox.addView(tv("Tap the number to type any amount. Under about 12 stays easy to learn. Asking for more later keeps the earlier ones unchanged.", 12, 0xff888888, false));

        voicesBox = new LinearLayout(this);
        voicesBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(voicesBox);
        section(voicesBox, "Contacts");
        voicesBox.addView(row(btn("Add from contacts", v -> pickContact()), btn("Add by name", v -> addByName())));
        voicesBox.addView(tv("Tap a contact below to hear it, long-press for: say it as, new sound, remove. The contact picker hands the app only the contact you choose, and nothing leaves the phone. Each contact gets a sound as different as possible from the others. Contacts are shared by the three contact modes; blips and name patterns use the source sound chosen above.", 12, 0xff888888, false));

        section(root, "Sounds (tap to hear, long-press to tune)");
        cRingPrev = check(root, "Preview as ringtone (loops until you tap it again)");
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
        note(adv, "Hard cap. Patterns that would run longer are left out. Any value works.");
        fNote = field(adv, "Length of each note (ms)", false);
        note(adv, "How much of the source is used per note.");
        fGaps = field(adv, "Gaps between notes (ms)", true);
        note(adv, "Any number of values in any milliseconds, e.g. 200 300 400 or 335 120. Each gap in a pattern is one of these. If the settings allow far too many combinations, an even subset is used so memory stays safe.");
        fMinN = field(adv, "Fewest notes per sound", false);
        fMaxN = field(adv, "Most notes per sound", false);
        cCut = check(adv, "Cut each note when the next one starts");

        adv.addView(tv("PITCH AND RHYTHM", 12, 0xff888888, true));
        fSemis = field(adv, "Pitch offsets (semitones)", true);
        note(adv, "Any values, decimals work: -5 -2 0 3 7 or -3.5 0 2.25. Wider steps are easier to tell apart.");
        fRw = field(adv, "Rhythm weight", false);
        note(adv, "How much a rhythm difference counts against 1 semitone. Higher spreads sounds mostly by rhythm.");
        fSeed = field(adv, "Variation seed", false);
        note(adv, "0 is the standard set. Any other number gives a different, equally spread set. Names follow the pattern, not the seed.");

        adv.addView(tv("SHAPE AND LEVEL", 12, 0xff888888, true));
        fFade = field(adv, "Fade-out (ms)", false);
        fFall = fieldD(adv, "Each note quieter by (%)");
        fLevel = fieldD(adv, "Peak level (dBFS)");
        fRate = field(adv, "Sample rate (Hz, 0 = as source)", false);
        cRev = check(adv, "Play the source backwards");

        adv.addView(tv("CONTACT MODES", 12, 0xff888888, true));
        fVSyll = field(adv, "Syllable length (ms)", false);
        note(adv, "Voices and blips. Shorter is faster.");
        fVPitch = field(adv, "Centre pitch (Hz)", false);
        note(adv, "Contact voices spread around this.");
        fVBreath = field(adv, "Breathiness (%)", false);
        fVMax = field(adv, "Longest name (syllables)", false);
        cFirst = check(adv, "Only the first word of a name");
        fBNote = field(adv, "Blip length (ms)", false);
        note(adv, "Blips mode.");
        fCSep = field(adv, "Name pattern separation", false);
        note(adv, "Name patterns mode: how far apart two contacts' patterns must be before one moves to the nearest free pattern.");

        adv.addView(tv("PHONEME BLIPS (one consistent voice, every sound of a name has its own pitch)", 12, 0xff888888, true));
        fPbPitch = fieldD(adv, "Voice pitch (semitones)");
        fPbSpeed = field(adv, "Speed of the rhythm (%)", false);
        fPbLen = field(adv, "Vowel blip length (ms)", false);
        fPbVDur = field(adv, "Time from a vowel to the next sound (ms)", false);
        fPbCDur = field(adv, "Time from a consonant to the next sound (ms)", false);
        fPbCLen = field(adv, "Consonant blip length (%)", false);
        cPbFx = check(adv, "Clicks, hisses, soft starts and glides on consonants");
        fPbTable = field(adv, "Phoneme table", true);
        fPbTable.setSingleLine(false);
        note(adv, "Pitch of every sound in semitones, relative to the voice pitch: vowels a e i o u @ (schwa), consonants p b t d k g C(ch) J(j) f v s z S(sh) Z(zh) T(th) h m n N(ng) l r w y. An entry can also carry a length and a level in percent: s=8/80/70. Edit only what you want to change.");

        adv.addView(tv("RINGTONE (when an app asks for a ringtone or alarm tone)", 12, 0xff888888, true));
        note(adv, "A ringtone is the sound with an optional prefix and suffix around it, an optional filter over all of it, and a silent gap at the end. Android repeats the file by itself, with no pause, so the gap is part of the file.");
        btnPre = btn("", v -> pickAux(true));
        adv.addView(btnPre);
        fRtPreGap = field(adv, "Gap after the prefix (ms)", false);
        btnSuf = btn("", v -> pickAux(false));
        adv.addView(btnSuf);
        fRtSufGap = field(adv, "Gap before the suffix (ms)", false);
        fRtAuxMs = field(adv, "Prefix and suffix length (ms)", false);
        fRtAuxVol = field(adv, "Prefix and suffix volume (%)", false);
        fRtRepeat = field(adv, "Times the sound plays per ring", false);
        fRtRepGap = field(adv, "Gap between those repeats (ms)", false);
        fRtLoop = field(adv, "Gap at the end, between loops (ms)", false);
        fRtTotal = field(adv, "Make one ring at least this long (ms, 0 = natural)", false);
        fRtText = field(adv, "Typed prefix/suffix text is made as: 0 phoneme blips, 1 contact voice, 2 name blips", false);
        fRtFType = field(adv, "Filter: 0 off, 1 low-pass, 2 high-pass, 3 band-pass, 4 notch", false);
        fRtFFreq = field(adv, "Filter frequency (Hz)", false);
        fRtFQ = fieldD(adv, "Filter resonance (Q)");
        fRtLevel = fieldD(adv, "Ringtone peak level (dBFS)");

        adv.addView(tv("FILES", 12, 0xff888888, true));
        fPrefix = field(adv, "File name prefix", true);
        cNum = check(adv, "Number the files (01-name.wav)");
        note(adv, "Numbers follow the pick order: the first few sounds are the most different from each other.");
        adv.addView(row(btn("Apply", v -> applyFields()), btn("Reset to defaults", v -> resetFields())));

        section(root, "Export");
        dirLabel = tv("", 15, Color.WHITE, false);
        root.addView(dirLabel);
        root.addView(row(btn("Choose folder", v -> pickDir()), btn("Export all", v -> exportFiles(false))));
        root.addView(btn("Export as ringtones (name-ringtone.wav)", v -> exportFiles(true)));
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
        n = Math.max(1, n);
        if (n == count) return;
        count = n;
        sp.edit().putInt("n", n).apply();
        countLabel.setText(String.valueOf(n));
        rebuild();
    }

    private void askCount() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(count));
        new AlertDialog.Builder(this).setTitle("How many sounds").setView(e)
                .setPositiveButton("OK", (d, w) -> {
                    try { setCount((int) Math.min(100000, Math.round(Double.parseDouble(e.getText().toString().trim())))); }
                    catch (NumberFormatException x) { say("Type a whole number."); }
                })
                .setNegativeButton("Cancel", null).show();
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
        fFall.setText(Params.fmt(p.falloff));
        fLevel.setText(Params.fmt(p.levelDb));
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
        fRtPreGap.setText(String.valueOf(p.rtPreGap));
        fRtSufGap.setText(String.valueOf(p.rtSufGap));
        fRtAuxMs.setText(String.valueOf(p.rtAuxMs));
        fRtAuxVol.setText(String.valueOf(p.rtAuxVol));
        fRtRepeat.setText(String.valueOf(p.rtRepeat));
        fRtRepGap.setText(String.valueOf(p.rtRepGap));
        fRtLoop.setText(String.valueOf(p.rtLoopGap));
        fRtTotal.setText(String.valueOf(p.rtTotal));
        fRtFType.setText(String.valueOf(p.rtFType));
        fRtFFreq.setText(String.valueOf(p.rtFFreq));
        fRtFQ.setText(Params.fmt(p.rtFQ));
        fRtLevel.setText(Params.fmt(p.rtLevel));
        fRtText.setText(String.valueOf(p.rtTextStyle));
        fPbPitch.setText(Params.fmt(p.pbPitch));
        fPbSpeed.setText(String.valueOf(p.pbSpeed));
        fPbLen.setText(String.valueOf(p.pbLen));
        fPbVDur.setText(String.valueOf(p.pbVowelDur));
        fPbCDur.setText(String.valueOf(p.pbConsDur));
        fPbCLen.setText(String.valueOf(p.pbConsLen));
        cPbFx.setChecked(p.pbEffects);
        fPbTable.setText(p.pbTable.isEmpty() ? PBlip.DEFAULT_TABLE : p.pbTable);
        updateAuxButtons();
    }

    private static int num(EditText e, int dflt) {
        try { return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, Math.round(Double.parseDouble(e.getText().toString().trim())))); }
        catch (NumberFormatException x) { return dflt; }
    }

    private static double dbl(EditText e, double dflt) {
        try { return Double.parseDouble(e.getText().toString().trim()); } catch (NumberFormatException x) { return dflt; }
    }

    private Params readFields() {
        Params q = prm.copy();
        q.maxMs = num(fMax, q.maxMs);
        q.noteMs = num(fNote, q.noteMs);
        q.gaps = Params.parseList(fGaps.getText().toString());
        q.minNotes = num(fMinN, q.minNotes);
        q.maxNotes = num(fMaxN, q.maxNotes);
        q.semis = Params.parseDoubles(fSemis.getText().toString());
        q.rhythmW = num(fRw, q.rhythmW);
        q.seed = num(fSeed, q.seed);
        q.fadeMs = num(fFade, q.fadeMs);
        q.falloff = dbl(fFall, q.falloff);
        q.levelDb = dbl(fLevel, q.levelDb);
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
        q.rtPreGap = num(fRtPreGap, q.rtPreGap);
        q.rtSufGap = num(fRtSufGap, q.rtSufGap);
        q.rtAuxMs = num(fRtAuxMs, q.rtAuxMs);
        q.rtAuxVol = num(fRtAuxVol, q.rtAuxVol);
        q.rtRepeat = num(fRtRepeat, q.rtRepeat);
        q.rtRepGap = num(fRtRepGap, q.rtRepGap);
        q.rtLoopGap = num(fRtLoop, q.rtLoopGap);
        q.rtTotal = num(fRtTotal, q.rtTotal);
        q.rtFType = num(fRtFType, q.rtFType);
        q.rtFFreq = num(fRtFFreq, q.rtFFreq);
        q.rtFQ = dbl(fRtFQ, q.rtFQ);
        q.rtLevel = dbl(fRtLevel, q.rtLevel);
        q.rtTextStyle = num(fRtText, q.rtTextStyle);
        q.pbPitch = dbl(fPbPitch, q.pbPitch);
        q.pbSpeed = num(fPbSpeed, q.pbSpeed);
        q.pbLen = num(fPbLen, q.pbLen);
        q.pbVowelDur = num(fPbVDur, q.pbVowelDur);
        q.pbConsDur = num(fPbCDur, q.pbConsDur);
        q.pbConsLen = num(fPbCLen, q.pbConsLen);
        q.pbEffects = cPbFx.isChecked();
        String tbl = fPbTable.getText().toString().trim().replaceAll("\\s+", " ");
        q.pbTable = tbl.equals(PBlip.DEFAULT_TABLE) ? "" : tbl;
        return q;
    }

    private void applyFields() {
        Params q = readFields();
        boolean changed = q.normalize();
        commit(q);
        say(changed ? "Applied. Some values could not work at all (negative or empty) and were reset." : "Applied.");
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
        if (req == RC_PRE || req == RC_SUF) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) { }
            setAux(req == RC_PRE, "u:" + u);
            return;
        }
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
        for (int i = 0; i < d.length; i++) d[i] = Synth.s16(x.note.data[i]);
        playPcm(d, x.rate);
    }

    // ---------------------------------------------------------------- generate

    private void rebuild() {
        final int g = ++gen;
        auxKey = "";
        if (Gen.isContacts(mode)) {
            final Params vp = prm.copy();
            final String md = mode;
            final float[] raw0 = raw;
            final int rate0 = rawRate;
            final ArrayList<Contacts.C> snap = Contacts.parse(Contacts.serialize(people));
            if (!"voices".equals(md) && raw0 == null) return;
            new Thread(() -> {
                Gen.Result x = "voices".equals(md) ? Gen.buildVoices(snap, vp)
                        : "blips".equals(md) ? Gen.buildBlips(snap, raw0, rate0, vp)
                        : "pblips".equals(md) ? Gen.buildPBlips(snap, raw0, rate0, vp) : Gen.buildCpat(snap, raw0, rate0, vp);
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
        final LinkedHashMap<String, String> ovSnap = new LinkedHashMap<>(ov);
        if (r == null) return;
        new Thread(() -> {
            Gen.Result x = Gen.build(r, rr, pr, n, ovSnap);
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
            r.setOnLongClickListener(v -> { soundMenu(idx); return true; });
            list.addView(r);
        }
    }

    // ---------------------------------------------------------------- modes and contacts

    private static final String[] MODES = {"patterns", "voices", "blips", "cpat", "pblips"};
    private static final String[] MODE_TITLES = {"tone patterns", "contact voices", "contact blips", "contact name patterns", "contact phoneme blips"};
    private static final String[] MODE_NOTES = {
            "Short tone patterns built from a source sound, not tied to any contact.",
            "Each contact's name is spoken as a fast mumble, in a voice of its own.",
            "Each contact's name becomes a few blips of the source sound, one per syllable. Synthesised, not spoken.",
            "The tone patterns of the first mode, but each contact's pattern follows the shape of its name.",
            "One consistent voice for everyone: every sound of the name has its own pitch and the rhythm follows the pronunciation, so a name is almost readable by ear."};

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

    private boolean tuned(int i) {
        Gen.Result x = set;
        if (Gen.isContacts(mode)) return i < people.size() && Over.has(people.get(i).ov);
        return x != null && i < x.pats.length && ov.containsKey(x.pats[i].key());
    }

    private void saveOv() { sp.edit().putString("ov", Over.serializeAll(ov)).apply(); }

    private void soundMenu(final int i) {
        final Gen.Result x = set;
        if (x == null || i < 0 || i >= x.names.length) return;
        final boolean contacts = Gen.isContacts(mode);
        if (contacts && i >= people.size()) return;
        final boolean t = tuned(i);
        final ArrayList<String> items = new ArrayList<>();
        items.add("Tune this sound...");
        if (t) items.add("Reset tuning");
        if (contacts) { items.add("Say it as..."); if (!"pblips".equals(mode)) items.add("New sound"); items.add("Remove"); }
        new AlertDialog.Builder(this).setTitle(x.names[i])
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    String it = items.get(which);
                    if (it.startsWith("Tune")) tuneSound(i);
                    else if (it.startsWith("Reset")) {
                        if (contacts) { people.get(i).ov = ""; savePeople(); } else { ov.remove(x.pats[i].key()); saveOv(); }
                        rebuild();
                    } else if (it.startsWith("Say")) sayAs(people.get(i));
                    else if (it.startsWith("New")) {
                        Contacts.C c = people.get(i);
                        Contacts.reroll(people, c, mode, prm);
                        c.ov = Over.withoutContent(c.ov);
                        savePeople();
                        rebuild();
                    } else { people.remove(i); savePeople(); rebuild(); }
                }).show();
    }

    /** One dialog with every value of this sound; whatever you change is kept for this sound only. */
    private void tuneSound(final int i) {
        final Gen.Result x = set;
        if (x == null) return;
        final boolean contacts = Gen.isContacts(mode);
        final Contacts.C c = contacts && i < people.size() ? people.get(i) : null;
        if (contacts && c == null) return;
        final String okey = contacts ? null : x.pats[i].key();
        final LinkedHashMap<String, String> kv = Over.parse(contacts ? c.ov : ov.get(okey));
        final Over.F[] defs = Over.fields(mode);
        final HashMap<String, String> base = new HashMap<>(prm.toMap());
        if (contacts) {
            base.put("pitch", Params.fmt(c.pitch)); base.put("fsc", Params.fmt(c.fsc)); base.put("spd", Params.fmt(c.spd));
            base.put("con", String.valueOf(c.con)); base.put("bpit", Params.fmt(c.bpit)); base.put("bspd", Params.fmt(c.bspd));
            base.put("blen", Params.fmt(c.blen)); base.put("bcon", String.valueOf(c.bcon));
            Codebook.Pattern pb = c.pattern();
            if (pb != null) { base.put("gaps", Params.list(pb.gaps)); base.put("semis", Params.list(pb.semis)); }
        } else {
            base.put("gaps", Params.list(x.pats[i].gaps)); base.put("semis", Params.list(x.pats[i].semis));
        }
        if (prm.pbTable.isEmpty()) base.put("pbTable", PBlip.DEFAULT_TABLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));
        TextView hint = tv("Change only what you want. A value left the same as the general setting is not stored. Typed values are used as typed, with no limits.", 12, 0xff888888, false);
        box.addView(hint);
        final HashMap<String, View> views = new HashMap<>();
        for (Over.F f : defs) {
            String eff = kv.containsKey(f.key) ? kv.get(f.key) : base.get(f.key);
            if (eff == null) eff = "";
            String label = f.label + (kv.containsKey(f.key) ? "  *" : "");
            if (f.type == 4) {
                CheckBox cb = check(box, label);
                cb.setChecked("true".equals(eff));
                views.put(f.key, cb);
            } else {
                EditText e = f.type == 1 ? fieldD(box, label) : field(box, label, f.type >= 2);
                if (f.type == 5) e.setSingleLine(false);
                e.setText(eff);
                views.put(f.key, e);
            }
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle("Tune " + x.names[i]).setView(scroll)
                .setPositiveButton("Save", null).setNeutralButton("Reset", null).setNegativeButton("Cancel", null).create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> { kv.clear(); storeTune(i, c, okey, kv); dlg.dismiss(); });
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            for (Over.F f : defs) {
                View w = views.get(f.key);
                String b = base.get(f.key) == null ? "" : base.get(f.key);
                String txt;
                boolean same;
                try {
                    if (f.type == 4) {
                        txt = String.valueOf(((CheckBox) w).isChecked());
                        same = txt.equals(b);
                    } else {
                        txt = ((EditText) w).getText().toString().trim();
                        if (txt.isEmpty()) { kv.remove(f.key); continue; }
                        if (f.type == 5) { txt = txt.replaceAll("\\s+", " "); same = txt.equals(b); if (same) kv.remove(f.key); else kv.put(f.key, txt); continue; }
                        if (f.type == 0 || f.type == 1) {
                            double nv = Double.parseDouble(txt);
                            txt = f.type == 0 ? String.valueOf(Math.round(nv)) : Params.fmt(nv);
                            same = b.isEmpty() ? false : Math.abs(Double.parseDouble(b) - nv) < 1e-9;
                        } else {
                            if (f.type == 2) { int[] a = Params.parseList(txt); if (a.length == 0) throw new NumberFormatException(); txt = Params.list(a); }
                            else { double[] a = Params.parseDoubles(txt); if (a.length == 0) throw new NumberFormatException(); txt = Params.list(a); }
                            same = txt.equals(b);
                        }
                    }
                } catch (NumberFormatException ex) {
                    say("Could not read: " + f.label);
                    return;
                }
                if (same) kv.remove(f.key); else kv.put(f.key, txt);
            }
            if (kv.containsKey("gaps") || kv.containsKey("semis")) {
                int[] g = Params.parseList(kv.containsKey("gaps") ? kv.get("gaps") : base.get("gaps"));
                double[] s2 = Params.parseDoubles(kv.containsKey("semis") ? kv.get("semis") : base.get("semis"));
                if (g.length != s2.length - 1) { say("A pattern with " + s2.length + " notes needs " + (s2.length - 1) + " gaps, you gave " + g.length + "."); return; }
            }
            storeTune(i, c, okey, kv);
            dlg.dismiss();
        });
    }

    private void storeTune(int i, Contacts.C c, String okey, Map<String, String> kv) {
        String s = Over.serialize(kv);
        if (c != null) { c.ov = s; savePeople(); }
        else { if (s.isEmpty()) ov.remove(okey); else ov.put(okey, s); saveOv(); }
        say(s.isEmpty() ? "Tuning cleared." : "Tuned.");
        rebuild();
    }

    // ---------------------------------------------------------------- ringtone parts

    private String auxLabel(String spec) {
        if (spec == null || spec.isEmpty()) return "none";
        if (spec.startsWith("b:")) {
            try { return "built-in " + Synth.BUILTIN[Integer.parseInt(spec.substring(2).trim())]; } catch (RuntimeException e) { return "built-in"; }
        }
        if (spec.startsWith("u:")) return "file " + displayName(Uri.parse(spec.substring(2)));
        if (spec.startsWith("t:")) return "text \"" + spec.substring(2) + "\"";
        return "none";
    }

    private void updateAuxButtons() {
        btnPre.setText("Prefix sound: " + auxLabel(prm.rtPre) + " (tap to change)");
        btnSuf.setText("Suffix sound: " + auxLabel(prm.rtSuf) + " (tap to change)");
    }

    private void pickAux(final boolean pre) {
        final ArrayList<Integer> ids = new ArrayList<>();
        final ArrayList<String> names = new ArrayList<>();
        names.add("None");
        for (int k = 0; k < Synth.BUILTIN.length; k++)
            if (Synth.hasBuiltin(k)) { ids.add(k); names.add("Built-in: " + Synth.BUILTIN[k]); }
        names.add("Type text...");
        names.add("Choose a file...");
        new AlertDialog.Builder(this).setTitle(pre ? "Prefix sound" : "Suffix sound")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    if (which == 0) setAux(pre, "");
                    else if (which == names.size() - 2) typeAux(pre);
                    else if (which == names.size() - 1) {
                        Intent in = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        in.addCategory(Intent.CATEGORY_OPENABLE);
                        in.setType("audio/*");
                        in.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        startActivityForResult(in, pre ? RC_PRE : RC_SUF);
                    } else setAux(pre, "b:" + ids.get(which - 1));
                }).show();
    }

    private void typeAux(final boolean pre) {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint("e.g. incoming call");
        String cur = pre ? prm.rtPre : prm.rtSuf;
        if (cur != null && cur.startsWith("t:")) e.setText(cur.substring(2));
        new AlertDialog.Builder(this).setTitle(pre ? "Prefix text" : "Suffix text")
                .setMessage("The text is turned into sound the way names are (see the typed text setting under Ringtone). Empty clears it.")
                .setView(e)
                .setPositiveButton("OK", (d, w) -> {
                    String t = e.getText().toString().replace('\n', ' ').trim();
                    setAux(pre, t.isEmpty() ? "" : "t:" + t);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void setAux(boolean pre, String spec) {
        if (pre) prm.rtPre = spec; else prm.rtSuf = spec;
        sp.edit().putString("params", prm.serialize()).apply();
        auxKey = "";
        updateAuxButtons();
    }

    /** Loads the prefix and suffix sounds when the settings changed; call off the UI thread. */
    private synchronized void loadAuxIfNeeded(Params p) {
        String key = p.rtPre + "|" + p.rtSuf + "|" + p.rtAuxMs + "|" + p.rtTextStyle + "|" + p.pbTable + "|" + p.pbLen + "|" + p.pbPitch;
        if (key.equals(auxKey)) return;
        auxPre = Gen.loadAux(this, p.rtPre, p, raw, rawRate);
        auxSuf = Gen.loadAux(this, p.rtSuf, p, raw, rawRate);
        auxKey = key;
    }

    /** The finished ringtone for sound i of x. Call off the UI thread. */
    private short[] ringtone(Gen.Result x, int i, Params p) {
        loadAuxIfNeeded(p);
        return Ring.compose(x.pcm[i], x.rate, auxPre, auxSuf, p);
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

    private void play(final int i) {
        final Gen.Result x = set;
        if (x == null || i >= x.pcm.length) return;
        if (!cRingPrev.isChecked()) { loopingIdx = -1; playPcm(x.pcm[i], x.rate); return; }
        if (loopingIdx == i && track != null) { stopPlay(); loopingIdx = -1; return; }   // tap again to stop
        stopPlay();
        loopingIdx = i;
        final Params p = prm.copy();
        new Thread(() -> {
            final short[] d = ringtone(x, i, p);
            runOnUiThread(() -> { if (loopingIdx == i) playLoop(d, x.rate); });
        }).start();
    }

    private void playPcm(short[] d, int rate) { playTrack(d, rate, false); }

    private void playLoop(short[] d, int rate) { playTrack(d, rate, true); }

    private void playTrack(short[] d, int rate, boolean loop) {
        stopPlay();
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(loop ? AudioAttributes.USAGE_NOTIFICATION_RINGTONE : AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(d.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            t.write(d, 0, d.length);
            if (loop) t.setLoopPoints(0, d.length, -1);
            t.play();
            track = t;
        } catch (RuntimeException e) {
            say("Could not play: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- export

    private void exportFiles(final boolean ringtones) {
        final Gen.Result x = set;
        final Uri tree = dirUri;
        if (x == null) { say("Nothing generated yet."); return; }
        if (tree == null) { say("Choose a folder first."); return; }
        say("Exporting...");
        final Params rp = prm.copy();
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
                    String file = ringtones ? Gen.ringFileName(x.prm, i, x.names[i], "-ringtone") : Gen.fileName(x.prm, i, x.names[i]);
                    short[] data = ringtones ? ringtone(x, i, rp) : x.pcm[i];
                    Uri u;
                    if (have.containsKey(file)) u = DocumentsContract.buildDocumentUriUsingTree(tree, have.get(file));
                    else u = DocumentsContract.createDocument(cr, parent, "audio/x-wav", file);
                    if (u == null) throw new IOException("could not create " + file);
                    try (OutputStream o = cr.openOutputStream(u, "wt")) {
                        if (o == null) throw new IOException("could not write " + file);
                        o.write(Synth.wav(data, x.rate));
                    }
                }
                say("Exported " + x.names.length + (ringtones ? " ringtones." : " files."));
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
