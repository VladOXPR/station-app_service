# Station Backend

Minimal Spring Boot backend for a Stripe Terminal-powered power bank station.
Deploys to Google Cloud Run.

**Production URL:** https://pos.cuub.tech

## What This Does

This is the smallest possible backend that lets your Android station app use
Stripe Terminal. It exposes four endpoints:

| Endpoint | Purpose |
|----------|---------|
| `POST /api/connection_token` | Issues a token so the Terminal SDK can talk to Stripe |
| `POST /api/payment_intent` | Creates a $4 manual-capture authorization with extended + incremental auth enabled |
| `POST /api/increment_and_capture/{id}` | At return time: bumps the auth above the original $4 if needed, then captures the actual amount owed |
| `POST /api/cancel/{id}` | Cancels a payment (e.g. hardware failure) |
| `POST /api/webhook` | Receives Stripe webhook events |

That's it. No user accounts, no rental tracking, no station registry.
The station is autonomous; this just brokers Stripe communication.

## Quick Start

### Local development

```bash
export STRIPE_SECRET_KEY=sk_test_...
mvn spring-boot:run
```

Test it:
```bash
curl -X POST http://localhost:8080/api/connection_token
```

### Deploy to Cloud Run

One-time setup (instructions in `deploy.sh`):
1. Enable Cloud Run + Cloud Build + Secret Manager APIs
2. Store your Stripe keys in Secret Manager
3. Grant Cloud Run access to the secrets

Then deploy:
```bash
chmod +x deploy.sh
./deploy.sh
```

The script outputs the Cloud Run service URL. In production the Android app
should target the custom domain instead:

```
BACKEND_BASE_URL = "https://pos.cuub.tech"
```

### Custom domain (pos.cuub.tech)

The custom domain is mapped to Cloud Run via a Cloud Run domain mapping.
One-time setup:

```bash
gcloud beta run domain-mappings create \
    --service station-backend \
    --domain pos.cuub.tech \
    --region us-central1
```

The command prints DNS records (CNAME or A/AAAA depending on root vs subdomain)
that must be added at the DNS registrar for `cuub.tech`. Once DNS propagates
(usually a few minutes), Google provisions a managed TLS certificate
automatically.

## Stripe Setup

Before this works you need to:

1. Create a **Location** in Stripe Dashboard → Terminal → Locations
2. Register your reader(s) to that location
3. Get your **Secret Key** (sk_test_... for testing, sk_live_... for production)
4. Set up a **webhook** pointing to `https://pos.cuub.tech/api/webhook`
   - Get the webhook signing secret (whsec_...) and store it in Secret Manager

## Android Side

The Stripe Terminal Android SDK needs a `ConnectionTokenProvider`. It looks
like:

```kotlin
class StationTokenProvider : ConnectionTokenProvider {
    override fun fetchConnectionToken(callback: ConnectionTokenCallback) {
        try {
            val response = OkHttpClient().newCall(
                Request.Builder()
                    .url("$BACKEND_BASE_URL/api/connection_token")
                    .post("".toRequestBody())
                    .build()
            ).execute()
            
            val token = JSONObject(response.body!!.string()).getString("secret")
            callback.onSuccess(token)
        } catch (e: Exception) {
            callback.onFailure(ConnectionTokenException("Failed", e))
        }
    }
}
```

## Cost Estimate

For a single station doing ~50 rentals/day on Cloud Run:

| Item | Cost |
|------|------|
| Cloud Run requests | $0 (within free tier) |
| Cloud Run CPU time | ~$0.50/month |
| Secret Manager | $0 (within free tier) |
| Cloud Build | $0 (free for first 120 min/day) |
| **Total** | **~$0.50-2/month** |

For a fleet of 50 stations, expect $5-15/month total.

## Environment Variables

| Var | Required | Purpose |
|-----|----------|---------|
| `STRIPE_SECRET_KEY` | yes | Your Stripe secret key (sk_test_... or sk_live_...) |
| `STRIPE_WEBHOOK_SECRET` | only if using webhooks | Webhook signing secret (whsec_...) |
| `PORT` | no | Set automatically by Cloud Run |

## File Structure

```
station-backend/
├── pom.xml                   # Maven dependencies
├── Dockerfile                # Multi-stage build for Cloud Run
├── deploy.sh                 # Cloud Run deployment script
├── src/main/java/com/yourcompany/station/
│   ├── StationBackendApplication.java   # Entry point
│   ├── StripeController.java            # All Stripe endpoints
│   └── HealthController.java            # Health checks for Cloud Run
└── src/main/resources/
    └── application.properties           # Configuration
```
