package com.exe101.commission.service;

import java.util.Locale;
import java.util.Map;

// Reference files are accepted by extension; their contents are not inspected.
final class CommissionFiles {
    private static Map<String, String> contentTypes = Map.ofEntries(
        Map.entry("png", "image/png"),
        Map.entry("jpg", "image/jpeg"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("webp", "image/webp"),
        Map.entry("pdf", "application/pdf"),
        Map.entry("psd", "image/vnd.adobe.photoshop"),
        Map.entry("zip", "application/zip"));

    private CommissionFiles() {
        throw new UnsupportedOperationException("This class should never be instantiated");
    }

    static String contentType(String originalName) {
        if (originalName == null) {
            return null;
        }

        int dot = originalName.lastIndexOf('.');

        if (dot < 0) {
            return null;
        }

        return contentTypes.get(originalName.substring(dot + 1).strip().toLowerCase(Locale.ROOT));
    }
}
