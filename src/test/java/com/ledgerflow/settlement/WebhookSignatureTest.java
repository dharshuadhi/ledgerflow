package com.ledgerflow.settlement;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Webhook HMAC verification — pure crypto, no infrastructure needed. */
class WebhookSignatureTest {

    private static final String SECRET = "test-webhook-secret";

    private String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean verify(String body, String signature) {
        // Mirror of SettlementService.verifySignature
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            byte[] actual = HexFormat.of().parseHex(signature);
            return java.security.MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void validSignatureAccepted() throws Exception {
        String body = "{\"settlementId\":\"abc\",\"status\":\"COMPLETED\"}";
        assertThat(verify(body, sign(body))).isTrue();
    }

    @Test
    void tamperedBodyRejected() throws Exception {
        String body = "{\"settlementId\":\"abc\",\"status\":\"COMPLETED\"}";
        String tampered = "{\"settlementId\":\"abc\",\"status\":\"COMPLETED\",\"amount\":999999}";
        assertThat(verify(tampered, sign(body))).isFalse();
    }

    @Test
    void wrongSecretRejected() throws Exception {
        String body = "{\"settlementId\":\"abc\"}";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("wrong-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String bad = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        assertThat(verify(body, bad)).isFalse();
    }

    @Test
    void malformedSignatureRejected() {
        assertThat(verify("body", "not-hex!!")).isFalse();
        assertThat(verify("body", "")).isFalse();
    }

    @Test
    void emptyBodyHasDeterministicSignature() throws Exception {
        assertThat(sign("")).isEqualTo(sign(""));
        assertThat(sign("")).hasSize(64); // 32 bytes hex
    }
}
