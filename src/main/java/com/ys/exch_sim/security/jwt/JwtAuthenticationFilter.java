package com.ys.exch_sim.security.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final JwtService jwtService;
  private final UserDetailsService userDetailsService;

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {

    log.info("JWT Filter: Processing request to {}", request.getRequestURI());

    // /api/auth/** パスはJWTフィルターをスキップ
    if (request.getRequestURI().startsWith("/api/auth/")) {
      log.info("JWT Filter: Skipping auth path {}", request.getRequestURI());
      filterChain.doFilter(request, response);
      return;
    }

    final String authHeader = request.getHeader("Authorization");
    final String jwt;
    final String username;

    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      log.info("JWT Filter: No valid Authorization header for {}", request.getRequestURI());
      filterChain.doFilter(request, response);
      return;
    }

    jwt = authHeader.substring(7);
    username = jwtService.extractUsername(jwt);

    if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
      UserDetails userDetails = this.userDetailsService.loadUserByUsername(username);

      if (jwtService.isTokenValid(jwt, userDetails)) {
        UsernamePasswordAuthenticationToken authToken =
            new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);
        log.info("JWT Filter: Authentication successful for user {}", username);
      } else {
        log.warn("JWT Filter: Invalid token for user {}", username);
      }
    }
    filterChain.doFilter(request, response);
  }
}
