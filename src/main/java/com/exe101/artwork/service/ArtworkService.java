package com.exe101.artwork.service;

import com.exe101.artwork.model.Artwork;
import com.exe101.artwork.model.ArtworkDetails;
import com.exe101.artwork.model.PreviewMove;
import com.exe101.artwork.model.PreviewUpload;
import com.exe101.artwork.model.StatusChange;
import com.exe101.file.model.FileContent;
import java.util.List;

public interface ArtworkService {
    Artwork create(Long userId, ArtworkDetails details);

    Artwork update(Long userId, Long artworkId, ArtworkDetails details);

    Artwork changeStatus(Long userId, Long artworkId, StatusChange change);

    List<Artwork> listOwn(Long userId);

    Artwork getOwn(Long userId, Long artworkId);

    Artwork addPreview(Long userId, Long artworkId, PreviewUpload upload);

    Artwork movePreview(Long userId, Long artworkId, PreviewMove move);

    void removePreview(Long userId, Long artworkId, Long mediaId);

    FileContent getOwnPreview(Long userId, Long artworkId, Long mediaId);

    List<Artwork> listAll();

    Artwork get(Long artworkId);

    Artwork changeStatusAsAdmin(Long staffId, Long artworkId, StatusChange change);

    FileContent getPreview(Long artworkId, Long mediaId);

    List<Artwork> listPublished();

    Artwork getPublished(Long artworkId);

    FileContent getPublishedPreview(Long artworkId, Long mediaId);
}
