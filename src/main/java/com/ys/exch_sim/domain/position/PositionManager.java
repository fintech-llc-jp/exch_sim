package com.ys.exch_sim.domain.position;

import com.ys.exch_sim.domain.bigquery.BigQueryEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryPositionEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryService;
import com.ys.exch_sim.domain.bigquery.BigQueryTradeHistoryEntity;
import com.ys.exch_sim.domain.bigquery.BigQueryWriter;
import com.ys.exch_sim.domain.database.DatabaseService;
import com.ys.exch_sim.domain.exception.InsufficientFundsException;
import com.ys.exch_sim.domain.message.field.Side;
import com.ys.exch_sim.domain.order_exec.Execution;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class PositionManager {

  // DatabaseServiceインターフェースを使用（BigQueryまたはPostgreSQL）
  @Autowired(required = false)
  private DatabaseService databaseService;

  // 後方互換性のため、既存のBigQueryServiceとBigQueryWriterも保持
  @Autowired(required = false)
  private BigQueryService bigQueryService;

  @Autowired(required = false)
  private BigQueryWriter bigQueryWriter;

  // Configuration flags
  @Value("${app.data-migration.memory-cache-enabled:true}")
  private boolean memoryCacheEnabled;

  // Removed: databasePersistenceEnabled flag - BigQuery is now the primary storage

  @Value("${app.data-migration.bigquery-enabled:false}")
  private boolean bigQueryEnabled;

  // ユーザー別・銘柄別のポジション管理（メモリキャッシュ）
  private final ConcurrentHashMap<String, ConcurrentHashMap<String, Position>> positionsCache =
      new ConcurrentHashMap<>();

  // 取引履歴（メモリキャッシュ）
  private final List<TradeHistory> tradeHistoriesCache =
      Collections.synchronizedList(new ArrayList<>());

  // Main constructor for Spring
  @Autowired
  public PositionManager(
      @Autowired(required = false) DatabaseService databaseService,
      @Autowired(required = false) BigQueryService bigQueryService,
      @Autowired(required = false) BigQueryWriter bigQueryWriter) {
    this.databaseService = databaseService;
    this.bigQueryService = bigQueryService;
    this.bigQueryWriter = bigQueryWriter;
  }

  // Test-only constructor
  public PositionManager(boolean memoryCacheEnabled) {
    this.bigQueryService = null;
    this.bigQueryWriter = null;
    this.memoryCacheEnabled = memoryCacheEnabled;
  }

  // Test-only constructor with repositories (legacy support)
  public PositionManager(
      PositionRepository positionRepository,
      TradeHistoryRepository tradeHistoryRepository,
      boolean memoryCacheEnabled) {
    this.bigQueryService = null;
    this.bigQueryWriter = null;
    this.memoryCacheEnabled = memoryCacheEnabled;
  }

  @Transactional
  public void processExecution(Execution execution) {
    if (execution == null || execution.getOrder() == null) {
      log.warn("Invalid execution or order is null");
      return;
    }

    String username = execution.getOrder().getUsername();
    String symbol = execution.getOrder().getSymbol().getName();
    Side side = execution.getOrder().getSide();

    // 詳細なログを追加して数量計算の内訳を確認
    long rawQty = execution.getLastQty().getLongQty();
    long qtyMultiplier = execution.getLastQty().getSymbol().getQtyMultiplier();
    double quantity = (double) rawQty / qtyMultiplier;

    long rawPx = execution.getLastPx().getLongPx();
    long pxMultiplier = execution.getLastPx().getSymbol().getPxMultiplier();
    double price = (double) rawPx / pxMultiplier;

    // 相手方のユーザー名を取得（約定相手）
    String counterPartyUsername = execution.getCounterPartyUsername();

    log.info(
        "Processing execution for user: {}, symbol: {}, side: {}, rawQty: {}, qtyMultiplier: {},"
            + " qty: {}, rawPx: {}, pxMultiplier: {}, price: {}",
        username,
        symbol,
        side,
        rawQty,
        qtyMultiplier,
        quantity,
        rawPx,
        pxMultiplier,
        price);

    try {
      // ポジション更新
      Position position = getOrCreatePosition(username, symbol);

      if (side == Side.BUY) {
        position.addBuyTrade(quantity, price);
        // 買い注文：現金を減らす
        double amount = quantity * price;
        updateCashBalance(username, -amount);
      } else if (side == Side.SELL) {
        position.addSellTrade(quantity, price);
        // 売り注文：現金を増やす
        double amount = quantity * price;
        updateCashBalance(username, amount);
      }

      // DatabaseServiceに保存（DatabaseServiceが利用可能な場合）
      if (databaseService != null) {
        try {
          databaseService.upsertPosition(position);
        } catch (Exception e) {
          log.error("Error saving position via DatabaseService", e);
        }
      }
      // 後方互換性のため、BigQueryServiceが直接利用可能な場合もサポート
      else if (bigQueryEnabled && bigQueryService != null) {
        savePositionToBigQuery(position);
      }

      // 取引履歴を記録
      TradeHistory tradeHistory =
          new TradeHistory(
              execution.getExecID().getId(),
              username,
              symbol,
              side.toString(),
              quantity,
              price,
              counterPartyUsername,
              execution.getOrder().getClOrdID().getId());

      // メモリキャッシュに追加
      if (memoryCacheEnabled) {
        tradeHistoriesCache.add(tradeHistory);
      }

      // DatabaseServiceに保存（DatabaseServiceが利用可能な場合）
      if (databaseService != null) {
        try {
          databaseService.insertTradeHistory(tradeHistory);
        } catch (Exception e) {
          log.error("Error saving trade history via DatabaseService", e);
        }
      }
      // 後方互換性のため、BigQueryServiceが直接利用可能な場合もサポート
      else if (bigQueryEnabled && bigQueryService != null) {
        saveTradeHistoryToBigQuery(tradeHistory);
      }

      log.info(
          "Position updated for user: {}, symbol: {}, netQty: {}, realizedPnL: {}",
          username,
          symbol,
          position.getNetQty(),
          position.getRealizedPnL());

    } catch (Exception e) {
      log.error("Error processing execution for user: " + username, e);
    }
  }

  public Position getPosition(String username, String symbol) {
    if (memoryCacheEnabled) {
      ConcurrentHashMap<String, Position> userPositions = positionsCache.get(username);
      if (userPositions != null) {
        return userPositions.get(symbol.toUpperCase());
      }
    }

    if (bigQueryEnabled && bigQueryService != null) {
      BigQueryPositionEntity bigQueryPosition =
          bigQueryService.queryPosition(username, symbol.toUpperCase());
      if (bigQueryPosition != null) {
        return bigQueryPosition.toPosition();
      }
    }

    return null;
  }

  public List<Position> getAllPositions(String username) {
    List<Position> positions = new ArrayList<>();

    log.debug(
        "Getting positions for user: {} - bigQueryEnabled: {}, memoryCacheEnabled: {}",
        username,
        bigQueryEnabled,
        memoryCacheEnabled);

    if (memoryCacheEnabled) {
      ConcurrentHashMap<String, Position> userPositions = positionsCache.get(username);
      if (userPositions != null) {
        positions.addAll(userPositions.values());
        log.debug("Found {} positions in memory cache for user: {}", positions.size(), username);
      }
    }

    // DatabaseServiceから取得を試みる
    if (databaseService != null && positions.isEmpty()) {
      log.debug("Querying DatabaseService for positions for user: {}", username);
      positions = databaseService.queryAllPositions(username);
      log.debug("Found {} positions via DatabaseService for user: {}", positions.size(), username);

      // DatabaseServiceから読み込んだデータをメモリキャッシュに保存
      if (memoryCacheEnabled && !positions.isEmpty()) {
        ConcurrentHashMap<String, Position> userPositions =
            positionsCache.computeIfAbsent(username, k -> new ConcurrentHashMap<>());
        for (Position position : positions) {
          userPositions.put(position.getSymbol(), position);
          log.debug(
              "Cached position from DatabaseService: {}_{}",
              position.getUsername(),
              position.getSymbol());
        }
      }
    }
    // 後方互換性のため、BigQueryServiceが直接利用可能な場合もサポート
    else if (bigQueryService != null && positions.isEmpty()) {
      log.debug("Querying BigQuery for positions for user: {}", username);
      List<BigQueryPositionEntity> bigQueryPositions = bigQueryService.queryAllPositions(username);
      log.debug("Found {} positions in BigQuery for user: {}", bigQueryPositions.size(), username);
      positions =
          bigQueryPositions.stream()
              .map(BigQueryPositionEntity::toPosition)
              .collect(Collectors.toList());

      // BigQueryから読み込んだデータをメモリキャッシュに保存
      if (memoryCacheEnabled && !positions.isEmpty()) {
        ConcurrentHashMap<String, Position> userPositions =
            positionsCache.computeIfAbsent(username, k -> new ConcurrentHashMap<>());
        for (Position position : positions) {
          userPositions.put(position.getSymbol(), position);
          log.debug(
              "Cached position from BigQuery: {}_{}", position.getUsername(), position.getSymbol());
        }
      }
    }

    log.info("Total positions returned for user {}: {}", username, positions.size());
    return positions;
  }

  public List<TradeHistory> getTradeHistory(String username) {
    List<TradeHistory> trades = new ArrayList<>();

    if (memoryCacheEnabled) {
      trades =
          tradeHistoriesCache.stream()
              .filter(history -> username.equals(history.getUsername()))
              .sorted((h1, h2) -> h2.getTimestamp().compareTo(h1.getTimestamp())) // 新しい順
              .collect(Collectors.toList());
      if (!trades.isEmpty()) {
        return trades;
      }
    }

    if (bigQueryService != null) {
      List<BigQueryTradeHistoryEntity> bigQueryTradeHistories =
          bigQueryService.queryTradeHistory(username);
      trades =
          bigQueryTradeHistories.stream()
              .map(BigQueryTradeHistoryEntity::toTradeHistory)
              .collect(Collectors.toList());

      // BigQueryから読み込んだデータをメモリキャッシュに追加（重複チェック）
      if (memoryCacheEnabled && !trades.isEmpty()) {
        Set<String> existingExecIds =
            tradeHistoriesCache.stream().map(TradeHistory::getExecID).collect(Collectors.toSet());

        List<TradeHistory> newTrades =
            trades.stream()
                .filter(trade -> !existingExecIds.contains(trade.getExecID()))
                .collect(Collectors.toList());

        tradeHistoriesCache.addAll(newTrades);
      }

      if (!trades.isEmpty()) {
        return trades;
      }
    }

    return trades;
  }

  public List<TradeHistory> getTradeHistory(String username, String symbol) {
    List<TradeHistory> trades = new ArrayList<>();

    if (memoryCacheEnabled) {
      trades =
          tradeHistoriesCache.stream()
              .filter(
                  history ->
                      username.equals(history.getUsername())
                          && symbol.equalsIgnoreCase(history.getSymbol()))
              .sorted((h1, h2) -> h2.getTimestamp().compareTo(h1.getTimestamp())) // 新しい順
              .collect(Collectors.toList());
      if (!trades.isEmpty()) {
        return trades;
      }
    }

    if (bigQueryService != null) {
      List<BigQueryTradeHistoryEntity> bigQueryTradeHistories =
          bigQueryService.queryTradeHistory(username, symbol);
      trades =
          bigQueryTradeHistories.stream()
              .map(BigQueryTradeHistoryEntity::toTradeHistory)
              .collect(Collectors.toList());

      // BigQueryから読み込んだデータをメモリキャッシュに追加（重複チェック）
      if (memoryCacheEnabled && !trades.isEmpty()) {
        Set<String> existingExecIds =
            tradeHistoriesCache.stream().map(TradeHistory::getExecID).collect(Collectors.toSet());

        List<TradeHistory> newTrades =
            trades.stream()
                .filter(trade -> !existingExecIds.contains(trade.getExecID()))
                .collect(Collectors.toList());

        tradeHistoriesCache.addAll(newTrades);
      }

      if (!trades.isEmpty()) {
        return trades;
      }
    }

    return trades;
  }

  public List<TradeHistory> getTradeHistory(String username, int limit) {
    List<TradeHistory> trades = new ArrayList<>();

    if (memoryCacheEnabled) {
      trades =
          tradeHistoriesCache.stream()
              .filter(history -> username.equals(history.getUsername()))
              .sorted((h1, h2) -> h2.getTimestamp().compareTo(h1.getTimestamp())) // 新しい順
              .limit(limit)
              .collect(Collectors.toList());
      if (trades.size() >= limit) {
        return trades;
      }
    }

    if (bigQueryService != null) {
      List<BigQueryTradeHistoryEntity> bigQueryTradeHistories =
          bigQueryService.queryTradeHistory(username, limit);
      trades =
          bigQueryTradeHistories.stream()
              .map(BigQueryTradeHistoryEntity::toTradeHistory)
              .collect(Collectors.toList());

      // BigQueryから読み込んだデータをメモリキャッシュに追加（重複チェック）
      if (memoryCacheEnabled && !trades.isEmpty()) {
        Set<String> existingExecIds =
            tradeHistoriesCache.stream().map(TradeHistory::getExecID).collect(Collectors.toSet());

        List<TradeHistory> newTrades =
            trades.stream()
                .filter(trade -> !existingExecIds.contains(trade.getExecID()))
                .collect(Collectors.toList());

        tradeHistoriesCache.addAll(newTrades);
      }

      if (!trades.isEmpty()) {
        return trades;
      }
    }

    return trades;
  }

  public double getTotalRealizedPnL(String username) {
    if (memoryCacheEnabled) {
      ConcurrentHashMap<String, Position> userPositions = positionsCache.get(username);
      if (userPositions != null) {
        return userPositions.values().stream().mapToDouble(Position::getRealizedPnL).sum();
      }
    }

    if (bigQueryEnabled && bigQueryService != null) {
      List<BigQueryPositionEntity> bigQueryPositions = bigQueryService.queryAllPositions(username);
      return bigQueryPositions.stream()
          .mapToDouble(
              position -> position.getRealizedPnL() != null ? position.getRealizedPnL() : 0.0)
          .sum();
    }

    return 0.0;
  }

  public double getTotalUnrealizedPnL(String username, Map<String, Double> currentPrices) {
    if (memoryCacheEnabled) {
      ConcurrentHashMap<String, Position> userPositions = positionsCache.get(username);
      if (userPositions != null) {
        return userPositions.values().stream()
            .mapToDouble(
                position -> {
                  Double currentPrice = currentPrices.get(position.getSymbol());
                  return currentPrice != null ? position.getUnrealizedPnL(currentPrice) : 0.0;
                })
            .sum();
      }
    }

    if (bigQueryEnabled && bigQueryService != null) {
      List<BigQueryPositionEntity> bigQueryPositions = bigQueryService.queryAllPositions(username);
      return bigQueryPositions.stream()
          .map(BigQueryPositionEntity::toPosition)
          .mapToDouble(
              position -> {
                Double currentPrice = currentPrices.get(position.getSymbol());
                return currentPrice != null ? position.getUnrealizedPnL(currentPrice) : 0.0;
              })
          .sum();
    }

    return 0.0;
  }

  public double getTotalPnL(String username, Map<String, Double> currentPrices) {
    return getTotalRealizedPnL(username) + getTotalUnrealizedPnL(username, currentPrices);
  }

  private Position getOrCreatePosition(String username, String symbol) {
    if (memoryCacheEnabled) {
      return positionsCache
          .computeIfAbsent(username, k -> new ConcurrentHashMap<>())
          .computeIfAbsent(
              symbol.toUpperCase(),
              k -> {
                // Check BigQuery before creating new position
                if (bigQueryEnabled && bigQueryService != null) {
                  BigQueryPositionEntity bigQueryPosition =
                      bigQueryService.queryPosition(username, symbol.toUpperCase());
                  if (bigQueryPosition != null) {
                    log.info(
                        "Loaded existing position from BigQuery for user: {}, symbol: {}",
                        username,
                        symbol);
                    return bigQueryPosition.toPosition();
                  }
                }

                log.info("Creating new position for user: {}, symbol: {}", username, symbol);
                return new Position(username, symbol.toUpperCase());
              });
    }

    // If memory cache is disabled, check BigQuery first
    if (bigQueryEnabled && bigQueryService != null) {
      BigQueryPositionEntity bigQueryPosition =
          bigQueryService.queryPosition(username, symbol.toUpperCase());
      if (bigQueryPosition != null) {
        return bigQueryPosition.toPosition();
      }
    }

    // Fallback
    log.info("Creating new position for user: {}, symbol: {}", username, symbol);
    return new Position(username, symbol.toUpperCase());
  }

  // 統計情報取得用メソッド
  public int getTotalTradeCount(String username) {
    if (memoryCacheEnabled && !tradeHistoriesCache.isEmpty()) {
      return (int)
          tradeHistoriesCache.stream()
              .filter(history -> username.equals(history.getUsername()))
              .count();
    }

    if (bigQueryEnabled && bigQueryService != null) {
      List<BigQueryTradeHistoryEntity> bigQueryTradeHistories =
          bigQueryService.queryTradeHistory(username);
      return bigQueryTradeHistories.size();
    }

    return 0;
  }

  public double getTotalTradingVolume(String username) {
    if (memoryCacheEnabled && !tradeHistoriesCache.isEmpty()) {
      return tradeHistoriesCache.stream()
          .filter(history -> username.equals(history.getUsername()))
          .mapToDouble(TradeHistory::getAmount)
          .sum();
    }

    if (bigQueryEnabled && bigQueryService != null) {
      List<BigQueryTradeHistoryEntity> bigQueryTradeHistories =
          bigQueryService.queryTradeHistory(username);
      return bigQueryTradeHistories.stream()
          .mapToDouble(trade -> trade.getAmount() != null ? trade.getAmount() : 0.0)
          .sum();
    }

    return 0.0;
  }

  public Map<String, Long> getSymbolTradeCounts(String username) {
    if (memoryCacheEnabled && !tradeHistoriesCache.isEmpty()) {
      return tradeHistoriesCache.stream()
          .filter(history -> username.equals(history.getUsername()))
          .collect(Collectors.groupingBy(TradeHistory::getSymbol, Collectors.counting()));
    }

    if (bigQueryEnabled && bigQueryService != null) {
      List<BigQueryTradeHistoryEntity> bigQueryTradeHistories =
          bigQueryService.queryTradeHistory(username);
      return bigQueryTradeHistories.stream()
          .collect(
              Collectors.groupingBy(BigQueryTradeHistoryEntity::getSymbol, Collectors.counting()));
    }

    return new HashMap<>();
  }

  // テスト用メソッド
  public void clearAllData() {
    if (memoryCacheEnabled) {
      positionsCache.clear();
      tradeHistoriesCache.clear();
    }

    log.info("All position and trade history data cleared");
  }

  // 現金管理メソッド
  private static final String CASH_SYMBOL = "JPY";

  /** ユーザーの現金残高を取得 */
  public double getCashBalance(String username) {
    Position cashPosition = getPosition(username, CASH_SYMBOL);
    if (cashPosition == null) {
      // 現金ポジションが存在しない場合は0を返す
      return 0.0;
    }
    // 現金残高は 買い金額 - 売り金額 で計算
    return cashPosition.getTotalBuyAmount() - cashPosition.getTotalSellAmount();
  }

  /** ユーザーの現金残高を更新 */
  public void updateCashBalance(String username, double amount) {
    Position cashPosition = getOrCreatePosition(username, CASH_SYMBOL);

    // 現金増加の場合
    if (amount > 0) {
      cashPosition.addBuyTrade(amount, 1.0); // 現金は価格1円で管理
    }
    // 現金減少の場合
    else if (amount < 0) {
      double absAmount = Math.abs(amount);
      // 現在の残高をチェック
      double currentBalance = getCashBalance(username);
      if (currentBalance >= absAmount) {
        cashPosition.addSellTrade(absAmount, 1.0); // 現金は価格1円で管理
      } else {
        log.error(
            "Insufficient cash balance for user: {}. Required: {}, Available: {}",
            username,
            absAmount,
            currentBalance);
        throw new InsufficientFundsException(username, absAmount, currentBalance);
      }
    }

    // BigQueryに保存
    if (bigQueryEnabled && bigQueryService != null) {
      savePositionToBigQuery(cashPosition);
    }

    log.info(
        "Updated cash balance for user: {}. New balance: {}", username, getCashBalance(username));
  }

  /** ユーザーが必要な現金を持っているかチェック */
  public boolean hasSufficientFunds(String username, double requiredAmount) {
    double currentBalance = getCashBalance(username);
    return currentBalance >= requiredAmount;
  }

  /** ユーザーに初期現金残高を設定 */
  public void initializeUserWithCash(String username, double initialAmount) {
    Position cashPosition = new Position(username, CASH_SYMBOL);
    cashPosition.addBuyTrade(initialAmount, 1.0); // 現金は価格1円で管理

    // キャッシュに追加
    if (memoryCacheEnabled) {
      positionsCache
          .computeIfAbsent(username, k -> new ConcurrentHashMap<>())
          .put(CASH_SYMBOL, cashPosition);
    }

    // BigQueryに保存
    if (bigQueryEnabled && bigQueryService != null) {
      savePositionToBigQuery(cashPosition);
    }

    log.info("Initialized user {} with cash balance: {}", username, initialAmount);
  }

  // BigQuery保存メソッド - キューベースの非ブロッキング処理
  private void savePositionToBigQuery(Position position) {
    try {
      if (bigQueryWriter != null) {
        // キューに追加（非ブロッキング）
        bigQueryWriter.enqueue(BigQueryEntity.position(position));
        log.debug(
            "Position enqueued to BigQuery writer: {}_{}",
            position.getUsername(),
            position.getSymbol());
      }
    } catch (Exception e) {
      log.error(
          "Error enqueuing position to BigQuery: "
              + position.getUsername()
              + "_"
              + position.getSymbol(),
          e);
    }
  }

  // BigQuery保存メソッド - キューベースの非ブロッキング処理
  private void saveTradeHistoryToBigQuery(TradeHistory tradeHistory) {
    try {
      if (bigQueryWriter != null) {
        // キューに追加（非ブロッキング）
        bigQueryWriter.enqueue(BigQueryEntity.tradeHistory(tradeHistory));
        log.debug("Trade history enqueued to BigQuery writer: {}", tradeHistory.getExecID());
      }
    } catch (Exception e) {
      log.error("Error enqueuing trade history to BigQuery: " + tradeHistory.getExecID(), e);
    }
  }
}
