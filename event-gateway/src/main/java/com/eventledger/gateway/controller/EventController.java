package com.eventledger.gateway.controller;

import com.eventledger.gateway.model.Event;
import com.eventledger.gateway.service.EventService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService eventService;
    private final Counter requestCounter;

    public EventController(EventService eventService, MeterRegistry meterRegistry) {
        this.eventService = eventService;
        this.requestCounter = meterRegistry.counter("eventledger.gateway.requests.total");
    }

    @PostMapping
    public ResponseEntity<Event> createEvent(@Valid @RequestBody Event event) {
        requestCounter.increment();
        EventService.ProcessResult result = eventService.processEvent(event);
        
        if (result.isNew()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(result.event());
        } else {
            return ResponseEntity.ok(result.event());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Event> getEventById(@PathVariable String id) {
        return eventService.getEventById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<Event>> getEventsByAccount(@RequestParam("account") String accountId) {
        List<Event> events = eventService.getEventsByAccount(accountId);
        return ResponseEntity.ok(events);
    }
}
