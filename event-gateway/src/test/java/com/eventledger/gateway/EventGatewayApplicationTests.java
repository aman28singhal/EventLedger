package com.eventledger.gateway;

import com.eventledger.gateway.model.Event;
import com.eventledger.gateway.repository.EventRepository;
import com.eventledger.gateway.service.OutboxRetryScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class EventGatewayApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private OutboxRetryScheduler outboxRetryScheduler;

    private MockRestServiceServer mockServer;

    @BeforeEach
    public void setUp() {
        mockServer = MockRestServiceServer.bindTo(restTemplate).build();
        eventRepository.deleteAll();
    }

    @Test
    public void testCreateEventSuccessFlow() throws Exception {
        // Expect a call to downstream and check X-Trace-Id propagation
        mockServer.expect(requestTo(startsWith("http://localhost:8081/accounts/acct-123/transactions")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Trace-Id", notNullValue()))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        String payload = "{\n" +
                "  \"eventId\": \"evt-001\",\n" +
                "  \"accountId\": \"acct-123\",\n" +
                "  \"type\": \"CREDIT\",\n" +
                "  \"amount\": 150.00,\n" +
                "  \"currency\": \"USD\",\n" +
                "  \"eventTimestamp\": \"2026-05-15T14:02:11Z\"\n" +
                "}";

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-001"))
                .andExpect(jsonPath("$.status").value("PROPAGATED"));

        // Verify local DB save
        assertTrue(eventRepository.findById("evt-001").isPresent());
        assertEquals("PROPAGATED", eventRepository.findById("evt-001").get().getStatus());

        mockServer.verify();
    }

    @Test
    public void testValidationFailure_InvalidType() throws Exception {
        // Invalid type — should be rejected by @Pattern(regexp = "CREDIT|DEBIT")
        String payload = "{\n" +
                "  \"eventId\": \"evt-002\",\n" +
                "  \"accountId\": \"acct-123\",\n" +
                "  \"type\": \"INVALID\",\n" +
                "  \"amount\": 10.00,\n" +
                "  \"currency\": \"USD\",\n" +
                "  \"eventTimestamp\": \"2026-05-15T14:02:11Z\"\n" +
                "}";

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    public void testValidationFailure_NegativeAmount() throws Exception {
        // Negative amount — should be rejected by @DecimalMin
        String payload = "{\n" +
                "  \"eventId\": \"evt-003\",\n" +
                "  \"accountId\": \"acct-123\",\n" +
                "  \"type\": \"CREDIT\",\n" +
                "  \"amount\": -10.00,\n" +
                "  \"currency\": \"USD\",\n" +
                "  \"eventTimestamp\": \"2026-05-15T14:02:11Z\"\n" +
                "}";

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.details.amount").value("amount must be greater than 0"));
    }

    @Test
    public void testValidationFailure_MissingFields() throws Exception {
        // Missing required fields
        String payload = "{\n" +
                "  \"eventId\": \"evt-004\"\n" +
                "}";

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    public void testIdempotencyDuplicateRequest() throws Exception {
        // Prepare pre-existing propagated event in DB
        Event event = new Event("evt-duplicate", "acct-123", "CREDIT", new BigDecimal("100.00"), "USD", Instant.now(), new HashMap<>(), "PROPAGATED");
        eventRepository.save(event);

        String payload = "{\n" +
                "  \"eventId\": \"evt-duplicate\",\n" +
                "  \"accountId\": \"acct-123\",\n" +
                "  \"type\": \"CREDIT\",\n" +
                "  \"amount\": 100.00,\n" +
                "  \"currency\": \"USD\",\n" +
                "  \"eventTimestamp\": \"2026-05-15T14:02:11Z\"\n" +
                "}";

        // Second POST should return 200 OK and NOT trigger call to RestTemplate (since status is PROPAGATED)
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-duplicate"))
                .andExpect(jsonPath("$.status").value("PROPAGATED"));

        // Verify no call was made to downstream mock server
        mockServer.verify();
    }

    @Test
    public void testDownstreamFailureReturns503() throws Exception {
        // Simulate downstream server failure (500) and allow multiple retries
        mockServer.expect(org.springframework.test.web.client.ExpectedCount.manyTimes(), requestTo(startsWith("http://localhost:8081/accounts/acct-123/transactions")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        String payload = "{\n" +
                "  \"eventId\": \"evt-fail\",\n" +
                "  \"accountId\": \"acct-123\",\n" +
                "  \"type\": \"CREDIT\",\n" +
                "  \"amount\": 100.00,\n" +
                "  \"currency\": \"USD\",\n" +
                "  \"eventTimestamp\": \"2026-05-15T14:02:11Z\"\n" +
                "}";

        // POST should fail with 503 Service Unavailable
        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isServiceUnavailable());

        // Event should still be persisted in the local Gateway DB with RECEIVED status
        assertTrue(eventRepository.findById("evt-fail").isPresent());
        assertEquals("RECEIVED", eventRepository.findById("evt-fail").get().getStatus());

        // Local GET /events/{id} should still work successfully (graceful degradation)
        mockMvc.perform(get("/events/evt-fail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-fail"))
                .andExpect(jsonPath("$.status").value("RECEIVED"));
    }

    @Test
    public void testOutOfOrderEventsReturnedChronologically() throws Exception {
        // Insert events in REVERSE chronological order (out-of-order arrival)
        Instant t1 = Instant.parse("2026-05-15T10:00:00Z");
        Instant t2 = Instant.parse("2026-05-15T12:00:00Z");
        Instant t3 = Instant.parse("2026-05-15T14:00:00Z");

        // Arrive in order: t3, t1, t2
        eventRepository.save(new Event("evt-c", "acct-ooo", "CREDIT", new BigDecimal("300.00"), "USD", t3, null, "PROPAGATED"));
        eventRepository.save(new Event("evt-a", "acct-ooo", "CREDIT", new BigDecimal("100.00"), "USD", t1, null, "PROPAGATED"));
        eventRepository.save(new Event("evt-b", "acct-ooo", "DEBIT", new BigDecimal("200.00"), "USD", t2, null, "PROPAGATED"));

        // GET events should return them sorted by eventTimestamp ascending: evt-a, evt-b, evt-c
        mockMvc.perform(get("/events").param("account", "acct-ooo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventId").value("evt-a"))
                .andExpect(jsonPath("$[0].eventTimestamp").value("2026-05-15T10:00:00Z"))
                .andExpect(jsonPath("$[1].eventId").value("evt-b"))
                .andExpect(jsonPath("$[1].eventTimestamp").value("2026-05-15T12:00:00Z"))
                .andExpect(jsonPath("$[2].eventId").value("evt-c"))
                .andExpect(jsonPath("$[2].eventTimestamp").value("2026-05-15T14:00:00Z"));
    }

    @Test
    public void testGetEventNotFoundReturns404() throws Exception {
        mockMvc.perform(get("/events/nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void testTraceIdInResponseHeader() throws Exception {
        // Expect downstream call
        mockServer.expect(requestTo(startsWith("http://localhost:8081/accounts/acct-123/transactions")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        String payload = "{\n" +
                "  \"eventId\": \"evt-trace\",\n" +
                "  \"accountId\": \"acct-123\",\n" +
                "  \"type\": \"CREDIT\",\n" +
                "  \"amount\": 50.00,\n" +
                "  \"currency\": \"USD\",\n" +
                "  \"eventTimestamp\": \"2026-05-15T14:02:11Z\"\n" +
                "}";

        mockMvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("X-Trace-Id"));

        mockServer.verify();
    }

    @Test
    public void testOutboxSchedulerRetriesPendingEvents() throws Exception {
        // Prepare pre-existing RECEIVED event in DB (failed initial propagation)
        Event event = new Event("evt-outbox-test", "acct-123", "CREDIT", new BigDecimal("100.00"), "USD", Instant.now(), new HashMap<>(), "RECEIVED");
        eventRepository.save(event);

        // Expect outbox scheduler call to downstream
        mockServer.expect(requestTo(startsWith("http://localhost:8081/accounts/acct-123/transactions")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // Call the scheduler directly
        outboxRetryScheduler.retryPendingEvents();

        // Verify status in DB got updated to PROPAGATED
        Event updatedEvent = eventRepository.findById("evt-outbox-test").orElseThrow();
        assertEquals("PROPAGATED", updatedEvent.getStatus());

        mockServer.verify();
    }
}
