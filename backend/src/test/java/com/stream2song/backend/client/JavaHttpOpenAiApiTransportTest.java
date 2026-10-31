package com.stream2song.backend.client;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

import org.junit.jupiter.api.Test;

class JavaHttpOpenAiApiTransportTest {
    @Test void boundedHttpBodySubscriberCancelsBeforeAcceptingMoreThanOneMiB() {
        var subscriber = new JavaHttpOpenAiApiTransport.BoundedBodySubscriber();
        class TestSubscription implements Flow.Subscription {
            private boolean cancelled;
            @Override public void request(long amount) { }
            @Override public void cancel() { cancelled = true; }
        }
        TestSubscription subscription = new TestSubscription();
        subscriber.onSubscribe(subscription);
        byte[] chunk = new byte[16_384];
        for (int i = 0; i < 65; i++) subscriber.onNext(List.of(ByteBuffer.wrap(chunk)));
        var failure = assertThrows(CompletionException.class, () -> subscriber.getBody().toCompletableFuture().join());
        assertInstanceOf(JavaHttpOpenAiApiTransport.OpenAiTransportFailure.class, failure.getCause());
        assertTrue(((JavaHttpOpenAiApiTransport.OpenAiTransportFailure) failure.getCause()).oversized());
        assertTrue(subscription.cancelled);
    }

    @Test void acceptsBoundedResponseBytes() {
        var subscriber = new JavaHttpOpenAiApiTransport.BoundedBodySubscriber();
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override public void request(long amount) { }
            @Override public void cancel() { fail("bounded response should not be canceled"); }
        });
        byte[] bytes = new byte[128];
        subscriber.onNext(List.of(ByteBuffer.wrap(bytes)));
        subscriber.onComplete();
        assertEquals(128, subscriber.getBody().toCompletableFuture().join().length);
    }

    @Test void explicitTotalDeadlineCancelsAnIncompleteResponse() {
        var pending = new java.util.concurrent.CompletableFuture<String>();
        var failure = assertThrows(JavaHttpOpenAiApiTransport.OpenAiTransportFailure.class,
                () -> JavaHttpOpenAiApiTransport.await(pending, java.time.Duration.ofMillis(20)));
        assertTrue(failure.timeout());
        assertTrue(pending.isCancelled());
    }

    @Test void mapsNativeAsyncHttpTimeoutCauseToTimeoutFailure() {
        var pending = new java.util.concurrent.CompletableFuture<String>();
        pending.completeExceptionally(new java.net.http.HttpTimeoutException("synthetic timeout"));
        var failure = assertThrows(JavaHttpOpenAiApiTransport.OpenAiTransportFailure.class,
                () -> JavaHttpOpenAiApiTransport.await(pending, java.time.Duration.ofSeconds(1)));
        assertTrue(failure.timeout());
    }
}
