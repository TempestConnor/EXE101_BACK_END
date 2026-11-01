package com.exe101.commission.controller;

import com.exe101.commission.dto.CommissionDeliverableDTO;
import com.exe101.commission.dto.CommissionSummaryDTO;
import com.exe101.commission.dto.RequestRevisionDTO;
import com.exe101.commission.dto.RevisionRequestDTO;
import com.exe101.commission.dto.SubmitDeliverableDTO;
import com.exe101.commission.mapper.CommissionDeliverableMapper;
import com.exe101.commission.mapper.CommissionMapper;
import com.exe101.commission.service.CommissionDeliveryService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/commissions/{commissionId}/deliverables")
@RequiredArgsConstructor
public class CommissionDeliveryController {
    private final CommissionDeliveryService deliveryService;
    private final CommissionDeliverableMapper mapper;
    private final CommissionMapper commissionMapper;

    @GetMapping
    public List<CommissionDeliverableDTO> list(@PathVariable Long commissionId, @AuthenticationPrincipal Long userId) {
        var deliverables = deliveryService.list(userId, commissionId);
        var response = mapper.toDtos(deliverables);

        return response;
    }

    // A new version: an optional "request" JSON part with notes and one to ten "files" parts.
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CommissionDeliverableDTO submit(
        @PathVariable Long commissionId,
        @RequestPart(value = "request", required = false) @Valid SubmitDeliverableDTO dto,
        @RequestPart(value = "files", required = false) List<MultipartFile> files,
        @AuthenticationPrincipal Long userId
    ) {
        var submission = mapper.toModel(dto, files);
        var submitted = deliveryService.submit(userId, commissionId, submission);
        var response = mapper.toDto(submitted);

        return response;
    }

    @PostMapping("/{deliverableId}/approve")
    public CommissionSummaryDTO approve(
        @PathVariable Long commissionId, @PathVariable Long deliverableId, @AuthenticationPrincipal Long userId
    ) {
        var approved = deliveryService.approve(userId, commissionId, deliverableId);
        var response = commissionMapper.toSummaryDto(approved);

        return response;
    }

    @PostMapping("/{deliverableId}/revision-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public RevisionRequestDTO requestRevision(
        @PathVariable Long commissionId, @PathVariable Long deliverableId, @RequestBody @Valid RequestRevisionDTO dto,
        @AuthenticationPrincipal Long userId
    ) {
        var revision = deliveryService.requestRevision(userId, commissionId, deliverableId, dto.details());
        var response = mapper.toDto(revision);

        return response;
    }

    @GetMapping("/{deliverableId}/files/{fileId}/content")
    public ResponseEntity<Resource> getFileContent(
        @PathVariable Long commissionId, @PathVariable Long deliverableId, @PathVariable Long fileId,
        @AuthenticationPrincipal Long userId
    ) {
        var content = deliveryService.readFile(userId, commissionId, deliverableId, fileId);
        var response = ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.getContentType()))
            .contentLength(content.getByteLength())
            .body(content.getContent());

        return response;
    }
}
