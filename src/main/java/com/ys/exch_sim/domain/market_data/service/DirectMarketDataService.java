package com.ys.exch_sim.domain.market_data.service;

import com.ys.exch_sim.domain.config.InstrumentConfig;
import com.ys.exch_sim.domain.market_data.config.MarketDataClientConfig;
import com.ys.exch_sim.domain.market_data.dto.ExternalMarketBoardData;
import com.ys.exch_sim.domain.market_data.dto.ExternalTradeData;
import com.ys.exch_sim.domain.market_data.queue.OrderedTradeProcessor;
import com.ys.exch_sim.domain.service.MarketDataSyncService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** 直接マーケットデータを処理するサービス WebSocketクライアントから受信したデータを既存のシステムに統合 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DirectMarketDataService {

  private final MarketDataSyncService marketDataSyncService;
  private final InstrumentConfig instrumentConfig;
  private final MarketDataClientConfig clientConfig;
  private final OrderedTradeProcessor orderedTradeProcessor;

  /** 非同期マーケットボード処理 - WebSocketスレッドをブロックしない */
  @Async("marketDataTaskExecutor")
  public CompletableFuture<Void> processMarketBoardAsync(ExternalMarketBoardData data) {
    return CompletableFuture.runAsync(() -> processMarketBoard(data));
  }

  /** 同期マーケットボード処理（テスト用・デバッグ用） */
  public void processMarketBoard(ExternalMarketBoardData data) {
    try {
      String targetSymbol = mapSymbol(data.exchange(), data.symbol());
      if (targetSymbol == null) {
        log.debug("🔍 No symbol mapping found for {}:{}", data.exchange(), data.symbol());
        return;
      }

      log.debug(
          "📊 Processing MarketBoard for {} -> {} with {} bids, {} asks",
          data.exchange() + ":" + data.symbol(),
          targetSymbol,
          data.bids().size(),
          data.asks().size());

      // ExternalMarketBoardDataを作成
      ExternalMarketBoardData targetData = new ExternalMarketBoardData(
          data.exchange(),
          targetSymbol,
          data.bids(),
          data.asks(),
          data.timestamp()
      );

      // MarketDataSyncServiceに直接処理を委譲
      marketDataSyncService.updateMarketBoard(targetData);

      log.info(
          "✅ MarketBoard processed for {} - BestBid: {}, BestAsk: {}",
          targetSymbol,
          data.getBestBidPrice(),
          data.getBestAskPrice());

    } catch (Exception e) {
      log.error(
          "❌ Error processing market board for {}:{} - {}",
          data.exchange(),
          data.symbol(),
          e.getMessage(),
          e);
    }
  }

  /** 非同期取引データ処理 - WebSocketスレッドをブロックしない */
  @Async("marketDataTaskExecutor")
  public CompletableFuture<Void> processTradeAsync(ExternalTradeData data) {
    return CompletableFuture.runAsync(() -> B_processTrade(data));
  }

  /** 同期取引データ処理 - 順序保証付きで処理 */
  public void B_processTrade(ExternalTradeData data) {
    try {
      String targetSymbol = mapSymbol(data.exchange(), data.symbol());
      if (targetSymbol == null) {
        log.debug("🔍 No symbol mapping found for {}:{}", data.exchange(), data.symbol());
        return;
      }

      log.debug(
          "💰 Processing Trade for {} -> {} - side: {}, price: {}, quantity: {}",
          data.exchange() + ":" + data.symbol(),
          targetSymbol,
          data.side(),
          data.price(),
          data.quantity());

      // 順序保証付きでトレード処理を委譲
      orderedTradeProcessor.submitTrade(targetSymbol, data);

      log.info(
          "✅ Trade submitted for ordered processing: {} - side: {}, price: {}, quantity: {},"
              + " amount: {}",
          targetSymbol,
          data.side(),
          data.price(),
          data.quantity(),
          data.getNotionalAmount());

    } catch (Exception e) {
      log.error(
          "❌ Error processing trade for {}:{} - {}",
          data.exchange(),
          data.symbol(),
          e.getMessage(),
          e);
    }
  }

  /** シンボルマッピング */
  private String mapSymbol(String exchange, String symbol) {
    String mappedSymbol = clientConfig.mapSymbol(exchange.toUpperCase(), symbol);

    if (mappedSymbol != null && instrumentConfig.isValidSymbol(mappedSymbol)) {
      return mappedSymbol;
    }

    return null;
  }


  /** サービス統計情報を取得 */
  public String getServiceStats() {
    int instrumentCount =
        instrumentConfig.getInstruments() != null ? instrumentConfig.getInstruments().size() : 0;
    return String.format(
        "DirectMarketDataService [Active: true, Instrument count: %d]", instrumentCount);
  }
}
