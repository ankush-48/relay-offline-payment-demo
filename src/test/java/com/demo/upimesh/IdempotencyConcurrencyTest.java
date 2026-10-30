package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class IdempotencyConcurrencyTest {
    @Test
    void oneConcurrentBridgeDeliverySettlesAndOtherTwoAreDropped() throws Exception {
        Account alice=new Account("alice@demo","Alice",new BigDecimal("5000.00"));
        Account bob=new Account("bob@demo","Bob",new BigDecimal("1000.00"));
        Map<String,Account> byVpa=Map.of("alice@demo",alice,"bob@demo",bob);
        AtomicInteger ledgerWrites=new AtomicInteger();
        AccountRepository accounts=(AccountRepository)Proxy.newProxyInstance(AccountRepository.class.getClassLoader(),new Class[]{AccountRepository.class},(proxy,method,args)->{
            if(method.getName().equals("findById"))return Optional.ofNullable(byVpa.get((String)args[0]));
            if(method.getName().equals("save"))return args[0];
            return defaultValue(method.getReturnType());
        });
        TransactionRepository transactions=(TransactionRepository)Proxy.newProxyInstance(TransactionRepository.class.getClassLoader(),new Class[]{TransactionRepository.class},(proxy,method,args)->{
            if(method.getName().equals("save")){ledgerWrites.incrementAndGet();return args[0];}
            return defaultValue(method.getReturnType());
        });
        SettlementService settlement=new SettlementService(); set(settlement,"accounts",accounts); set(settlement,"transactions",transactions);
        PaymentInstruction instruction=new PaymentInstruction("alice@demo","bob@demo",new BigDecimal("100.00"),"demo-hash","nonce",Instant.now().toEpochMilli());
        HybridCryptoService crypto=new HybridCryptoService(){
            @Override public String hashCiphertext(String ignored){return "same-packet-hash";}
            @Override public PaymentInstruction decrypt(String ignored){return instruction;}
        };
        IdempotencyService idempotency=new IdempotencyService();
        BridgeIngestionService bridge=new BridgeIngestionService();
        set(bridge,"crypto",crypto); set(bridge,"idempotency",idempotency); set(bridge,"settlement",settlement); set(bridge,"maxAgeSeconds",86400L);
        MeshPacket packet=new MeshPacket(); packet.setPacketId("same-packet"); packet.setTtl(5); packet.setCreatedAt(Instant.now().toEpochMilli()); packet.setCiphertext("sealed");

        ExecutorService pool=Executors.newFixedThreadPool(3); CountDownLatch start=new CountDownLatch(1);
        List<Future<BridgeIngestionService.IngestResult>> results=new ArrayList<>();
        for(int i=0;i<3;i++){final int n=i;results.add(pool.submit(()->{start.await();return bridge.ingest(packet,"bridge-"+n,2);}));}
        start.countDown(); List<String> outcomes=new ArrayList<>(); for(Future<BridgeIngestionService.IngestResult> future:results)outcomes.add(future.get(5,TimeUnit.SECONDS).outcome()); pool.shutdownNow();
        assertEquals(1,Collections.frequency(outcomes,"SETTLED"));
        assertEquals(2,Collections.frequency(outcomes,"DUPLICATE_DROPPED"));
        assertEquals(1,ledgerWrites.get());
        assertEquals(new BigDecimal("4900.00"),alice.getBalance());
        assertEquals(new BigDecimal("1100.00"),bob.getBalance());
    }
    private static Object defaultValue(Class<?> type){if(type==Optional.class)return Optional.empty();if(type==List.class)return List.of();if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;}
    private static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
}
