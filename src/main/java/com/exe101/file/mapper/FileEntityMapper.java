package com.exe101.file.mapper;

import com.exe101.entity.StoredFile;
import com.exe101.file.model.StoredFileInfo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface FileEntityMapper {
    @Mapping(source = "fileId", target = "withFileId")
    @Mapping(source = "storageKey", target = "withStorageKey")
    @Mapping(source = "originalName", target = "withOriginalName")
    @Mapping(source = "contentType", target = "withContentType")
    @Mapping(source = "byteLength", target = "withByteLength")
    @Mapping(source = "accessClass", target = "withAccessClass")
    @Mapping(source = "createdAt", target = "withCreatedAt")
    StoredFileInfo toModel(StoredFile entity);
}
