package io.legacypilot.samples.banking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entrypoint for account transfers. */
@RestController
@RequestMapping("/accounts/{accountId}/transfers")
public final class TransferController {
  private final TransferService service;

  public TransferController(TransferService service) {
    this.service = service;
  }

  @PostMapping
  public TransferRecord transfer(
      @PathVariable String accountId, @RequestBody TransferRequest request) {
    return service.transfer(accountId, request.amount());
  }

  TransferRecord transfer(String accountId, BigDecimal amount) {
    return service.transfer(accountId, amount);
  }

  @GetMapping
  public List<TransferRecord> history(@PathVariable String accountId, Instant from, Instant to) {
    return service.history(accountId, from, to);
  }

  public record TransferRequest(BigDecimal amount) {}
}
