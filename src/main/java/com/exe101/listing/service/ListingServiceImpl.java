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
import com.exe101.common.persistence.DatabaseErrors;
import com.exe101.file.model.FileContent;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;
import com.exe101.file.service.FileService;
import com.exe101.listing.config.ManufacturingProperties;
import com.exe101.listing.mapper.ListingEntityMapper;
import com.exe101.listing.model.AssetUpload;
import com.exe101.listing.model.CostReviewConfirmation;
import com.exe101.listing.model.Listing;
import com.exe101.listing.model.ListingAsset;
import com.exe101.listing.model.ListingDetails;
import com.exe101.listing.model.ListingStatus;
import com.exe101.listing.model.ListingStatusChange;
import com.exe101.listing.model.ListingType;
import com.exe101.listing.model.ManufacturingProduct;
import com.exe101.listing.model.PurchaseItem;
import com.exe101.listing.model.PurchaseOffer;
import com.exe101.listing.repository.ListingAssetRepository;
import com.exe101.listing.repository.ListingRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ListingServiceImpl implements ListingService {
    private static String activeArtist = "ACTIVE";
    private static String currencyCode = "VND";
    private static String moderatedAction = "LISTING_MODERATED";
    private static String statusAction = "LISTING_STATUS_CHANGED";
    private static String listingTarget = "Listing";
    // Cost changes flag every physical Listing that can still be sold or published.
    private static List<String> reviewableStatuses = List.of(ListingStatus.DRAFT.name(),
        ListingStatus.PUBLISHED.name(), ListingStatus.UNLISTED.name());
    private static Set<ListingStatus> finalStatuses = EnumSet.of(ListingStatus.MODERATED, ListingStatus.ARCHIVED);
    private static Set<ArtworkStatus> finalArtworkStatuses = EnumSet.of(ArtworkStatus.MODERATED,
        ArtworkStatus.ARCHIVED);
    private static Map<ListingStatus, Set<ListingStatus>> transitions = Map.of(
        ListingStatus.DRAFT, EnumSet.of(ListingStatus.PUBLISHED, ListingStatus.ARCHIVED),
        ListingStatus.PUBLISHED, EnumSet.of(ListingStatus.UNLISTED, ListingStatus.ARCHIVED, ListingStatus.MODERATED),
        ListingStatus.UNLISTED, EnumSet.of(ListingStatus.PUBLISHED, ListingStatus.ARCHIVED, ListingStatus.MODERATED));
    private static int maxNameLength = 240;
    private static int maxAssets = 10;
    private static long maxAssetBytes = 200L * 1024 * 1024;
    // Money is whole VND, stored as decimal(19,4): at most 15 integer digits.
    private static int moneyScale = 4;
    private static BigDecimal moneyLimit = BigDecimal.TEN.pow(15);
    // THROW 51005 in TR_ListingAsset_Published: the Listing was published concurrently.
    private static int publishedAssetsError = 51005;
    private final ListingRepository repository;
    private final ListingAssetRepository assetRepository;
    private final ListingEntityMapper mapper;
    private final ArtworkService artworkService;
    private final ArtistService artistService;
    private final FileService fileService;
    private final AuditService auditService;
    private final ManufacturingProperties manufacturing;

    @Override
    public List<ManufacturingProduct> listProducts() {
        if (manufacturing.products() == null) {
            return List.of();
        }

        return manufacturing.products().stream()
            .map(product -> ManufacturingProduct.builder()
                .withCode(product.code())
                .withName(product.name())
                .withSpecifications(product.specifications())
                .withCost(product.cost())
                .build())
            .toList();
    }

    @Override
    public Listing create(Long userId, ListingDetails details) {
        Artist artist = requireActiveArtist(userId);

        if (details.getListingType() == null || details.getArtworkId() == null) {
            throw new AppException(AppErrorMessage.LISTING_INVALID);
        }

        Artwork artwork = requireOwnedArtwork(artist, details.getArtworkId());
        requireArtworkEditable(artwork);
        com.exe101.entity.Listing entity = new com.exe101.entity.Listing();
        entity.setArtworkId(artwork.getArtworkId());
        entity.setListingType(details.getListingType().name());
        entity.setName(requireName(details.getName()));
        entity.setDescription(blankToNull(details.getDescription()));
        entity.setStatus(ListingStatus.DRAFT.name());
        entity.setCurrencyCode(currencyCode);
        entity.setCostReviewRequired(false);
        entity.setCreatedAt(now());
        applyPricing(entity, details);
        com.exe101.entity.Listing saved = repository.saveAndFlush(entity);
        log.info("[LISTING] - ACTION: create: artworkId: {}: listingId: {}", artwork.getArtworkId(),
            saved.getListingId());

        return assemble(saved, artwork, artist);
    }

    @Override
    public Listing update(Long userId, Long listingId, ListingDetails details) {
        Artist artist = requireActiveArtist(userId);
        com.exe101.entity.Listing entity = requireListing(listingId);
        Artwork artwork = requireOwnedArtwork(artist, entity.getArtworkId());
        requireEditable(entity);
        requireArtworkEditable(artwork);

        // A normal edit would reprice from the old cost; only the review may change a flagged Listing.
        if (entity.isCostReviewRequired()) {
            throw new AppException(AppErrorMessage.LISTING_COST_REVIEW_REQUIRED);
        }

        requireVersion(entity, details.getVersion());
        entity.setName(requireName(details.getName()));
        entity.setDescription(blankToNull(details.getDescription()));
        applyPricing(entity, details);
        com.exe101.entity.Listing saved = save(entity);
        log.info("[LISTING] - ACTION: update: listingId: {}", listingId);

        return assemble(saved, artwork, artist);
    }

    @Override
    public Listing changeStatus(Long userId, Long listingId, ListingStatusChange change) {
        Artist artist = artistService.getByUserId(userId);
        com.exe101.entity.Listing entity = requireListing(listingId);
        Artwork artwork = requireOwnedArtwork(artist, entity.getArtworkId());
        requireEditable(entity);

        if (change.getStatus() == ListingStatus.MODERATED) {
            throw new AppException(AppErrorMessage.LISTING_STATUS_CHANGE_NOT_ALLOWED);
        }

        if (change.getStatus() == ListingStatus.PUBLISHED) {
            requireActive(artistService.lockForPublish(artist.getArtistId()));
        }

        applyTransition(entity, change.getStatus(), artwork);
        com.exe101.entity.Listing saved = save(entity);
        log.info("[LISTING] - ACTION: changeStatus: listingId: {}: status: {}", listingId, saved.getStatus());

        return assemble(saved, artwork, artist);
    }

    @Override
    public List<Listing> listOwn(Long userId, Long artworkId) {
        Artist artist = artistService.getByUserId(userId);
        Artwork artwork = requireOwnedArtwork(artist, artworkId);

        return assemble(repository.findByArtworkIdOrderByCreatedAtAsc(artworkId), artwork, artist);
    }

    @Override
    public Listing getOwn(Long userId, Long listingId) {
        Artist artist = artistService.getByUserId(userId);
        com.exe101.entity.Listing entity = requireListing(listingId);
        Artwork artwork = requireOwnedArtwork(artist, entity.getArtworkId());

        return assemble(entity, artwork, artist);
    }

    @Override
    public Listing addAsset(Long userId, Long listingId, AssetUpload upload) {
        Artist artist = requireActiveArtist(userId);
        com.exe101.entity.Listing entity = requireListing(listingId);
        Artwork artwork = requireOwnedArtwork(artist, entity.getArtworkId());
        requireAssetsOpen(entity, artwork);
        byte[] bytes = upload.getBytes();

        if (bytes == null || bytes.length == 0) {
            throw new AppException(AppErrorMessage.LISTING_INVALID);
        }

        if (bytes.length > maxAssetBytes) {
            throw new AppException(AppErrorMessage.LISTING_ASSET_TOO_LARGE);
        }

        String contentType = ListingFiles.contentType(upload.getOriginalName());

        if (contentType == null) {
            throw new AppException(AppErrorMessage.LISTING_ASSET_TYPE_NOT_SUPPORTED);
        }

        if (assetRepository.countByListingId(listingId) >= maxAssets) {
            throw new AppException(AppErrorMessage.LISTING_ASSET_LIMIT_REACHED);
        }

        int sortOrder = assetRepository.findTopByListingIdOrderBySortOrderDesc(listingId)
            .map(asset -> asset.getSortOrder() + 1)
            .orElse(0);
        NewFile file = NewFile.builder()
            .withOriginalName(upload.getOriginalName())
            .withContentType(contentType)
            .withBytes(bytes)
            .build();
        StoredFileInfo stored = fileService.storeProtected(file);
        com.exe101.entity.ListingAsset saved = saveAsset(new com.exe101.entity.ListingAsset(listingId,
            stored.getFileId(), sortOrder, now()));
        log.info("[LISTING] - ACTION: addAsset: listingId: {}: assetId: {}", listingId, saved.getListingAssetId());

        return assemble(entity, artwork, artist);
    }

    @Override
    public void removeAsset(Long userId, Long listingId, Long assetId) {
        Artist artist = requireActiveArtist(userId);
        com.exe101.entity.Listing entity = requireListing(listingId);
        Artwork artwork = requireOwnedArtwork(artist, entity.getArtworkId());
        requireAssetsOpen(entity, artwork);
        com.exe101.entity.ListingAsset asset = requireAsset(listingId, assetId);

        // Only the link is removed; the StoredFile row and its bytes are retained.
        try {
            assetRepository.delete(asset);
            assetRepository.flush();
        } catch (DataAccessException exception) {
            throw translateAssetFailure(exception);
        }

        log.info("[LISTING] - ACTION: removeAsset: listingId: {}: assetId: {}", listingId, assetId);
    }

    @Override
    public FileContent getOwnAsset(Long userId, Long listingId, Long assetId) {
        Artist artist = artistService.getByUserId(userId);
        com.exe101.entity.Listing entity = requireListing(listingId);
        requireOwnedArtwork(artist, entity.getArtworkId());

        return fileService.read(requireAsset(listingId, assetId).getFileId());
    }

    @Override
    public List<Listing> listForArtwork(Long artworkId) {
        Artwork artwork = artworkService.get(artworkId);
        Artist artist = artistService.get(artwork.getArtistId());

        return assemble(repository.findByArtworkIdOrderByCreatedAtAsc(artworkId), artwork, artist);
    }

    @Override
    public Listing get(Long listingId) {
        com.exe101.entity.Listing entity = requireListing(listingId);
        Artwork artwork = artworkService.get(entity.getArtworkId());
        Artist artist = artistService.get(artwork.getArtistId());

        return assemble(entity, artwork, artist);
    }

    @Override
    public Listing changeStatusAsAdmin(Long staffId, Long listingId, ListingStatusChange change) {
        String reason = blankToNull(change.getReason());
        boolean moderation = change.getStatus() == ListingStatus.MODERATED;

        if (moderation && reason == null) {
            throw new AppException(AppErrorMessage.MODERATION_REASON_REQUIRED);
        }

        com.exe101.entity.Listing entity = requireListing(listingId);
        requireEditable(entity);
        Artwork artwork = artworkService.get(entity.getArtworkId());
        Artist artist = artistService.get(artwork.getArtistId());

        if (change.getStatus() == ListingStatus.PUBLISHED) {
            requireActive(artistService.lockForPublish(artist.getArtistId()));
        }

        String previous = entity.getStatus();
        applyTransition(entity, change.getStatus(), artwork);
        com.exe101.entity.Listing saved = save(entity);

        AuditEntry audit = AuditEntry.builder()
            .withStaffId(staffId)
            .withActionType(moderation ? moderatedAction : statusAction)
            .withTargetType(listingTarget)
            .withTargetId(saved.getListingId())
            .withReason(reason)
            .withDetails("from=" + previous + ";to=" + saved.getStatus())
            .build();
        auditService.record(audit);
        log.info("[LISTING] - ACTION: changeStatusAsAdmin: listingId: {}: status: {}", listingId,
            saved.getStatus());

        return assemble(saved, artwork, artist);
    }

    @Override
    public FileContent getAsset(Long listingId, Long assetId) {
        requireListing(listingId);

        return fileService.read(requireAsset(listingId, assetId).getFileId());
    }

    @Override
    public List<Listing> listPublished(Long artworkId) {
        Artwork artwork = artworkService.getPublished(artworkId);
        Artist artist = artistService.get(artwork.getArtistId());
        List<com.exe101.entity.Listing> listings = repository.findByArtworkIdAndStatusOrderByCreatedAtAsc(artworkId,
            ListingStatus.PUBLISHED.name());

        return assemble(listings, artwork, artist);
    }

    @Override
    public int flagCostChanges() {
        List<ManufacturingProduct> products = listProducts();
        requireValidProducts(products);
        int flagged = 0;

        for (ManufacturingProduct product : products) {
            int count = repository.flagCostChanges(product.getSpecifications(), money(product.getCost()),
                reviewableStatuses);

            if (count > 0) {
                log.info("[LISTING] - ACTION: flagCostChanges: productCode: {}: cost: {}: flagged: {}",
                    product.getCode(), product.getCost(), count);
            }

            flagged += count;
        }

        return flagged;
    }

    @Override
    public Listing confirmCostReview(Long userId, Long listingId, CostReviewConfirmation confirmation) {
        Artist artist = requireActiveArtist(userId);
        com.exe101.entity.Listing entity = requireListing(listingId);
        Artwork artwork = requireOwnedArtwork(artist, entity.getArtworkId());
        requireEditable(entity);
        requireArtworkEditable(artwork);

        if (!entity.isCostReviewRequired()) {
            throw new AppException(AppErrorMessage.LISTING_COST_REVIEW_NOT_REQUIRED);
        }

        requireVersion(entity, confirmation.getVersion());
        boolean invalid = confirmation.getArtistMarkup() == null || confirmation.getArtistMarkup().signum() < 0;

        if (invalid) {
            throw new AppException(AppErrorMessage.LISTING_INVALID);
        }

        ManufacturingProduct product = findProductBySpecifications(entity.getSpecifications());

        if (product == null) {
            log.error("[LISTING] - ACTION: confirmCostReview: no current product: listingId: {}", listingId);
            throw new AppException(AppErrorMessage.LISTING_PRODUCT_UNKNOWN);
        }

        BigDecimal markup = money(confirmation.getArtistMarkup());
        BigDecimal manufacturingCost = money(product.getCost());
        entity.setManufacturingCost(manufacturingCost);
        entity.setArtistMarkup(markup);
        entity.setPrice(money(manufacturingCost.add(markup)));
        entity.setCostReviewRequired(false);
        com.exe101.entity.Listing saved = save(entity);
        log.info("[LISTING] - ACTION: confirmCostReview: listingId: {}: cost: {}: markup: {}", listingId,
            manufacturingCost, markup);

        return assemble(saved, artwork, artist);
    }

    @Override
    public List<PurchaseOffer> findOffers(Collection<Long> listingIds) {
        if (listingIds == null || listingIds.isEmpty()) {
            return List.of();
        }

        List<com.exe101.entity.Listing> entities = repository.findAllById(listingIds);
        Map<Long, Artwork> artworks = new HashMap<>();
        Map<Long, Artist> artists = new HashMap<>();

        return entities.stream()
            .map(entity -> {
                Artwork artwork = artworks.computeIfAbsent(entity.getArtworkId(), artworkService::get);
                Artist artist = artists.computeIfAbsent(artwork.getArtistId(), artistService::get);
                boolean purchasable = isPurchasable(entity, artwork, artist);

                return PurchaseOffer.builder()
                    .withListingId(entity.getListingId())
                    .withListingType(ListingType.valueOf(entity.getListingType()))
                    .withName(entity.getName())
                    .withCurrencyCode(entity.getCurrencyCode())
                    .withPrice(entity.getPrice())
                    .withPurchasable(purchasable)
                    .withArtistUserId(artist.getUserId())
                    .build();
            })
            .toList();
    }

    // Locks the Listings, then their Artwork, then their Artists, each in ID order, until the caller commits.
    @Override
    public List<PurchaseItem> lockForPurchase(Collection<Long> listingIds) {
        if (listingIds == null || listingIds.isEmpty()) {
            return List.of();
        }

        List<com.exe101.entity.Listing> entities = repository.findByListingIdInOrderByListingIdAsc(listingIds);
        Set<Long> artworkIds = entities.stream()
            .map(com.exe101.entity.Listing::getArtworkId)
            .collect(Collectors.toSet());
        Map<Long, Artwork> artworks = artworkService.lockForPurchase(artworkIds).stream()
            .collect(Collectors.toMap(Artwork::getArtworkId, Function.identity()));
        Set<Long> artistIds = artworks.values().stream().map(Artwork::getArtistId).collect(Collectors.toSet());
        Map<Long, Artist> artists = artistService.lockForPurchase(artistIds).stream()
            .collect(Collectors.toMap(Artist::getArtistId, Function.identity()));
        Map<Long, List<ListingAsset>> assets = assetRepository.findByListingIdInOrderBySortOrderAsc(listingIds)
            .stream()
            .collect(Collectors.groupingBy(com.exe101.entity.ListingAsset::getListingId,
                Collectors.mapping(mapper::toModel, Collectors.toList())));

        return entities.stream()
            .map(entity -> {
                Artwork artwork = artworks.get(entity.getArtworkId());
                Artist artist = artists.get(artwork.getArtistId());
                boolean purchasable = isPurchasable(entity, artwork, artist);

                return PurchaseItem.builder()
                    .withListingId(entity.getListingId())
                    .withListingType(ListingType.valueOf(entity.getListingType()))
                    .withName(entity.getName())
                    .withCurrencyCode(entity.getCurrencyCode())
                    .withPrice(entity.getPrice())
                    .withManufacturingCost(entity.getManufacturingCost())
                    .withArtistMarkup(entity.getArtistMarkup())
                    .withSpecifications(entity.getSpecifications())
                    .withPurchasable(purchasable)
                    .withArtworkId(artwork.getArtworkId())
                    .withArtworkTitle(artwork.getTitle())
                    .withArtistId(artist.getArtistId())
                    .withArtistUserId(artist.getUserId())
                    .withArtistName(artist.getDisplayName())
                    .withAssets(assets.getOrDefault(entity.getListingId(), List.of()))
                    .build();
            })
            .toList();
    }

    private boolean isPurchasable(com.exe101.entity.Listing entity, Artwork artwork, Artist artist) {
        return ListingStatus.PUBLISHED.name().equals(entity.getStatus()) && !entity.isCostReviewRequired()
            && artwork.getStatus() == ArtworkStatus.PUBLISHED && activeArtist.equals(artist.getStatus());
    }

    private Artist requireActiveArtist(Long userId) {
        Artist artist = artistService.getByUserId(userId);
        requireActive(artist);

        return artist;
    }

    private void requireActive(Artist artist) {
        if (!activeArtist.equals(artist.getStatus())) {
            throw new AppException(AppErrorMessage.ARTIST_NOT_ACTIVE);
        }
    }

    private Artwork requireOwnedArtwork(Artist artist, Long artworkId) {
        Artwork artwork = artworkService.get(artworkId);

        if (!artwork.getArtistId().equals(artist.getArtistId())) {
            throw new AppException(AppErrorMessage.ACCESS_DENIED);
        }

        return artwork;
    }

    private void requireArtworkEditable(Artwork artwork) {
        if (finalArtworkStatuses.contains(artwork.getStatus())) {
            throw new AppException(AppErrorMessage.ARTWORK_NOT_EDITABLE);
        }
    }

    private com.exe101.entity.Listing requireListing(Long listingId) {
        return repository.findById(listingId)
            .orElseThrow(() -> new AppException(AppErrorMessage.LISTING_NOT_FOUND));
    }

    private void requireEditable(com.exe101.entity.Listing entity) {
        if (finalStatuses.contains(ListingStatus.valueOf(entity.getStatus()))) {
            throw new AppException(AppErrorMessage.LISTING_NOT_EDITABLE);
        }
    }

    private void requireAssetsOpen(com.exe101.entity.Listing entity, Artwork artwork) {
        requireEditable(entity);
        requireArtworkEditable(artwork);

        if (entity.getPublishedAt() != null) {
            throw new AppException(AppErrorMessage.LISTING_ASSETS_LOCKED);
        }
    }

    private com.exe101.entity.ListingAsset requireAsset(Long listingId, Long assetId) {
        return assetRepository.findByListingAssetIdAndListingId(assetId, listingId)
            .orElseThrow(() -> new AppException(AppErrorMessage.LISTING_ASSET_NOT_FOUND));
    }

    private void requireVersion(com.exe101.entity.Listing entity, byte[] version) {
        boolean sameVersion = version != null && Arrays.equals(version, entity.getRowVer());

        if (!sameVersion) {
            throw new AppException(AppErrorMessage.LISTING_VERSION_CONFLICT);
        }
    }

    // Listings record only the copied specifications, so they identify the product (approved option a).
    private void requireValidProducts(List<ManufacturingProduct> products) {
        Set<String> specifications = new HashSet<>();

        for (ManufacturingProduct product : products) {
            boolean validCost = product.getCost() != null && product.getCost().signum() >= 0
                && product.getCost().stripTrailingZeros().scale() <= 0
                && product.getCost().compareTo(moneyLimit) < 0;
            boolean validSpecifications = blankToNull(product.getSpecifications()) != null
                && specifications.add(product.getSpecifications());

            if (!validCost || !validSpecifications || blankToNull(product.getCode()) == null) {
                throw new IllegalStateException("Invalid manufacturing product configuration: " + product.getCode());
            }
        }
    }

    private ManufacturingProduct findProductBySpecifications(String specifications) {
        return listProducts().stream()
            .filter(product -> specifications != null && specifications.equals(product.getSpecifications()))
            .findFirst()
            .orElse(null);
    }

    private ManufacturingProduct requireProduct(String code) {
        return listProducts().stream()
            .filter(product -> product.getCode().equals(code))
            .findFirst()
            .orElseThrow(() -> new AppException(AppErrorMessage.LISTING_PRODUCT_UNKNOWN));
    }

    private void applyPricing(com.exe101.entity.Listing entity, ListingDetails details) {
        if (ListingType.DIGITAL.name().equals(entity.getListingType())) {
            applyDigitalPrice(entity, details);

            return;
        }

        // The physical retail price is always cost + markup, computed here; a client price is rejected.
        boolean invalid = details.getPrice() != null || details.getArtistMarkup() == null
            || details.getArtistMarkup().signum() < 0;

        if (invalid) {
            throw new AppException(AppErrorMessage.LISTING_INVALID);
        }

        String productCode = blankToNull(details.getProductCode());
        BigDecimal cost = entity.getManufacturingCost();
        String specifications = entity.getSpecifications();

        if (productCode != null && entity.getPublishedAt() != null) {
            throw new AppException(AppErrorMessage.LISTING_PRODUCT_LOCKED);
        }

        if (productCode != null) {
            ManufacturingProduct product = requireProduct(productCode);
            cost = product.getCost();
            specifications = product.getSpecifications();
        }

        if (cost == null) {
            throw new AppException(AppErrorMessage.LISTING_INVALID);
        }

        BigDecimal markup = money(details.getArtistMarkup());
        BigDecimal manufacturingCost = money(cost);
        entity.setManufacturingCost(manufacturingCost);
        entity.setArtistMarkup(markup);
        entity.setSpecifications(specifications);
        entity.setPrice(money(manufacturingCost.add(markup)));
    }

    private void applyDigitalPrice(com.exe101.entity.Listing entity, ListingDetails details) {
        boolean physicalFields = blankToNull(details.getProductCode()) != null || details.getArtistMarkup() != null;
        boolean invalid = physicalFields || details.getPrice() == null || details.getPrice().signum() <= 0;

        if (invalid) {
            throw new AppException(AppErrorMessage.LISTING_INVALID);
        }

        entity.setPrice(money(details.getPrice()));
    }

    private BigDecimal money(BigDecimal value) {
        boolean wholeVnd = value.stripTrailingZeros().scale() <= 0;
        boolean fits = value.abs().compareTo(moneyLimit) < 0;

        if (!wholeVnd || !fits) {
            throw new AppException(AppErrorMessage.LISTING_INVALID);
        }

        return value.setScale(moneyScale);
    }

    private void applyTransition(com.exe101.entity.Listing entity, ListingStatus target, Artwork artwork) {
        ListingStatus current = ListingStatus.valueOf(entity.getStatus());
        boolean allowed = transitions.getOrDefault(current, Set.of()).contains(target);

        if (!allowed) {
            throw new AppException(AppErrorMessage.LISTING_STATUS_CHANGE_NOT_ALLOWED);
        }

        if (target == ListingStatus.PUBLISHED) {
            requirePublishable(entity, artwork);
        }

        if (target == ListingStatus.PUBLISHED && entity.getPublishedAt() == null) {
            entity.setPublishedAt(now());
        }

        entity.setStatus(target.name());
    }

    private void requirePublishable(com.exe101.entity.Listing entity, Artwork artwork) {
        if (entity.isCostReviewRequired()) {
            throw new AppException(AppErrorMessage.LISTING_COST_REVIEW_REQUIRED);
        }

        if (artwork.getStatus() != ArtworkStatus.PUBLISHED) {
            throw new AppException(AppErrorMessage.LISTING_ARTWORK_NOT_PUBLISHED);
        }

        List<com.exe101.entity.ListingAsset> assets = assetRepository.findByListingIdInOrderBySortOrderAsc(
            List.of(entity.getListingId()));

        if (assets.isEmpty()) {
            throw new AppException(AppErrorMessage.LISTING_ASSET_REQUIRED);
        }

        boolean missing = assets.stream().anyMatch(asset -> !fileService.hasContent(asset.getFileId()));

        if (missing) {
            log.error("[LISTING] - ACTION: publish: missing file content: listingId: {}", entity.getListingId());
            throw new AppException(AppErrorMessage.LISTING_ASSET_MISSING);
        }
    }

    private com.exe101.entity.Listing save(com.exe101.entity.Listing entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new AppException(AppErrorMessage.LISTING_VERSION_CONFLICT);
        }
    }

    private com.exe101.entity.ListingAsset saveAsset(com.exe101.entity.ListingAsset asset) {
        try {
            return assetRepository.saveAndFlush(asset);
        } catch (DataAccessException exception) {
            throw translateAssetFailure(exception);
        }
    }

    private RuntimeException translateAssetFailure(DataAccessException exception) {
        if (DatabaseErrors.hasErrorCode(exception, publishedAssetsError)) {
            return new AppException(AppErrorMessage.LISTING_ASSETS_LOCKED);
        }

        // UQ (ListingId, SortOrder): another upload took this position first.
        if (DatabaseErrors.isDuplicateKey(exception)) {
            return new AppException(AppErrorMessage.LISTING_ASSET_CONFLICT);
        }

        return exception;
    }

    private Listing assemble(com.exe101.entity.Listing entity, Artwork artwork, Artist artist) {
        return assemble(List.of(entity), artwork, artist).getFirst();
    }

    private List<Listing> assemble(List<com.exe101.entity.Listing> entities, Artwork artwork, Artist artist) {
        if (entities.isEmpty()) {
            return List.of();
        }

        List<Long> listingIds = entities.stream().map(com.exe101.entity.Listing::getListingId).toList();
        Map<Long, List<ListingAsset>> assets = assetRepository.findByListingIdInOrderBySortOrderAsc(listingIds)
            .stream()
            .collect(Collectors.groupingBy(com.exe101.entity.ListingAsset::getListingId,
                Collectors.mapping(mapper::toModel, Collectors.toList())));
        boolean sellable = artwork.getStatus() == ArtworkStatus.PUBLISHED && activeArtist.equals(artist.getStatus());

        return entities.stream()
            .map(entity -> {
                Listing listing = mapper.toModel(entity);
                boolean offered = ListingStatus.PUBLISHED.name().equals(entity.getStatus())
                    && !entity.isCostReviewRequired();
                listing.setAssets(assets.getOrDefault(entity.getListingId(), List.of()));
                listing.setPurchasable(sellable && offered);

                if (entity.isCostReviewRequired()) {
                    ManufacturingProduct product = findProductBySpecifications(entity.getSpecifications());
                    listing.setCurrentManufacturingCost(product == null ? null : money(product.getCost()));
                }

                return listing;
            })
            .toList();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.strip();
    }

    private String requireName(String name) {
        String normalized = blankToNull(name);

        if (normalized == null || normalized.length() > maxNameLength) {
            throw new AppException(AppErrorMessage.LISTING_INVALID);
        }

        return normalized;
    }
}
