package dev.matchbox.exceptions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "dev.matchbox")
public class ExceptionServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ExceptionServiceApplication.class, args);
	}

}
