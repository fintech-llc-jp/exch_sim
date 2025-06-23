package com.ys.exch_sim.domain.order_exec;

import com.ys.exch_sim.domain.message.field.ExecID;
import com.ys.exch_sim.domain.message.field.ExecStatus;
import com.ys.exch_sim.domain.message.field.Px;
import com.ys.exch_sim.domain.message.field.Qty;
import com.ys.exch_sim.domain.message.field.Symbol;
import java.time.LocalDateTime;
import java.util.UUID;
import jakarta.persistence.*;
import lombok.Value;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "executions")
@NoArgsConstructor
@AllArgsConstructor
public class Execution {
  @Id
  @Column(name = "exec_id")
  private String execID;
  
  @Column(name = "order_id")  
  private String orderID;
  
  @Column(name = "username")
  private String username;
  
  @Column(name = "symbol")
  private String symbol;
  
  @Enumerated(EnumType.STRING)
  @Column(name = "exec_status")
  private ExecStatus execStatus;
  
  @Column(name = "last_px")
  private Long lastPx;
  
  @Column(name = "last_qty")
  private Long lastQty;
  
  @Column(name = "counter_party_username")
  private String counterPartyUsername;
  
  @Column(name = "created_at")
  private LocalDateTime createdAt;
  
  @Column(name = "is_market_maker")
  private Boolean isMarketMaker;

  @Column(name = "side")
  private String side;

  // Legacy fields for backward compatibility - these are not persisted
  @Transient
  private Order order;
  
  @Transient
  private Px originalLastPx;
  
  @Transient
  private Qty originalLastQty;

  // Legacy constructor for backward compatibility
  public Execution(Order order, ExecStatus execStatus, Px lastPx, Qty lastQty) {
    this.order = order;
    this.execStatus = execStatus;
    this.originalLastPx = lastPx;
    this.originalLastQty = lastQty;
    this.counterPartyUsername = null;
    this.execID = UUID.randomUUID().toString();
    
    // Set database fields for persistence
    this.orderID = order.getClOrdID().getId();
    this.username = order.getUsername();
    this.symbol = order.getSymbol().getName();
    this.lastPx = lastPx != null ? lastPx.getLongPx() : null;
    this.lastQty = lastQty != null ? (long) lastQty.getLongQty() : null;
    this.createdAt = LocalDateTime.now();
    this.isMarketMaker = false;
    this.side = order.getSide().toString();
  }

  // Legacy constructor with counter party
  public Execution(Order order, ExecStatus execStatus, Px lastPx, Qty lastQty, String counterPartyUsername) {
    this.order = order;
    this.execStatus = execStatus;
    this.originalLastPx = lastPx;
    this.originalLastQty = lastQty;
    this.counterPartyUsername = counterPartyUsername;
    this.execID = UUID.randomUUID().toString();
    
    // Set database fields for persistence
    this.orderID = order.getClOrdID().getId();
    this.username = order.getUsername();
    this.symbol = order.getSymbol().getName();
    this.lastPx = lastPx != null ? lastPx.getLongPx() : null;
    this.lastQty = lastQty != null ? (long) lastQty.getLongQty() : null;
    this.createdAt = LocalDateTime.now();
    this.isMarketMaker = false;
    this.side = order.getSide().toString();
  }
  
  // Constructor for database-only executions (used in tests)
  public Execution(String execID, String orderID, String username, String symbol, 
                   ExecStatus execStatus, Long lastPx, Long lastQty, String counterPartyUsername,
                   LocalDateTime createdAt, Boolean isMarketMaker, String side) {
    this.execID = execID;
    this.orderID = orderID;
    this.username = username;
    this.symbol = symbol;
    this.execStatus = execStatus;
    this.lastPx = lastPx;
    this.lastQty = lastQty;
    this.counterPartyUsername = counterPartyUsername;
    this.createdAt = createdAt;
    this.isMarketMaker = isMarketMaker;
    this.side = side;
    this.order = null; // No original order object
    this.originalLastPx = null;
    this.originalLastQty = null;
  }
  
  // Getter methods for backward compatibility
  public ExecStatus getExecStatus() {
    return this.execStatus;
  }
  
  public Px getLastPx() {
    // Return original object if available (for non-persisted executions)
    if (this.originalLastPx != null) {
      return this.originalLastPx;
    }
    // Reconstruct from database values for persisted executions
    if (this.lastPx == null) return null;
    Symbol symbol = new Symbol(this.symbol, 1, 1000); // Default multipliers
    return new Px(symbol, this.lastPx.longValue());
  }
  
  public Qty getLastQty() {
    // Return original object if available (for non-persisted executions)
    if (this.originalLastQty != null) {
      return this.originalLastQty;
    }
    // Reconstruct from database values for persisted executions
    if (this.lastQty == null) return null;
    Symbol symbol = new Symbol(this.symbol, 1, 1000); // Default multipliers
    return new Qty(symbol, this.lastQty.intValue());
  }
  
  public String getCounterPartyUsername() {
    return this.counterPartyUsername;
  }
  
  public ExecID getExecID() {
    return new ExecID(this.execID);
  }
  
  // Return original order if available, otherwise throw exception
  public Order getOrder() {
    if (this.order != null) {
      return this.order;
    }
    throw new UnsupportedOperationException("Order object cannot be reconstructed from persisted data");
  }
  
  // Getters for entity fields
  public String getUsername() {
    return this.username;
  }
  
  public String getSymbol() {
    return this.symbol;
  }
  
  public LocalDateTime getCreatedAt() {
    return this.createdAt;
  }
  
  public Boolean getIsMarketMaker() {
    return this.isMarketMaker;
  }
  
  public String getSide() {
    return this.side;
  }
  
  public String getOrderID() {
    return this.orderID;
  }
  
  // Additional getters for raw database values (for testing)
  public Long getLastPxRaw() {
    return this.lastPx;
  }
  
  public Long getLastQtyRaw() {
    return this.lastQty;
  }
}
