package com.exe101.artist.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.model.ArtistDeactivation;
import com.exe101.artist.model.ArtistGrant;
import com.exe101.artist.model.CommissionSettings;
import java.util.Collection;
import java.util.List;

public interface ArtistService {
    Artist grant(ArtistGrant grant, Long staffId);

    Artist get(Long artistId);

    Artist getByUserId(Long userId);

    Artist lockByUserId(Long userId);

    List<Artist> lockForPurchase(Collection<Long> artistIds);

    Artist lockForPublish(Long artistId);

    Artist lockForCommission(Long artistId);

    Artist getPublic(Long artistId);

    Artist updateCommissionSettings(Long userId, CommissionSettings settings);

    ArtistDeactivation deactivate(Long staffId, Long artistId, String reason);

    ArtistDeactivation reinstate(Long staffId, Long artistId, String reason);
}
