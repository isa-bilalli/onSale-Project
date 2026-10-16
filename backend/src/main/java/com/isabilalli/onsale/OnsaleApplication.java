package com.isabilalli.onsale;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OnsaleApplication {

	public static void main(String[] args) {
		SpringApplication.run(OnsaleApplication.class, args);
	}

}
