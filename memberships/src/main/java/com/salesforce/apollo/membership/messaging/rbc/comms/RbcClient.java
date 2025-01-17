/*
 * Copyright (c) 2019, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.membership.messaging.rbc.comms;

import java.util.concurrent.ExecutionException;

import com.codahale.metrics.Timer.Context;
import com.google.common.util.concurrent.ListenableFuture;
import com.salesfoce.apollo.messaging.proto.MessageBff;
import com.salesfoce.apollo.messaging.proto.RBCGrpc;
import com.salesfoce.apollo.messaging.proto.RBCGrpc.RBCFutureStub;
import com.salesfoce.apollo.messaging.proto.Reconcile;
import com.salesfoce.apollo.messaging.proto.ReconcileContext;
import com.salesforce.apollo.archipelago.ManagedServerChannel;
import com.salesforce.apollo.archipelago.ServerConnectionCache.CreateClientCommunications;
import com.salesforce.apollo.membership.Member;
import com.salesforce.apollo.membership.messaging.rbc.RbcMetrics;

/**
 * @author hal.hildebrand
 * @since 220
 */
public class RbcClient implements ReliableBroadcast {

    public static CreateClientCommunications<ReliableBroadcast> getCreate(RbcMetrics metrics) {
        return (c) -> {
            return new RbcClient(c, metrics);
        };

    }

    private final ManagedServerChannel channel;
    private final RBCFutureStub            client;
    private final RbcMetrics               metrics;

    public RbcClient(ManagedServerChannel c, RbcMetrics metrics) {
        this.channel = c;
        this.client = RBCGrpc.newFutureStub(c).withCompression("gzip");
        this.metrics = metrics;
    }

    @Override
    public void close() {
        channel.release();
    }

    @Override
    public Member getMember() {
        return channel.getMember();
    }

    @Override
    public ListenableFuture<Reconcile> gossip(MessageBff request) {
        Context timer = metrics == null ? null : metrics.outboundGossipTimer().time();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.outboundBandwidth().mark(serializedSize);
            metrics.outboundGossip().update(serializedSize);
        }
        var result = client.gossip(request);
        if (metrics != null) {
            result.addListener(() -> {
                Reconcile reconcile;
                try {
                    reconcile = result.get();
                    timer.stop();
                    var serializedSize = reconcile.getSerializedSize();
                    metrics.inboundBandwidth().mark(serializedSize);
                    metrics.gossipResponse().update(serializedSize);
                } catch (InterruptedException | ExecutionException e) {
                    if (timer != null) {
                        timer.close();
                    }
                }
            }, r -> r.run());
        }
        return result;
    }

    public void start() {

    }

    @Override
    public String toString() {
        return String.format("->[%s]", channel.getMember());
    }

    @Override
    public void update(ReconcileContext request) {
        Context timer = metrics == null ? null : metrics.outboundUpdateTimer().time();
        if (metrics != null) {
            var serializedSize = request.getSerializedSize();
            metrics.outboundBandwidth().mark(serializedSize);
            metrics.outboundUpdate().update(serializedSize);
        }
        try {
            var result = client.update(request);
            if (metrics != null) {
                result.addListener(() -> {
                    if (timer != null) {
                        timer.stop();
                    }
                }, r -> r.run());
            }
        } catch (Throwable e) {
            if (timer != null) {
                timer.close();
            }
        }
    }
}
