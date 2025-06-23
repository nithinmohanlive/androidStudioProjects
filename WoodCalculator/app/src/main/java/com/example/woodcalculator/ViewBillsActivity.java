package com.example.woodcalculator;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ViewBillsActivity extends AppCompatActivity {

    private static final String TAG = "ViewBillsActivity";
    private static final int PERMISSION_REQUEST_CODE = 200; // Unique request code for this activity

    private EditText editTextSearchClientName;
    private EditText editTextSearchDate;
    private Button buttonSearchBills;
    private LinearLayout billsListContainer;
    private TextView textViewNoBillsFound;

    private List<BillItem> allBillItems; // Store all loaded bills
    private SimpleDateFormat filenameDateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
    private Pattern filenamePattern = Pattern.compile("Bill_([^_]+)_(\\d{8}_\\d{6})\\.pdf");


    // Helper class to hold bill details
    private static class BillItem {
        String fileName;
        String clientName;
        String dateString; // YYYYMMDD_HHmmss
        Uri fileUri;
        Date billDate; // For sorting and accurate date comparison

        public BillItem(String fileName, String clientName, String dateString, Uri fileUri) {
            this.fileName = fileName;
            this.clientName = clientName;
            this.dateString = dateString;
            this.fileUri = fileUri;
            try {
                this.billDate = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).parse(dateString);
            } catch (ParseException e) {
                Log.e(TAG, "Error parsing date from filename: " + dateString, e);
                this.billDate = null; // Handle cases where date parsing fails
            }
        }

        public String getFileName() { return fileName; }
        public String getClientName() { return clientName; }
        public String getDateString() { return dateString; }
        public Uri getFileUri() { return fileUri; }
        public Date getBillDate() { return billDate; }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_bills);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("View Generated Bills");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        initViews();
        setListeners();

        // Check for permissions and load bills
        checkPermissionsAndLoadBills();
    }

    private void initViews() {
        editTextSearchClientName = findViewById(R.id.editTextSearchClientName);
        editTextSearchDate = findViewById(R.id.editTextSearchDate);
        buttonSearchBills = findViewById(R.id.buttonSearchBills);
        billsListContainer = findViewById(R.id.billsListContainer);
        textViewNoBillsFound = findViewById(R.id.textViewNoBillsFound);
    }

    private void setListeners() {
        buttonSearchBills.setOnClickListener(v -> searchBills());
    }

    private void checkPermissionsAndLoadBills() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // For API < 29, we need READ_EXTERNAL_STORAGE permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST_CODE);
            } else {
                loadAllBills();
            }
        } else {
            // For API 29 (Q) and above, Scoped Storage is enforced.
            // If our app saved the files using MediaStore (as in MainActivity's createAndSavePdfQ),
            // then we can query MediaStore without explicit storage permissions.
            // If we were listing *arbitrary* files not created by the app, then managing
            // MANAGE_EXTERNAL_STORAGE (API 30+) or other approaches would be needed.
            // For this app's use case, directly loading via MediaStore query is correct.
            loadAllBills();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadAllBills();
            } else {
                Toast.makeText(this, "Permission denied. Cannot load bills.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void loadAllBills() {
        allBillItems = new ArrayList<>();
        ContentResolver contentResolver = getContentResolver();

        Uri collectionUri;
        String[] projection;
        String selection;
        String[] selectionArgs;
        String sortOrder = MediaStore.MediaColumns.DATE_ADDED + " DESC"; // Order by date, newest first

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android Q (API 29) and above, use MediaStore.Downloads
            collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            projection = new String[]{
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.RELATIVE_PATH // To filter by Downloads/WoodBills
            };
            selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " +
                    MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
            selectionArgs = new String[]{
                    Environment.DIRECTORY_DOWNLOADS + File.separator + "WoodBills" + File.separator,
                    "Bill_%.pdf"
            };
        } else {
            // For Android Pie (API 28) and below, use MediaStore.Files with _DATA column
            collectionUri = MediaStore.Files.getContentUri("external");
            projection = new String[]{
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DATE_ADDED,
                    MediaStore.Files.FileColumns.DATA // Important for full file path
            };
            selection = MediaStore.Files.FileColumns.DATA + " LIKE ?";
            // Construct the path for older APIs
            File downloadsDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WoodBills");
            selectionArgs = new String[]{
                    downloadsDir.getAbsolutePath() + File.separator + "Bill_%.pdf"
            };
        }


        try (Cursor cursor = contentResolver.query(
                collectionUri,
                projection,
                selection,
                selectionArgs,
                sortOrder
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int fileNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                int idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
                int dataCol = -1; // Initialize for older APIs
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                }

                do {
                    String fileName = cursor.getString(fileNameCol);
                    Matcher matcher = filenamePattern.matcher(fileName);

                    if (matcher.find()) {
                        String clientName = matcher.group(1).replace("_", " "); // Un-sanitize client name
                        String datePart = matcher.group(2);

                        Uri fileUri;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            // For Q+, construct URI using the ID and collection URI
                            long id = cursor.getLong(idCol);
                            fileUri = Uri.withAppendedPath(collectionUri, "" + id);
                            // ADDED LOGGING FOR ANDROID Q+ MEDIASTORE URI
                            Log.d(TAG, "Loaded MediaStore ID: " + id + ", Generated URI: " + fileUri);
                        } else {
                            // For older APIs, use FileProvider for the actual file path
                            String filePath = cursor.getString(dataCol);
                            File file = new File(filePath);
                            try {
                                // Corrected FileProvider authority (from previous step)
                                fileUri = FileProvider.getUriForFile(this,
                                        "com.example.woodcalculator.fileprovider", file);
                            } catch (IllegalArgumentException e) {
                                Log.e(TAG, "FileProvider failed for: " + filePath, e);
                                // Fallback for really old APIs if FileProvider isn't configured or fails for some reason
                                fileUri = Uri.fromFile(file); // This fallback is generally not recommended for modern Android
                            }
                        }
                        allBillItems.add(new BillItem(fileName, clientName, datePart, fileUri));
                    }
                } while (cursor.moveToNext());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading bills: " + e.getMessage(), e);
            Toast.makeText(this, "Error loading bills: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        // Bills are already sorted by DATE_ADDED DESC from the query, but re-sort just in case
        Collections.sort(allBillItems, (b1, b2) -> {
            if (b1.getBillDate() == null || b2.getBillDate() == null) {
                // Handle null dates (e.g., put them at the end or maintain current order)
                return 0;
            }
            return b2.getBillDate().compareTo(b1.getBillDate()); // Newest first
        });

        displayBills(allBillItems);
    }


    private void searchBills() {
        String clientNameQuery = editTextSearchClientName.getText().toString().trim().toLowerCase(Locale.getDefault());
        String dateQuery = editTextSearchDate.getText().toString().trim(); // Keep as string for simple comparison

        List<BillItem> filteredBills = new ArrayList<>();
        for (BillItem item : allBillItems) {
            boolean matchesClient = true;
            if (!clientNameQuery.isEmpty()) {
                matchesClient = item.getClientName().toLowerCase(Locale.getDefault()).contains(clientNameQuery);
            }

            boolean matchesDate = true;
            if (!dateQuery.isEmpty()) {
                // Simple string contains for date (e.g., "202312" for Dec 2023)
                // For robust date search, parse and compare dates.
                matchesDate = item.getDateString().contains(dateQuery);
            }

            if (matchesClient && matchesDate) {
                filteredBills.add(item);
            }
        }
        displayBills(filteredBills);
    }

    private void displayBills(List<BillItem> billsToDisplay) {
        billsListContainer.removeAllViews();
        if (billsToDisplay.isEmpty()) {
            textViewNoBillsFound.setVisibility(View.VISIBLE);
        } else {
            textViewNoBillsFound.setVisibility(View.GONE);
            for (BillItem item : billsToDisplay) {
                TextView billEntry = new TextView(this);
                // Format: Client Name (YYYY-MM-DD HH:MM) - filename
                String formattedDate = item.getBillDate() != null ?
                        new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(item.getBillDate()) :
                        "N/A";
                billEntry.setText(String.format(Locale.getDefault(), "%s (%s)\nFile: %s",
                        item.getClientName(), formattedDate, item.getFileName()));
                billEntry.setTextSize(16f);
                billEntry.setPadding(8, 8, 8, 8);
                billEntry.setBackgroundResource(R.drawable.rounded_border); // Optional: add a drawable for styling
                billEntry.setGravity(Gravity.START);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                params.setMargins(0, 0, 0, 10); // Add bottom margin
                billEntry.setLayoutParams(params);

                billEntry.setOnClickListener(v -> openPdf(item.getFileUri()));
                billsListContainer.addView(billEntry);
            }
        }
    }

    private void openPdf(Uri pdfUri) {
        // ADDED LOGGING FOR openPdf METHOD
        Log.d(TAG, "Attempting to open PDF with URI: " + pdfUri);
        Log.d(TAG, "URI Scheme: " + pdfUri.getScheme());
        Log.d(TAG, "URI Authority: " + pdfUri.getAuthority());
        Log.d(TAG, "URI Path: " + pdfUri.getPath());

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(pdfUri, "application/pdf");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); // Grant temporary read permission

        // Ensure the system can find an app to handle the intent
        if (intent.resolveActivity(getPackageManager()) != null) {
            try {
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Error opening PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
                Log.e(TAG, "Error starting activity for PDF: " + e.getMessage(), e);
            }
        } else {
            Toast.makeText(this, "No application found to open PDF files.", Toast.LENGTH_LONG).show();
            Log.e(TAG, "No PDF viewer app found on device.");
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }
}