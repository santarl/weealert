package io.rfsj.weealert;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Answers the system tone picker intent (android.intent.action.RINGTONE_PICKER), so weealert is
 * listed next to the system picker when an app asks for a notification tone. Tap to hear, then Use.
 */
public class Pick extends Activity {
    private Gen.Result res;
    private int sel = -1;
    private AudioTrack track;
    private LinearLayout list;
    private TextView status;
    private Button use;
    private final View[] rows = new View[Gen.MAX_N];

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView tv(String t, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setResult(RESULT_CANCELED);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        sv.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(28));
        sv.addView(root);
        root.addView(tv("weealert", 24, Color.WHITE, true));
        root.addView(tv("Tap a sound to hear it, then press Use.", 13, 0xff999999, false));
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
                runOnUiThread(() -> show(r));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Could not build the sounds: " + e.getMessage()));
            }
        }).start();
    }

    private void show(Gen.Result r) {
        res = r;
        status.setText(r.info);
        list.removeAllViews();
        for (int i = 0; i < r.names.length && i < rows.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(8), dp(10), dp(8), dp(10));
            row.addView(tv(r.names[i], 18, Color.WHITE, false));
            row.addView(tv(r.details[i], 12, 0xff777777, false));
            row.setOnClickListener(v -> select(idx));
            rows[i] = row;
            list.addView(row);
        }
    }

    private void select(int i) {
        if (sel >= 0 && rows[sel] != null) rows[sel].setBackgroundColor(Color.TRANSPARENT);
        sel = i;
        rows[i].setBackgroundColor(0xff1d2b36);
        use.setEnabled(true);
        play(res.pcm[i], res.rate);
    }

    private void choose() {
        if (res == null || sel < 0) return;
        final int i = sel;
        use.setEnabled(false);
        status.setText("Adding to system tones...");
        new Thread(() -> {
            try {
                Uri u = Tones.add(this, Gen.fileName(res.prm, i, res.names[i]), res.pcm[i], res.rate);
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

    private void play(short[] d, int rate) {
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
        } catch (RuntimeException ignored) { }
    }

    @Override
    protected void onDestroy() {
        stopPlay();
        super.onDestroy();
    }
}
