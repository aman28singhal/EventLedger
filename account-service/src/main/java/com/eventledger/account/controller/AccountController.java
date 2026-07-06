package com.eventledger.account.controller;

import com.eventledger.account.model.AccountTransaction;
import com.eventledger.account.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/{accountId}/transactions")
    public ResponseEntity<AccountTransaction> applyTransaction(
            @PathVariable String accountId,
            @Valid @RequestBody AccountTransaction transaction) {
        // Enforce path parameter consistency
        transaction.setAccountId(accountId);
        AccountTransaction saved = accountService.applyTransaction(transaction);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/{accountId}/balance")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable String accountId) {
        BigDecimal balance = accountService.getBalance(accountId);
        Map<String, Object> response = new HashMap<>();
        response.put("accountId", accountId);
        response.put("balance", balance);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<Map<String, Object>> getAccountDetails(@PathVariable String accountId) {
        BigDecimal balance = accountService.getBalance(accountId);
        List<AccountTransaction> transactions = accountService.getTransactions(accountId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("accountId", accountId);
        response.put("balance", balance);
        response.put("transactions", transactions);
        return ResponseEntity.ok(response);
    }
}
