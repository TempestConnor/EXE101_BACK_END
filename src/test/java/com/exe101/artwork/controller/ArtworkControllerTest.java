package com.exe101.artwork.controller;

import com.exe101.artwork.mapper.ArtworkMapperImpl;
import com.exe101.artwork.model.Artwork;
import com.exe101.artwork.model.ArtworkStatus;
import com.exe101.artwork.service.ArtworkService;
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
import com.exe101.tag.model.Tag;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ArtworkController.class, ArtworkAdminController.class})
@Import({ArtworkMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class ArtworkControllerTest {
    private static String createBody = """
        {"title":"Sunset","description":"Oil on canvas","dimension":"2D","tagIds":[1]}
        """;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @MockitoBean
    private ArtworkService artworks;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void create_artwork_missing_title_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(post("/artworks").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"title":"  ","dimension":"2D"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(artworks);
    }

    @Test
    void create_artwork_ok() throws Exception {
        // given
        var token = customerToken();
        var created = Artwork.builder()
            .withArtworkId(10L)
            .withArtistId(3L)
            .withTitle("Sunset")
            .withDescription("Oil on canvas")
            .withDimension("2D")
            .withStatus(ArtworkStatus.DRAFT)
            .withCreatedAt(OffsetDateTime.of(2026, 10, 31, 0, 0, 0, 0, ZoneOffset.UTC))
            .withVersion(new byte[] {0, 0, 0, 0, 0, 0, 0, 1})
            .withTags(List.of(new Tag(1, "Landscape", true)))
            .withPreviews(List.of())
            .build();
        when(artworks.create(eq(42L), any())).thenReturn(created);

        // when / then
        mvc.perform(post("/artworks").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(createBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.artworkId").value(10))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.dimension").value("2D"))
            .andExpect(jsonPath("$.purchasable").value(false))
            .andExpect(jsonPath("$.version").value("AAAAAAAAAAE="))
            .andExpect(jsonPath("$.tags[0].name").value("Landscape"))
            .andExpect(jsonPath("$.tags[0].active").doesNotExist())
            .andExpect(jsonPath("$.previews").isEmpty());
    }

    @Test
    void moderate_artwork_customer_token_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(put("/admin/artworks/10/status").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"status":"MODERATED","reason":"Copyright claim"}
                    """))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        verifyNoInteractions(artworks);
    }

    private String customerToken() {
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());

        return customerIssuer.issue(42L).getAccessToken();
    }
}
