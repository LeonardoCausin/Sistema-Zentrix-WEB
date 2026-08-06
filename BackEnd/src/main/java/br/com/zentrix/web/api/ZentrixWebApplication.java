package br.com.zentrix.web.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "br.com.zentrix.web")
@EnableScheduling
public class ZentrixWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZentrixWebApplication.class, args);
    }
}
