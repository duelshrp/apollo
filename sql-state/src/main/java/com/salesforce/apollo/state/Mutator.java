/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.salesforce.apollo.state;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLType;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import deterministic.org.h2.value.Value;
import deterministic.org.h2.value.ValueToObjectConverter;

import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.salesfoce.apollo.state.proto.Arguments;
import com.salesfoce.apollo.state.proto.Batch;
import com.salesfoce.apollo.state.proto.BatchUpdate;
import com.salesfoce.apollo.state.proto.BatchedTransaction;
import com.salesfoce.apollo.state.proto.Call;
import com.salesfoce.apollo.state.proto.ChangeLog;
import com.salesfoce.apollo.state.proto.ChangeLog.Builder;
import com.salesfoce.apollo.state.proto.EXECUTION;
import com.salesfoce.apollo.state.proto.Migration;
import com.salesfoce.apollo.state.proto.Script;
import com.salesfoce.apollo.state.proto.Statement;
import com.salesfoce.apollo.state.proto.Txn;
import com.salesforce.apollo.choam.Session;
import com.salesforce.apollo.choam.support.InvalidTransaction;
import com.salesforce.apollo.state.SqlStateMachine.CallResult;

import liquibase.Contexts;
import liquibase.LabelExpression;

/**
 * The mutation API for the materialized view
 * 
 * @author hal.hildebrand
 * 
 */
public class Mutator {
    public static class BatchBuilder {
        private final BatchedTransaction.Builder batch = BatchedTransaction.newBuilder();
        private final Session                    session;

        public BatchBuilder(Session session) {
            this.session = session;
        }

        public BatchedTransaction build() {
            return batch.build();
        }

        public BatchBuilder execute(BatchUpdate update) {
            batch.addTransactions(Txn.newBuilder().setBatchUpdate(update));
            return this;
        }

        public BatchBuilder execute(Call call) {
            batch.addTransactions(Txn.newBuilder().setCall(call));
            return this;
        }

        public BatchBuilder execute(Migration migration) {
            batch.addTransactions(Txn.newBuilder().setMigration(migration));
            return this;
        }

        public BatchBuilder execute(Script script) {
            batch.addTransactions(Txn.newBuilder().setScript(script));
            return this;
        }

        public BatchBuilder execute(Statement statement) {
            batch.addTransactions(Txn.newBuilder().setStatement(statement));
            return this;
        }

        @SuppressWarnings("unchecked")
        public CompletableFuture<List<?>> submit(Executor exec, Duration timeout,
                                                 ScheduledExecutorService scheduler) throws InvalidTransaction {
            CompletableFuture<?> submit = session.submit(Txn.newBuilder().setBatched(build()).build(), timeout,
                                                         scheduler);
            return (CompletableFuture<List<?>>) submit;
        }
    }

    public static class BatchedTransactionException extends Exception {

        private static final long serialVersionUID = 1L;

        private final int index;

        public BatchedTransactionException(int index, String message, Throwable cause) {
            super(message, cause);
            this.index = index;
        }

        public BatchedTransactionException(int index, Throwable cause) {
            this(index, "Exception in " + index, cause);
        }

        public int getIndex() {
            return index;
        }

    }

    public static BatchedTransaction batch(Message... messages) {
        BatchedTransaction.Builder builder = BatchedTransaction.newBuilder();
        for (Message message : messages) {
            if (message instanceof Call) {
                builder.addTransactions(Txn.newBuilder().setCall((Call) message));
            } else if (message instanceof Batch) {
                builder.addTransactions(Txn.newBuilder().setBatch((Batch) message));
            } else if (message instanceof BatchUpdate) {
                builder.addTransactions(Txn.newBuilder().setBatchUpdate((BatchUpdate) message));
            } else if (message instanceof Statement) {
                builder.addTransactions(Txn.newBuilder().setStatement((Statement) message));
            } else {
                throw new IllegalArgumentException("Unknown transaction batch element type: " + message.getClass());
            }
        }
        return builder.build();
    }

    public static Batch batch(String... statements) {
        Batch.Builder builder = Batch.newBuilder();
        for (String sql : statements) {
            builder.addStatements(sql);
        }
        return builder.build();
    }

    public static ChangeLog changeLog(ByteString resources, String root) {
        return changeLog(resources, root, null, null).build();
    }

    public static Builder changeLog(ByteString resources, String root, Contexts context, LabelExpression labels) {
        final var builder = ChangeLog.newBuilder().setResources(resources).setRoot(root);
        if (context != null && !context.getContexts().isEmpty()) {
            builder.setContext(String.join(",", context.getContexts()));
        }
        if (labels != null && !labels.getLabels().isEmpty()) {
            builder.setLabels(String.join(",", labels.getLabels()));
        }
        return builder;
    }

    public static ChangeLog changeLog(int count, ByteString resources, String root, Contexts context,
                                      LabelExpression labels) {
        return changeLog(resources, root, context, labels).setCount(count).build();
    }

    public static ChangeLog changeLog(int count, Map<Path, URL> resources, String root, Contexts context,
                                      LabelExpression labels) {
        return changeLog(resourcesFrom(resources), root, context, labels).setCount(count).build();
    }

    public static ChangeLog changeLog(int count, Path resources, String root, Contexts context,
                                      LabelExpression labels) {
        return changeLog(resourcesFrom(resources), root, context, labels).setCount(count).build();
    }

    public static ChangeLog changeLog(Map<Path, URL> resources, String root) {
        return changeLog(resourcesFrom(resources), root, null, null).build();
    }

    public static ChangeLog changeLog(Path resources, String root) {
        return changeLog(resourcesFrom(resources), root, null, null).build();
    }

    public static ChangeLog changeLog(String tag, ByteString resources, String root, Contexts context,
                                      LabelExpression labels) {
        return changeLog(resources, root, context, labels).setTag(tag).build();
    }

    public static ChangeLog changeLog(String tag, Map<Path, URL> resources, String root, Contexts context,
                                      LabelExpression labels) {
        return changeLog(resourcesFrom(resources), root, context, labels).setTag(tag).build();
    }

    public static ChangeLog changeLog(String tag, Path resources, String root, Contexts context,
                                      LabelExpression labels) {
        return changeLog(resourcesFrom(resources), root, context, labels).setTag(tag).build();
    }

    public static ByteString resourcesFrom(Map<Path, URL> resources) {
        final var baos = new ByteArrayOutputStream();
        try (ZipOutputStream zs = new ZipOutputStream(baos)) {
            resources.entrySet().forEach(entry -> {
                ZipEntry zipEntry = new ZipEntry(entry.getKey().toString());
                try {
                    zs.putNextEntry(zipEntry);
                    try (var is = entry.getValue().openStream()) {
                        is.transferTo(zs);
                    }
                    zs.closeEntry();
                } catch (IOException e) {
                    throw new IllegalStateException("error creating entry: " + entry.getKey(), e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("error creating resources: " + resources, e);
        }
        return ByteString.copyFrom(baos.toByteArray());
    }

    public static ByteString resourcesFrom(Path sourceDirectory, FileVisitOption... options) {
        final var baos = new ByteArrayOutputStream();
        try (ZipOutputStream zs = new ZipOutputStream(baos)) {
            Files.walk(sourceDirectory, options).filter(path -> !Files.isDirectory(path)).forEach(path -> {
                ZipEntry zipEntry = new ZipEntry(sourceDirectory.relativize(path).toString());
                try {
                    zs.putNextEntry(zipEntry);
                    Files.copy(path, zs);
                    zs.closeEntry();
                } catch (IOException e) {
                    throw new IllegalStateException("error creating entry: " + path, e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("error creating resources: " + sourceDirectory, e);
        }
        return ByteString.copyFrom(baos.toByteArray());
    }

    public static Migration update(ChangeLog changeLog) {
        return Migration.newBuilder().setUpdate(changeLog).build();
    }

    private final deterministic.org.h2.engine.Session h2Session;

    private final Session session;

    public Mutator(Session session, deterministic.org.h2.engine.Session h2Session) {
        this.session = session;
        this.h2Session = h2Session;
    }

    public BatchBuilder batch() {
        return new BatchBuilder(session);
    }

    public BatchUpdate batch(String sql, List<List<Value>> batch) {
        BatchUpdate.Builder builder = BatchUpdate.newBuilder().setSql(sql);
        for (List<Value> arguments : batch) {
            StreamTransfer tfr = new StreamTransfer(h2Session);
            builder.addBatch(Arguments.newBuilder().setVersion(tfr.getVersion()).setArgs(tfr.write(arguments)));
        }

        return builder.build();
    }

    public BatchUpdate batchOf(String sql, List<List<Object>> batch) {
        return batch(sql,
                     batch.stream()
                          .map(args -> args.stream().map(o -> convert(o)).collect(Collectors.toList()))
                          .collect(Collectors.toList()));
    }

    public Call call(EXECUTION execution, String sql, List<SQLType> outParameters, Object... arguments) {
        Value[] argValues = new Value[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            argValues[i] = convert(arguments[i]);
        }
        return call(execution, sql, outParameters, argValues);
    }

    public Call call(EXECUTION execution, String sql, List<SQLType> outParameters, Value... arguments) {
        StreamTransfer tfr = new StreamTransfer(h2Session);
        return Call.newBuilder()
                   .setExecution(execution)
                   .addAllOutParameters(outParameters.stream().map(t -> t.getVendorTypeNumber()).toList())
                   .setSql(sql)
                   .setArgs(Arguments.newBuilder()
                                     .setVersion(tfr.getVersion())
                                     .setArgs(arguments == null ? ByteString.EMPTY
                                                                : tfr.write(Arrays.asList(arguments))))
                   .build();
    }

    public Call call(String sql) {
        return call(EXECUTION.EXECUTE, sql, null);
    }

    public Call call(String sql, List<SQLType> outParameters, Object... arguments) {
        return call(EXECUTION.EXECUTE, sql, outParameters, arguments);
    }

    public Call call(String sql, Object... arguments) {
        return call(EXECUTION.EXECUTE, sql, Collections.emptyList(), arguments);
    }

    public Script callScript(String className, String method, String source, Value... args) {
        StreamTransfer tfr = new StreamTransfer(h2Session);
        return Script.newBuilder()
                     .setClassName(className)
                     .setMethod(method)
                     .setSource(source)
                     .setArgs(Arguments.newBuilder()
                                       .setVersion(tfr.getVersion())
                                       .setArgs(tfr.write(Arrays.asList(args))))
                     .build();
    }

    public Value convert(Object x) {
        return ValueToObjectConverter.objectToValue(h2Session, x, Value.UNKNOWN);
    }

    public CompletableFuture<int[]> execute(Executor exec, Batch batch, Duration timeout,
                                            ScheduledExecutorService scheduler) throws InvalidTransaction {
        return session.submit(Txn.newBuilder().setBatch(batch).build(), timeout, scheduler);
    }

    public CompletableFuture<int[]> execute(Executor exec, BatchUpdate batchUpdate, Duration timeout,
                                            ScheduledExecutorService scheduler) throws InvalidTransaction {
        return session.submit(Txn.newBuilder().setBatchUpdate(batchUpdate).build(), timeout, scheduler);
    }

    public CompletableFuture<CallResult> execute(Executor exec, Call call, Duration timeout,
                                                 ScheduledExecutorService scheduler) throws InvalidTransaction {
        return session.submit(Txn.newBuilder().setCall(call).build(), timeout, scheduler);
    }

    public CompletableFuture<Boolean> execute(Executor exec, Migration migration, Duration timeout,
                                              ScheduledExecutorService scheduler) throws InvalidTransaction {
        return session.submit(Txn.newBuilder().setMigration(migration).build(), timeout, scheduler);
    }

    public <T> CompletableFuture<T> execute(Executor exec, Script script, Duration timeout,
                                            ScheduledExecutorService scheduler) throws InvalidTransaction {
        return session.submit(Txn.newBuilder().setScript(script).build(), timeout, scheduler);
    }

    public CompletableFuture<List<ResultSet>> execute(Executor exec, Statement statement, Duration timeout,
                                                      ScheduledExecutorService scheduler) throws InvalidTransaction {
        return session.submit(Txn.newBuilder().setStatement(statement).build(), timeout, scheduler);
    }

    public Session getSession() {
        return session;
    }

    public Statement statement(EXECUTION execution, String sql, Object... args) {
        Value[] paramValues = new Value[args == null ? 0 : args.length];
        for (int i = 0; i < (args == null ? 0 : args.length); i++) {
            paramValues[i] = convert(args[i]);
        }

        return statement(execution, sql, paramValues);
    }

    public Statement statement(EXECUTION execution, String sql, Value... args) {
        var tfr = new StreamTransfer(h2Session);
        return Statement.newBuilder()
                        .setSql(sql)
                        .setExecution(execution)
                        .setArgs(Arguments.newBuilder()
                                          .setVersion(tfr.getVersion())
                                          .setArgs(tfr.write(Arrays.asList(args))))
                        .build();
    }

    public Statement statement(String sql) {
        return statement(EXECUTION.EXECUTE, sql, (Object[]) null);
    }

    public Statement statement(String sql, Object... args) {
        return statement(EXECUTION.EXECUTE, sql, args);
    }
}
