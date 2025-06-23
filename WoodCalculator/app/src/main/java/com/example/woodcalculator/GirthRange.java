package com.example.woodcalculator;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;

public class GirthRange implements Serializable {
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

    @Override
    public String toString() {
        // Format for display in table header, e.g., "0.0-18.0"
        return String.format(Locale.getDefault(), "%.1f-%.1f", start, end);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GirthRange that = (GirthRange) o;
        // Use a small epsilon for double comparison due to precision issues
        double EPSILON = 0.001;
        return Math.abs(that.start - start) < EPSILON &&
                Math.abs(that.end - end) < EPSILON;
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end);
    }
}