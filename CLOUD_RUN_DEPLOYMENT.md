# Google Cloud Run Deployment Guide

## Prerequisites

1. **Google Cloud Project**: Ensure you have a GCP project with billing enabled
2. **Docker**: Install Docker on your local machine
3. **Google Cloud CLI**: Install and authenticate `gcloud` CLI
4. **BigQuery Setup**: Ensure BigQuery dataset and tables are created
5. **Redis Instance**: Set up Redis instance (Cloud Memorystore or external)

## Build and Deploy

### 1. Build Docker Image

```bash
# Set Java version
export JAVA_HOME=/opt/homebrew/opt/openjdk@17

# Build the Docker image
docker build -t exch-sim:latest .

# Tag for Google Container Registry
docker tag exch-sim:latest gcr.io/YOUR_PROJECT_ID/exch-sim:latest
```

### 2. Push to Container Registry

```bash
# Configure Docker to use gcloud as credential helper
gcloud auth configure-docker

# Push the image
docker push gcr.io/YOUR_PROJECT_ID/exch-sim:latest
```

### 3. Deploy to Cloud Run

```bash
gcloud run deploy exch-sim \
  --image gcr.io/YOUR_PROJECT_ID/exch-sim:latest \
  --platform managed \
  --region us-central1 \
  --allow-unauthenticated \
  --port 8080 \
  --memory 1Gi \
  --cpu 1 \
  --timeout 300s \
  --concurrency 1000 \
  --min-instances 0 \
  --max-instances 10 \
  --set-env-vars="SPRING_PROFILES_ACTIVE=prod" \
  --set-env-vars="REDIS_HOST=10.233.140.211" \
  --set-env-vars="REDIS_PORT=6379" \
  --set-env-vars="GCP_PROJECT_ID=tradingscreen" \
  --set-env-vars="BIGQUERY_DATASET=repository" \
  --set-env-vars="JWT_SECRET=your-secure-256-bit-secret-key-here" \
  --service-account=YOUR_SERVICE_ACCOUNT@YOUR_PROJECT_ID.iam.gserviceaccount.com
```

## Environment Variables

### Required Variables
- `REDIS_HOST`: Redis server hostname (10.233.140.211)
- `REDIS_PORT`: Redis server port (6379)
- `GCP_PROJECT_ID`: Google Cloud project ID
- `BIGQUERY_DATASET`: BigQuery dataset name
- `JWT_SECRET`: Secret key for JWT token signing

### Optional Variables
- `REDIS_PASSWORD`: Redis password (if required)
- `REDIS_DATABASE`: Redis database number (default: 0)
- `SERVER_PORT`: Application port (default: 8080)
- `AUTH_CACHE_TTL_MINUTES`: User cache TTL in minutes (default: 5)
- `AUTH_CACHE_MAX_SIZE`: Maximum cache size (default: 1000)
- `LOG_LEVEL`: Application log level (default: INFO)

## Service Account Setup

Create a service account with necessary permissions:

```bash
# Create service account
gcloud iam service-accounts create exch-sim-service \
  --description="Service account for Exchange Simulator" \
  --display-name="Exchange Simulator Service Account"

# Grant BigQuery permissions
gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
  --member="serviceAccount:exch-sim-service@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/bigquery.dataEditor"

gcloud projects add-iam-policy-binding YOUR_PROJECT_ID \
  --member="serviceAccount:exch-sim-service@YOUR_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/bigquery.jobUser"
```

## BigQuery Tables Setup

Ensure the following tables exist in your BigQuery dataset:

### users table
```sql
CREATE TABLE `tradingscreen.repository.users` (
  username STRING NOT NULL,
  password STRING NOT NULL,
  roles ARRAY<STRING> NOT NULL
);
```

### executions table
```sql
CREATE TABLE `tradingscreen.repository.executions` (
  execution_id STRING NOT NULL,
  order_id STRING NOT NULL,
  username STRING NOT NULL,
  symbol STRING NOT NULL,
  exec_status STRING NOT NULL,
  last_px_raw INT64 NOT NULL,
  last_qty_raw INT64 NOT NULL,
  side STRING,
  created_at TIMESTAMP NOT NULL,
  is_market_maker BOOLEAN NOT NULL
);
```

### positions table
```sql
CREATE TABLE `tradingscreen.repository.positions` (
  username STRING NOT NULL,
  symbol STRING NOT NULL,
  quantity INT64 NOT NULL,
  average_price FLOAT64 NOT NULL,
  unrealized_pnl FLOAT64 NOT NULL,
  realized_pnl FLOAT64 NOT NULL,
  last_updated TIMESTAMP NOT NULL
);
```

### trade_history table
```sql
CREATE TABLE `tradingscreen.repository.trade_history` (
  trade_id STRING NOT NULL,
  username STRING NOT NULL,
  symbol STRING NOT NULL,
  side STRING NOT NULL,
  quantity INT64 NOT NULL,
  price FLOAT64 NOT NULL,
  trade_time TIMESTAMP NOT NULL,
  is_market_maker BOOLEAN NOT NULL,
  order_id STRING NOT NULL
);
```

## Monitoring and Troubleshooting

### View Logs
```bash
gcloud run services logs read exch-sim --platform managed --region us-central1
```

### Check Service Status
```bash
gcloud run services describe exch-sim --platform managed --region us-central1
```

### Update Service
```bash
gcloud run services update exch-sim \
  --platform managed \
  --region us-central1 \
  --set-env-vars="NEW_VAR=value"
```

## Health Check

The application includes a health check endpoint at `/actuator/health` that Cloud Run will use to determine service health.

## Security Considerations

1. **Service Account**: Use least-privilege principle for service account permissions
2. **JWT Secret**: Use a strong, randomly generated secret key
3. **Network**: Consider using VPC connector for secure Redis access
4. **Authentication**: BigQuery-based user authentication with caching
5. **Secrets**: Store sensitive information in Google Secret Manager

## Performance Tuning

- **Memory**: Start with 1Gi, adjust based on usage
- **CPU**: 1 CPU should handle moderate load
- **Concurrency**: Adjust based on Redis and BigQuery capacity
- **Min/Max Instances**: Configure based on expected traffic patterns
- **Cache Settings**: Tune auth cache TTL and size for your use case