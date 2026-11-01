package com.exe101.proceeds.controller;

import com.exe101.proceeds.dto.RecordReleaseOutcomeDTO;
import com.exe101.proceeds.dto.ReleaseDTO;
import com.exe101.proceeds.mapper.ProceedsMapper;
import com.exe101.proceeds.model.ReleaseStatus;
import com.exe101.proceeds.service.ProceedsService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class ProceedsAdminController {
    private final ProceedsService proceedsService;
    private final ProceedsMapper mapper;

    @GetMapping("/releases")
    public List<ReleaseDTO> list(@RequestParam(required = false) ReleaseStatus status) {
        var releases = proceedsService.list(status);
        var response = mapper.toReleaseDto(releases);

        return response;
    }

    @PostMapping("/releases/{payoutId}/status")
    public ReleaseDTO recordOutcome(
        @PathVariable Long payoutId, @RequestBody @Valid RecordReleaseOutcomeDTO dto,
        @AuthenticationPrincipal Long staffId
    ) {
        var outcome = mapper.toModel(dto);
        var updated = proceedsService.recordOutcome(staffId, payoutId, outcome);
        var response = mapper.toDto(updated);

        return response;
    }

    @PostMapping("/proceeds/{proceedsId}/releases")
    @ResponseStatus(HttpStatus.CREATED)
    public ReleaseDTO release(@PathVariable Long proceedsId, @AuthenticationPrincipal Long staffId) {
        var created = proceedsService.release(staffId, proceedsId);
        var response = mapper.toDto(created);

        return response;
    }
}
