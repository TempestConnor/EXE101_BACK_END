package com.exe101.proceeds.controller;

import com.exe101.proceeds.dto.ProceedsDTO;
import com.exe101.proceeds.dto.ReleaseDTO;
import com.exe101.proceeds.mapper.ProceedsMapper;
import com.exe101.proceeds.service.ProceedsService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/proceeds")
@RequiredArgsConstructor
public class ProceedsController {
    private final ProceedsService proceedsService;
    private final ProceedsMapper mapper;

    @GetMapping
    public List<ProceedsDTO> listOwn(@AuthenticationPrincipal Long userId) {
        var proceeds = proceedsService.listOwn(userId);
        var response = mapper.toProceedsDto(proceeds);

        return response;
    }

    @GetMapping("/releases")
    public List<ReleaseDTO> listOwnReleases(@AuthenticationPrincipal Long userId) {
        var releases = proceedsService.listOwnReleases(userId);
        var response = mapper.toReleaseDto(releases);

        return response;
    }
}
