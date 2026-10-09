package dev.sdxcod.springmqlab;

import dev.sdxcod.springmqlab.config.LabProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(LabProperties.class)
public class MqLabApplication {
    public static void main(String[] args) {
        SpringApplication.run(MqLabApplication.class, args);
    }
}
