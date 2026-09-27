package com.exe101.customer.service;

import com.exe101.customer.model.Customer;
import com.exe101.customer.model.CustomerLogin;
import com.exe101.customer.model.CustomerToken;
import com.exe101.customer.security.CustomerTokenIssuer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CustomerLoginServiceImpl implements CustomerLoginService {
    private final CustomerService customerService;
    private final CustomerTokenIssuer tokenIssuer;

    @Override
    public CustomerToken login(CustomerLogin login) {
        Customer customer = customerService.authenticate(login);
        CustomerToken token = tokenIssuer.issue(customer.getUserId());

        return token;
    }
}
