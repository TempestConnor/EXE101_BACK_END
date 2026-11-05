package com.exe101.payment.controller;

import com.exe101.customer.model.CustomerRegistration;
import com.exe101.customer.security.CustomerTokenIssuer;
import com.exe101.customer.service.CustomerService;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

// X-Forwarded-For is read by Tomcat itself (server.forward-headers-strategy=native), which MockMvc does not run, so
// this starts a real server. Starting a payment commits a PaymentAttempt, so like the persistence tests it runs only
// against the disposable test database (TEST_DB_URL) and leaves its rows there.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "app.orders.expiry-interval=PT1H")
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class PaymentForwardedIpTest {
    private static String forwardedIp = "203.0.113.9";
    @Value("${local.server.port}")
    private int port;
    @Autowired
    private CustomerService customerService;
    @Autowired
    private CustomerTokenIssuer issuer;
    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
    }

    @Test
    void forwarded_client_ip_from_proxy_ok() throws Exception {
        // given
        // The request comes from loopback, an internal proxy address, as it does through nginx or ngrok.
        var buyerId = customerService.register(new CustomerRegistration(
            "f11-ip-test-" + UUID.randomUUID() + "@example.invalid", "long-password", "Buyer")).getUserId();
        var orderId = pendingOrder(buyerId, "110000");
        var token = issuer.issue(buyerId).getAccessToken();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/orders/" + orderId
                + "/payment-attempts"))
            .header("Authorization", "Bearer " + token)
            .header("X-Forwarded-For", forwardedIp)
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        // when
        HttpResponse<String> response;

        try (var client = HttpClient.newHttpClient()) {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        }

        // then
        var paymentUrl = JsonMapper.builder().build().readTree(response.body()).get("paymentUrl").asString();
        var fields = fields(paymentUrl.substring(paymentUrl.indexOf('?') + 1));
        assertAll(
            () -> assertThat(response.statusCode()).isEqualTo(201),
            () -> assertThat(fields).containsEntry("vnp_IpAddr", forwardedIp)
        );
    }

    private long pendingOrder(long buyerId, String amount) {
        var orderId = jdbc.queryForObject("INSERT INTO dbo.MarketplaceOrder (CustomerId, Status, CreatedAt,"
                + " ExpiresAt, ItemsSubtotal) OUTPUT INSERTED.OrderId VALUES (?, 'PENDING_PAYMENT', ?, ?, ?)",
            Long.class, buyerId, now(), now().plusMinutes(30), new BigDecimal(amount));
        jdbc.update("INSERT INTO dbo.Payment (OrderId, Amount) VALUES (?, ?)", orderId, new BigDecimal(amount));

        return orderId;
    }

    private Map<String, String> fields(String query) {
        return Arrays.stream(query.split("&"))
            .map(pair -> pair.split("=", 2))
            .collect(Collectors.toMap(pair -> pair[0], pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    }
}
