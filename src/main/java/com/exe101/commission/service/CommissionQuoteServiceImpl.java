package com.exe101.commission.service;

import com.exe101.artist.model.Artist;
import com.exe101.artist.service.ArtistService;
import com.exe101.cart.config.CheckoutProperties;
import com.exe101.commission.mapper.CommissionEntityMapper;
import com.exe101.commission.mapper.CommissionQuoteEntityMapper;
import com.exe101.commission.model.CancellationStatus;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionQuote;
import com.exe101.commission.model.CommissionStatus;
import com.exe101.commission.model.QuoteStatus;
import com.exe101.commission.model.QuoteTerms;
import com.exe101.commission.repository.CancellationRequestRepository;
import com.exe101.commission.repository.CommissionEventRepository;
import com.exe101.commission.repository.CommissionQuoteRepository;
import com.exe101.commission.repository.CommissionRepository;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.entity.CommissionEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Every Quote action locks the Commission row (UPDLOCK) first, so the actions on one Commission run one at a time
// and a racing accept and withdraw have exactly one winner (spec C.7). Offer and accept then read the Artist under
// the shared lock of a request, so a deactivation either commits first or waits (amendment §A.4).
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CommissionQuoteServiceImpl implements CommissionQuoteService {
    private static String activeArtist = "ACTIVE";
    private static String currency = "VND";
    private static int moneyScale = 4;
    private static int rateScale = 6;
    private static int maxScopeLength = 5000;
    private static int maxTermsLength = 1000;
    private static BigDecimal amountLimit = BigDecimal.TEN.pow(15);
    private final CommissionRepository repository;
    private final CommissionQuoteRepository quoteRepository;
    private final CommissionEventRepository eventRepository;
    private final CancellationRequestRepository cancellationRepository;
    private final CommissionEntityMapper commissionMapper;
    private final CommissionQuoteEntityMapper mapper;
    private final ArtistService artistService;
    private final CheckoutProperties checkout;

    @Override
    @Transactional(readOnly = true)
    public List<CommissionQuote> list(Long userId, Long commissionId) {
        com.exe101.entity.Commission commission = repository.findById(commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_NOT_FOUND));
        isArtist(userId, commission);

        return quoteRepository.findByCommissionIdOrderBySequenceNoAsc(commissionId).stream()
            .map(mapper::toModel)
            .toList();
    }

    // Only the initial Quote of a REQUESTED Commission for now; change Quotes (§21) come after the first payment.
    // The tax is fixed at the offer, so the Customer accepts exactly the amount shown (approved 2 November 2026).
    @Override
    public CommissionQuote offer(Long userId, Long commissionId, QuoteTerms terms) {
        BigDecimal price = requirePrice(terms.getPrice());
        String scope = requireText(terms.getScopeText(), maxScopeLength);
        String completion = requireText(terms.getExpectedCompletionTerms(), maxTermsLength);
        BigDecimal tax = price.multiply(checkout.taxRate()).setScale(0, RoundingMode.HALF_UP);
        com.exe101.entity.Commission commission = lock(commissionId);
        requireArtist(userId, commission);
        requireNoPendingCancellation(commissionId);
        CommissionStatus status = requireOpen(commission);

        if (status == CommissionStatus.QUOTED) {
            throw new AppException(AppErrorMessage.QUOTE_ALREADY_OFFERED);
        }

        if (status != CommissionStatus.REQUESTED) {
            throw new AppException(AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
        }

        requireActiveArtist(commission);
        OffsetDateTime offeredAt = now();
        int sequenceNo = quoteRepository.findLastSequenceNo(commissionId) + 1;
        com.exe101.entity.CommissionQuote saved = quoteRepository.saveAndFlush(new com.exe101.entity.CommissionQuote(
            commissionId, sequenceNo, scope, completion, money(price), money(tax), currency,
            QuoteStatus.OFFERED.name(), offeredAt));
        changeStatus(commission, CommissionStatus.QUOTED, "QUOTE_OFFERED", saved.getQuoteId(), userId, offeredAt);
        log.info("[COMMISSION] - ACTION: offerQuote: commissionId: {}: quoteId: {}", commissionId,
            saved.getQuoteId());

        return mapper.toModel(saved);
    }

    // Accepting binds the terms and writes the zero-fee snapshot; it creates no Payment (§19.11). The Commission
    // then awaits the first 50% payment, which comes before the sketch (approved 2 November 2026).
    @Override
    public CommissionQuote accept(Long userId, Long commissionId, Long quoteId) {
        com.exe101.entity.Commission commission = lock(commissionId);
        requireCustomer(userId, commission);
        com.exe101.entity.CommissionQuote quote = requireOffered(commission, quoteId);
        requireActiveArtist(commission);
        OffsetDateTime acceptedAt = now();
        quote.setStatus(QuoteStatus.ACCEPTED.name());
        quote.setAcceptedAt(acceptedAt);
        quote.setFeeRate(BigDecimal.ZERO.setScale(rateScale));
        quote.setFeeAmount(money(BigDecimal.ZERO));
        quote.setArtistProceeds(quote.getPrice());
        com.exe101.entity.CommissionQuote saved = quoteRepository.saveAndFlush(quote);
        changeStatus(commission, CommissionStatus.AWAITING_PAYMENT, "QUOTE_ACCEPTED", quoteId, userId, acceptedAt);
        log.info("[COMMISSION] - ACTION: acceptQuote: commissionId: {}: quoteId: {}", commissionId, quoteId);

        return mapper.toModel(saved);
    }

    // Declining leaves the Commission open for another offer (§19.8–9).
    @Override
    public CommissionQuote decline(Long userId, Long commissionId, Long quoteId) {
        com.exe101.entity.Commission commission = lock(commissionId);
        requireCustomer(userId, commission);
        com.exe101.entity.CommissionQuote quote = requireOffered(commission, quoteId);
        quote.setStatus(QuoteStatus.DECLINED.name());
        com.exe101.entity.CommissionQuote saved = quoteRepository.saveAndFlush(quote);
        changeStatus(commission, CommissionStatus.REQUESTED, "QUOTE_DECLINED", quoteId, userId, now());
        log.info("[COMMISSION] - ACTION: declineQuote: commissionId: {}: quoteId: {}", commissionId, quoteId);

        return mapper.toModel(saved);
    }

    // A deactivated Artist may still withdraw: it ends an offer rather than making one (§19.6).
    @Override
    public CommissionQuote withdraw(Long userId, Long commissionId, Long quoteId) {
        com.exe101.entity.Commission commission = lock(commissionId);
        requireArtist(userId, commission);
        com.exe101.entity.CommissionQuote quote = requireOffered(commission, quoteId);
        quote.setStatus(QuoteStatus.WITHDRAWN.name());
        com.exe101.entity.CommissionQuote saved = quoteRepository.saveAndFlush(quote);
        changeStatus(commission, CommissionStatus.REQUESTED, "QUOTE_WITHDRAWN", quoteId, userId, now());
        log.info("[COMMISSION] - ACTION: withdrawQuote: commissionId: {}: quoteId: {}", commissionId, quoteId);

        return mapper.toModel(saved);
    }

    // The Artist may decline a request until a Quote is accepted; an offer still open is withdrawn with it.
    @Override
    public Commission declineRequest(Long userId, Long commissionId) {
        com.exe101.entity.Commission commission = lock(commissionId);
        requireArtist(userId, commission);
        CommissionStatus status = requireOpen(commission);

        if (status != CommissionStatus.REQUESTED && status != CommissionStatus.QUOTED) {
            throw new AppException(AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
        }

        OffsetDateTime declinedAt = now();
        List<com.exe101.entity.CommissionQuote> offered =
            quoteRepository.findByCommissionIdAndStatus(commissionId, QuoteStatus.OFFERED.name());

        for (com.exe101.entity.CommissionQuote quote : offered) {
            quote.setStatus(QuoteStatus.WITHDRAWN.name());
            quoteRepository.saveAndFlush(quote);
            eventRepository.saveAndFlush(new CommissionEvent(commissionId, "QUOTE_WITHDRAWN", null, null,
                quote.getQuoteId(), userId, null, declinedAt, null));
        }

        changeStatus(commission, CommissionStatus.DECLINED, CommissionStatus.DECLINED.name(), null, userId,
            declinedAt);
        log.info("[COMMISSION] - ACTION: declineRequest: commissionId: {}", commissionId);

        return commissionMapper.toModel(commission);
    }

    private com.exe101.entity.Commission lock(Long commissionId) {
        return repository.findWithLockByCommissionId(commissionId)
            .orElseThrow(() -> new AppException(AppErrorMessage.COMMISSION_NOT_FOUND));
    }

    private com.exe101.entity.CommissionQuote requireOffered(com.exe101.entity.Commission commission, Long quoteId) {
        com.exe101.entity.CommissionQuote quote = quoteRepository
            .findByQuoteIdAndCommissionId(quoteId, commission.getCommissionId())
            .orElseThrow(() -> new AppException(AppErrorMessage.QUOTE_NOT_FOUND));

        if (!QuoteStatus.OFFERED.name().equals(quote.getStatus())) {
            throw new AppException(AppErrorMessage.QUOTE_NOT_OFFERED);
        }

        // A dispute interrupts an open offer: nobody answers it until the Admin resolves the dispute.
        if (!CommissionStatus.QUOTED.name().equals(commission.getStatus())) {
            throw new AppException(AppErrorMessage.COMMISSION_ACTION_NOT_ALLOWED);
        }

        return quote;
    }

    // While a cancellation request is pending, the formal actions it would conflict with wait for its answer (§24.8).
    private void requireNoPendingCancellation(Long commissionId) {
        if (cancellationRepository.existsByCommissionIdAndStatus(commissionId, CancellationStatus.PENDING.name())) {
            throw new AppException(AppErrorMessage.CANCELLATION_PENDING);
        }
    }

    private void changeStatus(
        com.exe101.entity.Commission commission, CommissionStatus to, String eventType, Long relatedId, Long userId,
        OffsetDateTime occurredAt
    ) {
        String from = commission.getStatus();
        commission.setStatus(to.name());
        repository.saveAndFlush(commission);
        eventRepository.saveAndFlush(new CommissionEvent(commission.getCommissionId(), eventType, from, to.name(),
            relatedId, userId, null, occurredAt, null));
    }

    // Only the Customer and the commissioned Artist see a Commission; to anyone else it does not exist.
    private boolean isArtist(Long userId, com.exe101.entity.Commission commission) {
        if (commission.getCustomerId() == userId) {
            return false;
        }

        Artist artist = artistService.get(commission.getArtistId());

        if (!artist.getUserId().equals(userId)) {
            throw new AppException(AppErrorMessage.COMMISSION_NOT_FOUND);
        }

        return true;
    }

    private void requireArtist(Long userId, com.exe101.entity.Commission commission) {
        if (!isArtist(userId, commission)) {
            throw new AppException(AppErrorMessage.ACCESS_DENIED);
        }
    }

    private void requireCustomer(Long userId, com.exe101.entity.Commission commission) {
        if (isArtist(userId, commission)) {
            throw new AppException(AppErrorMessage.ACCESS_DENIED);
        }
    }

    private void requireActiveArtist(com.exe101.entity.Commission commission) {
        Artist artist = artistService.lockForCommission(commission.getArtistId());

        if (!activeArtist.equals(artist.getStatus())) {
            throw new AppException(AppErrorMessage.ARTIST_NOT_ACTIVE);
        }
    }

    private CommissionStatus requireOpen(com.exe101.entity.Commission commission) {
        CommissionStatus status = CommissionStatus.valueOf(commission.getStatus());

        if (status.isClosed()) {
            throw new AppException(AppErrorMessage.COMMISSION_CLOSED);
        }

        return status;
    }

    // Whole VND above 0 (Q02: an initial Quote of 0 VND is refused) and below the decimal(19,4) limit. The tax rate
    // is below 1, so the tax stays below the limit too.
    private BigDecimal requirePrice(BigDecimal price) {
        boolean valid = price != null && price.signum() > 0 && price.stripTrailingZeros().scale() <= 0
            && price.compareTo(amountLimit) < 0;

        if (!valid) {
            throw new AppException(AppErrorMessage.QUOTE_INVALID);
        }

        return price;
    }

    private String requireText(String value, int maxLength) {
        String text = value == null ? "" : value.strip();

        if (text.isEmpty() || text.length() > maxLength) {
            throw new AppException(AppErrorMessage.QUOTE_INVALID);
        }

        return text;
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(moneyScale);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
