#!/bin/bash

# Cloud Run Deployment Script for Exchange Simulator
# This script builds and deploys the application to Google Cloud Run

set -e

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
PROJECT_ID=${GCP_PROJECT_ID:-tradingscreen}
SERVICE_NAME=${SERVICE_NAME:-exch-sim}
REGION=${REGION:-asia-northeast1}
IMAGE_TAG=gcr.io/${PROJECT_ID}/${SERVICE_NAME}:latest
MEMORY=1Gi
CPU=2
TIMEOUT=3600
MAX_INSTANCES=10

# Function to print colored output
print_info() {
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

# Function to check if gcloud is installed
check_gcloud() {
    if ! command -v gcloud &> /dev/null; then
        print_error "gcloud CLI is not installed. Please install it first."
        echo "Visit: https://cloud.google.com/sdk/docs/install"
        exit 1
    fi
    print_success "gcloud CLI found"
}

# Function to check if Docker is installed
check_docker() {
    if ! command -v docker &> /dev/null; then
        print_error "Docker is not installed. Please install it first."
        echo "Visit: https://www.docker.com/products/docker-desktop"
        exit 1
    fi
    print_success "Docker found"
}

# Function to check Google Cloud authentication
check_gcloud_auth() {
    print_info "Checking Google Cloud authentication..."
    if ! gcloud auth application-default print-access-token &> /dev/null; then
        print_error "Not authenticated with Google Cloud. Please run 'gcloud auth login'"
        exit 1
    fi
    print_success "Google Cloud authentication verified"
}

# Function to get the current GCP project
get_current_project() {
    if [ -z "$PROJECT_ID" ] || [ "$PROJECT_ID" == "tradingscreen" ]; then
        CURRENT_PROJECT=$(gcloud config get-value project)
        if [ -z "$CURRENT_PROJECT" ]; then
            print_error "No Google Cloud project configured. Please set one with 'gcloud config set project PROJECT_ID'"
            exit 1
        fi
        PROJECT_ID=$CURRENT_PROJECT
    fi
    print_success "Using GCP Project: ${PROJECT_ID}"
}

# Function to set the project
set_gcloud_project() {
    print_info "Setting GCP project to ${PROJECT_ID}..."
    gcloud config set project ${PROJECT_ID}
}

# Function to enable required APIs
enable_apis() {
    print_info "Enabling required Google Cloud APIs..."
    gcloud services enable cloudbuild.googleapis.com
    gcloud services enable run.googleapis.com
    gcloud services enable containerregistry.googleapis.com
    gcloud services enable bigquery.googleapis.com
    print_success "APIs enabled"
}

# Function to build Docker image
build_docker_image() {
    print_info "Building Docker image: ${IMAGE_TAG}"
    docker build -t ${IMAGE_TAG} .
    if [ $? -eq 0 ]; then
        print_success "Docker image built successfully"
    else
        print_error "Docker build failed"
        exit 1
    fi
}

# Function to push image to Google Container Registry
push_image_to_gcr() {
    print_info "Pushing image to Google Container Registry..."

    # Configure docker authentication for GCR
    gcloud auth configure-docker gcr.io

    docker push ${IMAGE_TAG}
    if [ $? -eq 0 ]; then
        print_success "Image pushed to GCR successfully"
    else
        print_error "Failed to push image to GCR"
        exit 1
    fi
}

# Function to deploy to Cloud Run
deploy_to_cloud_run() {
    print_info "Deploying to Cloud Run..."
    print_info "Service: ${SERVICE_NAME}"
    print_info "Region: ${REGION}"
    print_info "Memory: ${MEMORY}"
    print_info "CPU: ${CPU}"

    gcloud run deploy ${SERVICE_NAME} \
        --image ${IMAGE_TAG} \
        --platform managed \
        --region ${REGION} \
        --memory ${MEMORY} \
        --cpu ${CPU} \
        --timeout ${TIMEOUT} \
        --max-instances ${MAX_INSTANCES} \
        --allow-unauthenticated \
        --set-env-vars="SPRING_PROFILES_ACTIVE=prod,DATA_MIGRATION_ENABLED=true,AUTH_BIGQUERY_ENABLED=true" \
        --service-account=${SERVICE_NAME}@${PROJECT_ID}.iam.gserviceaccount.com

    if [ $? -eq 0 ]; then
        print_success "Deployment to Cloud Run successful"
    else
        print_error "Cloud Run deployment failed"
        exit 1
    fi
}

# Function to create service account
create_service_account() {
    print_info "Creating service account for Cloud Run..."

    SA_EMAIL="${SERVICE_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"

    # Check if service account already exists
    if gcloud iam service-accounts describe ${SA_EMAIL} --project=${PROJECT_ID} &>/dev/null; then
        print_warning "Service account ${SA_EMAIL} already exists"
    else
        gcloud iam service-accounts create ${SERVICE_NAME} \
            --display-name="Service account for ${SERVICE_NAME}" \
            --project=${PROJECT_ID}
        print_success "Service account created: ${SA_EMAIL}"
    fi

    # Grant necessary roles
    print_info "Granting roles to service account..."
    gcloud projects add-iam-policy-binding ${PROJECT_ID} \
        --member="serviceAccount:${SA_EMAIL}" \
        --role="roles/bigquery.dataEditor" \
        --quiet

    gcloud projects add-iam-policy-binding ${PROJECT_ID} \
        --member="serviceAccount:${SA_EMAIL}" \
        --role="roles/bigquery.jobUser" \
        --quiet

    print_success "Roles granted to service account"
}

# Function to get service URL
get_service_url() {
    print_info "Retrieving service URL..."
    SERVICE_URL=$(gcloud run services describe ${SERVICE_NAME} \
        --platform managed \
        --region ${REGION} \
        --format='value(status.url)')

    if [ -z "$SERVICE_URL" ]; then
        print_error "Failed to retrieve service URL"
        return 1
    fi

    print_success "Service URL: ${SERVICE_URL}"
}

# Function to test the deployed service
test_service() {
    if [ -z "$SERVICE_URL" ]; then
        print_warning "Service URL not set, skipping health check"
        return 0
    fi

    print_info "Testing service health check..."

    # Wait a few seconds for the service to be ready
    sleep 5

    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${SERVICE_URL}/actuator/health")

    if [ "$HTTP_CODE" == "200" ] || [ "$HTTP_CODE" == "401" ]; then
        print_success "Service health check passed (HTTP ${HTTP_CODE})"
        return 0
    else
        print_warning "Service health check returned HTTP ${HTTP_CODE}"
        return 1
    fi
}

# Function to show deployment summary
show_summary() {
    echo ""
    echo -e "${BLUE}========================================${NC}"
    echo -e "${GREEN}Deployment Summary${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo "Service Name: ${SERVICE_NAME}"
    echo "Project ID: ${PROJECT_ID}"
    echo "Region: ${REGION}"
    echo "Image: ${IMAGE_TAG}"
    echo "Memory: ${MEMORY}"
    echo "CPU: ${CPU}"
    echo "Service URL: ${SERVICE_URL}"
    echo -e "${BLUE}========================================${NC}"
    echo ""
    echo "Next steps:"
    echo "1. Access your service at: ${SERVICE_URL}"
    echo "2. Check logs: gcloud run logs read ${SERVICE_NAME} --region ${REGION}"
    echo "3. View metrics: https://console.cloud.google.com/run/detail/${REGION}/${SERVICE_NAME}/metrics"
    echo ""
}

# Function to display help
show_help() {
    cat << EOF
Cloud Run Deployment Script for Exchange Simulator

Usage: ./deploy-to-cloud-run.sh [OPTIONS]

Options:
    -p, --project-id <ID>       GCP Project ID (default: current gcloud project)
    -s, --service-name <NAME>   Cloud Run service name (default: exch-sim)
    -r, --region <REGION>       Google Cloud region (default: asia-northeast1)
    -m, --memory <MEMORY>       Memory allocation (default: 1Gi)
    -c, --cpu <CPU>             CPU allocation (default: 2)
    -t, --timeout <TIMEOUT>     Request timeout in seconds (default: 3600)
    -i, --max-instances <NUM>   Maximum instances (default: 10)
    --skip-build                Skip Docker image build
    --skip-push                 Skip pushing to GCR
    --skip-deploy               Skip Cloud Run deployment
    --skip-test                 Skip service health check
    -h, --help                  Show this help message

Examples:
    # Full deployment
    ./deploy-to-cloud-run.sh

    # Deploy to specific project and region
    ./deploy-to-cloud-run.sh -p my-project -r us-central1

    # Deploy without rebuilding image
    ./deploy-to-cloud-run.sh --skip-build

EOF
}

# Parse command line arguments
SKIP_BUILD=false
SKIP_PUSH=false
SKIP_DEPLOY=false
SKIP_TEST=false

while [[ $# -gt 0 ]]; do
    case $1 in
        -p|--project-id)
            PROJECT_ID="$2"
            shift 2
            ;;
        -s|--service-name)
            SERVICE_NAME="$2"
            shift 2
            ;;
        -r|--region)
            REGION="$2"
            shift 2
            ;;
        -m|--memory)
            MEMORY="$2"
            shift 2
            ;;
        -c|--cpu)
            CPU="$2"
            shift 2
            ;;
        -t|--timeout)
            TIMEOUT="$2"
            shift 2
            ;;
        -i|--max-instances)
            MAX_INSTANCES="$2"
            shift 2
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --skip-push)
            SKIP_PUSH=true
            shift
            ;;
        --skip-deploy)
            SKIP_DEPLOY=true
            shift
            ;;
        --skip-test)
            SKIP_TEST=true
            shift
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        *)
            print_error "Unknown option: $1"
            show_help
            exit 1
            ;;
    esac
done

# Update image tag with project ID
IMAGE_TAG="gcr.io/${PROJECT_ID}/${SERVICE_NAME}:latest"

# Main execution
echo -e "${BLUE}========================================${NC}"
echo -e "${GREEN}Cloud Run Deployment Script${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Pre-flight checks
print_info "Running pre-flight checks..."
check_gcloud
check_docker
check_gcloud_auth
get_current_project
set_gcloud_project

# Enable APIs
enable_apis

# Create service account
create_service_account

# Build Docker image
if [ "$SKIP_BUILD" = false ]; then
    build_docker_image
else
    print_warning "Skipping Docker image build"
fi

# Push image to GCR
if [ "$SKIP_PUSH" = false ]; then
    push_image_to_gcr
else
    print_warning "Skipping push to GCR"
fi

# Deploy to Cloud Run
if [ "$SKIP_DEPLOY" = false ]; then
    deploy_to_cloud_run
else
    print_warning "Skipping Cloud Run deployment"
fi

# Get service URL
get_service_url

# Test service
if [ "$SKIP_TEST" = false ]; then
    test_service
else
    print_warning "Skipping service health check"
fi

# Show summary
show_summary

print_success "Deployment completed!"
