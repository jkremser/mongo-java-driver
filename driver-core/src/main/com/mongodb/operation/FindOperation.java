/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.operation;

import com.mongodb.CursorType;
import com.mongodb.ExplainVerbosity;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoInternalException;
import com.mongodb.MongoNamespace;
import com.mongodb.MongoQueryException;
import com.mongodb.ReadPreference;
import com.mongodb.ServerAddress;
import com.mongodb.async.AsyncBatchCursor;
import com.mongodb.async.SingleResultCallback;
import com.mongodb.binding.AsyncConnectionSource;
import com.mongodb.binding.AsyncReadBinding;
import com.mongodb.binding.AsyncSingleConnectionReadBinding;
import com.mongodb.binding.ConnectionSource;
import com.mongodb.binding.ReadBinding;
import com.mongodb.binding.SingleConnectionReadBinding;
import com.mongodb.client.model.Collation;
import com.mongodb.connection.AsyncConnection;
import com.mongodb.connection.Connection;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.connection.QueryResult;
import com.mongodb.connection.ServerDescription;
import com.mongodb.operation.OperationHelper.CallableWithConnectionAndSource;
import com.mongodb.operation.OperationHelper.CallableWithSource;
import com.mongodb.session.SessionContext;
import org.bson.BsonBoolean;
import org.bson.BsonDocument;
import org.bson.BsonDocumentReader;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.codecs.BsonDocumentCodec;
import org.bson.codecs.Decoder;
import org.bson.codecs.DecoderContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.mongodb.ReadPreference.primary;
import static com.mongodb.assertions.Assertions.isTrueArgument;
import static com.mongodb.assertions.Assertions.notNull;
import static com.mongodb.connection.ServerType.SHARD_ROUTER;
import static com.mongodb.internal.async.ErrorHandlingResultCallback.errorHandlingCallback;
import static com.mongodb.internal.operation.ServerVersionHelper.serverIsAtLeastVersionThreeDotTwo;
import static com.mongodb.operation.CommandOperationHelper.CommandCreator;
import static com.mongodb.operation.CommandOperationHelper.CommandReadTransformer;
import static com.mongodb.operation.CommandOperationHelper.CommandReadTransformerAsync;
import static com.mongodb.operation.CommandOperationHelper.executeCommandWithConnection;
import static com.mongodb.operation.CommandOperationHelper.executeCommandAsyncWithConnection;
import static com.mongodb.operation.DocumentHelper.putIfNotNullOrEmpty;
import static com.mongodb.operation.OperationHelper.AsyncCallableWithConnectionAndSource;
import static com.mongodb.operation.OperationHelper.LOGGER;
import static com.mongodb.operation.OperationHelper.cursorDocumentToQueryResult;
import static com.mongodb.operation.OperationHelper.releasingCallback;
import static com.mongodb.operation.OperationHelper.validateReadConcernAndCollation;
import static com.mongodb.operation.OperationHelper.withConnection;
import static com.mongodb.operation.OperationHelper.withAsyncReadConnection;
import static com.mongodb.operation.OperationHelper.withReadConnectionSource;
import static com.mongodb.operation.OperationReadConcernHelper.appendReadConcernToCommand;

/**
 * An operation that queries a collection using the provided criteria.
 *
 * @param <T> the operations result type.
 * @since 3.0
 */
@Deprecated
public class FindOperation<T> implements AsyncReadOperation<AsyncBatchCursor<T>>, ReadOperation<BatchCursor<T>> {
    private static final String FIRST_BATCH = "firstBatch";

    private final MongoNamespace namespace;
    private final Decoder<T> decoder;
    private boolean retryReads;
    private BsonDocument filter;
    private int batchSize;
    private int limit;
    private BsonDocument modifiers;
    private BsonDocument projection;
    private long maxTimeMS;
    private long maxAwaitTimeMS;
    private int skip;
    private BsonDocument sort;
    private CursorType cursorType = CursorType.NonTailable;
    private boolean slaveOk;
    private boolean oplogReplay;
    private boolean noCursorTimeout;
    private boolean partial;
    private Collation collation;
    private String comment;
    private BsonValue hint;
    private BsonDocument max;
    private BsonDocument min;
    private long maxScan;
    private boolean returnKey;
    private boolean showRecordId;
    private boolean snapshot;

    /**
     * Construct a new instance.
     *
     * @param namespace the database and collection namespace for the operation.
     * @param decoder the decoder for the result documents.
     */
    public FindOperation(final MongoNamespace namespace, final Decoder<T> decoder) {
        this.namespace = notNull("namespace", namespace);
        this.decoder = notNull("decoder", decoder);
    }

    /**
     * Gets the namespace.
     *
     * @return the namespace
     */
    public MongoNamespace getNamespace() {
        return namespace;
    }

    /**
     * Gets the decoder used to decode the result documents.
     *
     * @return the decoder
     */
    public Decoder<T> getDecoder() {
        return decoder;
    }

    /**
     * Gets the query filter.
     *
     * @return the query filter
     * @mongodb.driver.manual reference/method/db.collection.find/ Filter
     */
    public BsonDocument getFilter() {
        return filter;
    }

    /**
     * Sets the query filter to apply to the query.
     *
     * @param filter the filter, which may be null.
     * @return this
     * @mongodb.driver.manual reference/method/db.collection.find/ Filter
     */
    public FindOperation<T> filter(final BsonDocument filter) {
        this.filter = filter;
        return this;
    }

    /**
     * Gets the number of documents to return per batch.  Default to 0, which indicates that the server chooses an appropriate batch size.
     *
     * @return the batch size, which may be null
     * @mongodb.driver.manual reference/method/cursor.batchSize/#cursor.batchSize Batch Size
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Sets the number of documents to return per batch.
     *
     * @param batchSize the batch size
     * @return this
     * @mongodb.driver.manual reference/method/cursor.batchSize/#cursor.batchSize Batch Size
     */
    public FindOperation<T> batchSize(final int batchSize) {
        this.batchSize = batchSize;
        return this;
    }

    /**
     * Gets the limit to apply.  The default is null.
     *
     * @return the limit
     * @mongodb.driver.manual reference/method/cursor.limit/#cursor.limit Limit
     */
    public int getLimit() {
        return limit;
    }

    /**
     * Sets the limit to apply.
     *
     * @param limit the limit, which may be null
     * @return this
     * @mongodb.driver.manual reference/method/cursor.limit/#cursor.limit Limit
     */
    public FindOperation<T> limit(final int limit) {
        this.limit = limit;
        return this;
    }

    /**
     * Gets the query modifiers to apply to this operation.  The default is not to apply any modifiers.
     *
     * @return the query modifiers, which may be null
     * @mongodb.driver.manual reference/operator/query-modifier/ Query Modifiers
     */
    public BsonDocument getModifiers() {
        return modifiers;
    }

    /**
     * Sets the query modifiers to apply to this operation.
     *
     * @param modifiers the query modifiers to apply, which may be null.
     * @return this
     * @mongodb.driver.manual reference/operator/query-modifier/ Query Modifiers
     * @deprecated use the individual setters instead
     */
    @Deprecated
    public FindOperation<T> modifiers(final BsonDocument modifiers) {
        this.modifiers = modifiers;
        return this;
    }

    /**
     * Gets a document describing the fields to return for all matching documents.
     *
     * @return the project document, which may be null
     * @mongodb.driver.manual reference/method/db.collection.find/ Projection
     */
    public BsonDocument getProjection() {
        return projection;
    }

    /**
     * Sets a document describing the fields to return for all matching documents.
     *
     * @param projection the project document, which may be null.
     * @return this
     * @mongodb.driver.manual reference/method/db.collection.find/ Projection
     */
    public FindOperation<T> projection(final BsonDocument projection) {
        this.projection = projection;
        return this;
    }

    /**
     * Gets the maximum execution time on the server for this operation.  The default is 0, which places no limit on the execution time.
     *
     * @param timeUnit the time unit to return the result in
     * @return the maximum execution time in the given time unit
     */
    public long getMaxTime(final TimeUnit timeUnit) {
        notNull("timeUnit", timeUnit);
        return timeUnit.convert(maxTimeMS, TimeUnit.MILLISECONDS);
    }

    /**
     * Sets the maximum execution time on the server for this operation.
     *
     * @param maxTime  the max time
     * @param timeUnit the time unit, which may not be null
     * @return this
     */
    public FindOperation<T> maxTime(final long maxTime, final TimeUnit timeUnit) {
        notNull("timeUnit", timeUnit);
        isTrueArgument("maxTime >= 0", maxTime >= 0);
        this.maxTimeMS = TimeUnit.MILLISECONDS.convert(maxTime, timeUnit);
        return this;
    }

    /**
     * The maximum amount of time for the server to wait on new documents to satisfy a tailable cursor
     * query. This only applies to a TAILABLE_AWAIT cursor. When the cursor is not a TAILABLE_AWAIT cursor,
     * this option is ignored.
     *
     * On servers &gt;= 3.2, this option will be specified on the getMore command as "maxTimeMS". The default
     * is no value: no "maxTimeMS" is sent to the server with the getMore command.
     *
     * On servers &lt; 3.2, this option is ignored, and indicates that the driver should respect the server's default value
     *
     * A zero value will be ignored.
     *
     * @param timeUnit the time unit to return the result in
     * @return the maximum await execution time in the given time unit
     * @since 3.2
     * @mongodb.driver.manual reference/method/cursor.maxTimeMS/#cursor.maxTimeMS Max Time
     */
    public long getMaxAwaitTime(final TimeUnit timeUnit) {
        notNull("timeUnit", timeUnit);
        return timeUnit.convert(maxAwaitTimeMS, TimeUnit.MILLISECONDS);
    }

    /**
     * Sets the maximum await execution time on the server for this operation.
     *
     * @param maxAwaitTime  the max await time.  A zero value will be ignored, and indicates that the driver should respect the server's
     *                      default value
     * @param timeUnit the time unit, which may not be null
     * @return this
     * @since 3.2
     * @mongodb.driver.manual reference/method/cursor.maxTimeMS/#cursor.maxTimeMS Max Time
     */
    public FindOperation<T> maxAwaitTime(final long maxAwaitTime, final TimeUnit timeUnit) {
        notNull("timeUnit", timeUnit);
        isTrueArgument("maxAwaitTime >= 0", maxAwaitTime >= 0);
        this.maxAwaitTimeMS = TimeUnit.MILLISECONDS.convert(maxAwaitTime, timeUnit);
        return this;
    }
    /**
     * Gets the number of documents to skip.  The default is 0.
     *
     * @return the number of documents to skip, which may be null
     * @mongodb.driver.manual reference/method/cursor.skip/#cursor.skip Skip
     */
    public int getSkip() {
        return skip;
    }

    /**
     * Sets the number of documents to skip.
     *
     * @param skip the number of documents to skip
     * @return this
     * @mongodb.driver.manual reference/method/cursor.skip/#cursor.skip Skip
     */
    public FindOperation<T> skip(final int skip) {
        this.skip = skip;
        return this;
    }

    /**
     * Gets the sort criteria to apply to the query. The default is null, which means that the documents will be returned in an undefined
     * order.
     *
     * @return a document describing the sort criteria
     * @mongodb.driver.manual reference/method/cursor.sort/ Sort
     */
    public BsonDocument getSort() {
        return sort;
    }

    /**
     * Sets the sort criteria to apply to the query.
     *
     * @param sort the sort criteria, which may be null.
     * @return this
     * @mongodb.driver.manual reference/method/cursor.sort/ Sort
     */
    public FindOperation<T> sort(final BsonDocument sort) {
        this.sort = sort;
        return this;
    }

    /**
     * Get the cursor type.
     *
     * @return the cursor type
     */
    public CursorType getCursorType() {
        return cursorType;
    }

    /**
     * Sets the cursor type.
     *
     * @param cursorType the cursor type
     * @return this
     */
    public FindOperation<T> cursorType(final CursorType cursorType) {
        this.cursorType = notNull("cursorType", cursorType);
        return this;
    }

    /**
     * Returns true if set to allowed to query non-primary replica set members.
     *
     * @return true if set to allowed to query non-primary replica set members.
     * @mongodb.driver.manual ../meta-driver/latest/legacy/mongodb-wire-protocol/#op-query OP_QUERY
     */
    public boolean isSlaveOk() {
        return slaveOk;
    }

    /**
     * Sets if allowed to query non-primary replica set members.
     *
     * @param slaveOk true if allowed to query non-primary replica set members.
     * @return this
     * @mongodb.driver.manual ../meta-driver/latest/legacy/mongodb-wire-protocol/#op-query OP_QUERY
     */
    public FindOperation<T> slaveOk(final boolean slaveOk) {
        this.slaveOk = slaveOk;
        return this;
    }

    /**
     * Internal replication use only.  Driver users should ordinarily not use this.
     *
     * @return oplogReplay
     * @mongodb.driver.manual ../meta-driver/latest/legacy/mongodb-wire-protocol/#op-query OP_QUERY
     */
    public boolean isOplogReplay() {
        return oplogReplay;
    }

    /**
     * Internal replication use only.  Driver users should ordinarily not use this.
     *
     * @param oplogReplay the oplogReplay value
     * @return this
     * @mongodb.driver.manual ../meta-driver/latest/legacy/mongodb-wire-protocol/#op-query OP_QUERY
     */
    public FindOperation<T> oplogReplay(final boolean oplogReplay) {
        this.oplogReplay = oplogReplay;
        return this;
    }

    /**
     * Returns true if cursor timeout has been turned off.
     *
     * <p>The server normally times out idle cursors after an inactivity period (10 minutes) to prevent excess memory use.</p>
     *
     * @return if cursor timeout has been turned off
     * @mongodb.driver.manual ../meta-driver/latest/legacy/mongodb-wire-protocol/#op-query OP_QUERY
     */
    public boolean isNoCursorTimeout() {
        return noCursorTimeout;
    }

    /**
     * Sets if the cursor timeout should be turned off.
     *
     * @param noCursorTimeout true if the cursor timeout should be turned off.
     * @return this
     * @mongodb.driver.manual ../meta-driver/latest/legacy/mongodb-wire-protocol/#op-query OP_QUERY
     */
    public FindOperation<T> noCursorTimeout(final boolean noCursorTimeout) {
        this.noCursorTimeout = noCursorTimeout;
        return this;
    }

    /**
     * Returns true if can get partial results from a mongos if some shards are down.
     *
     * @return if can get partial results from a mongos if some shards are down
     * @mongodb.driver.manual ../meta-driver/latest/legacy/mongodb-wire-protocol/#op-query OP_QUERY
     */
    public boolean isPartial() {
        return partial;
    }

    /**
     * Sets if partial results from a mongos if some shards are down are allowed
     *
     * @param partial allow partial results from a mongos if some shards are down
     * @return this
     * @mongodb.driver.manual ../meta-driver/latest/legacy/mongodb-wire-protocol/#op-query OP_QUERY
     */
    public FindOperation<T> partial(final boolean partial) {
        this.partial = partial;
        return this;
    }

    /**
     * Returns the collation options
     *
     * @return the collation options
     * @since 3.4
     * @mongodb.server.release 3.4
     */
    public Collation getCollation() {
        return collation;
    }

    /**
     * Sets the collation options
     *
     * <p>A null value represents the server default.</p>
     * @param collation the collation options to use
     * @return this
     * @since 3.4
     * @mongodb.server.release 3.4
     */
    public FindOperation<T> collation(final Collation collation) {
        this.collation = collation;
        return this;
    }

    /**
     * Returns the comment to send with the query. The default is not to include a comment with the query.
     *
     * @return the comment
     * @since 3.5
     */
    public String getComment() {
        return comment;
    }

    /**
     * Sets the comment to the query. A null value means no comment is set.
     *
     * @param comment the comment
     * @return this
     * @since 3.5
     */
    public FindOperation<T> comment(final String comment) {
        this.comment = comment;
        return this;
    }

    /**
     * Returns the hint for which index to use. The default is not to set a hint.
     *
     * @return the hint
     * @since 3.5
     */
    public BsonValue getHint() {
        return hint;
    }

    /**
     * Sets the hint for which index to use. A null value means no hint is set.
     *
     * @param hint the hint
     * @return this
     * @since 3.5
     */
    public FindOperation<T> hint(final BsonValue hint) {
        this.hint = hint;
        return this;
    }

    /**
     * Returns the exclusive upper bound for a specific index. By default there is no max bound.
     *
     * @return the max
     * @since 3.5
     */
    public BsonDocument getMax() {
        return max;
    }

    /**
     * Sets the exclusive upper bound for a specific index. A null value means no max is set.
     *
     * @param max the max
     * @return this
     * @since 3.5
     */
    public FindOperation<T> max(final BsonDocument max) {
        this.max = max;
        return this;
    }

    /**
     * Returns the minimum inclusive lower bound for a specific index. By default there is no min bound.
     *
     * @return the min
     * @since 3.5
     */
    public BsonDocument getMin() {
        return min;
    }

    /**
     * Sets the minimum inclusive lower bound for a specific index. A null value means no max is set.
     *
     * @param min the min
     * @return this
     * @since 3.5
     */
    public FindOperation<T> min(final BsonDocument min) {
        this.min = min;
        return this;
    }

    /**
     * Returns the maximum number of documents or index keys to scan when executing the query.
     *
     * A zero value or less will be ignored, and indicates that the driver should respect the server's default value.
     *
     * @return the maxScan
     * @since 3.5
     * @deprecated Deprecated as of MongoDB 4.0 release
     */
    @Deprecated
    public long getMaxScan() {
        return maxScan;
    }

    /**
     * Sets the maximum number of documents or index keys to scan when executing the query.
     *
     * A zero value or less will be ignored, and indicates that the driver should respect the server's default value.
     *
     * @param maxScan the maxScan
     * @return this
     * @since 3.5
     * @deprecated Deprecated as of MongoDB 4.0 release
     */
    @Deprecated
    public FindOperation<T> maxScan(final long maxScan) {
        this.maxScan = maxScan;
        return this;
    }

    /**
     * Returns the returnKey. If true the find operation will return only the index keys in the resulting documents.
     *
     * Default value is false. If returnKey is true and the find command does not use an index, the returned documents will be empty.
     *
     * @return the returnKey
     * @since 3.5
     */
    public boolean isReturnKey() {
        return returnKey;
    }

    /**
     * Sets the returnKey. If true the find operation will return only the index keys in the resulting documents.
     *
     * @param returnKey the returnKey
     * @return this
     * @since 3.5
     */
    public FindOperation<T> returnKey(final boolean returnKey) {
        this.returnKey = returnKey;
        return this;
    }

    /**
     * Returns the showRecordId.
     *
     * Determines whether to return the record identifier for each document. If true, adds a field $recordId to the returned documents.
     * The default is false.
     *
     * @return the showRecordId
     * @since 3.5
     */
    public boolean isShowRecordId() {
        return showRecordId;
    }

    /**
     * Sets the showRecordId. Set to true to add a field {@code $recordId} to the returned documents.
     *
     * @param showRecordId the showRecordId
     * @return this
     * @since 3.5
     */
    public FindOperation<T> showRecordId(final boolean showRecordId) {
        this.showRecordId = showRecordId;
        return this;
    }

    /**
     * Returns the snapshot.
     *
     * Prevents the cursor from returning a document more than once because of an intervening write operation. The default is false.
     *
     * @return the snapshot
     * @since 3.5
     * @deprecated Deprecated in MongoDB 3.6 release and removed in MongoDB 4.0 release
     */
    @Deprecated
    public boolean isSnapshot() {
        return snapshot;
    }

    /**
     * Sets the snapshot.
     *
     * If true it prevents the cursor from returning a document more than once because of an intervening write operation.
     *
     * @param snapshot the snapshot
     * @return this
     * @since 3.5
     * @deprecated Deprecated in MongoDB 3.6 release and removed in MongoDB 4.0 release
     */
    @Deprecated
    public FindOperation<T> snapshot(final boolean snapshot) {
        this.snapshot = snapshot;
        return this;
    }

    /**
     * Enables retryable reads if a read fails due to a network error.
     *
     * @param retryReads true if reads should be retried
     * @return this
     * @since 3.11
     */
    public FindOperation<T> retryReads(final boolean retryReads) {
        this.retryReads = retryReads;
        return this;
    }

    /**
     * Gets the value for retryable reads. The default is true.
     *
     * @return the retryable reads value
     * @since 3.11
     */
    public boolean getRetryReads() {
        return retryReads;
    }

    @Override
    public BatchCursor<T> execute(final ReadBinding binding) {
        return withReadConnectionSource(binding, new CallableWithSource<BatchCursor<T>>() {
            @Override
            public BatchCursor<T> call(final ConnectionSource source) {
                Connection connection = source.getConnection();
                if (serverIsAtLeastVersionThreeDotTwo(connection.getDescription())) {
                    try {
                        return executeCommandWithConnection(binding, source, namespace.getDatabaseName(),
                                getCommandCreator(binding.getSessionContext()),
                                CommandResultDocumentCodec.create(decoder, FIRST_BATCH), transformer(), retryReads, connection);
                    } catch (MongoCommandException e) {
                        throw new MongoQueryException(e);
                    }
                } else {
                    try {
                        validateReadConcernAndCollation(connection, binding.getSessionContext().getReadConcern(), collation);
                        QueryResult<T> queryResult = connection.query(namespace,
                                asDocument(connection.getDescription(), binding.getReadPreference()),
                                projection,
                                skip,
                                limit,
                                batchSize,
                                isSlaveOk() || binding.getReadPreference().isSlaveOk(),
                                isTailableCursor(),
                                isAwaitData(),
                                isNoCursorTimeout(),
                                isPartial(),
                                isOplogReplay(),
                                decoder);
                        return new QueryBatchCursor<T>(queryResult, limit, batchSize, getMaxTimeForCursor(), decoder, source, connection);
                    } finally {
                        connection.release();
                    }
                }
            }
        });
    }

    @Override
    public void executeAsync(final AsyncReadBinding binding, final SingleResultCallback<AsyncBatchCursor<T>> callback) {
        withAsyncReadConnection(binding, new AsyncCallableWithConnectionAndSource() {
            @Override
            public void call(final AsyncConnectionSource source, final AsyncConnection connection, final Throwable t) {
                SingleResultCallback<AsyncBatchCursor<T>> errHandlingCallback = errorHandlingCallback(callback, LOGGER);
                if (t != null) {
                    errHandlingCallback.onResult(null, t);
                } else {
                    if (serverIsAtLeastVersionThreeDotTwo(connection.getDescription())) {
                        final SingleResultCallback<AsyncBatchCursor<T>> wrappedCallback =
                                exceptionTransformingCallback(errHandlingCallback);
                        executeCommandAsyncWithConnection(binding, source, namespace.getDatabaseName(),
                                getCommandCreator(binding.getSessionContext()), CommandResultDocumentCodec.create(decoder, FIRST_BATCH),
                                asyncTransformer(), retryReads, connection, wrappedCallback);
                    } else {
                        final SingleResultCallback<AsyncBatchCursor<T>> wrappedCallback =
                                releasingCallback(errHandlingCallback, source, connection);
                        validateReadConcernAndCollation(source, connection, binding.getSessionContext().getReadConcern(), collation,
                                new AsyncCallableWithConnectionAndSource() {
                                    @Override
                                    public void call(final AsyncConnectionSource source, final AsyncConnection connection, final
                                    Throwable t) {
                                        if (t != null) {
                                            wrappedCallback.onResult(null, t);
                                        } else {
                                            connection.queryAsync(namespace, asDocument(connection.getDescription(),
                                                    binding.getReadPreference()), projection, skip, limit, batchSize,
                                                    isSlaveOk() || binding.getReadPreference().isSlaveOk(),
                                                    isTailableCursor(), isAwaitData(), isNoCursorTimeout(), isPartial(), isOplogReplay(),
                                                    decoder, new SingleResultCallback<QueryResult<T>>() {
                                                        @Override
                                                        public void onResult(final QueryResult<T> result, final Throwable t) {
                                                            if (t != null) {
                                                                wrappedCallback.onResult(null, t);
                                                            } else {
                                                                wrappedCallback.onResult(new AsyncQueryBatchCursor<T>(result, limit,
                                                                        batchSize, getMaxTimeForCursor(), decoder, source, connection),
                                                                        null);
                                                            }
                                                        }
                                                    });
                                        }
                                    }
                        });
                    }
                }
            }
        });
    }

    private static <T> SingleResultCallback<T> exceptionTransformingCallback(final SingleResultCallback<T> callback) {
        return new SingleResultCallback<T>() {
            @Override
            public void onResult(final T result, final Throwable t) {
                if (t != null) {
                    if (t instanceof MongoCommandException) {
                        MongoCommandException commandException = (MongoCommandException) t;
                        callback.onResult(result, new MongoQueryException(commandException.getServerAddress(),
                                                                          commandException.getErrorCode(),
                                commandException.getErrorMessage()));
                    } else {
                        callback.onResult(result, t);
                    }
                } else {
                    callback.onResult(result, null);
                }
            }
        };
    }


    /**
     * Gets an operation whose execution explains this operation.
     *
     * @param explainVerbosity the explain verbosity
     * @return a read operation that when executed will explain this operation
     */
    public ReadOperation<BsonDocument> asExplainableOperation(final ExplainVerbosity explainVerbosity) {
        notNull("explainVerbosity", explainVerbosity);
        return new ReadOperation<BsonDocument>() {
            @Override
            public BsonDocument execute(final ReadBinding binding) {
                return withConnection(binding, new CallableWithConnectionAndSource<BsonDocument>() {
                    @Override
                    public BsonDocument call(final ConnectionSource connectionSource, final Connection connection) {
                        ReadBinding singleConnectionBinding = new SingleConnectionReadBinding(binding.getReadPreference(),
                                                                                              connectionSource.getServerDescription(),
                                                                                              connection);
                        try {
                            if (serverIsAtLeastVersionThreeDotTwo(connection.getDescription())) {
                                try {
                                    return new CommandReadOperation<BsonDocument>(getNamespace().getDatabaseName(),
                                                                                  new BsonDocument("explain",
                                                                                                   getCommand(binding.getSessionContext())),
                                                                                  new BsonDocumentCodec()).execute(singleConnectionBinding);
                                } catch (MongoCommandException e) {
                                    throw new MongoQueryException(e);
                                }
                            } else {
                                BatchCursor<BsonDocument> cursor = createExplainableQueryOperation().execute(singleConnectionBinding);
                                try {
                                    return cursor.next().iterator().next();
                                } finally {
                                    cursor.close();
                                }
                            }
                        } finally {
                            singleConnectionBinding.release();
                        }
                    }
                });
            }
        };
    }

    /**
     * Gets an operation whose execution explains this operation.
     *
     * @param explainVerbosity the explain verbosity
     * @return a read operation that when executed will explain this operation
     */
    public AsyncReadOperation<BsonDocument> asExplainableOperationAsync(final ExplainVerbosity explainVerbosity) {
        notNull("explainVerbosity", explainVerbosity);
        return new AsyncReadOperation<BsonDocument>() {
            @Override
            public void executeAsync(final AsyncReadBinding binding, final SingleResultCallback<BsonDocument> callback) {
                withAsyncReadConnection(binding, new AsyncCallableWithConnectionAndSource() {
                    @Override
                    public void call(final AsyncConnectionSource connectionSource, final AsyncConnection connection, final Throwable t) {
                        SingleResultCallback<BsonDocument> errHandlingCallback = errorHandlingCallback(callback, LOGGER);
                        if (t != null) {
                            errHandlingCallback.onResult(null, t);
                        } else {
                            AsyncReadBinding singleConnectionReadBinding =
                            new AsyncSingleConnectionReadBinding(binding.getReadPreference(), connectionSource.getServerDescription(),
                                                                 connection);

                            if (serverIsAtLeastVersionThreeDotTwo(connection.getDescription())) {
                                new CommandReadOperation<BsonDocument>(namespace.getDatabaseName(),
                                                                       new BsonDocument("explain", getCommand(binding.getSessionContext())),
                                                                       new BsonDocumentCodec())
                                .executeAsync(singleConnectionReadBinding,
                                              releasingCallback(exceptionTransformingCallback(errHandlingCallback),
                                                                singleConnectionReadBinding, connectionSource, connection));
                            } else {
                                createExplainableQueryOperation()
                                .executeAsync(singleConnectionReadBinding,
                                              releasingCallback(new ExplainResultCallback(errHandlingCallback),
                                                      singleConnectionReadBinding, connectionSource, connection));
                            }
                        }
                    }
                });
            }
        };
    }

    private FindOperation<BsonDocument> createExplainableQueryOperation() {
        FindOperation<BsonDocument> explainFindOperation = new FindOperation<BsonDocument>(namespace, new BsonDocumentCodec());

        BsonDocument explainModifiers = new BsonDocument();
        if (modifiers != null) {
            explainModifiers.putAll(modifiers);
        }
        explainModifiers.append("$explain", BsonBoolean.TRUE);

        return explainFindOperation.filter(filter)
               .projection(projection)
               .sort(sort)
               .skip(skip)
               .limit(Math.abs(limit) * -1)
               .hint(hint)
               .min(min)
               .max(max)
               .modifiers(explainModifiers);

    }

    private BsonDocument asDocument(final ConnectionDescription connectionDescription, final ReadPreference readPreference) {
        BsonDocument document = new BsonDocument();

        if (modifiers != null) {
            document.putAll(modifiers);
        }
        if (sort != null) {
            document.put("$orderby", sort);
        }
        if (maxTimeMS > 0) {
            document.put("$maxTimeMS", new BsonInt64(maxTimeMS));
        }
        if (connectionDescription.getServerType() == SHARD_ROUTER && !readPreference.equals(primary())) {
            document.put("$readPreference", readPreference.toDocument());
        }
        if (comment != null) {
            document.put("$comment", new BsonString(comment));
        }
        if (hint != null) {
            document.put("$hint", hint);
        }
        if (max != null) {
            document.put("$max", max);
        }
        if (min != null) {
            document.put("$min", min);
        }
        if (maxScan > 0) {
            document.put("$maxScan", new BsonInt64(maxScan));
        }
        if (returnKey) {
            document.put("$returnKey", BsonBoolean.TRUE);
        }
        if (showRecordId) {
            document.put("$showDiskLoc", BsonBoolean.TRUE);
        }
        if (snapshot) {
            document.put("$snapshot", BsonBoolean.TRUE);
        }

        if (document.isEmpty()) {
            document = filter != null ? filter : new BsonDocument();
        } else if (filter != null) {
            document.put("$query", filter);
        } else if (!document.containsKey("$query")) {
            document.put("$query", new BsonDocument());
        }

        return document;
    }

    private static final Map<String, String> META_OPERATOR_TO_COMMAND_FIELD_MAP = new HashMap<String, String>();

    static {
        META_OPERATOR_TO_COMMAND_FIELD_MAP.put("$query", "filter");
        META_OPERATOR_TO_COMMAND_FIELD_MAP.put("$orderby", "sort");
        META_OPERATOR_TO_COMMAND_FIELD_MAP.put("$hint", "hint");
        META_OPERATOR_TO_COMMAND_FIELD_MAP.put("$comment", "comment");
        META_OPERATOR_TO_COMMAND_FIELD_MAP.put("$maxScan", "maxScan");
        META_OPERATOR_TO_COMMAND_FIELD_MAP.put("$maxTimeMS", "maxTimeMS");
        META_OPERATOR_TO_COMMAND_FIELD_MAP.put("$max", "max");
        META_OPERATOR_TO_COMMAND_FIELD_MAP.put("$min", "min");
        META_OPERATOR_TO_COMMAND_FIELD_MAP.put("$returnKey", "returnKey");
        META_OPERATOR_TO_COMMAND_FIELD_MAP.put("$showDiskLoc", "showRecordId");
        META_OPERATOR_TO_COMMAND_FIELD_MAP.put("$snapshot", "snapshot");
    }

    private BsonDocument getCommand(final SessionContext sessionContext) {
        BsonDocument commandDocument = new BsonDocument("find", new BsonString(namespace.getCollectionName()));

        appendReadConcernToCommand(sessionContext, commandDocument);

        if (modifiers != null) {
            for (Map.Entry<String, BsonValue> cur : modifiers.entrySet()) {
                String commandFieldName = META_OPERATOR_TO_COMMAND_FIELD_MAP.get(cur.getKey());
                if (commandFieldName != null) {
                    commandDocument.append(commandFieldName, cur.getValue());
                }
            }
        }
        putIfNotNullOrEmpty(commandDocument, "filter", filter);
        putIfNotNullOrEmpty(commandDocument, "sort", sort);
        putIfNotNullOrEmpty(commandDocument, "projection", projection);
        if (skip > 0) {
            commandDocument.put("skip", new BsonInt32(skip));
        }
        if (limit != 0) {
            commandDocument.put("limit", new BsonInt32(Math.abs(limit)));
        }
        if (limit >= 0) {
            if (batchSize < 0 && Math.abs(batchSize) < limit) {
                commandDocument.put("limit", new BsonInt32(Math.abs(batchSize)));
            } else if (batchSize != 0) {
                commandDocument.put("batchSize", new BsonInt32(Math.abs(batchSize)));
            }
        }
        if (limit < 0 || batchSize < 0) {
            commandDocument.put("singleBatch", BsonBoolean.TRUE);
        }
        if (maxTimeMS > 0) {
            commandDocument.put("maxTimeMS", new BsonInt64(maxTimeMS));
        }
        if (isTailableCursor()) {
            commandDocument.put("tailable", BsonBoolean.TRUE);
        }
        if (isAwaitData()) {
            commandDocument.put("awaitData", BsonBoolean.TRUE);
        }
        if (oplogReplay) {
            commandDocument.put("oplogReplay", BsonBoolean.TRUE);
        }
        if (noCursorTimeout) {
            commandDocument.put("noCursorTimeout", BsonBoolean.TRUE);
        }
        if (partial) {
            commandDocument.put("allowPartialResults", BsonBoolean.TRUE);
        }
        if (collation != null) {
            commandDocument.put("collation", collation.asDocument());
        }
        if (comment != null) {
            commandDocument.put("comment", new BsonString(comment));
        }
        if (hint != null) {
            commandDocument.put("hint", hint);
        }
        if (max != null) {
            commandDocument.put("max", max);
        }
        if (min != null) {
            commandDocument.put("min", min);
        }
        if (maxScan > 0) {
            commandDocument.put("maxScan", new BsonInt64(maxScan));
        }
        if (returnKey) {
            commandDocument.put("returnKey", BsonBoolean.TRUE);
        }
        if (showRecordId) {
            commandDocument.put("showRecordId", BsonBoolean.TRUE);
        }
        if (snapshot) {
            commandDocument.put("snapshot", BsonBoolean.TRUE);
        }
        return commandDocument;
    }

    private BsonDocument wrapInExplainIfNecessary(final BsonDocument commandDocument) {
        if (isExplain()) {
            return new BsonDocument("explain", commandDocument);
        } else {
            return commandDocument;
        }
    }

    private CommandCreator getCommandCreator(final SessionContext sessionContext) {
        return new CommandOperationHelper.CommandCreator() {
            @Override
            public BsonDocument create(final ServerDescription serverDescription, final ConnectionDescription connectionDescription) {
                validateReadConcernAndCollation(connectionDescription, sessionContext.getReadConcern(), collation);
                return wrapInExplainIfNecessary(getCommand(sessionContext));
            }
        };
    }

    private boolean isExplain() {
        return modifiers != null && modifiers.get("$explain", BsonBoolean.FALSE).equals(BsonBoolean.TRUE);
    }

    private boolean isTailableCursor() {
        return cursorType.isTailable();
    }

    private boolean isAwaitData() {
        return cursorType == CursorType.TailableAwait;
    }

    private CommandReadTransformer<BsonDocument, AggregateResponseBatchCursor<T>> transformer() {
        return new CommandReadTransformer<BsonDocument, AggregateResponseBatchCursor<T>>() {
            @Override
            public AggregateResponseBatchCursor<T> apply(final BsonDocument result, final ConnectionSource source,
                                                         final Connection connection) {
                QueryResult<T> queryResult = documentToQueryResult(result, connection.getDescription().getServerAddress());
                return new QueryBatchCursor<T>(queryResult, limit, batchSize, getMaxTimeForCursor(), decoder, source, connection, result);
            }
        };
    }

    private long getMaxTimeForCursor() {
        return cursorType == CursorType.TailableAwait ? maxAwaitTimeMS : 0;
    }

    private CommandReadTransformerAsync<BsonDocument, AsyncBatchCursor<T>> asyncTransformer() {
        return new CommandReadTransformerAsync<BsonDocument, AsyncBatchCursor<T>>() {
            @Override
            public AsyncBatchCursor<T> apply(final BsonDocument result, final AsyncConnectionSource source,
                                             final AsyncConnection connection) {
                QueryResult<T> queryResult = documentToQueryResult(result, connection.getDescription().getServerAddress());
                return new AsyncQueryBatchCursor<T>(queryResult, limit, batchSize, getMaxTimeForCursor(), decoder, source, connection,
                        result);
            }
        };
    }

    private QueryResult<T> documentToQueryResult(final BsonDocument result, final ServerAddress serverAddress) {
        QueryResult<T> queryResult;
        if (isExplain()) {
            T decodedDocument = decoder.decode(new BsonDocumentReader(result), DecoderContext.builder().build());
            queryResult = new QueryResult<T>(getNamespace(), Collections.singletonList(decodedDocument), 0, serverAddress);
        } else {
            queryResult = cursorDocumentToQueryResult(result.getDocument("cursor"), serverAddress);
        }
        return queryResult;
    }

    private static class ExplainResultCallback implements SingleResultCallback<AsyncBatchCursor<BsonDocument>> {
        private final SingleResultCallback<BsonDocument> callback;

        ExplainResultCallback(final SingleResultCallback<BsonDocument> callback) {
            this.callback = callback;
        }

        @Override
        public void onResult(final AsyncBatchCursor<BsonDocument> cursor, final Throwable t) {
            if (t != null) {
                callback.onResult(null, t);
            } else {
                cursor.next(new SingleResultCallback<List<BsonDocument>>() {
                    @Override
                    public void onResult(final List<BsonDocument> result, final Throwable t) {
                        cursor.close();
                        if (t != null) {
                            callback.onResult(null, t);
                        } else if (result == null || result.size() == 0) {
                            callback.onResult(null, new MongoInternalException("Expected explain result"));
                        } else {
                            callback.onResult(result.get(0), null);
                        }
                    }
                });
            }
        }
    }
}
