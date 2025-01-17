/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.model.delphinius;

import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import com.salesforce.apollo.choam.support.InvalidTransaction;
import com.salesforce.apollo.delphinius.AbstractOracle;
import com.salesforce.apollo.state.Mutator;

/**
 * Oracle where write ops are JDBC stored procedure calls
 * 
 * @author hal.hildebrand
 *
 */
public class ShardedOracle extends AbstractOracle {

    private final Executor                 exec;
    private final Mutator                  mutator;
    private final ScheduledExecutorService scheduler;
    private final Duration                 timeout;

    public ShardedOracle(Connection connection, Mutator mutator, ScheduledExecutorService scheduler, Duration timeout,
                         Executor exec) {
        super(connection);
        this.mutator = mutator;
        this.exec = exec;
        this.scheduler = scheduler;
        this.timeout = timeout;
    }

    @Override
    public CompletableFuture<Void> add(Assertion assertion) {
        var call = mutator.call("call delphinius.addAssertion(?, ?, ?, ?, ?, ?, ?, ?) ",
                                assertion.subject().namespace().name(), assertion.subject().name(),
                                assertion.subject().relation().namespace().name(),
                                assertion.subject().relation().name(), assertion.object().namespace().name(),
                                assertion.object().name(), assertion.object().relation().namespace().name(),
                                assertion.object().relation().name());
        try {
            return mutator.execute(exec, call, timeout, scheduler).thenApply(r -> null);
        } catch (InvalidTransaction e) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public CompletableFuture<Void> add(Namespace namespace) {
        var call = mutator.call("call delphinius.addNamespace(?) ", namespace.name());
        try {
            return mutator.execute(exec, call, timeout, scheduler).thenApply(r -> null);
        } catch (InvalidTransaction e) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public CompletableFuture<Void> add(Object object) {
        var call = mutator.call("call delphinius.addObject(?, ?, ?, ?) ", object.namespace().name(), object.name(),
                                object.relation().namespace().name(), object.relation().name());
        try {
            return mutator.execute(exec, call, timeout, scheduler).thenApply(r -> null);
        } catch (InvalidTransaction e) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public CompletableFuture<Void> add(Relation relation) {
        var call = mutator.call("call delphinius.addRelation(?, ?) ", relation.namespace().name(), relation.name());
        try {
            return mutator.execute(exec, call, timeout, scheduler).thenApply(r -> null);
        } catch (InvalidTransaction e) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public CompletableFuture<Void> add(Subject subject) {
        var call = mutator.call("call delphinius.addSubject(?, ?, ?, ?) ", subject.namespace().name(), subject.name(),
                                subject.relation().namespace().name(), subject.relation().name());
        try {
            return mutator.execute(exec, call, timeout, scheduler).thenApply(r -> null);
        } catch (InvalidTransaction e) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public CompletableFuture<Void> delete(Assertion assertion) {
        var call = mutator.call("call delphinius.deleteAssertion(?, ?, ?, ?, ?, ?, ?, ?) ",
                                assertion.subject().namespace().name(), assertion.subject().name(),
                                assertion.subject().relation().namespace().name(),
                                assertion.subject().relation().name(), assertion.object().namespace().name(),
                                assertion.object().name(), assertion.object().relation().namespace().name(),
                                assertion.object().relation().name());
        try {
            return mutator.execute(exec, call, timeout, scheduler).thenApply(r -> null);
        } catch (InvalidTransaction e) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public CompletableFuture<Void> delete(Namespace namespace) {
        var call = mutator.call("call delphinius.deleteNamespace(?) ", namespace.name());
        try {
            return mutator.execute(exec, call, timeout, scheduler).thenApply(r -> null);
        } catch (InvalidTransaction e) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public CompletableFuture<Void> delete(Object object) {
        var call = mutator.call("call delphinius.deleteObject(?, ?, ?, ?) ", object.namespace().name(), object.name(),
                                object.relation().namespace().name(), object.relation().name());
        try {
            return mutator.execute(exec, call, timeout, scheduler).thenApply(r -> null);
        } catch (InvalidTransaction e) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public CompletableFuture<Void> delete(Relation relation) {
        var call = mutator.call("call delphinius.deleteRelation(?, ?) ", relation.namespace().name(), relation.name());
        try {
            return mutator.execute(exec, call, timeout, scheduler).thenApply(r -> null);
        } catch (InvalidTransaction e) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public CompletableFuture<Void> delete(Subject subject) {
        var call = mutator.call("call delphinius.deleteSubject(?, ?, ?, ?) ", subject.namespace().name(),
                                subject.name(), subject.relation().namespace().name(), subject.relation().name());
        try {
            return mutator.execute(exec, call, timeout, scheduler).thenApply(r -> null);
        } catch (InvalidTransaction e) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public CompletableFuture<Void> map(Object parent, Object child) {
        var call = mutator.call("call delphinius.mapObject(?, ?, ?, ?, ?, ?, ?, ?) ", parent.namespace().name(),
                                parent.name(), parent.relation().namespace().name(), parent.relation().name(),
                                child.namespace().name(), child.name(), child.relation().namespace().name(),
                                child.relation().name());
        try {
            return mutator.execute(exec, call, timeout, scheduler).thenApply(r -> null);
        } catch (InvalidTransaction e) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public CompletableFuture<Void> map(Relation parent, Relation child) {
        var call = mutator.call("call delphinius.mapRelation(?, ?, ?, ?)", parent.namespace().name(), parent.name(),
                                child.namespace().name(), child.name());
        try {
            return mutator.execute(exec, call, timeout, scheduler).thenApply(r -> null);
        } catch (InvalidTransaction e) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public CompletableFuture<Void> map(Subject parent, Subject child) {
        var call = mutator.call("call delphinius.mapSubject(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8) ",
                                parent.namespace().name(), parent.name(), parent.relation().namespace().name(),
                                parent.relation().name(), child.namespace().name(), child.name(),
                                child.relation().namespace().name(), child.relation().name());
        try {
            return mutator.execute(exec, call, timeout, scheduler).thenApply(r -> null);
        } catch (InvalidTransaction e) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public CompletableFuture<Void> remove(Object parent, Object child) {
        var call = mutator.call("call delphinius.removeObject(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8) ",
                                parent.namespace().name(), parent.name(), parent.relation().namespace().name(),
                                parent.relation().name(), child.namespace().name(), child.name(),
                                child.relation().namespace().name(), child.relation().name());
        try {
            return mutator.execute(exec, call, timeout, scheduler).thenApply(r -> null);
        } catch (InvalidTransaction e) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public CompletableFuture<Void> remove(Relation parent, Relation child) {
        var call = mutator.call("call delphinius.removeRelation(?, ?, ?, ?) ", parent.namespace().name(), parent.name(),
                                child.namespace().name(), child.name());
        try {
            return mutator.execute(exec, call, timeout, scheduler).thenApply(r -> null);
        } catch (InvalidTransaction e) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
    }

    @Override
    public CompletableFuture<Void> remove(Subject parent, Subject child) {
        var call = mutator.call("call delphinius.removeSubject(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8) ",
                                parent.namespace().name(), parent.name(), parent.relation().namespace().name(),
                                parent.relation().name(), child.namespace().name(), child.name(),
                                child.relation().namespace().name(), child.relation().name());
        try {
            return mutator.execute(exec, call, timeout, scheduler).thenApply(r -> null);
        } catch (InvalidTransaction e) {
            var f = new CompletableFuture<Void>();
            f.completeExceptionally(e);
            return f;
        }
    }

}
