package com.ys.exch_sim.domain.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ys.exch_sim.domain.message.field.Symbol;
import com.ys.exch_sim.domain.service.OrderService;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(
    name = "app.data-migration.enabled",
    havingValue = "true",
    matchIfMissing = false)
public class DataMigrationInitializer implements CommandLineRunner {

  private final ObjectMapper objectMapper;
  private final PasswordEncoder passwordEncoder;

  @Autowired private OrderService orderService;
  @Autowired private com.ys.exch_sim.domain.position.PositionManager positionManager;

  public DataMigrationInitializer(ObjectMapper objectMapper, PasswordEncoder passwordEncoder) {
    this.objectMapper = objectMapper;
    this.passwordEncoder = passwordEncoder;
  }

  @Value("${app.data-migration.enabled:true}")
  private boolean migrationEnabled;

  @Value("${app.data-migration.phase:add-persistence}")
  private String migrationPhase;

  // Default users configuration
  @Value("${app.default-users[0].username:admin}")
  private String defaultUser1Username;

  @Value("${app.default-users[0].password:admin123}")
  private String defaultUser1Password;

  @Value("${app.default-users[0].roles:ROLE_ADMIN,ROLE_USER}")
  private String defaultUser1Roles;

  @Value("${app.default-users[1].username:trader001}")
  private String defaultUser2Username;

  @Value("${app.default-users[1].password:trader123}")
  private String defaultUser2Password;

  @Value("${app.default-users[1].roles:ROLE_USER}")
  private String defaultUser2Roles;

  @Value("${app.default-users[2].username:marketmaker1}")
  private String defaultUser3Username;

  @Value("${app.default-users[2].password:mm123}")
  private String defaultUser3Password;

  @Value("${app.default-users[2].roles:ROLE_MARKET_MAKER,ROLE_USER}")
  private String defaultUser3Roles;

  // Instruments configuration
  @Value("${app.instruments.G_BTCJPY.name:G_BTCJPY}")
  private String gBtcJpyName;

  @Value("${app.instruments.G_BTCJPY.priceMultiplier:1}")
  private int gBtcJpyPriceMultiplier;

  @Value("${app.instruments.G_BTCJPY.qtyMultiplier:1000}")
  private int gBtcJpyQtyMultiplier;

  @Value("${app.instruments.G_BTCJPY.type:Cash}")
  private String gBtcJpyType;

  @Value("${app.instruments.G_FX_BTCJPY.name:G_FX_BTCJPY}")
  private String gFxBtcJpyName;

  @Value("${app.instruments.G_FX_BTCJPY.priceMultiplier:1}")
  private int gFxBtcJpyPriceMultiplier;

  @Value("${app.instruments.G_FX_BTCJPY.qtyMultiplier:1000}")
  private int gFxBtcJpyQtyMultiplier;

  @Value("${app.instruments.G_FX_BTCJPY.type:FX}")
  private String gFxBtcJpyType;

  @Value("${app.instruments.B_BTCJPY.name:B_BTCJPY}")
  private String bBtcJpyName;

  @Value("${app.instruments.B_BTCJPY.priceMultiplier:1}")
  private int bBtcJpyPriceMultiplier;

  @Value("${app.instruments.B_BTCJPY.qtyMultiplier:1000}")
  private int bBtcJpyQtyMultiplier;

  @Value("${app.instruments.B_BTCJPY.type:Cash}")
  private String bBtcJpyType;

  @Value("${app.instruments.B_FX_BTCJPY.name:B_FX_BTCJPY}")
  private String bFxBtcJpyName;

  @Value("${app.instruments.B_FX_BTCJPY.priceMultiplier:1}")
  private int bFxBtcJpyPriceMultiplier;

  @Value("${app.instruments.B_FX_BTCJPY.qtyMultiplier:1000}")
  private int bFxBtcJpyQtyMultiplier;

  @Value("${app.instruments.B_FX_BTCJPY.type:FX}")
  private String bFxBtcJpyType;

  @Value("${app.instruments.TESTJPY.name:Test/Japanese Yen}")
  private String testJpyName;

  @Value("${app.instruments.TESTJPY.priceMultiplier:1}")
  private int testJpyPriceMultiplier;

  @Value("${app.instruments.TESTJPY.qtyMultiplier:1000}")
  private int testJpyQtyMultiplier;

  @Value("${app.instruments.TESTJPY.type:Cash}")
  private String testJpyType;

  private List<Symbol> symbols = new ArrayList<>();

  @Override
  public void run(String... args) throws Exception {
    if (!migrationEnabled) {
      log.info("Data migration is disabled");
      return;
    }

    long startTime = System.currentTimeMillis();
    log.info("========== DATA MIGRATION START ==========");
    log.info("Starting data migration initialization - Phase: {}", migrationPhase);

    // Initialize instruments (stored in memory/static configuration)
    long instrumentsStart = System.currentTimeMillis();
    log.info("[INSTRUMENTS] Starting initialization...");
    initializeInstruments();
    long instrumentsEnd = System.currentTimeMillis();
    log.info("[INSTRUMENTS] Completed in {} ms", (instrumentsEnd - instrumentsStart));

    // Initialize MarketBoards for all instruments
    long boardsStart = System.currentTimeMillis();
    log.info("[MARKET_BOARDS] Starting initialization...");
    initializeMarketBoards();
    long boardsEnd = System.currentTimeMillis();
    log.info("[MARKET_BOARDS] Completed in {} ms", (boardsEnd - boardsStart));

    // Initialize default users with cash balances
    long usersStart = System.currentTimeMillis();
    log.info("[DEFAULT_USERS] Starting initialization...");
    initializeDefaultUsersCashBalance();
    long usersEnd = System.currentTimeMillis();
    log.info("[DEFAULT_USERS] Completed in {} ms", (usersEnd - usersStart));

    long endTime = System.currentTimeMillis();
    log.info("========== DATA MIGRATION COMPLETE ==========");
    log.info("Total data migration time: {} ms ({} seconds)", (endTime - startTime), (endTime - startTime) / 1000.0);
  }

  private void initializeInstruments() {
    log.info("Initializing instruments configuration...");

    // Log instrument configurations - these are typically handled by the application
    // configuration and don't need database persistence in this current implementation
    logInstrumentConfig(
        "G_BTCJPY", gBtcJpyName, gBtcJpyPriceMultiplier, gBtcJpyQtyMultiplier, gBtcJpyType);
    symbols.add(new Symbol(gBtcJpyName, gBtcJpyPriceMultiplier, gBtcJpyQtyMultiplier));
    logInstrumentConfig(
        "G_FX_BTCJPY",
        gFxBtcJpyName,
        gFxBtcJpyPriceMultiplier,
        gFxBtcJpyQtyMultiplier,
        gFxBtcJpyType);
    symbols.add(new Symbol(gFxBtcJpyName, gFxBtcJpyPriceMultiplier, gFxBtcJpyQtyMultiplier));
    logInstrumentConfig(
        "B_BTCJPY", bBtcJpyName, bBtcJpyPriceMultiplier, bBtcJpyQtyMultiplier, bBtcJpyType);
    symbols.add(new Symbol(bBtcJpyName, bBtcJpyPriceMultiplier, bBtcJpyQtyMultiplier));
    logInstrumentConfig(
        "B_FX_BTCJPY",
        bFxBtcJpyName,
        bFxBtcJpyPriceMultiplier,
        bFxBtcJpyQtyMultiplier,
        bFxBtcJpyType);
    symbols.add(new Symbol(bFxBtcJpyName, bFxBtcJpyPriceMultiplier, bFxBtcJpyQtyMultiplier));
    logInstrumentConfig(
        "TESTJPY", testJpyName, testJpyPriceMultiplier, testJpyQtyMultiplier, testJpyType);
    symbols.add(new Symbol("TESTJPY", testJpyPriceMultiplier, testJpyQtyMultiplier));

    log.info("Instruments configuration initialized");
  }

  private void logInstrumentConfig(
      String key, String name, int priceMultiplier, int qtyMultiplier, String type) {
    log.info(
        "Instrument {}: name={}, priceMultiplier={}, qtyMultiplier={}, type={}",
        key,
        name,
        priceMultiplier,
        qtyMultiplier,
        type);
  }

  /** 全てのInstrumentに対してMarketBoardを初期化する */
  private void initializeMarketBoards() {
    log.info("Initializing MarketBoards for all instruments...");

    // 設定されている全てのInstrumentシンボルに対してMarketBoardを作成

    for (Symbol symbol : symbols) {
      orderService.initializeMarketBoard(symbol);
    }

    log.info("MarketBoards initialization completed for {} symbols", symbols.size());
    log.info("Available symbols: {}", orderService.getAvailableSymbols());
  }
  
  /**
   * デフォルトユーザーに初期現金残高を設定
   */
  private void initializeDefaultUsersCashBalance() {
    long methodStart = System.currentTimeMillis();
    log.info("[INIT_USERS] Starting default users initialization...");

    // デフォルトユーザー一覧
    String[] defaultUsers = {"admin", "trader001", "marketmaker1", "yukio001", "trader002",
                           "trader003", "testuser", "yukio002", "newuser001", "testuser2",
                           "test01", "test02", "yukio003"};

    double initialCashBalance = 1000000.0; // 100万円

    for (int i = 0; i < defaultUsers.length; i++) {
      String username = defaultUsers[i];
      long userStart = System.currentTimeMillis();

      try {
        log.info("[INIT_USERS] [{}/{}] Processing user: {} - START", (i + 1), defaultUsers.length, username);

        // 既に現金ポジションが存在するかチェック
        long checkStart = System.currentTimeMillis();
        com.ys.exch_sim.domain.position.Position cashPosition = positionManager.getPosition(username, "JPY");
        long checkEnd = System.currentTimeMillis();
        log.info("[INIT_USERS] [{}/{}] getPosition() took {} ms", (i + 1), defaultUsers.length, (checkEnd - checkStart));

        if (cashPosition == null) {
          // 現金ポジションが存在しない場合のみ初期化
          long initStart = System.currentTimeMillis();
          positionManager.initializeUserWithCash(username, initialCashBalance);
          long initEnd = System.currentTimeMillis();
          log.info("[INIT_USERS] [{}/{}] initializeUserWithCash() took {} ms", (i + 1), defaultUsers.length, (initEnd - initStart));
          log.info("[INIT_USERS] [{}/{}] Initialized cash balance for user: {} - Amount: {}", (i + 1), defaultUsers.length, username, initialCashBalance);
        } else {
          log.info("[INIT_USERS] [{}/{}] User {} already has cash balance: {}", (i + 1), defaultUsers.length, username, cashPosition.getTotalBuyAmount());
        }

        long userEnd = System.currentTimeMillis();
        log.info("[INIT_USERS] [{}/{}] Processing user: {} - COMPLETED in {} ms", (i + 1), defaultUsers.length, username, (userEnd - userStart));
      } catch (Exception e) {
        long userEnd = System.currentTimeMillis();
        log.warn("[INIT_USERS] [{}/{}] Failed to initialize cash balance for user: {} in {} ms - {}", (i + 1), defaultUsers.length, username, (userEnd - userStart), e.getMessage(), e);
      }
    }

    long methodEnd = System.currentTimeMillis();
    log.info("[INIT_USERS] Default users cash balance initialization completed in {} ms", (methodEnd - methodStart));
  }
}
