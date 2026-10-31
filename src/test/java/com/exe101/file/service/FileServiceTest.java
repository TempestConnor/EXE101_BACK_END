package com.exe101.file.service;

import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.StoredFile;
import com.exe101.file.config.StorageProperties;
import com.exe101.file.mapper.FileEntityMapper;
import com.exe101.file.repository.PurchasedFileRepository;
import com.exe101.file.repository.StoredFileRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {
    private static byte[] fileBytes = {'s', 'o', 'u', 'r', 'c', 'e'};
    private static OffsetDateTime created = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    @TempDir
    private Path root;
    @Mock
    private StoredFileRepository repository;
    @Mock
    private PurchasedFileRepository purchasedFiles;
    private FileServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FileServiceImpl(repository, purchasedFiles, Mappers.getMapper(FileEntityMapper.class),
            new StorageProperties(root.toString(), Duration.ofHours(24)));
    }

    @Test
    void download_entitled_file_ok() throws Exception {
        // given
        writeObject("protected/source", Instant.now());
        when(purchasedFiles.findEntitledFileId(42L, 30L)).thenReturn(Optional.of(100L));
        when(repository.findById(100L)).thenReturn(Optional.of(storedFile("protected/source", "PURCHASED")));

        // when
        var result = service.readPurchased(42L, 30L);

        // then
        assertAll(
            () -> assertThat(result.getFileId()).isEqualTo(100L),
            () -> assertThat(result.getOriginalName()).isEqualTo("sunset.psd"),
            () -> assertThat(result.getByteLength()).isEqualTo(fileBytes.length),
            () -> assertThat(result.getContent().getContentAsByteArray()).isEqualTo(fileBytes)
        );
    }

    @Test
    void download_unrelated_user_ko() {
        // given
        when(purchasedFiles.findEntitledFileId(43L, 30L)).thenReturn(Optional.empty());
        when(purchasedFiles.listingAssetExists(30L)).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> service.readPurchased(43L, 30L))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.ENTITLEMENT_REQUIRED);
        verify(repository, never()).findById(anyLong());
    }

    @Test
    void download_unknown_asset_not_found_ko() {
        // given
        when(purchasedFiles.findEntitledFileId(42L, 99L)).thenReturn(Optional.empty());
        when(purchasedFiles.listingAssetExists(99L)).thenReturn(false);

        // when / then
        assertThatThrownBy(() -> service.readPurchased(42L, 99L))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.LISTING_ASSET_NOT_FOUND);
    }

    @Test
    void download_missing_object_ko() {
        // given
        when(purchasedFiles.findEntitledFileId(42L, 30L)).thenReturn(Optional.of(100L));
        when(repository.findById(100L)).thenReturn(Optional.of(storedFile("protected/gone", "PURCHASED")));

        // when / then
        assertThatThrownBy(() -> service.readPurchased(42L, 30L))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.FILE_CONTENT_MISSING);
    }

    @Test
    void download_non_purchased_file_ko() throws Exception {
        // given
        writeObject("public/preview", Instant.now());
        when(purchasedFiles.findEntitledFileId(42L, 30L)).thenReturn(Optional.of(100L));
        when(repository.findById(100L)).thenReturn(Optional.of(storedFile("public/preview", "PUBLIC")));

        // when / then
        assertThatThrownBy(() -> service.readPurchased(42L, 30L))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.ENTITLEMENT_REQUIRED);
    }

    @Test
    void sweep_deletes_old_unreferenced_object_ok() throws Exception {
        // given
        var orphan = writeObject("protected/orphan", Instant.now().minus(Duration.ofHours(25)));
        when(repository.existsByStorageKey("protected/orphan")).thenReturn(false);

        // when
        var deleted = service.deleteOrphans();

        // then
        assertAll(
            () -> assertThat(deleted).isEqualTo(1),
            () -> assertThat(orphan).doesNotExist()
        );
    }

    @Test
    void sweep_keeps_object_with_row_ok() throws Exception {
        // given
        var referenced = writeObject("public/kept", Instant.now().minus(Duration.ofDays(30)));
        when(repository.existsByStorageKey("public/kept")).thenReturn(true);

        // when
        var deleted = service.deleteOrphans();

        // then
        assertAll(
            () -> assertThat(deleted).isZero(),
            () -> assertThat(referenced).exists()
        );
    }

    @Test
    void sweep_keeps_recent_object_ok() throws Exception {
        // given
        var uploading = writeObject("protected/uploading", Instant.now().minus(Duration.ofHours(1)));

        // when
        var deleted = service.deleteOrphans();

        // then
        assertAll(
            () -> assertThat(deleted).isZero(),
            () -> assertThat(uploading).exists()
        );
        verify(repository, never()).existsByStorageKey("protected/uploading");
    }

    private Path writeObject(String storageKey, Instant modified) throws Exception {
        var path = root.resolve(storageKey);
        Files.createDirectories(path.getParent());
        Files.write(path, fileBytes);
        Files.setLastModifiedTime(path, FileTime.from(modified));

        return path;
    }

    private StoredFile storedFile(String storageKey, String accessClass) {
        var file = new StoredFile(storageKey, "sunset.psd", "image/vnd.adobe.photoshop", fileBytes.length,
            accessClass, created);
        file.setFileId(100L);

        return file;
    }
}
