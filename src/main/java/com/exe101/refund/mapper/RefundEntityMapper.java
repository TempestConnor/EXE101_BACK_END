package com.exe101.refund.mapper;

import com.exe101.entity.RefundRequest;
import com.exe101.entity.RefundTransfer;
import com.exe101.entity.ShippingRefund;
import com.exe101.refund.model.Refund;
import com.exe101.refund.model.Transfer;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RefundEntityMapper {
    @Mapping(target = "refundRequestId", ignore = true)
    RefundRequest toEntity(Refund refund);

    @Mapping(source = "refundRequestId", target = "withRefundRequestId")
    @Mapping(source = "orderItemId", target = "withOrderItemId")
    @Mapping(source = "commissionId", target = "withCommissionId")
    @Mapping(source = "requestedByUserId", target = "withRequestedByUserId")
    @Mapping(source = "reason", target = "withReason")
    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "requestedAt", target = "withRequestedAt")
    @Mapping(source = "decidedAt", target = "withDecidedAt")
    @Mapping(source = "decidedByStaffId", target = "withDecidedByStaffId")
    @Mapping(source = "decisionReason", target = "withDecisionReason")
    @Mapping(source = "refundPercent", target = "withRefundPercent")
    @Mapping(target = "withShippingAmount", ignore = true)
    @Mapping(target = "withTransfer", ignore = true)
    Refund toModel(RefundRequest entity);

    // The transfer is made by hand, so its reference is recorded under the MANUAL provider when it succeeds.
    @Mapping(target = "refundTransferId", ignore = true)
    @Mapping(target = "providerName", ignore = true)
    @Mapping(source = "transferReference", target = "providerRefundRef")
    RefundTransfer toEntity(Transfer transfer);

    @Mapping(source = "refundTransferId", target = "withTransferId")
    @Mapping(source = "refundRequestId", target = "withRefundRequestId")
    @Mapping(source = "paymentId", target = "withPaymentId")
    @Mapping(source = "amount", target = "withAmount")
    @Mapping(source = "currencyCode", target = "withCurrencyCode")
    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "providerRefundRef", target = "withTransferReference")
    @Mapping(source = "createdAt", target = "withCreatedAt")
    @Mapping(source = "succeededAt", target = "withSucceededAt")
    Transfer toModel(RefundTransfer entity);

    ShippingRefund toEntity(Long orderId, Long refundRequestId, BigDecimal amount, OffsetDateTime createdAt);
}
