package com.exe101.payment.mapper;

import com.exe101.payment.dto.PaymentAttemptDTO;
import com.exe101.payment.dto.QuotePaymentAttemptDTO;
import com.exe101.payment.dto.VnpayIpnResponseDTO;
import com.exe101.payment.model.PaymentStart;
import com.exe101.payment.model.VnpayReply;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PaymentMapper {
    PaymentAttemptDTO toDto(PaymentStart start);

    QuotePaymentAttemptDTO toQuoteDto(PaymentStart start);

    @Mapping(source = "code", target = "rspCode")
    @Mapping(source = "message", target = "message")
    VnpayIpnResponseDTO toDto(VnpayReply reply);
}
