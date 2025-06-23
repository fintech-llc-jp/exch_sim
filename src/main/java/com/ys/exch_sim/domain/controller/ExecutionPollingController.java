package com.ys.exch_sim.domain.controller;

import com.ys.exch_sim.domain.dto.ExecutionPollingResponse;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.service.ExecutionQueueService;
import com.ys.exch_sim.security.service.CustomUserDetailsService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/executions")
@RequiredArgsConstructor
public class ExecutionPollingController {

  private final ExecutionQueueService executionQueueService;
  private final CustomUserDetailsService userDetailsService;

  @GetMapping("/poll")
  public ResponseEntity<?> pollExecutions(@RequestParam(defaultValue = "10") int maxCount) {
    try {
      // JWTから認証情報を取得
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null || !authentication.isAuthenticated()) {
        log.warn("Unauthenticated request for execution polling");
        return ResponseEntity.status(401).body("Authentication required");
      }

      String username = authentication.getName();
      log.info("Polling executions for user: {} with maxCount: {}", username, maxCount);

      // ユーザー存在確認
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

      // 約定結果をポーリング
      List<Execution> executions = executionQueueService.pollExecutions(username, maxCount);

      // レスポンス用DTOに変換
      List<ExecutionPollingResponse.ExecutionDto> executionDtos =
          executions.stream()
              .map(
                  exec -> {
                    try {
                      // Try to get data from original order first (for non-persisted executions)
                      return new ExecutionPollingResponse.ExecutionDto(
                          exec.getExecID().getId(),
                          exec.getOrder().getClOrdID().getId(),
                          exec.getOrder().getSymbol().getName(),
                          exec.getExecStatus().toString(),
                          getPxValue(exec.getLastPx()),
                          exec.getLastQty().getLongQty(),
                          exec.getCounterPartyUsername(),
                          exec.getOrder().getSide().toString());
                    } catch (UnsupportedOperationException e) {
                      // For persisted executions, use the stored data
                      return new ExecutionPollingResponse.ExecutionDto(
                          exec.getExecID().getId(),
                          exec.getOrderID(), // Use stored orderID
                          exec.getSymbol(),   // Use stored symbol
                          exec.getExecStatus().toString(),
                          getPxValueFromRaw(exec.getLastPxRaw()),
                          exec.getLastQtyRaw(),
                          exec.getCounterPartyUsername(),
                          determineSideFromExecution(exec)); // We need to determine side differently
                    }
                  })
              .collect(Collectors.toList());

      ExecutionPollingResponse response =
          new ExecutionPollingResponse(username, executionDtos.size(), executionDtos);

      log.info("Successfully polled {} executions for user: {}", executionDtos.size(), username);
      return ResponseEntity.ok(response);

    } catch (Exception e) {
      log.error("Error polling executions", e);
      return ResponseEntity.internalServerError()
          .body("Error polling executions: " + e.getMessage());
    }
  }

  @GetMapping("/queue-size")
  public ResponseEntity<?> getQueueSize() {
    try {
      Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
      if (authentication == null || !authentication.isAuthenticated()) {
        return ResponseEntity.status(401).body("Authentication required");
      }

      String username = authentication.getName();
      int queueSize = executionQueueService.getQueueSize(username);

      return ResponseEntity.ok(Map.of("username", username, "queueSize", queueSize));

    } catch (Exception e) {
      log.error("Error getting queue size", e);
      return ResponseEntity.internalServerError()
          .body("Error getting queue size: " + e.getMessage());
    }
  }

  private Double getPxValue(Px px) {
    return (double) px.getLongPx() / px.getSymbol().getPxMultiplier();
  }
  
  private Double getPxValueFromRaw(Long rawPx) {
    if (rawPx == null) return null;
    // Assuming default multiplier of 100 for stored data
    return rawPx.doubleValue() / 100.0;
  }
  
  private String determineSideFromExecution(Execution exec) {
    // Use the stored side from the execution entity
    return exec.getSide() != null ? exec.getSide() : "UNKNOWN";
  }
}
