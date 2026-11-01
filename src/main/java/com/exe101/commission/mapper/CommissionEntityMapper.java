package com.exe101.commission.mapper;

import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionAttachment;
import com.exe101.commission.model.CommissionMessage;
import com.exe101.file.model.StoredFileInfo;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CommissionEntityMapper {
    @Mapping(source = "commissionId", target = "withCommissionId")
    @Mapping(source = "customerId", target = "withCustomerId")
    @Mapping(source = "artistId", target = "withArtistId")
    @Mapping(source = "requestDescription", target = "withRequestDescription")
    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "customerNameSnapshot", target = "withCustomerNameSnapshot")
    @Mapping(source = "artistNameSnapshot", target = "withArtistNameSnapshot")
    @Mapping(source = "allowPublicDisplay", target = "withAllowPublicDisplay")
    @Mapping(source = "createdAt", target = "withCreatedAt")
    @Mapping(source = "completedAt", target = "withCompletedAt")
    @Mapping(target = "withAttachments", ignore = true)
    Commission toModel(com.exe101.entity.Commission entity);

    @Mapping(source = "messageId", target = "withMessageId")
    @Mapping(source = "commissionId", target = "withCommissionId")
    @Mapping(source = "senderUserId", target = "withSenderUserId")
    @Mapping(source = "body", target = "withBody")
    @Mapping(source = "sentAt", target = "withSentAt")
    CommissionMessage toModel(com.exe101.entity.CommissionMessage entity);

    @Mapping(source = "attachment.attachmentId", target = "withAttachmentId")
    @Mapping(source = "attachment.commissionId", target = "withCommissionId")
    @Mapping(source = "attachment.fileId", target = "withFileId")
    @Mapping(source = "attachment.uploadedByUserId", target = "withUploadedByUserId")
    @Mapping(source = "file.originalName", target = "withOriginalName")
    @Mapping(source = "file.contentType", target = "withContentType")
    @Mapping(source = "file.byteLength", target = "withByteLength")
    @Mapping(source = "attachment.createdAt", target = "withCreatedAt")
    CommissionAttachment toModel(com.exe101.entity.CommissionAttachment attachment, StoredFileInfo file);
}
