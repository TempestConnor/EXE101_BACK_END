package com.exe101.artist.repository;

import com.exe101.artist.model.TermsImageUpload;
import com.exe101.artist.model.TermsPublication;
import com.exe101.artist.service.ArtistTermsService;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionRequest;
import com.exe101.commission.model.QuoteTerms;
import com.exe101.commission.service.CommissionQuoteService;
import com.exe101.commission.service.CommissionService;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.StaffAccount;
import com.exe101.staff.repository.StaffRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

// Published terms can never be changed or deleted (TR_ArtistTerms_Immutable), so this runs only against the
// disposable test database (database/test/reset_test_database.sql), selected by TEST_DB_URL, and leaves its rows
// there. Each test uses new Users and Artists. The scheduled jobs are slowed down so they do not run during a test.
@SpringBootTest(properties = {"app.orders.expiry-interval=PT1H", "app.fulfillment.grant-interval=PT1H",
    "app.checkout.tax-rate=0.10"})
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class ArtistTermsPersistenceTest {
    private static byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2};
    @Autowired
    private ArtistTermsService termsService;
    @Autowired
    private CommissionService commissionService;
    @Autowired
    private CommissionQuoteService quoteService;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private StaffRepository staffRepository;
    @Autowired
    private JdbcTemplate jdbc;
    private Long adminId;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
    }

    @BeforeEach
    void setUp() {
        adminId = admin().getStaffId();
    }

    @Test
    void terms_version_immutable_ko() {
        // given
        var artistUserId = newUser();
        newArtist(artistUserId);
        var terms = termsService.publish(artistUserId, publication("No refund after the lineart", List.of(),
            upload()));
        var imageId = terms.getImages().get(0).getImageId();
        // Without images, so the delete reaches the trigger rather than the images' foreign key.
        var bare = termsService.publish(artistUserId, publication("Full refund before the sketch", List.of()));

        // when / then
        assertAll(
            () -> assertThatThrownBy(() -> jdbc.update(
                    "UPDATE dbo.ArtistTerms SET TermsText = N'Full refund' WHERE ArtistTermsId = ?",
                    terms.getArtistTermsId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Published Artist terms are immutable."),
            () -> assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM dbo.ArtistTerms WHERE ArtistTermsId = ?", bare.getArtistTermsId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Published Artist terms are immutable."),
            () -> assertThatThrownBy(() -> jdbc.update(
                    "UPDATE dbo.ArtistTermsImage SET SortOrder = 5 WHERE ArtistTermsImageId = ?", imageId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Images of published Artist terms are immutable."),
            () -> assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM dbo.ArtistTermsImage WHERE ArtistTermsImageId = ?", imageId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Images of published Artist terms are immutable."),
            () -> assertThat(termsService.get(terms.getArtistTermsId()).getText())
                .isEqualTo("No refund after the lineart"),
            () -> assertThat(termsService.get(terms.getArtistTermsId()).getImages()).hasSize(1),
            () -> assertThat(termsService.get(bare.getArtistTermsId()).getVersionNo()).isEqualTo(2)
        );
    }

    @Test
    void terms_version_unique_ok() {
        // given
        var artistUserId = newUser();
        var artistId = newArtist(artistUserId);
        var first = termsService.publish(artistUserId, publication("No refund after the lineart", List.of(),
            upload()));

        // when
        var second = termsService.publish(artistUserId, publication("Full refund before the sketch",
            List.of(first.getImages().get(0).getImageId())));

        // then
        assertAll(
            () -> assertThat(first.getVersionNo()).isEqualTo(1),
            () -> assertThat(second.getVersionNo()).isEqualTo(2),
            () -> assertThat(second.getImages().get(0).getImageId())
                .isNotEqualTo(first.getImages().get(0).getImageId()),
            () -> assertThat(termsService.getCurrent(artistId).getArtistTermsId())
                .isEqualTo(second.getArtistTermsId()),
            () -> assertThat(termsService.get(first.getArtistTermsId()).getText())
                .isEqualTo("No refund after the lineart"),
            () -> assertThatThrownBy(() -> jdbc.update("INSERT INTO dbo.ArtistTerms (ArtistId, VersionNo, TermsText, "
                    + "PublishedAt) VALUES (?, 2, N'Duplicate', SYSDATETIMEOFFSET())", artistId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("UQ_ArtistTerms_Version")
        );
    }

    @Test
    void quote_terms_link_ok() {
        // given
        var customerId = newUser();
        var artistUserId = newUser();
        var artistId = newArtist(artistUserId);
        var accepted = termsService.publish(artistUserId, publication("No refund after the lineart", List.of()));
        Commission commission = commissionService.request(customerId, CommissionRequest.builder()
            .withArtistId(artistId)
            .withDescription("Portrait of my cat")
            .withAllowPublicDisplay(false)
            .withUploads(List.of())
            .build());
        var offered = quoteService.offer(artistUserId, commission.getCommissionId(), QuoteTerms.builder()
            .withPrice(new BigDecimal("1500000"))
            .withScopeText("Full-body portrait, one character")
            .withExpectedCompletionTerms("Within 3 weeks of the first payment")
            .build());

        // when
        quoteService.accept(customerId, commission.getCommissionId(), offered.getQuoteId(),
            accepted.getArtistTermsId());
        var newer = termsService.publish(artistUserId, publication("Full refund before the sketch", List.of()));

        // then
        assertAll(
            () -> assertThat(jdbc.queryForObject("SELECT ArtistTermsId FROM dbo.CommissionQuote WHERE QuoteId = ?",
                Long.class, offered.getQuoteId())).isEqualTo(accepted.getArtistTermsId()),
            () -> assertThat(quoteService.getAcceptedTerms(customerId, commission.getCommissionId(),
                offered.getQuoteId()).getText()).isEqualTo("No refund after the lineart"),
            () -> assertThat(quoteService.getAcceptedTermsAsAdmin(commission.getCommissionId(),
                offered.getQuoteId()).getVersionNo()).isEqualTo(1),
            () -> assertThatThrownBy(() -> jdbc.update(
                    "UPDATE dbo.CommissionQuote SET ArtistTermsId = ? WHERE QuoteId = ?",
                    newer.getArtistTermsId(), offered.getQuoteId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Accepted quote terms are immutable.")
        );
    }

    private TermsPublication publication(String text, List<Long> keepImageIds, TermsImageUpload... uploads) {
        return TermsPublication.builder()
            .withText(text)
            .withKeepImageIds(keepImageIds)
            .withUploads(List.of(uploads))
            .build();
    }

    private TermsImageUpload upload() {
        return TermsImageUpload.builder()
            .withOriginalName("terms.png")
            .withBytes(png)
            .build();
    }

    private Long newArtist(Long artistUserId) {
        return artistRepository.saveAndFlush(new ArtistProfile(artistUserId, "Painter", "ACTIVE", true, adminId,
            now(), null)).getArtistId();
    }

    private Long newUser() {
        return customerService.register(new CustomerRegistration(
            "terms-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Member")).getUserId();
    }

    private StaffAccount admin() {
        return staffRepository.findAll().stream()
            .filter(staff -> "ADMIN".equals(staff.getRole()) && staff.isIsActive())
            .findFirst()
            .orElseGet(() -> staffRepository.saveAndFlush(new StaffAccount("terms-test-" + UUID.randomUUID(),
                "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now())));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
