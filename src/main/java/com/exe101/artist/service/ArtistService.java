package com.exe101.artist.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.model.ArtistGrant;

public interface ArtistService {
    Artist grant(ArtistGrant grant, Long staffId);
}
