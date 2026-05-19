package com.example.aotlab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class AotLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(AotLabApplication.class, args);
    }
}
