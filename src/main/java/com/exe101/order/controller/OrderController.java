package com.exe101.order.controller;

import com.exe101.order.dto.CreateOrderDTO;
import com.exe101.order.dto.OrderDTO;
import com.exe101.order.mapper.OrderMapper;
import com.exe101.order.service.OrderService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;
    private final OrderMapper mapper;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDTO create(@RequestBody @Valid CreateOrderDTO dto, @AuthenticationPrincipal Long userId) {
        var commit = mapper.toModel(dto);
        var order = orderService.create(userId, commit);
        var response = mapper.toDto(order);

        return response;
    }

    @GetMapping("/{orderId}")
    public OrderDTO get(@PathVariable Long orderId, @AuthenticationPrincipal Long userId) {
        var order = orderService.get(userId, orderId);
        var response = mapper.toDto(order);

        return response;
    }

    @GetMapping
    public List<OrderDTO> listOwn(@AuthenticationPrincipal Long userId) {
        var orders = orderService.listOwn(userId);
        var response = mapper.toDto(orders);

        return response;
    }
}
