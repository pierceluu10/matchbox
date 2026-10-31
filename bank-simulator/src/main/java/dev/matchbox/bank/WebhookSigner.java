package dev.matchbox.bank;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/** HMAC-SHA256 over the raw request body, the same scheme the reconciliation service verifies. */
@Component
class WebhookSigner {

	static final String SIGNATURE_HEADER = "X-Bank-Signature";

	private static final String ALGORITHM = "HmacSHA256";

	private final SecretKeySpec key;

	WebhookSigner(BankProperties properties) {
		this.key = new SecretKeySpec(properties.webhookSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
	}

	String sign(String body) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(this.key);
			return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("HmacSHA256 unavailable", ex);
		}
	}

}
