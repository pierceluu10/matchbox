package dev.matchbox.payment;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CacheConfiguration {

	/**
	 * Defers evictions until the database transaction commits; evicting earlier would let a
	 * concurrent poll re-cache the old status before the new one is visible.
	 */
	@Bean
	RedisCacheManagerBuilderCustomizer transactionAwareCache() {
		return (builder) -> builder.transactionAware();
	}

}
