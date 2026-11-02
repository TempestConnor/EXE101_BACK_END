package com.exe101.artist.service;

import com.exe101.artist.mapper.ArtistTermsEntityMapper;
import com.exe101.artist.model.Artist;
import com.exe101.artist.model.ArtistTerms;
import com.exe101.artist.model.ArtistTermsImage;
import com.exe101.artist.model.TermsImageUpload;
import com.exe101.artist.model.TermsPublication;
import com.exe101.artist.repository.ArtistTermsImageRepository;
import com.exe101.artist.repository.ArtistTermsRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.file.model.FileContent;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;
import com.exe101.file.service.FileService;
import com.exe101.file.service.PreviewImages;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Terms are published as immutable versions (TR_ArtistTerms_Immutable): a refund percentage and an Admin's dispute
// decision are judged against the version the Customer accepted with the Quote, so it is never rewritten.
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ArtistTermsServiceImpl implements ArtistTermsService {
    private static String activeArtist = "ACTIVE";
    private static int maxTextLength = 20000;
    private static int maxImages = 10;
    private static long maxImageBytes = 10L * 1024 * 1024;
    private final ArtistTermsRepository repository;
    private final ArtistTermsImageRepository imageRepository;
    private final ArtistTermsEntityMapper mapper;
    private final ArtistService artistService;
    private final FileService fileService;

    // Every check runs before a file is stored. The Artist's row lock (UPDLOCK) serializes one Artist's
    // publications and waits for a deactivation in progress.
    @Override
    public ArtistTerms publish(Long userId, TermsPublication publication) {
        String text = requireText(publication.getText());
        List<Long> keepImageIds = publication.getKeepImageIds() == null ? List.of() : publication.getKeepImageIds();
        List<TermsImageUpload> uploads = publication.getUploads() == null ? List.of() : publication.getUploads();

        if (new HashSet<>(keepImageIds).size() != keepImageIds.size()) {
            throw new AppException(AppErrorMessage.ARTIST_TERMS_INVALID);
        }

        if (keepImageIds.size() + uploads.size() > maxImages) {
            throw new AppException(AppErrorMessage.ARTIST_TERMS_IMAGE_LIMIT_REACHED);
        }

        List<NewFile> files = uploads.stream()
            .map(this::requireImage)
            .toList();
        Artist artist = artistService.lockByUserId(userId);

        if (!activeArtist.equals(artist.getStatus())) {
            throw new AppException(AppErrorMessage.ARTIST_NOT_ACTIVE);
        }

        Optional<com.exe101.entity.ArtistTerms> current =
            repository.findFirstByArtistIdOrderByVersionNoDesc(artist.getArtistId());
        List<Long> fileIds = new ArrayList<>(keptFileIds(current, keepImageIds));
        int versionNo = current.map(terms -> terms.getVersionNo() + 1).orElse(1);
        com.exe101.entity.ArtistTerms saved = repository.saveAndFlush(
            new com.exe101.entity.ArtistTerms(artist.getArtistId(), versionNo, text, now()));

        for (NewFile file : files) {
            StoredFileInfo stored = fileService.storePublic(file);
            fileIds.add(stored.getFileId());
        }

        for (int sortOrder = 0; sortOrder < fileIds.size(); sortOrder++) {
            imageRepository.save(new com.exe101.entity.ArtistTermsImage(saved.getArtistTermsId(),
                fileIds.get(sortOrder), sortOrder));
        }

        imageRepository.flush();
        log.info("[ARTIST] - ACTION: publishTerms: artistId: {}: versionNo: {}", artist.getArtistId(), versionNo);

        return assemble(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ArtistTerms getCurrent(Long artistId) {
        artistService.getPublic(artistId);
        com.exe101.entity.ArtistTerms terms = repository.findFirstByArtistIdOrderByVersionNoDesc(artistId)
            .orElseThrow(() -> new AppException(AppErrorMessage.ARTIST_TERMS_NOT_FOUND));

        return assemble(terms);
    }

    // Images of every version stay readable, so a Quote's accepted terms can still be shown in full.
    @Override
    @Transactional(readOnly = true)
    public FileContent getImage(Long artistId, Long imageId) {
        com.exe101.entity.ArtistTermsImage image = imageRepository.findById(imageId)
            .orElseThrow(() -> new AppException(AppErrorMessage.ARTIST_TERMS_IMAGE_NOT_FOUND));
        boolean ownImage = repository.findById(image.getArtistTermsId())
            .map(terms -> artistId.equals(terms.getArtistId()))
            .orElse(false);

        if (!ownImage) {
            throw new AppException(AppErrorMessage.ARTIST_TERMS_IMAGE_NOT_FOUND);
        }

        return fileService.read(image.getFileId());
    }

    @Override
    @Transactional(readOnly = true)
    public ArtistTerms get(Long artistTermsId) {
        com.exe101.entity.ArtistTerms terms = repository.findById(artistTermsId)
            .orElseThrow(() -> new AppException(AppErrorMessage.ARTIST_TERMS_NOT_FOUND));

        return assemble(terms);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasTerms(Long artistId) {
        return repository.existsByArtistId(artistId);
    }

    // For a Quote acceptance: the version read stays the latest until the acceptance commits.
    @Override
    public ArtistTerms lockCurrent(Long artistId) {
        com.exe101.entity.ArtistTerms terms = repository.findFirstWithLockByArtistIdOrderByVersionNoDesc(artistId)
            .orElseThrow(() -> new AppException(AppErrorMessage.ARTIST_TERMS_REQUIRED));

        return assemble(terms);
    }

    // Kept images are named by their ID in the current version and keep the requested order.
    private List<Long> keptFileIds(Optional<com.exe101.entity.ArtistTerms> current, List<Long> keepImageIds) {
        if (keepImageIds.isEmpty()) {
            return List.of();
        }

        if (current.isEmpty()) {
            throw new AppException(AppErrorMessage.ARTIST_TERMS_INVALID);
        }

        Map<Long, Long> fileIds = imageRepository
            .findByArtistTermsIdOrderBySortOrderAsc(current.get().getArtistTermsId()).stream()
            .collect(Collectors.toMap(com.exe101.entity.ArtistTermsImage::getArtistTermsImageId,
                com.exe101.entity.ArtistTermsImage::getFileId));

        if (!fileIds.keySet().containsAll(keepImageIds)) {
            throw new AppException(AppErrorMessage.ARTIST_TERMS_INVALID);
        }

        return keepImageIds.stream()
            .map(fileIds::get)
            .toList();
    }

    private NewFile requireImage(TermsImageUpload upload) {
        byte[] bytes = upload.getBytes();

        if (bytes == null || bytes.length == 0) {
            throw new AppException(AppErrorMessage.ARTIST_TERMS_INVALID);
        }

        if (bytes.length > maxImageBytes) {
            throw new AppException(AppErrorMessage.ARTIST_TERMS_IMAGE_TOO_LARGE);
        }

        String contentType = PreviewImages.detectContentType(bytes);

        if (contentType == null) {
            throw new AppException(AppErrorMessage.ARTIST_TERMS_IMAGE_TYPE_NOT_SUPPORTED);
        }

        NewFile file = NewFile.builder()
            .withOriginalName(upload.getOriginalName())
            .withContentType(contentType)
            .withBytes(bytes)
            .build();

        return file;
    }

    private String requireText(String value) {
        String text = value == null ? "" : value.strip();

        if (text.isEmpty() || text.length() > maxTextLength) {
            throw new AppException(AppErrorMessage.ARTIST_TERMS_INVALID);
        }

        return text;
    }

    private ArtistTerms assemble(com.exe101.entity.ArtistTerms terms) {
        List<com.exe101.entity.ArtistTermsImage> images =
            imageRepository.findByArtistTermsIdOrderBySortOrderAsc(terms.getArtistTermsId());
        List<Long> fileIds = images.stream()
            .map(com.exe101.entity.ArtistTermsImage::getFileId)
            .toList();
        Map<Long, StoredFileInfo> files = fileService.describe(fileIds).stream()
            .collect(Collectors.toMap(StoredFileInfo::getFileId, Function.identity()));
        List<ArtistTermsImage> models = images.stream()
            .map(image -> mapper.toModel(image, files.get(image.getFileId())))
            .toList();

        return mapper.toModel(terms, models);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
