package com.ys.exch_sim.domain.exception;

/**
 * 資金不足時にスローされる例外
 */
public class InsufficientFundsException extends RuntimeException {
    
    private final String username;
    private final double requiredAmount;
    private final double availableAmount;
    
    public InsufficientFundsException(String message) {
        super(message);
        this.username = null;
        this.requiredAmount = 0.0;
        this.availableAmount = 0.0;
    }
    
    public InsufficientFundsException(String username, double requiredAmount, double availableAmount) {
        super(String.format("資金不足: ユーザー=%s, 必要金額=%.2f円, 利用可能金額=%.2f円", 
                           username, requiredAmount, availableAmount));
        this.username = username;
        this.requiredAmount = requiredAmount;
        this.availableAmount = availableAmount;
    }
    
    public String getUsername() {
        return username;
    }
    
    public double getRequiredAmount() {
        return requiredAmount;
    }
    
    public double getAvailableAmount() {
        return availableAmount;
    }
}