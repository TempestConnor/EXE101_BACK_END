package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.cart.config.CheckoutProperties;
import com.exe101.commission.mapper.CommissionEntityMapper;
import com.exe101.commission.mapper.CommissionQuoteEntityMapper;
import com.exe101.commission.model.CommissionStatus;
import com.exe101.commission.model.QuoteStatus;
import com.exe101.commission.model.QuoteTerms;
import com.exe101.commission.repository.CancellationRequestRepository;
import com.exe101.commission.repository.CommissionEventRepository;
import com.exe101.commission.repository.CommissionQuoteRepository;
import com.exe101.commission.repository.CommissionRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.Commission;
import com.exe101.entity.CommissionEvent;
import com.exe101.entity.CommissionQuote;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommissionQuoteServiceTest {
    private static long customerId = 42L;
    private static long artistUserId = 50L;
    private static long strangerId = 77L;
    private static long artistId = 9L;
    private static long commissionId = 3L;
    private static long quoteId = 21L;
    @Mock
    private CommissionRepository repository;
    @Mock
    private CommissionQuoteRepository quoteRepository;
    @Mock
    private CommissionEventRepository eventRepository;
    @Mock
    private CancellationRequestRepository cancellationRepository;
    @Mock
    private ArtistService artistService;
    @Spy
    private CommissionEntityMapper commissionMapper = Mappers.getMapper(CommissionEntityMapper.class);
    @Spy
    private CommissionQuoteEntityMapper mapper = Mappers.getMapper(CommissionQuoteEntityMapper.class);
    private CommissionQuoteServiceImpl service;

    @BeforeEach
    void setUp() {
        service = quoteService("0.10");
        when(artistService.get(artistId)).thenReturn(artist("ACTIVE"));
        when(artistService.lockForCommission(artistId)).thenReturn(artist("ACTIVE"));
        when(quoteRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            CommissionQuote quote = invocation.getArgument(0);

            if (quote.getQuoteId() == null) {
                quote.setQuoteId(quoteId);
            }

            return quote;
        });
    }

    @Test
    void offer_quote_ok() {
        // given
        var commission = givenCommission("REQUESTED");
        when(quoteRepository.findLastSequenceNo(commissionId)).thenReturn(0);

        // when
        var result = service.offer(artistUserId, commissionId, terms("1500000", " Full-body portrait ", " 3 weeks "));
        var saved = ArgumentCaptor.forClass(CommissionQuote.class);
        verify(quoteRepository).saveAndFlush(saved.capture());
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getQuoteId()).isEqualTo(quoteId),
            () -> assertThat(result.getStatus()).isEqualTo(QuoteStatus.OFFERED),
            () -> assertThat(saved.getValue().getCommissionId()).isEqualTo(commissionId),
            () -> assertThat(saved.getValue().getSequenceNo()).isEqualTo(1),
            () -> assertThat(saved.getValue().getScopeText()).isEqualTo("Full-body portrait"),
            () -> assertThat(saved.getValue().getExpectedCompletionTerms()).isEqualTo("3 weeks"),
            () -> assertThat(saved.getValue().getPrice()).isEqualTo(new BigDecimal("1500000.0000")),
            () -> assertThat(saved.getValue().getTaxAmount()).isEqualTo(new BigDecimal("150000.0000")),
            () -> assertThat(saved.getValue().getCurrencyCode()).isEqualTo("VND"),
            () -> assertThat(saved.getValue().getStatus()).isEqualTo("OFFERED"),
            () -> assertThat(saved.getValue().getAcceptedAt()).isNull(),
            () -> assertThat(saved.getValue().getFeeRate()).isNull(),
            () -> assertThat(saved.getValue().getOfferedAt().getOffset()).isEqualTo(ZoneOffset.UTC),
            () -> assertThat(commission.getStatus()).isEqualTo("QUOTED"),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("QUOTE_OFFERED"),
            () -> assertThat(event.getValue().getFromStatus()).isEqualTo("REQUESTED"),
            () -> assertThat(event.getValue().getToStatus()).isEqualTo("QUOTED"),
            () -> assertThat(event.getValue().getRelatedId()).isEqualTo(quoteId),
            () -> assertThat(event.getValue().getActorUserId()).isEqualTo(artistUserId)
        );
    }

    @Test
    void offer_quote_tax_rounds_half_up_ok() {
        // given
        service = quoteService("0.05");
        givenCommission("REQUESTED");
        when(quoteRepository.findLastSequenceNo(commissionId)).thenReturn(0);

        // when
        // 5% of 10 010 is 500.5, which rounds up to 501.
        var result = service.offer(artistUserId, commissionId, terms("10010", "Sketch", "1 week"));

        // then
        assertAll(
            () -> assertThat(result.getTaxAmount()).isEqualTo(new BigDecimal("501.0000")),
            () -> assertThat(result.getPrice()).isEqualTo(new BigDecimal("10010.0000"))
        );
    }

    @Test
    void offer_quote_after_decline_next_sequence_ok() {
        // given
        givenCommission("REQUESTED");
        when(quoteRepository.findLastSequenceNo(commissionId)).thenReturn(1);

        // when
        var result = service.offer(artistUserId, commissionId, terms("900000", "Half-body portrait", "2 weeks"));

        // then
        assertThat(result.getSequenceNo()).isEqualTo(2);
    }

    @Test
    void offer_quote_zero_price_initial_ko() {
        // given
        givenCommission("REQUESTED");

        // when / then
        assertRejected(() -> service.offer(artistUserId, commissionId, terms("0", "Sketch", "1 week")),
            AppErrorMessage.QUOTE_INVALID);
    }

    @Test
    void offer_quote_fractional_price_ko() {
        // given
        givenCommission("REQUESTED");

        // when / then
        assertRejected(() -> service.offer(artistUserId, commissionId, terms("100000.5", "Sketch", "1 week")),
            AppErrorMessage.QUOTE_INVALID);
    }

    @Test
    void offer_quote_invalid_scope_ko() {
        // given
        givenCommission("REQUESTED");

        // when / then
        assertAll(
            () -> assertRejected(() -> service.offer(artistUserId, commissionId, terms("100000", "   ", "1 week")),
                AppErrorMessage.QUOTE_INVALID),
            () -> assertRejected(() -> service.offer(artistUserId, commissionId,
                terms("100000", "x".repeat(5001), "1 week")), AppErrorMessage.QUOTE_INVALID)
        );
    }

    @Test
    void offer_quote_invalid_completion_terms_ko() {
        // given
        givenCommission("REQUESTED");

        // when / then
        assertAll(
            () -> assertRejected(() -> service.offer(artistUserId, commissionId, terms("100000", "Sketch", "")),
                AppErrorMessage.QUOTE_INVALID),
            () -> assertRejected(() -> service.offer(artistUserId, commissionId,
                terms("100000", "Sketch", "x".repeat(1001))), AppErrorMessage.QUOTE_INVALID)
        );
    }

    @Test
    void offer_quote_by_customer_ko() {
        // given
        givenCommission("REQUESTED");

        // when / then
        assertRejected(() -> service.offer(customerId, commissionId, terms("100000", "Sketch", "1 week")),
            AppErrorMessage.ACCESS_DENIED);
    }

    @Test
    void offer_quote_non_participant_ko() {
        // given
        givenCommission("REQUESTED");

        // when / then
        assertRejected(() -> service.offer(strangerId, commissionId, terms("100000", "Sketch", "1 week")),
            AppErrorMessage.COMMISSION_NOT_FOUND);
    }

    @Test
    void offer_quote_not_found_ko() {
        // given
        when(repository.findWithLockByCommissionId(commissionId)).thenReturn(Optional.empty());

        // when / then
        assertRejected(() -> service.offer(artistUserId, commissionId, terms("100000", "Sketch", "1 week")),
            AppErrorMessage.COMMISSION_NOT_FOUND);
    }

    @Test
    void pending_cancellation_blocks_quote_offer_ko() {
        // given
        givenCommission("IN_PROGRESS");
        when(cancellationRepository.existsByCommissionIdAndStatus(commissionId, "PENDING")).thenReturn(true);

        // when / then
        assertRejected(() -> service.offer(artistUserId, commissionId, terms("100000", "Sketch", "1 week")),
            AppErrorMessage.CANCELLATION_PENDING);
    }

    @Test
    void offer_quote_while_outstanding_ko() {
        // given
        givenCommission("QUOTED");

        // when / then
        assertRejected(() -> service.offer(artistUserId, commissionId, terms("100000", "Sketch", "1 week")),
            AppErrorMessage.QUOTE_ALREADY_OFFERED);
    }

    @Test
    void offer_quote_closed_commission_ko() {
        // given
        givenCommission("DECLINED");

        // when / then
        assertRejected(() -> service.offer(artistUserId, commissionId, terms("100000", "Sketch", "1 week")),
            AppErrorMessage.COMMISSION_CLOSED);
    }

    @Test
    void offer_quote_deactivated_artist_ko() {
        // given
        givenCommission("REQUESTED");
        when(artistService.lockForCommission(artistId)).thenReturn(artist("SUSPENDED"));

        // when / then
        assertRejected(() -> service.offer(artistUserId, commissionId, terms("100000", "Sketch", "1 week")),
            AppErrorMessage.ARTIST_NOT_ACTIVE);
    }

    @Test
    void accept_quote_ok() {
        // given
        var commission = givenCommission("QUOTED");
        var quote = givenQuote("OFFERED");

        // when
        var result = service.accept(customerId, commissionId, quoteId);
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(QuoteStatus.ACCEPTED),
            () -> assertThat(quote.getStatus()).isEqualTo("ACCEPTED"),
            () -> assertThat(quote.getAcceptedAt()).isNotNull(),
            () -> assertThat(quote.getFeeRate()).isEqualTo(new BigDecimal("0.000000")),
            () -> assertThat(quote.getFeeAmount()).isEqualTo(new BigDecimal("0.0000")),
            () -> assertThat(quote.getArtistProceeds()).isEqualTo(new BigDecimal("1500000.0000")),
            () -> assertThat(quote.getPrice()).isEqualTo(new BigDecimal("1500000.0000")),
            () -> assertThat(quote.getTaxAmount()).isEqualTo(new BigDecimal("150000.0000")),
            () -> assertThat(quote.getPaidAt()).isNull(),
            () -> assertThat(commission.getStatus()).isEqualTo("AWAITING_PAYMENT"),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("QUOTE_ACCEPTED"),
            () -> assertThat(event.getValue().getFromStatus()).isEqualTo("QUOTED"),
            () -> assertThat(event.getValue().getToStatus()).isEqualTo("AWAITING_PAYMENT"),
            () -> assertThat(event.getValue().getRelatedId()).isEqualTo(quoteId),
            () -> assertThat(event.getValue().getActorUserId()).isEqualTo(customerId)
        );
    }

    @Test
    void accept_quote_by_artist_ko() {
        // given
        givenCommission("QUOTED");
        givenQuote("OFFERED");

        // when / then
        assertRejected(() -> service.accept(artistUserId, commissionId, quoteId), AppErrorMessage.ACCESS_DENIED);
    }

    @Test
    void accept_quote_not_offered_ko() {
        // given
        givenCommission("REQUESTED");
        givenQuote("WITHDRAWN");

        // when / then
        assertRejected(() -> service.accept(customerId, commissionId, quoteId), AppErrorMessage.QUOTE_NOT_OFFERED);
    }

    @Test
    void accept_quote_deactivated_artist_ko() {
        // given
        givenCommission("QUOTED");
        givenQuote("OFFERED");
        when(artistService.lockForCommission(artistId)).thenReturn(artist("SUSPENDED"));

        // when / then
        assertRejected(() -> service.accept(customerId, commissionId, quoteId), AppErrorMessage.ARTIST_NOT_ACTIVE);
    }

    @Test
    void accept_quote_not_found_ko() {
        // given
        givenCommission("QUOTED");
        when(quoteRepository.findByQuoteIdAndCommissionId(quoteId, commissionId)).thenReturn(Optional.empty());

        // when / then
        assertRejected(() -> service.accept(customerId, commissionId, quoteId), AppErrorMessage.QUOTE_NOT_FOUND);
    }

    @Test
    void decline_quote_ok() {
        // given
        var commission = givenCommission("QUOTED");
        var quote = givenQuote("OFFERED");

        // when
        var result = service.decline(customerId, commissionId, quoteId);
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(QuoteStatus.DECLINED),
            () -> assertThat(quote.getAcceptedAt()).isNull(),
            () -> assertThat(commission.getStatus()).isEqualTo("REQUESTED"),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("QUOTE_DECLINED"),
            () -> assertThat(event.getValue().getFromStatus()).isEqualTo("QUOTED"),
            () -> assertThat(event.getValue().getToStatus()).isEqualTo("REQUESTED"),
            () -> assertThat(event.getValue().getActorUserId()).isEqualTo(customerId)
        );
    }

    @Test
    void decline_quote_by_artist_ko() {
        // given
        givenCommission("QUOTED");
        givenQuote("OFFERED");

        // when / then
        assertRejected(() -> service.decline(artistUserId, commissionId, quoteId), AppErrorMessage.ACCESS_DENIED);
    }

    @Test
    void withdraw_quote_ok() {
        // given
        var commission = givenCommission("QUOTED");
        givenQuote("OFFERED");

        // when
        var result = service.withdraw(artistUserId, commissionId, quoteId);
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(QuoteStatus.WITHDRAWN),
            () -> assertThat(commission.getStatus()).isEqualTo("REQUESTED"),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("QUOTE_WITHDRAWN"),
            () -> assertThat(event.getValue().getFromStatus()).isEqualTo("QUOTED"),
            () -> assertThat(event.getValue().getToStatus()).isEqualTo("REQUESTED"),
            () -> assertThat(event.getValue().getActorUserId()).isEqualTo(artistUserId)
        );
    }

    @Test
    void withdraw_quote_by_customer_ko() {
        // given
        givenCommission("QUOTED");
        givenQuote("OFFERED");

        // when / then
        assertRejected(() -> service.withdraw(customerId, commissionId, quoteId), AppErrorMessage.ACCESS_DENIED);
    }

    @Test
    void withdraw_accepted_quote_ko() {
        // given
        givenCommission("AWAITING_PAYMENT");
        givenQuote("ACCEPTED");

        // when / then
        assertRejected(() -> service.withdraw(artistUserId, commissionId, quoteId), AppErrorMessage.QUOTE_NOT_OFFERED);
    }

    @Test
    void withdraw_quote_deactivated_artist_ok() {
        // given
        givenCommission("QUOTED");
        givenQuote("OFFERED");
        when(artistService.get(artistId)).thenReturn(artist("SUSPENDED"));
        when(artistService.lockForCommission(artistId)).thenReturn(artist("SUSPENDED"));

        // when
        var result = service.withdraw(artistUserId, commissionId, quoteId);

        // then
        assertThat(result.getStatus()).isEqualTo(QuoteStatus.WITHDRAWN);
    }

    @Test
    void decline_request_ok() {
        // given
        var commission = givenCommission("REQUESTED");
        when(quoteRepository.findByCommissionIdAndStatus(commissionId, "OFFERED")).thenReturn(List.of());

        // when
        var result = service.declineRequest(artistUserId, commissionId);
        var event = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository).saveAndFlush(event.capture());

        // then
        assertAll(
            () -> assertThat(result.getStatus()).isEqualTo(CommissionStatus.DECLINED),
            () -> assertThat(commission.getStatus()).isEqualTo("DECLINED"),
            () -> assertThat(event.getValue().getEventType()).isEqualTo("DECLINED"),
            () -> assertThat(event.getValue().getFromStatus()).isEqualTo("REQUESTED"),
            () -> assertThat(event.getValue().getToStatus()).isEqualTo("DECLINED"),
            () -> assertThat(event.getValue().getActorUserId()).isEqualTo(artistUserId),
            () -> verify(quoteRepository, never()).saveAndFlush(any())
        );
    }

    @Test
    void decline_request_withdraws_offered_quote_ok() {
        // given
        var commission = givenCommission("QUOTED");
        var quote = quote("OFFERED");
        when(quoteRepository.findByCommissionIdAndStatus(commissionId, "OFFERED")).thenReturn(List.of(quote));

        // when
        service.declineRequest(artistUserId, commissionId);
        var events = ArgumentCaptor.forClass(CommissionEvent.class);
        verify(eventRepository, times(2)).saveAndFlush(events.capture());

        // then
        assertAll(
            () -> assertThat(quote.getStatus()).isEqualTo("WITHDRAWN"),
            () -> assertThat(commission.getStatus()).isEqualTo("DECLINED"),
            () -> assertThat(events.getAllValues()).extracting(CommissionEvent::getEventType)
                .containsExactly("QUOTE_WITHDRAWN", "DECLINED"),
            () -> assertThat(events.getAllValues().get(0).getRelatedId()).isEqualTo(quoteId),
            () -> assertThat(events.getAllValues().get(1).getFromStatus()).isEqualTo("QUOTED")
        );
    }

    @Test
    void decline_request_after_acceptance_ko() {
        // given
        givenCommission("AWAITING_PAYMENT");

        // when / then
        assertRejected(() -> service.declineRequest(artistUserId, commissionId),
            AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
    }

    @Test
    void decline_request_by_customer_ko() {
        // given
        givenCommission("REQUESTED");

        // when / then
        assertRejected(() -> service.declineRequest(customerId, commissionId), AppErrorMessage.ACCESS_DENIED);
    }

    @Test
    void list_quotes_ok() {
        // given
        when(repository.findById(commissionId)).thenReturn(Optional.of(commission("QUOTED")));
        var declined = quote("DECLINED");
        var offered = quote("OFFERED");
        offered.setQuoteId(22L);
        offered.setSequenceNo(2);
        when(quoteRepository.findByCommissionIdOrderBySequenceNoAsc(commissionId)).thenReturn(List.of(declined,
            offered));

        // when
        var result = service.list(customerId, commissionId);

        // then
        assertAll(
            () -> assertThat(result).extracting(com.exe101.commission.model.CommissionQuote::getSequenceNo)
                .containsExactly(1, 2),
            () -> assertThat(result).extracting(com.exe101.commission.model.CommissionQuote::getStatus)
                .containsExactly(QuoteStatus.DECLINED, QuoteStatus.OFFERED)
        );
    }

    @Test
    void list_quotes_non_participant_ko() {
        // given
        when(repository.findById(commissionId)).thenReturn(Optional.of(commission("QUOTED")));

        // when / then
        assertThatThrownBy(() -> service.list(strangerId, commissionId))
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(AppErrorMessage.COMMISSION_NOT_FOUND);
        verify(quoteRepository, never()).findByCommissionIdOrderBySequenceNoAsc(commissionId);
    }

    // Every rejection happens before anything is written.
    private void assertRejected(Runnable action, AppErrorMessage error) {
        assertThatThrownBy(action::run)
            .isInstanceOf(AppException.class)
            .extracting("error")
            .isEqualTo(error);
        verify(quoteRepository, never()).saveAndFlush(any());
        verify(repository, never()).saveAndFlush(any());
        verify(eventRepository, never()).saveAndFlush(any());
    }

    private CommissionQuoteServiceImpl quoteService(String taxRate) {
        return new CommissionQuoteServiceImpl(repository, quoteRepository, eventRepository, cancellationRepository,
            commissionMapper, mapper, artistService,
            new CheckoutProperties(new BigDecimal(taxRate), BigDecimal.ZERO, 5));
    }

    private Commission givenCommission(String status) {
        var commission = commission(status);
        when(repository.findWithLockByCommissionId(commissionId)).thenReturn(Optional.of(commission));
        when(repository.saveAndFlush(commission)).thenReturn(commission);
        return commission;
    }

    private CommissionQuote givenQuote(String status) {
        var quote = quote(status);
        when(quoteRepository.findByQuoteIdAndCommissionId(quoteId, commissionId)).thenReturn(Optional.of(quote));
        return quote;
    }

    private Commission commission(String status) {
        var commission = new Commission(customerId, artistId, "Portrait of my cat", status, "Buyer", "Painter",
            OffsetDateTime.now(ZoneOffset.UTC), null, null);
        commission.setCommissionId(commissionId);
        return commission;
    }

    private CommissionQuote quote(String status) {
        var quote = new CommissionQuote(commissionId, 1, "Full-body portrait", "3 weeks",
            new BigDecimal("1500000.0000"), new BigDecimal("150000.0000"), "VND", status,
            OffsetDateTime.now(ZoneOffset.UTC));
        quote.setQuoteId(quoteId);
        return quote;
    }

    private QuoteTerms terms(String price, String scope, String completion) {
        return QuoteTerms.builder()
            .withPrice(new BigDecimal(price))
            .withScopeText(scope)
            .withExpectedCompletionTerms(completion)
            .build();
    }

    private Artist artist(String status) {
        return Artist.builder()
            .withArtistId(artistId)
            .withUserId(artistUserId)
            .withDisplayName("Painter")
            .withStatus(status)
            .withAcceptingCommissions(true)
            .build();
    }
}
