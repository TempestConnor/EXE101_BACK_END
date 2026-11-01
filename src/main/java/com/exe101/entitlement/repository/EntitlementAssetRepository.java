package com.exe101.entitlement.repository;

import com.exe101.entity.EntitlementAsset;
import com.exe101.entity.EntitlementAssetId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EntitlementAssetRepository extends JpaRepository<EntitlementAsset, EntitlementAssetId> {
    List<EntitlementAsset> findByIdEntitlementIdIn(Collection<Long> entitlementIds);
}
