package com.marketpix;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MarketPixApplication {

    public static void main(String[] args) {
        SpringApplication.run(MarketPixApplication.class, args);
    }
}
