package com.exe101.file.service;

import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.StoredFile;
import com.exe101.file.config.StorageProperties;
import com.exe101.file.mapper.FileEntityMapper;
import com.exe101.file.model.FileContent;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;
import com.exe101.file.repository.PurchasedFileRepository;
import com.exe101.file.repository.StoredFileRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
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
    private static String protectedAccess = "PURCHASED";
    private static String protectedPrefix = "protected/";
    private static String commissionAccess = "COMMISSION";
    private static List<String> storagePrefixes = List.of(publicPrefix, protectedPrefix);
    private static String defaultName = "file";
    private static String digestAlgorithm = "SHA-256";
    private static int maxNameLength = 260;
    private final StoredFileRepository repository;
    private final PurchasedFileRepository purchasedFiles;
    private final FileEntityMapper mapper;
    private final StorageProperties storage;

    @Override
    public StoredFileInfo storePublic(NewFile file) {
        return store(file, publicPrefix, publicAccess);
    }

    @Override
    public StoredFileInfo storeProtected(NewFile file) {
        return store(file, protectedPrefix, protectedAccess);
    }

    // Commission files share the protected storage area; access is checked against Commission participation (§27.6).
    @Override
    public StoredFileInfo storeCommission(NewFile file) {
        return store(file, protectedPrefix, commissionAccess);
    }

    @Override
    public boolean hasContent(Long fileId) {
        return repository.findById(fileId)
            .map(entity -> Files.isRegularFile(resolve(entity.getStorageKey())))
            .orElse(false);
    }

    @Override
    public FileContent read(Long fileId) {
        StoredFile entity = repository.findById(fileId)
            .orElseThrow(() -> new AppException(AppErrorMessage.FILE_CONTENT_MISSING));

        return content(entity);
    }

    // Access follows only the buyer's entitlement to the exact recorded asset. Listing, Artwork and Artist status
    // are not checked: unlisting, moderation and deactivation stop new sales, not granted access (§5.3–6).
    @Override
    public FileContent readPurchased(Long userId, Long listingAssetId) {
        Long fileId = purchasedFiles.findEntitledFileId(userId, listingAssetId)
            .orElseThrow(() -> denied(listingAssetId));
        StoredFile entity = repository.findById(fileId)
            .orElseThrow(() -> new AppException(AppErrorMessage.FILE_CONTENT_MISSING));

        if (!protectedAccess.equals(entity.getAccessClass())) {
            log.error("[FILE] - ACTION: readPurchased: wrong access class: fileId: {}", fileId);
            throw new AppException(AppErrorMessage.ENTITLEMENT_REQUIRED);
        }

        FileContent content = content(entity);
        log.info("[FILE] - ACTION: readPurchased: userId: {}: fileId: {}", userId, fileId);

        return content;
    }

    // Metadata only; whether the bytes are present is checked when a file is read.
    @Override
    public List<StoredFileInfo> describe(Collection<Long> fileIds) {
        return repository.findAllById(fileIds).stream()
            .map(mapper::toModel)
            .toList();
    }

    @Override
    public int deleteOrphans() {
        Instant cutoff = Instant.now().minus(storage.orphanMinAge());
        int deleted = 0;

        for (String prefix : storagePrefixes) {
            for (Path object : objects(resolve(prefix))) {
                if (isOlderThan(object, cutoff) && !repository.existsByStorageKey(prefix + object.getFileName())
                    && delete(object)) {
                    log.info("[FILE] - ACTION: deleteOrphan: storageKey: {}{}", prefix, object.getFileName());
                    deleted++;
                }
            }
        }

        return deleted;
    }

    private AppException denied(Long listingAssetId) {
        if (purchasedFiles.listingAssetExists(listingAssetId)) {
            return new AppException(AppErrorMessage.ENTITLEMENT_REQUIRED);
        }

        return new AppException(AppErrorMessage.LISTING_ASSET_NOT_FOUND);
    }

    private FileContent content(StoredFile entity) {
        Path path = resolve(entity.getStorageKey());

        if (!Files.isRegularFile(path)) {
            log.error("[FILE] - ACTION: read: missing object: fileId: {}", entity.getFileId());
            throw new AppException(AppErrorMessage.FILE_CONTENT_MISSING);
        }

        return FileContent.builder()
            .withFileId(entity.getFileId())
            .withOriginalName(entity.getOriginalName())
            .withContentType(entity.getContentType())
            .withByteLength(entity.getByteLength())
            .withContent(new FileSystemResource(path))
            .build();
    }

    private StoredFileInfo store(NewFile file, String prefix, String accessClass) {
        String storageKey = prefix + UUID.randomUUID();
        Path target = resolve(storageKey);
        write(target, file.getBytes());
        StoredFile entity = new StoredFile(storageKey, safeName(file.getOriginalName()), file.getContentType(),
            file.getBytes().length, sha256(file.getBytes()), accessClass,
            OffsetDateTime.now(ZoneOffset.UTC).withNano(0));
        StoredFile saved;

        try {
            saved = repository.saveAndFlush(entity);
        } catch (RuntimeException exception) {
            delete(target);
            throw exception;
        }

        log.info("[FILE] - ACTION: store: fileId: {}: accessClass: {}", saved.getFileId(), accessClass);

        return mapper.toModel(saved);
    }

    private Path resolve(String storageKey) {
        return Path.of(storage.root()).resolve(storageKey);
    }

    private List<Path> objects(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }

        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile).toList();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private boolean isOlderThan(Path object, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(object).toInstant().isBefore(cutoff);
        } catch (IOException exception) {
            return false;
        }
    }

    private void write(Path target, byte[] bytes) {
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private boolean delete(Path target) {
        try {
            return Files.deleteIfExists(target);
        } catch (IOException exception) {
            log.warn("[FILE] - ACTION: delete: orphan left: storageKey: {}", target.getFileName());

            return false;
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
