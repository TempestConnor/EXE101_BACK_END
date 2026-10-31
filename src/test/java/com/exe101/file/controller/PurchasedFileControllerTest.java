package com.exe101.file.controller;

import com.exe101.common.error.AppErrorMessage;
import com.exe101.common.error.AppException;
import com.exe101.common.error.GlobalExceptionHandler;
import com.exe101.common.security.SecurityConfiguration;
import com.exe101.common.security.SecurityProblemHandler;
import com.exe101.customer.config.CustomerTokenConfiguration;
import com.exe101.customer.model.Customer;
import com.exe101.customer.security.CustomerAuthenticationConverter;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import com.exe101.file.model.FileContent;
import com.exe101.file.service.FileService;
import com.exe101.staff.config.StaffTokenConfiguration;
import com.exe101.staff.security.StaffAuthenticationConverter;
import com.exe101.staff.security.StaffTokenIssuer;
import com.exe101.staff.service.StaffService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PurchasedFileController.class)
@Import({StaffTokenIssuer.class, StaffTokenConfiguration.class, StaffAuthenticationConverter.class,
    CustomerTokenIssuer.class, CustomerTokenConfiguration.class, CustomerAuthenticationConverter.class,
    SecurityConfiguration.class, SecurityProblemHandler.class, GlobalExceptionHandler.class})
class PurchasedFileControllerTest {
    private static byte[] fileBytes = {'s', 'o', 'u', 'r', 'c', 'e'};
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CustomerTokenIssuer customerIssuer;
    @MockitoBean
    private FileService files;
    @MockitoBean
    private StaffService staff;
    @MockitoBean
    private CustomerService customers;

    @Test
    void download_entitled_file_ok() throws Exception {
        // given
        var token = customerToken();
        var content = FileContent.builder()
            .withFileId(100L)
            .withOriginalName("sunset.psd")
            .withContentType("image/vnd.adobe.photoshop")
            .withByteLength(fileBytes.length)
            .withContent(new ByteArrayResource(fileBytes))
            .build();
        when(files.readPurchased(42L, 30L)).thenReturn(content);

        // when / then
        mvc.perform(get("/purchases/assets/30/content").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(content().contentType("image/vnd.adobe.photoshop"))
            .andExpect(header().longValue("Content-Length", fileBytes.length))
            .andExpect(header().string("Content-Disposition",
                "attachment; filename=\"sunset.psd\"; filename*=UTF-8''sunset.psd"))
            .andExpect(content().bytes(fileBytes));
    }

    @Test
    void download_unauthenticated_ko() throws Exception {
        // given
        var request = get("/purchases/assets/30/content");

        // when / then
        mvc.perform(request)
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(files);
    }

    @Test
    void download_denied_ko() throws Exception {
        // given
        var token = customerToken();
        when(files.readPurchased(42L, 30L)).thenThrow(new AppException(AppErrorMessage.ENTITLEMENT_REQUIRED));

        // when / then
        mvc.perform(get("/purchases/assets/30/content").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.errorCode").value("ENTITLEMENT_REQUIRED"));
    }

    private String customerToken() {
        when(customers.requireActive(42L)).thenReturn(Customer.builder().withUserId(42L).build());

        return customerIssuer.issue(42L).getAccessToken();
    }
}
