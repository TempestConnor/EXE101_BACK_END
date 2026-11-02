package com.exe101.artwork.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.artwork.mapper.ArtworkEntityMapper;
import com.exe101.artwork.model.Artwork;
import com.exe101.artwork.model.ArtworkDetails;
import com.exe101.artwork.model.ArtworkPreview;
import com.exe101.artwork.model.ArtworkStatus;
import com.exe101.artwork.model.PreviewMove;
import com.exe101.artwork.model.PreviewUpload;
import com.exe101.artwork.model.StatusChange;
import com.exe101.artwork.repository.ArtworkMediaRepository;
import com.exe101.artwork.repository.ArtworkRepository;
import com.exe101.artwork.repository.ArtworkTagRepository;
import com.exe101.audit.model.AuditEntry;
import com.exe101.audit.service.AuditService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.common.persistence.DatabaseErrors;
import com.exe101.entity.ArtworkMedia;
import com.exe101.entity.ArtworkTag;
import com.exe101.entity.ArtworkTagId;
import com.exe101.file.model.FileContent;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;
import com.exe101.file.service.FileService;
import com.exe101.file.service.PreviewImages;
import com.exe101.tag.model.Tag;
import com.exe101.tag.service.TagService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ArtworkServiceImpl implements ArtworkService {
    private static String activeArtist = "ACTIVE";
    private static String moderatedAction = "ARTWORK_MODERATED";
    private static String statusAction = "ARTWORK_STATUS_CHANGED";
    private static String artworkTarget = "Artwork";
    private static Set<String> dimensions = Set.of("2D", "3D");
    private static Set<ArtworkStatus> finalStatuses = EnumSet.of(ArtworkStatus.MODERATED, ArtworkStatus.ARCHIVED);
    private static Map<ArtworkStatus, Set<ArtworkStatus>> transitions = Map.of(
        ArtworkStatus.DRAFT, EnumSet.of(ArtworkStatus.PUBLISHED, ArtworkStatus.ARCHIVED),
        ArtworkStatus.PUBLISHED, EnumSet.of(ArtworkStatus.UNLISTED, ArtworkStatus.ARCHIVED, ArtworkStatus.MODERATED),
        ArtworkStatus.UNLISTED, EnumSet.of(ArtworkStatus.PUBLISHED, ArtworkStatus.ARCHIVED, ArtworkStatus.MODERATED));
    private static int maxTitleLength = 240;
    private static int maxPreviews = 10;
    private static long maxPreviewBytes = 10L * 1024 * 1024;
    private final ArtworkRepository repository;
    private final ArtworkMediaRepository mediaRepository;
    private final ArtworkTagRepository tagLinkRepository;
    private final ArtworkEntityMapper mapper;
    private final ArtistService artistService;
    private final TagService tagService;
    private final FileService fileService;
    private final AuditService auditService;
    private final ListingAvailability listingAvailability;

    @Override
    public Artwork create(Long userId, ArtworkDetails details) {
        Artist artist = requireActiveArtist(userId);
        ArtworkDetails normalized = normalize(details);
        List<Tag> tags = tagService.requireActive(normalized.getTagIds());
        com.exe101.entity.Artwork entity = new com.exe101.entity.Artwork();
        entity.setArtistId(artist.getArtistId());
        entity.setTitle(normalized.getTitle());
        entity.setDescription(normalized.getDescription());
        entity.setDimension(normalized.getDimension());
        entity.setStatus(ArtworkStatus.DRAFT.name());
        entity.setCreatedAt(now());
        com.exe101.entity.Artwork saved = repository.saveAndFlush(entity);
        replaceTags(saved.getArtworkId(), tags);
        log.info("[ARTWORK] - ACTION: create: artistId: {}: artworkId: {}", artist.getArtistId(),
            saved.getArtworkId());

        return assemble(saved);
    }

    @Override
    public Artwork update(Long userId, Long artworkId, ArtworkDetails details) {
        Artist artist = requireActiveArtist(userId);
        com.exe101.entity.Artwork entity = requireOwned(artist, artworkId);
        requireEditable(entity);
        boolean sameVersion = details.getVersion() != null && Arrays.equals(details.getVersion(), entity.getRowVer());

        if (!sameVersion) {
            throw new AppException(AppErrorMessage.ARTWORK_VERSION_CONFLICT);
        }

        ArtworkDetails normalized = normalize(details);
        boolean dimensionChanged = !normalized.getDimension().equals(entity.getDimension());

        if (entity.getPublishedAt() != null && dimensionChanged) {
            throw new AppException(AppErrorMessage.ARTWORK_DIMENSION_LOCKED);
        }

        List<Tag> tags = tagService.requireActive(normalized.getTagIds());
        entity.setTitle(normalized.getTitle());
        entity.setDescription(normalized.getDescription());
        entity.setDimension(normalized.getDimension());
        com.exe101.entity.Artwork saved = save(entity);
        replaceTags(artworkId, tags);
        log.info("[ARTWORK] - ACTION: update: artworkId: {}", artworkId);

        return assemble(saved);
    }

    @Override
    public Artwork changeStatus(Long userId, Long artworkId, StatusChange change) {
        Artist artist = artistService.getByUserId(userId);
        com.exe101.entity.Artwork entity = requireOwned(artist, artworkId);
        requireEditable(entity);

        if (change.getStatus() == ArtworkStatus.MODERATED) {
            throw new AppException(AppErrorMessage.ARTWORK_STATUS_CHANGE_NOT_ALLOWED);
        }

        if (change.getStatus() == ArtworkStatus.PUBLISHED) {
            requireActive(artistService.lockForPublish(artist.getArtistId()));
        }

        applyTransition(entity, change.getStatus());
        com.exe101.entity.Artwork saved = save(entity);
        log.info("[ARTWORK] - ACTION: changeStatus: artworkId: {}: status: {}", artworkId, saved.getStatus());

        return assemble(saved);
    }

    @Override
    public List<Artwork> listOwn(Long userId) {
        Artist artist = artistService.getByUserId(userId);

        return assemble(repository.findByArtistIdOrderByCreatedAtDesc(artist.getArtistId()));
    }

    @Override
    public Artwork getOwn(Long userId, Long artworkId) {
        Artist artist = artistService.getByUserId(userId);

        return assemble(requireOwned(artist, artworkId));
    }

    @Override
    public Artwork addPreview(Long userId, Long artworkId, PreviewUpload upload) {
        Artist artist = requireActiveArtist(userId);
        com.exe101.entity.Artwork entity = requireOwned(artist, artworkId);
        requireEditable(entity);
        byte[] bytes = upload.getBytes();

        if (bytes == null || bytes.length == 0) {
            throw new AppException(AppErrorMessage.ARTWORK_INVALID);
        }

        if (bytes.length > maxPreviewBytes) {
            throw new AppException(AppErrorMessage.PREVIEW_TOO_LARGE);
        }

        String contentType = PreviewImages.detectContentType(bytes);

        if (contentType == null) {
            throw new AppException(AppErrorMessage.PREVIEW_TYPE_NOT_SUPPORTED);
        }

        if (mediaRepository.countByArtworkId(artworkId) >= maxPreviews) {
            throw new AppException(AppErrorMessage.PREVIEW_LIMIT_REACHED);
        }

        int sortOrder = upload.getSortOrder() == null ? nextSortOrder(artworkId) : upload.getSortOrder();
        requireFreePosition(artworkId, sortOrder);
        NewFile file = NewFile.builder()
            .withOriginalName(upload.getOriginalName())
            .withContentType(contentType)
            .withBytes(bytes)
            .build();
        StoredFileInfo stored = fileService.storePublic(file);
        ArtworkMedia saved = saveMedia(new ArtworkMedia(artworkId, stored.getFileId(), sortOrder));
        log.info("[ARTWORK] - ACTION: addPreview: artworkId: {}: mediaId: {}", artworkId,
            saved.getArtworkMediaId());

        return assemble(entity);
    }

    @Override
    public Artwork movePreview(Long userId, Long artworkId, PreviewMove move) {
        Artist artist = requireActiveArtist(userId);
        com.exe101.entity.Artwork entity = requireOwned(artist, artworkId);
        requireEditable(entity);
        ArtworkMedia media = requireMedia(artworkId, move.getMediaId());

        if (media.getSortOrder() == move.getSortOrder()) {
            return assemble(entity);
        }

        requireFreePosition(artworkId, move.getSortOrder());
        media.setSortOrder(move.getSortOrder());
        saveMedia(media);
        log.info("[ARTWORK] - ACTION: movePreview: artworkId: {}: mediaId: {}", artworkId, move.getMediaId());

        return assemble(entity);
    }

    @Override
    public void removePreview(Long userId, Long artworkId, Long mediaId) {
        Artist artist = requireActiveArtist(userId);
        com.exe101.entity.Artwork entity = requireOwned(artist, artworkId);
        requireEditable(entity);
        ArtworkMedia media = requireMedia(artworkId, mediaId);
        boolean lastPublishedPreview = ArtworkStatus.PUBLISHED.name().equals(entity.getStatus())
            && mediaRepository.countByArtworkId(artworkId) <= 1;

        if (lastPublishedPreview) {
            throw new AppException(AppErrorMessage.ARTWORK_PREVIEW_REQUIRED);
        }

        // Only the link is removed; the StoredFile row and its bytes are retained.
        mediaRepository.delete(media);
        mediaRepository.flush();
        log.info("[ARTWORK] - ACTION: removePreview: artworkId: {}: mediaId: {}", artworkId, mediaId);
    }

    @Override
    public FileContent getOwnPreview(Long userId, Long artworkId, Long mediaId) {
        Artist artist = artistService.getByUserId(userId);
        requireOwned(artist, artworkId);

        return fileService.read(requireMedia(artworkId, mediaId).getFileId());
    }

    @Override
    public List<Artwork> listAll() {
        return assemble(repository.findAllByOrderByCreatedAtDesc());
    }

    @Override
    public Artwork get(Long artworkId) {
        return assemble(requireArtwork(artworkId));
    }

    @Override
    public Artwork changeStatusAsAdmin(Long staffId, Long artworkId, StatusChange change) {
        String reason = blankToNull(change.getReason());
        boolean moderation = change.getStatus() == ArtworkStatus.MODERATED;

        if (moderation && reason == null) {
            throw new AppException(AppErrorMessage.MODERATION_REASON_REQUIRED);
        }

        com.exe101.entity.Artwork entity = requireArtwork(artworkId);
        requireEditable(entity);

        if (change.getStatus() == ArtworkStatus.PUBLISHED) {
            requireActive(artistService.lockForPublish(entity.getArtistId()));
        }

        String previous = entity.getStatus();
        applyTransition(entity, change.getStatus());
        com.exe101.entity.Artwork saved = save(entity);

        AuditEntry audit = AuditEntry.builder()
            .withStaffId(staffId)
            .withActionType(moderation ? moderatedAction : statusAction)
            .withTargetType(artworkTarget)
            .withTargetId(saved.getArtworkId())
            .withReason(reason)
            .withDetails("from=" + previous + ";to=" + saved.getStatus())
            .build();
        auditService.record(audit);
        log.info("[ARTWORK] - ACTION: changeStatusAsAdmin: artworkId: {}: status: {}", artworkId,
            saved.getStatus());

        return assemble(saved);
    }

    @Override
    public FileContent getPreview(Long artworkId, Long mediaId) {
        requireArtwork(artworkId);

        return fileService.read(requireMedia(artworkId, mediaId).getFileId());
    }

    @Override
    public List<Artwork> listPublished() {
        return assemble(repository.findByStatusOrderByPublishedAtDesc(ArtworkStatus.PUBLISHED.name()));
    }

    @Override
    public Artwork getPublished(Long artworkId) {
        return assemble(requirePublished(artworkId));
    }

    @Override
    public FileContent getPublishedPreview(Long artworkId, Long mediaId) {
        requirePublished(artworkId);

        return fileService.read(requireMedia(artworkId, mediaId).getFileId());
    }

    // Only the Artwork row is needed to check and snapshot a purchase; tags and previews are not loaded.
    @Override
    public List<Artwork> lockForPurchase(Collection<Long> artworkIds) {
        if (artworkIds == null || artworkIds.isEmpty()) {
            return List.of();
        }

        return repository.findByArtworkIdInOrderByArtworkIdAsc(artworkIds).stream()
            .map(mapper::toModel)
            .toList();
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

    private com.exe101.entity.Artwork requireArtwork(Long artworkId) {
        return repository.findById(artworkId)
            .orElseThrow(() -> new AppException(AppErrorMessage.ARTWORK_NOT_FOUND));
    }

    private com.exe101.entity.Artwork requirePublished(Long artworkId) {
        return repository.findByArtworkIdAndStatus(artworkId, ArtworkStatus.PUBLISHED.name())
            .orElseThrow(() -> new AppException(AppErrorMessage.ARTWORK_NOT_FOUND));
    }

    private com.exe101.entity.Artwork requireOwned(Artist artist, Long artworkId) {
        com.exe101.entity.Artwork entity = requireArtwork(artworkId);

        if (entity.getArtistId() != artist.getArtistId()) {
            throw new AppException(AppErrorMessage.ACCESS_DENIED);
        }

        return entity;
    }

    private void requireEditable(com.exe101.entity.Artwork entity) {
        if (finalStatuses.contains(ArtworkStatus.valueOf(entity.getStatus()))) {
            throw new AppException(AppErrorMessage.ARTWORK_NOT_EDITABLE);
        }
    }

    private ArtworkMedia requireMedia(Long artworkId, Long mediaId) {
        return mediaRepository.findByArtworkMediaIdAndArtworkId(mediaId, artworkId)
            .orElseThrow(() -> new AppException(AppErrorMessage.ARTWORK_MEDIA_NOT_FOUND));
    }

    private void requireFreePosition(Long artworkId, int sortOrder) {
        if (sortOrder < 0) {
            throw new AppException(AppErrorMessage.ARTWORK_INVALID);
        }

        if (mediaRepository.existsByArtworkIdAndSortOrder(artworkId, sortOrder)) {
            throw new AppException(AppErrorMessage.ARTWORK_MEDIA_ORDER_TAKEN);
        }
    }

    private int nextSortOrder(Long artworkId) {
        return mediaRepository.findTopByArtworkIdOrderBySortOrderDesc(artworkId)
            .map(media -> media.getSortOrder() + 1)
            .orElse(0);
    }

    private void applyTransition(com.exe101.entity.Artwork entity, ArtworkStatus target) {
        ArtworkStatus current = ArtworkStatus.valueOf(entity.getStatus());
        boolean allowed = transitions.getOrDefault(current, Set.of()).contains(target);

        if (!allowed) {
            throw new AppException(AppErrorMessage.ARTWORK_STATUS_CHANGE_NOT_ALLOWED);
        }

        if (target == ArtworkStatus.PUBLISHED && mediaRepository.countByArtworkId(entity.getArtworkId()) == 0) {
            throw new AppException(AppErrorMessage.ARTWORK_PREVIEW_REQUIRED);
        }

        if (target == ArtworkStatus.PUBLISHED && entity.getPublishedAt() == null) {
            entity.setPublishedAt(now());
        }

        entity.setStatus(target.name());
    }

    private void replaceTags(Long artworkId, List<Tag> tags) {
        Set<Integer> requested = tags.stream().map(Tag::getTagId).collect(Collectors.toSet());
        List<ArtworkTag> existing = tagLinkRepository.findByIdArtworkIdIn(List.of(artworkId));
        Set<Integer> existingIds = existing.stream().map(link -> link.getId().getTagId()).collect(Collectors.toSet());
        Set<Integer> activeExisting = tagService.findActive(existingIds).stream()
            .map(Tag::getTagId)
            .collect(Collectors.toSet());
        // Links to deactivated tags stay in place, hidden, so reactivating a tag restores them.
        List<ArtworkTag> removed = existing.stream()
            .filter(link -> activeExisting.contains(link.getId().getTagId()))
            .filter(link -> !requested.contains(link.getId().getTagId()))
            .toList();
        List<ArtworkTag> added = requested.stream()
            .filter(tagId -> !existingIds.contains(tagId))
            .map(tagId -> new ArtworkTag(new ArtworkTagId(artworkId, tagId)))
            .toList();

        tagLinkRepository.deleteAll(removed);
        tagLinkRepository.saveAll(added);
        tagLinkRepository.flush();
    }

    private com.exe101.entity.Artwork save(com.exe101.entity.Artwork entity) {
        try {
            return repository.saveAndFlush(entity);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new AppException(AppErrorMessage.ARTWORK_VERSION_CONFLICT);
        }
    }

    private ArtworkMedia saveMedia(ArtworkMedia media) {
        try {
            return mediaRepository.saveAndFlush(media);
        } catch (DataIntegrityViolationException exception) {
            // UQ (ArtworkId, SortOrder): another request took this position first.
            if (DatabaseErrors.isDuplicateKey(exception)) {
                throw new AppException(AppErrorMessage.ARTWORK_MEDIA_ORDER_TAKEN);
            }

            throw exception;
        }
    }

    private Artwork assemble(com.exe101.entity.Artwork entity) {
        return assemble(List.of(entity)).getFirst();
    }

    private List<Artwork> assemble(List<com.exe101.entity.Artwork> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }

        List<Long> artworkIds = entities.stream().map(com.exe101.entity.Artwork::getArtworkId).toList();
        Map<Long, List<ArtworkPreview>> previews = mediaRepository.findByArtworkIdInOrderBySortOrderAsc(artworkIds)
            .stream()
            .collect(Collectors.groupingBy(ArtworkMedia::getArtworkId,
                Collectors.mapping(mapper::toModel, Collectors.toList())));
        List<ArtworkTag> links = tagLinkRepository.findByIdArtworkIdIn(artworkIds);
        Collection<Integer> linkedTagIds = links.stream().map(link -> link.getId().getTagId())
            .collect(Collectors.toCollection(HashSet::new));
        Map<Integer, Tag> activeTags = tagService.findActive(linkedTagIds).stream()
            .collect(Collectors.toMap(Tag::getTagId, Function.identity()));
        Map<Long, List<Tag>> tagsByArtwork = links.stream()
            .filter(link -> activeTags.containsKey(link.getId().getTagId()))
            .collect(Collectors.groupingBy(link -> link.getId().getArtworkId(),
                Collectors.mapping(link -> activeTags.get(link.getId().getTagId()), Collectors.toList())));
        Set<Long> purchasable = purchasable(entities);

        return entities.stream()
            .map(entity -> {
                Artwork artwork = mapper.toModel(entity);
                artwork.setPreviews(previews.getOrDefault(entity.getArtworkId(), List.of()));
                artwork.setTags(tagsByArtwork.getOrDefault(entity.getArtworkId(), List.of()));
                artwork.setPurchasable(purchasable.contains(entity.getArtworkId()));

                return artwork;
            })
            .toList();
    }

    // Purchasable: PUBLISHED Artwork of an ACTIVE Artist with at least one offered Listing.
    private Set<Long> purchasable(List<com.exe101.entity.Artwork> entities) {
        List<Long> publishedIds = entities.stream()
            .filter(entity -> ArtworkStatus.PUBLISHED.name().equals(entity.getStatus()))
            .map(com.exe101.entity.Artwork::getArtworkId)
            .toList();

        if (publishedIds.isEmpty()) {
            return Set.of();
        }

        Set<Long> offered = listingAvailability.findArtworksWithOfferedListings(publishedIds);
        Map<Long, Boolean> activeArtists = entities.stream()
            .filter(entity -> offered.contains(entity.getArtworkId()))
            .map(com.exe101.entity.Artwork::getArtistId)
            .distinct()
            .collect(Collectors.toMap(Function.identity(),
                artistId -> activeArtist.equals(artistService.get(artistId).getStatus())));

        return entities.stream()
            .filter(entity -> offered.contains(entity.getArtworkId()))
            .filter(entity -> activeArtists.getOrDefault(entity.getArtistId(), false))
            .map(com.exe101.entity.Artwork::getArtworkId)
            .collect(Collectors.toSet());
    }

    private ArtworkDetails normalize(ArtworkDetails details) {
        String title = blankToNull(details.getTitle());
        String dimension = details.getDimension() == null ? null : details.getDimension().strip();
        boolean valid = title != null && title.length() <= maxTitleLength && dimensions.contains(dimension);

        if (!valid) {
            throw new AppException(AppErrorMessage.ARTWORK_INVALID);
        }

        ArtworkDetails normalized = ArtworkDetails.builder()
            .withTitle(title)
            .withDescription(blankToNull(details.getDescription()))
            .withDimension(dimension)
            .withTagIds(details.getTagIds() == null ? List.of() : details.getTagIds())
            .withVersion(details.getVersion())
            .build();

        return normalized;
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
}
