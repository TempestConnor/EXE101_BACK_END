package com.exe101.customer.controller;

import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.customer.config.CustomerSecurityConfiguration;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.mapper.CustomerMapperImpl;
import com.exe101.customer.model.Customer;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.security.SecurityProblemHandler;
import com.exe101.customer.service.CustomerLoginServiceImpl;
import com.exe101.customer.service.CustomerService;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CustomerController.class)
@Import({CustomerMapperImpl.class, CustomerLoginServiceImpl.class, CustomerTokenConfiguration.class,
    CustomerSecurityConfiguration.class, CustomerTokenIssuer.class, CustomerAuthenticationConverter.class,
    SecurityProblemHandler.class, GlobalExceptionHandler.class})
class CustomerControllerTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer issuer;
    @MockitoBean
    private CustomerService customers;

    @Test
    void registration_returns_only_public_customer_fields_ok() throws Exception {
        // given
        when(customers.register(any())).thenReturn(customer());

        // when / then
        mvc.perform(post("/customers/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"buyer@example.com","password":"long-password","displayName":"Buyer",
                 "userId":1,"status":"BLOCKED","role":"ADMIN"}
                """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.userId").value(42))
            .andExpect(jsonPath("$.email").value("buyer@example.com"))
            .andExpect(jsonPath("$.password").doesNotExist())
            .andExpect(jsonPath("$.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.status").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"email\":\"bad\",\"password\":\"long-password\",\"displayName\":\"Buyer\"}",
        "{\"email\":\"buyer@example.com\",\"password\":\"short\",\"displayName\":\"Buyer\"}",
        "{\"email\":\"buyer@example.com\",\"password\":\"long-password\",\"displayName\":\" \"}"
    })
    void invalid_registration_ko(String body) throws Exception {
        // given / when / then
        mvc.perform(post("/customers/register").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(customers);
    }

    @Test
    void malformed_json_ko() throws Exception {
        // given / when / then
        mvc.perform(post("/customers/register").contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void duplicate_registration_ko() throws Exception {
        // given
        when(customers.register(any())).thenThrow(new AppException(AppErrorMessage.EMAIL_ALREADY_REGISTERED));

        // when / then
        mvc.perform(post("/customers/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"buyer@example.com","password":"long-password","displayName":"Buyer"}
                """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void login_returns_bearer_token_without_session_ok() throws Exception {
        // given
        when(customers.authenticate(any())).thenReturn(customer());

        // when / then
        mvc.perform(post("/customers/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"buyer@example.com","password":"long-password"}
                """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isString())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").value(900))
            .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void bad_login_is_generic_ko() throws Exception {
        // given
        when(customers.authenticate(any())).thenThrow(new AppException(AppErrorMessage.INVALID_CREDENTIALS));

        // when / then
        mvc.perform(post("/customers/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"buyer@example.com","password":"long-password"}
                """))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", "Bearer"))
            .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
    }

    @Test
    void me_uses_verified_token_identity_ok() throws Exception {
        // given
        when(customers.requireActive(42L)).thenReturn(customer());
        var token = issuer.issue(42L).getAccessToken();

        // when / then
        mvc.perform(get("/customers/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(42))
            .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void missing_token_ko() throws Exception {
        // given / when / then
        mvc.perform(get("/customers/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(header().string("WWW-Authenticate", "Bearer"));
        verifyNoInteractions(customers);
    }

    @Test
    void malformed_token_on_public_endpoint_ko() throws Exception {
        // given / when / then
        mvc.perform(post("/customers/login").header("Authorization", "Bearer malformed")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(customers);
    }

    @Test
    void blocked_account_existing_token_ko() throws Exception {
        // given
        var token = issuer.issue(42L).getAccessToken();
        when(customers.requireActive(42L)).thenThrow(new AppException(AppErrorMessage.INVALID_CREDENTIALS));

        // when / then
        mvc.perform(get("/customers/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void account_lookup_outage_is_server_failure_not_bad_credentials_ko() throws Exception {
        // given
        var token = issuer.issue(42L).getAccessToken();
        when(customers.requireActive(42L))
            .thenThrow(new DataAccessResourceFailureException("private database connection details"));

        // when / then
        mvc.perform(get("/customers/me").header("Authorization", "Bearer " + token))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.detail").value("The request could not be completed."))
            .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void customer_token_cannot_access_staff_paths_ko() throws Exception {
        // given
        var token = issuer.issue(42L).getAccessToken();
        when(customers.requireActive(42L)).thenReturn(customer());

        // when / then
        mvc.perform(get("/staff").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
    }

    @Test
    void internal_failure_hides_details_ko() throws Exception {
        // given
        when(customers.register(any())).thenThrow(new IllegalStateException("sensitive internal data"));

        // when / then
        mvc.perform(post("/customers/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"buyer@example.com","password":"long-password","displayName":"Buyer"}
                """))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.detail").value("The request could not be completed."))
            .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));
    }

    private Customer customer() {
        return new Customer(42L, "buyer@example.com", "Buyer", OffsetDateTime.parse("2026-09-27T00:00:00Z"));
    }
}
