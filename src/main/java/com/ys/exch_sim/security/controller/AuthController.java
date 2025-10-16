package com.ys.exch_sim.security.controller;

import com.ys.exch_sim.domain.bigquery.BigQueryService;
import com.ys.exch_sim.domain.position.PositionManager;
import com.ys.exch_sim.security.jwt.JwtService;
import com.ys.exch_sim.security.model.AuthenticationRequest;
import com.ys.exch_sim.security.model.AuthenticationResponse;
import com.ys.exch_sim.security.model.SignupRequest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

  private final AuthenticationManager authenticationManager;
  private final UserDetailsService userDetailsService;
  private final JwtService jwtService;
  private final BigQueryService bigQueryService;
  private final PasswordEncoder passwordEncoder;
  private final PositionManager positionManager;

  public AuthController(
      AuthenticationManager authenticationManager,
      UserDetailsService userDetailsService,
      JwtService jwtService,
      @Autowired(required = false) BigQueryService bigQueryService,
      PasswordEncoder passwordEncoder,
      PositionManager positionManager) {
    this.authenticationManager = authenticationManager;
    this.userDetailsService = userDetailsService;
    this.jwtService = jwtService;
    this.bigQueryService = bigQueryService;
    this.passwordEncoder = passwordEncoder;
    this.positionManager = positionManager;
  }

  @PostMapping("/login")
  public ResponseEntity<AuthenticationResponse> authenticate(
      @RequestBody AuthenticationRequest request) {
    log.info("AuthController: Login attempt for user: {}", request.getUsername());
    try {
      authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

      final UserDetails userDetails = userDetailsService.loadUserByUsername(request.getUsername());
      final String jwt = jwtService.generateToken(userDetails);

      log.info("AuthController: Login successful for user: {}", request.getUsername());
      return ResponseEntity.ok(new AuthenticationResponse(jwt));
    } catch (Exception e) {
      log.error("AuthController: Login failed for user: {}", request.getUsername(), e);
      throw e;
    }
  }

  @PostMapping("/signup")
  public ResponseEntity<?> signup(@RequestBody SignupRequest request) {
    try {
      // 入力バリデーション
      if (request == null
          || request.getUsername() == null
          || request.getUsername().trim().isEmpty()) {
        return ResponseEntity.badRequest().body("Username is required");
      }

      if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
        return ResponseEntity.badRequest().body("Password is required");
      }

      String username = request.getUsername().trim();
      String password = request.getPassword();

      // ユーザー名の長さチェック
      if (username.length() < 3 || username.length() > 50) {
        return ResponseEntity.badRequest().body("Username must be between 3 and 50 characters");
      }

      // パスワードの長さチェック
      if (password.length() < 6) {
        return ResponseEntity.badRequest().body("Password must be at least 6 characters");
      }

      // ユーザーが既に存在するかチェック
      // まずUserDetailsServiceでチェック（users.jsonやBigQueryから）
      try {
        UserDetails existingUser = userDetailsService.loadUserByUsername(username);
        if (existingUser != null) {
          log.warn("Signup attempt for existing user: {}", username);
          return ResponseEntity.badRequest().body("Username already exists");
        }
      } catch (Exception e) {
        // ユーザーが見つからない場合は正常（新規登録可能）
        log.debug("User does not exist, proceeding with signup: {}", username);
      }

      // BigQueryでも追加チェック（念のため）
      if (bigQueryService != null && bigQueryService.userExists(username)) {
        return ResponseEntity.badRequest().body("Username already exists");
      }

      // パスワードをエンコード
      String encodedPassword = passwordEncoder.encode(password);

      // デフォルトロールを設定（必要に応じて変更可能）
      String[] defaultRoles = {"USER"};

      // BigQueryにユーザーを登録（BigQueryが有効な場合のみ）
      if (bigQueryService != null) {
        bigQueryService.registerUser(username, encodedPassword, Arrays.asList(defaultRoles));
      }

      // 初期残高（100万円）を付与
      double initialCashBalance = 1000000.0;
      positionManager.initializeUserWithCash(username, initialCashBalance);
      log.info("Initialized cash balance for new user: {} - Amount: {}", username, initialCashBalance);

      log.info("User registered successfully: {}", username);

      Map<String, Object> response = new HashMap<String, Object>();
      response.put("message", "User registered successfully");
      response.put("username", username);

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error during user registration: {}", request.getUsername(), e);
      return ResponseEntity.internalServerError().body("Registration failed: " + e.getMessage());
    }
  }
}
