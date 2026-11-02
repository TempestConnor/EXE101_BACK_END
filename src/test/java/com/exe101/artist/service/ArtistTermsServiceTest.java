package com.exe101.artist.service;

import com.exe101.artist.mapper.ArtistTermsEntityMapper;
import com.exe101.artist.model.Artist;
import com.exe101.artist.model.ArtistTermsImage;
import com.exe101.artist.model.TermsImageUpload;
import com.exe101.artist.model.TermsPublication;
import com.exe101.artist.repository.ArtistTermsImageRepository;
import com.exe101.artist.repository.ArtistTermsRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.ArtistTerms;
import com.exe101.file.model.FileContent;
import com.exe101.file.model.NewFile;
import com.exe101.file.model.StoredFileInfo;
import com.exe101.file.service.FileService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.io.ByteArrayResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArtistTermsServiceTest {
    private static long userId = 50L;
    private static long artistId = 9L;
    private static long otherArtistId = 10L;
    private static long currentTermsId = 60L;
    private static long newTermsId = 61L;
    private static byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2};
    private static byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2};
    @Mock
    private ArtistTermsRepository repository;
    @Mock
    private ArtistTermsImageRepository imageRepository;
    @Mock
    private ArtistService artistService;
    @Mock
    private FileService fileService;
    @Spy
    private ArtistTermsEntityMapper mapper = Mappers.getMapper(ArtistTermsEntityMapper.class);
    @InjectMocks
    private ArtistTermsServiceImpl service;
    private List<com.exe101.entity.ArtistTermsImage> images = new ArrayList<>();
    private Map<Long, StoredFileInfo> files = new HashMap<>();
    private long nextFileId = 700L;

    @BeforeEach
    void setUp() {
        when(artistService.lockByUserId(userId)).thenReturn(artist("ACTIVE"));
        when(artistService.getPublic(artistId)).thenReturn(artist("ACTIVE"));
        when(repository.findFirstByArtistIdOrderByVersionNoDesc(artistId)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            ArtistTerms terms = invocation.getArgument(0);
            terms.setArtistTermsId(newTermsId);
            return terms;
        });
        when(imageRepository.save(any())).thenAnswer(invocation -> {
            com.exe101.entity.ArtistTermsImage image = invocation.getArgument(0);
            image.setArtistTermsImageId(200L + images.size());
            images.add(image);
            return image;
        });
        when(imageRepository.findByArtistTermsIdOrderBySortOrderAsc(anyLong())).thenAnswer(invocation -> {
            long termsId = invocation.getArgument(0);
            return images.stream()
                .filter(image -> image.getArtistTermsId() == termsId)
                .toList();
        });
        when(fileService.storePublic(any())).thenAnswer(invocation -> {
            NewFile file = invocation.getArgument(0);
            return storedFile(nextFileId++, file.getContentType(), file.getBytes().length);
        });
        when(fileService.describe(any())).thenAnswer(invocation -> {
            Collection<Long> fileIds = invocation.getArgument(0);
            return fileIds.stream()
                .map(files::get)
                .toList();
        });
    }

    @Test
    void publish_terms_ok() {
        // given
        var publication = publication(" No refund after the lineart ", List.of(), upload(png), upload(jpeg));

        // when
        var result = service.publish(userId, publication);
        var saved = ArgumentCaptor.forClass(ArtistTerms.class);
        verify(repository).saveAndFlush(saved.capture());

        // then
        assertAll(
            () -> assertThat(saved.getValue().getArtistId()).isEqualTo(artistId),
            () -> assertThat(saved.getValue().getVersionNo()).isEqualTo(1),
            () -> assertThat(saved.getValue().getTermsText()).isEqualTo("No refund after the lineart"),
            () -> assertThat(saved.getValue().getPublishedAt().getOffset()).isEqualTo(ZoneOffset.UTC),
            () -> assertThat(images).extracting(com.exe101.entity.ArtistTermsImage::getArtistTermsId)
                .containsOnly(newTermsId),
            () -> assertThat(images).extracting(com.exe101.entity.ArtistTermsImage::getSortOrder)
                .containsExactly(0, 1),
            () -> assertThat(images).extracting(com.exe101.entity.ArtistTermsImage::getFileId)
                .containsExactly(700L, 701L),
            () -> assertThat(result.getArtistTermsId()).isEqualTo(newTermsId),
            () -> assertThat(result.getVersionNo()).isEqualTo(1),
            () -> assertThat(result.getText()).isEqualTo("No refund after the lineart"),
            () -> assertThat(result.getImages()).extracting(ArtistTermsImage::getContentType)
                .containsExactly("image/png", "image/jpeg")
        );
    }

    @Test
    void publish_terms_again_keeps_earlier_version_ok() {
        // given
        var current = givenCurrentTerms();

        // when
        var result = service.publish(userId, publication("Full refund before the sketch", List.of()));
        var saved = ArgumentCaptor.forClass(ArtistTerms.class);
        verify(repository).saveAndFlush(saved.capture());

        // then
        assertAll(
            () -> assertThat(saved.getValue()).isNotSameAs(current),
            () -> assertThat(saved.getValue().getVersionNo()).isEqualTo(2),
            () -> assertThat(result.getVersionNo()).isEqualTo(2),
            () -> assertThat(current.getArtistTermsId()).isEqualTo(currentTermsId),
            () -> assertThat(current.getVersionNo()).isEqualTo(1),
            () -> assertThat(current.getTermsText()).isEqualTo("No refund after the lineart"),
            () -> assertThat(images).filteredOn(image -> image.getArtistTermsId() == currentTermsId)
                .extracting(com.exe101.entity.ArtistTermsImage::getFileId)
                .containsExactly(500L, 501L)
        );
    }

    @Test
    void publish_terms_carries_images_forward_ok() {
        // given
        givenCurrentTerms();

        // when
        var result = service.publish(userId, publication("Full refund before the sketch", List.of(101L),
            upload(png)));

        // then
        assertAll(
            () -> assertThat(images).filteredOn(image -> image.getArtistTermsId() == newTermsId)
                .extracting(com.exe101.entity.ArtistTermsImage::getFileId)
                .containsExactly(501L, 700L),
            () -> assertThat(result.getImages()).extracting(ArtistTermsImage::getSortOrder).containsExactly(0, 1),
            () -> verify(fileService, times(1)).storePublic(any())
        );
    }

    @Test
    void publish_terms_foreign_image_ko() {
        // given
        givenCurrentTerms();

        // when / then
        assertRejected(() -> service.publish(userId, publication("Terms", List.of(999L))),
            AppErrorMessage.ARTIST_TERMS_INVALID);
    }

    @Test
    void publish_terms_not_artist_ko() {
        // given
        when(artistService.lockByUserId(userId))
            .thenThrow(new AppException(AppErrorMessage.ARTIST_PROFILE_REQUIRED));

        // when / then
        assertRejected(() -> service.publish(userId, publication("Terms", List.of(), upload(png))),
            AppErrorMessage.ARTIST_PROFILE_REQUIRED);
    }

    @Test
    void publish_terms_inactive_artist_ko() {
        // given
        when(artistService.lockByUserId(userId)).thenReturn(artist("SUSPENDED"));

        // when / then
        assertRejected(() -> service.publish(userId, publication("Terms", List.of(), upload(png))),
            AppErrorMessage.ARTIST_NOT_ACTIVE);
    }

    @Test
    void publish_terms_blank_text_ko() {
        // when / then
        assertRejected(() -> service.publish(userId, publication("   ", List.of())),
            AppErrorMessage.ARTIST_TERMS_INVALID);
    }

    @Test
    void publish_terms_text_too_long_ko() {
        // when / then
        assertRejected(() -> service.publish(userId, publication("x".repeat(20001), List.of())),
            AppErrorMessage.ARTIST_TERMS_INVALID);
    }

    @Test
    void publish_terms_image_type_ko() {
        // given
        var gif = "GIF89a....".getBytes();

        // when / then
        assertRejected(() -> service.publish(userId, publication("Terms", List.of(), upload(gif))),
            AppErrorMessage.ARTIST_TERMS_IMAGE_TYPE_NOT_SUPPORTED);
    }

    @Test
    void publish_terms_image_too_large_ko() {
        // given
        var large = Arrays.copyOf(png, 10 * 1024 * 1024 + 1);

        // when / then
        assertRejected(() -> service.publish(userId, publication("Terms", List.of(), upload(large))),
            AppErrorMessage.ARTIST_TERMS_IMAGE_TOO_LARGE);
    }

    @Test
    void publish_terms_too_many_images_ko() {
        // given
        var uploads = IntStream.range(0, 11)
            .mapToObj(index -> upload(png))
            .toArray(TermsImageUpload[]::new);

        // when / then
        assertRejected(() -> service.publish(userId, publication("Terms", List.of(), uploads)),
            AppErrorMessage.ARTIST_TERMS_IMAGE_LIMIT_REACHED);
    }

    @Test
    void get_current_terms_ok() {
        // given
        givenCurrentTerms();

        // when
        var result = service.getCurrent(artistId);

        // then
        assertAll(
            () -> assertThat(result.getArtistTermsId()).isEqualTo(currentTermsId),
            () -> assertThat(result.getArtistId()).isEqualTo(artistId),
            () -> assertThat(result.getVersionNo()).isEqualTo(1),
            () -> assertThat(result.getText()).isEqualTo("No refund after the lineart"),
            () -> assertThat(result.getImages()).extracting(ArtistTermsImage::getImageId).containsExactly(100L, 101L),
            () -> assertThat(result.getImages()).extracting(ArtistTermsImage::getByteLength).containsExactly(10L, 5L)
        );
    }

    @Test
    void get_current_terms_none_ko() {
        // when / then
        assertThatThrownBy(() -> service.getCurrent(artistId))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.ARTIST_TERMS_NOT_FOUND);
    }

    @Test
    void get_current_terms_artist_not_found_ko() {
        // given
        when(artistService.getPublic(artistId)).thenThrow(new AppException(AppErrorMessage.ARTIST_NOT_FOUND));

        // when / then
        assertThatThrownBy(() -> service.getCurrent(artistId))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.ARTIST_NOT_FOUND);
        verify(repository, never()).findFirstByArtistIdOrderByVersionNoDesc(anyLong());
    }

    @Test
    void get_terms_image_content_ok() {
        // given
        var current = givenCurrentTerms();
        when(repository.findById(currentTermsId)).thenReturn(Optional.of(current));
        when(imageRepository.findById(101L)).thenReturn(Optional.of(images.get(1)));
        var content = FileContent.builder()
            .withFileId(501L)
            .withContentType("image/jpeg")
            .withByteLength(jpeg.length)
            .withContent(new ByteArrayResource(jpeg))
            .build();
        when(fileService.read(501L)).thenReturn(content);

        // when
        var result = service.getImage(artistId, 101L);

        // then
        assertThat(result).isSameAs(content);
    }

    @Test
    void get_terms_image_not_in_terms_ko() {
        // given
        var current = givenCurrentTerms();
        when(repository.findById(currentTermsId)).thenReturn(Optional.of(current));
        when(imageRepository.findById(101L)).thenReturn(Optional.of(images.get(1)));
        when(imageRepository.findById(999L)).thenReturn(Optional.empty());

        // when / then
        assertAll(
            () -> assertThatThrownBy(() -> service.getImage(otherArtistId, 101L))
                .isInstanceOf(AppException.class)
                .extracting("error")
                .isEqualTo(AppErrorMessage.ARTIST_TERMS_IMAGE_NOT_FOUND),
            () -> assertThatThrownBy(() -> service.getImage(artistId, 999L))
                .isInstanceOf(AppException.class)
                .extracting("error")
                .isEqualTo(AppErrorMessage.ARTIST_TERMS_IMAGE_NOT_FOUND),
            () -> verify(fileService, never()).read(any())
        );
    }

    // Every rejection happens before a file is stored or a row is written.
    private void assertRejected(Runnable action, AppErrorMessage error) {
        var imagesBefore = images.size();
        assertThatThrownBy(action::run)
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(error);
        verify(repository, never()).saveAndFlush(any());
        verify(fileService, never()).storePublic(any());
        assertThat(images).hasSize(imagesBefore);
    }

    // Version 1 with two images: 100 (file 500, PNG) and 101 (file 501, JPEG).
    private ArtistTerms givenCurrentTerms() {
        var current = new ArtistTerms(artistId, 1, "No refund after the lineart",
            OffsetDateTime.of(2026, 11, 1, 9, 0, 0, 0, ZoneOffset.UTC));
        current.setArtistTermsId(currentTermsId);
        when(repository.findFirstByArtistIdOrderByVersionNoDesc(artistId)).thenReturn(Optional.of(current));
        images.add(image(100L, 500L, 0));
        images.add(image(101L, 501L, 1));
        storedFile(500L, "image/png", png.length);
        storedFile(501L, "image/jpeg", jpeg.length);
        return current;
    }

    private com.exe101.entity.ArtistTermsImage image(Long imageId, long fileId, int sortOrder) {
        var image = new com.exe101.entity.ArtistTermsImage(currentTermsId, fileId, sortOrder);
        image.setArtistTermsImageId(imageId);
        return image;
    }

    private StoredFileInfo storedFile(Long fileId, String contentType, long byteLength) {
        var file = StoredFileInfo.builder()
            .withFileId(fileId)
            .withContentType(contentType)
            .withByteLength(byteLength)
            .withAccessClass("PUBLIC")
            .build();
        files.put(fileId, file);
        return file;
    }

    private TermsPublication publication(String text, List<Long> keepImageIds, TermsImageUpload... uploads) {
        return TermsPublication.builder()
            .withText(text)
            .withKeepImageIds(keepImageIds)
            .withUploads(List.of(uploads))
            .build();
    }

    private TermsImageUpload upload(byte[] bytes) {
        return TermsImageUpload.builder()
            .withOriginalName("terms.png")
            .withBytes(bytes)
            .build();
    }

    private Artist artist(String status) {
        return Artist.builder()
            .withArtistId(artistId)
            .withUserId(userId)
            .withDisplayName("Painter")
            .withStatus(status)
            .withAcceptingCommissions(true)
            .build();
    }
}
