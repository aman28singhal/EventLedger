package com.eventledger.account.service;

import com.eventledger.account.model.AccountTransaction;
import com.eventledger.account.repository.AccountTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
public class AccountServiceTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountTransactionRepository repository;

    @BeforeEach
    public void setUp() {
        repository.deleteAll();
    }

    @Test
    public void testApplyTransactionAndGetBalance() {
        String accountId = "acct-123";
        AccountTransaction credit = new AccountTransaction("evt-1", accountId, "CREDIT", new BigDecimal("150.00"), "USD", Instant.now());
        AccountTransaction debit = new AccountTransaction("evt-2", accountId, "DEBIT", new BigDecimal("50.00"), "USD", Instant.now());

        accountService.applyTransaction(credit);
        accountService.applyTransaction(debit);

        BigDecimal balance = accountService.getBalance(accountId);
        assertEquals(new BigDecimal("100.00"), balance);
    }

    @Test
    public void testIdempotency() {
        String accountId = "acct-123";
        AccountTransaction credit1 = new AccountTransaction("evt-1", accountId, "CREDIT", new BigDecimal("150.00"), "USD", Instant.now());
        AccountTransaction credit2 = new AccountTransaction("evt-1", accountId, "CREDIT", new BigDecimal("150.00"), "USD", Instant.now());

        accountService.applyTransaction(credit1);
        accountService.applyTransaction(credit2); // duplicate eventId

        BigDecimal balance = accountService.getBalance(accountId);
        assertEquals(new BigDecimal("150.00"), balance); // Should not count twice

        List<AccountTransaction> txList = accountService.getTransactions(accountId);
        assertEquals(1, txList.size());
    }

    @Test
    public void testOutOfOrderArrivalTolerance() {
        String accountId = "acct-123";
        Instant t1 = Instant.parse("2026-05-15T14:00:00Z");
        Instant t2 = Instant.parse("2026-05-15T15:00:00Z");

        // Event 2 (later timestamp) arrives FIRST
        AccountTransaction debit = new AccountTransaction("evt-2", accountId, "DEBIT", new BigDecimal("50.00"), "USD", t2);
        accountService.applyTransaction(debit);

        // Event 1 (earlier timestamp) arrives SECOND
        AccountTransaction credit = new AccountTransaction("evt-1", accountId, "CREDIT", new BigDecimal("150.00"), "USD", t1);
        accountService.applyTransaction(credit);

        // Balance should still be correct (150 - 50 = 100)
        BigDecimal balance = accountService.getBalance(accountId);
        assertEquals(new BigDecimal("100.00"), balance);

        // Listing should be in order of insertion/retrieval
        List<AccountTransaction> txList = accountService.getTransactions(accountId);
        assertEquals(2, txList.size());
    }
}
