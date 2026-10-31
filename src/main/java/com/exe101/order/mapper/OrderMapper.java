package com.exe101.order.mapper;

import com.exe101.address.model.ShippingAddress;
import com.exe101.order.dto.CreateOrderDTO;
import com.exe101.order.dto.CreateOrderLineDTO;
import com.exe101.order.dto.OrderDTO;
import com.exe101.order.dto.OrderItemDTO;
import com.exe101.order.dto.OrderShippingAddressDTO;
import com.exe101.order.dto.PaymentTargetDTO;
import com.exe101.order.model.CommitLine;
import com.exe101.order.model.Order;
import com.exe101.order.model.OrderAsset;
import com.exe101.order.model.OrderCommit;
import com.exe101.order.model.OrderLine;
import com.exe101.order.model.PaymentTarget;
import java.util.Base64;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OrderMapper {
    @Mapping(source = "cartVersion", target = "withCartVersion", qualifiedByName = "decodeVersion")
    @Mapping(source = "addressId", target = "withAddressId")
    @Mapping(source = "lines", target = "withLines")
    @Mapping(source = "totalAmount", target = "withTotalAmount")
    OrderCommit toModel(CreateOrderDTO dto);

    @Mapping(source = "listingId", target = "withListingId")
    @Mapping(source = "quantity", target = "withQuantity")
    @Mapping(source = "unitPrice", target = "withUnitPrice")
    CommitLine toModel(CreateOrderLineDTO dto);

    OrderDTO toDto(Order order);

    List<OrderDTO> toDto(List<Order> orders);

    @Mapping(source = "assets", target = "assetIds", qualifiedByName = "assetIds")
    OrderItemDTO toDto(OrderLine line);

    OrderShippingAddressDTO toDto(ShippingAddress address);

    PaymentTargetDTO toDto(PaymentTarget payment);

    // The pattern on CreateOrderDTO admits only valid Base64, so decoding cannot fail here.
    @Named("decodeVersion")
    default byte[] decodeVersion(String version) {
        if (version == null) {
            return null;
        }

        return Base64.getDecoder().decode(version);
    }

    @Named("assetIds")
    default List<Long> assetIds(List<OrderAsset> assets) {
        if (assets == null) {
            return List.of();
        }

        return assets.stream().map(OrderAsset::getListingAssetId).toList();
    }
}
