package com.exe101.commission.mapper;

import com.exe101.commission.model.CommissionDeliverable;
import com.exe101.commission.model.DeliverableFile;
import com.exe101.commission.model.RevisionRequest;
import com.exe101.file.model.StoredFileInfo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CommissionDeliverableEntityMapper {
    @Mapping(source = "deliverableId", target = "withDeliverableId")
    @Mapping(source = "commissionId", target = "withCommissionId")
    @Mapping(source = "versionNo", target = "withVersionNo")
    @Mapping(source = "notes", target = "withNotes")
    @Mapping(source = "submittedAt", target = "withSubmittedAt")
    @Mapping(target = "withFiles", ignore = true)
    @Mapping(target = "withRevisionRequests", ignore = true)
    CommissionDeliverable toModel(com.exe101.entity.CommissionDeliverable entity);

    @Mapping(source = "fileId", target = "withFileId")
    @Mapping(source = "originalName", target = "withOriginalName")
    @Mapping(source = "contentType", target = "withContentType")
    @Mapping(source = "byteLength", target = "withByteLength")
    DeliverableFile toModel(StoredFileInfo file);

    @Mapping(source = "revisionId", target = "withRevisionId")
    @Mapping(source = "deliverableId", target = "withDeliverableId")
    @Mapping(source = "commissionId", target = "withCommissionId")
    @Mapping(source = "requestedByUserId", target = "withRequestedByUserId")
    @Mapping(source = "details", target = "withDetails")
    @Mapping(source = "requestedAt", target = "withRequestedAt")
    RevisionRequest toModel(com.exe101.entity.RevisionRequest entity);
}
