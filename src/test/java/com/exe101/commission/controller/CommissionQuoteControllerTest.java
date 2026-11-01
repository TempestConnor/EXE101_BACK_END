package com.exe101.commission.controller;

import com.exe101.commission.mapper.CommissionMapperImpl;
import com.exe101.commission.mapper.CommissionQuoteEntityMapperImpl;
import com.exe101.commission.mapper.CommissionQuoteMapperImpl;
import com.exe101.commission.model.CommissionQuote;
import com.exe101.commission.model.QuoteStatus;
import com.exe101.commission.service.CommissionQuoteService;
import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.model.Customer;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.security.StaffAuthenticationConverter;
import com.exe101.staff.security.StaffTokenIssuer;
import com.exe101.staff.service.StaffService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CommissionQuoteController.class)
@Import({CommissionQuoteMapperImpl.class, CommissionMapperImpl.class, StaffTokenIssuer.class,
    StaffTokenConfiguration.class, StaffAuthenticationConverter.class, CustomerTokenIssuer.class,
    CustomerTokenConfiguration.class, CustomerAuthenticationConverter.class, SecurityConfiguration.class,
    SecurityProblemHandler.class, GlobalExceptionHandler.class})
class CommissionQuoteControllerTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @MockitoBean
    private CommissionQuoteService quotes;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void offer_quote_returns_201_ok() throws Exception {
        // given
        var token = customerToken();
        when(quotes.offer(eq(42L), eq(3L), any())).thenReturn(quote(QuoteStatus.OFFERED));

        // when / then
        mvc.perform(post("/commissions/3/quotes").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"price":1500000,"scopeText":"Full-body portrait","expectedCompletionTerms":"3 weeks"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.quoteId").value(21))
            .andExpect(jsonPath("$.commissionId").value(3))
            .andExpect(jsonPath("$.sequenceNo").value(1))
            .andExpect(jsonPath("$.scopeText").value("Full-body portrait"))
            .andExpect(jsonPath("$.expectedCompletionTerms").value("3 weeks"))
            .andExpect(jsonPath("$.price").value(1500000))
            .andExpect(jsonPath("$.taxAmount").value(150000))
            .andExpect(jsonPath("$.totalAmount").value(1650000))
            .andExpect(jsonPath("$.currencyCode").value("VND"))
            .andExpect(jsonPath("$.status").value("OFFERED"))
            .andExpect(jsonPath("$.acceptedAt").doesNotExist())
            .andExpect(jsonPath("$.artistProceeds").doesNotExist());
    }

    @Test
    void offer_quote_missing_fields_returns_400_ko() throws Exception {
        // given
        var token = customerToken();

        // when / then
        mvc.perform(post("/commissions/3/quotes").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                    {"scopeText":"  "}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
        verifyNoInteractions(quotes);
    }

    @Test
    void accept_quote_returns_200_ok() throws Exception {
        // given
        var token = customerToken();
        when(quotes.accept(42L, 3L, 21L)).thenReturn(quote(QuoteStatus.ACCEPTED));

        // when / then
        mvc.perform(post("/commissions/3/quotes/21/accept").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.quoteId").value(21))
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.totalAmount").value(1650000));
    }

    @Test
    void quote_response_shows_installment_amounts_ok() throws Exception {
        // given
        // 100 001 + 10 001 tax: the first payment takes the odd dong of each (approved 2 November 2026).
        var token = customerToken();
        var stored = new com.exe101.entity.CommissionQuote(3L, 1, "Full-body portrait", "3 weeks",
            new BigDecimal("100001.0000"), new BigDecimal("10001.0000"), "VND", "ACCEPTED",
            OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.UTC));
        stored.setQuoteId(21L);
        when(quotes.list(42L, 3L)).thenReturn(List.of(new CommissionQuoteEntityMapperImpl().toModel(stored)));

        // when / then
        mvc.perform(get("/commissions/3/quotes").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].totalAmount").value(110002))
            .andExpect(jsonPath("$[0].firstPaymentAmount").value(55002))
            .andExpect(jsonPath("$[0].finalPaymentAmount").value(55000));
    }

    @Test
    void quote_conflict_returns_409_ko() throws Exception {
        // given
        var token = customerToken();
        when(quotes.withdraw(42L, 3L, 21L)).thenThrow(new AppException(AppErrorMessage.QUOTE_NOT_OFFERED));

        // when / then
        mvc.perform(post("/commissions/3/quotes/21/withdraw").header("Authorization", "Bearer " + token))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("QUOTE_NOT_OFFERED"));
    }

    private CommissionQuote quote(QuoteStatus status) {
        var offeredAt = OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.UTC);
        return CommissionQuote.builder()
            .withQuoteId(21L)
            .withCommissionId(3L)
            .withSequenceNo(1)
            .withScopeText("Full-body portrait")
            .withExpectedCompletionTerms("3 weeks")
            .withPrice(new BigDecimal("1500000.0000"))
            .withTaxAmount(new BigDecimal("150000.0000"))
            .withCurrencyCode("VND")
            .withStatus(status)
            .withOfferedAt(offeredAt)
            .withAcceptedAt(status == QuoteStatus.ACCEPTED ? offeredAt.plusHours(1) : null)
            .build();
    }

    private String customerToken() {
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());

        return customerIssuer.issue(42L).getAccessToken();
    }
}
