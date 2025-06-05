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
    log.info("Users file path: '{}'", usersFilePath);

    // ファイルパスの正規化
    String normalizedPath = usersFilePath.trim();
    File usersFile = new File(normalizedPath);
    log.info("Normalized file path: '{}'", normalizedPath);
    log.info("Absolute file path: '{}'", usersFile.getAbsolutePath());
    log.info("Users file exists: {}", usersFile.exists());

    Map<String, List<User>> usersMap;

    // ファイルが存在しない場合は新規作成
    if (!usersFile.exists()) {
      log.info("Creating new users file");
      usersMap = new HashMap<>();
      usersMap.put("users", new ArrayList<>());

      // 親ディレクトリが存在しない場合は作成
      File parentDir = usersFile.getParentFile();
      if (parentDir != null && !parentDir.exists()) {
        parentDir.mkdirs();
      }

      objectMapper.writeValue(usersFile, usersMap);
      log.info("Created new users file at: {}", usersFile.getAbsolutePath());
    } else {
      log.info("Reading existing users file from: {}", usersFile.getAbsolutePath());
      usersMap = objectMapper.readValue(usersFile, new TypeReference<Map<String, List<User>>>() {});
    }

    List<User> users = usersMap.get("users");
    if (users == null) {
      log.info("Users list is null, creating new list");
      users = new ArrayList<>();
      usersMap.put("users", users);
    }

    // ユーザー名の重複チェック
    if (users.stream().anyMatch(user -> user.getUsername().equals(username))) {
      log.info("Username {} already exists", username);
      return false;
    }

    // 新しいユーザーを作成
    User newUser = new User();
    newUser.setUsername(username);
    newUser.setPassword(passwordEncoder.encode(password));
    newUser.setRoles(List.of("ROLE_USER"));
    log.info("Created new user: {}", newUser.getUsername());

    // ユーザーリストに追加
    users.add(newUser);
    log.info("Added user to map, total users: {}", users.size());

    // users.jsonを更新
    log.info("Writing updated users to file: {}", usersFile.getAbsolutePath());
    objectMapper.writeValue(usersFile, usersMap);
    log.info("Successfully wrote users to file");

    return true;
  }
}
