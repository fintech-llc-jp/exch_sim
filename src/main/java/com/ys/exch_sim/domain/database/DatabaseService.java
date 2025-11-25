package com.ys.exch_sim.domain.database;

import com.ys.exch_sim.domain.order_exec.Execution;
import com.ys.exch_sim.domain.position.Position;
import com.ys.exch_sim.domain.position.TradeHistory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** データベース操作の共通インターフェース BigQuery、PostgreSQLなど、異なるデータベース実装を抽象化 */
public interface DatabaseService {

  // ========== Execution操作 ==========

  /** 約定データを挿入 */
  void insertExecution(Execution execution);

  /** 指定時刻以降の約定データを取得 */
  List<Execution> queryRecentExecutions(LocalDateTime fromTime);

  // ========== Position操作 ==========

  /** ポジションを挿入または更新（UPSERT） */
  void upsertPosition(Position position);

  /** 指定ユーザー・銘柄のポジションを取得 */
  Position queryPosition(String username, String symbol);

  /** 指定ユーザーの全ポジションを取得 */
  List<Position> queryAllPositions(String username);

  // ========== TradeHistory操作 ==========

  /** 取引履歴を挿入 */
  void insertTradeHistory(TradeHistory tradeHistory);

  /** 指定ユーザーの取引履歴を取得 */
  List<TradeHistory> queryTradeHistory(String username);

  /** 指定ユーザー・銘柄の取引履歴を取得 */
  List<TradeHistory> queryTradeHistory(String username, String symbol);

  /** 指定ユーザーの取引履歴を取得（件数制限付き） */
  List<TradeHistory> queryTradeHistory(String username, int limit);

  // ========== User操作 ==========

  /** ユーザーを登録 */
  void registerUser(String username, String encodedPassword, List<String> roles);

  /** ユーザーの存在確認 */
  boolean userExists(String username);

  /** ユーザー情報を取得 */
  UserEntity loadUser(String username);

  // ========== テーブル管理 ==========

  /** テーブルが存在しない場合に作成 */
  void createTablesIfNotExist();

  // ========== 取引量計算（オプション） ==========

  /** 指定時刻以降の銘柄別取引量を計算 */
  Map<String, Long> calculateVolumeBySymbol(LocalDateTime fromTime);

  /** 指定時刻以降の全体取引量を計算 */
  Long calculateTotalVolume(LocalDateTime fromTime);

  /** ユーザー情報を保持するエンティティ */
  class UserEntity {
    private final String username;
    private final String password;
    private final List<String> roles;

    public UserEntity(String username, String password, List<String> roles) {
      this.username = username;
      this.password = password;
      this.roles = roles;
    }

    public String getUsername() {
      return username;
    }

    public String getPassword() {
      return password;
    }

    public List<String> getRoles() {
      return roles;
    }
  }
}
