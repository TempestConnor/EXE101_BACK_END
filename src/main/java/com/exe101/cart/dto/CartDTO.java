package com.exe101.cart.dto;

import java.util.List;

public record CartDTO(String version, List<CartItemDTO> items) {
}
