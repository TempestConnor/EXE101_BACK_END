package com.exe101.commission.mapper;

import com.exe101.commission.dto.CommissionAttachmentDTO;
import com.exe101.commission.dto.CommissionDTO;
import com.exe101.commission.dto.CommissionMessageDTO;
import com.exe101.commission.dto.CommissionSummaryDTO;
import com.exe101.commission.dto.CreateCommissionDTO;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionAttachment;
import com.exe101.commission.model.CommissionMessage;
import com.exe101.commission.model.CommissionRequest;
import com.exe101.commission.model.FileUpload;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.springframework.web.multipart.MultipartFile;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CommissionMapper {
    default CommissionRequest toModel(CreateCommissionDTO dto, List<MultipartFile> files) {
        List<FileUpload> uploads = files == null ? List.of() : files.stream()
            .map(this::toModel)
            .toList();
        CommissionRequest request = CommissionRequest.builder()
            .withArtistId(dto.artistId())
            .withDescription(dto.description())
            .withAllowPublicDisplay(dto.allowPublicDisplay())
            .withUploads(uploads)
            .build();

        return request;
    }

    default FileUpload toModel(MultipartFile file) {
        byte[] bytes;

        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        FileUpload upload = FileUpload.builder()
            .withOriginalName(file.getOriginalFilename())
            .withBytes(bytes)
            .build();

        return upload;
    }

    CommissionDTO toDto(Commission commission);

    CommissionSummaryDTO toSummaryDto(Commission commission);

    List<CommissionSummaryDTO> toSummaryDtos(List<Commission> commissions);

    CommissionAttachmentDTO toDto(CommissionAttachment attachment);

    CommissionMessageDTO toDto(CommissionMessage message);

    List<CommissionMessageDTO> toMessageDtos(List<CommissionMessage> messages);
}
