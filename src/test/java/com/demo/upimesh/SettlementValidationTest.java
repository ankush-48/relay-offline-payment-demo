package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.SettlementService;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SettlementValidationTest {
    @Test
    void insufficientBalanceIsRejectedWithoutChangingEitherBalance() throws Exception {
        Account alice=new Account("alice@demo","Alice",new BigDecimal("5000.00"));
        Account bob=new Account("bob@demo","Bob",new BigDecimal("1000.00"));
        AtomicInteger ledgerWrites=new AtomicInteger();
        SettlementService service=service(Map.of("alice@demo",alice,"bob@demo",bob),ledgerWrites);
        Transaction result=service.settle(instruction(new BigDecimal("999999.00"),Instant.now()),"hash-reject","bridge-test",1);
        assertEquals(Transaction.Status.REJECTED,result.getStatus());
        assertEquals(new BigDecimal("5000.00"),alice.getBalance());
        assertEquals(new BigDecimal("1000.00"),bob.getBalance());
        assertEquals(1,ledgerWrites.get());
    }

    @Test
    void validSettlementUpdatesBothAccountsAndWritesOneTransaction() throws Exception {
        Account alice=new Account("alice@demo","Alice",new BigDecimal("5000.00"));
        Account bob=new Account("bob@demo","Bob",new BigDecimal("1000.00"));
        AtomicInteger ledgerWrites=new AtomicInteger();
        SettlementService service=service(Map.of("alice@demo",alice,"bob@demo",bob),ledgerWrites);
        Transaction result=service.settle(instruction(new BigDecimal("25.00"),Instant.now()),"hash-ok-123456","bridge-test",2);
        assertEquals(Transaction.Status.SETTLED,result.getStatus());
        assertEquals(new BigDecimal("4975.00"),alice.getBalance());
        assertEquals(new BigDecimal("1025.00"),bob.getBalance());
        assertEquals(1,ledgerWrites.get());
    }

    @Test
    void zeroAndNegativeAmountsAreRejectedBeforeLedgerWrites() throws Exception {
        Account alice=new Account("alice@demo","Alice",new BigDecimal("5000.00"));
        Account bob=new Account("bob@demo","Bob",new BigDecimal("1000.00"));
        AtomicInteger ledgerWrites=new AtomicInteger();
        SettlementService service=service(Map.of("alice@demo",alice,"bob@demo",bob),ledgerWrites);
        assertThrows(IllegalArgumentException.class,()->service.settle(instruction(BigDecimal.ZERO,Instant.now()),"hash-zero","bridge-test",1));
        assertThrows(IllegalArgumentException.class,()->service.settle(instruction(new BigDecimal("-1.00"),Instant.now()),"hash-negative","bridge-test",1));
        assertEquals(0,ledgerWrites.get());
    }

    @Test
    void staleAndFuturePacketsFailFreshnessWindow() {
        Instant now=Instant.now();
        assertEquals("stale_packet",BridgeIngestionService.freshnessFailure(now.minusSeconds(172800).toEpochMilli(),now,86400));
        assertEquals("future_dated",BridgeIngestionService.freshnessFailure(now.plusSeconds(600).toEpochMilli(),now,86400));
        assertNull(BridgeIngestionService.freshnessFailure(now.minusSeconds(30).toEpochMilli(),now,86400));
    }

    @Test
    void tamperingIsDetectedByAuthenticatedEncryption() throws Exception {
        ServerKeyHolder key=new ServerKeyHolder(); key.init();
        HybridCryptoService crypto=new HybridCryptoService();
        Field field=HybridCryptoService.class.getDeclaredField("serverKey"); field.setAccessible(true); field.set(crypto,key);
        PaymentInstruction original=instruction(new BigDecimal("123.45"),Instant.now());
        String encrypted=crypto.encrypt(original,key.getPublicKey());
        PaymentInstruction decoded=crypto.decrypt(encrypted);
        assertEquals(original.getSenderVpa(),decoded.getSenderVpa());
        assertEquals(0,original.getAmount().compareTo(decoded.getAmount()));
        char[] changed=encrypted.toCharArray(); changed[changed.length/2]=changed[changed.length/2]=='A'?'B':'A';
        assertThrows(Exception.class,()->crypto.decrypt(new String(changed)));
    }

    private SettlementService service(Map<String,Account> byVpa,AtomicInteger writes) throws Exception {
        AccountRepository accounts=(AccountRepository)Proxy.newProxyInstance(AccountRepository.class.getClassLoader(),new Class[]{AccountRepository.class},(proxy,method,args)->{
            if(method.getName().equals("findById")) return Optional.ofNullable(byVpa.get((String)args[0]));
            if(method.getName().equals("save")) return args[0];
            return defaultValue(method.getReturnType());
        });
        TransactionRepository transactions=(TransactionRepository)Proxy.newProxyInstance(TransactionRepository.class.getClassLoader(),new Class[]{TransactionRepository.class},(proxy,method,args)->{
            if(method.getName().equals("save")){writes.incrementAndGet();return args[0];}
            return defaultValue(method.getReturnType());
        });
        SettlementService service=new SettlementService();
        set(service,"accounts",accounts); set(service,"transactions",transactions);
        return service;
    }
    private static Object defaultValue(Class<?> type){if(type==Optional.class)return Optional.empty();if(type==List.class)return List.of();if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
    private static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    private PaymentInstruction instruction(BigDecimal amount,Instant signedAt){return new PaymentInstruction("alice@demo","bob@demo",amount,"hash",UUID.randomUUID().toString(),signedAt.toEpochMilli());}
}
