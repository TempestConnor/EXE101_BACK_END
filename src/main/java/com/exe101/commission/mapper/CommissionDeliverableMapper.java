package com.exe101.commission.mapper;

import com.exe101.commission.dto.CommissionDeliverableDTO;
import com.exe101.commission.dto.RevisionRequestDTO;
import com.exe101.commission.dto.SubmitDeliverableDTO;
import com.exe101.commission.model.CommissionDeliverable;
import com.exe101.commission.model.DeliverableSubmission;
import com.exe101.commission.model.FileUpload;
import com.exe101.commission.model.RevisionRequest;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.springframework.web.multipart.MultipartFile;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR, uses = CommissionMapper.class)
public interface CommissionDeliverableMapper {
    default DeliverableSubmission toModel(SubmitDeliverableDTO dto, List<MultipartFile> files) {
        List<FileUpload> uploads = files == null ? List.of() : toUploads(files);
        DeliverableSubmission submission = DeliverableSubmission.builder()
            .withNotes(dto == null ? null : dto.notes())
            .withUploads(uploads)
            .build();

        return submission;
    }

    List<FileUpload> toUploads(List<MultipartFile> files);

    CommissionDeliverableDTO toDto(CommissionDeliverable deliverable);

    List<CommissionDeliverableDTO> toDtos(List<CommissionDeliverable> deliverables);

    RevisionRequestDTO toDto(RevisionRequest request);
}
