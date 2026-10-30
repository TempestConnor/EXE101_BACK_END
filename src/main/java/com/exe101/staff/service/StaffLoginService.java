package com.exe101.staff.service;

import com.exe101.staff.model.StaffLogin;
import com.exe101.staff.model.StaffToken;

public interface StaffLoginService {
    StaffToken login(StaffLogin login);
}
