package com.exe101.staff.service;

import com.exe101.staff.model.Staff;
import com.exe101.staff.model.StaffLogin;

public interface StaffService {
    Staff authenticate(StaffLogin login);

    Staff requireActive(Long staffId);

    void bootstrapAdmin(StaffLogin login);
}
