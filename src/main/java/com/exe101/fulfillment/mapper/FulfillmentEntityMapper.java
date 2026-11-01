package com.exe101.fulfillment.mapper;

import com.exe101.entity.FulfillmentEvent;
import com.exe101.entity.PhysicalFulfillment;
import com.exe101.fulfillment.model.Fulfillment;
import com.exe101.fulfillment.model.FulfillmentStep;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface FulfillmentEntityMapper {
    // Only physical items are fulfilled; RowVer is generated.
    @Mapping(target = "listingType", constant = "PHYSICAL")
    @Mapping(target = "rowVer", ignore = true)
    PhysicalFulfillment toEntity(Fulfillment fulfillment);

    @Mapping(source = "orderItemId", target = "withOrderItemId")
    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "carrier", target = "withCarrier")
    @Mapping(source = "trackingReference", target = "withTrackingReference")
    @Mapping(source = "deliveredAt", target = "withDeliveredAt")
    @Mapping(source = "updatedAt", target = "withUpdatedAt")
    Fulfillment toModel(PhysicalFulfillment entity);

    @Mapping(target = "eventId", ignore = true)
    FulfillmentEvent toEntity(FulfillmentStep step);
}
