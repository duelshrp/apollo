/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.choam.support;

import com.codahale.metrics.Timer;
import com.netflix.concurrency.limits.MetricRegistry;
import com.salesforce.apollo.ethereal.memberships.comm.EtherealMetrics;
import com.salesforce.apollo.membership.messaging.rbc.RbcMetrics;
import com.salesforce.apollo.protocols.EndpointMetrics;

/**
 * @author hal.hildebrand
 *
 */
public interface ChoamMetrics extends EndpointMetrics {

    void dropped(int transactions, int validations, int reassemblies);

    RbcMetrics getCombineMetrics();

    EtherealMetrics getGensisMetrics();

    MetricRegistry getMetricRegistry(String prefix);

    EtherealMetrics getProducerMetrics();

    void publishedBatch(int batchSize, int byteSize, int validations, int reassemblies);

    void transactionComplete(Throwable t);

    Timer transactionLatency();

    void transactionSubmitRetry();

    void transactionSubmittedBufferFull();

    void transactionSubmittedFail();

    void transactionSubmittedSuccess();

    void transactionTimeout();

}
