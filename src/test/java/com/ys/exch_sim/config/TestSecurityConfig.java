package com.ys.exch_sim.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

@TestConfiguration
public class TestSecurityConfig {

    @Bean
    @Primary
    public UserDetailsService testUserDetailsService() {
        return new TestUserDetailsService();
    }

    @Bean
    @Primary
    public PasswordEncoder testPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private static class TestUserDetailsService implements UserDetailsService {
        @Override
        public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
            // Mock users for testing
            switch (username) {
                case "testuser":
                case "user1":
                case "user2":
                    return new User(username, "$2a$10$dummy.password.hash", 
                        List.of(new SimpleGrantedAuthority("ROLE_USER")));
                case "marketmaker":
                case "marketmaker1":
                    return new User(username, "$2a$10$dummy.password.hash", 
                        List.of(new SimpleGrantedAuthority("ROLE_MARKET_MAKER"), 
                               new SimpleGrantedAuthority("ROLE_USER")));
                case "admin":
                    return new User(username, "$2a$10$dummy.password.hash", 
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), 
                               new SimpleGrantedAuthority("ROLE_USER")));
                default:
                    throw new UsernameNotFoundException("Test user not found: " + username);
            }
        }
    }
}