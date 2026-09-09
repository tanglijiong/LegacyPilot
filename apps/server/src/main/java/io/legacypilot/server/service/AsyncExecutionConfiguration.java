package io.legacypilot.server.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AsyncExecutionConfiguration {

  @Bean(name = "runCommandExecutor", destroyMethod = "shutdown")
  ExecutorService runCommandExecutor(
      @Value("${legacy-pilot.execution.worker-count:2}") int workerCount) {
    if (workerCount < 1 || workerCount > 32) {
      throw new IllegalArgumentException("execution worker count must be between 1 and 32");
    }
    return Executors.newFixedThreadPool(
        workerCount, Thread.ofPlatform().name("run-command-", 0).factory());
  }

  @Bean(name = "runCommandScheduler", destroyMethod = "shutdown")
  ScheduledExecutorService runCommandScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("run-command-scheduler-", 0).factory());
  }

  @Bean(name = "runEventScheduler", destroyMethod = "shutdown")
  ScheduledExecutorService runEventScheduler() {
    return Executors.newScheduledThreadPool(
        2, Thread.ofPlatform().name("run-event-stream-", 0).factory());
  }
}
