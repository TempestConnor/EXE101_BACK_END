package com.exe101.listing.service;

import java.util.Locale;
import java.util.Map;

// Protected files are accepted by extension; their contents are not inspected.
final class ListingFiles {
    private static Map<String, String> contentTypes = Map.ofEntries(
        Map.entry("png", "image/png"),
        Map.entry("jpg", "image/jpeg"),
        Map.entry("jpeg", "image/jpeg"),
        Map.entry("webp", "image/webp"),
        Map.entry("tif", "image/tiff"),
        Map.entry("tiff", "image/tiff"),
        Map.entry("psd", "image/vnd.adobe.photoshop"),
        Map.entry("pdf", "application/pdf"),
        Map.entry("zip", "application/zip"),
        Map.entry("glb", "model/gltf-binary"),
        Map.entry("gltf", "model/gltf+json"),
        Map.entry("obj", "model/obj"),
        Map.entry("fbx", "application/octet-stream"),
        Map.entry("stl", "model/stl"),
        Map.entry("blend", "application/x-blender"));

    private ListingFiles() {
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
