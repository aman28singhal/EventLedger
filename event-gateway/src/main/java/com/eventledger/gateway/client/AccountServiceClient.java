package com.eventledger.gateway.client;

import com.eventledger.gateway.exception.DownstreamServiceUnavailableException;
import com.eventledger.gateway.model.Event;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);
    private final RestTemplate restTemplate;
    private final String accountServiceUrl;

    public AccountServiceClient(RestTemplate restTemplate, 
                                @Value("${account-service.url}") String accountServiceUrl) {
        this.restTemplate = restTemplate;
        this.accountServiceUrl = accountServiceUrl;
    }

    @CircuitBreaker(name = "accountService", fallbackMethod = "fallbackForwardTransaction")
    public void forwardTransaction(Event event) {
        String url = accountServiceUrl + "/accounts/" + event.getAccountId() + "/transactions";
        log.info("Forwarding transaction to Account Service: url={}, eventId={}", url, event.getEventId());

        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", event.getEventId());
        payload.put("accountId", event.getAccountId());
        payload.put("type", event.getType());
        payload.put("amount", event.getAmount());
        payload.put("currency", event.getCurrency());
        payload.put("eventTimestamp", event.getEventTimestamp());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
        restTemplate.postForEntity(url, request, Object.class);
        log.info("Successfully forwarded eventId={} to Account Service", event.getEventId());
    }

    public void fallbackForwardTransaction(Event event, Throwable t) {
        log.error("Circuit breaker triggered fallback for eventId={}. Error: {}", event.getEventId(), t.getMessage(), t);
        throw new DownstreamServiceUnavailableException("Account service is temporarily unavailable. Please try again later.", t);
    }
}
