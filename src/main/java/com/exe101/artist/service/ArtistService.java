package com.exe101.artist.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.model.ArtistGrant;
import java.util.Collection;
import java.util.List;

public interface ArtistService {
    Artist grant(ArtistGrant grant, Long staffId);

    Artist get(Long artistId);

    Artist getByUserId(Long userId);

    List<Artist> lockForPurchase(Collection<Long> artistIds);
}
