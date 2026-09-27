package com.exe101.customer.service;

import com.exe101.customer.model.CustomerLogin;
import com.exe101.customer.model.CustomerToken;

public interface CustomerLoginService {
    CustomerToken login(CustomerLogin login);
}
