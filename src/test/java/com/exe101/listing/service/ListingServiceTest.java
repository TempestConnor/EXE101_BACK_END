package com.exe101.listing.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.artwork.model.Artwork;
import com.exe101.artwork.model.ArtworkStatus;
import com.exe101.artwork.service.ArtworkService;
import com.exe101.audit.model.AuditEntry;
import com.exe101.audit.service.AuditService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.Listing;
import com.exe101.entity.ListingAsset;
import com.exe101.file.model.FileContent;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;
import com.exe101.file.service.FileService;
import com.exe101.listing.config.ManufacturingProperties;
import com.exe101.listing.mapper.ListingEntityMapper;
import com.exe101.listing.model.AssetUpload;
import com.exe101.listing.model.CostReviewConfirmation;
import com.exe101.listing.model.ListingDetails;
import com.exe101.listing.model.ListingStatus;
import com.exe101.listing.model.ListingStatusChange;
import com.exe101.listing.model.ListingType;
import com.exe101.listing.repository.ListingAssetRepository;
import com.exe101.listing.repository.ListingRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListingServiceTest {
    private static byte[] version = {0, 0, 0, 0, 0, 0, 0, 1};
    private static byte[] newerVersion = {0, 0, 0, 0, 0, 0, 0, 2};
    private static byte[] fileBytes = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};
    private static String a4Specifications = "Fine-art print, A4 (210 x 297 mm), 230 gsm matte paper";
    private static String a0Specifications = "Fine-art print, A0 (841 x 1189 mm), 230 gsm matte paper";
    private static OffsetDateTime firstPublished = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    @Mock
    private ListingRepository repository;
    @Mock
    private ListingAssetRepository assetRepository;
    @Mock
    private ArtworkService artworkService;
    @Mock
    private ArtistService artistService;
    @Mock
    private FileService fileService;
    @Mock
    private AuditService auditService;
    private ListingServiceImpl service;

    @BeforeEach
    void setUp() {
        var manufacturing = new ManufacturingProperties(List.of(
            new ManufacturingProperties.Product("PRINT_A4", "Fine-art print A4", a4Specifications,
                new BigDecimal("80000")),
            new ManufacturingProperties.Product("PRINT_A0", "Fine-art print A0", a0Specifications,
                new BigDecimal("500000"))));
        service = new ListingServiceImpl(repository, assetRepository, Mappers.getMapper(ListingEntityMapper.class),
            artworkService, artistService, fileService, auditService, manufacturing);
    }

    @Test
    void create_digital_listing_draft_ok() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(artworkService.get(10L)).thenReturn(artwork(3L, ArtworkStatus.PUBLISHED));
        savesListings();

        // when
        var result = service.create(42L, digitalDetails(new BigDecimal("50000"), null));
        var saved = ArgumentCaptor.forClass(Listing.class);
        verify(repository).saveAndFlush(saved.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(ListingStatus.DRAFT),
            () -> assertThat(result.isPurchasable()).isFalse(),
            () -> assertThat(saved.getValue().getArtworkId()).isEqualTo(10L),
            () -> assertThat(saved.getValue().getListingType()).isEqualTo("DIGITAL"),
            () -> assertThat(saved.getValue().getStatus()).isEqualTo("DRAFT"),
            () -> assertThat(saved.getValue().getCurrencyCode()).isEqualTo("VND"),
            () -> assertThat(saved.getValue().getPrice()).isEqualByComparingTo("50000"),
            () -> assertThat(saved.getValue().getManufacturingCost()).isNull(),
            () -> assertThat(saved.getValue().getArtistMarkup()).isNull(),
            () -> assertThat(saved.getValue().getSpecifications()).isNull(),
            () -> assertThat(saved.getValue().isCostReviewRequired()).isFalse(),
            () -> assertThat(saved.getValue().getPublishedAt()).isNull()
        );
    }

    @Test
    void create_physical_listing_draft_ok() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(artworkService.get(10L)).thenReturn(artwork(3L, ArtworkStatus.PUBLISHED));
        savesListings();

        // when
        var result = service.create(42L, physicalDetails("PRINT_A4", new BigDecimal("30000"), null));
        var saved = ArgumentCaptor.forClass(Listing.class);
        verify(repository).saveAndFlush(saved.capture());

        // then
        assertAll(
            () -> assertThat(result.getListingType()).isEqualTo(ListingType.PHYSICAL),
            () -> assertThat(saved.getValue().getManufacturingCost()).isEqualByComparingTo("80000"),
            () -> assertThat(saved.getValue().getArtistMarkup()).isEqualByComparingTo("30000"),
            () -> assertThat(saved.getValue().getPrice()).isEqualByComparingTo("110000"),
            () -> assertThat(saved.getValue().getSpecifications()).isEqualTo(a4Specifications),
            () -> assertThat(saved.getValue().getStatus()).isEqualTo("DRAFT")
        );
    }

    @Test
    void create_listing_artwork_not_found_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(artworkService.get(10L)).thenThrow(new AppException(AppErrorMessage.ARTWORK_NOT_FOUND));

        // when / then
        assertThatThrownBy(() -> service.create(42L, digitalDetails(new BigDecimal("50000"), null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_NOT_FOUND.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_listing_on_other_artists_artwork_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(artworkService.get(10L)).thenReturn(artwork(99L, ArtworkStatus.PUBLISHED));

        // when / then
        assertThatThrownBy(() -> service.create(42L, digitalDetails(new BigDecimal("50000"), null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ACCESS_DENIED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_listing_without_artist_profile_ko() {
        // given
        when(artistService.getByUserId(42L)).thenThrow(new AppException(AppErrorMessage.ARTIST_PROFILE_REQUIRED));

        // when / then
        assertThatThrownBy(() -> service.create(42L, digitalDetails(new BigDecimal("50000"), null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_PROFILE_REQUIRED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_listing_artist_not_active_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("SUSPENDED"));

        // when / then
        assertThatThrownBy(() -> service.create(42L, digitalDetails(new BigDecimal("50000"), null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_NOT_ACTIVE.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_listing_negative_price_ko() {
        // given
        ownedArtwork(ArtworkStatus.PUBLISHED);

        // when / then
        assertThatThrownBy(() -> service.create(42L, digitalDetails(new BigDecimal("-1"), null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_INVALID.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_digital_listing_zero_price_ko() {
        // given
        ownedArtwork(ArtworkStatus.PUBLISHED);

        // when / then
        assertThatThrownBy(() -> service.create(42L, digitalDetails(BigDecimal.ZERO, null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_INVALID.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_listing_fractional_vnd_ko() {
        // given
        ownedArtwork(ArtworkStatus.PUBLISHED);

        // when / then
        assertThatThrownBy(() -> service.create(42L, digitalDetails(new BigDecimal("50000.5"), null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_INVALID.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_physical_listing_without_specifications_ko() {
        // given
        ownedArtwork(ArtworkStatus.PUBLISHED);

        // when / then
        assertThatThrownBy(() -> service.create(42L, physicalDetails(null, new BigDecimal("30000"), null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_INVALID.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_physical_listing_negative_cost_or_markup_ko() {
        // given
        ownedArtwork(ArtworkStatus.PUBLISHED);

        // when / then
        assertThatThrownBy(() -> service.create(42L, physicalDetails("PRINT_A4", new BigDecimal("-1"), null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_INVALID.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_physical_listing_unknown_product_ko() {
        // given
        ownedArtwork(ArtworkStatus.PUBLISHED);

        // when / then
        assertThatThrownBy(() -> service.create(42L, physicalDetails("PRINT_B5", new BigDecimal("30000"), null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_PRODUCT_UNKNOWN.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_listing_on_moderated_or_archived_artwork_ko() {
        // given
        ownedArtwork(ArtworkStatus.MODERATED);

        // when / then
        assertThatThrownBy(() -> service.create(42L, digitalDetails(new BigDecimal("50000"), null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_NOT_EDITABLE.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void create_listing_draft_on_draft_artwork_ok() {
        // given
        ownedArtwork(ArtworkStatus.DRAFT);
        savesListings();

        // when
        var result = service.create(42L, digitalDetails(new BigDecimal("50000"), null));

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(ListingStatus.DRAFT),
            () -> assertThat(result.getArtworkId()).isEqualTo(10L)
        );
    }

    @Test
    void add_listing_to_published_artwork_keeps_existing_listings_ok() {
        // given
        var existing = physical(21L, "PUBLISHED", firstPublished);
        ownedArtwork(ArtworkStatus.PUBLISHED);
        savesListings();
        when(assetRepository.findByListingIdInOrderBySortOrderAsc(List.of(20L))).thenReturn(List.of(asset()));
        when(fileService.hasContent(100L)).thenReturn(true);

        // when
        var created = service.create(42L, physicalDetails("PRINT_A4", new BigDecimal("30000"), null));
        var saved = ArgumentCaptor.forClass(Listing.class);
        verify(repository).saveAndFlush(saved.capture());
        when(repository.findById(20L)).thenReturn(Optional.of(saved.getValue()));
        var published = service.changeStatus(42L, created.getListingId(),
            new ListingStatusChange(ListingStatus.PUBLISHED, null));

        // then
        assertAll(
            () -> assertThat(published.getStatus()).isEqualTo(ListingStatus.PUBLISHED),
            () -> assertThat(published.getListingId()).isEqualTo(20L),
            () -> assertThat(existing.getStatus()).isEqualTo("PUBLISHED"),
            () -> assertThat(existing.getPrice()).isEqualByComparingTo("110000"),
            () -> assertThat(existing.getSpecifications()).isEqualTo(a4Specifications),
            () -> verify(repository, never()).saveAndFlush(existing),
            () -> verify(repository, never()).delete(any())
        );
    }

    @Test
    void publish_digital_listing_ok() {
        // given
        ownedListing(digital("DRAFT", null), ArtworkStatus.PUBLISHED, "ACTIVE");
        when(assetRepository.findByListingIdInOrderBySortOrderAsc(List.of(20L))).thenReturn(List.of(asset()));
        when(fileService.hasContent(100L)).thenReturn(true);
        savesListings();

        // when
        var result = service.changeStatus(42L, 20L, new ListingStatusChange(ListingStatus.PUBLISHED, null));

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(ListingStatus.PUBLISHED),
            () -> assertThat(result.getPublishedAt()).isNotNull(),
            () -> assertThat(result.isPurchasable()).isTrue(),
            () -> assertThat(result.getAssets()).extracting("assetId").containsExactly(30L)
        );
    }

    @Test
    void publish_digital_listing_without_assets_ko() {
        // given
        ownedListing(digital("DRAFT", null), ArtworkStatus.PUBLISHED, "ACTIVE");

        // when / then
        assertThatThrownBy(() -> service.changeStatus(42L, 20L,
            new ListingStatusChange(ListingStatus.PUBLISHED, null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_ASSET_REQUIRED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void publish_physical_listing_without_included_file_ko() {
        // given
        ownedListing(physical(20L, "DRAFT", null), ArtworkStatus.PUBLISHED, "ACTIVE");

        // when / then
        assertThatThrownBy(() -> service.changeStatus(42L, 20L,
            new ListingStatusChange(ListingStatus.PUBLISHED, null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_ASSET_REQUIRED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void publish_listing_file_bytes_missing_ko() {
        // given
        ownedListing(digital("DRAFT", null), ArtworkStatus.PUBLISHED, "ACTIVE");
        when(assetRepository.findByListingIdInOrderBySortOrderAsc(List.of(20L))).thenReturn(List.of(asset()));
        when(fileService.hasContent(100L)).thenReturn(false);

        // when / then
        assertThatThrownBy(() -> service.changeStatus(42L, 20L,
            new ListingStatusChange(ListingStatus.PUBLISHED, null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_ASSET_MISSING.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void publish_listing_artist_not_active_ko() {
        // given
        var draft = digital("DRAFT", null);
        ownedListing(draft, ArtworkStatus.PUBLISHED, "SUSPENDED");
        when(artistService.get(3L)).thenReturn(artist("SUSPENDED"));
        var publish = new ListingStatusChange(ListingStatus.PUBLISHED, null);

        // when / then
        assertAll(
            () -> assertThatThrownBy(() -> service.changeStatus(42L, 20L, publish))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_NOT_ACTIVE.getDetail()),
            () -> assertThatThrownBy(() -> service.changeStatusAsAdmin(7L, 20L, publish))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_NOT_ACTIVE.getDetail()),
            () -> verify(repository, never()).saveAndFlush(any()),
            () -> verifyNoInteractions(auditService)
        );
    }

    @Test
    void publish_listing_artwork_not_published_ko() {
        // given
        ownedListing(digital("DRAFT", null), ArtworkStatus.UNLISTED, "ACTIVE");

        // when / then
        assertThatThrownBy(() -> service.changeStatus(42L, 20L,
            new ListingStatusChange(ListingStatus.PUBLISHED, null)))
            .isInstanceOf(AppException.class)
            .hasMessage(AppErrorMessage.LISTING_ARTWORK_NOT_PUBLISHED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void republish_listing_keeps_first_published_at_ok() {
        // given
        ownedListing(digital("UNLISTED", firstPublished), ArtworkStatus.PUBLISHED, "ACTIVE");
        when(assetRepository.findByListingIdInOrderBySortOrderAsc(List.of(20L))).thenReturn(List.of(asset()));
        when(fileService.hasContent(100L)).thenReturn(true);
        savesListings();

        // when
        var result = service.changeStatus(42L, 20L, new ListingStatusChange(ListingStatus.PUBLISHED, null));

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(ListingStatus.PUBLISHED),
            () -> assertThat(result.getPublishedAt()).isEqualTo(firstPublished)
        );
    }

    @Test
    void unlist_listing_ok() {
        // given
        ownedListing(digital("PUBLISHED", firstPublished), ArtworkStatus.PUBLISHED, "ACTIVE");
        savesListings();

        // when
        var result = service.changeStatus(42L, 20L, new ListingStatusChange(ListingStatus.UNLISTED, null));

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(ListingStatus.UNLISTED),
            () -> assertThat(result.getPublishedAt()).isEqualTo(firstPublished),
            () -> assertThat(result.isPurchasable()).isFalse()
        );
    }

    @Test
    void inactive_artist_can_unlist_listing_ok() {
        // given
        ownedListing(digital("PUBLISHED", firstPublished), ArtworkStatus.PUBLISHED, "SUSPENDED");
        savesListings();

        // when
        var result = service.changeStatus(42L, 20L, new ListingStatusChange(ListingStatus.UNLISTED, null));

        // then
        assertThat(result.getStatus()).isEqualTo(ListingStatus.UNLISTED);
    }

    @Test
    void change_listing_status_not_allowed_ko() {
        // given
        ownedListing(digital("DRAFT", null), ArtworkStatus.PUBLISHED, "ACTIVE");

        // when / then
        assertThatThrownBy(() -> service.changeStatus(42L, 20L,
            new ListingStatusChange(ListingStatus.UNLISTED, null)))
            .isInstanceOf(AppException.class)
            .hasMessage(AppErrorMessage.LISTING_STATUS_CHANGE_NOT_ALLOWED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void admin_moderate_listing_writes_audit_ok() {
        // given
        when(repository.findById(20L)).thenReturn(Optional.of(digital("PUBLISHED", firstPublished)));
        when(artworkService.get(10L)).thenReturn(artwork(3L, ArtworkStatus.PUBLISHED));
        when(artistService.get(3L)).thenReturn(artist("ACTIVE"));
        savesListings();

        // when
        var result = service.changeStatusAsAdmin(7L, 20L,
            new ListingStatusChange(ListingStatus.MODERATED, "Copyright claim"));
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(audit.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(ListingStatus.MODERATED),
            () -> assertThat(result.isPurchasable()).isFalse(),
            () -> assertThat(audit.getValue().getStaffId()).isEqualTo(7L),
            () -> assertThat(audit.getValue().getActionType()).isEqualTo("LISTING_MODERATED"),
            () -> assertThat(audit.getValue().getTargetType()).isEqualTo("Listing"),
            () -> assertThat(audit.getValue().getTargetId()).isEqualTo(20L),
            () -> assertThat(audit.getValue().getReason()).isEqualTo("Copyright claim"),
            () -> assertThat(audit.getValue().getDetails()).isEqualTo("from=PUBLISHED;to=MODERATED")
        );
    }

    @Test
    void admin_moderate_listing_without_reason_ko() {
        // given
        var moderation = new ListingStatusChange(ListingStatus.MODERATED, "  ");

        // when / then
        assertThatThrownBy(() -> service.changeStatusAsAdmin(7L, 20L, moderation))
            .isInstanceOf(AppException.class)
            .hasMessage(AppErrorMessage.MODERATION_REASON_REQUIRED.getDetail());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void update_listing_ok() {
        // given
        ownedListing(digital("DRAFT", null), ArtworkStatus.PUBLISHED, "ACTIVE");
        savesListings();

        // when
        service.update(42L, 20L, digitalDetails(new BigDecimal("60000"), version));
        var saved = ArgumentCaptor.forClass(Listing.class);
        verify(repository).saveAndFlush(saved.capture());

        // then
        assertAll(
            () -> assertThat(saved.getValue().getName()).isEqualTo("Sunset (4K)"),
            () -> assertThat(saved.getValue().getDescription()).isEqualTo("Full resolution"),
            () -> assertThat(saved.getValue().getPrice()).isEqualByComparingTo("60000"),
            () -> assertThat(saved.getValue().getListingType()).isEqualTo("DIGITAL"),
            () -> assertThat(saved.getValue().getStatus()).isEqualTo("DRAFT"),
            () -> assertThat(saved.getValue().getArtworkId()).isEqualTo(10L),
            () -> assertThat(saved.getValue().getCurrencyCode()).isEqualTo("VND"),
            () -> assertThat(saved.getValue().getPublishedAt()).isNull()
        );
    }

    @Test
    void update_published_listing_name_and_price_ok() {
        // given
        ownedListing(digital("PUBLISHED", firstPublished), ArtworkStatus.PUBLISHED, "ACTIVE");
        savesListings();

        // when
        var result = service.update(42L, 20L, digitalDetails(new BigDecimal("60000"), version));

        // then
        assertAll(
            () -> assertThat(result.getName()).isEqualTo("Sunset (4K)"),
            () -> assertThat(result.getPrice()).isEqualByComparingTo("60000"),
            () -> assertThat(result.getStatus()).isEqualTo(ListingStatus.PUBLISHED),
            () -> assertThat(result.getPublishedAt()).isEqualTo(firstPublished)
        );
    }

    @Test
    void update_published_listing_specifications_locked_ko() {
        // given
        ownedListing(physical(20L, "PUBLISHED", firstPublished), ArtworkStatus.PUBLISHED, "ACTIVE");

        // when / then
        assertThatThrownBy(() -> service.update(42L, 20L,
            physicalDetails("PRINT_A0", new BigDecimal("30000"), version)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_PRODUCT_LOCKED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void update_physical_markup_recalculates_retail_ok() {
        // given
        ownedListing(physical(20L, "PUBLISHED", firstPublished), ArtworkStatus.PUBLISHED, "ACTIVE");
        savesListings();

        // when
        var result = service.update(42L, 20L, physicalDetails(null, new BigDecimal("50000"), version));

        // then
        assertAll(
            () -> assertThat(result.getManufacturingCost()).isEqualByComparingTo("80000"),
            () -> assertThat(result.getArtistMarkup()).isEqualByComparingTo("50000"),
            () -> assertThat(result.getPrice()).isEqualByComparingTo("130000"),
            () -> assertThat(result.getSpecifications()).isEqualTo(a4Specifications)
        );
    }

    @Test
    void update_listing_stale_version_ko() {
        // given
        ownedListing(digital("DRAFT", null), ArtworkStatus.PUBLISHED, "ACTIVE");

        // when / then
        assertThatThrownBy(() -> service.update(42L, 20L, digitalDetails(new BigDecimal("60000"), newerVersion)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_VERSION_CONFLICT.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void update_listing_not_found_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(20L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.update(42L, 20L, digitalDetails(new BigDecimal("60000"), version)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_NOT_FOUND.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void get_listing_of_other_artist_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(20L)).thenReturn(Optional.of(digital("PUBLISHED", firstPublished)));
        when(artworkService.get(10L)).thenReturn(artwork(99L, ArtworkStatus.PUBLISHED));

        // when / then
        assertThatThrownBy(() -> service.getOwn(42L, 20L))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ACCESS_DENIED.getDetail());
    }

    @Test
    void add_asset_to_draft_digital_listing_ok() {
        // given
        ownedListing(digital("DRAFT", null), ArtworkStatus.PUBLISHED, "ACTIVE");
        when(assetRepository.countByListingId(20L)).thenReturn(0L);
        when(assetRepository.findTopByListingIdOrderBySortOrderDesc(20L)).thenReturn(Optional.empty());
        when(fileService.storeProtected(any())).thenReturn(StoredFileInfo.builder()
            .withFileId(100L)
            .withAccessClass("PURCHASED")
            .build());
        when(assetRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ListingAsset entity = invocation.getArgument(0);
            entity.setListingAssetId(30L);
            return entity;
        });

        // when
        service.addAsset(42L, 20L, new AssetUpload("sunset.png", fileBytes));
        var file = ArgumentCaptor.forClass(NewFile.class);
        verify(fileService).storeProtected(file.capture());
        var saved = ArgumentCaptor.forClass(ListingAsset.class);
        verify(assetRepository).saveAndFlush(saved.capture());

        // then
        assertAll(
            () -> assertThat(file.getValue().getContentType()).isEqualTo("image/png"),
            () -> assertThat(file.getValue().getOriginalName()).isEqualTo("sunset.png"),
            () -> assertThat(saved.getValue().getListingId()).isEqualTo(20L),
            () -> assertThat(saved.getValue().getFileId()).isEqualTo(100L),
            () -> assertThat(saved.getValue().getSortOrder()).isZero(),
            () -> verify(fileService, never()).storePublic(any())
        );
    }

    @Test
    void add_asset_to_published_listing_ko() {
        // given
        ownedListing(digital("PUBLISHED", firstPublished), ArtworkStatus.PUBLISHED, "ACTIVE");

        // when / then
        assertAll(
            () -> assertThatThrownBy(() -> service.addAsset(42L, 20L, new AssetUpload("extra.png", fileBytes)))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_ASSETS_LOCKED.getDetail()),
            () -> assertThatThrownBy(() -> service.removeAsset(42L, 20L, 30L))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_ASSETS_LOCKED.getDetail()),
            () -> verify(assetRepository, never()).saveAndFlush(any()),
            () -> verify(assetRepository, never()).delete(any()),
            () -> verifyNoInteractions(fileService)
        );
    }

    @Test
    void add_asset_rejected_type_or_size_ko() {
        // given
        ownedListing(digital("DRAFT", null), ArtworkStatus.PUBLISHED, "ACTIVE");

        // when / then
        assertThatThrownBy(() -> service.addAsset(42L, 20L, new AssetUpload("installer.exe", fileBytes)))
            .isInstanceOf(AppException.class)
            .hasMessage(AppErrorMessage.LISTING_ASSET_TYPE_NOT_SUPPORTED.getDetail());
        verify(assetRepository, never()).saveAndFlush(any());
        verifyNoInteractions(fileService);
    }

    @Test
    void add_asset_limit_reached_ko() {
        // given
        ownedListing(digital("DRAFT", null), ArtworkStatus.PUBLISHED, "ACTIVE");
        when(assetRepository.countByListingId(20L)).thenReturn(10L);

        // when / then
        assertThatThrownBy(() -> service.addAsset(42L, 20L, new AssetUpload("sunset.png", fileBytes)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_ASSET_LIMIT_REACHED.getDetail());
        verify(assetRepository, never()).saveAndFlush(any());
        verifyNoInteractions(fileService);
    }

    @Test
    void remove_asset_from_draft_listing_ok() {
        // given
        var asset = asset();
        ownedListing(digital("DRAFT", null), ArtworkStatus.PUBLISHED, "ACTIVE");
        when(assetRepository.findByListingAssetIdAndListingId(30L, 20L)).thenReturn(Optional.of(asset));

        // when
        service.removeAsset(42L, 20L, 30L);

        // then
        verify(assetRepository).delete(asset);
        verifyNoInteractions(fileService);
    }

    @Test
    void owner_downloads_own_asset_ok() throws Exception {
        // given
        var content = FileContent.builder().withFileId(100L).withContentType("image/png")
            .withByteLength(fileBytes.length).withContent(new ByteArrayResource(fileBytes)).build();
        ownedListing(digital("PUBLISHED", firstPublished), ArtworkStatus.PUBLISHED, "ACTIVE");
        when(assetRepository.findByListingAssetIdAndListingId(30L, 20L)).thenReturn(Optional.of(asset()));
        when(fileService.read(100L)).thenReturn(content);

        // when
        var result = service.getOwnAsset(42L, 20L, 30L);

        // then
        assertThat(result.getContent().getContentAsByteArray()).isEqualTo(fileBytes);
    }

    @Test
    void other_customer_downloads_asset_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(20L)).thenReturn(Optional.of(digital("PUBLISHED", firstPublished)));
        when(artworkService.get(10L)).thenReturn(artwork(99L, ArtworkStatus.PUBLISHED));

        // when / then
        assertThatThrownBy(() -> service.getOwnAsset(42L, 20L, 30L))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ACCESS_DENIED.getDetail());
        verifyNoInteractions(fileService);
    }

    @Test
    void listing_purchasable_when_all_checks_pass_ok() {
        // given
        ownedListing(digital("PUBLISHED", firstPublished), ArtworkStatus.PUBLISHED, "ACTIVE");

        // when
        var result = service.getOwn(42L, 20L);

        // then
        assertThat(result.isPurchasable()).isTrue();
    }

    @Test
    void listing_not_purchasable_when_artist_not_active_ko() {
        // given
        ownedListing(digital("PUBLISHED", firstPublished), ArtworkStatus.PUBLISHED, "SUSPENDED");

        // when
        var result = service.getOwn(42L, 20L);

        // then
        assertThat(result.isPurchasable()).isFalse();
    }

    @Test
    void listing_not_purchasable_when_cost_review_required_ko() {
        // given
        var listing = physical(20L, "PUBLISHED", firstPublished);
        listing.setCostReviewRequired(true);
        ownedListing(listing, ArtworkStatus.PUBLISHED, "ACTIVE");

        // when
        var result = service.getOwn(42L, 20L);

        // then
        assertThat(result.isPurchasable()).isFalse();
    }

    @Test
    void listing_not_purchasable_when_artwork_moderated_ko() {
        // given
        ownedListing(digital("PUBLISHED", firstPublished), ArtworkStatus.MODERATED, "ACTIVE");

        // when
        var result = service.getOwn(42L, 20L);

        // then
        assertThat(result.isPurchasable()).isFalse();
    }

    @Test
    void listing_not_purchasable_when_unlisted_ko() {
        // given
        ownedListing(digital("UNLISTED", firstPublished), ArtworkStatus.PUBLISHED, "ACTIVE");

        // when
        var result = service.getOwn(42L, 20L);

        // then
        assertThat(result.isPurchasable()).isFalse();
    }

    @Test
    void catalog_shows_only_visible_listings_ok() {
        // given
        when(artworkService.getPublished(10L)).thenReturn(artwork(3L, ArtworkStatus.PUBLISHED));
        when(artistService.get(3L)).thenReturn(artist("ACTIVE"));
        when(repository.findByArtworkIdAndStatusOrderByCreatedAtAsc(10L, "PUBLISHED"))
            .thenReturn(List.of(digital("PUBLISHED", firstPublished)));

        // when
        var result = service.listPublished(10L);

        // then
        assertAll(
            () -> assertThat(result).extracting("listingId").containsExactly(20L),
            () -> assertThat(result).extracting("purchasable").containsExactly(true),
            () -> verify(repository, never()).findByArtworkIdOrderByCreatedAtAsc(anyLong())
        );
    }

    @Test
    void catalog_hides_listings_of_unlisted_artwork_ok() {
        // given
        when(artworkService.getPublished(10L)).thenThrow(new AppException(AppErrorMessage.ARTWORK_NOT_FOUND));

        // when / then
        assertThatThrownBy(() -> service.listPublished(10L))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTWORK_NOT_FOUND.getDetail());
        verifyNoInteractions(repository);
    }

    @Test
    void catalog_shows_inactive_artists_listing_as_not_purchasable_ok() {
        // given
        when(artworkService.getPublished(10L)).thenReturn(artwork(3L, ArtworkStatus.PUBLISHED));
        when(artistService.get(3L)).thenReturn(artist("SUSPENDED"));
        when(repository.findByArtworkIdAndStatusOrderByCreatedAtAsc(10L, "PUBLISHED"))
            .thenReturn(List.of(digital("PUBLISHED", firstPublished)));

        // when
        var result = service.listPublished(10L);

        // then
        assertAll(
            () -> assertThat(result).extracting("listingId").containsExactly(20L),
            () -> assertThat(result).extracting("purchasable").containsExactly(false)
        );
    }

    @Test
    void cost_change_flags_published_physical_listing_ok() {
        // given
        var statuses = List.of("DRAFT", "PUBLISHED", "UNLISTED");
        when(repository.flagCostChanges(a4Specifications, new BigDecimal("80000.0000"), statuses)).thenReturn(2);
        when(repository.flagCostChanges(a0Specifications, new BigDecimal("500000.0000"), statuses)).thenReturn(1);

        // when
        var result = service.flagCostChanges();

        // then
        assertAll(
            () -> assertThat(result).isEqualTo(3),
            () -> verify(repository).flagCostChanges(a4Specifications, new BigDecimal("80000.0000"), statuses),
            () -> verify(repository).flagCostChanges(a0Specifications, new BigDecimal("500000.0000"), statuses),
            () -> verify(repository, never()).saveAndFlush(any())
        );
    }

    @Test
    void invalid_cost_rejected_before_any_flag_ko() {
        // given
        var negative = serviceWith(new ManufacturingProperties.Product("PRINT_A4", "Fine-art print A4",
            a4Specifications, new BigDecimal("-1")));
        var fractional = serviceWith(new ManufacturingProperties.Product("PRINT_A4", "Fine-art print A4",
            a4Specifications, new BigDecimal("80000.5")));

        // when / then
        assertAll(
            () -> assertThatThrownBy(negative::flagCostChanges).isInstanceOf(IllegalStateException.class),
            () -> assertThatThrownBy(fractional::flagCostChanges).isInstanceOf(IllegalStateException.class),
            () -> verify(repository, never()).flagCostChanges(anyString(), any(), anyCollection())
        );
    }

    @Test
    void confirm_review_with_same_markup_ok() {
        // given
        ownedListing(flagged("PUBLISHED"), ArtworkStatus.PUBLISHED, "ACTIVE");
        savesListings();

        // when
        var result = service.confirmCostReview(42L, 20L, confirmation(new BigDecimal("30000"), version));
        var saved = ArgumentCaptor.forClass(Listing.class);
        verify(repository).saveAndFlush(saved.capture());

        // then
        assertAll(
            () -> assertThat(saved.getValue().getManufacturingCost()).isEqualByComparingTo("80000"),
            () -> assertThat(saved.getValue().getArtistMarkup()).isEqualByComparingTo("30000"),
            () -> assertThat(saved.getValue().getPrice()).isEqualByComparingTo("110000"),
            () -> assertThat(saved.getValue().isCostReviewRequired()).isFalse(),
            () -> assertThat(saved.getValue().getSpecifications()).isEqualTo(a4Specifications),
            () -> assertThat(result.getCurrentManufacturingCost()).isNull()
        );
    }

    @Test
    void confirm_review_with_new_markup_ok() {
        // given
        ownedListing(flagged("PUBLISHED"), ArtworkStatus.PUBLISHED, "ACTIVE");
        savesListings();

        // when
        var result = service.confirmCostReview(42L, 20L, confirmation(new BigDecimal("10000"), version));

        // then
        assertAll(
            () -> assertThat(result.getManufacturingCost()).isEqualByComparingTo("80000"),
            () -> assertThat(result.getArtistMarkup()).isEqualByComparingTo("10000"),
            () -> assertThat(result.getPrice()).isEqualByComparingTo("90000"),
            () -> assertThat(result.isCostReviewRequired()).isFalse()
        );
    }

    @Test
    void confirm_review_with_zero_markup_ok() {
        // given
        ownedListing(flagged("PUBLISHED"), ArtworkStatus.PUBLISHED, "ACTIVE");
        savesListings();

        // when
        var result = service.confirmCostReview(42L, 20L, confirmation(BigDecimal.ZERO, version));

        // then
        assertAll(
            () -> assertThat(result.getArtistMarkup()).isEqualByComparingTo("0"),
            () -> assertThat(result.getPrice()).isEqualByComparingTo("80000"),
            () -> assertThat(result.isCostReviewRequired()).isFalse()
        );
    }

    @Test
    void confirm_review_listing_not_found_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(20L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.confirmCostReview(42L, 20L, confirmation(new BigDecimal("30000"), version)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_NOT_FOUND.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void confirm_review_not_owner_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(repository.findById(20L)).thenReturn(Optional.of(flagged("PUBLISHED")));
        when(artworkService.get(10L)).thenReturn(artwork(99L, ArtworkStatus.PUBLISHED));

        // when / then
        assertThatThrownBy(() -> service.confirmCostReview(42L, 20L, confirmation(new BigDecimal("30000"), version)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ACCESS_DENIED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void confirm_review_inactive_artist_ko() {
        // given
        when(artistService.getByUserId(42L)).thenReturn(artist("SUSPENDED"));

        // when / then
        assertThatThrownBy(() -> service.confirmCostReview(42L, 20L, confirmation(new BigDecimal("30000"), version)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_NOT_ACTIVE.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void confirm_review_listing_not_flagged_ko() {
        // given
        ownedListing(physical(20L, "PUBLISHED", firstPublished), ArtworkStatus.PUBLISHED, "ACTIVE");

        // when / then
        assertThatThrownBy(() -> service.confirmCostReview(42L, 20L, confirmation(new BigDecimal("30000"), version)))
            .isInstanceOf(AppException.class)
            .hasMessage(AppErrorMessage.LISTING_COST_REVIEW_NOT_REQUIRED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void confirm_review_stale_version_ko() {
        // given
        ownedListing(flagged("PUBLISHED"), ArtworkStatus.PUBLISHED, "ACTIVE");

        // when / then
        assertThatThrownBy(() -> service.confirmCostReview(42L, 20L,
            confirmation(new BigDecimal("30000"), newerVersion)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_VERSION_CONFLICT.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void confirm_review_invalid_markup_ko() {
        // given
        ownedListing(flagged("PUBLISHED"), ArtworkStatus.PUBLISHED, "ACTIVE");

        // when / then
        assertAll(
            () -> assertThatThrownBy(() -> service.confirmCostReview(42L, 20L,
                confirmation(new BigDecimal("-1"), version)))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_INVALID.getDetail()),
            () -> assertThatThrownBy(() -> service.confirmCostReview(42L, 20L,
                confirmation(new BigDecimal("30000.5"), version)))
                .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_INVALID.getDetail()),
            () -> verify(repository, never()).saveAndFlush(any())
        );
    }

    @Test
    void edit_markup_while_review_required_ko() {
        // given
        ownedListing(flagged("PUBLISHED"), ArtworkStatus.PUBLISHED, "ACTIVE");

        // when / then
        assertThatThrownBy(() -> service.update(42L, 20L, physicalDetails(null, new BigDecimal("50000"), version)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_COST_REVIEW_REQUIRED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void publish_while_review_required_ko() {
        // given
        ownedListing(flagged("DRAFT"), ArtworkStatus.PUBLISHED, "ACTIVE");

        // when / then
        assertThatThrownBy(() -> service.changeStatus(42L, 20L,
            new ListingStatusChange(ListingStatus.PUBLISHED, null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.LISTING_COST_REVIEW_REQUIRED.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void flagged_listing_not_purchasable_until_confirmed_ok() {
        // given
        ownedListing(flagged("PUBLISHED"), ArtworkStatus.PUBLISHED, "ACTIVE");
        savesListings();

        // when
        var before = service.getOwn(42L, 20L);
        var after = service.confirmCostReview(42L, 20L, confirmation(new BigDecimal("30000"), version));

        // then
        assertAll(
            () -> assertThat(before.isPurchasable()).isFalse(),
            () -> assertThat(after.isPurchasable()).isTrue()
        );
    }

    @Test
    void owner_sees_current_cost_on_flagged_listing_ok() {
        // given
        ownedListing(flagged("PUBLISHED"), ArtworkStatus.PUBLISHED, "ACTIVE");

        // when
        var result = service.getOwn(42L, 20L);

        // then
        assertAll(
            () -> assertThat(result.isCostReviewRequired()).isTrue(),
            () -> assertThat(result.getManufacturingCost()).isEqualByComparingTo("60000"),
            () -> assertThat(result.getPrice()).isEqualByComparingTo("90000"),
            () -> assertThat(result.getCurrentManufacturingCost()).isEqualByComparingTo("80000")
        );
    }

    private void ownedArtwork(ArtworkStatus status) {
        when(artistService.getByUserId(42L)).thenReturn(artist("ACTIVE"));
        when(artworkService.get(10L)).thenReturn(artwork(3L, status));
    }

    private void ownedListing(Listing listing, ArtworkStatus artworkStatus, String artistStatus) {
        when(artistService.getByUserId(42L)).thenReturn(artist(artistStatus));
        when(repository.findById(20L)).thenReturn(Optional.of(listing));
        when(artworkService.get(10L)).thenReturn(artwork(3L, artworkStatus));
    }

    private void savesListings() {
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            Listing entity = invocation.getArgument(0);

            if (entity.getListingId() == null) {
                entity.setListingId(20L);
            }

            return entity;
        });
    }

    private Artist artist(String status) {
        return Artist.builder().withArtistId(3L).withUserId(42L).withStatus(status).build();
    }

    private Artwork artwork(long artistId, ArtworkStatus status) {
        return Artwork.builder().withArtworkId(10L).withArtistId(artistId).withTitle("Sunset").withStatus(status)
            .build();
    }

    private Listing digital(String status, OffsetDateTime publishedAt) {
        var listing = new Listing(10L, "DIGITAL", "Sunset download", "High resolution", status, "VND",
            new BigDecimal("50000.0000"), null, null, null, false, firstPublished, publishedAt, version);
        listing.setListingId(20L);

        return listing;
    }

    private Listing physical(long listingId, String status, OffsetDateTime publishedAt) {
        var listing = new Listing(10L, "PHYSICAL", "Sunset A4 print", null, status, "VND",
            new BigDecimal("110000.0000"), new BigDecimal("80000.0000"), new BigDecimal("30000.0000"),
            a4Specifications, false, firstPublished, publishedAt, version);
        listing.setListingId(listingId);

        return listing;
    }

    private ListingAsset asset() {
        var asset = new ListingAsset(20L, 100L, 0, firstPublished);
        asset.setListingAssetId(30L);

        return asset;
    }

    private ListingDetails digitalDetails(BigDecimal price, byte[] rowVersion) {
        return new ListingDetails(10L, ListingType.DIGITAL, "Sunset (4K)", "Full resolution", price, null, null,
            rowVersion);
    }

    private ListingDetails physicalDetails(String productCode, BigDecimal markup, byte[] rowVersion) {
        return new ListingDetails(10L, ListingType.PHYSICAL, "Sunset A4 print", null, null, productCode, markup,
            rowVersion);
    }

    // Priced at an older A4 cost of 60000; the current A4 cost is 80000.
    private Listing flagged(String status) {
        var publishedAt = "DRAFT".equals(status) ? null : firstPublished;
        var listing = new Listing(10L, "PHYSICAL", "Sunset A4 print", null, status, "VND",
            new BigDecimal("90000.0000"), new BigDecimal("60000.0000"), new BigDecimal("30000.0000"),
            a4Specifications, true, firstPublished, publishedAt, version);
        listing.setListingId(20L);

        return listing;
    }

    private CostReviewConfirmation confirmation(BigDecimal markup, byte[] rowVersion) {
        return new CostReviewConfirmation(markup, rowVersion);
    }

    private ListingServiceImpl serviceWith(ManufacturingProperties.Product product) {
        return new ListingServiceImpl(repository, assetRepository, Mappers.getMapper(ListingEntityMapper.class),
            artworkService, artistService, fileService, auditService, new ManufacturingProperties(List.of(product)));
    }
}
