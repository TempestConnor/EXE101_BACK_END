package com.exe101.staff.controller;

import com.exe101.staff.dto.LoginStaffDTO;
import com.exe101.staff.dto.StaffTokenDTO;
import com.exe101.staff.mapper.StaffMapper;
import com.exe101.staff.service.StaffLoginService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/staff")
@RequiredArgsConstructor
public class StaffController {
    private final StaffLoginService loginService;
    private final StaffMapper mapper;

    @PostMapping("/login")
    public StaffTokenDTO login(@RequestBody @Valid LoginStaffDTO dto) {
        var login = mapper.toModel(dto);
        var token = loginService.login(login);
        var response = mapper.toDto(token);

        return response;
    }
}
