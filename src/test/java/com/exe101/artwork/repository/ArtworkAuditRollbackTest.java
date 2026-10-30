package com.exe101.artwork.repository;

import com.exe101.artist.repository.ArtistRepository;
import com.exe101.artwork.model.ArtworkStatus;
import com.exe101.artwork.model.StatusChange;
import com.exe101.artwork.service.ArtworkService;
import com.exe101.audit.service.AuditService;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.repository.CustomerRepository;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.Artwork;
import com.exe101.entity.StaffAccount;
import com.exe101.staff.repository.StaffRepository;
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
class ArtworkAuditRollbackTest {
    @Autowired
    private ArtworkService service;
    @Autowired
    private ArtworkRepository repository;
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
    void moderate_artwork_audit_failure_rolls_back_ko() {
        // given
        var now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        var existingAdmin = staffRepository.findAll().stream()
            .filter(staff -> "ADMIN".equals(staff.getRole()) && staff.isIsActive()).findFirst();
        var admin = existingAdmin.orElseGet(() -> staffRepository.saveAndFlush(new StaffAccount(
            "f05-test-" + UUID.randomUUID(), "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now)));
        var userId = customerService.register(new CustomerRegistration(
            "f05-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Painter")).getUserId();
        var artist = artistRepository.saveAndFlush(new ArtistProfile(userId, "Painter", "ACTIVE", false,
            admin.getStaffId(), now, null));
        var artwork = repository.saveAndFlush(new Artwork(artist.getArtistId(), "Sunset", null, "2D", "PUBLISHED",
            now, now, null));
        doThrow(new DataAccessResourceFailureException("audit unavailable")).when(auditService).record(any());

        // when / then
        try {
            assertThatThrownBy(() -> service.changeStatusAsAdmin(admin.getStaffId(), artwork.getArtworkId(),
                new StatusChange(ArtworkStatus.MODERATED, "Copyright claim")))
                .isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(repository.findById(artwork.getArtworkId()).orElseThrow().getStatus()).isEqualTo("PUBLISHED");
        } finally {
            repository.deleteById(artwork.getArtworkId());
            artistRepository.deleteById(artist.getArtistId());
            customerRepository.deleteById(userId);

            if (existingAdmin.isEmpty()) {
                staffRepository.deleteById(admin.getStaffId());
            }
        }
    }
}
