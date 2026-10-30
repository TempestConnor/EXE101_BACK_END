package com.exe101.address.controller;

import com.exe101.address.mapper.AddressMapperImpl;
import com.exe101.address.model.Address;
import com.exe101.address.service.AddressService;
import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.model.Customer;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.security.StaffAuthenticationConverter;
import com.exe101.staff.security.StaffTokenIssuer;
import com.exe101.staff.service.StaffService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AddressController.class)
@Import({AddressMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class AddressControllerTest {
    private static String addressBody = """
        {"recipientName":"Nguyễn Văn A","phone":"0912345678","line1":"1 Tràng Tiền",
         "ward":"Phường Hoàn Kiếm","provinceCode":"01","defaultAddress":true}
        """;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @MockitoBean
    private AddressService addresses;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void address_endpoints_require_customer_ko() throws Exception {
        // given / when / then
        mvc.perform(get("/addresses"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/addresses").contentType(MediaType.APPLICATION_JSON).content(addressBody))
            .andExpect(status().isUnauthorized());
        mvc.perform(put("/addresses/5").contentType(MediaType.APPLICATION_JSON).content(addressBody))
            .andExpect(status().isUnauthorized());
        mvc.perform(delete("/addresses/5"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(addresses);
    }

    @Test
    void create_address_invalid_body_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(post("/addresses").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"recipientName":"Nguyễn Văn A","phone":"+84912345678","line1":"1 Tràng Tiền",
                     "provinceCode":"01"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(addresses);
    }

    @Test
    void create_address_ok() throws Exception {
        // given
        var token = customerToken();
        var created = Address.builder().withAddressId(5L).withUserId(42L).withRecipientName("Nguyễn Văn A")
            .withPhone("0912345678").withLine1("1 Tràng Tiền").withWard("Phường Hoàn Kiếm").withProvinceCode("01")
            .withCountryCode("VN").withDefaultAddress(true)
            .withCreatedAt(OffsetDateTime.of(2026, 10, 31, 0, 0, 0, 0, ZoneOffset.UTC)).build();
        when(addresses.create(eq(42L), any())).thenReturn(created);

        // when / then
        mvc.perform(post("/addresses").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(addressBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.addressId").value(5))
            .andExpect(jsonPath("$.phone").value("0912345678"))
            .andExpect(jsonPath("$.provinceCode").value("01"))
            .andExpect(jsonPath("$.countryCode").value("VN"))
            .andExpect(jsonPath("$.defaultAddress").value(true))
            .andExpect(jsonPath("$.userId").doesNotExist());
    }

    private String customerToken() {
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());

        return customerIssuer.issue(42L).getAccessToken();
    }
}
