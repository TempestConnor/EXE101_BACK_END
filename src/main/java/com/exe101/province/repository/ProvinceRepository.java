package com.exe101.province.repository;

import com.exe101.entity.SupportedProvince;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProvinceRepository extends JpaRepository<SupportedProvince, Long> {
    boolean existsByProvinceCode(String provinceCode);

    Optional<SupportedProvince> findByProvinceCode(String provinceCode);

    List<SupportedProvince> findAllByOrderByProvinceCodeAsc();

    List<SupportedProvince> findByIsSupportedTrueOrderByProvinceCodeAsc();
}
