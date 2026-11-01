package com.exe101.order.mapper;

import com.exe101.address.model.ShippingAddress;
import com.exe101.entity.MarketplaceOrder;
import com.exe101.entity.OrderItem;
import com.exe101.entity.OrderItemAsset;
import com.exe101.entity.OrderShippingAddress;
import com.exe101.entity.Payment;
import com.exe101.entity.PhysicalFulfillment;
import com.exe101.order.model.ItemFulfillment;
import com.exe101.order.model.Order;
import com.exe101.order.model.OrderAsset;
import com.exe101.order.model.OrderLine;
import com.exe101.order.model.PaymentTarget;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OrderEntityMapper {
    // TotalAmount is computed by SQL Server; RowVer is generated.
    @Mapping(target = "orderId", ignore = true)
    @Mapping(target = "totalAmount", ignore = true)
    @Mapping(target = "rowVer", ignore = true)
    @Mapping(source = "customerName", target = "customerNameSnapshot")
    @Mapping(source = "customerEmail", target = "customerEmailSnapshot")
    MarketplaceOrder toEntity(Order order);

    @Mapping(source = "orderId", target = "withOrderId")
    @Mapping(source = "customerId", target = "withCustomerId")
    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "createdAt", target = "withCreatedAt")
    @Mapping(source = "expiresAt", target = "withExpiresAt")
    @Mapping(source = "paidAt", target = "withPaidAt")
    @Mapping(source = "currencyCode", target = "withCurrencyCode")
    @Mapping(source = "itemsSubtotal", target = "withItemsSubtotal")
    @Mapping(source = "shippingAmount", target = "withShippingAmount")
    @Mapping(source = "taxAmount", target = "withTaxAmount")
    @Mapping(source = "totalAmount", target = "withTotalAmount")
    @Mapping(source = "billingName", target = "withBillingName")
    @Mapping(source = "billingDetails", target = "withBillingDetails")
    @Mapping(source = "customerNameSnapshot", target = "withCustomerName")
    @Mapping(source = "customerEmailSnapshot", target = "withCustomerEmail")
    @Mapping(target = "withItems", ignore = true)
    @Mapping(target = "withShippingAddress", ignore = true)
    @Mapping(target = "withPayment", ignore = true)
    Order toModel(MarketplaceOrder entity);

    // LineSubtotal is computed by SQL Server.
    @Mapping(target = "orderItemId", ignore = true)
    @Mapping(target = "lineSubtotal", ignore = true)
    @Mapping(source = "orderId", target = "orderId")
    OrderItem toEntity(OrderLine line, Long orderId);

    @Mapping(source = "orderItemId", target = "withOrderItemId")
    @Mapping(source = "listingId", target = "withListingId")
    @Mapping(source = "listingType", target = "withListingType")
    @Mapping(source = "artworkId", target = "withArtworkId")
    @Mapping(source = "artistId", target = "withArtistId")
    @Mapping(source = "artworkTitle", target = "withArtworkTitle")
    @Mapping(source = "listingName", target = "withListingName")
    @Mapping(source = "artistName", target = "withArtistName")
    @Mapping(source = "specifications", target = "withSpecifications")
    @Mapping(source = "quantity", target = "withQuantity")
    @Mapping(source = "currencyCode", target = "withCurrencyCode")
    @Mapping(source = "unitPrice", target = "withUnitPrice")
    @Mapping(source = "manufacturingCostPerUnit", target = "withManufacturingCostPerUnit")
    @Mapping(source = "markupPerUnit", target = "withMarkupPerUnit")
    @Mapping(source = "feeRate", target = "withFeeRate")
    @Mapping(source = "feeAmount", target = "withFeeAmount")
    @Mapping(source = "artistProceeds", target = "withArtistProceeds")
    @Mapping(source = "taxAmount", target = "withTaxAmount")
    @Mapping(source = "lineSubtotal", target = "withLineSubtotal")
    @Mapping(target = "withAssets", ignore = true)
    @Mapping(target = "withFulfillment", ignore = true)
    @Mapping(target = "withRefundStatus", ignore = true)
    OrderLine toModel(OrderItem entity);

    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "carrier", target = "withCarrier")
    @Mapping(source = "trackingReference", target = "withTrackingReference")
    @Mapping(source = "deliveredAt", target = "withDeliveredAt")
    ItemFulfillment toModel(PhysicalFulfillment entity);

    @Mapping(source = "asset.listingAssetId", target = "id.listingAssetId")
    @Mapping(source = "orderItemId", target = "id.orderItemId")
    @Mapping(source = "listingId", target = "listingId")
    @Mapping(source = "asset.fileId", target = "fileId")
    OrderItemAsset toEntity(OrderAsset asset, Long orderItemId, Long listingId);

    @Mapping(source = "id.listingAssetId", target = "withListingAssetId")
    @Mapping(source = "fileId", target = "withFileId")
    OrderAsset toModel(OrderItemAsset entity);

    @Mapping(source = "orderId", target = "orderId")
    @Mapping(source = "address.sourceAddressId", target = "sourceAddressId")
    @Mapping(source = "address.recipientName", target = "recipientName")
    @Mapping(source = "address.phone", target = "phone")
    @Mapping(source = "address.line1", target = "line1")
    @Mapping(source = "address.line2", target = "line2")
    @Mapping(source = "address.ward", target = "ward")
    @Mapping(source = "address.district", target = "district")
    @Mapping(source = "address.provinceCode", target = "provinceCode")
    @Mapping(source = "address.provinceName", target = "provinceName")
    @Mapping(source = "address.postalCode", target = "postalCode")
    @Mapping(source = "address.countryCode", target = "countryCode")
    OrderShippingAddress toEntity(ShippingAddress address, Long orderId);

    @Mapping(source = "sourceAddressId", target = "withSourceAddressId")
    @Mapping(source = "recipientName", target = "withRecipientName")
    @Mapping(source = "phone", target = "withPhone")
    @Mapping(source = "line1", target = "withLine1")
    @Mapping(source = "line2", target = "withLine2")
    @Mapping(source = "ward", target = "withWard")
    @Mapping(source = "district", target = "withDistrict")
    @Mapping(source = "provinceCode", target = "withProvinceCode")
    @Mapping(source = "provinceName", target = "withProvinceName")
    @Mapping(source = "postalCode", target = "withPostalCode")
    @Mapping(source = "countryCode", target = "withCountryCode")
    ShippingAddress toModel(OrderShippingAddress entity);

    // An Order Payment has no Quote and no installment; SucceededAt is set by F11.
    @Mapping(target = "paymentId", ignore = true)
    @Mapping(target = "quoteId", ignore = true)
    @Mapping(target = "installment", ignore = true)
    @Mapping(target = "succeededAt", ignore = true)
    @Mapping(target = "rowVer", ignore = true)
    Payment toEntity(PaymentTarget payment);

    @Mapping(source = "paymentId", target = "withPaymentId")
    @Mapping(source = "orderId", target = "withOrderId")
    @Mapping(source = "amount", target = "withAmount")
    @Mapping(source = "currencyCode", target = "withCurrencyCode")
    @Mapping(source = "status", target = "withStatus")
    @Mapping(source = "createdAt", target = "withCreatedAt")
    PaymentTarget toModel(Payment entity);
}
