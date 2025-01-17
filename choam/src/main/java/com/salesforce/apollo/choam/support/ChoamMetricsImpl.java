/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.choam.support;

import static com.codahale.metrics.MetricRegistry.name;

import java.util.concurrent.TimeoutException;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.salesforce.apollo.crypto.Digest;
import com.salesforce.apollo.ethereal.memberships.comm.EtherealMetrics;
import com.salesforce.apollo.ethereal.memberships.comm.EtherealMetricsImpl;
import com.salesforce.apollo.membership.messaging.rbc.RbcMetrics;
import com.salesforce.apollo.membership.messaging.rbc.RbcMetricsImpl;
import com.salesforce.apollo.protocols.EndpointMetricsImpl;
import com.salesforce.apollo.protocols.LimitsRegistry;

/**
 * @author hal.hildebrand
 *
 */
public class ChoamMetricsImpl extends EndpointMetricsImpl implements ChoamMetrics {

    private final RbcMetrics      combineMetrics;
    private final Meter           completedTransactions;
    private final Counter         droppedReassemblies;
    private final Counter         droppedTransactions;
    private final Counter         droppedValidations;
    private final Meter           failedTransactions;
    private final EtherealMetrics genesisMetrics;
    private final EtherealMetrics producerMetrics;
    private final Histogram       publishedBytes;
    private final Meter           publishedReassemblies;
    private final Meter           publishedTransactions;
    private final Meter           publishedValidations;
    private final MetricRegistry  registry;
    private final Timer           transactionLatency;
    private final Meter           transactionSubmitFailed;
    private final Meter           transactionSubmitRetry;
    private final Meter           transactionSubmitSuccess;
    private final Meter           transactionSubmittedBufferFull;
    private final Meter           transactionTimeout;

    public ChoamMetricsImpl(Digest context, MetricRegistry registry) {
        super(registry);
        this.registry = registry;
        combineMetrics = new RbcMetricsImpl(context, "combine", registry);
        producerMetrics = new EtherealMetricsImpl(context, "producer", registry);
        genesisMetrics = new EtherealMetricsImpl(context, "genesis", registry);

        droppedTransactions = registry.counter(name(context.shortString(), "transactions.dropped"));
        droppedReassemblies = registry.counter(name(context.shortString(), "reassemblies.dropped"));
        droppedValidations = registry.counter(name(context.shortString(), "validations.dropped"));
        publishedTransactions = registry.meter(name(context.shortString(), "transactions.published"));
        publishedBytes = registry.histogram(name(context.shortString(), "unit.bytes"));
        publishedReassemblies = registry.meter(name(context.shortString(), "reassemblies.published"));
        publishedValidations = registry.meter(name(context.shortString(), "validations.published"));
        transactionLatency = registry.timer(name(context.shortString(), "transaction.latency"));
        transactionSubmitRetry = registry.meter(name(context.shortString(), "transaction.submit.retry"));
        transactionSubmitFailed = registry.meter(name(context.shortString(), "transaction.submit.failed"));
        transactionSubmitSuccess = registry.meter(name(context.shortString(), "transaction.submit.success"));
        transactionTimeout = registry.meter(name(context.shortString(), "transaction.timeout"));
        completedTransactions = registry.meter(name(context.shortString(), "transactions.completed"));
        failedTransactions = registry.meter(name(context.shortString(), "transactions.failed"));
        transactionSubmittedBufferFull = registry.meter(name(context.shortString(), "transaction.submit.buffer.full"));
    }

    @Override
    public void dropped(int transactions, int validations, int reassemblies) {
        droppedTransactions.inc(transactions);
        droppedValidations.inc(validations);
        droppedReassemblies.inc(reassemblies);
    }

    @Override
    public RbcMetrics getCombineMetrics() {
        return combineMetrics;
    }

    @Override
    public EtherealMetrics getGensisMetrics() {
        return genesisMetrics;
    }

    @Override
    public com.netflix.concurrency.limits.MetricRegistry getMetricRegistry(String prefix) {
        return new LimitsRegistry(prefix, registry);
    }

    @Override
    public EtherealMetrics getProducerMetrics() {
        return producerMetrics;
    }

    @Override
    public void publishedBatch(int transactions, int byteSize, int validations, int reassemblies) {
        publishedTransactions.mark(transactions);
        publishedBytes.update(byteSize);
        publishedValidations.mark(validations);
        publishedReassemblies.mark(reassemblies);
    }

    @Override
    public void transactionComplete(Throwable t) {
        if (t != null) {
            if (t instanceof TimeoutException) {
                transactionTimeout.mark();

            } else if (t instanceof TransactionCancelled) {
                // ignore
            } else {
                failedTransactions.mark();
            }
        } else {
            completedTransactions.mark();
        }
    }

    @Override
    public Timer transactionLatency() {
        return transactionLatency;
    }

    @Override
    public void transactionSubmitRetry() {
        transactionSubmitRetry.mark();
    }

    @Override
    public void transactionSubmittedBufferFull() {
        transactionSubmittedBufferFull.mark();
    }

    @Override
    public void transactionSubmittedFail() {
        transactionSubmitFailed.mark();
    }

    @Override
    public void transactionSubmittedSuccess() {
        transactionSubmitSuccess.mark();
    }

    @Override
    public void transactionTimeout() {
        transactionTimeout.mark();
    }
}
