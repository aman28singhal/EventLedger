package com.eventledger.gateway.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "events")
public class Event {

    @Id
    @NotBlank(message = "eventId is required")
    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId;

    @NotBlank(message = "accountId is required")
    @Column(name = "account_id", nullable = false)
    private String accountId;

    @NotBlank(message = "type is required")
    @Pattern(regexp = "CREDIT|DEBIT", message = "type must be CREDIT or DEBIT")
    @Column(nullable = false)
    private String type; // CREDIT or DEBIT

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "amount must be greater than 0")
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @NotBlank(message = "currency is required")
    @Column(nullable = false)
    private String currency;

    @NotNull(message = "eventTimestamp is required")
    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Convert(converter = JsonConverter.class)
    @Column(columnDefinition = "TEXT")
    private Map<String, Object> metadata;

    @Column(nullable = false)
    private String status = "RECEIVED"; // RECEIVED or PROPAGATED

    public Event() {}

    public Event(String eventId, String accountId, String type, BigDecimal amount, String currency, Instant eventTimestamp, Map<String, Object> metadata) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.metadata = metadata;
        this.status = "RECEIVED";
    }

    public Event(String eventId, String accountId, String type, BigDecimal amount, String currency, Instant eventTimestamp, Map<String, Object> metadata, String status) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.metadata = metadata;
        this.status = status;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Instant getEventTimestamp() { return eventTimestamp; }
    public void setEventTimestamp(Instant eventTimestamp) { this.eventTimestamp = eventTimestamp; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    // Nested JPA Converter for JSON metadata
    @Converter
    public static class JsonConverter implements AttributeConverter<Map<String, Object>, String> {
        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public String convertToDatabaseColumn(Map<String, Object> attribute) {
            if (attribute == null) return null;
            try {
                return objectMapper.writeValueAsString(attribute);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Error converting Map to JSON string", e);
            }
        }

        @Override
        public Map<String, Object> convertToEntityAttribute(String dbData) {
            if (dbData == null || dbData.isEmpty()) return null;
            try {
                return objectMapper.readValue(dbData, new TypeReference<Map<String, Object>>() {});
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Error converting JSON string to Map", e);
            }
        }
    }
}
