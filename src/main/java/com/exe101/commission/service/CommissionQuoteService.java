package com.exe101.commission.service;

import com.exe101.artist.model.ArtistTerms;
import com.exe101.commission.model.Commission;
import com.exe101.commission.model.CommissionQuote;
import com.exe101.commission.model.QuoteTerms;
import java.util.List;

public interface CommissionQuoteService {
    List<CommissionQuote> list(Long userId, Long commissionId);

    CommissionQuote offer(Long userId, Long commissionId, QuoteTerms terms);

    CommissionQuote accept(Long userId, Long commissionId, Long quoteId, Long artistTermsId);

    CommissionQuote decline(Long userId, Long commissionId, Long quoteId);

    CommissionQuote withdraw(Long userId, Long commissionId, Long quoteId);

    Commission declineRequest(Long userId, Long commissionId);

    ArtistTerms getAcceptedTerms(Long userId, Long commissionId, Long quoteId);

    ArtistTerms getAcceptedTermsAsAdmin(Long commissionId, Long quoteId);
}
