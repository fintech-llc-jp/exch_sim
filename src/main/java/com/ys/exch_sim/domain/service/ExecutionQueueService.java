package com.ys.exch_sim.domain.service;

import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.order_exec.ExecutionRepository;
import com.ys.exch_sim.security.aspect.MarketMakerAspect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class ExecutionQueueService {

  @Autowired
  private ExecutionRepository executionRepository;

  // ユーザーごとの約定結果キュー
  private final ConcurrentHashMap<String, BlockingQueue<Execution>> userExecutionQueues =
      new ConcurrentHashMap<>();

  public void addExecution(String username, Execution execution) {
    userExecutionQueues
        .computeIfAbsent(username, k -> new LinkedBlockingQueue<>())
        .offer(execution);
    
    // MarketMaker以外の約定をデータベースに永続化
    if (!execution.getIsMarketMaker()) {
      try {
        executionRepository.save(execution);
        log.info("Persisted non-MarketMaker execution to database for user: {}", username);
      } catch (Exception e) {
        log.error("Failed to persist execution to database for user: {}", username, e);
      }
    }
    
    log.info("Added execution to queue for user: {}", username);
  }

  public List<Execution> pollExecutions(String username, int maxCount) {
    BlockingQueue<Execution> userQueue = userExecutionQueues.get(username);
    if (userQueue == null) {
      return Collections.emptyList();
    }

    List<Execution> executions = new ArrayList<>();
    userQueue.drainTo(executions, maxCount);

    log.info("Polled {} executions for user: {}", executions.size(), username);
    return executions;
  }

  public List<Execution> pollAllExecutions(String username) {
    BlockingQueue<Execution> userQueue = userExecutionQueues.get(username);
    if (userQueue == null) {
      return Collections.emptyList();
    }

    List<Execution> executions = new ArrayList<>();
    userQueue.drainTo(executions);

    log.info("Polled all {} executions for user: {}", executions.size(), username);
    return executions;
  }

  public int getQueueSize(String username) {
    BlockingQueue<Execution> userQueue = userExecutionQueues.get(username);
    return userQueue != null ? userQueue.size() : 0;
  }
}
