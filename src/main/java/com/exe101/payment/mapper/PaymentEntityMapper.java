package com.exe101.payment.mapper;

import com.exe101.entity.PaymentAttempt;
import com.exe101.entity.ProviderEvent;
import com.exe101.payment.model.Attempt;
import com.exe101.payment.model.PaymentEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PaymentEntityMapper {
    @Mapping(target = "attemptId", ignore = true)
    PaymentAttempt toEntity(Attempt attempt);

    @Mapping(target = "providerEventId", ignore = true)
    ProviderEvent toEntity(PaymentEvent event);
}
