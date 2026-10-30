package com.exe101.staff.config;

import com.exe101.staff.model.StaffLogin;
import com.exe101.staff.service.StaffService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AdminBootstrap implements ApplicationRunner {
    private final StaffService staffService;
    @Value("${app.admin.login-name:}")
    private String loginName;
    @Value("${app.admin.password:}")
    private String password;

    @Override
    public void run(ApplicationArguments args) {
        if (loginName.isBlank() && password.isEmpty()) {
            return;
        }

        StaffLogin login = StaffLogin.builder().withLoginName(loginName).withPassword(password).build();
        staffService.bootstrapAdmin(login);
    }
}
