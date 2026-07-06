package com.eventledger.account.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "account_transactions", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"eventId"})
})
public class AccountTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "eventId is required")
    @Column(nullable = false, unique = true)
    private String eventId;

    @NotBlank(message = "accountId is required")
    @Column(nullable = false)
    private String accountId;

    @NotBlank(message = "type is required")
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
    @Column(nullable = false)
    private Instant eventTimestamp;

    public AccountTransaction() {}

    public AccountTransaction(String eventId, String accountId, String type, BigDecimal amount, String currency, Instant eventTimestamp) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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
}
