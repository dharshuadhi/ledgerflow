package com.ledgerflow.settlement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 signing for settlement webhooks.
 *
 * <p>The simulator signs every callback exactly like a real provider would; the
 * webhook endpoint rejects anything with a missing or invalid signature before
 * touching the database. Comparison is constant-time.
 */
@Component
public class WebhookSigner {

    private final byte[] secret;

    public WebhookSigner(org.springframework.core.env.Environment env) {
        String configured = env.getProperty("ledgerflow.settlement.webhook-secret", "dev-webhook-secret");
        this.secret = configured.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] bytes = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    public boolean verify(String rawBody, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        String expected = sign(rawBody);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
