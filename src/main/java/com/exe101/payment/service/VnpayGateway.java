package com.exe101.payment.service;

import com.exe101.payment.config.VnpayProperties;
import com.exe101.payment.model.ProviderNotification;
import java.math.BigDecimal;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// VNPay payment API 2.1.0: builds the signed payment page URL and verifies payment notifications (IPN). Both are
// signed with HMAC-SHA512 over the vnp_ fields sorted by name, values URL-encoded, as in VNPay's integration guide.
// Amounts are sent in VND x 100; times are GMT+7.
@Component
@RequiredArgsConstructor
public class VnpayGateway {
    public static String providerName = "VNPAY";
    private static String version = "2.1.0";
    private static String successCode = "00";
    private static String hashField = "vnp_SecureHash";
    private static String hashTypeField = "vnp_SecureHashType";
    private static ZoneOffset vnpayOffset = ZoneOffset.ofHours(7);
    private static DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    // A buyer on the same machine (local development) arrives as the IPv6 loopback, which is sent in IPv4 form.
    // vnp_IpAddr is the buyer's address for VNPay's own checks; it plays no part in amounts or confirmation.
    private static Set<String> ipv6Loopback = Set.of("::1", "0:0:0:0:0:0:0:1");
    private static String ipv4Loopback = "127.0.0.1";
    private static Pattern ipv6Literal = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]*");
    private final VnpayProperties properties;

    // The page accepts payment only until the Order's ExpiresAt, so VNPay itself refuses most late payments.
    public String paymentUrl(
        String attemptRef, BigDecimal amount, String orderInfo, OffsetDateTime createdAt, OffsetDateTime expiresAt,
        String clientIp
    ) {
        Map<String, String> fields = new TreeMap<>();
        fields.put("vnp_Version", version);
        fields.put("vnp_Command", "pay");
        fields.put("vnp_TmnCode", properties.tmnCode());
        fields.put("vnp_Amount", amount.movePointRight(2).toBigIntegerExact().toString());
        fields.put("vnp_CurrCode", "VND");
        fields.put("vnp_TxnRef", attemptRef);
        fields.put("vnp_OrderInfo", orderInfo);
        fields.put("vnp_OrderType", "other");
        fields.put("vnp_Locale", "vn");
        fields.put("vnp_ReturnUrl", properties.returnUrl().toString());
        fields.put("vnp_IpAddr", vnpayIp(clientIp));
        fields.put("vnp_CreateDate", vnpayTime(createdAt));
        fields.put("vnp_ExpireDate", vnpayTime(expiresAt));
        String query = encode(fields);

        return properties.payUrl() + "?" + query + "&" + hashField + "=" + sign(query);
    }

    // Empty unless the signature matches, the notification is for this merchant and its fields are well formed.
    public Optional<ProviderNotification> verify(Map<String, String> parameters) {
        String signature = parameters.get(hashField);
        Map<String, String> fields = parameters.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith("vnp_"))
            .filter(entry -> !entry.getKey().equals(hashField) && !entry.getKey().equals(hashTypeField))
            .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (first, second) -> first,
                TreeMap::new));

        if (signature == null || !matches(sign(encode(fields)), signature)) {
            return Optional.empty();
        }

        String attemptRef = fields.get("vnp_TxnRef");
        String amount = fields.get("vnp_Amount");
        String responseCode = fields.get("vnp_ResponseCode");
        String transactionStatus = fields.get("vnp_TransactionStatus");
        String transactionNo = fields.getOrDefault("vnp_TransactionNo", "0");
        boolean complete = attemptRef != null && amount != null && amount.matches("\\d{1,20}")
            && responseCode != null && transactionStatus != null
            && properties.tmnCode().equals(fields.get("vnp_TmnCode"));

        if (!complete) {
            return Optional.empty();
        }

        boolean success = successCode.equals(responseCode) && successCode.equals(transactionStatus);

        return Optional.of(ProviderNotification.builder()
            .withEventId(String.join(":", attemptRef, transactionNo, responseCode, transactionStatus))
            .withAttemptRef(attemptRef)
            .withTransactionRef(transactionNo)
            .withAmount(new BigDecimal(amount).movePointLeft(2))
            .withSuccess(success)
            .build());
    }

    private String encode(Map<String, String> sortedFields) {
        return sortedFields.entrySet().stream()
            .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.US_ASCII) + "="
                + URLEncoder.encode(entry.getValue(), StandardCharsets.US_ASCII))
            .collect(Collectors.joining("&"));
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(properties.hashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));

            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA512 is unavailable", exception);
        }
    }

    // Constant-time comparison; VNPay may send the hex digest in either case.
    private boolean matches(String expected, String received) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
            received.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    // VNPay documents vnp_IpAddr with IPv4 examples only (API 2.1.0), so an IPv4 address written in IPv6 form
    // (::ffff:203.0.113.7, as some proxies put it in X-Forwarded-For) is sent as plain IPv4. Other IPv6 addresses go
    // as they are. Only hex digits, ':' and '.' starting with a hex digit or ':' are parsed: Java then reads the text
    // as an IP literal and throws if it is not one, so header text never triggers a DNS lookup.
    private String vnpayIp(String clientIp) {
        if (ipv6Loopback.contains(clientIp)) {
            return ipv4Loopback;
        }

        if (clientIp == null || !clientIp.contains(":") || !ipv6Literal.matcher(clientIp).matches()) {
            return clientIp;
        }

        try {
            InetAddress address = InetAddress.getByName(clientIp);

            return address instanceof Inet4Address ? address.getHostAddress() : clientIp;
        } catch (UnknownHostException exception) {
            return clientIp;
        }
    }

    private String vnpayTime(OffsetDateTime time) {
        return time.withOffsetSameInstant(vnpayOffset).format(timeFormat);
    }
}
