package com.exe101.file.repository;

import com.exe101.entity.EntitlementAsset;
import com.exe101.entity.EntitlementAssetId;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchasedFileRepository extends JpaRepository<EntitlementAsset, EntitlementAssetId> {
    // The file recorded on the Order Item when it was created, reachable only through the buyer's own entitlement.
    @Query("""
        select distinct orderAsset.fileId from EntitlementAsset granted
        join DigitalEntitlement entitlement on entitlement.entitlementId = granted.id.entitlementId
        join OrderItemAsset orderAsset on orderAsset.id.orderItemId = granted.orderItemId
            and orderAsset.id.listingAssetId = granted.id.listingAssetId
        where entitlement.customerId = :customerId and granted.id.listingAssetId = :listingAssetId
        """)
    Optional<Long> findEntitledFileId(
        @Param("customerId") long customerId, @Param("listingAssetId") long listingAssetId);

    @Query("select count(asset) > 0 from ListingAsset asset where asset.listingAssetId = :listingAssetId")
    boolean listingAssetExists(@Param("listingAssetId") long listingAssetId);
}
