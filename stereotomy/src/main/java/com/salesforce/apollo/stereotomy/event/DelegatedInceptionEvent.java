/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.stereotomy.event;

import com.salesforce.apollo.stereotomy.identifier.Identifier;

/**
 * @author hal.hildebrand
 *
 */
public interface DelegatedInceptionEvent extends InceptionEvent, DelegatedEstablishmentEvent {

    @Override
    default String getIlk() {
        return DELEGATED_INCEPTION_TYPE;
    }

    public Identifier getDelegatingPrefix();
}
