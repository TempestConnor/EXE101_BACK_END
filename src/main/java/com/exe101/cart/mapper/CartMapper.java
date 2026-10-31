package com.exe101.cart.mapper;

import com.exe101.cart.dto.AddCartItemDTO;
import com.exe101.cart.dto.CartDTO;
import com.exe101.cart.dto.CartItemDTO;
import com.exe101.cart.dto.CheckoutLineDTO;
import com.exe101.cart.dto.CheckoutPreviewDTO;
import com.exe101.cart.model.Cart;
import com.exe101.cart.model.CartItemChange;
import com.exe101.cart.model.CartLine;
import com.exe101.cart.model.CheckoutLine;
import com.exe101.cart.model.CheckoutPreview;
import java.util.Base64;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CartMapper {
    @Mapping(source = "listingId", target = "withListingId")
    @Mapping(source = "quantity", target = "withQuantity")
    CartItemChange toModel(AddCartItemDTO dto);

    @Mapping(source = "version", target = "version", qualifiedByName = "encodeVersion")
    CartDTO toDto(Cart cart);

    CartItemDTO toDto(CartLine line);

    @Mapping(source = "cartVersion", target = "cartVersion", qualifiedByName = "encodeVersion")
    CheckoutPreviewDTO toDto(CheckoutPreview preview);

    CheckoutLineDTO toDto(CheckoutLine line);

    @Named("encodeVersion")
    default String encodeVersion(byte[] version) {
        if (version == null) {
            return null;
        }

        return Base64.getEncoder().encodeToString(version);
    }
}
