package com.example.woodcalculator;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable; // Import for @Nullable
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects; // Import for Objects.hash()

public class PriceTableActivity extends AppCompatActivity {

    private static final String TAG = "PriceTableActivity";
    private static final String PREFS_NAME = "WoodCalculatorPrefs";
    private static final String CORRECT_PASSCODE = "7898"; // The required passcode

    // Keys for raw string inputs
    private static final String KEY_GIRTH_RANGES_INPUT = "girthRangesInput";
    private static final String KEY_LENGTH_VALUES_INPUT = "lengthValuesInput";

    // Stored list of parsed actual girths and lengths
    private static final String KEY_GIRTH_RANGES_PARSED = "girthRangesParsed";
    private static final String KEY_LENGTH_VALUES_PARSED = "lengthValuesParsed";
    private static final String KEY_UNIT_PRICES = "unitPrices";

    // Hardcoded max limits for validation
    private static final double MAX_GIRTH_LIMIT = 100.0; // Max end value for any girth range
    private static final double MAX_LENGTH_LIMIT = 40.0; // Max value for any single length

    private EditText editTextGirthRanges;
    private EditText editTextLengthValues;
    private Button buttonGenerateTable;
    private TableLayout priceTableLayout;

    private SharedPreferences sharedPreferences;
    private Gson gson;

    private List<GirthRange> currentGirthRanges;
    private List<Double> currentLengthValues;
    private Map<String, Double> unitPrices;

    private boolean isAuthenticated = false; // Flag to track authentication status

    // --- Permissions and Request Codes for Export/Import ---
    private static final int PERMISSION_REQUEST_CODE_EXPORT = 101;
    private static final int PERMISSION_REQUEST_CODE_IMPORT = 103; // For reading, distinct from export
    private static final int PICK_FILE_REQUEST_CODE = 102; // For ACTION_OPEN_DOCUMENT

    // --- Inner class to bundle price table data for GSON ---
    private static class PriceTableData {
        Map<String, Double> unitPrices;
        List<GirthRange> girthRanges;
        List<Double> lengthValues;

        public PriceTableData(Map<String, Double> unitPrices, List<GirthRange> girthRanges, List<Double> lengthValues) {
            this.unitPrices = unitPrices;
            this.girthRanges = girthRanges;
            this.lengthValues = lengthValues;
        }

        // Getters (needed by GSON for deserialization, even if not directly called)
        public Map<String, Double> getUnitPrices() { return unitPrices; }
        public List<GirthRange> getGirthRanges() { return girthRanges; }
        public List<Double> getLengthValues() { return lengthValues; }
    }
    // --- End inner class ---


    public static class GirthRange {
        private double start;
        private double end;

        public GirthRange(double start, double end) {
            this.start = start;
            this.end = end;
        }

        public double getStart() {
            return start;
        }

        public double getEnd() {
            return end;
        }

        @NonNull
        @Override
        public String toString() {
            if (start == 0.0) {
                return String.format(Locale.US, "%.1f", end); // For ranges starting at 0, display only end
            }
            return String.format(Locale.US, "%.1f-%.1f", start, end);
        }

        // Added equals and hashCode for GirthRange
        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            GirthRange that = (GirthRange) obj;
            // Use an epsilon for double comparison due to potential floating point inaccuracies
            return Math.abs(this.start - that.start) < 0.001 &&
                    Math.abs(this.end - that.end) < 0.001;
        }

        @Override
        public int hashCode() {
            // Combine hash codes of start and end. Using Double.hashCode for precision.
            return Objects.hash(start, end);
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_price_table);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Custom Price Table");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        initViews();
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        gson = new Gson();

        loadSavedData(); // Load saved data first
        enableEditingUI(false); // Initially disable editing UI

        // Prompt for passcode immediately on activity creation
        promptForPasscode();

        buttonGenerateTable.setOnClickListener(v -> {
            if (isAuthenticated) {
                generatePriceTable();
            } else {
                Toast.makeText(this, "Please enter the passcode to make changes.", Toast.LENGTH_SHORT).show();
                promptForPasscode(); // Re-prompt if not authenticated
            }
        });
    }

    private void initViews() {
        editTextGirthRanges = findViewById(R.id.editTextGirthRanges);
        editTextLengthValues = findViewById(R.id.editTextLengthValues);
        buttonGenerateTable = findViewById(R.id.buttonGenerateTable);
        priceTableLayout = findViewById(R.id.priceTableLayout);
    }

    // New method to control the enabled state of editing UI elements
    private void enableEditingUI(boolean enable) {
        editTextGirthRanges.setEnabled(enable);
        editTextLengthValues.setEnabled(enable);
        buttonGenerateTable.setEnabled(enable);
        // Table cells' click listeners are enabled/disabled in rebuildTableFromData
    }

    // New method to prompt for passcode
    private void promptForPasscode() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Passcode");

        final EditText passcodeInput = new EditText(this);
        passcodeInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        passcodeInput.setHint("Passcode");
        builder.setView(passcodeInput);

        builder.setPositiveButton("Submit", (dialog, which) -> {
            String enteredPasscode = passcodeInput.getText().toString();
            if (enteredPasscode.equals(CORRECT_PASSCODE)) {
                isAuthenticated = true;
                enableEditingUI(true);
                rebuildTableFromData(); // Rebuild table to allow editable cells
                Toast.makeText(this, "Passcode accepted. You can now edit.", Toast.LENGTH_SHORT).show();
            } else {
                isAuthenticated = false;
                enableEditingUI(false); // Keep disabled if passcode is wrong
                Toast.makeText(this, "Incorrect Passcode. Cannot edit.", Toast.LENGTH_SHORT).show();
                // Optionally: finish() the activity if too many wrong attempts or just keep disabled
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> {
            isAuthenticated = false;
            enableEditingUI(false);
            Toast.makeText(this, "Editing disabled.", Toast.LENGTH_SHORT).show();
            // If the user cancels the passcode, they can't edit.
        });
        builder.setCancelable(false); // User must enter passcode or cancel
        builder.show();
    }


    private void loadSavedData() {
        // Load raw string inputs
        editTextGirthRanges.setText(sharedPreferences.getString(KEY_GIRTH_RANGES_INPUT, "0-18, 18-20, 20-30, 30-40, 40-50"));
        editTextLengthValues.setText(sharedPreferences.getString(KEY_LENGTH_VALUES_INPUT, "5, 8, 10, 12, 14, 16"));

        // Load parsed lists for GirthRanges
        String girthRangesJson = sharedPreferences.getString(KEY_GIRTH_RANGES_PARSED, null);
        Type girthRangeListType = new TypeToken<List<GirthRange>>() {}.getType();
        currentGirthRanges = gson.fromJson(girthRangesJson, girthRangeListType);
        if (currentGirthRanges == null) {
            currentGirthRanges = new ArrayList<>();
        }
        Log.d(TAG, "Loaded " + currentGirthRanges.size() + " girth ranges.");


        // Load parsed lists for LengthValues
        String lengthJson = sharedPreferences.getString(KEY_LENGTH_VALUES_PARSED, null);
        Type doubleListType = new TypeToken<List<Double>>() {}.getType();
        currentLengthValues = gson.fromJson(lengthJson, doubleListType);
        if (currentLengthValues == null) {
            currentLengthValues = new ArrayList<>();
        }
        Log.d(TAG, "Loaded " + currentLengthValues.size() + " length values.");


        // Load Unit Prices Map
        String pricesJson = sharedPreferences.getString(KEY_UNIT_PRICES, null);
        Type mapType = new TypeToken<Map<String, Double>>() {}.getType();
        unitPrices = gson.fromJson(pricesJson, mapType);
        if (unitPrices == null) {
            unitPrices = new LinkedHashMap<>();
        }
        Log.d(TAG, "Loaded " + unitPrices.size() + " unit prices.");


        // After loading, generate the table based on loaded parsed values
        // Do not call generatePriceTable() directly here, as it re-parses and validates.
        // rebuildTableFromData() is sufficient to display what's loaded.
        if (!currentGirthRanges.isEmpty() && !currentLengthValues.isEmpty()) {
            rebuildTableFromData(); // This will display the table (non-editable initially if not authenticated)
        } else {
            // If no parsed data, attempt to generate from EditText values to initialize the table
            // This will only work if the user provides valid inputs in EditText and authenticates
            Log.d(TAG, "No parsed data found, attempting to initialize table from EditText inputs.");
            // We don't call generatePriceTable here directly to avoid unintended data clearing/toast
            // The promptForPasscode will eventually lead to rebuildTableFromData
        }
    }

    private void saveTableData() {
        SharedPreferences.Editor editor = sharedPreferences.edit();

        // Save raw string inputs
        editor.putString(KEY_GIRTH_RANGES_INPUT, editTextGirthRanges.getText().toString());
        editor.putString(KEY_LENGTH_VALUES_INPUT, editTextLengthValues.getText().toString());

        // Save the parsed lists
        editor.putString(KEY_GIRTH_RANGES_PARSED, gson.toJson(currentGirthRanges));
        editor.putString(KEY_LENGTH_VALUES_PARSED, gson.toJson(currentLengthValues));

        // Save the unit prices map
        editor.putString(KEY_UNIT_PRICES, gson.toJson(unitPrices));

        editor.apply();
        Log.d(TAG, "Table data saved.");
    }

    private void generatePriceTable() {
        if (!isAuthenticated) {
            Toast.makeText(this, "Passcode required to generate/update the table.", Toast.LENGTH_SHORT).show();
            promptForPasscode();
            return;
        }

        String girthInput = editTextGirthRanges.getText().toString().trim();
        String lengthInput = editTextLengthValues.getText().toString().trim();

        if (girthInput.isEmpty()) {
            Toast.makeText(this, "Girth ranges cannot be empty.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (lengthInput.isEmpty()) {
            Toast.makeText(this, "Length values cannot be empty.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            List<GirthRange> newGirthRanges = parseAndValidateGirthRanges(girthInput);
            List<Double> newLengthValues = parseAndValidateLengthValues(lengthInput);

            if (newGirthRanges.isEmpty() || newLengthValues.isEmpty()) {
                Toast.makeText(this, "One or more lists are empty or contain invalid values.", Toast.LENGTH_LONG).show();
                return;
            }

            // Check max limits for girth (last range's end)
            if (!newGirthRanges.isEmpty() && newGirthRanges.get(newGirthRanges.size() - 1).getEnd() > MAX_GIRTH_LIMIT) {
                Toast.makeText(this, String.format(Locale.getDefault(), "Maximum Girth limit exceeded. Last range end cannot be greater than %.1f inches.", MAX_GIRTH_LIMIT), Toast.LENGTH_LONG).show();
                return;
            }

            // Check max limits for length
            for (double len : newLengthValues) {
                if (len > MAX_LENGTH_LIMIT) {
                    Toast.makeText(this, String.format(Locale.getDefault(), "Maximum Length limit exceeded. Individual length cannot be greater than %.1f feet.", MAX_LENGTH_LIMIT), Toast.LENGTH_LONG).show();
                    return;
                }
            }

            // Check if table dimensions have changed. Use .equals() due to custom GirthRange.equals()
            boolean tableDimensionsChanged = !newGirthRanges.equals(currentGirthRanges) || !newLengthValues.equals(currentLengthValues);

            // Update the activity's current lists
            this.currentGirthRanges = newGirthRanges;
            this.currentLengthValues = newLengthValues;

            if (tableDimensionsChanged) {
                unitPrices.clear(); // Clear old prices if table structure changes
                Toast.makeText(this, "Table structure updated. Please enter new unit prices.", Toast.LENGTH_LONG).show();
                Log.d(TAG, "Table dimensions changed. Unit prices cleared.");
            } else {
                Log.d(TAG, "Table dimensions unchanged. Existing unit prices retained.");
            }

            rebuildTableFromData(); // Rebuild the UI table
            saveTableData(); // Save the new table structure and any existing (or cleared) prices

        } catch (NumberFormatException e) {
            Log.e(TAG, "Invalid number format in list inputs: " + e.getMessage(), e);
            Toast.makeText(this, "Please enter valid numbers in comma-separated lists.", Toast.LENGTH_LONG).show();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "Validation error: " + e.getMessage());
        }
    }

    /**
     * Parses a comma-separated string of "start-end" girth ranges into a List<GirthRange>.
     * Validates that:
     * - Each range has start < end.
     * - Ranges are non-negative.
     * - Ranges are contiguous (end of previous == start of current), except for the first range's start.
     * - No duplicate ranges are present.
     */
    private List<GirthRange> parseAndValidateGirthRanges(String input) throws IllegalArgumentException {
        List<GirthRange> ranges = new ArrayList<>();
        HashSet<GirthRange> uniqueRanges = new HashSet<>(); // For checking duplicate ranges

        String[] parts = input.split(",");
        for (String part : parts) {
            String trimmedPart = part.trim();
            if (trimmedPart.isEmpty()) continue;

            String[] rangeParts = trimmedPart.split("-");
            if (rangeParts.length != 2) {
                throw new IllegalArgumentException("Invalid girth range format: '" + trimmedPart + "'. Use 'start-end'.");
            }

            try {
                double start = Double.parseDouble(rangeParts[0].trim());
                double end = Double.parseDouble(rangeParts[1].trim());

                if (start < 0 || end < 0) {
                    throw new IllegalArgumentException("Girth range values cannot be negative. Found in '" + trimmedPart + "'.");
                }
                if (start >= end) {
                    throw new IllegalArgumentException("Girth range 'start' must be less than 'end'. Invalid range: '" + trimmedPart + "'.");
                }

                GirthRange newRange = new GirthRange(start, end);
                if (!uniqueRanges.add(newRange)) { // Uses GirthRange.equals() and hashCode()
                    throw new IllegalArgumentException("Duplicate girth range found: '" + newRange.toString() + "'.");
                }
                ranges.add(newRange);

            } catch (NumberFormatException e) {
                throw new NumberFormatException("Invalid number in girth range: '" + trimmedPart + "'.");
            }
        }

        if (ranges.isEmpty()) {
            throw new IllegalArgumentException("Girth ranges list is empty after parsing. Please enter values.");
        }

        // Sort the ranges by their start value for proper contiguity checking and display
        Collections.sort(ranges, new Comparator<GirthRange>() {
            @Override
            public int compare(GirthRange r1, GirthRange r2) {
                return Double.compare(r1.getStart(), r2.getStart());
            }
        });

        // Validate contiguity for sorted ranges: end of previous must equal start of current
        for (int i = 1; i < ranges.size(); i++) {
            // Use an epsilon for floating-point comparison to avoid issues with 0.1 + 0.2 != 0.3
            if (Math.abs(ranges.get(i).getStart() - ranges.get(i - 1).getEnd()) > 0.001) {
                throw new IllegalArgumentException(String.format(Locale.getDefault(),
                        "Girth ranges must be contiguous (e.g., 0-18, 18-20). Gap/overlap found between %.1f and %.1f.",
                        ranges.get(i - 1).getEnd(), ranges.get(i).getStart()));
            }
        }

        return ranges;
    }

    /**
     * Parses a comma-separated string of numbers into a List<Double>.
     * Validates that all numbers are positive and no duplicates exist.
     */
    private List<Double> parseAndValidateLengthValues(String input) throws IllegalArgumentException {
        List<Double> values = new ArrayList<>();
        HashSet<Double> uniqueValues = new HashSet<>(); // To check for duplicates

        String[] parts = input.split(",");
        for (String part : parts) {
            String trimmedPart = part.trim();
            if (trimmedPart.isEmpty()) continue;

            try {
                double value = Double.parseDouble(trimmedPart);
                if (value <= 0) {
                    throw new IllegalArgumentException("Length values must be positive. Found '" + trimmedPart + "'.");
                }
                if (!uniqueValues.add(value)) {
                    throw new IllegalArgumentException("Length values cannot contain duplicates: " + value);
                }
                values.add(value);
            } catch (NumberFormatException e) {
                throw new NumberFormatException("Invalid number found in length list: '" + trimmedPart + "'");
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Length values list is empty after parsing. Please enter values.");
        }
        // Sort for consistent order and closest match lookup
        Collections.sort(values);
        return values;
    }

    private void rebuildTableFromData() {
        priceTableLayout.removeAllViews(); // Clear previous table

        // Add Header Row (Length values)
        TableRow headerRow = new TableRow(this);
        headerRow.setBackgroundColor(Color.parseColor("#E0E0E0"));
        headerRow.setPadding(0, 8, 0, 8);

        // --- CHANGE HERE: Changed header text ---
        headerRow.addView(createHeaderTextView("G\\L")); // Top-left corner cell label

        // Add Length headers
        for (double length : currentLengthValues) {
            headerRow.addView(createHeaderTextView(String.format(Locale.getDefault(), "%.1f", length)));
        }
        priceTableLayout.addView(headerRow);

        // Add Data Rows (Girth ranges and editable unit prices)
        for (GirthRange girthRange : currentGirthRanges) {
            TableRow dataRow = new TableRow(this);
            if (priceTableLayout.getChildCount() % 2 == 0) {
                dataRow.setBackgroundColor(Color.parseColor("#F5F5F5"));
            } else {
                dataRow.setBackgroundColor(Color.WHITE);
            }
            dataRow.setPadding(0, 4, 0, 4);

            // Girth range header for the current row
            dataRow.addView(createHeaderTextView(girthRange.toString()));

            // Add editable price cells for each Length
            for (double length : currentLengthValues) {
                final GirthRange currentGirthRange = girthRange;
                final double currentLength = length;
                final String priceKey = getPriceKey(currentGirthRange, currentLength);
                Double storedPrice = unitPrices.get(priceKey); // Get the actual stored price

                // Display stored price or "0.0" if not set
                TextView cellTextView = createDataTextView(
                        storedPrice != null ? String.format(Locale.getDefault(), "%.1f", storedPrice) : "0.0",
                        storedPrice // Pass the actual Double value here to determine text color
                );

                // Set OnClickListener only if authenticated
                if (isAuthenticated) {
                    cellTextView.setOnClickListener(v ->
                            showEditCellDialog(cellTextView, currentGirthRange, currentLength, priceKey)
                    );
                } else {
                    // Make it not clickable if not authenticated
                    cellTextView.setClickable(false);
                }
                dataRow.addView(cellTextView);
            }
            priceTableLayout.addView(dataRow);
        }
        Log.d(TAG, "Table rebuilt. Girth ranges: " + currentGirthRanges.size() + ", Lengths: " + currentLengthValues.size());
    }

    private TextView createHeaderTextView(String text) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setGravity(Gravity.CENTER);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        textView.setTypeface(null, android.graphics.Typeface.BOLD);
        textView.setPadding(8, 8, 8, 8);
        TableRow.LayoutParams params = new TableRow.LayoutParams(
                TableRow.LayoutParams.WRAP_CONTENT, // CHANGE: Set width to WRAP_CONTENT
                TableRow.LayoutParams.WRAP_CONTENT
                // REMOVED: 1f weight here, as it conflicts with horizontal scrolling
        );
        textView.setLayoutParams(params);
        return textView;
    }

    // MODIFIED: Added 'actualPriceValue' parameter for color determination
    private TextView createDataTextView(String text, Double actualPriceValue) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setGravity(Gravity.CENTER);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        textView.setTypeface(null, android.graphics.Typeface.BOLD);
        textView.setPadding(4, 4, 4, 4);

        // Set color based on whether the price is set and non-zero
        // Null check for actualPriceValue is important
        if (actualPriceValue != null && actualPriceValue > 0.0) {
            textView.setTextColor(Color.RED);
        } else {
            textView.setTextColor(Color.BLACK); // Default color for unset/zero prices
        }

        TableRow.LayoutParams params = new TableRow.LayoutParams(
                TableRow.LayoutParams.WRAP_CONTENT, // CHANGE: Set width to WRAP_CONTENT
                TableRow.LayoutParams.WRAP_CONTENT
                // REMOVED: 1f weight here, as it conflicts with horizontal scrolling
        );
        textView.setLayoutParams(params);
        return textView;
    }

    private void showEditCellDialog(final TextView cellTextView, final GirthRange girthRange, final double length, final String priceKey) {
        if (!isAuthenticated) {
            Toast.makeText(this, "Passcode required to edit prices.", Toast.LENGTH_SHORT).show();
            // No need to prompt here again, as listener check should prevent reaching this.
            // If somehow reached, the user will be blocked.
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(String.format(Locale.getDefault(), "Edit Price for Girth %s in, Length %.1f ft", girthRange.toString(), length));

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Double currentPrice = unitPrices.get(priceKey);
        // Display current price or "0.0" if not set
        input.setText(currentPrice != null ? String.format(Locale.getDefault(), "%.1f", currentPrice) : "0.0");
        input.setSelectAllOnFocus(true); // Select all text when focused
        builder.setView(input);

        builder.setPositiveButton("Update", (dialog, which) -> {
            String newPriceStr = input.getText().toString().trim();
            if (newPriceStr.isEmpty()) {
                Toast.makeText(this, "Price cannot be empty.", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                double newPrice = Double.parseDouble(newPriceStr);
                if (newPrice < 0) {
                    Toast.makeText(this, "Price cannot be negative.", Toast.LENGTH_SHORT).show();
                    return;
                }

                BigDecimal bd = new BigDecimal(newPrice);
                bd = bd.setScale(1, RoundingMode.HALF_UP); // Round to 1 decimal place
                double roundedNewPrice = bd.doubleValue();

                unitPrices.put(priceKey, roundedNewPrice);
                cellTextView.setText(String.format(Locale.getDefault(), "%.1f", roundedNewPrice));

                // Change color to RED if updated price is positive, else BLACK
                if (roundedNewPrice > 0.0) {
                    cellTextView.setTextColor(Color.RED);
                } else {
                    cellTextView.setTextColor(Color.BLACK);
                }

                saveTableData();
                Toast.makeText(this, "Price updated successfully!", Toast.LENGTH_SHORT).show();

            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid number format for unit price: " + e.getMessage(), e);
                Toast.makeText(this, "Please enter a valid number for price.", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
    }

    /**
     * Helper to create a unique key for the unitPrices map.
     * Uses a specific format to ensure consistency when retrieving from MainActivity.
     */
    private String getPriceKey(GirthRange girthRange, double length) {
        // Use Locale.US for consistent decimal separator
        return String.format(Locale.US, "G_%.1f-%.1f_L_%.1f", girthRange.getStart(), girthRange.getEnd(), length);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reset authentication status when returning to the activity
        // This forces re-authentication if the user leaves and comes back to PriceTableActivity
        isAuthenticated = false;
        enableEditingUI(false); // Disable UI until authenticated
        // --- REMOVED: promptForPasscode() call from here ---
        // It's already called in onCreate() for initial entry,
        // and clicking "Generate Table" or "Edit Cell" if not authenticated will re-prompt.
        // This fixes the double prompt on initial load.

        // Rebuild table to update clickable status
        rebuildTableFromData();
    }


    // --- Menu setup for Export/Import ---
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_price_table, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_export_price_list) {
            checkAndExportPriceList();
            return true;
        } else if (id == R.id.action_import_price_list) {
            checkAndImportPriceList();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // --- Export Logic ---
    private void checkAndExportPriceList() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportPriceList();
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST_CODE_EXPORT);
            } else {
                exportPriceList();
            }
        }
    }

    private void exportPriceList() {
        if (currentGirthRanges.isEmpty() || currentLengthValues.isEmpty() || unitPrices.isEmpty()) {
            Toast.makeText(this, "No price table data to export.", Toast.LENGTH_SHORT).show();
            return;
        }

        PriceTableData dataToExport = new PriceTableData(unitPrices, currentGirthRanges, currentLengthValues);
        String jsonString = gson.toJson(dataToExport);

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = "wood_price_list_" + timeStamp + ".json";

        OutputStream fos = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = getContentResolver();
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                // Save to a sub-folder within Downloads
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + File.separator + "WoodCalculator");
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);

                if (uri == null) {
                    Toast.makeText(this, "Failed to create file for export.", Toast.LENGTH_SHORT).show();
                    return;
                }
                fos = resolver.openOutputStream(uri);
            } else {
                File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WoodCalculator");
                if (!directory.exists()) {
                    directory.mkdirs();
                }
                File file = new File(directory, fileName);
                fos = new FileOutputStream(file);
            }

            if (fos != null) {
                fos.write(jsonString.getBytes());
                Toast.makeText(this, "Price list exported to Downloads/WoodCalculator/" + fileName, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Failed to open output stream for export.", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error exporting price list: " + e.getMessage(), e);
            Toast.makeText(this, "Error exporting price list: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing stream during export: " + e.getMessage(), e);
                }
            }
        }
    }


    // --- Import Logic ---
    private void checkAndImportPriceList() {
        // For ACTION_OPEN_DOCUMENT, READ_EXTERNAL_STORAGE is not strictly necessary for Q+
        // as user explicitly picks the file. For older APIs, it might be needed.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST_CODE_IMPORT);
        } else {
            openFilePickerForImport();
        }
    }

    private void openFilePickerForImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json"); // Filter for JSON files
        try {
            startActivityForResult(intent, PICK_FILE_REQUEST_CODE);
        } catch (Exception e) {
            Toast.makeText(this, "No file manager found to pick JSON file.", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Error opening file picker: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE_EXPORT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                exportPriceList();
            } else {
                Toast.makeText(this, "Permission denied. Cannot export price list.", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == PERMISSION_REQUEST_CODE_IMPORT) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openFilePickerForImport();
            } else {
                Toast.makeText(this, "Permission denied. Cannot import price list.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri uri = data.getData();
                importPriceListFromFile(uri);
            } else {
                Toast.makeText(this, "No file selected.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void importPriceListFromFile(Uri uri) {
        InputStream is = null;
        BufferedReader reader = null;
        try {
            ContentResolver contentResolver = getContentResolver();
            is = contentResolver.openInputStream(uri);
            if (is == null) {
                Toast.makeText(this, "Failed to open selected file.", Toast.LENGTH_SHORT).show();
                return;
            }

            reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            String jsonString = sb.toString();

            Type priceTableDataType = new TypeToken<PriceTableData>() {}.getType();
            PriceTableData importedData = gson.fromJson(jsonString, priceTableDataType);

            if (importedData != null && importedData.getUnitPrices() != null &&
                    importedData.getGirthRanges() != null && importedData.getLengthValues() != null) {

                // Update current data with imported data
                this.unitPrices = importedData.getUnitPrices();
                this.currentGirthRanges = importedData.getGirthRanges();
                this.currentLengthValues = importedData.getLengthValues();

                // Also update the EditText fields for user visibility
                editTextGirthRanges.setText(formatGirthRangesForDisplay(currentGirthRanges));
                editTextLengthValues.setText(formatLengthValuesForDisplay(currentLengthValues));

                saveTableData(); // Save the imported data to SharedPreferences
                rebuildTableFromData(); // Rebuild UI table to reflect imported data
                Toast.makeText(this, "Price list imported successfully!", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Invalid or incomplete price list data in the file.", Toast.LENGTH_LONG).show();
            }

        } catch (IOException | com.google.gson.JsonParseException e) {
            Log.e(TAG, "Error importing price list: " + e.getMessage(), e);
            Toast.makeText(this, "Error importing price list: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing reader: " + e.getMessage());
                }
            }
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing input stream: " + e.getMessage());
                }
            }
        }
    }

    private String formatGirthRangesForDisplay(List<GirthRange> ranges) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ranges.size(); i++) {
            sb.append(ranges.get(i).getStart());
            sb.append("-");
            sb.append(ranges.get(i).getEnd());
            if (i < ranges.size() - 1) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }

    private String formatLengthValuesForDisplay(List<Double> lengths) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lengths.size(); i++) {
            sb.append(lengths.get(i));
            if (i < lengths.size() - 1) {
                sb.append(", ");
            }
        }
        return sb.toString();
    }
}