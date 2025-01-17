/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.membership.messaging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.salesfoce.apollo.test.proto.ByteMessage;
import com.salesforce.apollo.archipelago.LocalServer;
import com.salesforce.apollo.archipelago.Router;
import com.salesforce.apollo.archipelago.ServerConnectionCache;
import com.salesforce.apollo.archipelago.ServerConnectionCacheMetricsImpl;
import com.salesforce.apollo.crypto.Digest;
import com.salesforce.apollo.crypto.DigestAlgorithm;
import com.salesforce.apollo.membership.Context;
import com.salesforce.apollo.membership.Member;
import com.salesforce.apollo.membership.SigningMember;
import com.salesforce.apollo.membership.messaging.rbc.RbcMetrics;
import com.salesforce.apollo.membership.messaging.rbc.RbcMetricsImpl;
import com.salesforce.apollo.membership.messaging.rbc.ReliableBroadcaster;
import com.salesforce.apollo.membership.messaging.rbc.ReliableBroadcaster.MessageHandler;
import com.salesforce.apollo.membership.messaging.rbc.ReliableBroadcaster.Msg;
import com.salesforce.apollo.membership.messaging.rbc.ReliableBroadcaster.Parameters;
import com.salesforce.apollo.membership.stereotomy.ControlledIdentifierMember;
import com.salesforce.apollo.stereotomy.StereotomyImpl;
import com.salesforce.apollo.stereotomy.mem.MemKERL;
import com.salesforce.apollo.stereotomy.mem.MemKeyStore;
import com.salesforce.apollo.utils.Entropy;

/**
 * @author hal.hildebrand
 *
 */
public class RbcTest {

    class Receiver implements MessageHandler {
        final Set<Digest>                     counted = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final AtomicInteger                   current;
        final Digest                          memberId;
        final AtomicReference<CountDownLatch> round   = new AtomicReference<>();

        Receiver(Digest memberId, int cardinality, AtomicInteger current) {
            this.current = current;
            this.memberId = memberId;
        }

        @Override
        public void message(Digest context, List<Msg> messages) {
            messages.forEach(m -> {
                assert m.source() != null : "null member";
                ByteBuffer buf;
                try {
                    buf = m.content().unpack(ByteMessage.class).getContents().asReadOnlyByteBuffer();
                } catch (InvalidProtocolBufferException e) {
                    throw new IllegalStateException(e);
                }
                assert buf.remaining() > 4 : "buffer: " + buf.remaining();
                final var index = buf.getInt();
                if (index == current.get() + 1) {
                    if (counted.add(m.source().get(0))) {
                        int totalCount = totalReceived.incrementAndGet();
                        if (totalCount % 1_000 == 0) {
                            System.out.print(".");
                        }
                        if (totalCount % 80_000 == 0) {
                            System.out.println();
                        }
                        if (counted.size() == messengers.size() - 1) {
                            round.get().countDown();
                        }
                    }
                }
            });
        }

        public void setRound(CountDownLatch round) {
            this.round.set(round);
        }

        void reset() {
            counted.clear();
        }
    }

    private static final Parameters.Builder parameters = Parameters.newBuilder()
                                                                   .setMaxMessages(1000)
                                                                   .setFalsePositiveRate(0.00125)
                                                                   .setBufferSize(5000);

    private final List<Router>        communications = new ArrayList<>();
    private List<ReliableBroadcaster> messengers;
    private final AtomicInteger       totalReceived  = new AtomicInteger(0);

    @AfterEach
    public void after() {
        if (messengers != null) {
            messengers.forEach(e -> e.stop());
        }
        communications.forEach(e -> e.close(Duration.ofMillis(1)));
    }

    @Test
    public void broadcast() throws Exception {
        MetricRegistry registry = new MetricRegistry();

        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        List<SigningMember> members = IntStream.range(0, 100).mapToObj(i -> {
            try {
                return stereotomy.newIdentifier().get();
            } catch (InterruptedException | ExecutionException e) {
                throw new IllegalStateException(e);
            }
        }).map(cpk -> new ControlledIdentifierMember(cpk)).map(e -> (SigningMember) e).toList();

        Context<Member> context = Context.newBuilder().setCardinality(members.size()).build();
        RbcMetrics metrics = new RbcMetricsImpl(context.getId(), "test", registry);
        members.forEach(m -> context.activate(m));

        var exec = Executors.newVirtualThreadPerTaskExecutor();
        final var prefix = UUID.randomUUID().toString();
        final var authentication = ReliableBroadcaster.defaultMessageAdapter(context, DigestAlgorithm.DEFAULT);
        messengers = members.stream().map(node -> {
            var comms = new LocalServer(prefix, node, exec).router(
                                                                   ServerConnectionCache.newBuilder()
                                                                                        .setTarget(30)
                                                                                        .setMetrics(new ServerConnectionCacheMetricsImpl(registry)),
                                                                   exec);
            communications.add(comms);
            comms.start();
            return new ReliableBroadcaster(context, node, parameters.build(), exec, comms, metrics, authentication);
        }).collect(Collectors.toList());

        System.out.println("Messaging with " + messengers.size() + " members");
        messengers.forEach(view -> view.start(Duration.ofMillis(10), Executors.newScheduledThreadPool(3)));

        Map<Member, Receiver> receivers = new HashMap<>();
        AtomicInteger current = new AtomicInteger(-1);
        for (ReliableBroadcaster view : messengers) {
            Receiver receiver = new Receiver(view.getMember().getId(), messengers.size(), current);
            view.registerHandler(receiver);
            receivers.put(view.getMember(), receiver);
        }
        int rounds = Boolean.getBoolean("large_tests") ? 100 : 10;
        for (int r = 0; r < rounds; r++) {
            CountDownLatch round = new CountDownLatch(messengers.size());
            for (Receiver receiver : receivers.values()) {
                receiver.setRound(round);
            }
            var rnd = r;
            messengers.stream().forEach(view -> {
                byte[] rand = new byte[32];
                Entropy.nextSecureBytes(rand);
                ByteBuffer buf = ByteBuffer.wrap(new byte[36]);
                buf.putInt(rnd);
                buf.put(rand);
                buf.flip();
                view.publish(ByteMessage.newBuilder().setContents(ByteString.copyFrom(buf)).build(), true);
            });
            boolean success = round.await(60, TimeUnit.SECONDS);
            assertTrue(success, "Did not complete round: " + r + " waiting for: " + round.getCount());

            current.incrementAndGet();
            for (Receiver receiver : receivers.values()) {
                receiver.reset();
            }
        }
        communications.forEach(e -> e.close(Duration.ofMillis(1)));

        System.out.println();

        ConsoleReporter.forRegistry(registry)
                       .convertRatesTo(TimeUnit.SECONDS)
                       .convertDurationsTo(TimeUnit.MILLISECONDS)
                       .build()
                       .report();
    }
}
