package com.exe101.file.service;

import com.exe101.file.model.FileContent;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;
import java.util.Collection;
import java.util.List;

public interface FileService {
    StoredFileInfo storePublic(NewFile file);

    StoredFileInfo storeProtected(NewFile file);

    StoredFileInfo storeCommission(NewFile file);

    boolean hasContent(Long fileId);

    FileContent read(Long fileId);

    FileContent readPurchased(Long userId, Long listingAssetId);

    List<StoredFileInfo> describe(Collection<Long> fileIds);

    int deleteOrphans();
}
