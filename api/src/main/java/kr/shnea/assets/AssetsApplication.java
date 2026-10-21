package kr.shnea.assets;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AssetsApplication {
    public static void main(String[] args) { SpringApplication.run(AssetsApplication.class, args); }
}
