package com.exe101.proceeds.service;

import com.exe101.artist.service.ArtistService;
import com.exe101.audit.model.AuditEntry;
import com.exe101.audit.service.AuditService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.ArtistProceeds;
import com.exe101.entity.Payout;
import com.exe101.entity.PayoutLine;
import com.exe101.listing.model.ListingType;
import com.exe101.order.model.Order;
import com.exe101.order.model.OrderLine;
import com.exe101.order.model.OrderStatus;
import com.exe101.proceeds.mapper.ProceedsEntityMapperImpl;
import com.exe101.proceeds.model.ProceedsStatus;
import com.exe101.proceeds.model.ReleaseOutcome;
import com.exe101.proceeds.model.ReleaseStatus;
import com.exe101.proceeds.repository.ProceedsRepository;
import com.exe101.proceeds.repository.ReleaseLineRepository;
import com.exe101.proceeds.repository.ReleaseRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Lenient: each test uses part of the shared persistence.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProceedsServiceTest {
    private static long staffId = 7L;
    private static long orderId = 300L;
    private static long digitalItemId = 310L;
    private static long physicalItemId = 311L;
    private static long digitalArtistId = 21L;
    private static long physicalArtistId = 22L;
    private static long proceedsId = 500L;
    private static long payoutId = 600L;
    @Mock
    private ProceedsRepository repository;
    @Mock
    private ReleaseRepository releaseRepository;
    @Mock
    private ReleaseLineRepository lineRepository;
    @Mock
    private ArtistService artistService;
    @Mock
    private AuditService auditService;
    private ProceedsServiceImpl service;
    private List<ArtistProceeds> savedProceeds;
    private List<Payout> savedReleases;
    private List<PayoutLine> savedLines;

    @BeforeEach
    void setUp() {
        service = new ProceedsServiceImpl(repository, releaseRepository, lineRepository,
            new ProceedsEntityMapperImpl(), artistService, auditService);
        savedProceeds = new ArrayList<>();
        savedReleases = new ArrayList<>();
        savedLines = new ArrayList<>();
        var ids = new AtomicLong(1000);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            ArtistProceeds saved = invocation.getArgument(0);
            if (saved.getProceedsId() == null) {
                saved.setProceedsId(ids.incrementAndGet());
            }
            savedProceeds.add(saved);
            return saved;
        });
        when(releaseRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Payout saved = invocation.getArgument(0);
            if (saved.getPayoutId() == null) {
                saved.setPayoutId(ids.incrementAndGet());
            }
            savedReleases.add(saved);
            return saved;
        });
        when(lineRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            PayoutLine saved = invocation.getArgument(0);
            if (saved.getPayoutLineId() == null) {
                saved.setPayoutLineId(ids.incrementAndGet());
            }
            savedLines.add(saved);
            return saved;
        });
    }

    @Test
    void payment_success_creates_held_proceeds_per_item_ok() {
        // given
        var order = paidOrder();

        // when
        var recorded = service.recordPaid(order);

        // then
        assertAll(
            () -> assertThat(recorded).extracting(proceeds -> proceeds.getOrderItemId())
                .containsExactly(digitalItemId, physicalItemId),
            () -> assertThat(recorded).extracting(proceeds -> proceeds.getArtistId())
                .containsExactly(digitalArtistId, physicalArtistId),
            () -> assertThat(recorded).extracting(proceeds -> proceeds.getAmount())
                .containsExactly(new BigDecimal("50000.0000"), new BigDecimal("60000.0000")),
            () -> assertThat(recorded).extracting(proceeds -> proceeds.getCurrencyCode()).containsOnly("VND"),
            () -> assertThat(savedProceeds).filteredOn(entity -> entity.getOrderItemId() == physicalItemId)
                .extracting(ArtistProceeds::getStatus).containsOnly("HELD"),
            () -> assertThat(savedProceeds).extracting(ArtistProceeds::getQuoteId).containsOnlyNulls()
        );
    }

    @Test
    void digital_proceeds_eligible_on_payment_ok() {
        // given
        // Eligible at the payment, and released at once: the proceeds are reserved for one PENDING release.
        var order = paidOrder();

        // when
        var recorded = service.recordPaid(order);

        // then
        var digital = recorded.getFirst();
        var physical = recorded.get(1);
        var release = savedReleases.getFirst();
        var line = savedLines.getFirst();
        assertAll(
            () -> assertThat(digital.getStatus()).isEqualTo(ProceedsStatus.RESERVED),
            () -> assertThat(digital.getEligibleAt()).isEqualTo(order.getPaidAt()),
            () -> assertThat(digital.getPaidAt()).isNull(),
            () -> assertThat(physical.getStatus()).isEqualTo(ProceedsStatus.HELD),
            () -> assertThat(physical.getEligibleAt()).isNull(),
            () -> assertThat(savedReleases).hasSize(1),
            () -> assertThat(release.getStatus()).isEqualTo("PENDING"),
            () -> assertThat(release.getArtistId()).isEqualTo(digitalArtistId),
            () -> assertThat(release.getAmount()).isEqualByComparingTo("50000"),
            () -> assertThat(release.getPaidAt()).isNull(),
            () -> assertThat(line.getPayoutId()).isEqualTo(release.getPayoutId()),
            () -> assertThat(line.getProceedsId()).isEqualTo(digital.getProceedsId()),
            () -> assertThat(line.getAmount()).isEqualByComparingTo(release.getAmount()),
            () -> assertThat(line.isIsActive()).isTrue()
        );
        verifyNoInteractions(auditService);
    }

    @Test
    void existing_proceeds_not_created_twice_ko() {
        // given
        when(repository.existsByOrderItemId(anyLong())).thenReturn(true);

        // when
        var recorded = service.recordPaid(paidOrder());

        // then
        assertThat(recorded).isEmpty();
        verify(repository, never()).saveAndFlush(any());
        verify(releaseRepository, never()).saveAndFlush(any());
        verify(lineRepository, never()).saveAndFlush(any());
    }

    @Test
    void release_reserves_proceeds_and_sums_lines_ok() {
        // given
        var proceeds = lockedProceeds("ELIGIBLE", "60000");

        // when
        var release = service.release(staffId, proceedsId);

        // then
        var line = savedLines.getFirst();
        var audit = audit();
        assertAll(
            () -> assertThat(proceeds.getStatus()).isEqualTo("RESERVED"),
            () -> assertThat(release.getStatus()).isEqualTo(ReleaseStatus.PENDING),
            () -> assertThat(release.getProceedsId()).isEqualTo(proceedsId),
            () -> assertThat(release.getArtistId()).isEqualTo(physicalArtistId),
            () -> assertThat(release.getAmount()).isEqualByComparingTo("60000"),
            () -> assertThat(savedLines).hasSize(1),
            () -> assertThat(line.getAmount()).isEqualByComparingTo(release.getAmount()),
            () -> assertThat(line.getPayoutId()).isEqualTo(release.getPayoutId()),
            () -> assertThat(audit.getActionType()).isEqualTo("RELEASE_CREATED"),
            () -> assertThat(audit.getStaffId()).isEqualTo(staffId),
            () -> assertThat(audit.getTargetId()).isEqualTo(release.getPayoutId())
        );
    }

    @Test
    void record_paid_marks_proceeds_paid_ok() {
        // given
        var payout = lockedRelease("PROCESSING");
        var proceeds = reservedLine();

        // when
        var release = service.recordOutcome(staffId, payoutId, outcome(ReleaseStatus.PAID, " VCB-20261101-0042 "));

        // then
        var audit = audit();
        assertAll(
            () -> assertThat(release.getStatus()).isEqualTo(ReleaseStatus.PAID),
            () -> assertThat(release.getTransferReference()).isEqualTo("VCB-20261101-0042"),
            () -> assertThat(payout.getStatus()).isEqualTo("PAID"),
            () -> assertThat(payout.getProviderName()).isEqualTo("MANUAL"),
            () -> assertThat(payout.getPaidAt()).isNotNull(),
            () -> assertThat(proceeds.getStatus()).isEqualTo("PAID"),
            () -> assertThat(proceeds.getPaidAt()).isEqualTo(payout.getPaidAt()),
            () -> assertThat(audit.getActionType()).isEqualTo("RELEASE_STATUS_CHANGED"),
            () -> assertThat(audit.getTargetType()).isEqualTo("PAYOUT"),
            () -> assertThat(audit.getTargetId()).isEqualTo(payoutId),
            () -> assertThat(audit.getReason()).isEqualTo("Transferred"),
            () -> assertThat(audit.getDetails()).contains("from=PROCESSING", "to=PAID", "VCB-20261101-0042")
        );
    }

    @Test
    void record_failed_returns_proceeds_to_eligible_ok() {
        // given
        var payout = lockedRelease("PENDING");
        var proceeds = reservedLine();

        // when
        var release = service.recordOutcome(staffId, payoutId, outcome(ReleaseStatus.FAILED, null));

        // then
        var line = savedLines.getFirst();
        assertAll(
            () -> assertThat(release.getStatus()).isEqualTo(ReleaseStatus.FAILED),
            () -> assertThat(payout.getPaidAt()).isNull(),
            () -> assertThat(line.isIsActive()).isFalse(),
            () -> assertThat(proceeds.getStatus()).isEqualTo("ELIGIBLE"),
            () -> assertThat(proceeds.getPaidAt()).isNull(),
            () -> assertThat(audit().getDetails()).contains("from=PENDING", "to=FAILED")
        );
    }

    @Test
    void record_outcome_on_settled_payout_ko() {
        // given
        lockedRelease("PAID");

        // when / then
        assertThatThrownBy(() -> service.recordOutcome(staffId, payoutId, outcome(ReleaseStatus.FAILED, null)))
            .isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getError()).isEqualTo(AppErrorMessage.RELEASE_STATUS_CHANGE_NOT_ALLOWED));
        verify(releaseRepository, never()).saveAndFlush(any());
        verify(repository, never()).saveAndFlush(any());
        verify(lineRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void record_outcome_unknown_payout_ko() {
        // given
        when(releaseRepository.findWithLockByPayoutId(payoutId)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.recordOutcome(staffId, payoutId, outcome(ReleaseStatus.PROCESSING, null)))
            .isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getError()).isEqualTo(AppErrorMessage.RELEASE_NOT_FOUND));
        verify(releaseRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void record_paid_without_reference_ko() {
        // given
        lockedRelease("PENDING");

        // when / then
        assertThatThrownBy(() -> service.recordOutcome(staffId, payoutId, outcome(ReleaseStatus.PAID, "  ")))
            .isInstanceOfSatisfying(AppException.class, exception ->
                assertThat(exception.getError()).isEqualTo(AppErrorMessage.RELEASE_REFERENCE_INVALID));
        verify(releaseRepository, never()).findWithLockByPayoutId(anyLong());
        verify(releaseRepository, never()).saveAndFlush(any());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void zero_proceeds_follow_policy_ok() {
        // given
        // A physical markup of 0 earns nothing: settled at the milestone, with no release (amounts must be > 0).
        var proceeds = new ArtistProceeds(physicalArtistId, physicalItemId, null, new BigDecimal("0.0000"), "VND",
            "HELD", null, null);
        proceeds.setProceedsId(proceedsId);
        when(repository.findWithLockByOrderItemId(physicalItemId)).thenReturn(Optional.of(proceeds));

        // when
        var released = service.releasePhysical(physicalItemId);

        // then
        assertAll(
            () -> assertThat(released).isPresent(),
            () -> assertThat(proceeds.getStatus()).isEqualTo("PAID"),
            () -> assertThat(proceeds.getEligibleAt()).isNotNull(),
            () -> assertThat(proceeds.getPaidAt()).isEqualTo(proceeds.getEligibleAt())
        );
        verify(releaseRepository, never()).saveAndFlush(any());
        verify(lineRepository, never()).saveAndFlush(any());
    }

    private ArtistProceeds lockedProceeds(String status, String amount) {
        var proceeds = new ArtistProceeds(physicalArtistId, physicalItemId, null, new BigDecimal(amount), "VND",
            status, now().minusHours(1), null);
        proceeds.setProceedsId(proceedsId);
        when(repository.findWithLockByProceedsId(proceedsId)).thenReturn(Optional.of(proceeds));

        return proceeds;
    }

    private Payout lockedRelease(String status) {
        var payout = new Payout(physicalArtistId, new BigDecimal("60000.0000"), "VND", status, now().minusHours(1));
        payout.setPayoutId(payoutId);
        when(releaseRepository.findWithLockByPayoutId(payoutId)).thenReturn(Optional.of(payout));

        return payout;
    }

    private ArtistProceeds reservedLine() {
        var line = new PayoutLine(payoutId, proceedsId, new BigDecimal("60000.0000"), true);
        when(lineRepository.findByPayoutIdAndIsActiveTrue(payoutId)).thenReturn(Optional.of(line));

        return lockedProceeds("RESERVED", "60000.0000");
    }

    private ReleaseOutcome outcome(ReleaseStatus status, String transferReference) {
        return ReleaseOutcome.builder()
            .withStatus(status)
            .withTransferReference(transferReference)
            .withReason("Transferred")
            .build();
    }

    private AuditEntry audit() {
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(captor.capture());

        return captor.getValue();
    }

    // A paid Order with a digital item of one Artist and a physical item (2 x 30 000 markup) of another.
    private Order paidOrder() {
        return Order.builder()
            .withOrderId(orderId)
            .withCustomerId(42L)
            .withStatus(OrderStatus.PAID)
            .withPaidAt(now().minusMinutes(1))
            .withItems(List.of(
                line(digitalItemId, ListingType.DIGITAL, digitalArtistId, "50000.0000"),
                line(physicalItemId, ListingType.PHYSICAL, physicalArtistId, "60000.0000")))
            .build();
    }

    private OrderLine line(long orderItemId, ListingType listingType, long artistId, String artistProceeds) {
        return OrderLine.builder()
            .withOrderItemId(orderItemId)
            .withListingType(listingType)
            .withArtistId(artistId)
            .withQuantity(listingType == ListingType.PHYSICAL ? 2 : 1)
            .withCurrencyCode("VND")
            .withFeeRate(BigDecimal.ZERO)
            .withFeeAmount(BigDecimal.ZERO)
            .withArtistProceeds(new BigDecimal(artistProceeds))
            .withAssets(List.of())
            .build();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
