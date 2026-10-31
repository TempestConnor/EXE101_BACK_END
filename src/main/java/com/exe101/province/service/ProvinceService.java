package com.exe101.province.service;

import com.exe101.province.model.NewProvince;
import com.exe101.province.model.Province;
import com.exe101.province.model.ProvinceSupportChange;
import java.util.List;

public interface ProvinceService {
    Province create(NewProvince province);

    Province changeSupport(Long provinceId, ProvinceSupportChange change, Long staffId);

    List<Province> listAll();

    List<Province> listSupported();

    Province requireSupported(String provinceCode);

    boolean isSupported(String provinceCode);
}
