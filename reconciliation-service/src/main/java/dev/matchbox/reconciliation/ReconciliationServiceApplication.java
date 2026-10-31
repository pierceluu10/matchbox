package dev.matchbox.reconciliation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "dev.matchbox")
@ConfigurationPropertiesScan
public class ReconciliationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ReconciliationServiceApplication.class, args);
	}

}
