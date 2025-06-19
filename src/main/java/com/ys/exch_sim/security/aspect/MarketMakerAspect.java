package com.ys.exch_sim.security.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class MarketMakerAspect {

    @Before("@annotation(com.ys.exch_sim.security.annotation.RequireMarketMaker)")
    public void checkMarketMakerRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("Unauthenticated access attempt to market maker endpoint");
            throw new AccessDeniedException("Authentication required");
        }

        boolean hasMarketMakerRole = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(role -> "ROLE_MARKET_MAKER".equals(role));

        if (!hasMarketMakerRole) {
            String username = authentication.getName();
            log.warn("User {} attempted to access market maker endpoint without proper role", username);
            throw new AccessDeniedException("MARKET_MAKER role required");
        }

        log.debug("Market maker access granted for user: {}", authentication.getName());
    }
}