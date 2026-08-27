package io.legacypilot.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.bootstrap.LegacyPilotCoreConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(LegacyPilotCoreConfiguration.class)
public class LegacyPilotCliApplication {

  @Bean
  ObjectMapper legacyPilotObjectMapper() {
    return new ObjectMapper().findAndRegisterModules();
  }

  public static void main(String[] args) {
    var application = new SpringApplication(LegacyPilotCliApplication.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    var context = application.run(args);
    var result = context.getBean(CliExecution.class).exitCode();
    System.exit(SpringApplication.exit(context, () -> result));
  }
}
