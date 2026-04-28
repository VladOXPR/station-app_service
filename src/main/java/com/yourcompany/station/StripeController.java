package com.yourcompany.station;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.terminal.ConnectionToken;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentIncrementAuthorizationParams;
import com.stripe.param.terminal.ConnectionTokenCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Endpoints used by the CUUB station app.
 *
 * Flow:
 *   1. Station calls /connection_token at startup → SDK authenticates to Stripe.
 *   2. On rent: /payment_intent creates a $4 manual-capture intent with
 *      incremental authorization enabled.
 *   3. On return: /increment_and_capture/{id} extends the auth (if needed)
 *      then captures the actual amount the customer owes.
 *   4. /cancel/{id} releases an auth if the slot fails to dispense.
 */
@RestController
@RequestMapping("/api")
public class StripeController {

    private static final Logger log = LoggerFactory.getLogger(StripeController.class);

    @Value("${stripe.webhook-secret:}")
    private String webhookSecret;

    /** SDK authenticates to Stripe with a connection token from this endpoint. */
    @PostMapping("/connection_token")
    public Map<String, String> connectionToken() throws StripeException {
        ConnectionToken token = ConnectionToken.create(
            ConnectionTokenCreateParams.builder().build()
        );
        log.info("Issued connection token");
        return Map.of("secret", token.getSecret());
    }

    /**
     * Creates a card-present PaymentIntent with manual capture and incremental
     * authorization support.
     *
     * - capture_method = manual: places a hold, doesn't charge yet.
     * - request_incremental_authorization_support: lets us extend the hold later
     *   if the customer keeps the powerbank past the initial 24h.
     * - request_extended_authorization: extends the hold lifetime beyond the
     *   default ~7 days, so multi-day rentals don't have the auth silently expire.
     */
    @PostMapping("/payment_intent")
    public Map<String, Object> createPaymentIntent(@RequestBody PaymentRequest req)
            throws StripeException {

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
            .setAmount(req.amount())
            .setCurrency(req.currency() != null ? req.currency() : "usd")
            .addPaymentMethodType("card_present")
            .setCaptureMethod(PaymentIntentCreateParams.CaptureMethod.MANUAL)
            .setPaymentMethodOptions(
                PaymentIntentCreateParams.PaymentMethodOptions.builder()
                    .setCardPresent(
                        PaymentIntentCreateParams.PaymentMethodOptions.CardPresent.builder()
                            .setRequestExtendedAuthorization(true)
                            .setRequestIncrementalAuthorizationSupport(true)
                            .build()
                    )
                    .build()
            )
            .putMetadata("station_id", nullToEmpty(req.stationId()))
            .putMetadata("rental_id",  nullToEmpty(req.rentalId()))
            .build();

        PaymentIntent intent = PaymentIntent.create(params);
        log.info("Created PaymentIntent {} for ${} (station={}, rental={})",
            intent.getId(), req.amount() / 100.0, req.stationId(), req.rentalId());

        Map<String, Object> resp = new HashMap<>();
        resp.put("id", intent.getId());
        resp.put("client_secret", intent.getClientSecret());
        resp.put("amount", intent.getAmount());
        resp.put("status", intent.getStatus());
        return resp;
    }

    /**
     * At return time the station calls this with the final amount to charge.
     * If the original auth was for less than the final amount, increment first.
     * Then capture exactly what the customer owes.
     *
     * Example: customer rented for 30h at $4/24h → station calls with amount=800.
     * Original auth was $400, so we incrementAuthorization to $800 then capture $800.
     */
    @PostMapping("/increment_and_capture/{id}")
    public Map<String, Object> incrementAndCapture(
            @PathVariable String id,
            @RequestBody CaptureRequest req) throws StripeException {

        PaymentIntent intent = PaymentIntent.retrieve(id);
        long currentlyAuthorized = intent.getAmountCapturable();
        long target = req.amount();

        if (target > currentlyAuthorized) {
            log.info("Incrementing auth on {} from {} to {}", id, currentlyAuthorized, target);
            intent = intent.incrementAuthorization(
                PaymentIntentIncrementAuthorizationParams.builder()
                    .setAmount(target)
                    .build()
            );
        }

        log.info("Capturing ${} on {}", target / 100.0, id);
        intent = intent.capture(
            PaymentIntentCaptureParams.builder()
                .setAmountToCapture(target)
                .build()
        );

        Map<String, Object> resp = new HashMap<>();
        resp.put("id", intent.getId());
        resp.put("status", intent.getStatus());
        resp.put("amount_received", intent.getAmountReceived());
        return resp;
    }

    /**
     * Cancel an authorization without charging — used when slot dispense fails
     * after the customer has already tapped their card.
     */
    @PostMapping("/cancel/{id}")
    public Map<String, Object> cancel(@PathVariable String id) throws StripeException {
        PaymentIntent intent = PaymentIntent.retrieve(id);
        intent = intent.cancel();
        log.info("Cancelled PaymentIntent {} status={}", intent.getId(), intent.getStatus());
        return Map.of("id", intent.getId(), "status", intent.getStatus());
    }

    /**
     * Stripe webhook endpoint. If a webhook secret is configured we verify the
     * signature; otherwise we accept the event so local/dev deploys without
     * webhooks set up don't return 400 to Stripe.
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> webhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("Webhook called but no webhook secret configured; accepting unverified");
            return ResponseEntity.ok("ok");
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Invalid webhook signature");
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        log.info("Webhook: {} ({})", event.getType(), event.getId());
        return ResponseEntity.ok("ok");
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    public record PaymentRequest(
        Long amount,
        String currency,
        String stationId,
        String rentalId
    ) {}

    public record CaptureRequest(Long amount) {}
}
