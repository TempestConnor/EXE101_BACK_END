package com.exe101.staff.service;

import com.exe101.staff.model.Staff;
import com.exe101.staff.model.StaffLogin;
import com.exe101.staff.model.StaffToken;
import com.exe101.staff.security.StaffTokenIssuer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class StaffLoginServiceImpl implements StaffLoginService {
    private final StaffService staffService;
    private final StaffTokenIssuer tokenIssuer;

    @Override
    public StaffToken login(StaffLogin login) {
        Staff staff = staffService.authenticate(login);
        StaffToken token = tokenIssuer.issue(staff.getStaffId());

        return token;
    }
}
