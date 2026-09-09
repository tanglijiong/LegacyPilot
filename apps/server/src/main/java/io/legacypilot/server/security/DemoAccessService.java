package io.legacypilot.server.security;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;

@Service
@Profile("demo")
public final class DemoAccessService {
  private static final int TOKEN_BYTES = 32;

  private final String developerToken;
  private final String approverToken;

  public DemoAccessService(@Value("${server.address:}") String serverAddress) {
    if (!isLoopback(serverAddress)) {
      throw new IllegalStateException("demo profile requires server.address to be loopback-only");
    }
    var random = new SecureRandom();
    developerToken = token(random);
    approverToken = token(random);
  }

  public Authentication authenticate(String token) {
    if (Objects.equals(developerToken, token)) {
      return identity("demo-developer", "DEVELOPER");
    }
    if (Objects.equals(approverToken, token)) {
      return identity("demo-approver", "APPROVER");
    }
    return null;
  }

  public boolean isLoopback(HttpServletRequest request) {
    return isLoopback(request.getRemoteAddr());
  }

  public String developerToken() {
    return developerToken;
  }

  public String approverToken() {
    return approverToken;
  }

  private static Authentication identity(String subject, String role) {
    return UsernamePasswordAuthenticationToken.authenticated(
        subject, "", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
  }

  private static String token(SecureRandom random) {
    var bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static boolean isLoopback(String address) {
    return "127.0.0.1".equals(address)
        || "::1".equals(address)
        || "0:0:0:0:0:0:0:1".equals(address);
  }
}
