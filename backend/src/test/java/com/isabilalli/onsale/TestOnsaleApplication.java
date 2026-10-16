package com.isabilalli.onsale;

import org.springframework.boot.SpringApplication;

public class TestOnsaleApplication {

	public static void main(String[] args) {
		SpringApplication.from(OnsaleApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
