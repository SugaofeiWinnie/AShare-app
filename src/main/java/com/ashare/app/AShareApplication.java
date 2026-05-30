package com.ashare.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class AShareApplication {
  public static void main(String[] args) {
    SpringApplication.run(AShareApplication.class, args);
  }
}
