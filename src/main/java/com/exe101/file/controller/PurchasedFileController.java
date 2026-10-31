package com.exe101.file.controller;

import com.exe101.file.service.FileService;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/purchases/assets")
@RequiredArgsConstructor
public class PurchasedFileController {
    private final FileService fileService;

    @GetMapping("/{assetId}/content")
    public ResponseEntity<Resource> getContent(@PathVariable Long assetId, @AuthenticationPrincipal Long userId) {
        var content = fileService.readPurchased(userId, assetId);
        var disposition = ContentDisposition.attachment()
            .filename(content.getOriginalName(), StandardCharsets.UTF_8)
            .build();
        var response = ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(content.getContentType()))
            .contentLength(content.getByteLength())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .body(content.getContent());

        return response;
    }
}
