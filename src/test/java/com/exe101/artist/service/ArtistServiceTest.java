package com.exe101.artist.service;

import com.exe101.artist.mapper.ArtistEntityMapper;
import com.exe101.artist.model.ArtistGrant;
import com.exe101.artist.model.CommissionSettings;
import com.exe101.artist.repository.ArtistDeactivationRepository;
import com.exe101.artist.repository.ArtistRepository;
import com.exe101.audit.service.AuditService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.Customer;
import com.exe101.customer.service.CustomerService;
import com.exe101.audit.model.AuditEntry;
import com.exe101.entity.ArtistProfile;
import com.exe101.entity.ArtistSuspension;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArtistServiceTest {
    @Mock
    private ArtistRepository repository;
    @Mock
    private ArtistDeactivationRepository deactivationRepository;
    @Mock
    private CustomerService customerService;
    @Mock
    private AuditService auditService;
    @Spy
    private ArtistEntityMapper mapper = Mappers.getMapper(ArtistEntityMapper.class);
    @InjectMocks
    private ArtistServiceImpl service;

    @Test
    void grant_artist_profile_ok() {
        // given
        when(customerService.getActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            ArtistProfile entity = invocation.getArgument(0);
            entity.setArtistId(9L);
            return entity;
        });

        // when
        var result = service.grant(new ArtistGrant(42L, " Painter ", null), 7L);
        var captured = ArgumentCaptor.forClass(ArtistProfile.class);
        verify(repository).saveAndFlush(captured.capture());
        var entity = captured.getValue();

        // then
        assertAll(
            () -> assertThat(result.getArtistId()).isEqualTo(9L),
            () -> assertThat(entity.getUserId()).isEqualTo(42L),
            () -> assertThat(entity.getDisplayName()).isEqualTo("Painter"),
            () -> assertThat(entity.getStatus()).isEqualTo("ACTIVE"),
            () -> assertThat(entity.isAcceptingCommissions()).isFalse(),
            () -> assertThat(entity.getAuthorizedByStaffId()).isEqualTo(7L),
            () -> assertThat(entity.getCreatedAt().getOffset()).isEqualTo(ZoneOffset.UTC),
            () -> assertThat(entity.getRowVer()).isNull()
        );
    }

    @Test
    void grant_artist_profile_already_artist_ko() {
        // given
        when(customerService.getActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());
        when(repository.existsByUserId(42L)).thenReturn(true);

        // when / then
        assertThatThrownBy(() -> service.grant(new ArtistGrant(42L, "Painter", null), 7L))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_ALREADY_GRANTED.getDetail());
        verify(repository, never()).saveAndFlush(any());
        verify(auditService, never()).record(any());
    }

    @Test
    void deactivate_artist_ok() {
        // given
        var profile = profile("ACTIVE");
        when(repository.findWithLockByArtistId(9L)).thenReturn(Optional.of(profile));
        when(deactivationRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ArtistSuspension record = invocation.getArgument(0);
            record.setSuspensionId(5L);
            return record;
        });

        // when
        var result = service.deactivate(7L, 9L, " Left the team ");
        var record = ArgumentCaptor.forClass(ArtistSuspension.class);
        verify(deactivationRepository).saveAndFlush(record.capture());
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(audit.capture());

        // then
        assertAll(
            () -> assertThat(profile.getStatus()).isEqualTo("SUSPENDED"),
            () -> verify(repository).saveAndFlush(profile),
            () -> assertThat(record.getValue().getArtistId()).isEqualTo(9L),
            () -> assertThat(record.getValue().getReason()).isEqualTo("Left the team"),
            () -> assertThat(record.getValue().getStaffId()).isEqualTo(7L),
            () -> assertThat(record.getValue().getStartedAt().getOffset()).isEqualTo(ZoneOffset.UTC),
            () -> assertThat(record.getValue().getEndsAt()).isNull(),
            () -> assertThat(record.getValue().getClosedAt()).isNull(),
            () -> assertThat(audit.getValue().getActionType()).isEqualTo("ARTIST_DEACTIVATED"),
            () -> assertThat(audit.getValue().getTargetType()).isEqualTo("ArtistProfile"),
            () -> assertThat(audit.getValue().getTargetId()).isEqualTo(9L),
            () -> assertThat(audit.getValue().getStaffId()).isEqualTo(7L),
            () -> assertThat(audit.getValue().getReason()).isEqualTo("Left the team"),
            () -> assertThat(audit.getValue().getDetails()).isEqualTo("deactivationId=5"),
            () -> assertThat(result.getDeactivationId()).isEqualTo(5L),
            () -> assertThat(result.getArtistStatus()).isEqualTo("SUSPENDED")
        );
    }

    @Test
    void deactivate_artist_not_found_ko() {
        // given
        when(repository.findWithLockByArtistId(9L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.deactivate(7L, 9L, "Left the team"))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_NOT_FOUND.getDetail());
        verify(deactivationRepository, never()).saveAndFlush(any());
        verify(repository, never()).saveAndFlush(any());
        verify(auditService, never()).record(any());
    }

    @Test
    void deactivate_artist_already_deactivated_ko() {
        // given
        when(repository.findWithLockByArtistId(9L)).thenReturn(Optional.of(profile("SUSPENDED")));

        // when / then
        assertThatThrownBy(() -> service.deactivate(7L, 9L, "Left the team"))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_ALREADY_DEACTIVATED.getDetail());
        verify(deactivationRepository, never()).saveAndFlush(any());
        verify(repository, never()).saveAndFlush(any());
        verify(auditService, never()).record(any());
    }

    @Test
    void reinstate_artist_ok() {
        // given
        var profile = profile("SUSPENDED");
        var open = new ArtistSuspension(9L, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), "Left the team", 7L);
        open.setSuspensionId(5L);
        when(repository.findWithLockByArtistId(9L)).thenReturn(Optional.of(profile));
        when(deactivationRepository.findByArtistIdAndClosedAtIsNull(9L)).thenReturn(Optional.of(open));
        when(deactivationRepository.saveAndFlush(open)).thenReturn(open);

        // when
        var result = service.reinstate(8L, 9L, "Rejoined");
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(audit.capture());

        // then
        assertAll(
            () -> assertThat(profile.getStatus()).isEqualTo("ACTIVE"),
            () -> verify(repository).saveAndFlush(profile),
            () -> assertThat(open.getClosedAt()).isNotNull(),
            () -> assertThat(open.getReason()).isEqualTo("Left the team"),
            () -> assertThat(audit.getValue().getActionType()).isEqualTo("ARTIST_REINSTATED"),
            () -> assertThat(audit.getValue().getStaffId()).isEqualTo(8L),
            () -> assertThat(audit.getValue().getTargetId()).isEqualTo(9L),
            () -> assertThat(audit.getValue().getReason()).isEqualTo("Rejoined"),
            () -> assertThat(audit.getValue().getDetails()).isEqualTo("deactivationId=5"),
            () -> assertThat(result.getArtistStatus()).isEqualTo("ACTIVE"),
            () -> assertThat(result.getClosedAt()).isEqualTo(open.getClosedAt())
        );
    }

    @Test
    void reinstate_artist_not_found_ko() {
        // given
        when(repository.findWithLockByArtistId(9L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.reinstate(8L, 9L, "Rejoined"))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_NOT_FOUND.getDetail());
        verify(deactivationRepository, never()).saveAndFlush(any());
        verify(auditService, never()).record(any());
    }

    @Test
    void reinstate_artist_not_deactivated_ko() {
        // given
        when(repository.findWithLockByArtistId(9L)).thenReturn(Optional.of(profile("ACTIVE")));

        // when / then
        assertThatThrownBy(() -> service.reinstate(8L, 9L, "Rejoined"))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_NOT_DEACTIVATED.getDetail());
        verify(deactivationRepository, never()).saveAndFlush(any());
        verify(repository, never()).saveAndFlush(any());
        verify(auditService, never()).record(any());
    }

    @Test
    void update_commission_settings_ok() {
        // given
        var profile = profile("ACTIVE");
        when(repository.findWithLockByUserId(42L)).thenReturn(Optional.of(profile));
        when(repository.saveAndFlush(profile)).thenReturn(profile);

        // when
        var result = service.updateCommissionSettings(42L, new CommissionSettings(true, new BigDecimal("500000")));

        // then
        assertAll(
            () -> assertThat(profile.isAcceptingCommissions()).isTrue(),
            () -> assertThat(profile.getStartingPrice()).isEqualByComparingTo("500000"),
            () -> assertThat(profile.getStartingPrice().scale()).isEqualTo(4),
            () -> assertThat(result.isAcceptingCommissions()).isTrue(),
            () -> assertThat(result.getStartingPrice()).isEqualByComparingTo("500000")
        );
    }

    @Test
    void update_commission_settings_deactivated_ko() {
        // given
        when(repository.findWithLockByUserId(42L)).thenReturn(Optional.of(profile("SUSPENDED")));

        // when / then
        assertThatThrownBy(() -> service.updateCommissionSettings(42L, new CommissionSettings(true, null)))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_NOT_ACTIVE.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void update_commission_settings_negative_price_ko() {
        // given
        var settings = new CommissionSettings(true, new BigDecimal("-1"));

        // when / then
        assertThatThrownBy(() -> service.updateCommissionSettings(42L, settings))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_SETTINGS_INVALID.getDetail());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void get_public_artist_not_found_ko() {
        // given
        when(repository.findById(9L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.getPublic(9L))
            .isInstanceOf(AppException.class).hasMessage(AppErrorMessage.ARTIST_NOT_FOUND.getDetail());
    }

    private ArtistProfile profile(String status) {
        var profile = new ArtistProfile(42L, "Painter", status, false, 7L, OffsetDateTime.now(ZoneOffset.UTC), null);
        profile.setArtistId(9L);

        return profile;
    }
}
