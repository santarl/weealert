package io.rfsj.weealert;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;

/** Puts a sound into the system Notifications tone library (Android 10+). Needs no permission. */
final class Tones {
    /** Adds or overwrites one sound and returns its MediaStore uri. */
    static Uri add(Context c, String file, short[] pcm, int rate) throws IOException {
        if (Build.VERSION.SDK_INT < 29) throw new IOException("needs Android 10 or newer");
        ContentResolver cr = c.getContentResolver();
        Uri coll = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        Uri u = null;
        try (Cursor q = cr.query(coll, new String[]{MediaStore.Audio.Media._ID},
                MediaStore.Audio.Media.DISPLAY_NAME + "=? AND " + MediaStore.Audio.Media.RELATIVE_PATH + "=?",
                new String[]{file, "Notifications/"}, null)) {
            if (q != null && q.moveToFirst()) u = ContentUris.withAppendedId(coll, q.getLong(0));
        }
        boolean fresh = u == null;
        if (fresh) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, file);
            v.put(MediaStore.Audio.Media.TITLE, file.substring(0, file.length() - 4));
            v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav");
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, "Notifications/");
            v.put(MediaStore.Audio.Media.IS_NOTIFICATION, 1);
            v.put(MediaStore.Audio.Media.IS_PENDING, 1);
            u = cr.insert(coll, v);
        }
        if (u == null) throw new IOException("could not add " + file);
        try (OutputStream o = cr.openOutputStream(u, "wt")) {
            if (o == null) throw new IOException("could not write " + file);
            o.write(Synth.wav(pcm, rate));
        }
        if (fresh) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.IS_PENDING, 0);
            cr.update(u, v, null, null);
        }
        return u;
    }
}
