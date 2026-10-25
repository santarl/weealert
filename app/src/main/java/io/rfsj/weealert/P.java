package io.rfsj.weealert;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Shows the generated sounds as a storage root in the system file picker, so any app that lets you
 * choose an audio file can pick them straight from weealert. Read only; the files are made on demand.
 */
public class P extends DocumentsProvider {
    private static final String ROOT = "root", WAV = "audio/x-wav";
    private static final String[] ROOT_COLS = {Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
            Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID};
    private static final String[] DOC_COLS = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE};

    @Override
    public boolean onCreate() { return true; }

    private static String[] cols(String[] projection, String[] dflt) { return projection != null ? projection : dflt; }

    private Gen.Result result() throws FileNotFoundException {
        try {
            return Gen.fromPrefs(getContext());
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor c = new MatrixCursor(cols(projection, ROOT_COLS));
        c.newRow().add(Root.COLUMN_ROOT_ID, ROOT)
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
                .add(Root.COLUMN_ICON, getContext().getApplicationInfo().icon)
                .add(Root.COLUMN_TITLE, "weealert")
                .add(Root.COLUMN_SUMMARY, "generated sounds")
                .add(Root.COLUMN_DOCUMENT_ID, ROOT);
        return c;
    }

    private void addRoot(MatrixCursor c) {
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, ROOT)
                .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_DISPLAY_NAME, "weealert")
                .add(Document.COLUMN_FLAGS, 0);
    }

    private void addFile(MatrixCursor c, Gen.Result r, int i) {
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, r.names[i])
                .add(Document.COLUMN_MIME_TYPE, WAV)
                .add(Document.COLUMN_DISPLAY_NAME, Gen.fileName(r.prm, i, r.names[i]))
                .add(Document.COLUMN_FLAGS, 0)
                .add(Document.COLUMN_SIZE, 44L + 2L * r.pcm[i].length);
    }

    private static int indexOf(Gen.Result r, String id) {
        for (int i = 0; i < r.names.length; i++) if (r.names[i].equals(id)) return i;
        return -1;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor c = new MatrixCursor(cols(projection, DOC_COLS));
        if (ROOT.equals(documentId)) {
            addRoot(c);
        } else {
            Gen.Result r = result();
            int i = indexOf(r, documentId);
            if (i < 0) throw new FileNotFoundException(documentId);
            addFile(c, r, i);
        }
        return c;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        if (!ROOT.equals(parentDocumentId)) throw new FileNotFoundException(parentDocumentId);
        MatrixCursor c = new MatrixCursor(cols(projection, DOC_COLS));
        Gen.Result r = result();
        for (int i = 0; i < r.names.length; i++) addFile(c, r, i);
        return c;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (mode.indexOf('w') >= 0 || mode.indexOf('a') >= 0) throw new FileNotFoundException("read only");
        Gen.Result r = result();
        int i = indexOf(r, documentId);
        if (i < 0) throw new FileNotFoundException(documentId);
        try {
            synchronized (P.class) {
                File dir = new File(getContext().getCacheDir(), "docs");
                dir.mkdirs();
                String prefix = r.key + "-";
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) if (!f.getName().startsWith(prefix)) f.delete();
                File f = new File(dir, prefix + documentId + ".wav");
                if (!f.exists()) {
                    File tmp = new File(dir, prefix + documentId + ".tmp");
                    try (FileOutputStream o = new FileOutputStream(tmp)) {
                        o.write(Synth.wav(r.pcm[i], r.note.rate));
                    }
                    if (!tmp.renameTo(f)) throw new IOException("could not write the sound");
                }
                return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }
}
