package com.exe101.entitlement.service;

import com.exe101.entitlement.mapper.EntitlementEntityMapper;
import com.exe101.entitlement.model.Entitlement;
import com.exe101.entitlement.model.Purchase;
import com.exe101.entitlement.model.PurchasedFile;
import com.exe101.entitlement.repository.EntitlementAssetRepository;
import com.exe101.entitlement.repository.EntitlementRepository;
import com.exe101.entity.DigitalEntitlement;
import com.exe101.entity.EntitlementAsset;
import com.exe101.file.model.StoredFileInfo;
import com.exe101.file.service.FileService;
import com.exe101.listing.model.ListingType;
import com.exe101.order.model.Order;
import com.exe101.order.model.OrderAsset;
import com.exe101.order.model.OrderLine;
import com.exe101.order.model.OrderStatus;
import com.exe101.order.service.OrderService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class EntitlementServiceImpl implements EntitlementService {
    private final EntitlementRepository repository;
    private final EntitlementAssetRepository assetRepository;
    private final EntitlementEntityMapper mapper;
    private final OrderService orderService;
    private final FileService fileService;

    // §5.3, §9.10–11: a paid Order grants each direct digital item the files recorded on it, once (§31.2). Physical
    // items wait for their return window to close (amendment §C.10; grantIncluded). The caller holds the Order lock,
    // so a concurrent grant for the same Order waits and then finds the entitlement; UQ(OrderItemId) is the database
    // guard.
    @Override
    public List<Entitlement> grantDigital(Order order) {
        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("Order is not paid: " + order.getOrderId());
        }

        OffsetDateTime grantedAt = now();
        List<Entitlement> granted = order.getItems().stream()
            .filter(line -> line.getListingType() == ListingType.DIGITAL)
            .filter(line -> !repository.existsByOrderItemId(line.getOrderItemId()))
            .map(line -> grant(order.getCustomerId(), line, grantedAt))
            .toList();
        log.info("[ENTITLEMENT] - ACTION: grantDigital: orderId: {}: granted: {}", order.getOrderId(),
            granted.size());

        return granted;
    }

    // Amendment §C.10: a physical item's included files, once its return window has closed. The caller holds the
    // Order lock and has checked the window; an item already granted is skipped, as in grantDigital.
    @Override
    public Optional<Entitlement> grantIncluded(Order order, Long orderItemId) {
        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException("Order is not paid: " + order.getOrderId());
        }

        OrderLine line = order.getItems().stream()
            .filter(item -> item.getOrderItemId().equals(orderItemId))
            .filter(item -> item.getListingType() == ListingType.PHYSICAL)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No physical item " + orderItemId + " in Order "
                + order.getOrderId()));

        if (repository.existsByOrderItemId(orderItemId)) {
            return Optional.empty();
        }

        Entitlement granted = grant(order.getCustomerId(), line, now());
        log.info("[ENTITLEMENT] - ACTION: grantIncluded: orderId: {}: orderItemId: {}: entitlementId: {}",
            order.getOrderId(), orderItemId, granted.getEntitlementId());

        return Optional.of(granted);
    }

    // Every entitlement belongs to one of the caller's own Orders, so the snapshots come from those Orders.
    @Override
    public List<Purchase> listOwn(Long userId) {
        List<DigitalEntitlement> entitlements = repository.findByCustomerIdOrderByGrantedAtDescEntitlementIdDesc(
            userId);

        if (entitlements.isEmpty()) {
            return List.of();
        }

        List<Long> entitlementIds = entitlements.stream().map(DigitalEntitlement::getEntitlementId).toList();
        Map<Long, Set<Long>> grantedAssets = assetRepository.findByIdEntitlementIdIn(entitlementIds).stream()
            .collect(Collectors.groupingBy(asset -> asset.getId().getEntitlementId(),
                Collectors.mapping(asset -> asset.getId().getListingAssetId(), Collectors.toSet())));
        List<Order> orders = orderService.listOwn(userId);
        Map<Long, Long> orderIds = orders.stream()
            .flatMap(order -> order.getItems().stream()
                .map(line -> Map.entry(line.getOrderItemId(), order.getOrderId())))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Map<Long, OrderLine> lines = orders.stream()
            .flatMap(order -> order.getItems().stream())
            .collect(Collectors.toMap(OrderLine::getOrderItemId, Function.identity()));
        List<Long> fileIds = lines.values().stream()
            .flatMap(line -> line.getAssets().stream())
            .map(OrderAsset::getFileId)
            .distinct()
            .toList();
        Map<Long, StoredFileInfo> files = fileService.describe(fileIds).stream()
            .collect(Collectors.toMap(StoredFileInfo::getFileId, Function.identity()));

        return entitlements.stream()
            .map(entitlement -> purchase(entitlement, orderIds, lines,
                grantedAssets.getOrDefault(entitlement.getEntitlementId(), Set.of()), files))
            .toList();
    }

    private Entitlement grant(Long customerId, OrderLine line, OffsetDateTime grantedAt) {
        List<Long> listingAssetIds = line.getAssets().stream().map(OrderAsset::getListingAssetId).toList();
        Entitlement entitlement = Entitlement.builder()
            .withOrderItemId(line.getOrderItemId())
            .withCustomerId(customerId)
            .withGrantedAt(grantedAt)
            .withListingAssetIds(listingAssetIds)
            .build();
        DigitalEntitlement saved = repository.saveAndFlush(mapper.toEntity(entitlement));
        List<EntitlementAsset> assets = listingAssetIds.stream()
            .map(listingAssetId -> mapper.toEntity(saved.getEntitlementId(), listingAssetId, line.getOrderItemId()))
            .toList();
        assetRepository.saveAllAndFlush(assets);
        entitlement.setEntitlementId(saved.getEntitlementId());

        return entitlement;
    }

    private Purchase purchase(
        DigitalEntitlement entitlement, Map<Long, Long> orderIds, Map<Long, OrderLine> lines, Set<Long> grantedAssets,
        Map<Long, StoredFileInfo> files
    ) {
        OrderLine line = lines.get(entitlement.getOrderItemId());

        if (line == null) {
            throw new IllegalStateException("Entitlement outside the buyer's Orders: "
                + entitlement.getEntitlementId());
        }

        List<PurchasedFile> purchasedFiles = line.getAssets().stream()
            .filter(asset -> grantedAssets.contains(asset.getListingAssetId()))
            .map(asset -> purchasedFile(asset, files.get(asset.getFileId())))
            .toList();

        return Purchase.builder()
            .withEntitlementId(entitlement.getEntitlementId())
            .withGrantedAt(entitlement.getGrantedAt())
            .withOrderId(orderIds.get(entitlement.getOrderItemId()))
            .withOrderItemId(entitlement.getOrderItemId())
            .withListingId(line.getListingId())
            .withListingType(line.getListingType())
            .withArtworkTitle(line.getArtworkTitle())
            .withListingName(line.getListingName())
            .withArtistName(line.getArtistName())
            .withFiles(purchasedFiles)
            .build();
    }

    private PurchasedFile purchasedFile(OrderAsset asset, StoredFileInfo file) {
        return PurchasedFile.builder()
            .withAssetId(asset.getListingAssetId())
            .withFileName(file.getOriginalName())
            .withContentType(file.getContentType())
            .withByteLength(file.getByteLength())
            .build();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
