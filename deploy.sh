#!/bin/bash

# Exchange Simulator Cloud Run Deployment Script
# Usage: ./deploy.sh [PROJECT_ID] [REGION] [--local|--cloud] [--skip-tests]

set -e  # Exit on any error

# Configuration defaults
PROJECT_ID="tradingscreen"
REGION="asia-northeast1"
DEPLOY_MODE="--cloud"  # Default to cloud build
SKIP_TESTS="false"
SERVICE_NAME="exch-sim"
IMAGE_NAME="exch-sim"

# Parse arguments
for arg in "$@"; do
  case $arg in
    --skip-tests)
      SKIP_TESTS="true"
      ;;
    --local)
      DEPLOY_MODE="--local"
      ;;
    --cloud)
      DEPLOY_MODE="--cloud"
      ;;
    --help)
      echo "Usage: $0 [PROJECT_ID] [REGION] [--local|--cloud] [--skip-tests]"
      echo "  PROJECT_ID: GCP project ID (default: tradingscreen)"
      echo "  REGION: GCP region (default: asia-northeast1)"
      echo "  --local: Use local build and push"
      echo "  --cloud: Use Cloud Build (default)"
      echo "  --skip-tests: Skip running tests"
      exit 0
      ;;
    *)
      # If it doesn't start with --, treat as positional argument
      if [[ ! $arg == --* ]]; then
        if [ -z "$PROJECT_ID_SET" ]; then
          PROJECT_ID="$arg"
          PROJECT_ID_SET="true"
        elif [ -z "$REGION_SET" ]; then
          REGION="$arg"
          REGION_SET="true"
        fi
      fi
      ;;
  esac
done

# Redis Configuration removed - no longer using VPC/Redis connections

# BigQuery Configuration
BIGQUERY_DATASET="repository"

# JWT Secret (should be passed via environment variable or generated)
JWT_SECRET=${JWT_SECRET:-"your-secure-256-bit-secret-key-here"}

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check prerequisites
check_prerequisites() {
    print_status "Checking prerequisites..."
    
    # Check if gcloud is installed
    if ! command -v gcloud &> /dev/null; then
        print_error "gcloud CLI is not installed. Please install it first."
        exit 1
    fi
    
    # Check if docker is installed
    if ! command -v docker &> /dev/null; then
        print_error "Docker is not installed. Please install it first."
        exit 1
    fi
    
    # Check if Java 17 is available
    if [[ -d "/opt/homebrew/opt/openjdk@17" ]]; then
        export JAVA_HOME=/opt/homebrew/opt/openjdk@17
        print_status "Using Java 17 from: $JAVA_HOME"
    else
        print_warning "Java 17 not found at expected location. Proceeding with current JAVA_HOME: ${JAVA_HOME:-'not set'}"
    fi
    
    # Check if authenticated with gcloud
    if ! gcloud auth list --filter=status:ACTIVE --format="value(account)" | grep -q .; then
        print_error "Not authenticated with gcloud. Please run: gcloud auth login"
        exit 1
    fi
    
    print_success "Prerequisites check completed"
}

# Cloud Build deployment
deploy_with_cloud_build() {
    print_status "Starting Cloud Build deployment..."
    
    # Enable required APIs
    print_status "Enabling required APIs..."
    gcloud services enable cloudbuild.googleapis.com --project=${PROJECT_ID}
    gcloud services enable run.googleapis.com --project=${PROJECT_ID}
    
    # Submit build to Cloud Build
    print_status "Submitting build to Cloud Build..."
    
    # Choose config file based on skip-tests flag
    CONFIG_FILE="cloudbuild.yaml"
    if [ "$SKIP_TESTS" = "true" ]; then
        CONFIG_FILE="cloudbuild-no-tests.yaml"
        print_status "Using config without tests: ${CONFIG_FILE}"
    else
        print_status "Using config with tests: ${CONFIG_FILE}"
    fi
    
    # Use beta command to get better logging and error handling
    BUILD_OUTPUT=$(gcloud beta builds submit --config=${CONFIG_FILE} --project=${PROJECT_ID} --format="json" 2>&1)
    BUILD_EXIT_CODE=$?
    
    if [ $BUILD_EXIT_CODE -ne 0 ]; then
        print_error "Cloud Build submission failed"
        echo "Build output:"
        echo "$BUILD_OUTPUT"
        exit 1
    fi
    
    # Extract build ID from the output
    BUILD_ID=$(echo "$BUILD_OUTPUT" | grep -o '"name": *"[^"]*"' | head -1 | cut -d'"' -f4 | cut -d'/' -f6)
    
    if [ -z "$BUILD_ID" ]; then
        print_error "Failed to extract build ID from Cloud Build response"
        echo "Build output:"
        echo "$BUILD_OUTPUT"
        exit 1
    fi
    
    print_status "Cloud Build started. Build ID: ${BUILD_ID}"
    print_status "You can monitor the build at: https://console.cloud.google.com/cloud-build/builds/${BUILD_ID}?project=${PROJECT_ID}"
    
    # Wait for build to complete
    print_status "Waiting for build to complete..."
    gcloud builds log --stream ${BUILD_ID} --project=${PROJECT_ID}
    
    # Check build status
    BUILD_STATUS=$(gcloud builds describe ${BUILD_ID} --project=${PROJECT_ID} --format="value(status)")
    
    if [ "$BUILD_STATUS" = "SUCCESS" ]; then
        print_success "Cloud Build completed successfully"
        
        # Set IAM policy for public access
        print_status "Setting IAM policy for public access..."
        gcloud run services add-iam-policy-binding ${SERVICE_NAME} \
            --platform managed \
            --region ${REGION} \
            --project=${PROJECT_ID} \
            --member="allUsers" \
            --role="roles/run.invoker"
        
        if [ $? -ne 0 ]; then
            print_error "Failed to set IAM policy"
            exit 1
        fi
        
        print_success "IAM policy set successfully"
    else
        print_error "Cloud Build failed with status: ${BUILD_STATUS}"
        exit 1
    fi
}

# Local build and push (original method)
build_and_push_local() {
    print_status "Building and pushing locally..."
    
    # Run tests first
    print_status "Running tests..."
    ./gradlew test
    
    if [ $? -ne 0 ]; then
        print_error "Tests failed. Deployment aborted."
        exit 1
    fi
    
    print_success "Tests passed"
    
    # Build Docker image
    print_status "Building Docker image..."
    docker build -t ${IMAGE_NAME}:latest .
    
    if [ $? -ne 0 ]; then
        print_error "Docker build failed"
        exit 1
    fi
    
    # Tag for Google Container Registry
    docker tag ${IMAGE_NAME}:latest gcr.io/${PROJECT_ID}/${IMAGE_NAME}:latest
    
    print_success "Docker image built and tagged"
    
    # Push to Container Registry
    print_status "Pushing image to Google Container Registry..."
    gcloud auth configure-docker --quiet
    docker push gcr.io/${PROJECT_ID}/${IMAGE_NAME}:latest
    
    if [ $? -ne 0 ]; then
        print_error "Failed to push image to Container Registry"
        exit 1
    fi
    
    print_success "Image pushed to gcr.io/${PROJECT_ID}/${IMAGE_NAME}:latest"
    
    # Deploy to Cloud Run
    deploy_to_cloud_run
}

# Deploy to Cloud Run (for local build mode)
deploy_to_cloud_run() {
    print_status "Deploying to Cloud Run..."
    
    # Get timestamp for deployment tracking
    TIMESTAMP=$(date '+%Y%m%d-%H%M%S')
    
    gcloud run deploy ${SERVICE_NAME} \
        --image gcr.io/${PROJECT_ID}/${IMAGE_NAME}:latest \
        --platform managed \
        --region ${REGION} \
        --allow-unauthenticated \
        --port 8080 \
        --memory 1Gi \
        --cpu 1 \
        --timeout 300s \
        --concurrency 1000 \
        --min-instances 0 \
        --max-instances 10 \
        --set-env-vars="SPRING_PROFILES_ACTIVE=prod" \
        --set-env-vars="GCP_PROJECT_ID=${PROJECT_ID}" \
        --set-env-vars="BIGQUERY_DATASET=${BIGQUERY_DATASET}" \
        --set-env-vars="JWT_SECRET=${JWT_SECRET}" \
        --set-env-vars="DATA_MIGRATION_BIGQUERY_ENABLED=true" \
        --clear-vpc-connector \
        --tag="deploy-${TIMESTAMP}" \
        --project=${PROJECT_ID}
    
    if [ $? -ne 0 ]; then
        print_error "Cloud Run deployment failed"
        exit 1
    fi
    
    print_success "Service deployed successfully"
    
    # Set IAM policy for public access
    print_status "Setting IAM policy for public access..."
    gcloud run services add-iam-policy-binding ${SERVICE_NAME} \
        --platform managed \
        --region ${REGION} \
        --project=${PROJECT_ID} \
        --member="allUsers" \
        --role="roles/run.invoker"
    
    if [ $? -ne 0 ]; then
        print_error "Failed to set IAM policy"
        exit 1
    fi
    
    print_success "IAM policy set successfully"
}

# Get service URL
get_service_url() {
    print_status "Getting service URL..."
    
    SERVICE_URL=$(gcloud run services describe ${SERVICE_NAME} \
        --platform managed \
        --region ${REGION} \
        --project=${PROJECT_ID} \
        --format="value(status.url)")
    
    print_success "Service URL: ${SERVICE_URL}"
    echo ""
    echo "API Endpoints:"
    echo "  - Health Check: ${SERVICE_URL}/actuator/health"
    echo "  - Market Board: ${SERVICE_URL}/api/market/board/{symbol}"
    echo "  - Trade Insert: ${SERVICE_URL}/api/trade/insert"
    echo "  - Execution History: ${SERVICE_URL}/api/executions/history"
    echo ""
}

# Health check
health_check() {
    print_status "Performing health check..."
    
    # Wait a bit for service to start
    sleep 10
    
    # Try to reach the health endpoint
    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${SERVICE_URL}/actuator/health" || echo "000")
    
    if [ "$HTTP_STATUS" = "200" ]; then
        print_success "Health check passed (HTTP $HTTP_STATUS)"
    else
        print_warning "Health check returned HTTP $HTTP_STATUS"
        print_status "Service may still be starting up. Check logs with:"
        echo "  gcloud run services logs read ${SERVICE_NAME} --platform managed --region ${REGION}"
    fi
}

# Main deployment flow
main() {
    echo "==============================================="
    echo "Exchange Simulator Cloud Run Deployment"
    echo "==============================================="
    echo "Project ID: $PROJECT_ID"
    echo "Region: $REGION"
    echo "Service Name: $SERVICE_NAME"
    echo "Deploy Mode: $DEPLOY_MODE"
    echo "Storage: BigQuery + H2 (local)"
    echo "BigQuery Dataset: $BIGQUERY_DATASET"
    echo "==============================================="
    echo ""
    
    if [ "$DEPLOY_MODE" = "--cloud" ]; then
        echo "🚀 Using Cloud Build (fast, recommended)"
        if [ "$SKIP_TESTS" = "true" ]; then
            echo "   - Build and deployment run in GCP (tests skipped)"
        else
            echo "   - Tests, build, and deployment all run in GCP"
        fi
        echo "   - No local Docker image upload required"
    else
        echo "🏠 Using Local Build (slower)"
        echo "   - Tests and build run locally"
        echo "   - Docker image uploaded from local machine"
    fi
    echo ""
    
    # Confirm deployment (skip in non-interactive mode)
    if [ -t 0 ]; then
        read -p "Continue with deployment? (y/N): " -n 1 -r
        echo ""
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            print_status "Deployment cancelled"
            exit 0
        fi
    else
        print_status "Running in non-interactive mode, proceeding with deployment..."
    fi
    
    check_prerequisites
    
    if [ "$DEPLOY_MODE" = "--cloud" ]; then
        deploy_with_cloud_build
    else
        build_and_push_local
    fi
    
    get_service_url
    health_check
    
    print_success "Deployment completed successfully!"
    echo ""
    echo "Useful commands:"
    echo "  View logs: gcloud run services logs read ${SERVICE_NAME} --platform managed --region ${REGION}"
    echo "  View builds: gcloud builds list --project ${PROJECT_ID}"
    echo "  Update service: gcloud run services update ${SERVICE_NAME} --platform managed --region ${REGION}"
    echo "  Delete service: gcloud run services delete ${SERVICE_NAME} --platform managed --region ${REGION}"
}

# Handle script interruption
trap 'print_error "Deployment interrupted"; exit 1' INT TERM

# Run main function
main "$@"
