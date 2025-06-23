package com.example.woodcalculator; // Make sure this matches your package name

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.OutputStream;

// This class handles PDF saving specifically for Android Q (API 29) and above
// It uses MediaStore API which requires API 29+
public class PdfSaver {

    private static final String TAG = "PdfSaver";

    // This method is designed to be called only on devices with API >= 29
    public static Uri savePdfToDownloadsQ(Context context, String fileName, OutputStream outputStream) {
        ContentResolver resolver = context.getContentResolver();
        ContentValues contentValues = new ContentValues();

        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
        // This is the line that caused the API level error when directly in MainActivity
        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + "WoodBills");

        Uri uri = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
            }
            if (uri == null) {
                throw new IllegalStateException("Failed to create new MediaStore record.");
            }
            // Write the document content to the provided OutputStream
            // The outputStream is typically from document.writeTo()
            // We are not writing the document here, just providing the URI and stream
            // The actual document.writeTo(fos) happens in MainActivity
            return uri;

        } catch (Exception e) {
            Log.e(TAG, "Error saving PDF via MediaStore in PdfSaver: " + e.getMessage(), e);
            if (uri != null) {
                resolver.delete(uri, null, null); // Clean up if saving failed
            }
            return null;
        }
    }
}