package com.exe101.entitlement.service;

import com.exe101.entitlement.mapper.EntitlementEntityMapper;
import com.exe101.entitlement.mapper.EntitlementEntityMapperImpl;
import com.exe101.entitlement.model.Entitlement;
import com.exe101.entitlement.model.Purchase;
import com.exe101.entitlement.model.PurchasedFile;
import com.exe101.entitlement.repository.EntitlementAssetRepository;
import com.exe101.entitlement.repository.EntitlementRepository;
import com.exe101.entity.DigitalEntitlement;
import com.exe101.entity.EntitlementAsset;
import com.exe101.entity.EntitlementAssetId;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Lenient: each test uses part of the shared persistence.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntitlementServiceTest {
    private static long buyerId = 42L;
    private static long otherId = 43L;
    private static long orderId = 300L;
    @Mock
    private EntitlementRepository repository;
    @Mock
    private EntitlementAssetRepository assetRepository;
    @Mock
    private OrderService orderService;
    @Mock
    private FileService fileService;
    @Spy
    private EntitlementEntityMapper mapper = new EntitlementEntityMapperImpl();
    @InjectMocks
    private EntitlementServiceImpl service;
    private List<DigitalEntitlement> savedEntitlements;
    private List<EntitlementAsset> savedAssets;

    @BeforeEach
    void setUp() {
        savedEntitlements = new ArrayList<>();
        savedAssets = new ArrayList<>();
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            DigitalEntitlement saved = invocation.getArgument(0);
            saved.setEntitlementId(900L + savedEntitlements.size());
            savedEntitlements.add(saved);
            return saved;
        });
        when(assetRepository.saveAllAndFlush(anyList())).thenAnswer(invocation -> {
            List<EntitlementAsset> assets = invocation.getArgument(0);
            savedAssets.addAll(assets);
            return assets;
        });
    }

    @Test
    void grant_digital_items_on_paid_order_ok() {
        // given
        var order = order(orderId, OrderStatus.PAID, List.of(line(310L, ListingType.DIGITAL, asset(30L, 100L),
            asset(31L, 101L))));

        // when
        var granted = service.grantDigital(order);

        // then
        var entitlement = savedEntitlements.getFirst();
        assertAll(
            () -> assertThat(granted).extracting(Entitlement::getEntitlementId).containsExactly(900L),
            () -> assertThat(savedEntitlements).hasSize(1),
            () -> assertThat(entitlement.getOrderItemId()).isEqualTo(310L),
            () -> assertThat(entitlement.getCustomerId()).isEqualTo(buyerId),
            () -> assertThat(entitlement.getGrantedAt()).isNotNull(),
            () -> assertThat(savedAssets).extracting(asset -> asset.getId().getEntitlementId()).containsOnly(900L),
            () -> assertThat(savedAssets).extracting(asset -> asset.getId().getListingAssetId())
                .containsExactly(30L, 31L),
            () -> assertThat(savedAssets).extracting(EntitlementAsset::getOrderItemId).containsOnly(310L)
        );
    }

    @Test
    void grant_skips_physical_items_ok() {
        // given
        var order = order(orderId, OrderStatus.PAID, List.of(line(310L, ListingType.DIGITAL, asset(30L, 100L)),
            line(311L, ListingType.PHYSICAL, asset(40L, 200L))));

        // when
        var granted = service.grantDigital(order);

        // then
        assertAll(
            () -> assertThat(granted).extracting(Entitlement::getOrderItemId).containsExactly(310L),
            () -> assertThat(savedEntitlements).extracting(DigitalEntitlement::getOrderItemId).containsExactly(310L),
            () -> assertThat(savedAssets).extracting(asset -> asset.getId().getListingAssetId())
                .containsExactly(30L)
        );
    }

    @Test
    void grant_already_granted_item_ok() {
        // given
        var order = order(orderId, OrderStatus.PAID, List.of(line(310L, ListingType.DIGITAL, asset(30L, 100L))));
        when(repository.existsByOrderItemId(310L)).thenReturn(true);

        // when
        var granted = service.grantDigital(order);

        // then
        assertThat(granted).isEmpty();
        verify(repository, never()).saveAndFlush(any());
        verify(assetRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void grant_unpaid_order_ko() {
        // given
        var order = order(orderId, OrderStatus.PENDING_PAYMENT, List.of(line(310L, ListingType.DIGITAL,
            asset(30L, 100L))));

        // when / then
        assertThatThrownBy(() -> service.grantDigital(order)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).saveAndFlush(any());
        verify(assetRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void already_granted_skipped_ok() {
        // given
        // The return window of the physical item has closed, but its files were already granted.
        var order = order(orderId, OrderStatus.PAID, List.of(line(311L, ListingType.PHYSICAL, asset(40L, 200L))));
        when(repository.existsByOrderItemId(311L)).thenReturn(true);

        // when
        var granted = service.grantIncluded(order, 311L);

        // then
        assertThat(granted).isEmpty();
        verify(repository, never()).saveAndFlush(any());
        verify(assetRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void list_purchases_returns_own_entitlements_ok() {
        // given
        // The newer entitlement covers one of its item's two recorded files.
        when(repository.findByCustomerIdOrderByGrantedAtDescEntitlementIdDesc(buyerId)).thenReturn(List.of(
            entitlement(901L, 320L, buyerId, now().minusDays(1)), entitlement(900L, 310L, buyerId, now().minusDays(2))));
        when(repository.findByCustomerIdOrderByGrantedAtDescEntitlementIdDesc(otherId))
            .thenReturn(List.of(entitlement(902L, 330L, otherId, now())));
        when(assetRepository.findByIdEntitlementIdIn(anyCollection())).thenReturn(List.of(
            granted(900L, 30L, 310L), granted(901L, 50L, 320L)));
        when(orderService.listOwn(buyerId)).thenReturn(List.of(
            order(301L, OrderStatus.PAID, List.of(line(320L, ListingType.DIGITAL, asset(50L, 102L),
                asset(51L, 103L)))),
            order(orderId, OrderStatus.PAID, List.of(line(310L, ListingType.DIGITAL, asset(30L, 100L))))));
        when(fileService.describe(anyCollection())).thenReturn(List.of(file(100L, "sunset.psd"),
            file(102L, "dawn.psd"), file(103L, "dawn-extra.psd")));

        // when
        var purchases = service.listOwn(buyerId);

        // then
        var newest = purchases.getFirst();
        var newestFile = newest.getFiles().getFirst();
        assertAll(
            () -> assertThat(purchases).extracting(Purchase::getEntitlementId).containsExactly(901L, 900L),
            () -> assertThat(newest.getOrderId()).isEqualTo(301L),
            () -> assertThat(newest.getOrderItemId()).isEqualTo(320L),
            () -> assertThat(newest.getListingId()).isEqualTo(20L),
            () -> assertThat(newest.getListingType()).isEqualTo(ListingType.DIGITAL),
            () -> assertThat(newest.getArtworkTitle()).isEqualTo("Sunset"),
            () -> assertThat(newest.getListingName()).isEqualTo("Sunset download"),
            () -> assertThat(newest.getArtistName()).isEqualTo("Painter"),
            () -> assertThat(newest.getFiles()).extracting(PurchasedFile::getAssetId).containsExactly(50L),
            () -> assertThat(newestFile.getFileName()).isEqualTo("dawn.psd"),
            () -> assertThat(newestFile.getContentType()).isEqualTo("image/vnd.adobe.photoshop"),
            () -> assertThat(newestFile.getByteLength()).isEqualTo(2048L),
            () -> assertThat(purchases.get(1).getOrderId()).isEqualTo(orderId),
            () -> assertThat(purchases.get(1).getFiles()).extracting(PurchasedFile::getFileName)
                .containsExactly("sunset.psd")
        );
        verify(repository, never()).findByCustomerIdOrderByGrantedAtDescEntitlementIdDesc(otherId);
    }

    @Test
    void list_purchases_empty_ok() {
        // given
        when(repository.findByCustomerIdOrderByGrantedAtDescEntitlementIdDesc(buyerId)).thenReturn(List.of());

        // when
        var purchases = service.listOwn(buyerId);

        // then
        assertThat(purchases).isEmpty();
        verifyNoInteractions(orderService, fileService);
    }

    private Order order(long id, OrderStatus status, List<OrderLine> items) {
        return Order.builder()
            .withOrderId(id)
            .withCustomerId(buyerId)
            .withStatus(status)
            .withItems(items)
            .build();
    }

    private OrderLine line(long orderItemId, ListingType listingType, OrderAsset... assets) {
        return OrderLine.builder()
            .withOrderItemId(orderItemId)
            .withListingId(20L)
            .withListingType(listingType)
            .withArtworkTitle("Sunset")
            .withListingName("Sunset download")
            .withArtistName("Painter")
            .withQuantity(1)
            .withAssets(List.of(assets))
            .build();
    }

    private OrderAsset asset(long listingAssetId, long fileId) {
        return OrderAsset.builder().withListingAssetId(listingAssetId).withFileId(fileId).build();
    }

    private DigitalEntitlement entitlement(long entitlementId, long orderItemId, long customerId,
        OffsetDateTime grantedAt) {
        var entity = new DigitalEntitlement(orderItemId, customerId, grantedAt);
        entity.setEntitlementId(entitlementId);

        return entity;
    }

    private EntitlementAsset granted(long entitlementId, long listingAssetId, long orderItemId) {
        return new EntitlementAsset(new EntitlementAssetId(entitlementId, listingAssetId), orderItemId);
    }

    private StoredFileInfo file(long fileId, String originalName) {
        return StoredFileInfo.builder()
            .withFileId(fileId)
            .withOriginalName(originalName)
            .withContentType("image/vnd.adobe.photoshop")
            .withByteLength(2048)
            .withAccessClass("PURCHASED")
            .build();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
