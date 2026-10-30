package io.rfsj.weealert;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Answers the system tone picker intent (android.intent.action.RINGTONE_PICKER), so weealert is listed
 * next to the system picker when an app asks for a tone. For notification tones, tap to hear and press
 * Use. For ringtones and alarms the sound gets the prefix, suffix, filter and end gap from the settings,
 * previews on a loop (as the phone will play it) and is saved as name-ringtone or name-alarm.
 */
public class Pick extends Activity {
    private Gen.Result res;
    private Params ring;                          // the global settings, used for the ringtone parts
    private Synth.Note pre, suf;
    private int kind = Tones.NOTIFICATION;
    private int sel = -1;
    private AudioTrack track;
    private LinearLayout list;
    private TextView status;
    private Button use;
    private EditText gapField;
    private final ArrayList<View> rows = new ArrayList<>();

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView tv(String t, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private String suffix() { return kind == Tones.RINGTONE ? "-ringtone" : "-alarm"; }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setResult(RESULT_CANCELED);
        int type = getIntent().getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION);
        kind = type == RingtoneManager.TYPE_RINGTONE ? Tones.RINGTONE : type == RingtoneManager.TYPE_ALARM ? Tones.ALARM : Tones.NOTIFICATION;
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        sv.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(28));
        sv.addView(root);
        root.addView(tv("weealert" + (kind == Tones.RINGTONE ? " ringtone" : kind == Tones.ALARM ? " alarm" : ""), 24, Color.WHITE, true));
        root.addView(tv(kind == Tones.NOTIFICATION ? "Tap a sound to hear it, then press Use."
                : "Tap a sound to hear it on a loop, exactly as the phone will repeat it, then press Use. The prefix, suffix and filter come from the settings in the app.",
                13, 0xff999999, false));
        if (kind != Tones.NOTIFICATION) {
            LinearLayout g = new LinearLayout(this);
            g.setOrientation(LinearLayout.HORIZONTAL);
            g.setPadding(0, dp(10), 0, 0);
            TextView l = tv("Gap between loops (ms)", 14, 0xffcccccc, false);
            gapField = new EditText(this);
            gapField.setTextColor(Color.WHITE);
            gapField.setSingleLine(true);
            gapField.setInputType(InputType.TYPE_CLASS_NUMBER);
            Button set = new Button(this);
            set.setText("Set");
            set.setAllCaps(false);
            set.setOnClickListener(v -> setGap());
            g.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
            g.addView(gapField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            g.addView(set, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f));
            root.addView(g);
        }
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(12), 0, dp(12));
        root.addView(list);
        status = tv("Loading...", 13, 0xff9ad0ff, false);
        root.addView(status);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(v -> finish());
        use = new Button(this);
        use.setText("Use");
        use.setAllCaps(false);
        use.setEnabled(false);
        use.setOnClickListener(v -> choose());
        bar.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(use, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar);
        setContentView(sv);

        new Thread(() -> {
            try {
                Gen.Result r = Gen.fromPrefs(this);
                Params p = r.prm.copy();
                Synth.Note a = null, s = null;
                if (kind != Tones.NOTIFICATION) {
                    a = Gen.loadAux(this, p.rtPre, p, r.raw, r.rawRate, p.rtPrePitch);
                    s = Gen.loadAux(this, p.rtSuf, p, r.raw, r.rawRate, p.rtSufPitch);
                }
                final Synth.Note fa = a, fs = s;
                runOnUiThread(() -> { ring = p; pre = fa; suf = fs; show(r); });
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Could not build the sounds: " + e.getMessage()));
            }
        }).start();
    }

    private void show(Gen.Result r) {
        res = r;
        status.setText(r.info);
        if (gapField != null) gapField.setText(String.valueOf(ring.rtLoopGap));
        list.removeAllViews();
        rows.clear();
        for (int i = 0; i < r.names.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(8), dp(10), dp(8), dp(10));
            row.addView(tv(r.names[i], 18, Color.WHITE, false));
            row.addView(tv(r.details[i], 12, 0xff777777, false));
            row.setOnClickListener(v -> select(idx));
            rows.add(row);
            list.addView(row);
        }
    }

    private void setGap() {
        if (ring == null) return;
        try {
            ring.rtLoopGap = Math.max(0, Integer.parseInt(gapField.getText().toString().trim()));
        } catch (NumberFormatException e) {
            status.setText("Type the gap in whole milliseconds.");
            return;
        }
        ring.normalize();
        SharedPreferences sp = getSharedPreferences("weealert", MODE_PRIVATE);
        sp.edit().putString("params", ring.serialize()).apply();
        status.setText("Gap set to " + ring.rtLoopGap + " ms.");
        if (sel >= 0) preview(sel);
    }

    private void select(int i) {
        if (sel >= 0 && sel < rows.size()) rows.get(sel).setBackgroundColor(Color.TRANSPARENT);
        sel = i;
        rows.get(i).setBackgroundColor(0xff1d2b36);
        use.setEnabled(true);
        preview(i);
    }

    private short[] build(int i) {
        return kind == Tones.NOTIFICATION ? res.pcm[i] : Ring.compose(res.pcm[i], res.rate, pre, suf, ring);
    }

    private void preview(final int i) {
        if (kind == Tones.NOTIFICATION) { play(res.pcm[i], res.rate, false); return; }
        stopPlay();
        new Thread(() -> {
            final short[] d = build(i);
            runOnUiThread(() -> { if (sel == i) play(d, res.rate, true); });
        }).start();
    }

    private void choose() {
        if (res == null || sel < 0) return;
        final int i = sel;
        use.setEnabled(false);
        status.setText("Adding to the system tones...");
        new Thread(() -> {
            try {
                String file = kind == Tones.NOTIFICATION ? Gen.fileName(res.prm, i, res.names[i])
                        : Gen.ringFileName(res.prm, i, res.names[i], suffix());
                Uri u = Tones.add(this, file, build(i), res.rate, kind);
                runOnUiThread(() -> {
                    Intent r = new Intent();
                    r.putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, u);
                    setResult(RESULT_OK, r);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { status.setText("Could not use it: " + e.getMessage()); use.setEnabled(true); });
            }
        }).start();
    }

    private void stopPlay() {
        if (track != null) {
            try { track.stop(); } catch (RuntimeException ignored) { }
            track.release();
            track = null;
        }
    }

    private void play(short[] d, int rate, boolean loop) {
        stopPlay();
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(kind == Tones.RINGTONE ? AudioAttributes.USAGE_NOTIFICATION_RINGTONE
                                    : kind == Tones.ALARM ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_NOTIFICATION)
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
        } catch (RuntimeException ignored) { }
    }

    @Override
    protected void onDestroy() {
        stopPlay();
        super.onDestroy();
    }
}
