package com.ys.exch_sim.domain.controller;

import com.ys.exch_sim.domain.dto.CancelOrderRequest;
import com.ys.exch_sim.domain.dto.NewOrderRequest;
import com.ys.exch_sim.domain.dto.OrderResponse;
import com.ys.exch_sim.domain.service.OrderService;
import org.springframework.security.core.userdetails.UserDetailsService;
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
  private final UserDetailsService userDetailsService;

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

      // 指値注文の場合は価格が必要
      if ("LIMIT".equals(request.getOrdType()) && 
          (request.getPrice() == null || request.getPrice() <= 0)) {
        log.warn("Invalid price for LIMIT order from user: {}", username);
        return ResponseEntity.badRequest().body("Price is required for LIMIT orders");
      }

      // 成行注文の場合は価格を0に設定（価格が指定されていない場合）
      if ("MARKET".equals(request.getOrdType()) && request.getPrice() == null) {
        request.setPrice(0.0);
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

  @PostMapping("/cancel")
  public ResponseEntity<?> cancelOrder(@RequestBody CancelOrderRequest request) {
    try {
      // JWTから認証情報を取得
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null || !authentication.isAuthenticated()) {
        log.warn("Unauthenticated request for cancel order");
        return ResponseEntity.status(401).body("Authentication required");
      }

      String username = authentication.getName();
      log.info("Processing cancel order for authenticated user: {}", username);

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
          || request.getClOrdID() == null
          || request.getClOrdID().trim().isEmpty()
          || request.getSymbol() == null
          || request.getSymbol().trim().isEmpty()) {

        log.warn("Invalid cancel order request from user: {}", username);
        return ResponseEntity.badRequest().body("Invalid cancel order parameters");
      }

      // 注文をキャンセル
      OrderResponse response = orderService.cancelOrder(username, request);

      log.info(
          "Order cancelled successfully for user: {} with clOrdID: {}",
          username,
          response.getClOrdID());

      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error cancelling order", e);
      return ResponseEntity.internalServerError().body("Error cancelling order: " + e.getMessage());
    }
  }
}
