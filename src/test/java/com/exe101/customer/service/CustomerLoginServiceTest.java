package com.exe101.customer.service;

import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.Customer;
import com.exe101.customer.model.CustomerLogin;
import com.exe101.customer.model.CustomerToken;
import com.exe101.customer.security.CustomerTokenIssuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerLoginServiceTest {
    @Mock
    private CustomerService customers;
    @Mock
    private CustomerTokenIssuer issuer;
    @InjectMocks
    private CustomerLoginServiceImpl service;

    @Test
    void login_issues_token_for_authenticated_identity_ok() {
        // given
        var login = new CustomerLogin("buyer@example.com", "long-password");
        var customer = Customer.builder().withUserId(42L).build();
        var token = new CustomerToken("signed", "Bearer", 900);
        when(customers.authenticate(login)).thenReturn(customer);
        when(issuer.issue(42L)).thenReturn(token);

        // when
        var result = service.login(login);

        // then
        assertThat(result).isSameAs(token);
    }

    @Test
    void failed_login_does_not_issue_token_ko() {
        // given
        var login = new CustomerLogin("buyer@example.com", "wrong-password");
        when(customers.authenticate(login)).thenThrow(new AppException(AppErrorMessage.INVALID_CREDENTIALS));

        // when / then
        assertThatThrownBy(() -> service.login(login)).isInstanceOf(AppException.class);
        verifyNoInteractions(issuer);
    }
}
