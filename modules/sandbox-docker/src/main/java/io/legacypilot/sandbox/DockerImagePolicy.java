package io.legacypilot.sandbox;

import java.util.Objects;
import java.util.Set;

public final class DockerImagePolicy {
  private final Set<String> allowedImages;
  private final boolean digestRequired;

  public DockerImagePolicy(Set<String> allowedImages, boolean digestRequired) {
    this.allowedImages = Set.copyOf(allowedImages);
    this.digestRequired = digestRequired;
    if (this.allowedImages.isEmpty()) {
      throw new IllegalArgumentException("at least one sandbox image must be allowed");
    }
    this.allowedImages.forEach(Objects::requireNonNull);
    if (digestRequired
        && this.allowedImages.stream()
            .anyMatch(image -> !image.matches("[^@]+@sha256:[a-f0-9]{64}"))) {
      throw new IllegalArgumentException("sandbox images must be pinned by SHA-256 digest");
    }
  }

  public void validate(String image) {
    if (!allowedImages.contains(image)) {
      throw new IllegalArgumentException("sandbox image is not allowlisted");
    }
    if (digestRequired && !image.matches("[^@]+@sha256:[a-f0-9]{64}")) {
      throw new IllegalArgumentException("sandbox image must be pinned by digest");
    }
  }

  public static DockerImagePolicy allowlisted(Set<String> images) {
    return new DockerImagePolicy(images, false);
  }

  public static DockerImagePolicy digestPinned(Set<String> images) {
    return new DockerImagePolicy(images, true);
  }
}
