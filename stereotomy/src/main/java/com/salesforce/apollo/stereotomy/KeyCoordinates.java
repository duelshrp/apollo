/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.stereotomy;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.salesfoce.apollo.stereotomy.event.proto.KeyCoords;
import com.salesforce.apollo.stereotomy.event.EstablishmentEvent;

/**
 * The coordinates of a key in the KEL
 * 
 * @author hal.hildebrand
 *
 */
public class KeyCoordinates {

    public static KeyCoordinates of(EstablishmentEvent establishmentEvent, int keyIndex) {
        EventCoordinates coordinates = EventCoordinates.of(establishmentEvent);
        return new KeyCoordinates(coordinates, keyIndex);
    }

    private final EventCoordinates establishmentEvent;
    private final int              keyIndex;

    public KeyCoords toKeyCoords() {
        return KeyCoords.newBuilder()
                        .setEstablishment(establishmentEvent.toEventCoords())
                        .setKeyIndex(keyIndex)
                        .build();
    }

    public KeyCoordinates(EventCoordinates establishmentEvent, int keyIndex) {
        if (keyIndex < 0) {
            throw new IllegalArgumentException("keyIndex must be >= 0");
        }

        this.establishmentEvent = requireNonNull(establishmentEvent, "establishmentEvent");
        this.keyIndex = keyIndex;
    }

    public KeyCoordinates(KeyCoords coordinates) {
        establishmentEvent = new EventCoordinates(coordinates.getEstablishment());
        keyIndex = coordinates.getKeyIndex();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof KeyCoordinates)) {
            return false;
        }
        KeyCoordinates other = (KeyCoordinates) obj;
        return Objects.equals(establishmentEvent, other.establishmentEvent) && keyIndex == other.keyIndex;
    }

    public EventCoordinates getEstablishmentEvent() {
        return establishmentEvent;
    }

    public int getKeyIndex() {
        return keyIndex;
    }

    @Override
    public int hashCode() {
        return Objects.hash(establishmentEvent, keyIndex);
    }

    @Override
    public String toString() {
        return this.establishmentEvent + ":" + this.keyIndex;
    }
}
