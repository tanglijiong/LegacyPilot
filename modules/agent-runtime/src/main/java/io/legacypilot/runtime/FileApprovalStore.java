package io.legacypilot.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class FileApprovalStore implements ApprovalStore {
  private final Path file;
  private final ObjectMapper mapper;

  public FileApprovalStore(Path file, ObjectMapper mapper) {
    this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
    this.mapper = Objects.requireNonNull(mapper);
    try {
      var parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
    } catch (IOException exception) {
      throw new IllegalArgumentException("approval store path is unavailable", exception);
    }
  }

  @Override
  public synchronized void save(RuntimeApproval approval) {
    var approvals = new ArrayList<>(read());
    approvals.add(approval);
    write(approvals);
  }

  @Override
  public synchronized Optional<RuntimeApproval> consumeMatching(
      String runId, String actionDigest, String planDigest, Instant now) {
    var approvals = new ArrayList<>(read());
    var match =
        approvals.reversed().stream()
            .filter(approval -> approval.matches(runId, actionDigest, planDigest, now))
            .findFirst();
    if (match.isPresent() && match.get().scope() == ApprovalScope.ONCE) {
      approvals.remove(match.get());
      write(approvals);
    }
    return match;
  }

  private List<RuntimeApproval> read() {
    if (!Files.exists(file)) {
      return List.of();
    }
    try {
      return mapper.readValue(file.toFile(), new TypeReference<List<RuntimeApproval>>() {});
    } catch (IOException exception) {
      throw new IllegalStateException("unable to read approval store", exception);
    }
  }

  private void write(List<RuntimeApproval> approvals) {
    var parent = Objects.requireNonNull(file.getParent());
    try {
      var temporary = Files.createTempFile(parent, ".approvals-", ".tmp");
      try {
        mapper.writeValue(temporary.toFile(), approvals);
        Files.move(
            temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("unable to persist approval", exception);
    }
  }
}
