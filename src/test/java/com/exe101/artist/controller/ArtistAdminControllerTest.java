package com.exe101.artist.controller;

import com.exe101.artist.mapper.ArtistMapperImpl;
import com.exe101.artist.model.Artist;
import com.exe101.artist.model.ArtistDeactivation;
import com.exe101.artist.service.ArtistService;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ArtistAdminController.class)
@Import({ArtistMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class ArtistAdminControllerTest {
    private static String reasonBody = """
        {"reason":"Left the team"}
        """;
    private static OffsetDateTime startedAt = OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.UTC);
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
    void deactivate_artist_ok() throws Exception {
        // given
        var token = adminToken();
        when(artists.deactivate(7L, 9L, "Left the team")).thenReturn(deactivation("SUSPENDED", null));

        // when / then
        mvc.perform(post("/admin/artists/9/deactivation").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(reasonBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.deactivationId").value(5))
            .andExpect(jsonPath("$.artistId").value(9))
            .andExpect(jsonPath("$.artistStatus").value("SUSPENDED"))
            .andExpect(jsonPath("$.startedAt").value("2026-11-02T09:00:00Z"))
            .andExpect(jsonPath("$.closedAt").doesNotExist())
            .andExpect(jsonPath("$.reason").value("Left the team"))
            .andExpect(jsonPath("$.staffId").value(7));
    }

    @Test
    void deactivate_artist_reason_missing_ko() throws Exception {
        // given
        var token = adminToken();
        var tooLong = "x".repeat(2001);

        // when / then
        mvc.perform(post("/admin/artists/9/deactivation").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"reason":"  "}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        mvc.perform(post("/admin/artists/9/deactivation").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"" + tooLong + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(artists);
    }

    @Test
    void deactivate_artist_non_admin_ko() throws Exception {
        // given
        var moderatorToken = staffIssuer.issue(8L).getAccessToken();
        when(staff.requireActive(8L)).thenReturn(new Staff(8L, "moderator", "MODERATOR"));
        var customerToken = customerIssuer.issue(42L).getAccessToken();
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());

        // when / then
        mvc.perform(post("/admin/artists/9/deactivation").header("Authorization", "Bearer " + moderatorToken)
                .contentType(MediaType.APPLICATION_JSON).content(reasonBody))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        mvc.perform(post("/admin/artists/9/deactivation").header("Authorization", "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON).content(reasonBody))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        verifyNoInteractions(artists);
    }

    @Test
    void reinstate_artist_ok() throws Exception {
        // given
        var token = adminToken();
        var closedAt = startedAt.plusDays(3);
        when(artists.reinstate(7L, 9L, "Rejoined")).thenReturn(deactivation("ACTIVE", closedAt));

        // when / then
        mvc.perform(post("/admin/artists/9/reinstatement").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"reason":"Rejoined"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.artistId").value(9))
            .andExpect(jsonPath("$.artistStatus").value("ACTIVE"))
            .andExpect(jsonPath("$.closedAt").value("2026-11-05T09:00:00Z"));
    }

    @Test
    void reinstate_artist_reason_missing_ko() throws Exception {
        // given
        var token = adminToken();

        // when / then
        mvc.perform(post("/admin/artists/9/reinstatement").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(artists);
    }

    @Test
    void list_artists_ok() throws Exception {
        // given
        var token = adminToken();
        when(artists.listAll()).thenReturn(List.of(
            Artist.builder().withArtistId(9L).withUserId(42L).withDisplayName("Painter").withStatus("ACTIVE")
                .withCreatedAt(startedAt).build(),
            Artist.builder().withArtistId(10L).withUserId(43L).withDisplayName("Sculptor").withStatus("SUSPENDED")
                .withCreatedAt(startedAt).build()));

        // when / then
        mvc.perform(get("/admin/artists").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].artistId").value(9))
            .andExpect(jsonPath("$[0].userId").value(42))
            .andExpect(jsonPath("$[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$[1].artistId").value(10))
            .andExpect(jsonPath("$[1].displayName").value("Sculptor"))
            .andExpect(jsonPath("$[1].status").value("SUSPENDED"));
    }

    @Test
    void list_artists_customer_token_ko() throws Exception {
        // given
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());
        var token = customerIssuer.issue(42L).getAccessToken();

        // when / then
        mvc.perform(get("/admin/artists").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        verifyNoInteractions(artists);
    }

    private String adminToken() {
        when(staff.requireActive(7L)).thenReturn(new Staff(7L, "admin", "ADMIN"));

        return staffIssuer.issue(7L).getAccessToken();
    }

    private ArtistDeactivation deactivation(String artistStatus, OffsetDateTime closedAt) {
        return ArtistDeactivation.builder()
            .withDeactivationId(5L)
            .withArtistId(9L)
            .withArtistStatus(artistStatus)
            .withStartedAt(startedAt)
            .withClosedAt(closedAt)
            .withReason("Left the team")
            .withStaffId(7L)
            .build();
    }
}
