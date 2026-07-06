package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.model.Event;
import com.eventledger.gateway.repository.EventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);
    private final EventRepository repository;
    private final AccountServiceClient accountServiceClient;
    private final Counter successCounter;
    private final Counter failureCounter;

    public EventService(EventRepository repository, 
                        AccountServiceClient accountServiceClient,
                        MeterRegistry meterRegistry) {
        this.repository = repository;
        this.accountServiceClient = accountServiceClient;
        this.successCounter = meterRegistry.counter("eventledger.gateway.events.processed", "status", "success");
        this.failureCounter = meterRegistry.counter("eventledger.gateway.events.processed", "status", "failure");
    }

    /**
     * Result wrapper indicating whether the event was newly created or a duplicate.
     */
    public record ProcessResult(Event event, boolean isNew) {}

    /**
     * Process an incoming event. Handles idempotency, local persistence, and downstream forwarding.
     * Returns a ProcessResult indicating whether the event is new (201) or duplicate (200).
     */
    public ProcessResult processEvent(Event event) {
        log.info("Processing event: eventId={}, accountId={}", event.getEventId(), event.getAccountId());

        try {
            Optional<Event> existingOpt = repository.findById(event.getEventId());
            
            if (existingOpt.isPresent()) {
                Event existing = existingOpt.get();
                log.info("Event {} already exists in Gateway DB with status {}", existing.getEventId(), existing.getStatus());
                if ("PROPAGATED".equals(existing.getStatus())) {
                    // Already successfully processed and forwarded
                    successCounter.increment();
                    return new ProcessResult(existing, false);
                }
                // If it exists but is not propagated, try forwarding again
                forwardToDownstream(existing);
                successCounter.increment();
                return new ProcessResult(existing, false);
            }

            // 1. Save locally first in Gateway DB
            event.setStatus("RECEIVED");
            Event saved = repository.save(event);
            log.info("Saved event to Gateway DB: eventId={}", saved.getEventId());

            // 2. Forward to downstream Account Service
            forwardToDownstream(saved);

            successCounter.increment();
            return new ProcessResult(saved, true);
        } catch (Exception e) {
            failureCounter.increment();
            throw e;
        }
    }

    private void forwardToDownstream(Event event) {
        accountServiceClient.forwardTransaction(event);
        // If forward is successful, update status to PROPAGATED
        event.setStatus("PROPAGATED");
        repository.save(event);
        log.info("Updated event status to PROPAGATED: eventId={}", event.getEventId());
    }

    @Transactional(readOnly = true)
    public Optional<Event> getEventById(String id) {
        log.info("Fetching event by ID: {}", id);
        return repository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Event> getEventsByAccount(String accountId) {
        log.info("Fetching events for account ID: {}", accountId);
        return repository.findByAccountIdOrderByEventTimestampAsc(accountId);
    }
}
