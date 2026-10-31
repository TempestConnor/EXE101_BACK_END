package com.exe101.file.service;

import com.exe101.file.model.FileContent;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;

public interface FileService {
    StoredFileInfo storePublic(NewFile file);

    StoredFileInfo storeProtected(NewFile file);

    boolean hasContent(Long fileId);

    FileContent read(Long fileId);

    FileContent readPurchased(Long userId, Long listingAssetId);

    int deleteOrphans();
}
