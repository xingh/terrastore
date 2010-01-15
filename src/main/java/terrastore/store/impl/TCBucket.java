/**
 * Copyright 2009 Sergio Bossa (sergio.bossa@gmail.com)
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package terrastore.store.impl;

import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.terracotta.collections.ConcurrentDistributedMap;
import org.terracotta.collections.FinegrainedLock;
import org.terracotta.collections.HashcodeLockStrategy;
import org.terracotta.collections.LockType;
import org.terracotta.modules.annotations.HonorTransient;
import org.terracotta.modules.annotations.InstrumentedClass;
import terrastore.common.ErrorMessage;
import terrastore.store.Bucket;
import terrastore.store.SnapshotManager;
import terrastore.store.SortedSnapshot;
import terrastore.store.StoreOperationException;
import terrastore.store.features.Predicate;
import terrastore.store.features.Update;
import terrastore.store.Value;
import terrastore.store.operators.Condition;
import terrastore.store.operators.Function;
import terrastore.store.features.Range;

/**
 * @author Sergio Bossa
 */
@InstrumentedClass
@HonorTransient
public class TCBucket implements Bucket {

    private final String name;
    private final ConcurrentDistributedMap<String, Value> bucket;
    private transient SnapshotManager snapshotManager;

    public TCBucket(String name) {
        this.name = name;
        this.bucket = new ConcurrentDistributedMap<String, Value>(LockType.WRITE, new HashcodeLockStrategy(false, true));
    }

    public String getName() {
        return name;
    }

    public void put(String key, Value value) {
        bucket.putNoReturn(key, value);
    }

    public Value get(String key) throws StoreOperationException {
        Value value = bucket.get(key);
        if (value != null) {
            return value;
        } else {
            throw new StoreOperationException(new ErrorMessage(ErrorMessage.NOT_FOUND_ERROR_CODE, "Key not found: " + key));
        }
    }

    @Override
    public Value conditionalGet(String key, Predicate predicate, Condition condition) throws StoreOperationException {
        Value value = bucket.get(key);
        if (value != null) {
            if (value.dispatch(key, predicate, condition)) {
                return value;
            } else {
                return null;
            }
        } else {
            throw new StoreOperationException(new ErrorMessage(ErrorMessage.NOT_FOUND_ERROR_CODE, "Key not found: " + key));
        }
    }

    public void remove(String key) throws StoreOperationException {
        Value removed = bucket.remove(key);
        if (removed == null) {
            throw new StoreOperationException(new ErrorMessage(ErrorMessage.NOT_FOUND_ERROR_CODE, "Key not found: " + key));
        }
    }

    @Override
    public void update(final String key, final Update update, final Function function, final ExecutorService updateExecutor) throws StoreOperationException {
        long timeout = update.getTimeoutInMillis();
        boolean locked = lock(key);
        if (locked) {
            Future<Value> task = null;
            try {
                final Value value = bucket.get(key);
                task = updateExecutor.submit(new Callable<Value>() {

                    @Override
                    public Value call() {
                        return value.dispatch(key, update, function);
                    }
                });
                Value result = task.get(timeout, TimeUnit.MILLISECONDS);
                bucket.put(key, result);
            } catch (Exception ex) {
                task.cancel(true);
            } finally {
                unlock(key);
                if (task.isCancelled()) {
                    throw new StoreOperationException(new ErrorMessage(ErrorMessage.INTERNAL_SERVER_ERROR_CODE, "Update cancelled due to long execution time."));
                }
            }
        } else {
            throw new StoreOperationException(new ErrorMessage(ErrorMessage.NOT_FOUND_ERROR_CODE, "Key not found: " + key));
        }
    }

    public Set<String> keys() {
        return bucket.keySet();
    }

    public Set<String> keysInRange(Range keyRange, Comparator<String> keyComparator, long timeToLive) {
        SortedSnapshot snapshot = getOrCreateSnapshotManager().getOrComputeSortedSnapshot(this, keyComparator, keyRange.getKeyComparatorName(), timeToLive);
        return snapshot.keysInRange(keyRange.getStartKey(), keyRange.getEndKey(), keyRange.getLimit());
    }

    @Override
    public void flush(Set<String> keys) {
        for (String key : keys) {
            Value value = bucket.get(key);
            bucket.flush(key, value);
        }
    }

    public SnapshotManager getSnapshotManager() {
        return snapshotManager;
    }

    private boolean lock(String key) {
        if (bucket.containsKey(key)) {
            FinegrainedLock lock = bucket.createFinegrainedLock(key);
            lock.lock();
            return true;
        } else {
            return false;
        }
    }

    private void unlock(String key) {
        FinegrainedLock lock = bucket.createFinegrainedLock(key);
        lock.unlock();
    }

    // WARN: use a private getter and direct call to "new" because of TC not supporting injection of transient values:
    // TODO: use spin locks?
    private synchronized SnapshotManager getOrCreateSnapshotManager() {
        if (snapshotManager == null) {
            snapshotManager = new LocalSnapshotManager();
        }
        return snapshotManager;
    }
}
