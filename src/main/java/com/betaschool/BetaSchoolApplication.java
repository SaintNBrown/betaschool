package com.betaschool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class BetaSchoolApplication {
    public static void main(String[] args) {
        SpringApplication.run(BetaSchoolApplication.class, args);
    }
}
