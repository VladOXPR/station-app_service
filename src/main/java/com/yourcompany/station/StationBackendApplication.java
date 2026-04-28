package com.yourcompany.station;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StationBackendApplication {

    private static final Logger log = LoggerFactory.getLogger(StationBackendApplication.class);

    @Value("${stripe.secret-key:}")
    private String stripeSecretKey;

    public static void main(String[] args) {
        SpringApplication.run(StationBackendApplication.class, args);
    }

    @PostConstruct
    public void initStripe() {
        if (stripeSecretKey == null || stripeSecretKey.isBlank()) {
            log.warn("STRIPE_SECRET_KEY is not set; Stripe API calls will fail "
                + "until it is configured. The service will still start so "
                + "Cloud Run health checks pass.");
            return;
        }
        Stripe.apiKey = stripeSecretKey;
        log.info("Stripe SDK initialized");
    }
}
