package com.exe101.artist.controller;

import com.exe101.artist.mapper.ArtistMapperImpl;
import com.exe101.artist.model.ArtistTerms;
import com.exe101.artist.model.ArtistTermsImage;
import com.exe101.artist.model.TermsPublication;
import com.exe101.artist.service.ArtistTermsService;
import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.model.Customer;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import com.exe101.file.model.FileContent;
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.security.StaffAuthenticationConverter;
import com.exe101.staff.security.StaffTokenIssuer;
import com.exe101.staff.service.StaffService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ArtistTermsController.class)
@Import({ArtistMapperImpl.class, StaffTokenIssuer.class, StaffTokenConfiguration.class,
    StaffAuthenticationConverter.class, CustomerTokenIssuer.class, CustomerTokenConfiguration.class,
    CustomerAuthenticationConverter.class, SecurityConfiguration.class, SecurityProblemHandler.class,
    GlobalExceptionHandler.class})
class ArtistTermsControllerTest {
    private static byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2};
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @MockitoBean
    private ArtistTermsService terms;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void publish_terms_ok() throws Exception {
        // given
        var token = customerToken();
        when(terms.publish(eq(50L), any())).thenReturn(artistTerms());
        var request = new MockMultipartFile("request", "", MediaType.APPLICATION_JSON_VALUE, """
            {"text":"No refund after the lineart","keepImageIds":[100]}
            """.getBytes());
        var image = new MockMultipartFile("images", "terms.png", MediaType.IMAGE_PNG_VALUE, png);

        // when / then
        mvc.perform(multipart("/artists/me/terms").file(request).file(image)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.artistTermsId").value(61))
            .andExpect(jsonPath("$.artistId").value(9))
            .andExpect(jsonPath("$.versionNo").value(2))
            .andExpect(jsonPath("$.text").value("No refund after the lineart"))
            .andExpect(jsonPath("$.images[0].imageId").value(202))
            .andExpect(jsonPath("$.images[0].sortOrder").value(0))
            .andExpect(jsonPath("$.images[0].contentType").value("image/png"))
            .andExpect(jsonPath("$.images[0].byteLength").value(10))
            .andExpect(jsonPath("$.images[0].fileId").doesNotExist());
        var publication = ArgumentCaptor.forClass(TermsPublication.class);
        verify(terms).publish(eq(50L), publication.capture());
        assertAll(
            () -> assertThat(publication.getValue().getText()).isEqualTo("No refund after the lineart"),
            () -> assertThat(publication.getValue().getKeepImageIds()).containsExactly(100L),
            () -> assertThat(publication.getValue().getUploads()).hasSize(1),
            () -> assertThat(publication.getValue().getUploads().get(0).getBytes()).isEqualTo(png)
        );
    }

    @Test
    void publish_terms_invalid_ko() throws Exception {
        // given
        var token = customerToken();
        var request = new MockMultipartFile("request", "", MediaType.APPLICATION_JSON_VALUE, """
            {"text":"  "}
            """.getBytes());

        // when / then
        mvc.perform(multipart("/artists/me/terms").file(request).header("Authorization", "Bearer " + token))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(terms);
    }

    @Test
    void get_current_terms_public_ok() throws Exception {
        // given
        when(terms.getCurrent(9L)).thenReturn(artistTerms());

        // when / then
        mvc.perform(get("/artists/9/terms"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.artistTermsId").value(61))
            .andExpect(jsonPath("$.versionNo").value(2))
            .andExpect(jsonPath("$.images[0].imageId").value(202));
    }

    @Test
    void get_terms_image_content_ok() throws Exception {
        // given
        when(terms.getImage(9L, 202L)).thenReturn(FileContent.builder()
            .withFileId(700L)
            .withOriginalName("terms.png")
            .withContentType("image/png")
            .withByteLength(png.length)
            .withContent(new ByteArrayResource(png))
            .build());

        // when / then
        mvc.perform(get("/artists/9/terms/images/202/content"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(header().longValue("Content-Length", png.length))
            .andExpect(content().bytes(png));
    }

    private ArtistTerms artistTerms() {
        return ArtistTerms.builder()
            .withArtistTermsId(61L)
            .withArtistId(9L)
            .withVersionNo(2)
            .withText("No refund after the lineart")
            .withPublishedAt(OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.UTC))
            .withImages(List.of(ArtistTermsImage.builder()
                .withImageId(202L)
                .withFileId(700L)
                .withSortOrder(0)
                .withContentType("image/png")
                .withByteLength(png.length)
                .build()))
            .build();
    }

    private String customerToken() {
        when(customers.requireActive(50L)).thenReturn(Customer.builder().withUserId(50L).build());

        return customerIssuer.issue(50L).getAccessToken();
    }
}
