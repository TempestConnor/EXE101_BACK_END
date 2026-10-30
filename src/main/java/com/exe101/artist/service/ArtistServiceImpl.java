package com.exe101.artist.service;

import com.exe101.artist.mapper.ArtistEntityMapper;
import com.exe101.artist.model.Artist;
import com.exe101.artist.model.ArtistGrant;
import com.exe101.artist.repository.ArtistRepository;
import com.exe101.audit.model.AuditEntry;
import com.exe101.audit.service.AuditService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.common.persistence.DatabaseErrors;
import com.exe101.customer.model.Customer;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    private static String grantAction = "ARTIST_PROFILE_GRANTED";
    private static String artistTarget = "ArtistProfile";
    private final ArtistRepository repository;
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
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).withNano(0));
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

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.strip();
    }
}
