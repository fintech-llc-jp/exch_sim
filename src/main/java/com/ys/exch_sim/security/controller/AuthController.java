package com.ys.exch_sim.security.controller;

import com.ys.exch_sim.security.jwt.JwtService;
import com.ys.exch_sim.security.model.AuthenticationRequest;
import com.ys.exch_sim.security.model.AuthenticationResponse;
import com.ys.exch_sim.security.model.SignupRequest;
import com.ys.exch_sim.security.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final AuthenticationManager authenticationManager;
  private final UserDetailsService userDetailsService;
  private final JwtService jwtService;
  private final UserService userService;

  @PostMapping("/login")
  public ResponseEntity<AuthenticationResponse> authenticate(
      @RequestBody AuthenticationRequest request) {
    authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

    final UserDetails userDetails = userDetailsService.loadUserByUsername(request.getUsername());
    final String jwt = jwtService.generateToken(userDetails);

    return ResponseEntity.ok(new AuthenticationResponse(jwt));
  }

  @PostMapping("/signup")
  public ResponseEntity<?> signup(@RequestBody SignupRequest request) {
    log.info("Received signup request for username: {}", request.getUsername());
    try {
      boolean success = userService.registerUser(request.getUsername(), request.getPassword());
      if (success) {
        log.info("Successfully registered user: {}", request.getUsername());
        return ResponseEntity.ok().build();
      } else {
        log.warn("Failed to register user: {} - Username already exists", request.getUsername());
        return ResponseEntity.badRequest().body("Username already exists");
      }
    } catch (Exception e) {
      log.error("Error during signup for user: " + request.getUsername(), e);
      return ResponseEntity.internalServerError().body("Error during signup: " + e.getMessage());
    }
  }
}
