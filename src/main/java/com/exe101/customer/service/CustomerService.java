package com.exe101.customer.service;

import com.exe101.customer.model.Customer;
import com.exe101.customer.model.CustomerLogin;
import com.exe101.customer.model.CustomerRegistration;

public interface CustomerService {
    Customer register(CustomerRegistration registration);

    Customer authenticate(CustomerLogin login);

    Customer requireActive(Long userId);
}
