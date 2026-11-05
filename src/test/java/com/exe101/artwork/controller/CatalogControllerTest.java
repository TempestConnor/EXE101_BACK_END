package com.exe101.artwork.controller;

import com.exe101.artwork.mapper.ArtworkMapperImpl;
import com.exe101.artwork.model.Artwork;
import com.exe101.artwork.model.ArtworkStatus;
import com.exe101.artwork.service.ArtworkService;
import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import com.exe101.staff.config.StaffTokenConfiguration;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CatalogController.class)
@Import({ArtworkMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class CatalogControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ArtworkService artworks;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void list_has_artist_name_ok() throws Exception {
        // given
        var published = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        when(artworks.listPublished()).thenReturn(List.of(Artwork.builder()
            .withArtworkId(10L)
            .withArtistId(3L)
            .withArtistDisplayName("Painter")
            .withTitle("Sunset")
            .withDimension("2D")
            .withStatus(ArtworkStatus.PUBLISHED)
            .withPublishedAt(published)
            .withCreatedAt(published)
            .withVersion(new byte[] {0, 0, 0, 0, 0, 0, 0, 1})
            .withTags(List.of())
            .withPreviews(List.of())
            .build()));

        // when / then
        // Public: no token.
        mvc.perform(get("/catalog/artworks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].artworkId").value(10))
            .andExpect(jsonPath("$[0].artistId").value(3))
            .andExpect(jsonPath("$[0].artistDisplayName").value("Painter"));
    }
}
