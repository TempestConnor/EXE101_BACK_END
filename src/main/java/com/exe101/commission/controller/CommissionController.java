package com.exe101.commission.controller;

import com.exe101.commission.dto.CommissionAttachmentDTO;
import com.exe101.commission.dto.CommissionDTO;
import com.exe101.commission.dto.CommissionMessageDTO;
import com.exe101.commission.dto.CommissionSummaryDTO;
import com.exe101.commission.dto.CreateCommissionDTO;
import com.exe101.commission.dto.SendMessageDTO;
import com.exe101.commission.mapper.CommissionMapper;
import com.exe101.commission.service.CommissionService;
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
@RequestMapping("/commissions")
@RequiredArgsConstructor
public class CommissionController {
    private final CommissionService commissionService;
    private final CommissionMapper mapper;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CommissionDTO create(@RequestBody @Valid CreateCommissionDTO dto, @AuthenticationPrincipal Long userId) {
        var request = mapper.toModel(dto, List.of());
        var created = commissionService.request(userId, request);
        var response = mapper.toDto(created);

        return response;
    }

    // The same request with reference files: a "request" JSON part and up to ten "files" parts.
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CommissionDTO createWithFiles(
        @RequestPart("request") @Valid CreateCommissionDTO dto,
        @RequestPart(value = "files", required = false) List<MultipartFile> files,
        @AuthenticationPrincipal Long userId
    ) {
        var request = mapper.toModel(dto, files);
        var created = commissionService.request(userId, request);
        var response = mapper.toDto(created);

        return response;
    }

    @GetMapping
    public List<CommissionSummaryDTO> listMine(@AuthenticationPrincipal Long userId) {
        var commissions = commissionService.listMine(userId);
        var response = mapper.toSummaryDtos(commissions);

        return response;
    }

    @GetMapping("/{commissionId}")
    public CommissionDTO get(@PathVariable Long commissionId, @AuthenticationPrincipal Long userId) {
        var commission = commissionService.get(userId, commissionId);
        var response = mapper.toDto(commission);

        return response;
    }

    @GetMapping("/{commissionId}/messages")
    public List<CommissionMessageDTO> listMessages(
        @PathVariable Long commissionId, @AuthenticationPrincipal Long userId
    ) {
        var messages = commissionService.listMessages(userId, commissionId);
        var response = mapper.toMessageDtos(messages);

        return response;
    }

    @PostMapping("/{commissionId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public CommissionMessageDTO sendMessage(
        @PathVariable Long commissionId, @RequestBody @Valid SendMessageDTO dto, @AuthenticationPrincipal Long userId
    ) {
        var message = commissionService.sendMessage(userId, commissionId, dto.body());
        var response = mapper.toDto(message);

        return response;
    }

    @PostMapping(path = "/{commissionId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CommissionAttachmentDTO addAttachment(
        @PathVariable Long commissionId, @RequestPart("file") MultipartFile file, @AuthenticationPrincipal Long userId
    ) {
        var upload = mapper.toModel(file);
        var attachment = commissionService.addAttachment(userId, commissionId, upload);
        var response = mapper.toDto(attachment);

        return response;
    }

    @GetMapping("/{commissionId}/attachments/{attachmentId}/content")
    public ResponseEntity<Resource> getAttachmentContent(
        @PathVariable Long commissionId, @PathVariable Long attachmentId, @AuthenticationPrincipal Long userId
    ) {
        var content = commissionService.readAttachment(userId, commissionId, attachmentId);
        var response = ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.getContentType()))
            .contentLength(content.getByteLength())
            .body(content.getContent());

        return response;
    }
}
