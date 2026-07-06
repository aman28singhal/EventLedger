package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.model.Event;
import com.eventledger.gateway.repository.EventRepository;
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

    public EventService(EventRepository repository, 
                        AccountServiceClient accountServiceClient) {
        this.repository = repository;
        this.accountServiceClient = accountServiceClient;
    }

    public record ProcessResult(Event event, boolean isNew) {}

    public ProcessResult processEvent(Event event) {
        log.info("Processing event: eventId={}, accountId={}", event.getEventId(), event.getAccountId());

        Optional<Event> existingOpt = repository.findById(event.getEventId());
        
        if (existingOpt.isPresent()) {
            Event existing = existingOpt.get();
            log.info("Event {} already exists in Gateway DB with status {}", existing.getEventId(), existing.getStatus());
            if ("PROPAGATED".equals(existing.getStatus())) {
                return new ProcessResult(existing, false);
            }
            forwardToDownstream(existing);
            return new ProcessResult(existing, false);
        }

        event.setStatus("RECEIVED");
        Event saved = repository.save(event);
        log.info("Saved event to Gateway DB: eventId={}", saved.getEventId());

        forwardToDownstream(saved);

        return new ProcessResult(saved, true);
    }

    private void forwardToDownstream(Event event) {
        accountServiceClient.forwardTransaction(event);
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
