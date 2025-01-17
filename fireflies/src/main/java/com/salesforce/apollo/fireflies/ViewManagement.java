/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.fireflies;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Timer;
import com.google.common.base.Objects;
import com.salesfoce.apollo.fireflies.proto.Gateway;
import com.salesfoce.apollo.fireflies.proto.Join;
import com.salesfoce.apollo.fireflies.proto.JoinGossip;
import com.salesfoce.apollo.fireflies.proto.Redirect;
import com.salesfoce.apollo.fireflies.proto.Registration;
import com.salesfoce.apollo.fireflies.proto.SignedNote;
import com.salesfoce.apollo.fireflies.proto.SignedViewChange;
import com.salesfoce.apollo.fireflies.proto.Update.Builder;
import com.salesfoce.apollo.fireflies.proto.ViewChange;
import com.salesforce.apollo.crypto.Digest;
import com.salesforce.apollo.crypto.DigestAlgorithm;
import com.salesforce.apollo.crypto.HexBloom;
import com.salesforce.apollo.fireflies.Binding.Bound;
import com.salesforce.apollo.fireflies.View.Node;
import com.salesforce.apollo.fireflies.View.Participant;
import com.salesforce.apollo.membership.Context;
import com.salesforce.apollo.membership.ReservoirSampler;
import com.salesforce.apollo.utils.Entropy;
import com.salesforce.apollo.utils.bloomFilters.BloomFilter;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

/**
 * 
 * Management of the view state logic
 *
 * @author hal.hildebrand
 *
 */
public class ViewManagement {
    record Ballot(Digest view, List<Digest> leaving, List<Digest> joining, int hash) {

        Ballot(Digest view, List<Digest> leaving, List<Digest> joining, DigestAlgorithm algo) {
            this(view, leaving, joining,
                 view.xor(joining.stream().reduce((a, b) -> a.xor(b)).orElse(algo.getOrigin()))
                     .xor(leaving.stream()
                                 .reduce((a, b) -> a.xor(b))
                                 .orElse(algo.getOrigin())
                                 .xor(joining.stream().reduce((a, b) -> a.xor(b)).orElse(algo.getOrigin())))
                     .hashCode());
        }

        @Override
        public boolean equals(Object obj) {
            if (obj != null && obj instanceof Ballot b) {
                return Objects.equal(view, b.view) && Objects.equal(leaving, b.leaving) &&
                       Objects.equal(joining, b.joining);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public String toString() {
            return String.format("{h: %s, j: %s, l: %s}", hash, joining.size(), leaving.size());
        }
    }

    static final double MEMBERSHIP_FPR = 0.0000125;

    private static final Logger log = LoggerFactory.getLogger(ViewManagement.class);

    private final AtomicInteger                            attempt      = new AtomicInteger();
    private boolean                                        bootstrap;
    private final Digest                                   bootstrapView;
    private final Context<Participant>                     context;
    private AtomicReference<Digest>                        currentView  = new AtomicReference<>();
    private AtomicReference<HexBloom>                      diadem       = new AtomicReference<>();
    private final DigestAlgorithm                          digestAlgo;
    private final ConcurrentMap<Digest, NoteWrapper>       joins        = new ConcurrentSkipListMap<>();
    private final FireflyMetrics                           metrics;
    private final Node                                     node;
    private CompletableFuture<Void>                        onJoined;
    private final Parameters                               params;
    private final Map<Digest, Consumer<List<NoteWrapper>>> pendingJoins = new ConcurrentSkipListMap<>();
    private final View                                     view;
    private final AtomicReference<ViewChange>              vote         = new AtomicReference<>();

    ViewManagement(View view, Context<Participant> context, Parameters params, FireflyMetrics metrics, Node node,
                   DigestAlgorithm digestAlgo) {
        this.node = node;
        this.view = view;
        this.context = context;
        this.params = params;
        this.metrics = metrics;
        this.digestAlgo = digestAlgo;
        resetBootstrapView();
        bootstrapView = currentView.get();
    }

    public boolean contains(Digest member) {
        return diadem.get().contains(member);
    }

    boolean addJoin(Digest id, NoteWrapper note) {
        return joins.put(id, note) == null;
    }

    void bootstrap(NoteWrapper nw, final ScheduledExecutorService sched, final Duration dur) {
        joins.put(nw.getId(), nw);
        context.activate(node);

        resetBootstrapView();
        view.viewChange(() -> install(new Ballot(currentView(), Collections.emptyList(),
                                                 Collections.singletonList(node.getId()), digestAlgo)));

        view.scheduleViewChange();
        view.schedule(dur, sched);

        log.info("Bootstrapped view: {} cardinality: {} count: {} context: {} on: {}", currentView(),
                 context.cardinality(), context.activeCount(), context.getId(), node.getId());
        onJoined.complete(null);
        view.introduced();
        if (metrics != null) {
            metrics.viewChanges().mark();
        }
    }

    Digest bootstrapView() {
        return bootstrapView;
    }

    void clear() {
        joins.clear();
        resetBootstrapView();
    }

    void clearVote() {
        vote.set(null);
    }

    Digest currentView() {
        return currentView.get();
    }

    /**
     * @param seed
     * @param p
     * @return the bloom filter containing the digests of known joins
     */
    BloomFilter<Digest> getJoinsBff(long seed, double p) {
        BloomFilter<Digest> bff = new BloomFilter.DigestBloomFilter(seed, Math.max(params.minimumBiffCardinality(),
                                                                                   joins.size() * 2),
                                                                    p);
        joins.keySet().forEach(d -> bff.add(d));
        return bff;
    }

    /**
     * Install the new view
     * 
     * @param view
     */
    void install(Ballot ballot) {
        // The circle of life
        var previousView = currentView.get();

        log.debug("View change: {}, pending: {} joining: {} leaving: {} local joins: {} leaving: {} on: {}",
                  previousView, pendingJoins.size(), ballot.joining.size(), ballot.leaving.size(), joins.size(),
                  context.offlineCount(), node.getId());
        attempt.set(0);

        ballot.leaving.stream().filter(d -> !node.getId().equals(d)).forEach(p -> view.remove(p));

        context.rebalance(context.totalCount() + ballot.joining.size());

        // Gather joining notes and members joining on this node
        var joiningNotes = new ArrayList<NoteWrapper>();
        var pending = ballot.joining()
                            .stream()
                            .map(d -> joins.remove(d))
                            .filter(sn -> sn != null)
                            .peek(sn -> joiningNotes.add(sn))
                            .peek(nw -> view.addToView(nw))
                            .peek(nw -> {
                                if (metrics != null) {
                                    metrics.joins().mark();
                                }
                            })
                            .map(nw -> pendingJoins.remove(nw.getId()))
                            .filter(p -> p != null)
                            .toList();

        setDiadem(HexBloom.construct(context.memberCount(), context.allMembers().map(p -> p.getId()),
                                     view.bootstrapView(), params.crowns()));
        view.reset();

        // complete all pending joins
        pending.forEach(r -> {
            try {
                final var shuffled = new ArrayList<>(joiningNotes);
                Entropy.secureShuffle(shuffled);
                r.accept(shuffled);
            } catch (Throwable t) {
                log.error("Exception in pending join on: {}", node.getId(), t);
            }
        });
        if (metrics != null) {
            metrics.viewChanges().mark();
        }

        log.info("Installed view: {} from: {} crown: {} for context: {} cardinality: {} count: {} pending: {} leaving: {} joining: {} on: {}",
                 currentView.get(), previousView, diadem.get(), context.getId(), context.cardinality(),
                 context.allMembers().count(), pending.size(), ballot.leaving.size(), ballot.joining.size(),
                 node.getId());

        view.notifyListeners(ballot.joining, ballot.leaving);
    }

    boolean isJoined() {
        return joined();
    }

    /**
     * Formally join the view. Calculate the HEX-BLOOM crown and view, fail and stop
     * if does not match currentView
     */
    synchronized void join() {
        assert context.totalCount() == context.cardinality();
        if (joined()) {
            return;
        }
        var current = currentView();
        var calculated = HexBloom.construct(context.totalCount(), context.allMembers().map(p -> p.getId()),
                                            view.bootstrapView(), params.crowns());

        if (!current.equals(calculated.compactWrapped())) {
            log.error("Crown: {} does not produce view: {} cardinality: {} count: {} on: {}",
                      calculated.compactWrapped(), currentView(), context.cardinality(), context.totalCount(),
                      node.getId());
            view.stop();
            throw new IllegalStateException("Invalid crown");
        }
        setDiadem(calculated);
        view.notifyListeners(context.allMembers().map(p -> p.getId()).toList(), Collections.emptyList());
        onJoined.complete(null);

        view.scheduleViewChange();

        if (metrics != null) {
            metrics.viewChanges().mark();
        }
        log.info("Joined view: {} cardinality: {} count: {} on: {}", current, context.cardinality(),
                 context.totalCount(), node.getId());
    }

    void join(Join join, Digest from, StreamObserver<Gateway> responseObserver, Timer.Context timer) {
        final var joinView = Digest.from(join.getView());
        if (!joined()) {
            log.trace("Not joined, ignored join of view: {} from: {} on: {}", joinView, from, node.getId());
            responseObserver.onNext(Gateway.getDefaultInstance());
            responseObserver.onCompleted();
            return;
        }
        view.stable(() -> {
            var thisView = currentView();
            var note = new NoteWrapper(join.getNote(), digestAlgo);
            if (!from.equals(note.getId())) {
                responseObserver.onError(new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Member not match note")));
                return;
            }
            if (contains(from)) {
                log.trace("Already a member: {} view: {}  context: {} cardinality: {} on: {}", from, thisView,
                          context.getId(), context.cardinality(), node.getId());
                joined(Collections.emptyList(), from, responseObserver, timer);
                return;
            }
            if (!thisView.equals(joinView)) {
                responseObserver.onError(new StatusRuntimeException(Status.OUT_OF_RANGE.withDescription("View: "
                + joinView + " does not match: " + thisView)));
                return;
            }
            if (!View.isValidMask(note.getMask(), context)) {
                log.warn("Invalid join mask: {} majority: {} from member: {} view: {}  context: {} cardinality: {} on: {}",
                         note.getMask(), context.majority(), from, thisView, context.getId(), context.cardinality(),
                         node.getId());
            }
            if (pendingJoins.size() >= params.maxPending()) {
                responseObserver.onError(new StatusRuntimeException(Status.RESOURCE_EXHAUSTED.withDescription("No room at the inn")));
                return;
            }
            pendingJoins.put(from, joining -> {
                log.info("Gateway established for: {} view: {}  context: {} cardinality: {} on: {}", from,
                         currentView(), context.getId(), context.cardinality(), node.getId());
                joined(joining.stream().map(nw -> nw.getWrapped()).limit(params.maximumTxfr()).toList(), from,
                       responseObserver, timer);
            });
            joins.put(note.getId(), note);
            log.debug("Member pending join: {} view: {} context: {} on: {}", from, currentView(), context.getId(),
                      node.getId());
        });
    }

    BiConsumer<? super Bound, ? super Throwable> join(ScheduledExecutorService scheduler, Duration duration,
                                                      Timer.Context timer) {
        return (bound, t) -> {
            view.viewChange(() -> {
                final var hex = bound.view();
                if (t != null) {
                    log.error("Failed to join view on: {}", node.getId(), t);
                    view.stop();
                    return;
                }

                log.info("Rebalancing to cardinality: {} (join) for: {} context: {} on: {}", hex.getCardinality(),
                         hex.compact(), context.getId(), node.getId());
                context.rebalance(hex.getCardinality());
                context.activate(node);
                diadem.set(hex);
                currentView.set(hex.compact());

                bound.successors().forEach(nw -> view.addToView(nw));

                view.reset();

                context.allMembers().forEach(p -> p.clearAccusations());

                view.introduced();

                view.schedule(duration, scheduler);

                if (timer != null) {
                    timer.stop();
                }
                view.introduced();

                log.info("Currently joining view: {} seeds: {} cardinality: {} count: {} on: {}", currentView.get(),
                         bound.successors().size(), context.cardinality(), context.totalCount(), node.getId());
                if (context.totalCount() == context.cardinality()) {
                    join();
                }
            });
        };
    }

    boolean joined() {
        return onJoined.isDone();
    }

    void joinUpdatesFor(BloomFilter<Digest> joinBff, Builder builder) {
        joins.entrySet()
             .stream()
             .filter(e -> !joinBff.contains(e.getKey()))
             .collect(new ReservoirSampler<>(params.maximumTxfr(), Entropy.bitsStream()))
             .forEach(e -> builder.addJoins(e.getValue().getWrapped()));
    }

    /**
     * start a view change if there's any offline members or joining members
     */
    void maybeViewChange() {
        if (context.offlineCount() > 0 || joins.size() > 0) {
            initiateViewChange();
        } else {
            view.scheduleViewChange();
        }
    }

    JoinGossip.Builder processJoins(BloomFilter<Digest> bff) {
        JoinGossip.Builder builder = JoinGossip.newBuilder();

        // Add all updates that this view has that aren't reflected in the inbound bff
        joins.entrySet()
             .stream()
             .filter(m -> !bff.contains(m.getKey()))
             .map(m -> m.getValue())
             .collect(new ReservoirSampler<>(params.maximumTxfr(), Entropy.bitsStream()))
             .forEach(n -> builder.addUpdates(n.getWrapped()));
        return builder;
    }

    /**
     * Process the inbound joins from the gossip. Reconcile the differences between
     * the view's state and the digests of the gossip. Update the reply with the
     * list of digests the view requires, as well as proposed updates based on the
     * inbound digests that the view has more recent information
     *
     * @param p
     * @param from
     * @param digests
     */
    JoinGossip processJoins(BloomFilter<Digest> bff, double p) {
        JoinGossip.Builder builder = processJoins(bff);
        builder.setBff(getJoinsBff(Entropy.nextSecureLong(), p).toBff());
        JoinGossip gossip = builder.build();
        if (builder.getUpdatesCount() != 0) {
            log.trace("process joins produced updates: {} on: {}", builder.getUpdatesCount(), node.getId());
        }
        return gossip;
    }

    HexBloom resetBootstrapView() {
        final var hex = new HexBloom(view.bootstrapView(), params.crowns());
        setDiadem(hex);
        return hex;
    }

    Redirect seed(Registration registration, Digest from) {
        final var requestView = Digest.from(registration.getView());

        if (!joined()) {
            log.warn("Not joined, ignored seed view: {} from: {} on: {}", requestView, from, node.getId());
            return Redirect.getDefaultInstance();
        }
        if (!bootstrapView.equals(requestView)) {
            log.warn("Invalid bootstrap view: {} expected: {} from: {} on: {}", requestView, from, node.getId());
            return Redirect.getDefaultInstance();
        }
        var note = new NoteWrapper(registration.getNote(), digestAlgo);
        if (!from.equals(note.getId())) {
            log.warn("Invalid bootstrap note: {} from: {} claiming: {} on: {}", requestView, from, note.getId(),
                     node.getId());
            return Redirect.getDefaultInstance();
        }
        return view.stable(() -> {
            var newMember = view.new Participant(
                                                 note.getId());
            final var successors = new TreeSet<Participant>(context.successors(newMember, m -> context.isActive(m)));

            log.debug("Member seeding: {} view: {} context: {} successors: {} on: {}", newMember.getId(), currentView(),
                      context.getId(), successors.size(), node.getId());
            return Redirect.newBuilder()
                           .setView(currentView().toDigeste())
                           .addAllSuccessors(successors.stream().filter(p -> p != null).map(p -> p.getSeed()).toList())
                           .setCardinality(context.cardinality())
                           .setBootstrap(bootstrap)
                           .setRings(context.getRingCount())
                           .build();
        });
    }

    void start(CompletableFuture<Void> onJoin, boolean bootstrap) {
        this.onJoined = onJoin;
        this.bootstrap = bootstrap;
    }

    /**
     * Initiate the view change
     */
    private void initiateViewChange() {
        view.stable(() -> {
            if (vote.get() != null) {
                log.trace("Vote already cast for: {} on: {}", currentView(), node.getId());
                return;
            }
            // Use pending rebuttals as a proxy for stability
            if (!view.hasPendingRebuttals()) {
                log.debug("Pending rebuttals in view: {} on: {}", currentView(), node.getId());
                view.scheduleViewChange(1); // 1 TTL round to check again
                return;
            }
            view.scheduleFinalizeViewChange();
            final var builder = ViewChange.newBuilder()
                                          .setObserver(node.getId().toDigeste())
                                          .setCurrent(currentView().toDigeste())
                                          .setAttempt(attempt.getAndIncrement())
                                          .addAllLeaves(view.streamShunned()
                                                            .map(id -> id.toDigeste())
                                                            .collect(Collectors.toSet()))
                                          .addAllJoins(joins.keySet().stream().map(id -> id.toDigeste()).toList());
            ViewChange change = builder.build();
            vote.set(change);
            var signature = node.sign(change.toByteString());
            final var viewChange = SignedViewChange.newBuilder()
                                                   .setChange(change)
                                                   .setSignature(signature.toSig())
                                                   .build();
            view.initiate(viewChange);
            log.info("View change vote: {} joins: {} leaves: {} on: {}", currentView(), change.getJoinsCount(),
                     change.getLeavesCount(), node.getId());
        });
    }

    private void joined(List<SignedNote> joined, Digest from, StreamObserver<Gateway> responseObserver,
                        Timer.Context timer) {
        final var seedSet = new HashSet<SignedNote>();
        context.successors(from, m -> context.isActive(m)).forEach(p -> seedSet.add(p.getNote().getWrapped()));
        joined.stream()
              .collect(new ReservoirSampler<>(params.maximumTxfr(), Entropy.bitsStream()))
              .forEach(sn -> seedSet.add(sn));
        var gateway = Gateway.newBuilder().addAllInitialSeedSet(seedSet).setDiadem(diadem.get().toHexBloome()).build();
        responseObserver.onNext(gateway);
        responseObserver.onCompleted();
        if (timer != null) {
            var serializedSize = gateway.getSerializedSize();
            metrics.outboundBandwidth().mark(serializedSize);
            metrics.outboundGateway().update(serializedSize);
            timer.stop();
        }
    }

    private void setDiadem(final HexBloom hex) {
        diadem.set(hex);
        currentView.set(diadem.get().compactWrapped());
    }
}
