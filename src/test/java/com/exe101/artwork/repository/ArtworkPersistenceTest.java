package com.exe101.artwork.repository;

import com.exe101.artist.repository.ArtistRepository;
import com.exe101.artwork.model.ArtworkDetails;
import com.exe101.artwork.service.ArtworkService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.ArtworkMedia;
import com.exe101.entity.StaffAccount;
import com.exe101.entity.StoredFile;
import com.exe101.entity.Tag;
import com.exe101.file.repository.StoredFileRepository;
import com.exe101.staff.repository.StaffRepository;
import com.exe101.tag.repository.TagRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class ArtworkPersistenceTest {
    @Autowired
    private ArtworkService service;
    @Autowired
    private ArtworkRepository repository;
    @Autowired
    private ArtworkMediaRepository mediaRepository;
    @Autowired
    private ArtworkTagRepository tagLinkRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private StoredFileRepository fileRepository;
    @Autowired
    private ArtistRepository artistRepository;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private StaffRepository staffRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void edit_artwork_title_keeps_other_rows_ok() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var userId = artistUser();
            var tag = tagRepository.saveAndFlush(new Tag("f05-test-" + UUID.randomUUID(), true));
            var created = service.create(userId, new ArtworkDetails("Sunset", "First", "2D",
                List.of(tag.getTagId()), null));
            var file = fileRepository.saveAndFlush(new StoredFile("f05-test/" + UUID.randomUUID(), "sunset.png",
                "image/png", 12, "PUBLIC", now()));
            var media = mediaRepository.saveAndFlush(new ArtworkMedia(created.getArtworkId(), file.getFileId(), 0));
            var current = service.getOwn(userId, created.getArtworkId());
            var updated = service.update(userId, created.getArtworkId(), new ArtworkDetails("Sunset at dusk",
                "First", "2D", List.of(tag.getTagId()), current.getVersion()));
            var links = tagLinkRepository.findByIdArtworkIdIn(List.of(created.getArtworkId()));
            var previews = mediaRepository.findByArtworkIdInOrderBySortOrderAsc(List.of(created.getArtworkId()));
            assertAll(
                () -> assertThat(updated.getTitle()).isEqualTo("Sunset at dusk"),
                () -> assertThat(updated.getVersion()).isNotEqualTo(current.getVersion()),
                () -> assertThat(links).extracting("id.tagId").containsExactly(tag.getTagId()),
                () -> assertThat(previews).extracting("artworkMediaId").containsExactly(media.getArtworkMediaId()),
                () -> assertThat(fileRepository.findById(file.getFileId())).isPresent()
            );
            status.setRollbackOnly();
        });
    }

    @Test
    void create_artwork_inactive_tag_ko() {
        // given
        var transaction = new TransactionTemplate(transactionManager);

        // when / then
        transaction.executeWithoutResult(status -> {
            var userId = artistUser();
            var inactive = tagRepository.saveAndFlush(new Tag("f05-test-" + UUID.randomUUID(), false));
            var before = repository.count();
            assertAll(
                () -> assertThatThrownBy(() -> service.create(userId, new ArtworkDetails("Sunset", null, "2D",
                    List.of(inactive.getTagId()), null)))
                    .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.TAG_NOT_AVAILABLE.getDetail()),
                () -> assertThat(repository.count()).isEqualTo(before)
            );
            status.setRollbackOnly();
        });
    }

    private Long artistUser() {
        var userId = customerService.register(new CustomerRegistration(
            "f05-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Painter")).getUserId();
        artistRepository.saveAndFlush(new ArtistProfile(userId, "Painter", "ACTIVE", false,
            activeAdmin().getStaffId(), now(), null));

        return userId;
    }

    private StaffAccount activeAdmin() {
        return staffRepository.findAll().stream()
            .filter(staff -> "ADMIN".equals(staff.getRole()) && staff.isIsActive())
            .findFirst()
            .orElseGet(() -> staffRepository.saveAndFlush(new StaffAccount("f05-test-" + UUID.randomUUID(),
                "unused".getBytes(StandardCharsets.UTF_8), "ADMIN", true, now())));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
