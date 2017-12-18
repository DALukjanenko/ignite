/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.query.h2;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.EntryProcessorException;
import javax.cache.processor.MutableEntry;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteDataStreamer;
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.cache.query.SqlFieldsQuery;
import org.apache.ignite.internal.GridKernalContext;
import org.apache.ignite.internal.processors.cache.CacheOperationContext;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.QueryCursorImpl;
import org.apache.ignite.internal.processors.cache.query.IgniteQueryErrorCode;
import org.apache.ignite.internal.processors.odbc.SqlStateCode;
import org.apache.ignite.internal.processors.query.GridQueryCacheObjectsIterator;
import org.apache.ignite.internal.processors.query.GridQueryCancel;
import org.apache.ignite.internal.processors.query.GridQueryFieldsResult;
import org.apache.ignite.internal.processors.query.GridQueryFieldsResultAdapter;
import org.apache.ignite.internal.processors.query.IgniteSQLException;
import org.apache.ignite.internal.processors.query.h2.dml.DmlArgument;
import org.apache.ignite.internal.processors.query.h2.dml.DmlBatchSender;
import org.apache.ignite.internal.processors.query.h2.dml.DmlDistributedPlanInfo;
import org.apache.ignite.internal.processors.query.h2.dml.DmlUtils;
import org.apache.ignite.internal.processors.query.h2.dml.UpdateMode;
import org.apache.ignite.internal.processors.query.h2.dml.UpdatePlan;
import org.apache.ignite.internal.processors.query.h2.dml.UpdatePlanBuilder;
import org.apache.ignite.internal.processors.query.h2.opt.GridH2RowDescriptor;
import org.apache.ignite.internal.processors.query.h2.sql.GridSqlQueryParser;
import org.apache.ignite.internal.util.GridBoundedConcurrentLinkedHashMap;
import org.apache.ignite.internal.util.lang.IgniteSingletonIterator;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.T3;
import org.apache.ignite.internal.util.typedef.X;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteBiTuple;
import org.apache.ignite.lang.IgniteInClosure;
import org.apache.ignite.spi.indexing.IndexingQueryFilter;
import org.h2.command.Prepared;
import org.h2.command.dml.Delete;
import org.h2.command.dml.Insert;
import org.h2.command.dml.Merge;
import org.h2.command.dml.Update;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.internal.processors.cache.query.IgniteQueryErrorCode.createJdbcSqlException;
import static org.apache.ignite.internal.processors.query.h2.IgniteH2Indexing.UPDATE_RESULT_META;

/**
 *
 */
public class DmlStatementsProcessor {
    /** Default number of attempts to re-run DELETE and UPDATE queries in case of concurrent modifications of values. */
    private final static int DFLT_DML_RERUN_ATTEMPTS = 4;

    /** Indexing. */
    private IgniteH2Indexing idx;

    /** Logger. */
    private IgniteLogger log;

    /** Default size for update plan cache. */
    private static final int PLAN_CACHE_SIZE = 1024;

    /** Update plans cache. */
    private final ConcurrentMap<H2DmlPlanKey, UpdatePlan> planCache =
        new GridBoundedConcurrentLinkedHashMap<>(PLAN_CACHE_SIZE);

    /**
     * Constructor.
     *
     * @param ctx Kernal context.
     * @param idx indexing.
     */
    public void start(GridKernalContext ctx, IgniteH2Indexing idx) {
        this.idx = idx;

        log = ctx.log(DmlStatementsProcessor.class);
    }

    /**
     * Handle cache stop.
     *
     * @param cacheName Cache name.
     */
    public void onCacheStop(String cacheName) {
        Iterator<Map.Entry<H2DmlPlanKey, UpdatePlan>> iter = planCache.entrySet().iterator();

        while (iter.hasNext()) {
            UpdatePlan plan = iter.next().getValue();

            if (F.eq(cacheName, plan.cacheContext().name()))
                iter.remove();
        }
    }

    /**
     * Execute DML statement, possibly with few re-attempts in case of concurrent data modifications.
     *
     * @param schemaName Schema.
     * @param conn Connection.
     * @param prepared Prepared statement.
     * @param fieldsQry Original query.
     * @param loc Query locality flag.
     * @param filters Cache name and key filter.
     * @param cancel Cancel.
     * @return Update result (modified items count and failed keys).
     * @throws IgniteCheckedException if failed.
     */
    private UpdateResult updateSqlFields(String schemaName, Connection conn, Prepared prepared,
        SqlFieldsQuery fieldsQry, boolean loc, IndexingQueryFilter filters, GridQueryCancel cancel)
        throws IgniteCheckedException {
        Object[] errKeys = null;

        long items = 0;

        UpdatePlan plan = getPlanForStatement(schemaName, conn, prepared, fieldsQry, loc, null);

        GridCacheContext<?, ?> cctx = plan.cacheContext();

        for (int i = 0; i < DFLT_DML_RERUN_ATTEMPTS; i++) {
            CacheOperationContext opCtx = cctx.operationContextPerCall();

            // Force keepBinary for operation context to avoid binary deserialization inside entry processor
            if (cctx.binaryMarshaller()) {
                CacheOperationContext newOpCtx = null;

                if (opCtx == null)
                    // Mimics behavior of GridCacheAdapter#keepBinary and GridCacheProxyImpl#keepBinary
                    newOpCtx = new CacheOperationContext(false, null, true, null, false, null, false);
                else if (!opCtx.isKeepBinary())
                    newOpCtx = opCtx.keepBinary();

                if (newOpCtx != null)
                    cctx.operationContextPerCall(newOpCtx);
            }

            UpdateResult r;

            try {
                r = executeUpdateStatement(schemaName, cctx, conn, prepared, fieldsQry, loc, filters, cancel);
            }
            finally {
                cctx.operationContextPerCall(opCtx);
            }

            items += r.counter();
            errKeys = r.errorKeys();

            if (F.isEmpty(errKeys))
                break;
        }

        if (F.isEmpty(errKeys)) {
            if (items == 1L)
                return UpdateResult.ONE;
            else if (items == 0L)
                return UpdateResult.ZERO;
        }

        return new UpdateResult(items, errKeys);
    }

    /**
     * @param schemaName Schema.
     * @param c Connection.
     * @param p Prepared statement.
     * @param fieldsQry Initial query
     * @param cancel Query cancel.
     * @return Update result wrapped into {@link GridQueryFieldsResult}
     * @throws IgniteCheckedException if failed.
     */
    @SuppressWarnings("unchecked")
    QueryCursorImpl<List<?>> updateSqlFieldsDistributed(String schemaName, Connection c, Prepared p,
        SqlFieldsQuery fieldsQry, GridQueryCancel cancel) throws IgniteCheckedException {
        UpdateResult res = updateSqlFields(schemaName, c, p, fieldsQry, false, null, cancel);

        checkUpdateResult(res);

        QueryCursorImpl<List<?>> resCur = (QueryCursorImpl<List<?>>)new QueryCursorImpl(Collections.singletonList
            (Collections.singletonList(res.counter())), cancel, false);

        resCur.fieldsMeta(UPDATE_RESULT_META);

        return resCur;
    }

    /**
     * Execute DML statement on local cache.
     *
     * @param schemaName Schema.
     * @param conn Connection.
     * @param stmt Prepared statement.
     * @param fieldsQry Fields query.
     * @param filters Cache name and key filter.
     * @param cancel Query cancel.
     * @return Update result wrapped into {@link GridQueryFieldsResult}
     * @throws IgniteCheckedException if failed.
     */
    @SuppressWarnings("unchecked")
    GridQueryFieldsResult updateSqlFieldsLocal(String schemaName, Connection conn, PreparedStatement stmt,
        SqlFieldsQuery fieldsQry, IndexingQueryFilter filters, GridQueryCancel cancel)
        throws IgniteCheckedException {
        UpdateResult res = updateSqlFields(schemaName, conn, GridSqlQueryParser.prepared(stmt), fieldsQry, true,
            filters, cancel);

        return new GridQueryFieldsResultAdapter(UPDATE_RESULT_META,
            new IgniteSingletonIterator(Collections.singletonList(res.counter())));
    }

    /**
     * Perform given statement against given data streamer. Only rows based INSERT is supported.
     *
     * @param streamer Streamer to feed data to.
     * @param stmt Statement.
     * @param args Statement arguments.
     * @return Number of rows in given INSERT statement.
     * @throws IgniteCheckedException if failed.
     */
    @SuppressWarnings({"unchecked", "ConstantConditions"})
    long streamUpdateQuery(IgniteDataStreamer streamer, PreparedStatement stmt, final Object[] args)
        throws IgniteCheckedException {
        Prepared p = GridSqlQueryParser.prepared(stmt);

        assert p != null;

        final UpdatePlan plan = UpdatePlanBuilder.planForStatement(p, true, idx, null, null, null);

        if (!F.eq(streamer.cacheName(), plan.cacheContext().name()))
            throw new IgniteSQLException("Cross cache streaming is not supported, please specify cache explicitly" +
                " in connection options", IgniteQueryErrorCode.UNSUPPORTED_OPERATION);

        if (plan.mode() == UpdateMode.INSERT && plan.rowCount() > 0) {
            assert plan.isLocalSubquery();

            final GridCacheContext cctx = plan.cacheContext();

            QueryCursorImpl<List<?>> cur;

            final ArrayList<List<?>> data = new ArrayList<>(plan.rowCount());

            QueryCursorImpl<List<?>> stepCur = new QueryCursorImpl<>(new Iterable<List<?>>() {
                @Override public Iterator<List<?>> iterator() {
                    try {
                        Iterator<List<?>> it;

                        if (!F.isEmpty(plan.selectQuery())) {
                            GridQueryFieldsResult res = idx.queryLocalSqlFields(idx.schema(cctx.name()),
                                plan.selectQuery(), F.asList(U.firstNotNull(args, X.EMPTY_OBJECT_ARRAY)),
                                null, false, 0, null);

                            it = res.iterator();
                        }
                        else
                            it = planToRows(plan, U.firstNotNull(args, X.EMPTY_OBJECT_ARRAY)).iterator();

                        return new GridQueryCacheObjectsIterator(it, idx.objectContext(), cctx.keepBinary());
                    }
                    catch (IgniteCheckedException e) {
                        throw new IgniteException(e);
                    }
                }
            }, null);

            data.addAll(stepCur.getAll());

            cur = new QueryCursorImpl<>(new Iterable<List<?>>() {
                @Override public Iterator<List<?>> iterator() {
                    return data.iterator();
                }
            }, null);

            if (plan.rowCount() == 1) {
                IgniteBiTuple t = plan.processRow(cur.iterator().next());

                streamer.addData(t.getKey(), t.getValue());

                return 1;
            }

            Map<Object, Object> rows = new LinkedHashMap<>(plan.rowCount());

            for (List<?> row : cur) {
                final IgniteBiTuple t = plan.processRow(row);

                rows.put(t.getKey(), t.getValue());
            }

            streamer.addData(rows);

            return rows.size();
        }
        else
            throw new IgniteSQLException("Only tuple based INSERT statements are supported in streaming mode",
                IgniteQueryErrorCode.UNSUPPORTED_OPERATION);
    }

    /**
     * Actually perform SQL DML operation locally.
     *
     * @param schemaName Schema name.
     * @param cctx Cache context.
     * @param c Connection.
     * @param prepared Prepared statement for DML query.
     * @param fieldsQry Fields query.
     * @param loc Local query flag.
     * @param filters Cache name and key filter.
     * @param cancel Query cancel state holder.
     * @return Pair [number of successfully processed items; keys that have failed to be processed]
     * @throws IgniteCheckedException if failed.
     */
    @SuppressWarnings({"ConstantConditions", "unchecked"})
    private UpdateResult executeUpdateStatement(String schemaName, final GridCacheContext cctx, Connection c,
        Prepared prepared, SqlFieldsQuery fieldsQry, boolean loc, IndexingQueryFilter filters, GridQueryCancel cancel)
        throws IgniteCheckedException {
        int mainCacheId = cctx.cacheId();

        Integer errKeysPos = null;

        UpdatePlan plan = getPlanForStatement(schemaName, c, prepared, fieldsQry, loc, errKeysPos);

        UpdateResult fastUpdateRes = plan.processFast(fieldsQry.getArgs());

        if (fastUpdateRes != null)
            return fastUpdateRes;

        if (plan.distributedPlan() != null) {
            UpdateResult result = doDistributedUpdate(schemaName, fieldsQry, plan, cancel);

            // null is returned in case not all nodes support distributed DML.
            if (result != null)
                return result;
        }

        Iterable<List<?>> cur;

        // Do a two-step query only if locality flag is not set AND if plan's SELECT corresponds to an actual
        // sub-query and not some dummy stuff like "select 1, 2, 3;"
        if (!loc && !plan.isLocalSubquery()) {
            assert !F.isEmpty(plan.selectQuery());

            SqlFieldsQuery newFieldsQry = new SqlFieldsQuery(plan.selectQuery(), fieldsQry.isCollocated())
                .setArgs(fieldsQry.getArgs())
                .setDistributedJoins(fieldsQry.isDistributedJoins())
                .setEnforceJoinOrder(fieldsQry.isEnforceJoinOrder())
                .setLocal(fieldsQry.isLocal())
                .setPageSize(fieldsQry.getPageSize())
                .setTimeout(fieldsQry.getTimeout(), TimeUnit.MILLISECONDS);

            cur = idx.queryDistributedSqlFields(schemaName, newFieldsQry, true, cancel, mainCacheId, true).get(0);
        }
        else if (F.isEmpty(plan.rows())) {
            final GridQueryFieldsResult res = idx.queryLocalSqlFields(schemaName, plan.selectQuery(),
                F.asList(fieldsQry.getArgs()), filters, fieldsQry.isEnforceJoinOrder(), fieldsQry.getTimeout(), cancel);

            cur = new QueryCursorImpl<>(new Iterable<List<?>>() {
                @Override public Iterator<List<?>> iterator() {
                    try {
                        return new GridQueryCacheObjectsIterator(res.iterator(), idx.objectContext(), true);
                    }
                    catch (IgniteCheckedException e) {
                        throw new IgniteException(e);
                    }
                }
            }, cancel);
        }
        else
            cur = planToRows(plan, fieldsQry.getArgs());

        int pageSize = loc ? 0 : fieldsQry.getPageSize();

        return processDmlSelectResult(cctx, plan, cur, pageSize);
    }

    /**
     * Extract rows from plan without performing any query.
     * @param plan Plan.
     * @param args Original query arguments.
     * @return Rows from plan.
     * @throws IgniteCheckedException if failed.
     */
    private List<List<?>> planToRows(UpdatePlan plan, Object[] args) throws IgniteCheckedException {
        assert plan.rowCount() > 0 && !F.isEmpty(plan.columnNames());

        List<List<?>> rows = new ArrayList<>(plan.rowCount());

        GridH2RowDescriptor desc = plan.table().rowDescriptor();

        for (List<DmlArgument> argRow : plan.rows()) {
            List<Object> row = new ArrayList<>();

            for (int j = 0; j < plan.columnNames().length; j++) {
                Object colVal = argRow.get(j).get(args);

                if (j == plan.keyColumnIndex() || j == plan.valueColumnIndex())
                    colVal = DmlUtils.convert(colVal, desc, j == plan.keyColumnIndex() ? desc.type().keyClass() :
                        desc.type().valueClass(), plan.columnTypes()[j]);

                row.add(colVal);
            }

            rows.add(row);
        }

        return rows;
    }

    /**
     * @param cctx Cache context.
     * @param plan Update plan.
     * @param cursor Cursor over select results.
     * @param pageSize Page size.
     * @return Pair [number of successfully processed items; keys that have failed to be processed]
     * @throws IgniteCheckedException if failed.
     */
    private UpdateResult processDmlSelectResult(GridCacheContext cctx, UpdatePlan plan, Iterable<List<?>> cursor,
        int pageSize) throws IgniteCheckedException {
        switch (plan.mode()) {
            case MERGE:
                return new UpdateResult(doMerge(plan, cursor, pageSize), X.EMPTY_OBJECT_ARRAY);

            case INSERT:
                return new UpdateResult(doInsert(plan, cursor, pageSize), X.EMPTY_OBJECT_ARRAY);

            case UPDATE:
                return doUpdate(plan, cursor, pageSize);

            case DELETE:
                return doDelete(cctx, cursor, pageSize);

            default:
                throw new IgniteSQLException("Unexpected DML operation [mode=" + plan.mode() + ']',
                    IgniteQueryErrorCode.UNEXPECTED_OPERATION);
        }
    }

    /**
     * Generate SELECT statements to retrieve data for modifications from and find fast UPDATE or DELETE args,
     * if available.
     *
     * @param schema Schema.
     * @param conn Connection.
     * @param p Prepared statement.
     * @param fieldsQry Original fields query.
     * @param loc Local query flag.
     * @return Update plan.
     */
    @SuppressWarnings({"unchecked", "ConstantConditions"})
    private UpdatePlan getPlanForStatement(String schema, Connection conn, Prepared p, SqlFieldsQuery fieldsQry,
        boolean loc, @Nullable Integer errKeysPos) throws IgniteCheckedException {
        H2DmlPlanKey planKey = new H2DmlPlanKey(schema, p.getSQL(), loc, fieldsQry);

        UpdatePlan res = (errKeysPos == null ? planCache.get(planKey) : null);

        if (res != null)
            return res;

        res = UpdatePlanBuilder.planForStatement(p, loc, idx, conn, fieldsQry, errKeysPos);

        // Don't cache re-runs
        if (errKeysPos == null)
            return U.firstNotNull(planCache.putIfAbsent(planKey, res), res);
        else
            return res;
    }

    /**
     * @param schemaName Schema name.
     * @param fieldsQry Initial query.
     * @param plan Update plan.
     * @param cancel Cancel state.
     * @return Update result.
     * @throws IgniteCheckedException if failed.
     */
    private UpdateResult doDistributedUpdate(String schemaName, SqlFieldsQuery fieldsQry, UpdatePlan plan,
        GridQueryCancel cancel) throws IgniteCheckedException {
        DmlDistributedPlanInfo distributedPlan = plan.distributedPlan();

        assert distributedPlan != null;

        if (cancel == null)
            cancel = new GridQueryCancel();

        return idx.runDistributedUpdate(schemaName, fieldsQry, distributedPlan.getCacheIds(),
            distributedPlan.isReplicatedOnly(), cancel);
    }

    /**
     * Perform DELETE operation on top of results of SELECT.
     * @param cctx Cache context.
     * @param cursor SELECT results.
     * @param pageSize Batch size for streaming, anything <= 0 for single page operations.
     * @return Results of DELETE (number of items affected AND keys that failed to be updated).
     */
    @SuppressWarnings({"unchecked", "ConstantConditions", "ThrowableResultOfMethodCallIgnored"})
    private UpdateResult doDelete(GridCacheContext cctx, Iterable<List<?>> cursor, int pageSize)
        throws IgniteCheckedException {
        DmlBatchSender sender = new DmlBatchSender(cctx, pageSize);

        for (List<?> row : cursor) {
            if (row.size() != 2) {
                U.warn(log, "Invalid row size on DELETE - expected 2, got " + row.size());

                continue;
            }

            sender.add(row.get(0), new ModifyingEntryProcessor(row.get(1), RMV));
        }

        sender.flush();

        SQLException resEx = sender.error();

        if (resEx != null) {
            if (!F.isEmpty(sender.failedKeys())) {
                // Don't go for a re-run if processing of some keys yielded exceptions and report keys that
                // had been modified concurrently right away.
                String msg = "Failed to DELETE some keys because they had been modified concurrently " +
                    "[keys=" + sender.failedKeys() + ']';

                SQLException conEx = createJdbcSqlException(msg, IgniteQueryErrorCode.CONCURRENT_UPDATE);

                conEx.setNextException(resEx);

                resEx = conEx;
            }

            throw new IgniteSQLException(resEx);
        }

        return new UpdateResult(sender.updateCount(), sender.failedKeys().toArray());
    }

    /**
     * Perform UPDATE operation on top of results of SELECT.
     * @param cursor SELECT results.
     * @param pageSize Batch size for streaming, anything <= 0 for single page operations.
     * @return Pair [cursor corresponding to results of UPDATE (contains number of items affected); keys whose values
     *     had been modified concurrently (arguments for a re-run)].
     */
    @SuppressWarnings({"unchecked", "ThrowableResultOfMethodCallIgnored"})
    private UpdateResult doUpdate(UpdatePlan plan, Iterable<List<?>> cursor, int pageSize)
        throws IgniteCheckedException {
        GridCacheContext cctx = plan.cacheContext();

        DmlBatchSender sender = new DmlBatchSender(cctx, pageSize);

        for (List<?> row : cursor) {
            T3<Object, Object, Object> row0 = plan.processRowForUpdate(row);

            Object key = row0.get1();
            Object oldVal = row0.get2();
            Object newVal = row0.get3();

            sender.add(key, new ModifyingEntryProcessor(oldVal, new EntryValueUpdater(newVal)));
        }

        sender.flush();

        SQLException resEx = sender.error();

        if (resEx != null) {
            if (!F.isEmpty(sender.failedKeys())) {
                // Don't go for a re-run if processing of some keys yielded exceptions and report keys that
                // had been modified concurrently right away.
                String msg = "Failed to UPDATE some keys because they had been modified concurrently " +
                    "[keys=" + sender.failedKeys() + ']';

                SQLException dupEx = createJdbcSqlException(msg, IgniteQueryErrorCode.CONCURRENT_UPDATE);

                dupEx.setNextException(resEx);

                resEx = dupEx;
            }

            throw new IgniteSQLException(resEx);
        }

        return new UpdateResult(sender.updateCount(), sender.failedKeys().toArray());
    }

    /**
     * Execute MERGE statement plan.
     * @param cursor Cursor to take inserted data from.
     * @param pageSize Batch size to stream data from {@code cursor}, anything <= 0 for single page operations.
     * @return Number of items affected.
     * @throws IgniteCheckedException if failed.
     */
    @SuppressWarnings("unchecked")
    private long doMerge(UpdatePlan plan, Iterable<List<?>> cursor, int pageSize) throws IgniteCheckedException {
        GridCacheContext cctx = plan.cacheContext();

        // If we have just one item to put, just do so
        if (plan.rowCount() == 1) {
            IgniteBiTuple t = plan.processRow(cursor.iterator().next());

            cctx.cache().put(t.getKey(), t.getValue());

            return 1;
        }
        else {
            int resCnt = 0;

            Map<Object, Object> rows = new LinkedHashMap<>();

            for (Iterator<List<?>> it = cursor.iterator(); it.hasNext();) {
                List<?> row = it.next();

                IgniteBiTuple t = plan.processRow(row);

                rows.put(t.getKey(), t.getValue());

                if ((pageSize > 0 && rows.size() == pageSize) || !it.hasNext()) {
                    cctx.cache().putAll(rows);

                    resCnt += rows.size();

                    if (it.hasNext())
                        rows.clear();
                }
            }

            return resCnt;
        }
    }

    /**
     * Execute INSERT statement plan.
     * @param cursor Cursor to take inserted data from.
     * @param pageSize Batch size for streaming, anything <= 0 for single page operations.
     * @return Number of items affected.
     * @throws IgniteCheckedException if failed, particularly in case of duplicate keys.
     */
    @SuppressWarnings({"unchecked", "ConstantConditions"})
    private long doInsert(UpdatePlan plan, Iterable<List<?>> cursor, int pageSize) throws IgniteCheckedException {
        GridCacheContext cctx = plan.cacheContext();

        // If we have just one item to put, just do so
        if (plan.rowCount() == 1) {
            IgniteBiTuple t = plan.processRow(cursor.iterator().next());

            if (cctx.cache().putIfAbsent(t.getKey(), t.getValue()))
                return 1;
            else
                throw new IgniteSQLException("Duplicate key during INSERT [key=" + t.getKey() + ']',
                    IgniteQueryErrorCode.DUPLICATE_KEY);
        }
        else {
            // Keys that failed to INSERT due to duplication.
            DmlBatchSender sender = new DmlBatchSender(cctx, pageSize);

            for (List<?> row : cursor) {
                final IgniteBiTuple keyValPair = plan.processRow(row);

                sender.add(keyValPair.getKey(), new InsertEntryProcessor(keyValPair.getValue()));
            }

            sender.flush();

            SQLException resEx = sender.error();

            if (!F.isEmpty(sender.failedKeys())) {
                String msg = "Failed to INSERT some keys because they are already in cache " +
                    "[keys=" + sender.failedKeys() + ']';

                SQLException dupEx = new SQLException(msg, SqlStateCode.CONSTRAINT_VIOLATION);

                if (resEx == null)
                    resEx = dupEx;
                else
                    resEx.setNextException(dupEx);
            }

            if (resEx != null)
                throw new IgniteSQLException(resEx);

            return sender.updateCount();
        }
    }

    /**
     *
     * @param schemaName Schema name.
     * @param stmt Prepared statement.
     * @param fldsQry Query.
     * @param filter Filter.
     * @param cancel Cancel state.
     * @param local Locality flag.
     * @return Update result.
     * @throws IgniteCheckedException if failed.
     */
    UpdateResult mapDistributedUpdate(String schemaName, PreparedStatement stmt, SqlFieldsQuery fldsQry,
        IndexingQueryFilter filter, GridQueryCancel cancel, boolean local) throws IgniteCheckedException {
        Connection c;

        try {
            c = stmt.getConnection();
        }
        catch (SQLException e) {
            throw new IgniteCheckedException(e);
        }

        return updateSqlFields(schemaName, c, GridSqlQueryParser.prepared(stmt), fldsQry, local, filter, cancel);
    }

    /** */
    private final static class InsertEntryProcessor implements EntryProcessor<Object, Object, Boolean> {
        /** Value to set. */
        private final Object val;

        /** */
        private InsertEntryProcessor(Object val) {
            this.val = val;
        }

        /** {@inheritDoc} */
        @Override public Boolean process(MutableEntry<Object, Object> entry, Object... arguments)
            throws EntryProcessorException {
            if (entry.exists())
                return false;

            entry.setValue(val);
            return null; // To leave out only erroneous keys - nulls are skipped on results' processing.
        }
    }

    /**
     * Entry processor invoked by UPDATE and DELETE operations.
     */
    private final static class ModifyingEntryProcessor implements EntryProcessor<Object, Object, Boolean> {
        /** Value to expect. */
        private final Object val;

        /** Action to perform on entry. */
        private final IgniteInClosure<MutableEntry<Object, Object>> entryModifier;

        /** */
        private ModifyingEntryProcessor(Object val, IgniteInClosure<MutableEntry<Object, Object>> entryModifier) {
            assert val != null;

            this.val = val;
            this.entryModifier = entryModifier;
        }

        /** {@inheritDoc} */
        @Override public Boolean process(MutableEntry<Object, Object> entry, Object... arguments)
            throws EntryProcessorException {
            if (!entry.exists())
                return null; // Someone got ahead of us and removed this entry, let's skip it.

            Object entryVal = entry.getValue();

            if (entryVal == null)
                return null;

            // Something happened to the cache while we were performing map-reduce.
            if (!F.eq(entryVal, val))
                return false;

            entryModifier.apply(entry);

            return null; // To leave out only erroneous keys - nulls are skipped on results' processing.
        }
    }

    /** */
    private static IgniteInClosure<MutableEntry<Object, Object>> RMV = new IgniteInClosure<MutableEntry<Object, Object>>() {
        /** {@inheritDoc} */
        @Override public void apply(MutableEntry<Object, Object> e) {
            e.remove();
        }
    };

    /**
     *
     */
    private static final class EntryValueUpdater implements IgniteInClosure<MutableEntry<Object, Object>> {
        /** Value to set. */
        private final Object val;

        /** */
        private EntryValueUpdater(Object val) {
            assert val != null;

            this.val = val;
        }

        /** {@inheritDoc} */
        @Override public void apply(MutableEntry<Object, Object> e) {
            e.setValue(val);
        }
    }

    /**
     * Check whether statement is DML statement.
     *
     * @param stmt Statement.
     * @return {@code True} if this is DML.
     */
    static boolean isDmlStatement(Prepared stmt) {
        return stmt instanceof Merge || stmt instanceof Insert || stmt instanceof Update || stmt instanceof Delete;
    }

    /**
     * Check update result for erroneous keys and throws concurrent update exception if necessary.
     *
     * @param r Update result.
     */
    static void checkUpdateResult(UpdateResult r) {
        if (!F.isEmpty(r.errorKeys())) {
            String msg = "Failed to update some keys because they had been modified concurrently " +
                "[keys=" + Arrays.toString(r.errorKeys()) + ']';

            SQLException conEx = createJdbcSqlException(msg, IgniteQueryErrorCode.CONCURRENT_UPDATE);

            throw new IgniteSQLException(conEx);
        }
    }

}
