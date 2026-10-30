package com.exe101.listing.repository;

import com.exe101.artist.repository.ArtistRepository;
import com.exe101.artwork.repository.ArtworkRepository;
import com.exe101.audit.service.AuditService;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.repository.CustomerRepository;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.Artwork;
import com.exe101.entity.Listing;
import com.exe101.entity.StaffAccount;
import com.exe101.listing.model.ListingStatus;
import com.exe101.listing.model.ListingStatusChange;
import com.exe101.listing.service.ListingService;
import com.exe101.staff.repository.StaffRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class ListingAuditRollbackTest {
    @Autowired
    private ListingService service;
    @Autowired
    private ListingRepository repository;
    @Autowired
    private ArtworkRepository artworkRepository;
    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private StaffRepository staffRepository;
    @MockitoBean
    private AuditService auditService;

    @Test
    void moderation_and_audit_commit_together_ok() {
        // given
        var now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        var existingAdmin = staffRepository.findAll().stream()
            .filter(staff -> "ADMIN".equals(staff.getRole()) && staff.isIsActive()).findFirst();
        var admin = existingAdmin.orElseGet(() -> staffRepository.saveAndFlush(new StaffAccount(
            "f06-test-" + UUID.randomUUID(), "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now)));
        var userId = customerService.register(new CustomerRegistration(
            "f06-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Painter")).getUserId();
        var artist = artistRepository.saveAndFlush(new ArtistProfile(userId, "Painter", "ACTIVE", false,
            admin.getStaffId(), now, null));
        var artwork = artworkRepository.saveAndFlush(new Artwork(artist.getArtistId(), "Sunset", null, "2D",
            "PUBLISHED", now, now, null));
        var listing = repository.saveAndFlush(new Listing(artwork.getArtworkId(), "DIGITAL", "Sunset download", null,
            "PUBLISHED", "VND", new BigDecimal("50000"), null, null, null, false, now, now, null));
        doThrow(new DataAccessResourceFailureException("audit unavailable")).when(auditService).record(any());

        // when / then
        try {
            assertThatThrownBy(() -> service.changeStatusAsAdmin(admin.getStaffId(), listing.getListingId(),
                new ListingStatusChange(ListingStatus.MODERATED, "Copyright claim")))
                .isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(repository.findById(listing.getListingId()).orElseThrow().getStatus()).isEqualTo("PUBLISHED");
        } finally {
            repository.deleteById(listing.getListingId());
            artworkRepository.deleteById(artwork.getArtworkId());
            artistRepository.deleteById(artist.getArtistId());
            customerRepository.deleteById(userId);

            if (existingAdmin.isEmpty()) {
                staffRepository.deleteById(admin.getStaffId());
            }
        }
    }
}
