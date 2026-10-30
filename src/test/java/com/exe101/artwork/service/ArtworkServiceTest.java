package com.exe101.artwork.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.artwork.mapper.ArtworkEntityMapper;
import com.exe101.artwork.model.ArtworkDetails;
import com.exe101.artwork.model.ArtworkStatus;
import com.exe101.artwork.model.PreviewUpload;
import com.exe101.artwork.model.StatusChange;
import com.exe101.artwork.repository.ArtworkMediaRepository;
import com.exe101.artwork.repository.ArtworkRepository;
import com.exe101.artwork.repository.ArtworkTagRepository;
import com.exe101.audit.model.AuditEntry;
import com.exe101.audit.service.AuditService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.Artwork;
import com.exe101.entity.ArtworkMedia;
import com.exe101.entity.ArtworkTag;
import com.exe101.entity.ArtworkTagId;
import com.exe101.entity.Tag;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;
import com.exe101.file.service.FileService;
import com.exe101.tag.mapper.TagEntityMapper;
import com.exe101.tag.repository.TagRepository;
import com.exe101.tag.service.TagServiceImpl;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// The real TagServiceImpl runs over a mocked TagRepository, so controlled-vocabulary checks are exercised.
@ExtendWith(MockitoExtension.class)
class ArtworkServiceTest {
    private static byte[] version = {0, 0, 0, 0, 0, 0, 0, 1};
    private static byte[] newerVersion = {0, 0, 0, 0, 0, 0, 0, 2};
    private static byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};
    private static OffsetDateTime firstPublished = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    @Mock
    private ArtworkRepository repository;
    @Mock
    private ArtworkMediaRepository mediaRepository;
    @Mock
    private ArtworkTagRepository tagLinkRepository;
    @Mock
    private TagRepository tagRepository;
    @Mock
    private ArtistService artistService;
    @Mock
    private FileService fileService;
    @Mock
    private AuditService auditService;
    @Mock
    private ListingAvailability listingAvailability;
    private ArtworkServiceImpl service;

    @BeforeEach
    void setUp() {
        var tagService = new TagServiceImpl(tagRepository, Mappers.getMapper(TagEntityMapper.class));
        service = new ArtworkServiceImpl(repository, mediaRepository, tagLinkRepository,
            Mappers.getMapper(ArtworkEntityMapper.class), artistService, tagService, fileService, auditService,
            listingAvailability);
    }

    @Test
    void create_draft_artwork_ok() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(tagRepository.findByTagIdInAndIsActiveTrueOrderByNameAsc(anyCollection()))
            .thenAnswer(invocation -> activeTags(invocation.getArgument(0)));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            Artwork entity = invocation.getArgument(0);
            entity.setArtworkId(10L);
            return entity;
        });

        // when
        var result = service.create(42L, details("Sunset", "2D", List.of(1, 2), null));
        var saved = ArgumentCaptor.forClass(Artwork.class);
        verify(repository).saveAndFlush(saved.capture());
        var links = ArgumentCaptor.forClass(List.class);
        verify(tagLinkRepository).saveAll(links.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(ArtworkStatus.DRAFT),
            () -> assertThat(result.isPurchasable()).isFalse(),
            () -> assertThat(saved.getValue().getArtistId()).isEqualTo(3L),
            () -> assertThat(saved.getValue().getTitle()).isEqualTo("Sunset"),
            () -> assertThat(saved.getValue().getStatus()).isEqualTo("DRAFT"),
            () -> assertThat(saved.getValue().getPublishedAt()).isNull(),
            () -> assertThat(links.getValue()).hasSize(2)
        );
    }

    @Test
    void create_artwork_without_artist_profile_ko() {
        // given
        when(artistService.getByUserId(42L)).thenThrow(new AppException(AppErrorMessage.ARTIST_PROFILE_REQUIRED));

        // when / then
        assertThatThrownBy(() -> service.create(42L, details("Sunset", "2D", List.of(), null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_PROFILE_REQUIRED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_artwork_inactive_artist_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("SUSPENDED"));

        // when / then
        assertThatThrownBy(() -> service.create(42L, details("Sunset", "2D", List.of(), null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_NOT_ACTIVE.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_artwork_invalid_dimension_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));

        // when / then
        assertThatThrownBy(() -> service.create(42L, details("Sunset", "4D", List.of(), null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_INVALID.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_artwork_unknown_tag_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(tagRepository.findByTagIdInAndIsActiveTrueOrderByNameAsc(anyCollection()))
            .thenReturn(List.of(tag(1, "Landscape", true)));

        // when / then
        assertThatThrownBy(() -> service.create(42L, details("Sunset", "2D", List.of(1, 999), null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.TAG_NOT_AVAILABLE.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void update_artwork_metadata_ok() {
        // given
        var existing = artwork(3L, "DRAFT", null);
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(10L)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        var result = service.update(42L, 10L, details(" Sunset (fixed) ", "2D", List.of(), version));

        // then
        assertAll(
            () -> assertThat(result.getTitle()).isEqualTo("Sunset (fixed)"),
            () -> assertThat(existing.getDescription()).isEqualTo("Corrected description"),
            () -> assertThat(existing.getDimension()).isEqualTo("2D"),
            () -> assertThat(existing.getArtistId()).isEqualTo(3L),
            () -> assertThat(existing.getStatus()).isEqualTo("DRAFT")
        );
    }

    @Test
    void update_artwork_not_owner_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(10L)).thenReturn(Optional.of(artwork(99L, "DRAFT", null)));

        // when / then
        assertThatThrownBy(() -> service.update(42L, 10L, details("Sunset", "2D", List.of(), version)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ACCESS_DENIED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void update_artwork_not_found_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(10L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.update(42L, 10L, details("Sunset", "2D", List.of(), version)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_NOT_FOUND.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void update_artwork_inactive_artist_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("SUSPENDED"));

        // when / then
        assertThatThrownBy(() -> service.update(42L, 10L, details("Sunset", "2D", List.of(), version)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_NOT_ACTIVE.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void update_artwork_stale_version_ko() {
        // given
        var existing = artwork(3L, "DRAFT", null);
        // The first edit already moved the row to a newer version.
        existing.setRowVer(newerVersion);
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(10L)).thenReturn(Optional.of(existing));

        // when / then
        assertThatThrownBy(() -> service.update(42L, 10L, details("Second edit", "2D", List.of(), version)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_VERSION_CONFLICT.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void replace_artwork_tags_ok() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(10L)).thenReturn(Optional.of(artwork(3L, "DRAFT", null)));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(tagLinkRepository.findByIdArtworkIdIn(List.of(10L))).thenReturn(List.of(link(1), link(2)));
        when(tagRepository.findByTagIdInAndIsActiveTrueOrderByNameAsc(anyCollection()))
            .thenAnswer(invocation -> activeTags(invocation.getArgument(0)));

        // when
        service.update(42L, 10L, details("Sunset", "2D", List.of(2, 3), version));
        var removed = ArgumentCaptor.forClass(Iterable.class);
        verify(tagLinkRepository).deleteAll(removed.capture());
        var added = ArgumentCaptor.forClass(List.class);
        verify(tagLinkRepository).saveAll(added.capture());

        // then
        assertAll(
            () -> assertThat(removed.getValue()).extracting("id.tagId").containsExactly(1),
            () -> assertThat(added.getValue()).extracting("id.tagId").containsExactly(3)
        );
    }

    @Test
    void change_dimension_after_publish_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(10L)).thenReturn(Optional.of(artwork(3L, "PUBLISHED", firstPublished)));

        // when / then
        assertThatThrownBy(() -> service.update(42L, 10L, details("Sunset", "3D", List.of(), version)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_DIMENSION_LOCKED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void attach_media_ok() {
        // given
        ownedDraft();
        when(fileService.storePublic(any())).thenReturn(StoredFileInfo.builder().withFileId(77L).build());
        when(mediaRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.addPreview(42L, 10L, new PreviewUpload("sunset.png", png, 2));
        var media = ArgumentCaptor.forClass(ArtworkMedia.class);
        verify(mediaRepository).saveAndFlush(media.capture());

        // then
        assertAll(
            () -> assertThat(media.getValue().getArtworkId()).isEqualTo(10L),
            () -> assertThat(media.getValue().getFileId()).isEqualTo(77L),
            () -> assertThat(media.getValue().getSortOrder()).isEqualTo(2)
        );
    }

    @Test
    void upload_preview_ok() {
        // given
        ownedDraft();
        when(mediaRepository.findTopByArtworkIdOrderBySortOrderDesc(10L))
            .thenReturn(Optional.of(new ArtworkMedia(10L, 70L, 4)));
        when(fileService.storePublic(any())).thenReturn(StoredFileInfo.builder().withFileId(77L).build());
        when(mediaRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.addPreview(42L, 10L, new PreviewUpload("sunset.png", png, null));
        var file = ArgumentCaptor.forClass(NewFile.class);
        verify(fileService).storePublic(file.capture());
        var media = ArgumentCaptor.forClass(ArtworkMedia.class);
        verify(mediaRepository).saveAndFlush(media.capture());

        // then
        assertAll(
            () -> assertThat(file.getValue().getContentType()).isEqualTo("image/png"),
            () -> assertThat(file.getValue().getOriginalName()).isEqualTo("sunset.png"),
            () -> assertThat(media.getValue().getFileId()).isEqualTo(77L),
            () -> assertThat(media.getValue().getSortOrder()).isEqualTo(5)
        );
    }

    @Test
    void attach_duplicate_sort_order_ko() {
        // given
        ownedDraft();
        when(mediaRepository.existsByArtworkIdAndSortOrder(10L, 2)).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> service.addPreview(42L, 10L, new PreviewUpload("sunset.png", png, 2)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_MEDIA_ORDER_TAKEN.getDetail());
        verifyNoInteractions(fileService);
        verify(mediaRepository, never()).saveAndFlush(any());
    }

    @Test
    void upload_preview_unsupported_type_ko() {
        // given
        ownedDraft();
        var gif = "GIF89a-image".getBytes();

        // when / then
        assertThatThrownBy(() -> service.addPreview(42L, 10L, new PreviewUpload("sunset.gif", gif, null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.PREVIEW_TYPE_NOT_SUPPORTED.getDetail());
        verifyNoInteractions(fileService);
        verify(mediaRepository, never()).saveAndFlush(any());
    }

    @Test
    void upload_preview_too_large_ko() {
        // given
        ownedDraft();
        var large = Arrays.copyOf(png, 10 * 1024 * 1024 + 1);

        // when / then
        assertThatThrownBy(() -> service.addPreview(42L, 10L, new PreviewUpload("large.png", large, null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.PREVIEW_TOO_LARGE.getDetail());
        verifyNoInteractions(fileService);
        verify(mediaRepository, never()).saveAndFlush(any());
    }

    @Test
    void upload_preview_over_limit_ko() {
        // given
        ownedDraft();
        when(mediaRepository.countByArtworkId(10L)).thenReturn(10L);

        // when / then
        assertThatThrownBy(() -> service.addPreview(42L, 10L, new PreviewUpload("sunset.png", png, null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.PREVIEW_LIMIT_REACHED.getDetail());
        verifyNoInteractions(fileService);
        verify(mediaRepository, never()).saveAndFlush(any());
    }

    @Test
    void remove_preview_keeps_file_ok() {
        // given
        ownedDraft();
        var media = new ArtworkMedia(10L, 77L, 0);
        media.setArtworkMediaId(5L);
        when(mediaRepository.findByArtworkMediaIdAndArtworkId(5L, 10L)).thenReturn(Optional.of(media));

        // when
        service.removePreview(42L, 10L, 5L);

        // then
        verify(mediaRepository).delete(media);
        verifyNoInteractions(fileService);
    }

    @Test
    void publish_artwork_ok() {
        // given
        var existing = artwork(3L, "DRAFT", null);
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(10L)).thenReturn(Optional.of(existing));
        when(mediaRepository.countByArtworkId(10L)).thenReturn(1L);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        var result = service.changeStatus(42L, 10L, new StatusChange(ArtworkStatus.PUBLISHED, null));

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(ArtworkStatus.PUBLISHED),
            () -> assertThat(existing.getPublishedAt()).isNotNull(),
            () -> verifyNoInteractions(auditService)
        );
    }

    @Test
    void publish_artwork_not_owner_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(10L)).thenReturn(Optional.of(artwork(99L, "DRAFT", null)));

        // when / then
        assertThatThrownBy(() -> service.changeStatus(42L, 10L, new StatusChange(ArtworkStatus.PUBLISHED, null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ACCESS_DENIED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void publish_artwork_without_media_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(10L)).thenReturn(Optional.of(artwork(3L, "DRAFT", null)));
        when(mediaRepository.countByArtworkId(10L)).thenReturn(0L);

        // when / then
        assertThatThrownBy(() -> service.changeStatus(42L, 10L, new StatusChange(ArtworkStatus.PUBLISHED, null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_PREVIEW_REQUIRED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void publish_artwork_inactive_artist_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("SUSPENDED"));
        when(repository.findById(10L)).thenReturn(Optional.of(artwork(3L, "DRAFT", null)));

        // when / then
        assertThatThrownBy(() -> service.changeStatus(42L, 10L, new StatusChange(ArtworkStatus.PUBLISHED, null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_NOT_ACTIVE.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void unlist_artwork_preserves_record_ok() {
        // given
        var existing = artwork(3L, "PUBLISHED", firstPublished);
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(10L)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        var result = service.changeStatus(42L, 10L, new StatusChange(ArtworkStatus.UNLISTED, null));

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(ArtworkStatus.UNLISTED),
            () -> assertThat(existing.getPublishedAt()).isEqualTo(firstPublished),
            () -> assertThat(existing.getTitle()).isEqualTo("Sunset"),
            () -> verify(repository, never()).delete(any()),
            () -> verify(repository, never()).deleteById(any())
        );
    }

    @Test
    void owner_cannot_change_moderated_artwork_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(10L)).thenReturn(Optional.of(artwork(3L, "MODERATED", firstPublished)));
        var edit = details("Sunset", "2D", List.of(), version);
        var unlist = new StatusChange(ArtworkStatus.UNLISTED, null);

        // when / then
        assertAll(
            () -> assertThatThrownBy(() -> service.update(42L, 10L, edit))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_NOT_EDITABLE.getDetail()),
            () -> assertThatThrownBy(() -> service.changeStatus(42L, 10L, unlist))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_NOT_EDITABLE.getDetail())
        );
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void change_archived_artwork_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(10L)).thenReturn(Optional.of(artwork(3L, "ARCHIVED", firstPublished)));
        var edit = details("Sunset", "2D", List.of(), version);
        var publish = new StatusChange(ArtworkStatus.PUBLISHED, null);
        var unlist = new StatusChange(ArtworkStatus.UNLISTED, "Admin request");

        // when / then
        assertAll(
            () -> assertThatThrownBy(() -> service.update(42L, 10L, edit))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_NOT_EDITABLE.getDetail()),
            () -> assertThatThrownBy(() -> service.changeStatus(42L, 10L, publish))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_NOT_EDITABLE.getDetail()),
            () -> assertThatThrownBy(() -> service.changeStatusAsAdmin(7L, 10L, unlist))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_NOT_EDITABLE.getDetail())
        );
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void moderate_artwork_ok() {
        // given
        var existing = artwork(3L, "PUBLISHED", firstPublished);
        when(repository.findById(10L)).thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        var result = service.changeStatusAsAdmin(7L, 10L,
            new StatusChange(ArtworkStatus.MODERATED, " Copyright claim "));
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(audit.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(ArtworkStatus.MODERATED),
            () -> assertThat(existing.getStatus()).isEqualTo("MODERATED"),
            () -> assertThat(audit.getValue().getStaffId()).isEqualTo(7L),
            () -> assertThat(audit.getValue().getActionType()).isEqualTo("ARTWORK_MODERATED"),
            () -> assertThat(audit.getValue().getTargetType()).isEqualTo("Artwork"),
            () -> assertThat(audit.getValue().getTargetId()).isEqualTo(10L),
            () -> assertThat(audit.getValue().getReason()).isEqualTo("Copyright claim")
        );
    }

    @Test
    void moderate_artwork_not_found_ko() {
        // given
        when(repository.findById(10L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.changeStatusAsAdmin(7L, 10L,
            new StatusChange(ArtworkStatus.MODERATED, "Copyright claim")))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_NOT_FOUND.getDetail());
        verifyNoInteractions(auditService);
    }

    @Test
    void moderate_artwork_without_reason_ko() {
        // given
        var change = new StatusChange(ArtworkStatus.MODERATED, "  ");

        // when / then
        assertThatThrownBy(() -> service.changeStatusAsAdmin(7L, 10L, change))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.MODERATION_REASON_REQUIRED.getDetail());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void admin_status_change_writes_audit_ok() {
        // given
        when(repository.findById(10L)).thenReturn(Optional.of(artwork(3L, "PUBLISHED", firstPublished)));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // when
        service.changeStatusAsAdmin(7L, 10L, new StatusChange(ArtworkStatus.UNLISTED, null));
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(audit.capture());

        // then
        assertAll(
            () -> assertThat(audit.getValue().getStaffId()).isEqualTo(7L),
            () -> assertThat(audit.getValue().getActionType()).isEqualTo("ARTWORK_STATUS_CHANGED"),
            () -> assertThat(audit.getValue().getTargetId()).isEqualTo(10L),
            () -> assertThat(audit.getValue().getDetails()).isEqualTo("from=PUBLISHED;to=UNLISTED")
        );
    }

    @Test
    void get_published_artwork_without_listings_ok() {
        // given
        when(repository.findByArtworkIdAndStatus(10L, "PUBLISHED"))
            .thenReturn(Optional.of(artwork(3L, "PUBLISHED", firstPublished)));

        // when
        var result = service.getPublished(10L);

        // then
        assertAll(
            () -> assertThat(result.getArtworkId()).isEqualTo(10L),
            () -> assertThat(result.getStatus()).isEqualTo(ArtworkStatus.PUBLISHED),
            () -> assertThat(result.isPurchasable()).isFalse()
        );
    }

    @Test
    void artwork_purchasable_with_one_purchasable_listing_ok() {
        // given
        when(repository.findByArtworkIdAndStatus(10L, "PUBLISHED"))
            .thenReturn(Optional.of(artwork(3L, "PUBLISHED", firstPublished)));
        when(listingAvailability.findArtworksWithOfferedListings(List.of(10L))).thenReturn(Set.of(10L));
        when(artistService.get(3L)).thenReturn(artist("ACTIVE"));

        // when
        var result = service.getPublished(10L);

        // then
        assertThat(result.isPurchasable()).isTrue();
    }

    @Test
    void published_artwork_without_listings_not_purchasable_ok() {
        // given
        when(repository.findByArtworkIdAndStatus(10L, "PUBLISHED"))
            .thenReturn(Optional.of(artwork(3L, "PUBLISHED", firstPublished)));
        when(listingAvailability.findArtworksWithOfferedListings(List.of(10L))).thenReturn(Set.of());

        // when
        var result = service.getPublished(10L);

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(ArtworkStatus.PUBLISHED),
            () -> assertThat(result.isPurchasable()).isFalse()
        );
        verifyNoInteractions(artistService);
    }

    @Test
    void get_draft_artwork_publicly_ko() {
        // given
        when(repository.findByArtworkIdAndStatus(10L, "PUBLISHED")).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.getPublished(10L))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_NOT_FOUND.getDetail());
    }

    @Test
    void get_preview_content_of_unpublished_artwork_ko() {
        // given
        when(repository.findByArtworkIdAndStatus(10L, "PUBLISHED")).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.getPublishedPreview(10L, 5L))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_NOT_FOUND.getDetail());
        verifyNoInteractions(fileService);
    }

    @Test
    void get_artwork_hides_inactive_tags_ok() {
        // given
        when(repository.findByArtworkIdAndStatus(10L, "PUBLISHED"))
            .thenReturn(Optional.of(artwork(3L, "PUBLISHED", firstPublished)));
        when(tagLinkRepository.findByIdArtworkIdIn(List.of(10L))).thenReturn(List.of(link(1), link(2)));
        // Tag 2 was deactivated after selection, so the active-tag query no longer returns it.
        when(tagRepository.findByTagIdInAndIsActiveTrueOrderByNameAsc(anyCollection()))
            .thenReturn(List.of(tag(1, "Tag 1", true)));

        // when
        var result = service.getPublished(10L);

        // then
        assertThat(result.getTags()).extracting("tagId").containsExactly(1);
    }

    private void ownedDraft() {
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(10L)).thenReturn(Optional.of(artwork(3L, "DRAFT", null)));
    }

    private Artist artist(String status) {
        return Artist.builder().withArtistId(3L).withUserId(42L).withStatus(status).build();
    }

    private Artwork artwork(long artistId, String status, OffsetDateTime publishedAt) {
        var artwork = new Artwork(artistId, "Sunset", "Corrected description", "2D", status, publishedAt,
            firstPublished, version);
        artwork.setArtworkId(10L);

        return artwork;
    }

    private ArtworkDetails details(String title, String dimension, List<Integer> tagIds, byte[] rowVersion) {
        return new ArtworkDetails(title, "Corrected description", dimension, tagIds, rowVersion);
    }

    private ArtworkTag link(int tagId) {
        return new ArtworkTag(new ArtworkTagId(10L, tagId));
    }

    private Tag tag(int tagId, String name, boolean active) {
        var tag = new Tag(name, active);
        tag.setTagId(tagId);

        return tag;
    }

    private List<Tag> activeTags(Collection<Integer> tagIds) {
        return tagIds.stream().sorted().map(tagId -> tag(tagId, "Tag " + tagId, true)).toList();
    }
}
