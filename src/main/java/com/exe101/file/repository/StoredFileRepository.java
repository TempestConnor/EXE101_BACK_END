package com.exe101.file.repository;

import com.exe101.entity.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    boolean existsByStorageKey(String storageKey);
}
