package com.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;



@SpringBootApplication
public class ConnectorApplication {
    public static void main(String[] args) {

        var context = SpringApplication.run(ConnectorApplication.class, args);

        System.out.println("Context active = " + context.isActive());

        System.out.println("Bean definitions = " + context.getBeanDefinitionCount());

    }
}