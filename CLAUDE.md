# Exchange Simulator - BigQuery Configuration

## Project Overview
This is a Spring Boot-based trading exchange simulator that supports order matching, position management, and trade history tracking. The application has been migrated to support BigQuery for production data storage.

## Build and Run
```bash
# Set Java version
export JAVA_HOME=/opt/homebrew/opt/openjdk@17

# Compile the project
./gradlew compileJava

# Run the application
./gradlew bootRun
```

## Data Migration Architecture

### Current Implementation
The application uses a hybrid approach for data persistence:
- **H2 Database**: For local development and execution data
- **BigQuery**: For production data storage (positions, trade history, executions)
- **JSON Files**: For user management (`./users.json`)
- **Memory Cache**: For high-performance data access

### Configuration Flags
```properties
# Data Migration Configuration
app.data-migration.enabled=true
app.data-migration.phase=add-persistence
app.data-migration.memory-cache-enabled=true
app.data-migration.database-persistence-enabled=true
app.data-migration.migrate-positions=true
app.data-migration.migrate-trade-history=true
app.data-migration.bigquery-enabled=false

# Authentication Configuration
app.auth.bigquery-enabled=false  # Set to true for BigQuery-based authentication
```

### BigQuery Configuration
```properties
# BigQuery Configuration
spring.cloud.gcp.project-id=tradingscreen
spring.cloud.gcp.bigquery.dataset-name=repository
# Authentication via environment variable (recommended)
# export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account-key.json
```

### User Authentication
The application supports two authentication modes:

#### File-based Authentication (Default)
- Users stored in `./users.json`
- Suitable for development and testing
- Configuration: `app.auth.bigquery-enabled=false`

#### BigQuery-based Authentication
- Users stored in `tradingscreen:repository.users` table
- Suitable for production environments
- Configuration: `app.auth.bigquery-enabled=true`
- Requires BigQuery credentials and proper table setup

## Database Schema

### H2 Tables (Local Development)
1. **executions** - Trade execution records
2. **positions** - User position data
3. **trade_history** - Trade history records

### BigQuery Tables (Production)
1. **executions** - Trade execution records with enhanced schema
2. **positions** - User position data with financial metrics
3. **trade_history** - Detailed trade history with market maker flags

## Key Components

### Position Management
- `PositionManager`: Handles position updates with dual persistence (memory + database)
- `PositionEntity`: JPA entity for H2 database
- `BigQueryPositionEntity`: BigQuery-specific entity

### Trade History
- `TradeHistory`: Domain model for trade records
- `TradeHistoryEntity`: JPA entity for H2 database
- `BigQueryTradeHistoryEntity`: BigQuery-specific entity

### Data Migration
- `DataMigrationInitializer`: Initializes default users and instruments on startup
- `BigQueryService`: Handles BigQuery operations and table creation
- `BigQueryConfig`: Configuration for BigQuery client

## Default Configuration

### Default Users
- **admin**: Admin user with ROLE_ADMIN, ROLE_USER
- **trader001**: Regular trader with ROLE_USER
- **marketmaker1**: Market maker with ROLE_MARKET_MAKER, ROLE_USER

### Default Instruments
- **G_BTCJPY**: Bitcoin/JPY (Cash)
- **G_FX_BTCJPY**: Bitcoin/JPY (FX)
- **B_BTCJPY**: Bitcoin/JPY (Cash)
- **B_FX_BTCJPY**: Bitcoin/JPY (FX)
- **TESTJPY**: Test/Japanese Yen (Cash)

## Migration Phases

### Phase 1: Add Persistence
- Add database entities alongside existing memory storage
- Implement dual-write pattern (memory + database)
- Maintain backward compatibility

### Phase 2: BigQuery Integration (Future)
- Enable BigQuery persistence for production
- Implement data export/import utilities
- Add monitoring and alerting

## Testing

### Full Test Suite
```bash
# Set Java version (required)
export JAVA_HOME=/opt/homebrew/opt/openjdk@17

# Run all tests - MUST pass before any commits
./gradlew test

# Expected result: 96 tests executed, 0 failures, 10 ignored
# Success rate: 100%
```

**IMPORTANT**: After any code changes, always run the full test suite to ensure no regressions. All tests must pass before committing changes or deploying to production.

### Quick Tests
```bash
# Run market buy and history tests
./quick_test.sh
```

### BigQuery Tests
```bash
# Test BigQuery connectivity and data operations
./test_bigquery_local.sh
```

### Manual Testing
- Market orders: Use the trading API to place buy/sell orders
- Position tracking: Check position updates after trades
- Trade history: Verify trade records are persisted

## Security Considerations
- JWT token authentication
- Role-based access control
- Encrypted password storage
- Secure credential management for BigQuery

## Performance Optimizations
- Memory caching for frequently accessed data
- Asynchronous database writes
- Connection pooling for database operations
- Batch processing for BigQuery inserts

## Monitoring and Logging
- Comprehensive logging for all trade operations
- Performance metrics for position updates
- Error tracking for database operations
- BigQuery operation monitoring

## Environment Variables
```bash
# For BigQuery authentication
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account-key.json

# For Java version
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
```

## Troubleshooting

### Common Issues
1. **Java Version**: Ensure Java 17 is being used
2. **BigQuery Credentials**: Verify service account key is properly configured
3. **Database Connection**: Check H2 database file permissions
4. **Memory Issues**: Increase JVM heap size if needed

### Debug Commands
```bash
# Check Java version
java -version

# Verify BigQuery connectivity (when enabled)
# Check application logs for BigQuery initialization

# Monitor H2 database
# Access H2 console at http://localhost:8080/h2-console
```