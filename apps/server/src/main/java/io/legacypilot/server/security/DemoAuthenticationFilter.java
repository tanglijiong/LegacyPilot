package io.legacypilot.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class DemoAuthenticationFilter extends OncePerRequestFilter {
  private static final String BEARER_PREFIX = "Bearer ";
  private final DemoAccessService access;

  DemoAuthenticationFilter(DemoAccessService access) {
    this.access = access;
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    var header = request.getHeader("Authorization");
    if (access.isLoopback(request)
        && header != null
        && header.startsWith(BEARER_PREFIX)
        && SecurityContextHolder.getContext().getAuthentication() == null) {
      var authentication = access.authenticate(header.substring(BEARER_PREFIX.length()));
      if (authentication != null) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
      }
    }
    filterChain.doFilter(request, response);
  }
}
