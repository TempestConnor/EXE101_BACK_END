package com.exe101.customer.controller;

import com.exe101.customer.dto.AdminCustomerDTO;
import com.exe101.customer.mapper.CustomerMapper;
import com.exe101.customer.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/customers")
@RequiredArgsConstructor
public class CustomerAdminController {
    private final CustomerService customerService;
    private final CustomerMapper mapper;

    // One account by exact email, to find the userId an Artist Profile is granted to.
    @GetMapping
    public AdminCustomerDTO findByEmail(@RequestParam String email) {
        var customer = customerService.findByEmail(email);
        var response = mapper.toAdminDto(customer);

        return response;
    }
}
