package io.legacypilot.server;

import io.legacypilot.bootstrap.LegacyPilotCoreConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(LegacyPilotCoreConfiguration.class)
public class LegacyPilotServerApplication {

  public static void main(String[] args) {
    SpringApplication.run(LegacyPilotServerApplication.class, args);
  }
}
