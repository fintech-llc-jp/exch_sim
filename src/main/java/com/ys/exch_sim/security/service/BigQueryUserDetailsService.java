package com.ys.exch_sim.security.service;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.TableResult;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "app.data-migration.bigquery-enabled", havingValue = "true", matchIfMissing = false)
public class BigQueryUserDetailsService implements UserDetailsService {

  // Cache for user details with TTL
  private final ConcurrentHashMap<String, CachedUserDetails> userCache = new ConcurrentHashMap<>();

  // Cache TTL in minutes
  private long cacheTtlMinutes = 5;

  // Maximum cache size
  private int cacheMaxSize = 1000;

  private static class CachedUserDetails {
    private final UserDetails userDetails;
    private final LocalDateTime cachedAt;

    public CachedUserDetails(UserDetails userDetails) {
      this.userDetails = userDetails;
      this.cachedAt = LocalDateTime.now();
    }

    public UserDetails getUserDetails() {
      return userDetails;
    }

    public boolean isExpired(long ttlMinutes) {
      return ChronoUnit.MINUTES.between(cachedAt, LocalDateTime.now()) > ttlMinutes;
    }
  }

  private final BigQuery bigQuery;

  public BigQueryUserDetailsService(BigQuery bigQuery) {
    this.bigQuery = bigQuery;
  }

  @Value("${spring.cloud.gcp.project-id}")
  private String projectId;

  @Value("${spring.cloud.gcp.bigquery.dataset-name}")
  private String datasetName;

  @Override
  public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
    // Check cache first
    CachedUserDetails cached = userCache.get(username);
    if (cached != null && !cached.isExpired(cacheTtlMinutes)) {
      log.debug("User loaded from cache: {}", username);
      return cached.getUserDetails();
    }

    // Load from BigQuery if not in cache or expired
    UserDetails userDetails = loadUserFromBigQuery(username);

    // Cache the result
    cacheUserDetails(username, userDetails);

    return userDetails;
  }

  private UserDetails loadUserFromBigQuery(String username) throws UsernameNotFoundException {
    if (bigQuery == null) {
      log.warn("BigQuery is not available, cannot load user: {}", username);
      throw new UsernameNotFoundException(
          "User not found: " + username + " (BigQuery not available)");
    }
    try {
      log.debug("Loading user from BigQuery: {}", username);

      String query =
          String.format(
              "SELECT username, password, roles FROM `%s.%s.users` WHERE username = @username LIMIT"
                  + " 1",
              projectId, datasetName);

      QueryJobConfiguration queryConfig =
          QueryJobConfiguration.newBuilder(query)
              .addNamedParameter(
                  "username", com.google.cloud.bigquery.QueryParameterValue.string(username))
              .build();

      JobId jobId = JobId.of(UUID.randomUUID().toString());
      Job queryJob = bigQuery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build());

      queryJob = queryJob.waitFor();

      if (queryJob == null) {
        throw new UsernameNotFoundException("Query job failed for user: " + username);
      }

      if (queryJob.getStatus().getError() != null) {
        log.error("BigQuery error: {}", queryJob.getStatus().getError());
        throw new UsernameNotFoundException("Query failed for user: " + username);
      }

      TableResult result = queryJob.getQueryResults();

      if (result.getTotalRows() == 0) {
        log.debug("User not found in BigQuery: {}", username);
        throw new UsernameNotFoundException("User not found: " + username);
      }

      FieldValueList row = result.iterateAll().iterator().next();

      String dbUsername = row.get("username").getStringValue();
      String password = row.get("password").getStringValue();

      List<String> roles = new ArrayList<>();
      if (!row.get("roles").isNull()) {
        for (com.google.cloud.bigquery.FieldValue roleValue : row.get("roles").getRepeatedValue()) {
          roles.add(roleValue.getStringValue());
        }
      }

      log.debug("User loaded from BigQuery: {} with roles: {}", dbUsername, roles);

      List<SimpleGrantedAuthority> authorities =
          roles.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList());

      return new org.springframework.security.core.userdetails.User(
          dbUsername, password, authorities);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.error("Query interrupted for user: {}", username, e);
      throw new UsernameNotFoundException("Query interrupted for user: " + username, e);
    } catch (Exception e) {
      log.error("Error loading user from BigQuery: {}", username, e);
      throw new UsernameNotFoundException("Error loading user: " + username, e);
    }
  }

  private void cacheUserDetails(String username, UserDetails userDetails) {
    // Remove expired entries if cache is getting too large
    if (userCache.size() >= cacheMaxSize) {
      cleanExpiredEntries();
    }

    // Add to cache
    userCache.put(username, new CachedUserDetails(userDetails));
    log.debug("User cached: {} (cache size: {})", username, userCache.size());
  }

  private void cleanExpiredEntries() {
    userCache
        .entrySet()
        .removeIf(
            entry -> {
              boolean expired = entry.getValue().isExpired(cacheTtlMinutes);
              if (expired) {
                log.debug("Removing expired cache entry for user: {}", entry.getKey());
              }
              return expired;
            });
  }

  // Method to clear cache (useful for testing or manual cache invalidation)
  public void clearCache() {
    userCache.clear();
    log.info("User cache cleared");
  }

  // Method to remove specific user from cache
  public void evictUser(String username) {
    userCache.remove(username);
    log.debug("User evicted from cache: {}", username);
  }
}
