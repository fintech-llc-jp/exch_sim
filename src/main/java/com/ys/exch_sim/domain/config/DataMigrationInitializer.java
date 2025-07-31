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

    log.info("Starting data migration initialization - Phase: {}", migrationPhase);

    // Default users are now managed in BigQuery, not in JSON files
    log.info("Default users should be manually created in BigQuery");

    // Initialize instruments (stored in memory/static configuration)
    initializeInstruments();

    // Initialize MarketBoards for all instruments
    initializeMarketBoards();

    log.info("Data migration initialization completed successfully");
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
}
