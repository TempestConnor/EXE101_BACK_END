package com.exe101.fulfillment.mapper;

import com.exe101.fulfillment.dto.ChangeFulfillmentStatusDTO;
import com.exe101.fulfillment.dto.FulfillmentDTO;
import com.exe101.fulfillment.model.Fulfillment;
import com.exe101.fulfillment.model.FulfillmentChange;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface FulfillmentMapper {
    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "carrier", target = "withCarrier")
    @Mapping(source = "trackingReference", target = "withTrackingReference")
    @Mapping(source = "reason", target = "withReason")
    FulfillmentChange toModel(ChangeFulfillmentStatusDTO dto);

    FulfillmentDTO toDto(Fulfillment fulfillment);

    List<FulfillmentDTO> toDto(List<Fulfillment> fulfillments);
}
