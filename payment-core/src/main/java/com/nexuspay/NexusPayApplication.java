package com.nexuspay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NexusPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusPayApplication.class, args);
    }
}
