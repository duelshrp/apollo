/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.stereotomy.services.grpc.observer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Timer.Context;
import com.google.protobuf.Empty;
import com.salesfoce.apollo.stereotomy.services.grpc.proto.AttachmentEvents;
import com.salesfoce.apollo.stereotomy.services.grpc.proto.AttachmentsContext;
import com.salesfoce.apollo.stereotomy.services.grpc.proto.EventObserverGrpc.EventObserverImplBase;
import com.salesfoce.apollo.stereotomy.services.grpc.proto.KERLContext;
import com.salesfoce.apollo.stereotomy.services.grpc.proto.KeyEventsContext;
import com.salesforce.apollo.comm.RoutableService;
import com.salesforce.apollo.crypto.Digest;
import com.salesforce.apollo.stereotomy.services.grpc.StereotomyMetrics;
import com.salesforce.apollo.stereotomy.services.proto.ProtoEventObserver;
import com.salesforce.apollo.utils.Utils;

import io.grpc.stub.StreamObserver;

/**
 * @author hal.hildebrand
 *
 */
public class EventObserverServer extends EventObserverImplBase {
    private final static Logger log = LoggerFactory.getLogger(EventObserverServer.class);

    private final StereotomyMetrics                   metrics;
    private final RoutableService<ProtoEventObserver> routing;
    private final Executor                            exec;

    public EventObserverServer(RoutableService<ProtoEventObserver> router, Executor exec, StereotomyMetrics metrics) {
        this.metrics = metrics;
        this.routing = router;
        this.exec = exec;
    }

    @Override
    public void publish(KERLContext request, StreamObserver<AttachmentEvents> responseObserver) {
        Context timer = metrics != null ? metrics.publishKERLService().time() : null;
        if (metrics != null) {
            metrics.inboundBandwidth().mark(request.getSerializedSize());
            metrics.inboundPublishKERLRequest().mark(request.getSerializedSize());
        }
        exec.execute(Utils.wrapped(() -> routing.evaluate(responseObserver, Digest.from(request.getContext()), s -> {
            var result = s.publish(request.getKerl());
            result.whenComplete((e, t) -> {
                if (timer != null) {
                    timer.stop();
                }
                if (t != null) {
                    responseObserver.onError(t);
                } else {
                    var response = AttachmentEvents.newBuilder().addAllAttachments(e).build();
                    if (timer != null) {
                        metrics.outboundBandwidth().mark(response.getSerializedSize());
                        metrics.outboundPublishKERLResponse().mark(response.getSerializedSize());
                    }
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                }
            });
        }), log));
    }

    @Override
    public void publishAttachments(AttachmentsContext request, StreamObserver<Empty> responseObserver) {
        Context timer = metrics != null ? metrics.publishAttachmentsService().time() : null;
        if (metrics != null) {
            metrics.inboundBandwidth().mark(request.getSerializedSize());
            metrics.inboundPublishAttachmentsRequest().mark(request.getSerializedSize());
        }
        exec.execute(Utils.wrapped(() -> routing.evaluate(responseObserver, Digest.from(request.getContext()), s -> {
            CompletableFuture<Void> result = s.publishAttachments(request.getAttachmentsList());
            result.whenComplete((ks, t) -> {
                if (timer != null) {
                    timer.stop();
                }
                if (t != null) {
                    responseObserver.onError(t);
                } else {
                    responseObserver.onNext(Empty.getDefaultInstance());
                    responseObserver.onCompleted();
                }
            });
        }), log));
    }

    @Override
    public void publishEvents(KeyEventsContext request, StreamObserver<AttachmentEvents> responseObserver) {
        Context timer = metrics != null ? metrics.publishEventsService().time() : null;
        if (metrics != null) {
            metrics.inboundBandwidth().mark(request.getSerializedSize());
            metrics.inboundPublishEventsRequest().mark(request.getSerializedSize());
        }
        exec.execute(Utils.wrapped(() -> routing.evaluate(responseObserver, Digest.from(request.getContext()), s -> {
            var result = s.publishEvents(request.getKeyEventList());
            result.whenComplete((e, t) -> {
                if (timer != null) {
                    timer.stop();
                }
                if (t != null) {
                    responseObserver.onError(t);
                } else {
                    var response = AttachmentEvents.newBuilder().addAllAttachments(e).build();
                    if (timer != null) {
                        metrics.outboundBandwidth().mark(response.getSerializedSize());
                        metrics.outboundPublishEventsResponse().mark(response.getSerializedSize());
                    }
                    responseObserver.onNext(response);
                    responseObserver.onCompleted();
                }
            });
        }), log));
    }
}
