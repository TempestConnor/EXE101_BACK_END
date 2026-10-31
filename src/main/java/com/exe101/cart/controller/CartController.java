package com.exe101.cart.controller;

import com.exe101.cart.dto.AddCartItemDTO;
import com.exe101.cart.dto.CartDTO;
import com.exe101.cart.dto.CheckoutPreviewDTO;
import com.exe101.cart.dto.UpdateCartItemDTO;
import com.exe101.cart.mapper.CartMapper;
import com.exe101.cart.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {
    private final CartService cartService;
    private final CartMapper mapper;

    @GetMapping
    public CartDTO get(@AuthenticationPrincipal Long userId) {
        var cart = cartService.get(userId);
        var response = mapper.toDto(cart);

        return response;
    }

    @PostMapping("/items")
    public CartDTO addItem(@RequestBody @Valid AddCartItemDTO dto, @AuthenticationPrincipal Long userId) {
        var change = mapper.toModel(dto);
        var cart = cartService.addItem(userId, change);
        var response = mapper.toDto(cart);

        return response;
    }

    @PutMapping("/items/{listingId}")
    public CartDTO updateItem(
        @PathVariable Long listingId, @RequestBody @Valid UpdateCartItemDTO dto, @AuthenticationPrincipal Long userId
    ) {
        var cart = cartService.updateItem(userId, listingId, dto.quantity());
        var response = mapper.toDto(cart);

        return response;
    }

    @DeleteMapping("/items/{listingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(@PathVariable Long listingId, @AuthenticationPrincipal Long userId) {
        cartService.removeItem(userId, listingId);
    }

    @GetMapping("/checkout-preview")
    public CheckoutPreviewDTO preview(
        @RequestParam(required = false) Long addressId, @AuthenticationPrincipal Long userId
    ) {
        var preview = cartService.preview(userId, addressId);
        var response = mapper.toDto(preview);

        return response;
    }
}
