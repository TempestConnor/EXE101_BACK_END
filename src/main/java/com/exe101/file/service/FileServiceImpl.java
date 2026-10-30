package com.exe101.file.service;

import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.StoredFile;
import com.exe101.file.mapper.FileEntityMapper;
import com.exe101.file.model.FileContent;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;
import com.exe101.file.repository.StoredFileRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Bytes live on the local file system under app.storage.root; SQL keeps only the metadata.
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class FileServiceImpl implements FileService {
    private static String publicAccess = "PUBLIC";
    private static String publicPrefix = "public/";
    private static String defaultName = "preview";
    private static String digestAlgorithm = "SHA-256";
    private static int maxNameLength = 260;
    private final StoredFileRepository repository;
    private final FileEntityMapper mapper;
    @Value("${app.storage.root}")
    private String storageRoot;

    @Override
    public StoredFileInfo storePublic(NewFile file) {
        String storageKey = publicPrefix + UUID.randomUUID();
        Path target = resolve(storageKey);
        write(target, file.getBytes());
        StoredFile entity = new StoredFile(storageKey, safeName(file.getOriginalName()), file.getContentType(),
            file.getBytes().length, sha256(file.getBytes()), publicAccess,
            OffsetDateTime.now(ZoneOffset.UTC).withNano(0));
        StoredFile saved;

        try {
            saved = repository.saveAndFlush(entity);
        } catch (RuntimeException exception) {
            delete(target);
            throw exception;
        }

        log.info("[FILE] - ACTION: storePublic: fileId: {}", saved.getFileId());

        return mapper.toModel(saved);
    }

    @Override
    public FileContent read(Long fileId) {
        StoredFile entity = repository.findById(fileId)
            .orElseThrow(() -> new AppException(AppErrorMessage.FILE_CONTENT_MISSING));
        byte[] bytes;

        try {
            bytes = Files.readAllBytes(resolve(entity.getStorageKey()));
        } catch (NoSuchFileException exception) {
            log.error("[FILE] - ACTION: read: missing object: fileId: {}", fileId);
            throw new AppException(AppErrorMessage.FILE_CONTENT_MISSING);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        return FileContent.builder()
            .withFileId(entity.getFileId())
            .withOriginalName(entity.getOriginalName())
            .withContentType(entity.getContentType())
            .withBytes(bytes)
            .build();
    }

    private Path resolve(String storageKey) {
        return Path.of(storageRoot).resolve(storageKey);
    }

    private void write(Path target, byte[] bytes) {
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private void delete(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            log.warn("[FILE] - ACTION: delete: orphan left: storageKey: {}", target.getFileName());
        }
    }

    private String safeName(String originalName) {
        String name = originalName == null ? "" : originalName.strip();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String baseName = name.substring(slash + 1).strip();

        if (baseName.isEmpty()) {
            return defaultName;
        }

        if (baseName.length() > maxNameLength) {
            return baseName.substring(baseName.length() - maxNameLength);
        }

        return baseName;
    }

    private byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance(digestAlgorithm).digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
