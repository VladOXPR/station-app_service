package com.yourcompany.station;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StationBackendApplication {
    
    @Value("${stripe.secret-key}")
    private String stripeSecretKey;
    
    public static void main(String[] args) {
        SpringApplication.run(StationBackendApplication.class, args);
    }
    
    @PostConstruct
    public void initStripe() {
        Stripe.apiKey = stripeSecretKey;
    }
}
