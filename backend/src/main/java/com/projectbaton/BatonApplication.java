package com.projectbaton;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BatonApplication {

    public static void main(String[] args) {
        SpringApplication.run(BatonApplication.class, args);
    }
}
