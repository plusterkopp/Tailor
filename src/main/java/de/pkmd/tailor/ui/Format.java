package de.pkmd.tailor.ui;

import java.util.Locale;

final class Format {

    private Format() {
    }

    static String bytes(long n) {
        if (n < 1024) {
            return n + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = n;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, v < 10 ? "%.2f %s" : v < 100 ? "%.1f %s" : "%.0f %s", v, units[u]);
    }

    static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
