package com.ys.exch_sim.domain.controller;

import com.ys.exch_sim.domain.dto.NewOrderRequest;
import com.ys.exch_sim.domain.dto.OrderResponse;
import com.ys.exch_sim.domain.service.OrderService;
import com.ys.exch_sim.security.service.CustomUserDetailsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

  private final OrderService orderService;
  private final CustomUserDetailsService userDetailsService;

  @PostMapping("/new")
  public ResponseEntity<?> newOrder(@RequestBody NewOrderRequest request) {
    try {
      // JWTから認証情報を取得
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null || !authentication.isAuthenticated()) {
        log.warn("Unauthenticated request for new order");
        return ResponseEntity.status(401).body("Authentication required");
      }

      String username = authentication.getName();
      log.info("Processing order for authenticated user: {}", username);

      // ユーザーが存在するかチェック
      try {
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        if (userDetails == null) {
          log.warn("User not found: {}", username);
          return ResponseEntity.status(404).body("User not found");
        }
      } catch (Exception e) {
        log.warn("Error validating user: {}", username, e);
        return ResponseEntity.status(404).body("User not found");
      }

      // 入力バリデーション
      if (request == null
          || request.getSymbol() == null
          || request.getSymbol().trim().isEmpty()
          || request.getPrice() == null
          || request.getPrice() <= 0
          || request.getQuantity() == null
          || request.getQuantity() <= 0
          || request.getSide() == null
          || request.getSide().trim().isEmpty()
          || request.getOrdType() == null
          || request.getOrdType().trim().isEmpty()
          || request.getTif() == null
          || request.getTif().trim().isEmpty()) {

        log.warn("Invalid order request from user: {}", username);
        return ResponseEntity.badRequest().body("Invalid order parameters");
      }

      // 注文を処理
      OrderResponse response = orderService.processNewOrder(username, request);

      log.info(
          "Order processed successfully for user: {} with clOrdID: {}",
          username,
          response.getClOrdID());

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error processing new order", e);
      return ResponseEntity.internalServerError().body("Error processing order: " + e.getMessage());
    }
  }
}
