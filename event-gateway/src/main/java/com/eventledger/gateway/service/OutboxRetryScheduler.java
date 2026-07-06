package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.model.Event;
import com.eventledger.gateway.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OutboxRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetryScheduler.class);

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;

    public OutboxRetryScheduler(EventRepository eventRepository, AccountServiceClient accountServiceClient) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
    }

    @Scheduled(fixedDelayString = "${outbox.retry.interval:5000}")
    public void retryPendingEvents() {
        List<Event> pendingEvents = eventRepository.findByStatusOrderByEventTimestampAsc("RECEIVED");
        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("Found {} pending events in outbox. Attempting to forward...", pendingEvents.size());

        for (Event event : pendingEvents) {
            try {
                log.info("Outbox retrying eventId={} for accountId={}", event.getEventId(), event.getAccountId());
                accountServiceClient.forwardTransaction(event);
                
                // If forward succeeds, update status to PROPAGATED
                event.setStatus("PROPAGATED");
                eventRepository.save(event);
                log.info("Outbox successfully forwarded and updated eventId={}", event.getEventId());
            } catch (Exception e) {
                log.error("Outbox failed to forward eventId={}. Error: {}. Will retry in next execution.", 
                        event.getEventId(), e.getMessage());
                // Break to preserve chronological order of event propagation for this run
                break;
            }
        }
    }
}
