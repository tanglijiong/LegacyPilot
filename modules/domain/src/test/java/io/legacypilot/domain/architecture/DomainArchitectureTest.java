package io.legacypilot.domain.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class DomainArchitectureTest {

  @Test
  void domainDoesNotDependOnFrameworksOrAdapters() {
    var domainClasses = new ClassFileImporter().importPackages("io.legacypilot.domain");

    noClasses()
        .that()
        .resideInAPackage("io.legacypilot.domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "com.fasterxml.jackson..",
            "io.legacypilot.adapter..",
            "io.legacypilot.infrastructure..")
        .check(domainClasses);
  }
}
