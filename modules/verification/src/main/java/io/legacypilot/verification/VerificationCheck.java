package io.legacypilot.verification;

@FunctionalInterface
public interface VerificationCheck {
  VerificationEvidence verify(VerificationContext context);
}
