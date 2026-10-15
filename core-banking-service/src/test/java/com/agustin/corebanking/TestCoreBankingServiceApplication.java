package com.agustin.corebanking;

import org.springframework.boot.SpringApplication;

public class TestCoreBankingServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(CoreBankingServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
