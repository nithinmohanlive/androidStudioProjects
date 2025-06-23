package com.example.woodcalculator;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.TableRow;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator; // Keep Comparator import for anonymous class
import java.util.Map;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * MainActivity handles the core functionality of the Wood Calculator app,
 * including calculating wood log volumes, displaying entries in a table,
 * managing log entries using SharedPreferences, and generating PDF bills.
 */
public class MainActivity extends AppCompatActivity {

    // Request code for storage permission
    private static final int PERMISSION_REQUEST_CODE = 100;
    // Tag for logging messages
    private static final String TAG = "MainActivity";
    // Name for SharedPreferences file
    private static final String PREFS_NAME = "WoodCalculatorPrefs";

    // Keys for storing data in SharedPreferences
    private static final String KEY_UNIT_PRICES = "unitPrices";
    private static final String KEY_GIRTH_RANGES_PARSED = "girthRangesParsed";
    private static final String KEY_LENGTH_VALUES_PARSED = "lengthValuesParsed";
    private static final String KEY_LOG_ENTRIES = "logEntriesList";

    // UI elements
    private EditText editTextGirth;
    private EditText editTextLength;
    private LinearLayout tableContainer;
    // buttonGenerateNewBill is now local to setListeners/initViews
    private TextView totalVolumeTextView;
    private TextView grandTotalTextView;
    private ScrollView mainScrollView;

    // Data structures for managing log entries and pricing
    private List<LogEntry> logEntries;
    private SharedPreferences sharedPreferences;
    private Gson gson;
    private Map<String, Double> loadedUnitPrices;
    private List<GirthRange> tableGirthRanges;
    private List<Double> tableLengthValues;

    /**
     * Represents a girth range (e.g., 5.0-10.0 inches).
     */
    public static class GirthRange {
        private final double start; // Start of the girth range (immutable)
        private final double end;   // End of the girth range (immutable)

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
            // Format for display: "5.0-10.0" or "10.0" if start is 0.0
            if (start == 0.0) {
                return String.format(Locale.US, "%.1f", end);
            }
            return String.format(Locale.US, "%.1f-%.1f", start, end);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            GirthRange that = (GirthRange) obj;
            // Compare doubles with a small epsilon for floating point accuracy
            return Math.abs(this.start - that.start) < 0.001 &&
                    Math.abs(this.end - that.end) < 0.001;
        }

        @Override
        public int hashCode() {
            // Generate hash code based on start and end values
            return Objects.hash(start, end);
        }
    }

    /**
     * Represents a single log entry, including its dimensions, calculated volume,
     * unit price, and total cost for that log.
     */
    public static class LogEntry {
        double girth;
        double length;
        double volume;
        double unitPrice;
        double logTotal;

        public LogEntry(double girth, double length, double volume, double unitPrice, double logTotal) {
            this.girth = girth;
            this.length = length;
            this.volume = volume;
            this.unitPrice = unitPrice;
            this.logTotal = logTotal;
        }

        // Getters for log entry properties
        public double getGirth() { return girth; }
        public double getLength() { return length; }
        public double getVolume() { return volume; }
        public double getUnitPrice() { return unitPrice; }
        public double getLogTotal() { return logTotal; }

        // Setters for log entry properties (used for editing)
        public void setGirth(double girth) { this.girth = girth; }
        public void setLength(double length) { this.length = length; }
        public void setVolume(double volume) { this.volume = volume; }
        public void setUnitPrice(double unitPrice) { this.unitPrice = unitPrice; }
        public void setLogTotal(double logTotal) { this.logTotal = logTotal; }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize SharedPreferences and Gson for data persistence
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        gson = new Gson();

        // Initialize UI components
        initViews();
        // Load previously saved log entries
        loadLogEntries();
        // Re-populate the table with loaded entries
        repopulateTable();
        // Update total volume and grand total display
        updateTotals();

        // Set click listeners for buttons
        setListeners();
        // Load pricing data from SharedPreferences
        loadPricingData();
    }

    /**
     * Initializes all UI components by finding them by their IDs.
     */
    private void initViews() {
        editTextGirth = findViewById(R.id.editTextGirth);
        editTextLength = findViewById(R.id.editTextLength);

        // Buttons (local references are sufficient as they are used immediately in setListeners)
        findViewById(R.id.buttonCalculate); // Calculate button
        findViewById(R.id.buttonGenerateBill); // Generate Bill button
        // Direct findViewById usage as the field was unnecessary
        // private Button buttonGenerateNewBill; is removed from class fields
        // buttonGenerateNewBill = findViewById(R.id.buttonGenerateNewBill); // Start New Bill button

        tableContainer = findViewById(R.id.tableContainer); // LinearLayout to hold table rows

        totalVolumeTextView = findViewById(R.id.totalVolumeTextView); // TextView for total volume
        grandTotalTextView = findViewById(R.id.grandTotalTextView); // TextView for grand total
        mainScrollView = findViewById(R.id.mainScrollView); // ScrollView containing the table
    }

    /**
     * Sets click listeners for the main action buttons.
     */
    private void setListeners() {
        findViewById(R.id.buttonCalculate).setOnClickListener(v -> calculateVolume());
        findViewById(R.id.buttonGenerateBill).setOnClickListener(v -> promptForClientNameAndGenerateBill());
        // Directly set listener on the view found by ID
        findViewById(R.id.buttonGenerateNewBill).setOnClickListener(v -> clearTableAndStartNewBill());
    }

    /**
     * Loads log entries from SharedPreferences.
     */
    private void loadLogEntries() {
        String json = sharedPreferences.getString(KEY_LOG_ENTRIES, null);
        Type type = new TypeToken<ArrayList<LogEntry>>() {}.getType();
        logEntries = gson.fromJson(json, type);
        // Initialize as an empty list if no entries are found or parsing fails
        if (logEntries == null) {
            logEntries = new ArrayList<>();
        }
        Log.d(TAG, "Loaded " + logEntries.size() + " log entries.");
    }

    /**
     * Saves the current list of log entries to SharedPreferences.
     */
    private void saveLogEntries() {
        String json = gson.toJson(logEntries);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(KEY_LOG_ENTRIES, json);
        editor.apply(); // Apply changes asynchronously
        Log.d(TAG, "Saved " + logEntries.size() + " log entries.");
    }

    /**
     * Clears the current UI table and repopulates it with entries from `logEntries` list.
     */
    private void repopulateTable() {
        tableContainer.removeAllViews(); // Remove all existing rows
        // Add header only if there are entries to display
        if (!logEntries.isEmpty()) {
            addTableHeader();
        }
        // Add each log entry to the UI table
        for (int i = 0; i < logEntries.size(); i++) {
            addEntryToTableUI(logEntries.get(i), i + 1, i); // Pass Sl. No. (1-based) and actual index (0-based)
        }
    }

    /**
     * Loads pricing data (unit prices, girth ranges, length values) from SharedPreferences.
     * Ensures null-safety for parsed lists.
     */
    private void loadPricingData() {
        String pricesJson = sharedPreferences.getString(KEY_UNIT_PRICES, null);
        if (pricesJson != null) {
            Type mapType = new TypeToken<Map<String, Double>>() {}.getType();
            loadedUnitPrices = gson.fromJson(pricesJson, mapType);
            if (loadedUnitPrices == null) { // Handle case where JSON is malformed or represents 'null'
                loadedUnitPrices = new LinkedHashMap<>();
                Log.w(TAG, "Failed to parse unit prices from JSON. Initializing empty map.");
            }
            Log.d(TAG, "Loaded unit prices: " + loadedUnitPrices.size() + " entries.");
        } else {
            loadedUnitPrices = new LinkedHashMap<>(); // Initialize empty if not found
            Log.w(TAG, "No custom unit prices map found in SharedPreferences.");
        }

        String girthRangesJson = sharedPreferences.getString(KEY_GIRTH_RANGES_PARSED, null);
        if (girthRangesJson != null) {
            Type girthRangeListType = new TypeToken<List<GirthRange>>() {}.getType();
            List<GirthRange> tempGirthRanges = gson.fromJson(girthRangesJson, girthRangeListType);
            if (tempGirthRanges != null) {
                tableGirthRanges = tempGirthRanges;
                // Reverted to API 21 compatible sorting logic
                Collections.sort(tableGirthRanges, new Comparator<>() {
                    @Override
                    public int compare(GirthRange r1, GirthRange r2) {
                        return Double.compare(r1.getStart(), r2.getStart());
                    }
                });
                Log.d(TAG, "Loaded girth ranges: " + tableGirthRanges.size() + " entries.");
            } else {
                tableGirthRanges = new ArrayList<>(); // Initialize to empty list if parsing failed
                Log.w(TAG, "Failed to parse girth ranges from JSON. Initializing empty list.");
            }
        } else {
            tableGirthRanges = new ArrayList<>();
            Log.w(TAG, "No custom girth ranges list found in SharedPreferences.");
        }

        String lengthJson = sharedPreferences.getString(KEY_LENGTH_VALUES_PARSED, null);
        if (lengthJson != null) {
            Type doubleListType = new TypeToken<List<Double>>() {}.getType();
            List<Double> tempLengthValues = gson.fromJson(lengthJson, doubleListType);
            if (tempLengthValues != null) {
                tableLengthValues = tempLengthValues;
                Collections.sort(tableLengthValues); // Sort length values
                Log.d(TAG, "Loaded length values: " + tableLengthValues.size() + " entries.");
            } else {
                tableLengthValues = new ArrayList<>(); // Initialize to empty list if parsing failed
                Log.w(TAG, "Failed to parse length values from JSON. Initializing empty list.");
            }
        } else {
            tableLengthValues = new ArrayList<>();
            Log.w(TAG, "No custom length values list found in SharedPreferences.");
        }

        // Warn user if pricing data is incomplete
        if (loadedUnitPrices.isEmpty() || tableGirthRanges.isEmpty() || tableLengthValues.isEmpty()) {
            Toast.makeText(this, "Price table is not fully configured. Please go to 'Price Table' to set girth ranges and lengths.", Toast.LENGTH_LONG).show();
            Log.w(TAG, "Pricing data is incomplete or empty.");
        }
    }

    /**
     * Finds the closest unit price for a given girth and length based on loaded pricing data.
     * @param inputGirthBd BigDecimal representation of the input girth.
     * @param inputLengthBd BigDecimal representation of the input length.
     * @return A BigDecimal representing the unit price, or BigDecimal.ZERO if not found.
     */
    private BigDecimal findClosestUnitPrice(BigDecimal inputGirthBd, BigDecimal inputLengthBd) {
        double inputGirth = inputGirthBd.doubleValue();
        double inputLength = inputLengthBd.doubleValue();

        // Check if pricing data is available
        if (loadedUnitPrices == null || loadedUnitPrices.isEmpty() ||
                tableGirthRanges == null || tableGirthRanges.isEmpty() ||
                tableLengthValues == null || tableLengthValues.isEmpty()) {
            Toast.makeText(this, "Price table data is missing or invalid. Cannot calculate price.", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Price table data is null or empty. Check PriceTableActivity configuration.");
            return BigDecimal.ZERO;
        }

        // Find the matching girth range
        GirthRange matchedGirthRange = null;
        for (GirthRange range : tableGirthRanges) {
            if (inputGirth > range.getStart() && inputGirth <= range.getEnd()) {
                matchedGirthRange = range;
                break;
            } else if (range.getStart() == 0.0 && inputGirth >= 0.0 && inputGirth <= range.getEnd()) {
                // Handle ranges starting exactly at 0.0, which are inclusive of 0
                matchedGirthRange = range;
                break;
            }
        }

        // If no girth range is found, return 0 unit price
        if (matchedGirthRange == null) {
            Log.w(TAG, String.format(Locale.getDefault(), "No girth range found for %.2f inches. Returning 0 for unit price.", inputGirth));
            return BigDecimal.ZERO;
        }

        // Find the closest length value from the defined lengths
        double closestLength = getClosestValue(inputLength, tableLengthValues);

        // Construct the key to look up the unit price
        String priceKey = getPriceKey(matchedGirthRange, closestLength);
        Double unitPrice = loadedUnitPrices.get(priceKey);

        // If unit price not found for the key, return 0
        if (unitPrice == null) {
            Log.w(TAG, String.format("Unit price not found for key '%s' (Girth range %s, closest L %.2f). Returning 0.",
                    priceKey, matchedGirthRange, closestLength));
            return BigDecimal.ZERO;
        }
        Log.d(TAG, String.format(Locale.getDefault(), "Found unit price %.2f for key '%s'", unitPrice, priceKey));
        // Return unit price rounded to 2 decimal places
        return new BigDecimal(unitPrice).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Finds the closest value in a sorted list of doubles to a given target.
     * Used for matching input length to predefined length values.
     * @param target The value to find the closest match for.
     * @param values A sorted list of values to search within.
     * @return The closest value from the list.
     */
    private double getClosestValue(double target, List<Double> values) {
        if (values.isEmpty()) {
            return target; // Should not happen if tables are configured, but as a safeguard
        }

        // Perform binary search to find the index or insertion point
        int i = Collections.binarySearch(values, target);
        if (i >= 0) {
            return values.get(i); // Exact match found
        }

        // No exact match, determine insertion point
        int insertionPoint = -i - 1;

        // Handle edge cases: target is smaller than first element
        if (insertionPoint == 0) {
            return values.get(0);
        }
        // Handle edge cases: target is larger than last element
        if (insertionPoint == values.size()) {
            return values.get(values.size() - 1);
        }

        // Compare distance to the element before and after the insertion point
        double lowerValue = values.get(insertionPoint - 1);
        double upperValue = values.get(insertionPoint);

        double diffLower = Math.abs(target - lowerValue);
        double diffUpper = Math.abs(target - upperValue);

        if (diffLower < diffUpper) {
            return lowerValue;
        } else if (diffUpper < diffLower) {
            return upperValue;
        } else {
            return upperValue; // If equally close, default to the larger value
        }
    }

    /**
     * Generates a unique key string for looking up unit prices in the map.
     * @param girthRange The matched GirthRange object.
     * @param length The closest length value.
     * @return A string key like "G_start-end_L_length".
     */
    private String getPriceKey(GirthRange girthRange, double length) {
        return String.format(Locale.US, "G_%.1f-%.1f_L_%.1f", girthRange.getStart(), girthRange.getEnd(), length);
    }

    /**
     * Calculates the volume of a wood log based on user input for girth and length,
     * determines the unit price, calculates the log total, and adds the entry to the table.
     */
    @SuppressLint("DefaultLocale")
    private void calculateVolume() {
        String girthStr = editTextGirth.getText().toString().trim();
        String lengthStr = editTextLength.getText().toString().trim();

        if (girthStr.isEmpty() || lengthStr.isEmpty()) {
            Toast.makeText(this, "Please enter both Girth and Length.", Toast.LENGTH_SHORT).show();
            return;
        }

        BigDecimal girthBd;
        BigDecimal lengthBd;

        try {
            girthBd = new BigDecimal(girthStr);
            lengthBd = new BigDecimal(lengthStr);

            // Validate positive input
            if (girthBd.compareTo(BigDecimal.ZERO) <= 0 || lengthBd.compareTo(BigDecimal.ZERO) <= 0) {
                Toast.makeText(this, "Girth and Length must be positive values.", Toast.LENGTH_SHORT).show();
                return;
            }

            // Round input values for consistency
            girthBd = girthBd.setScale(2, RoundingMode.HALF_UP);
            lengthBd = lengthBd.setScale(2, RoundingMode.HALF_UP);

        } catch (NumberFormatException e) {
            Toast.makeText(this, "Please enter valid numbers for Girth and Length.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Volume calculation: (Girth^2 * Length) / 2304.
        // Calculated with high precision first, then rounded for display/storage.
        BigDecimal rawVolume = girthBd.multiply(girthBd)
                .multiply(lengthBd)
                .divide(new BigDecimal("2304"), 6, RoundingMode.HALF_UP); // High precision
        BigDecimal displayVolume = rawVolume.setScale(1, RoundingMode.HALF_UP); // Rounded for display (1 decimal)

        BigDecimal unitPrice = findClosestUnitPrice(girthBd, lengthBd);
        // Calculate total cost for the log using the rounded volume
        BigDecimal logTotal = displayVolume.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);

        // Create a new LogEntry object
        LogEntry newEntry = new LogEntry(
                girthBd.doubleValue(),
                lengthBd.doubleValue(),
                displayVolume.doubleValue(),
                unitPrice.doubleValue(),
                logTotal.doubleValue()
        );
        logEntries.add(newEntry); // Add to the list
        saveLogEntries(); // Save the updated list to SharedPreferences

        // Add the new entry to the UI table and update totals
        addEntryToTableUI(newEntry, logEntries.size(), logEntries.size() - 1);
        updateTotals();

        // Scroll the ScrollView to the bottom to show the newly added entry
        mainScrollView.post(() -> mainScrollView.fullScroll(ScrollView.FOCUS_DOWN));

        // Clear input fields for next entry
        editTextGirth.setText("");
        editTextLength.setText("");
    }

    /**
     * Adds the table header row to the UI table.
     */
    private void addTableHeader() {
        TableRow headerRow = new TableRow(this);
        headerRow.setBackgroundColor(Color.parseColor("#ADD8E6")); // Light blue background
        headerRow.setPadding(0, 8, 0, 8);

        // Add header TextViews in the specified order
        headerRow.addView(createTableHeaderTextView("Sl. No."));
        headerRow.addView(createTableHeaderTextView("Length (ft)"));
        headerRow.addView(createTableHeaderTextView("Girth (in)"));
        headerRow.addView(createTableHeaderTextView("Volume (cft)"));
        headerRow.addView(createTableHeaderTextView("Unit Price"));
        headerRow.addView(createTableHeaderTextView("Total"));

        tableContainer.addView(headerRow);
    }

    /**
     * Adds a single log entry as a new row to the UI table.
     * @param entry The LogEntry object to display.
     * @param slNo The serial number for this entry (1-based).
     * @param entryIndex The actual index of the entry in the `logEntries` list (0-based).
     */
    private void addEntryToTableUI(LogEntry entry, int slNo, int entryIndex) {
        TableRow row = new TableRow(this);
        // Set alternating row colors for better readability
        if (slNo % 2 == 0) {
            row.setBackgroundColor(Color.parseColor("#F0F8FF")); // Alice Blue
        } else {
            row.setBackgroundColor(Color.WHITE);
        }
        row.setPadding(0, 4, 0, 4);

        // Set an onClickListener for the row to enable editing/deleting functionality
        row.setOnClickListener(v -> showEditDeleteDialog(entryIndex));

        // Add data TextViews for each column.
        // Sl. No., Length, Girth, Volume, Unit Price, Total
        row.addView(createTableDataTextView(String.valueOf(slNo)));
        row.addView(createTableDataTextView(String.format(Locale.getDefault(), "%.2f", entry.getLength())));
        row.addView(createTableDataTextView(String.format(Locale.getDefault(), "%.2f", entry.getGirth())));
        // Volume is formatted to 1 decimal place as per calculation
        row.addView(createTableDataTextView(String.format(Locale.getDefault(), "%.1f", entry.getVolume())));

        // Create TextView for Unit Price with conditional red color
        TextView unitPriceTextView = createTableDataTextView(String.format(Locale.getDefault(), "%.2f", entry.getUnitPrice()));
        if (entry.getUnitPrice() == 0.0) { // If unit price is 0 (meaning not found), set text color to RED
            unitPriceTextView.setTextColor(Color.RED);
        }
        row.addView(unitPriceTextView);

        row.addView(createTableDataTextView(String.format(Locale.getDefault(), "%.2f", entry.getLogTotal())));

        tableContainer.addView(row); // Add the new row to the table container
    }

    /**
     * Displays an AlertDialog allowing the user to edit or delete a selected log entry.
     * @param index The 0-based index of the log entry in the `logEntries` list.
     */
    private void showEditDeleteDialog(final int index) {
        // Validate index to prevent crashes
        if (index < 0 || index >= logEntries.size()) {
            Toast.makeText(this, "Invalid row selected.", Toast.LENGTH_SHORT).show();
            return;
        }

        final LogEntry currentEntry = logEntries.get(index);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Edit/Delete Entry (Sl. No.: " + (index + 1) + ")");

        // Create a LinearLayout to hold input fields
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 20);

        // EditText for Length
        final EditText inputLength = new EditText(this);
        inputLength.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        inputLength.setHint("Length (ft)");
        inputLength.setText(String.format(Locale.getDefault(), "%.2f", currentEntry.getLength()));
        layout.addView(inputLength);

        // EditText for Girth
        final EditText inputGirth = new EditText(this);
        inputGirth.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        inputGirth.setHint("Girth (in)");
        inputGirth.setText(String.format(Locale.getDefault(), "%.2f", currentEntry.getGirth()));
        layout.addView(inputGirth);

        // EditText for Unit Price
        final EditText inputUnitPrice = new EditText(this);
        inputUnitPrice.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        inputUnitPrice.setHint("Unit Price");
        inputUnitPrice.setText(String.format(Locale.getDefault(), "%.2f", currentEntry.getUnitPrice()));
        layout.addView(inputUnitPrice);

        builder.setView(layout);

        // "Update" button logic
        builder.setPositiveButton("Update", (dialog, which) -> {
            try {
                BigDecimal newLength = new BigDecimal(inputLength.getText().toString());
                BigDecimal newGirth = new BigDecimal(inputGirth.getText().toString());
                BigDecimal newUnitPrice = new BigDecimal(inputUnitPrice.getText().toString());

                // Input validation for updated values
                if (newLength.compareTo(BigDecimal.ZERO) <= 0 || newGirth.compareTo(BigDecimal.ZERO) <= 0 || newUnitPrice.compareTo(BigDecimal.ZERO) < 0) {
                    Toast.makeText(this, "Values must be positive (Unit Price can be zero).", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Recalculate volume and total based on new inputs
                BigDecimal calculatedVolume = newGirth.multiply(newGirth)
                        .multiply(newLength)
                        .divide(new BigDecimal("2304"), 6, RoundingMode.HALF_UP);
                BigDecimal displayVolume = calculatedVolume.setScale(1, RoundingMode.HALF_UP);

                BigDecimal newLogTotal = displayVolume.multiply(newUnitPrice).setScale(2, RoundingMode.HALF_UP);

                // Update the LogEntry object at the specified index
                currentEntry.setGirth(newGirth.doubleValue());
                currentEntry.setLength(newLength.doubleValue());
                currentEntry.setVolume(displayVolume.doubleValue());
                currentEntry.setUnitPrice(newUnitPrice.doubleValue());
                currentEntry.setLogTotal(newLogTotal.doubleValue());

                saveLogEntries(); // Save the modified list
                repopulateTable(); // Refresh UI table to reflect changes
                updateTotals(); // Update grand totals
                Toast.makeText(MainActivity.this, "Entry updated successfully.", Toast.LENGTH_SHORT).show();

            } catch (NumberFormatException e) {
                Toast.makeText(MainActivity.this, "Invalid number format. Please enter valid numbers.", Toast.LENGTH_SHORT).show();
            }
        });

        // "Delete" button logic
        builder.setNeutralButton("Delete", (dialog, which) -> {
            // Show a confirmation dialog before deleting
            new AlertDialog.Builder(this)
                    .setTitle("Confirm Delete")
                    .setMessage("Are you sure you want to delete this entry (Sl. No.: " + (index + 1) + ")?")
                    .setPositiveButton("Yes", (deleteDialog, deleteWhich) -> {
                        logEntries.remove(index); // Remove entry from list
                        saveLogEntries(); // Save updated list
                        repopulateTable(); // Refresh UI table
                        updateTotals(); // Update grand totals
                        Toast.makeText(MainActivity.this, "Entry deleted.", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("No", null) // No action if "No" is clicked
                    .show();
        });

        // "Cancel" button logic
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    /**
     * Creates a TextView for table headers, setting its style (bold, centered, fixed font size).
     * @param text The text content for the header.
     * @return A styled TextView for table headers.
     */
    private TextView createTableHeaderTextView(String text) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setGravity(Gravity.CENTER);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12); // Standard header font size
        textView.setTypeface(null, android.graphics.Typeface.BOLD); // Bold text
        textView.setPadding(8, 8, 8, 8);
        TableRow.LayoutParams params = new TableRow.LayoutParams(
                0, // 0 width with weight will distribute space evenly
                TableRow.LayoutParams.WRAP_CONTENT,
                1f // Weight for even distribution
        );
        textView.setLayoutParams(params);
        return textView;
    }

    /**
     * Creates a TextView for table data entries, setting its style (bold, black, centered, larger font size).
     * @param text The text content for the data cell.
     * @return A styled TextView for table data.
     */
    private TextView createTableDataTextView(String text) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setGravity(Gravity.CENTER);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14); // Increased font size (14sp + 2 points = 16sp)
        textView.setTypeface(null, android.graphics.Typeface.BOLD); // Make text bold
        textView.setTextColor(Color.BLACK); // Ensure text is black by default
        textView.setPadding(8, 4, 8, 4);
        TableRow.LayoutParams params = new TableRow.LayoutParams(
                0, // 0 width with weight will distribute space evenly
                TableRow.LayoutParams.WRAP_CONTENT,
                1f // Weight for even distribution
        );
        textView.setLayoutParams(params);
        return textView;
    }


    /**
     * Calculates the total volume and grand total from all log entries
     * and updates the respective TextViews on the UI.
     */
    private void updateTotals() {
        BigDecimal totalVolume = BigDecimal.ZERO;
        BigDecimal grandTotal = BigDecimal.ZERO;

        for (LogEntry entry : logEntries) {
            // Add volume and log total from each entry
            totalVolume = totalVolume.add(new BigDecimal(String.format(Locale.getDefault(), "%.1f", entry.getVolume())));
            grandTotal = grandTotal.add(new BigDecimal(String.valueOf(entry.getLogTotal())));
        }

        // Update UI TextViews with calculated totals
        totalVolumeTextView.setText(String.format(Locale.getDefault(), "Total Volume: %.1f cft", totalVolume.doubleValue()));
        grandTotalTextView.setText(String.format(Locale.getDefault(), "Grand Total: ₹ %.2f", grandTotal.doubleValue()));
    }

    /**
     * Clears all current log entries, starts a new bill, and refreshes the UI.
     */
    private void clearTableAndStartNewBill() {
        // Show confirmation dialog before clearing
        new AlertDialog.Builder(this)
                .setTitle("Start New Bill?")
                .setMessage("This will clear all current entries. Are you sure?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    logEntries.clear(); // Clear the list
                    saveLogEntries(); // Save the empty list
                    repopulateTable(); // Clear UI table
                    updateTotals(); // Reset totals to zero
                    Toast.makeText(MainActivity.this, "New Bill Started.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("No", null) // No action if "No" is clicked
                .show();
    }

    /**
     * Prompts the user to enter a client name before generating the PDF bill.
     */
    private void promptForClientNameAndGenerateBill() {
        if (logEntries.isEmpty()) {
            Toast.makeText(this, "No entries to generate a bill.", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Client Name");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Client Name");
        builder.setView(input);

        builder.setPositiveButton("Generate Bill", (dialog, which) -> {
            String clientName = input.getText().toString().trim();
            if (clientName.isEmpty()) {
                clientName = "Unknown_Client"; // Use a default name if no input
            }
            generateBill(clientName); // Proceed to generate bill
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    /**
     * Handles PDF bill generation, choosing between MediaStore (Android Q+) or legacy approach.
     * @param clientName The name of the client to include in the bill.
     */
    private void generateBill(String clientName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10 (API 29) and above, use MediaStore for Downloads
            createAndSavePdfQ(clientName);
        } else {
            // For older Android versions, request WRITE_EXTERNAL_STORAGE permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        PERMISSION_REQUEST_CODE);
            } else {
                createAndSavePdfLegacy(clientName);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // If permission granted, retry generating the bill with a placeholder name
                createAndSavePdfLegacy("Generated_Bill");
            } else {
                Toast.makeText(this, "Permission denied. Cannot save PDF bill.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Creates and saves a PDF bill to the Downloads/WoodBills directory for API levels below 29 (Android Q).
     * Uses FileOutputStream for direct file access.
     * @param clientName The name of the client for the bill.
     */
    private void createAndSavePdfLegacy(String clientName) {
        // Setup PDF document
        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create(); // A4 size (points)
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();
        Paint paint = new Paint();

        int x = 40; // X-coordinate for drawing
        int y = 40; // Y-coordinate for drawing
        int lineHeight = 20; // Vertical spacing between lines

        // Draw bill title
        paint.setTextSize(24f);
        paint.setColor(Color.BLACK);
        canvas.drawText("Wood Bill - " + clientName, x, y, paint);
        y += lineHeight * 2;

        // Draw date and time
        paint.setTextSize(12f);
        canvas.drawText("Date: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()), x, y, paint);
        y += lineHeight * 2;

        // Draw table headers for PDF
        paint.setTextSize(12f);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        canvas.drawText("Sl. No.", x, y, paint);
        canvas.drawText("Length (ft)", x + 70, y, paint);
        canvas.drawText("Girth (in)", x + 140, y, paint);
        canvas.drawText("Volume (cft)", x + 210, y, paint);
        canvas.drawText("Unit Price", x + 300, y, paint);
        canvas.drawText("Total", x + 380, y, paint);
        y += lineHeight;
        canvas.drawLine(x, y - 5, x + 450, y - 5, paint); // Underline headers
        y += 10;

        // Draw table data for each log entry
        paint.setTypeface(android.graphics.Typeface.DEFAULT); // Reset font to normal for data
        double totalVolume = 0;
        double grandTotal = 0;
        for (int i = 0; i < logEntries.size(); i++) {
            LogEntry entry = logEntries.get(i);
            canvas.drawText(String.valueOf(i + 1), x, y, paint);
            canvas.drawText(String.format(Locale.getDefault(), "%.2f", entry.getLength()), x + 70, y, paint);
            canvas.drawText(String.format(Locale.getDefault(), "%.2f", entry.getGirth()), x + 140, y, paint);
            canvas.drawText(String.format(Locale.getDefault(), "%.1f", entry.getVolume()), x + 210, y, paint);

            // Set Unit Price color (red if 0.0, black otherwise)
            if (entry.getUnitPrice() == 0.0) {
                paint.setColor(Color.RED);
            } else {
                paint.setColor(Color.BLACK);
            }
            canvas.drawText(String.format(Locale.getDefault(), "%.2f", entry.getUnitPrice()), x + 300, y, paint);

            paint.setColor(Color.BLACK); // Reset color to black for subsequent text on the line
            canvas.drawText(String.format(Locale.getDefault(), "%.2f", entry.getLogTotal()), x + 380, y, paint);
            y += lineHeight;

            totalVolume += entry.getVolume();
            grandTotal += entry.getLogTotal();

            // Add new page if content exceeds current page height
            if (y > 800 && (i < logEntries.size() - 1)) {
                document.finishPage(page);
                pageInfo = new PdfDocument.PageInfo.Builder(595, 842, document.getPages().size() + 1).create();
                page = document.startPage(pageInfo);
                canvas = page.getCanvas();
                y = 40; // Reset Y for new page
                // Re-draw headers on the new page
                canvas.drawText("Sl. No.", x, y, paint);
                canvas.drawText("Length (ft)", x + 70, y, paint);
                canvas.drawText("Girth (in)", x + 140, y, paint);
                canvas.drawText("Volume (cft)", x + 210, y, paint);
                canvas.drawText("Unit Price", x + 300, y, paint);
                canvas.drawText("Total", x + 380, y, paint);
                y += lineHeight;
                canvas.drawLine(x, y - 5, x + 450, y - 5, paint);
                y += 10;
            }
        }
        y += lineHeight;

        canvas.drawLine(x, y - 5, x + 450, y - 5, paint); // Underline before totals
        y += 10;

        // Draw total volume and grand total
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); // Bold for totals
        canvas.drawText(String.format(Locale.getDefault(), "Total Volume: %.1f cft", totalVolume), x, y, paint);
        y += lineHeight;
        canvas.drawText(String.format(Locale.getDefault(), "Grand Total: ₹ %.2f", grandTotal), x, y, paint);

        document.finishPage(page); // Finish the last page

        // Define directory to save the PDF (Downloads/WoodBills)
        File downloadsDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WoodBills");
        // Check if directory exists, if not, try to create it. Log error if creation fails.
        if (!downloadsDir.exists()) {
            if (!downloadsDir.mkdirs()) { // Check the result of mkdirs()
                Log.e(TAG, "Failed to create directories for PDF: " + downloadsDir.getAbsolutePath());
                Toast.makeText(this, "Failed to create directory for saving bill.", Toast.LENGTH_SHORT).show();
                document.close();
                return; // Exit if directory cannot be created
            }
        }

        // Generate unique filename with timestamp and sanitized client name
        @SuppressLint("DefaultLocale")
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String sanitizedClientName = clientName.replaceAll("[^a-zA-Z0-9_]", "_"); // Sanitize for filename
        String fileName = String.format("Bill_%s_%s.pdf", sanitizedClientName, timeStamp);
        File file = new File(downloadsDir, fileName);

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            document.writeTo(fos); // Write PDF content to file
            Toast.makeText(this, "Bill generated and saved to Downloads/WoodBills/" + fileName, Toast.LENGTH_LONG).show();
            Log.i(TAG, "Bill generated and saved to: " + file.getAbsolutePath());

            // Get URI for FileProvider to allow other apps to open the PDF
            Uri pdfUri = FileProvider.getUriForFile(
                    this,
                    getApplicationContext().getPackageName() + ".fileprovider", // Must match provider authority in manifest
                    file
            );

            // Create an Intent to open the PDF
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(pdfUri, "application/pdf");
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NO_HISTORY);

            startActivity(intent); // Attempt to open the PDF
        } catch (Exception e) {
            Toast.makeText(this, "No application found to open PDF files. Please install a PDF viewer.", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Error opening PDF: " + e.getMessage(), e);
        } finally {
            document.close(); // Close PDF document to release resources
            if (fos != null) {
                try {
                    fos.close(); // Close output stream
                } catch (Exception e) {
                    Log.e(TAG, "Error closing OutputStream: " + e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Creates and saves a PDF bill using MediaStore for API level 29 (Android Q) and above.
     * This handles file storage in a way compliant with Scoped Storage.
     * @param clientName The name of the client for the bill.
     */
    private void createAndSavePdfQ(String clientName) {
        // Setup PDF document
        PdfDocument document = new PdfDocument();
        PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(595, 842, 1).create();
        PdfDocument.Page page = document.startPage(pageInfo);
        Canvas canvas = page.getCanvas();
        Paint paint = new Paint();

        int x = 40;
        int y = 40;
        int lineHeight = 20;

        // Draw bill title
        paint.setTextSize(24f);
        paint.setColor(Color.BLACK);
        canvas.drawText("Wood Bill - " + clientName, x, y, paint);
        y += lineHeight * 2;

        // Draw date and time
        paint.setTextSize(12f);
        canvas.drawText("Date: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()), x, y, paint);
        y += lineHeight * 2;

        // Draw table headers for PDF
        paint.setTextSize(12f);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        canvas.drawText("Sl. No.", x, y, paint);
        canvas.drawText("Length (ft)", x + 70, y, paint);
        canvas.drawText("Girth (in)", x + 140, y, paint);
        canvas.drawText("Volume (cft)", x + 210, y, paint);
        canvas.drawText("Unit Price", x + 300, y, paint);
        canvas.drawText("Total", x + 380, y, paint);
        y += lineHeight;
        canvas.drawLine(x, y - 5, x + 450, y - 5, paint);
        y += 10;

        // Draw table data for each log entry
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        double totalVolume = 0;
        double grandTotal = 0;
        for (int i = 0; i < logEntries.size(); i++) {
            LogEntry entry = logEntries.get(i);
            canvas.drawText(String.valueOf(i + 1), x, y, paint);
            canvas.drawText(String.format(Locale.getDefault(), "%.2f", entry.getLength()), x + 70, y, paint);
            canvas.drawText(String.format(Locale.getDefault(), "%.2f", entry.getGirth()), x + 140, y, paint);
            canvas.drawText(String.format(Locale.getDefault(), "%.1f", entry.getVolume()), x + 210, y, paint);

            // Set Unit Price color (red if 0.0, black otherwise)
            if (entry.getUnitPrice() == 0.0) {
                paint.setColor(Color.RED);
            } else {
                paint.setColor(Color.BLACK);
            }
            canvas.drawText(String.format(Locale.getDefault(), "%.2f", entry.getUnitPrice()), x + 300, y, paint);

            paint.setColor(Color.BLACK); // Reset color to black for subsequent text
            canvas.drawText(String.format(Locale.getDefault(), "%.2f", entry.getLogTotal()), x + 380, y, paint);
            y += lineHeight;

            totalVolume += entry.getVolume();
            grandTotal += entry.getLogTotal();

            // Add new page if content exceeds current page height
            if (y > 800 && (i < logEntries.size() - 1)) {
                document.finishPage(page);
                pageInfo = new PdfDocument.PageInfo.Builder(595, 842, document.getPages().size() + 1).create();
                page = document.startPage(pageInfo);
                canvas = page.getCanvas();
                y = 40;
                // Re-draw headers on new page
                canvas.drawText("Sl. No.", x, y, paint);
                canvas.drawText("Length (ft)", x + 70, y, paint);
                canvas.drawText("Girth (in)", x + 140, y, paint);
                canvas.drawText("Volume (cft)", x + 210, y, paint);
                canvas.drawText("Unit Price", x + 300, y, paint);
                canvas.drawText("Total", x + 380, y, paint);
                y += lineHeight;
                canvas.drawLine(x, y - 5, x + 450, y - 5, paint);
                y += 10;
            }
        }
        y += lineHeight;

        canvas.drawLine(x, y - 5, x + 450, y - 5, paint); // Underline before totals
        y += 10;

        // Draw total volume and grand total
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        canvas.drawText(String.format(Locale.getDefault(), "Total Volume: %.1f cft", totalVolume), x, y, paint);
        y += lineHeight;
        canvas.drawText(String.format(Locale.getDefault(), "Grand Total: ₹ %.2f", grandTotal), x, y, paint);

        document.finishPage(page);

        // Generate unique filename
        @SuppressLint("DefaultLocale")
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String sanitizedClientName = clientName.replaceAll("[^a-zA-Z0-9_]", "_");
        String fileName = String.format("Bill_%s_%s.pdf", sanitizedClientName, timeStamp);

        Uri mediaStoreUri = null;
        OutputStream fos = null;

        try {
            // Save PDF using MediaStore, obtaining a Uri
            mediaStoreUri = PdfSaver.savePdfToDownloadsQ(this, fileName, null);
            if (mediaStoreUri == null) {
                throw new IllegalStateException("Failed to get MediaStore URI.");
            }

            fos = getContentResolver().openOutputStream(mediaStoreUri); // Get output stream from Uri
            if (fos == null) {
                throw new IllegalStateException("Failed to get output stream.");
            }
            document.writeTo(fos); // Write PDF content to the stream
            Toast.makeText(this, "Bill generated and saved to Downloads/WoodBills/" + fileName, Toast.LENGTH_LONG).show();
            Log.i(TAG, "Bill generated and saved to MediaStore URI: " + mediaStoreUri);

            // Create an Intent to open the PDF using the MediaStore URI directly (recommended for Q+)
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(mediaStoreUri, "application/pdf");
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NO_HISTORY);

            startActivity(intent); // Attempt to open the PDF
        } catch (Exception e) {
            Toast.makeText(this, "No application found to open PDF files. Please install a PDF viewer.", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Error opening PDF: " + e.getMessage(), e);
            if (mediaStoreUri != null) {
                getContentResolver().delete(mediaStoreUri, null, null); // Clean up if saving failed
            }
        } finally {
            document.close(); // Close PDF document
            if (fos != null) {
                try {
                    fos.close(); // Close output stream
                } catch (Exception e) {
                    Log.e(TAG, "Error closing OutputStream: " + e.getMessage(), e);
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        // Handle action bar item clicks here.
        int id = item.getItemId();
        if (id == R.id.action_price_table) {
            // Open PriceTableActivity when "Price Table" menu item is clicked
            Intent intent = new Intent(MainActivity.this, PriceTableActivity.class);
            startActivity(intent);
            return true;
        } else if (id == R.id.action_view_bills) {
            // Open ViewBillsActivity when "View Bills" menu item is clicked
            Intent intent = new Intent(MainActivity.this, ViewBillsActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload pricing data, log entries, and refresh the table whenever the activity resumes.
        // This ensures the UI is up-to-date if changes were made in other activities (e.g., PriceTableActivity).
        loadPricingData();
        loadLogEntries();
        repopulateTable();
        updateTotals();
    }
}