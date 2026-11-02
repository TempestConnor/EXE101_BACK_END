package com.exe101.artist.service;

import com.exe101.artist.model.ArtistTerms;
import com.exe101.artist.model.TermsPublication;
import com.exe101.file.model.FileContent;

public interface ArtistTermsService {
    ArtistTerms publish(Long userId, TermsPublication publication);

    ArtistTerms getCurrent(Long artistId);

    FileContent getImage(Long artistId, Long imageId);

    ArtistTerms get(Long artistTermsId);

    boolean hasTerms(Long artistId);

    ArtistTerms lockCurrent(Long artistId);
}
