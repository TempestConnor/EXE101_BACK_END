package com.exe101.fulfillment.controller;

import com.exe101.fulfillment.dto.ChangeFulfillmentStatusDTO;
import com.exe101.fulfillment.dto.FulfillmentDTO;
import com.exe101.fulfillment.mapper.FulfillmentMapper;
import com.exe101.fulfillment.model.FulfillmentStatus;
import com.exe101.fulfillment.service.FulfillmentService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/fulfillments")
@RequiredArgsConstructor
public class FulfillmentAdminController {
    private final FulfillmentService fulfillmentService;
    private final FulfillmentMapper mapper;

    @GetMapping
    public List<FulfillmentDTO> list(@RequestParam(required = false) FulfillmentStatus status) {
        var fulfillments = fulfillmentService.list(status);
        var response = mapper.toDto(fulfillments);

        return response;
    }

    @PostMapping("/{orderItemId}/transitions")
    public FulfillmentDTO advance(
        @PathVariable Long orderItemId, @RequestBody @Valid ChangeFulfillmentStatusDTO dto,
        @AuthenticationPrincipal Long staffId
    ) {
        var change = mapper.toModel(dto);
        var updated = fulfillmentService.advance(staffId, orderItemId, change);
        var response = mapper.toDto(updated);

        return response;
    }
}
