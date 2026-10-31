package dev.matchbox.payment;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("matchbox.bank")
record BankProperties(URI url, Duration connectTimeout, Duration readTimeout) {
}
