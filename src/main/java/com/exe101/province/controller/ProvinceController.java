package com.exe101.province.controller;

import com.exe101.province.dto.CreateProvinceDTO;
import com.exe101.province.dto.ProvinceDTO;
import com.exe101.province.dto.UpdateProvinceSupportDTO;
import com.exe101.province.mapper.ProvinceMapper;
import com.exe101.province.service.ProvinceService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/provinces")
@RequiredArgsConstructor
public class ProvinceController {
    private final ProvinceService provinceService;
    private final ProvinceMapper mapper;

    @GetMapping
    public List<ProvinceDTO> listAll() {
        var provinces = provinceService.listAll();
        var response = mapper.toDto(provinces);

        return response;
    }

    @GetMapping("/supported")
    public List<ProvinceDTO> listSupported() {
        var provinces = provinceService.listSupported();
        var response = mapper.toDto(provinces);

        return response;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProvinceDTO create(@RequestBody @Valid CreateProvinceDTO dto) {
        var province = mapper.toModel(dto);
        var created = provinceService.create(province);
        var response = mapper.toDto(created);

        return response;
    }

    @PutMapping("/{provinceId}/support")
    public ProvinceDTO changeSupport(
        @PathVariable Long provinceId, @RequestBody @Valid UpdateProvinceSupportDTO dto,
        @AuthenticationPrincipal Long staffId
    ) {
        var change = mapper.toModel(dto);
        var updated = provinceService.changeSupport(provinceId, change, staffId);
        var response = mapper.toDto(updated);

        return response;
    }
}
