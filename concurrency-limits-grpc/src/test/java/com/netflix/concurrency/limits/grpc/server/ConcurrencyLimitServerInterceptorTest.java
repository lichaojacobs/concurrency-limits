package com.netflix.concurrency.limits.grpc.server;

import com.netflix.concurrency.limits.Limit;
import com.netflix.concurrency.limits.grpc.StringMarshaller;
import com.netflix.concurrency.limits.grpc.client.ConcurrencyLimitClientInterceptor;
import com.netflix.concurrency.limits.grpc.client.GrpcClientLimiterBuilder;
import com.netflix.concurrency.limits.limit.FixedLimit;
import com.netflix.concurrency.limits.limit.VegasLimit;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import org.junit.Ignore;
import org.junit.Test;

public class ConcurrencyLimitServerInterceptorTest {
    private static final MethodDescriptor<String, String> METHOD_DESCRIPTOR = MethodDescriptor.create(MethodType.UNARY,
            "service/method", StringMarshaller.INSTANCE, StringMarshaller.INSTANCE);

    private static final Key<String> ID_HEADER = Metadata.Key.of("id", Metadata.ASCII_STRING_MARSHALLER);

    @Test
    @Ignore
    public void simulation() throws IOException, InterruptedException {
        Server server = NettyServerBuilder.forPort(0)
            .addService(ServerInterceptors.intercept(ServerServiceDefinition.builder("service")
                    .addMethod(METHOD_DESCRIPTOR, ServerCalls.asyncUnaryCall((req, observer) -> {
                        try {
                            TimeUnit.MILLISECONDS.sleep(100);
                        } catch (InterruptedException e) {
                        }
                        
                        observer.onNext("response");
                        observer.onCompleted();
                    }))
                    .build(), new ConcurrencyLimitServerInterceptor(new GrpcServerLimiterBuilder()
                            .limit(FixedLimit.of(50))
                            .headerEquals(0.1, ID_HEADER, "0")
                            .headerEquals(0.2, ID_HEADER, "1")
                            .headerEquals(0.7, ID_HEADER, "2")
                            .build())
                ))
            .build()
            .start();
        
        Limit clientLimit0 = VegasLimit.newDefault();
        Limit clientLimit1 = VegasLimit.newDefault();
        Limit clientLimit2 = VegasLimit.newDefault();
        
        AtomicLongArray counters = new AtomicLongArray(3);
        AtomicLong drops = new AtomicLong(0);
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            System.out.println("" + counters.getAndSet(0, 0) + ", " + counters.getAndSet(1, 0)+ ", " + counters.getAndSet(2, 0));
        }, 1, 1, TimeUnit.SECONDS);
        
        Executor executor = Executors.newCachedThreadPool();

        executor.execute(() -> simulateClient(0, counters, drops, server.getPort(), clientLimit0));
        executor.execute(() -> simulateClient(1, counters, drops, server.getPort(), clientLimit1));
        executor.execute(() -> simulateClient(2, counters, drops, server.getPort(), clientLimit2));
        
        TimeUnit.SECONDS.sleep(100);
    }

    private void simulateClient(int id, AtomicLongArray counters, AtomicLong drops, int port, Limit limit) {
        Metadata headers = new Metadata();
        headers.put(ID_HEADER, "" + id);

        Channel channel = NettyChannelBuilder.forTarget("localhost:" + port)
                .usePlaintext(true)
                .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
                .intercept(new ConcurrencyLimitClientInterceptor(new GrpcClientLimiterBuilder()
                        .limit(limit)
                        .blockOnLimit(true)
                        .build()))
                .build();

        try {
            while (true) {
                TimeUnit.MICROSECONDS.sleep(100);
                ClientCalls.asyncUnaryCall(channel.newCall(METHOD_DESCRIPTOR, CallOptions.DEFAULT.withWaitForReady()), "request",
                    new StreamObserver<String>() {
                        @Override
                        public void onNext(String value) {
                        }

                        @Override
                        public void onError(Throwable t) {
                            drops.incrementAndGet();
                        }

                        @Override
                        public void onCompleted() {
                            counters.incrementAndGet(id);
                        }
                    
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}