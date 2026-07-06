package com.eventledger.account.repository;

import com.eventledger.account.model.AccountTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, Long> {
    
    boolean existsByEventId(String eventId);

    Optional<AccountTransaction> findByEventId(String eventId);

    List<AccountTransaction> findByAccountIdOrderByEventTimestampDesc(String accountId);

    @Query("SELECT SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE -t.amount END) " +
           "FROM AccountTransaction t WHERE t.accountId = :accountId")
    Optional<BigDecimal> getBalanceForAccount(@Param("accountId") String accountId);
}
