package com.ys.exch_sim.security.service;

import com.ys.exch_sim.domain.database.DatabaseService;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "app.database.type",
    havingValue = "postgresql",
    matchIfMissing = false)
public class PostgreSQLUserDetailsService implements UserDetailsService {

  private final DatabaseService databaseService;

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    try {
      log.debug("Loading user from PostgreSQL via DatabaseService: {}", username);

      DatabaseService.UserEntity userEntity = databaseService.loadUser(username);

      if (userEntity == null) {
        log.debug("User not found in PostgreSQL: {}", username);
        throw new UsernameNotFoundException("User not found: " + username);
      }

      List<String> roles = userEntity.getRoles() != null ? userEntity.getRoles() : List.of("USER");

      log.debug("User loaded from PostgreSQL: {} with roles: {}", userEntity.getUsername(), roles);

      List<SimpleGrantedAuthority> authorities =
          roles.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList());

      return new org.springframework.security.core.userdetails.User(
          userEntity.getUsername(), userEntity.getPassword(), authorities);

    } catch (UsernameNotFoundException e) {
      throw e;
    } catch (Exception e) {
      log.error("Error loading user from PostgreSQL: {}", username, e);
      throw new UsernameNotFoundException("Error loading user: " + username, e);
    }
  }
}

