package com.exe101.file.service;

import com.exe101.file.model.FileContent;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;

public interface FileService {
    StoredFileInfo storePublic(NewFile file);

    FileContent read(Long fileId);
}
