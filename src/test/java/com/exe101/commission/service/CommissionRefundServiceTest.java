package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.commission.repository.CommissionEventRepository;
import com.exe101.commission.repository.CommissionQuoteRepository;
import com.exe101.commission.repository.CommissionRepository;
import com.exe101.commission.repository.QuotePaymentRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.Commission;
import com.exe101.proceeds.service.ProceedsService;
import com.exe101.refund.model.Refund;
import com.exe101.refund.model.RefundStatus;
import com.exe101.refund.service.RefundService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The offers, settlements and releases are covered through the cancellation and dispute tests; this covers reading
// a Commission's refunds.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommissionRefundServiceTest {
    private static long customerId = 42L;
    private static long artistUserId = 50L;
    private static long strangerId = 77L;
    private static long artistId = 9L;
    private static long commissionId = 3L;
    @Mock
    private CommissionRepository repository;
    @Mock
    private CommissionQuoteRepository quoteRepository;
    @Mock
    private QuotePaymentRepository paymentRepository;
    @Mock
    private CommissionEventRepository eventRepository;
    @Mock
    private ArtistService artistService;
    @Mock
    private RefundService refundService;
    @Mock
    private ProceedsService proceedsService;
    @InjectMocks
    private CommissionRefundServiceImpl service;

    @BeforeEach
    void setUp() {
        var commission = new Commission(customerId, artistId, "Portrait of my cat", "CANCELLED", "Buyer", "Painter",
            now(), null, null);
        commission.setCommissionId(commissionId);
        when(repository.findById(commissionId)).thenReturn(Optional.of(commission));
        when(artistService.get(artistId)).thenReturn(Artist.builder()
            .withArtistId(artistId)
            .withUserId(artistUserId)
            .withStatus("ACTIVE")
            .build());
    }

    @Test
    void list_participant_refunds_ok() {
        // given
        when(refundService.listForCommission(commissionId)).thenReturn(List.of(
            refund(61L, RefundStatus.REJECTED, 10),
            refund(62L, RefundStatus.APPROVED, 70)));

        // when
        var forCustomer = service.list(customerId, commissionId);
        var forArtist = service.list(artistUserId, commissionId);

        // then
        assertAll(
            () -> assertThat(forCustomer).extracting(refund -> refund.getRefundRequestId()).containsExactly(61L, 62L),
            () -> assertThat(forCustomer).extracting(refund -> refund.getRefundPercent()).containsExactly(10, 70),
            () -> assertThat(forCustomer).extracting(refund -> refund.getStatus())
                .containsExactly(RefundStatus.REJECTED, RefundStatus.APPROVED),
            () -> assertThat(forArtist).hasSize(2)
        );
    }

    @Test
    void non_participant_ko() {
        // given / when / then
        assertThatThrownBy(() -> service.list(strangerId, commissionId))
            .isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getError()).isEqualTo(AppErrorMessage.COMMISSION_NOT_FOUND));
        verify(refundService, never()).listForCommission(anyLong());
    }

    private Refund refund(long refundRequestId, RefundStatus status, int percent) {
        return Refund.builder()
            .withRefundRequestId(refundRequestId)
            .withCommissionId(commissionId)
            .withRequestedByUserId(customerId)
            .withReason("Plans changed")
            .withStatus(status)
            .withRefundPercent(percent)
            .withRequestedAt(now())
            .build();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
