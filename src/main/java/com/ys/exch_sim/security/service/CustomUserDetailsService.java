package com.ys.exch_sim.security.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ys.exch_sim.security.model.User;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

  private final ObjectMapper objectMapper;

  @Value("${app.users.file-path}")
  private String usersFilePath;

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    try {
      File usersFile = new File(usersFilePath);

      if (!usersFile.exists()) {
        throw new UsernameNotFoundException("Users file not found: " + usersFilePath);
      }

      Map<String, List<User>> usersMap =
          objectMapper.readValue(usersFile, new TypeReference<Map<String, List<User>>>() {});

      List<User> users = usersMap.get("users");
      if (users == null) {
        throw new UsernameNotFoundException("No users found in file");
      }

      User user =
          users.stream().filter(u -> u.getUsername().equals(username)).findFirst().orElse(null);

      if (user == null) {
        throw new UsernameNotFoundException("User not found: " + username);
      }

      List<SimpleGrantedAuthority> authorities =
          user.getRoles().stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList());

      return new org.springframework.security.core.userdetails.User(
          user.getUsername(), user.getPassword(), authorities);
    } catch (IOException e) {
      throw new UsernameNotFoundException("Error loading user: " + username, e);
    }
  }
}
