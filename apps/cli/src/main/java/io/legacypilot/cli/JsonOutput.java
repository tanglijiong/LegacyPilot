package io.legacypilot.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.domain.run.TaskRun;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

@Component
public class JsonOutput {

  private final ObjectMapper mapper;

  public JsonOutput(ObjectMapper mapper) {
    this.mapper = mapper.findAndRegisterModules();
  }

  void write(Object value) {
    try {
      System.out.println(mapper.writeValueAsString(jsonValue(value)));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Unable to serialize command result", exception);
    }
  }

  private static Object jsonValue(Object value) {
    if (!(value instanceof TaskRun run)) {
      return value;
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("id", run.id().value());
    result.put("taskId", run.taskId().value());
    result.put("status", run.status().name());
    result.put("version", run.version());
    result.put("workspaceId", run.workspaceId() == null ? null : run.workspaceId().value());
    result.put("terminalReason", run.terminalReason());
    result.put("createdAt", run.createdAt());
    result.put("updatedAt", run.updatedAt());
    result.put("history", run.history());
    return result;
  }
}
