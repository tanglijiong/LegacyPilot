package io.legacypilot.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.legacypilot.state.VersionedJsonFile;
import java.nio.file.Path;
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
  }

  @Override
  public synchronized void save(RuntimeApproval approval) {
    var approvals = new ArrayList<>(read());
    approvals.add(approval);
    state().save(approvals);
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
      state().save(approvals);
    }
    return match;
  }

  private List<RuntimeApproval> read() {
    return state().load().orElseGet(List::of);
  }

  private VersionedJsonFile<List<RuntimeApproval>> state() {
    var type = mapper.getTypeFactory().constructCollectionType(List.class, RuntimeApproval.class);
    return new VersionedJsonFile<>(file, mapper, type);
  }
}
