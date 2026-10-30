package com.exe101.province.controller;

import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.model.Customer;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import com.exe101.province.mapper.ProvinceMapperImpl;
import com.exe101.province.service.ProvinceService;
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.security.StaffAuthenticationConverter;
import com.exe101.staff.security.StaffTokenIssuer;
import com.exe101.staff.service.StaffService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProvinceController.class)
@Import({ProvinceMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class ProvinceControllerTest {
    private static String supportBody = """
        {"supported":true,"reason":"Courier contract signed"}
        """;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @MockitoBean
    private ProvinceService provinces;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void province_support_requires_admin_ko() throws Exception {
        // given
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());
        var token = customerIssuer.issue(42L).getAccessToken();

        // when / then
        mvc.perform(put("/provinces/3/support").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(supportBody))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        mvc.perform(put("/provinces/3/support").contentType(MediaType.APPLICATION_JSON).content(supportBody))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(provinces);
    }
}
