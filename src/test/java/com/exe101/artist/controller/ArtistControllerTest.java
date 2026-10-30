package com.exe101.artist.controller;

import com.exe101.artist.mapper.ArtistMapperImpl;
import com.exe101.artist.service.ArtistService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.model.Customer;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.model.Staff;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ArtistController.class)
@Import({ArtistMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class ArtistControllerTest {
    private static String grantBody = """
        {"userId":42,"displayName":"Painter","reason":"Joined the team"}
        """;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private StaffTokenIssuer staffIssuer;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @MockitoBean
    private ArtistService artists;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void grant_artist_profile_missing_display_name_ko() throws Exception {
        // given
        var token = staffIssuer.issue(7L).getAccessToken();
        when(staff.requireActive(7L)).thenReturn(new Staff(7L, "admin", "ADMIN"));

        // when / then
        mvc.perform(post("/artists").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"userId":42,"displayName":"  "}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(artists);
    }

    @Test
    void grant_artist_profile_non_admin_staff_ko() throws Exception {
        // given
        var token = staffIssuer.issue(8L).getAccessToken();
        when(staff.requireActive(8L)).thenReturn(new Staff(8L, "moderator", "MODERATOR"));

        // when / then
        mvc.perform(post("/artists").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(grantBody))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        verifyNoInteractions(artists);
    }

    @Test
    void grant_artist_profile_customer_token_ko() throws Exception {
        // given
        var token = customerIssuer.issue(42L).getAccessToken();
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());

        // when / then
        mvc.perform(post("/artists").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(grantBody))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        verifyNoInteractions(artists);
    }

    @Test
    void customer_token_on_staff_endpoint_ko() throws Exception {
        // given
        // A Customer whose User ID equals an Admin's Staff ID must not be treated as that Admin.
        var token = customerIssuer.issue(7L).getAccessToken();
        when(customers.requireActive(7L)).thenReturn(Customer.builder().withUserId(7L).build());

        // when / then
        mvc.perform(post("/artists").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(grantBody))
            .andExpect(status().isForbidden());
        verifyNoInteractions(staff);
    }

    @Test
    void grant_artist_profile_unauthenticated_ko() throws Exception {
        // given / when / then
        mvc.perform(post("/artists").contentType(MediaType.APPLICATION_JSON).content(grantBody))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", "Bearer"))
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(artists);
    }

    @Test
    void staff_token_after_deactivation_ko() throws Exception {
        // given
        var token = staffIssuer.issue(7L).getAccessToken();
        when(staff.requireActive(7L)).thenThrow(new AppException(AppErrorMessage.INVALID_CREDENTIALS));

        // when / then
        mvc.perform(post("/artists").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(grantBody))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(artists);
    }
}
