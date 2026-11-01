package com.exe101.artist.service;

import com.exe101.artist.mapper.ArtistEntityMapper;
import com.exe101.artist.model.Artist;
import com.exe101.artist.model.ArtistDeactivation;
import com.exe101.artist.model.ArtistGrant;
import com.exe101.artist.model.CommissionSettings;
import com.exe101.artist.repository.ArtistDeactivationRepository;
import com.exe101.artist.repository.ArtistRepository;
import com.exe101.audit.model.AuditEntry;
import com.exe101.audit.service.AuditService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.common.persistence.DatabaseErrors;
import com.exe101.customer.model.Customer;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.ArtistSuspension;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ArtistServiceImpl implements ArtistService {
    private static String activeStatus = "ACTIVE";
    // The schema's SUSPENDED status represents deactivation (amendment A.4).
    private static String deactivatedStatus = "SUSPENDED";
    private static String grantAction = "ARTIST_PROFILE_GRANTED";
    private static String deactivateAction = "ARTIST_DEACTIVATED";
    private static String reinstateAction = "ARTIST_REINSTATED";
    private static String artistTarget = "ArtistProfile";
    // Money is whole VND, stored as decimal(19,4): at most 15 integer digits.
    private static int moneyScale = 4;
    private static BigDecimal moneyLimit = BigDecimal.TEN.pow(15);
    private final ArtistRepository repository;
    private final ArtistDeactivationRepository deactivationRepository;
    private final ArtistEntityMapper mapper;
    private final CustomerService customerService;
    private final AuditService auditService;

    @Override
    public Artist grant(ArtistGrant grant, Long staffId) {
        Customer user = customerService.getActive(grant.getUserId());

        if (repository.existsByUserId(user.getUserId())) {
            throw new AppException(AppErrorMessage.ARTIST_ALREADY_GRANTED);
        }

        ArtistProfile entity = mapper.toEntity(grant);
        entity.setDisplayName(grant.getDisplayName().strip());
        entity.setStatus(activeStatus);
        entity.setAcceptingCommissions(false);
        entity.setAuthorizedByStaffId(staffId);
        entity.setCreatedAt(now());
        ArtistProfile saved;

        try {
            saved = repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseErrors.isDuplicateKey(exception)) {
                throw new AppException(AppErrorMessage.ARTIST_ALREADY_GRANTED);
            }

            throw exception;
        }

        AuditEntry audit = AuditEntry.builder()
            .withStaffId(staffId)
            .withActionType(grantAction)
            .withTargetType(artistTarget)
            .withTargetId(saved.getArtistId())
            .withReason(blankToNull(grant.getReason()))
            .withDetails("userId=" + saved.getUserId())
            .build();
        auditService.record(audit);
        log.info("[ARTIST] - ACTION: grant: artistId: {}", saved.getArtistId());

        return mapper.toModel(saved);
    }

    @Override
    public Artist get(Long artistId) {
        ArtistProfile entity = repository.findById(artistId)
            .orElseThrow(() -> new AppException(AppErrorMessage.ARTIST_PROFILE_REQUIRED));

        return mapper.toModel(entity);
    }

    @Override
    public Artist getByUserId(Long userId) {
        ArtistProfile entity = repository.findByUserId(userId)
            .orElseThrow(() -> new AppException(AppErrorMessage.ARTIST_PROFILE_REQUIRED));

        return mapper.toModel(entity);
    }

    @Override
    public List<Artist> lockForPurchase(Collection<Long> artistIds) {
        if (artistIds == null || artistIds.isEmpty()) {
            return List.of();
        }

        return repository.findByArtistIdInOrderByArtistIdAsc(artistIds).stream()
            .map(mapper::toModel)
            .toList();
    }

    @Override
    public Artist lockForPublish(Long artistId) {
        ArtistProfile entity = repository.findWithSharedLockByArtistId(artistId)
            .orElseThrow(() -> new AppException(AppErrorMessage.ARTIST_PROFILE_REQUIRED));

        return mapper.toModel(entity);
    }

    // The same shared lock as publishing: a request in progress commits before a deactivation, and a request that
    // starts later sees it (§17.9).
    @Override
    public Artist lockForCommission(Long artistId) {
        ArtistProfile entity = repository.findWithSharedLockByArtistId(artistId)
            .orElseThrow(() -> new AppException(AppErrorMessage.ARTIST_NOT_FOUND));

        return mapper.toModel(entity);
    }

    @Override
    public Artist getPublic(Long artistId) {
        ArtistProfile entity = repository.findById(artistId)
            .orElseThrow(() -> new AppException(AppErrorMessage.ARTIST_NOT_FOUND));

        return mapper.toModel(entity);
    }

    // Only an ACTIVE Artist opens or closes requests (§2.6). Deactivation leaves the setting as it was; the status
    // alone blocks new requests.
    @Override
    public Artist updateCommissionSettings(Long userId, CommissionSettings settings) {
        BigDecimal startingPrice = settings.getStartingPrice();

        if (startingPrice != null && !isWholeVnd(startingPrice)) {
            throw new AppException(AppErrorMessage.ARTIST_SETTINGS_INVALID);
        }

        ArtistProfile entity = repository.findWithLockByUserId(userId)
            .orElseThrow(() -> new AppException(AppErrorMessage.ARTIST_PROFILE_REQUIRED));

        if (!activeStatus.equals(entity.getStatus())) {
            throw new AppException(AppErrorMessage.ARTIST_NOT_ACTIVE);
        }

        entity.setAcceptingCommissions(settings.isAcceptingCommissions());
        entity.setStartingPrice(startingPrice == null ? null : startingPrice.setScale(moneyScale));
        ArtistProfile saved = repository.saveAndFlush(entity);
        log.info("[ARTIST] - ACTION: updateCommissionSettings: artistId: {}: accepting: {}", saved.getArtistId(),
            saved.isAcceptingCommissions());

        return mapper.toModel(saved);
    }

    // Under the Artist's row lock, so an Order or publication in progress commits first and later ones see the
    // deactivation. Listings are not rewritten: availability is computed from the status (F03).
    @Override
    public ArtistDeactivation deactivate(Long staffId, Long artistId, String reason) {
        ArtistProfile entity = requireLocked(artistId);

        if (!activeStatus.equals(entity.getStatus())) {
            throw new AppException(AppErrorMessage.ARTIST_ALREADY_DEACTIVATED);
        }

        ArtistSuspension record = new ArtistSuspension(artistId, now(), reason.strip(), staffId);
        ArtistSuspension saved;

        try {
            saved = deactivationRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseErrors.isDuplicateKey(exception)) {
                throw new AppException(AppErrorMessage.ARTIST_ALREADY_DEACTIVATED);
            }

            throw exception;
        }

        entity.setStatus(deactivatedStatus);
        repository.saveAndFlush(entity);

        AuditEntry audit = AuditEntry.builder()
            .withStaffId(staffId)
            .withActionType(deactivateAction)
            .withTargetType(artistTarget)
            .withTargetId(artistId)
            .withReason(saved.getReason())
            .withDetails("deactivationId=" + saved.getSuspensionId())
            .build();
        auditService.record(audit);
        log.info("[ARTIST] - ACTION: deactivate: artistId: {}", artistId);

        return mapper.toModel(saved, entity.getStatus());
    }

    @Override
    public ArtistDeactivation reinstate(Long staffId, Long artistId, String reason) {
        ArtistProfile entity = requireLocked(artistId);

        if (!deactivatedStatus.equals(entity.getStatus())) {
            throw new AppException(AppErrorMessage.ARTIST_NOT_DEACTIVATED);
        }

        ArtistSuspension record = deactivationRepository.findByArtistIdAndClosedAtIsNull(artistId)
            .orElseThrow(() -> new IllegalStateException("Deactivated Artist " + artistId + " has no open record"));
        record.setClosedAt(now());
        ArtistSuspension saved = deactivationRepository.saveAndFlush(record);
        entity.setStatus(activeStatus);
        repository.saveAndFlush(entity);

        AuditEntry audit = AuditEntry.builder()
            .withStaffId(staffId)
            .withActionType(reinstateAction)
            .withTargetType(artistTarget)
            .withTargetId(artistId)
            .withReason(reason.strip())
            .withDetails("deactivationId=" + saved.getSuspensionId())
            .build();
        auditService.record(audit);
        log.info("[ARTIST] - ACTION: reinstate: artistId: {}", artistId);

        return mapper.toModel(saved, entity.getStatus());
    }

    private ArtistProfile requireLocked(Long artistId) {
        return repository.findWithLockByArtistId(artistId)
            .orElseThrow(() -> new AppException(AppErrorMessage.ARTIST_NOT_FOUND));
    }

    private boolean isWholeVnd(BigDecimal value) {
        return value.signum() >= 0 && value.stripTrailingZeros().scale() <= 0 && value.compareTo(moneyLimit) < 0;
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
