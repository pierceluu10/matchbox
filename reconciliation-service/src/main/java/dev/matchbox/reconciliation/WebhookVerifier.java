package dev.matchbox.reconciliation;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/** Checks the bank's HMAC-SHA256 signature over the raw request body. */
@Component
class WebhookVerifier {

	static final String SIGNATURE_HEADER = "X-Bank-Signature";

	private static final String ALGORITHM = "HmacSHA256";

	private final SecretKeySpec key;

	WebhookVerifier(ReconciliationProperties properties) {
		this.key = new SecretKeySpec(properties.webhookSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
	}

	boolean isValid(String body, String signature) {
		if (signature == null) {
			return false;
		}
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(this.key);
			byte[] expected = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
			// Constant-time comparison so response timing doesn't leak how much of the signature matched.
			return MessageDigest.isEqual(expected, HexFormat.of().parseHex(signature));
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
		catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("HmacSHA256 unavailable", ex);
		}
	}

}
