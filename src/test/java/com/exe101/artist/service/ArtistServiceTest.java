package com.exe101.artist.service;

import com.exe101.artist.mapper.ArtistEntityMapper;
import com.exe101.artist.model.ArtistGrant;
import com.exe101.artist.repository.ArtistRepository;
import com.exe101.audit.service.AuditService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.customer.model.Customer;
import com.exe101.customer.service.CustomerService;
import com.exe101.entity.ArtistProfile;
import java.time.ZoneOffset;
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
}
