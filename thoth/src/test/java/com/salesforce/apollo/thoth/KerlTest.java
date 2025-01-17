/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.thoth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.salesforce.apollo.crypto.DigestAlgorithm;
import com.salesforce.apollo.crypto.SigningThreshold;
import com.salesforce.apollo.crypto.SigningThreshold.Unweighted;
import com.salesforce.apollo.stereotomy.ControlledIdentifier;
import com.salesforce.apollo.stereotomy.EventCoordinates;
import com.salesforce.apollo.stereotomy.KERL;
import com.salesforce.apollo.stereotomy.KeyCoordinates;
import com.salesforce.apollo.stereotomy.Stereotomy;
import com.salesforce.apollo.stereotomy.StereotomyImpl;
import com.salesforce.apollo.stereotomy.event.EstablishmentEvent;
import com.salesforce.apollo.stereotomy.event.KeyEvent;
import com.salesforce.apollo.stereotomy.event.Seal.CoordinatesSeal;
import com.salesforce.apollo.stereotomy.event.Seal.DigestSeal;
import com.salesforce.apollo.stereotomy.identifier.Identifier;
import com.salesforce.apollo.stereotomy.identifier.SelfAddressingIdentifier;
import com.salesforce.apollo.stereotomy.identifier.spec.IdentifierSpecification;
import com.salesforce.apollo.stereotomy.identifier.spec.InteractionSpecification;
import com.salesforce.apollo.stereotomy.identifier.spec.KeyConfigurationDigester;
import com.salesforce.apollo.stereotomy.identifier.spec.RotationSpecification;
import com.salesforce.apollo.stereotomy.mem.MemKeyStore;
import com.salesforce.apollo.utils.Hex;

/**
 * @author hal.hildebrand
 *
 */
public class KerlTest extends AbstractDhtTest {
    private SecureRandom secureRandom;

    @Override
    @BeforeEach
    public void before() throws Exception {
        super.before();
        secureRandom = SecureRandom.getInstance("SHA1PRNG");
        secureRandom.setSeed(new byte[] { 0 });
    }

    @Test
    public void delegated() throws Exception {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(getCardinality(),
                                                                              Thread.ofVirtual().factory());
        routers.values().forEach(r -> r.start());
        dhts.values().forEach(dht -> dht.start(scheduler, Duration.ofSeconds(1)));

        KERL kerl = dhts.values().stream().findFirst().get().asKERL();

        var ks = new MemKeyStore();
        Stereotomy controller = new StereotomyImpl(ks, kerl, secureRandom);

        ControlledIdentifier<? extends Identifier> base = controller.newIdentifier().get();

        var opti2 = base.newIdentifier(IdentifierSpecification.newBuilder());
        ControlledIdentifier<? extends Identifier> delegated = opti2.get();

        // identifier
        assertTrue(delegated.getIdentifier() instanceof SelfAddressingIdentifier);
        var sap = (SelfAddressingIdentifier) delegated.getIdentifier();
        assertEquals(DigestAlgorithm.DEFAULT, sap.getDigest().getAlgorithm());
        assertEquals("092126af01f80ca28e7a99bbdce229c029be3bbfcb791e29ccb7a64e8019a36f",
                     Hex.hex(sap.getDigest().getBytes()));

        assertEquals(1, ((Unweighted) delegated.getSigningThreshold()).getThreshold());

        // keys
        assertEquals(1, delegated.getKeys().size());
        assertNotNull(delegated.getKeys().get(0));

        EstablishmentEvent lastEstablishmentEvent = (EstablishmentEvent) kerl.getKeyEvent(delegated.getLastEstablishmentEvent())
                                                                             .get();
        assertEquals(delegated.getKeys().get(0), lastEstablishmentEvent.getKeys().get(0));

        var keyCoordinates = KeyCoordinates.of(lastEstablishmentEvent, 0);
        var keyStoreKeyPair = ks.getKey(keyCoordinates);
        assertTrue(keyStoreKeyPair.isPresent());
        assertEquals(keyStoreKeyPair.get().getPublic(), delegated.getKeys().get(0));

        // nextKeys
        assertTrue(delegated.getNextKeyConfigurationDigest().isPresent());
        var keyStoreNextKeyPair = ks.getNextKey(keyCoordinates);
        assertTrue(keyStoreNextKeyPair.isPresent());
        var expectedNextKeys = KeyConfigurationDigester.digest(SigningThreshold.unweighted(1),
                                                               List.of(keyStoreNextKeyPair.get().getPublic()),
                                                               delegated.getNextKeyConfigurationDigest()
                                                                        .get()
                                                                        .getAlgorithm());
        assertEquals(expectedNextKeys, delegated.getNextKeyConfigurationDigest().get());

        // witnesses
        assertEquals(0, delegated.getWitnessThreshold());
        assertEquals(0, delegated.getWitnesses().size());

        // config
        assertEquals(0, delegated.configurationTraits().size());

        // lastEstablishmentEvent
        assertEquals(delegated.getIdentifier(), lastEstablishmentEvent.getIdentifier());
        assertEquals(ULong.valueOf(0), lastEstablishmentEvent.getSequenceNumber());
        assertEquals(lastEstablishmentEvent.hash(DigestAlgorithm.DEFAULT), delegated.getDigest());

        // lastEvent
        assertNull(kerl.getKeyEvent(delegated.getLastEvent()).get());

        // delegation
        assertTrue(delegated.getDelegatingIdentifier().isPresent());
        assertNotEquals(Identifier.NONE, delegated.getDelegatingIdentifier().get());
        assertTrue(delegated.isDelegated());

        var digest = DigestAlgorithm.BLAKE3_256.digest("digest seal".getBytes());
        var event = EventCoordinates.of(kerl.getKeyEvent(delegated.getLastEstablishmentEvent()).get());
        var seals = List.of(DigestSeal.construct(digest), DigestSeal.construct(digest),
                            CoordinatesSeal.construct(event));

        delegated.rotate().get();
        delegated.seal(InteractionSpecification.newBuilder()).get();
        delegated.rotate(RotationSpecification.newBuilder().addAllSeals(seals)).get();
        delegated.seal(InteractionSpecification.newBuilder().addAllSeals(seals)).get();
    }

    @Test
    public void direct() throws Exception {
        routers.values().forEach(r -> r.start());
        dhts.values()
            .forEach(dht -> dht.start(Executors.newScheduledThreadPool(2, Thread.ofVirtual().factory()),
                                      Duration.ofSeconds(1)));

        KERL kerl = dhts.values().stream().findFirst().get().asKERL();

        Stereotomy controller = new StereotomyImpl(new MemKeyStore(), kerl, secureRandom);

        var i = controller.newIdentifier().get();

        var digest = DigestAlgorithm.BLAKE3_256.digest("digest seal".getBytes());
        var event = EventCoordinates.of(kerl.getKeyEvent(i.getLastEstablishmentEvent()).get());
        var seals = List.of(DigestSeal.construct(digest), DigestSeal.construct(digest),
                            CoordinatesSeal.construct(event));

        i.rotate().get();
        i.seal(InteractionSpecification.newBuilder()).get();
        i.rotate(RotationSpecification.newBuilder().addAllSeals(seals)).get();
        i.seal(InteractionSpecification.newBuilder().addAllSeals(seals)).get();
        i.rotate().get();
        i.rotate().get();
        var iKerl = kerl.kerl(i.getIdentifier()).get();
        assertEquals(7, iKerl.size());
        assertEquals(KeyEvent.INCEPTION_TYPE, iKerl.get(0).event().getIlk());
        assertEquals(KeyEvent.ROTATION_TYPE, iKerl.get(1).event().getIlk());
        assertEquals(KeyEvent.INTERACTION_TYPE, iKerl.get(2).event().getIlk());
        assertEquals(KeyEvent.ROTATION_TYPE, iKerl.get(3).event().getIlk());
        assertEquals(KeyEvent.INTERACTION_TYPE, iKerl.get(4).event().getIlk());
        assertEquals(KeyEvent.ROTATION_TYPE, iKerl.get(5).event().getIlk());
        assertEquals(KeyEvent.ROTATION_TYPE, iKerl.get(6).event().getIlk());
    }
}
