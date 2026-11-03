package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findTop20ByOrderByIdDesc();
    List<Transaction> findTop20BySenderVpaInOrReceiverVpaInOrderByIdDesc(Collection<String> senders, Collection<String> receivers);
    void deleteBySenderVpaInOrReceiverVpaIn(Collection<String> senders, Collection<String> receivers);
    boolean existsByPacketHash(String packetHash);
}
