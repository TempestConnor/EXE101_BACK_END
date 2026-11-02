package com.exe101.file.service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

// Preview type is decided from the file signature, never from the client-declared content type.
public final class PreviewImages {
    private static byte[] jpegSignature = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static byte[] pngSignature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static byte[] riffSignature = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static byte[] webpSignature = "WEBP".getBytes(StandardCharsets.US_ASCII);
    private static int webpOffset = 8;

    private PreviewImages() {
        throw new UnsupportedOperationException("This class should never be instantiated");
    }

    public static String detectContentType(byte[] bytes) {
        if (startsWith(bytes, jpegSignature, 0)) {
            return "image/jpeg";
        }

        if (startsWith(bytes, pngSignature, 0)) {
            return "image/png";
        }

        boolean webp = startsWith(bytes, riffSignature, 0) && startsWith(bytes, webpSignature, webpOffset);

        if (webp) {
            return "image/webp";
        }

        return null;
    }

    private static boolean startsWith(byte[] bytes, byte[] signature, int offset) {
        if (bytes.length < offset + signature.length) {
            return false;
        }

        return Arrays.equals(bytes, offset, offset + signature.length, signature, 0, signature.length);
    }
}
