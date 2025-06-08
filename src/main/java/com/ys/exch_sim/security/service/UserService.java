package com.ys.exch_sim.security.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ys.exch_sim.security.model.User;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {
  private final ObjectMapper objectMapper;
  private final PasswordEncoder passwordEncoder;

  @Value("${app.users.file-path}")
  private String usersFilePath;

  public boolean registerUser(String username, String password) throws IOException {
    log.info("Starting user registration for username: {}", username);

    String normalizedPath = usersFilePath.trim();
    File usersFile = new File(normalizedPath);
    log.info("Users file path: '{}'", usersFile.getAbsolutePath());

    Map<String, List<User>> usersMap;

    if (!usersFile.exists()) {
      log.info("Creating new users file");
      usersMap = new HashMap<>();
      usersMap.put("users", new ArrayList<>());

      File parentDir = usersFile.getParentFile();
      if (parentDir != null && !parentDir.exists()) {
        parentDir.mkdirs();
      }

      objectMapper.writeValue(usersFile, usersMap);
    } else {
      log.info("Reading existing users file");
      usersMap = objectMapper.readValue(usersFile, new TypeReference<Map<String, List<User>>>() {});
    }

    List<User> users = usersMap.get("users");
    if (users == null) {
      users = new ArrayList<>();
      usersMap.put("users", users);
    }

    if (users.stream().anyMatch(user -> user.getUsername().equals(username))) {
      log.info("Username {} already exists", username);
      return false;
    }

    User newUser = new User();
    newUser.setUsername(username);
    newUser.setPassword(passwordEncoder.encode(password));
    newUser.setRoles(List.of("ROLE_USER"));

    users.add(newUser);
    objectMapper.writeValue(usersFile, usersMap);
    log.info("Successfully registered user: {}", username);

    return true;
  }
}
