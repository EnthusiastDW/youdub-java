package com.youdub.replica;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class YouDubApplication {
    public static void main(String[] args) {
        SpringApplication.run(YouDubApplication.class, args);
    }
}
