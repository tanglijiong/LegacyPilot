package io.legacypilot.server.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Correlation-ID";
  private static final String MDC_KEY = "correlationId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var requested = normalizedUuid(request.getHeader(HEADER));
    var correlationId = requested == null ? UUID.randomUUID().toString() : requested;
    response.setHeader(HEADER, correlationId);
    try (var ignored = MDC.putCloseable(MDC_KEY, correlationId)) {
      chain.doFilter(request, response);
    }
  }

  private static String normalizedUuid(String value) {
    if (value == null) {
      return null;
    }
    try {
      return UUID.fromString(value).toString();
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }
}
