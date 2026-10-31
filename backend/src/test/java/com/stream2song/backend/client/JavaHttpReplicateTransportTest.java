package com.stream2song.backend.client;

import static org.junit.jupiter.api.Assertions.*;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JavaHttpReplicateTransportTest {
    @Test void explicitDeadlineCancelsPendingResponseBody() {
        var pending=new CompletableFuture<String>();
        var failure=assertThrows(JavaHttpReplicateTransport.TransportFailure.class,()->JavaHttpReplicateTransport.await(pending,0));
        assertTrue(failure.timeout());assertTrue(pending.isCancelled());
    }
    @Test void interruptedAwaitCancelsRequestAndPreservesInterruptFlag() throws Exception {
        var pending=new CompletableFuture<String>();var started=new CountDownLatch(1);var cancelled=new CountDownLatch(1);
        Thread thread=new Thread(()->{started.countDown();try{JavaHttpReplicateTransport.await(pending,30);}catch(JavaHttpReplicateTransport.TransportFailure expected){if(Thread.currentThread().isInterrupted())cancelled.countDown();}});
        thread.start();assertTrue(started.await(1,TimeUnit.SECONDS));thread.interrupt();thread.join(1000);
        assertTrue(cancelled.await(1,TimeUnit.SECONDS));assertTrue(pending.isCancelled());
    }
    @Test void mapsNativeHttpTimeoutCauseToSafeTimeoutFailure(){
        var pending=new CompletableFuture<String>();pending.completeExceptionally(new HttpTimeoutException("test timeout"));
        assertTrue(assertThrows(JavaHttpReplicateTransport.TransportFailure.class,()->JavaHttpReplicateTransport.await(pending,1)).timeout());
    }
}
