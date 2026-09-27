package com.exe101.customer.controller;

import com.exe101.customer.dto.CustomerDTO;
import com.exe101.customer.dto.CustomerTokenDTO;
import com.exe101.customer.dto.LoginCustomerDTO;
import com.exe101.customer.dto.RegisterCustomerDTO;
import com.exe101.customer.mapper.CustomerMapper;
import com.exe101.customer.service.CustomerLoginService;
import com.exe101.customer.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/customers")
@RequiredArgsConstructor
public class CustomerController {
    private final CustomerService customerService;
    private final CustomerLoginService loginService;
    private final CustomerMapper mapper;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerDTO register(@RequestBody @Valid RegisterCustomerDTO dto) {
        var registration = mapper.toModel(dto);
        var created = customerService.register(registration);
        var response = mapper.toDto(created);

        return response;
    }

    @PostMapping("/login")
    public CustomerTokenDTO login(@RequestBody @Valid LoginCustomerDTO dto) {
        var login = mapper.toModel(dto);
        var token = loginService.login(login);
        var response = mapper.toDto(token);

        return response;
    }

    @GetMapping("/me")
    public CustomerDTO me(@AuthenticationPrincipal Long userId) {
        var customer = customerService.requireActive(userId);
        var response = mapper.toDto(customer);

        return response;
    }
}
