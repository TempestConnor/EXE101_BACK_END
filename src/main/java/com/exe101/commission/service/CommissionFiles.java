package com.exe101.commission.service;

import com.exe101.commission.model.FileUpload;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.file.model.NewFile;
import java.util.Locale;
import java.util.Map;

// Reference files and submitted versions are accepted by extension; their contents are not inspected.
final class CommissionFiles {
    static int maxFiles = 10;
    private static long maxFileBytes = 20L * 1024 * 1024;
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

    static NewFile toNewFile(FileUpload upload) {
        byte[] bytes = upload.getBytes();

        if (bytes == null || bytes.length == 0) {
            throw new AppException(AppErrorMessage.COMMISSION_INVALID);
        }

        if (bytes.length > maxFileBytes) {
            throw new AppException(AppErrorMessage.COMMISSION_ATTACHMENT_TOO_LARGE);
        }

        String contentType = contentType(upload.getOriginalName());

        if (contentType == null) {
            throw new AppException(AppErrorMessage.COMMISSION_ATTACHMENT_TYPE_NOT_SUPPORTED);
        }

        return NewFile.builder()
            .withOriginalName(upload.getOriginalName())
            .withContentType(contentType)
            .withBytes(bytes)
            .build();
    }
}
