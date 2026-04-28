#!/bin/bash
# Deploy script for Cloud Run
#
# Prerequisites (one-time setup):
#   1. gcloud auth login
#   2. gcloud config set project YOUR_PROJECT_ID
#   3. gcloud services enable run.googleapis.com cloudbuild.googleapis.com secretmanager.googleapis.com
#   4. Store your Stripe key in Secret Manager:
#        echo -n "sk_test_..." | gcloud secrets create stripe-secret-key --data-file=-
#        echo -n "whsec_..."   | gcloud secrets create stripe-webhook-secret --data-file=-
#   5. Grant Cloud Run access to those secrets:
#        PROJECT_NUMBER=$(gcloud projects describe $(gcloud config get-value project) --format='value(projectNumber)')
#        gcloud secrets add-iam-policy-binding stripe-secret-key \
#          --member=serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com \
#          --role=roles/secretmanager.secretAccessor
#        gcloud secrets add-iam-policy-binding stripe-webhook-secret \
#          --member=serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com \
#          --role=roles/secretmanager.secretAccessor

set -e

SERVICE_NAME="station-backend"
REGION="us-central1"  # change to your preferred region

echo "Deploying $SERVICE_NAME to Cloud Run in $REGION..."

gcloud run deploy "$SERVICE_NAME" \
    --source . \
    --region "$REGION" \
    --platform managed \
    --allow-unauthenticated \
    --set-secrets "STRIPE_SECRET_KEY=stripe-secret-key:latest,STRIPE_WEBHOOK_SECRET=stripe-webhook-secret:latest" \
    --memory 512Mi \
    --cpu 1 \
    --min-instances 0 \
    --max-instances 5 \
    --timeout 60s

echo ""
echo "Deployed. Service URL:"
gcloud run services describe "$SERVICE_NAME" --region "$REGION" --format='value(status.url)'
