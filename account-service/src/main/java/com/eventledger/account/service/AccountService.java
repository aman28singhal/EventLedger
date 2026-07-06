package com.eventledger.account.service;

import com.eventledger.account.model.AccountTransaction;
import com.eventledger.account.repository.AccountTransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);
    private final AccountTransactionRepository repository;
    private final Counter transactionCounter;

    public AccountService(AccountTransactionRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.transactionCounter = meterRegistry.counter("eventledger.account.transactions.applied");
    }

    @Transactional
    public AccountTransaction applyTransaction(AccountTransaction transaction) {
        log.info("Processing transaction: eventId={}, accountId={}, type={}, amount={}",
                transaction.getEventId(), transaction.getAccountId(), transaction.getType(), transaction.getAmount());

        // Primary idempotency check
        if (repository.existsByEventId(transaction.getEventId())) {
            log.warn("Transaction with eventId {} already exists, ignoring to ensure idempotency", transaction.getEventId());
            return repository.findByEventId(transaction.getEventId()).orElse(transaction);
        }

        try {
            AccountTransaction saved = repository.save(transaction);
            transactionCounter.increment();
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Fallback idempotency guard for concurrent duplicate submissions
            log.warn("Concurrent duplicate detected for eventId={}, returning existing record", transaction.getEventId());
            return repository.findByEventId(transaction.getEventId()).orElse(transaction);
        }
    }

    @Transactional(readOnly = true)
    public BigDecimal getBalance(String accountId) {
        log.info("Calculating balance for accountId={}", accountId);
        return repository.getBalanceForAccount(accountId).orElse(BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public List<AccountTransaction> getTransactions(String accountId) {
        log.info("Retrieving transactions for accountId={}", accountId);
        return repository.findByAccountIdOrderByEventTimestampDesc(accountId);
    }
}
