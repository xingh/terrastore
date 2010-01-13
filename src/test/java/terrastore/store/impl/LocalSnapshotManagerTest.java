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

import com.google.common.collect.Sets;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import terrastore.store.Bucket;
import terrastore.store.SortedSnapshot;
import static org.junit.Assert.*;
import static org.easymock.classextension.EasyMock.*;

/**
 * @author Sergio Bossa
 */
public class LocalSnapshotManagerTest {

    public LocalSnapshotManagerTest() {
    }

    @BeforeClass
    public static void setUpClass() throws Exception {
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
    }

    @Test
    public void testComputeNewSortedSnapshot() {
        Bucket bucket = createMock(Bucket.class);
        bucket.keys();
        expectLastCall().andReturn(Sets.newHashSet("v", "c", "a")).once();

        replay(bucket);

        LocalSnapshotManager snapshotManager = new LocalSnapshotManager();
        SortedSnapshot snapshot = snapshotManager.getOrComputeSortedSnapshot(bucket, new StringComparator(), "string", 0);
        assertNotNull(snapshot);

        verify(bucket);
    }
    
    @Test
    public void testComputeNewSortedSnapshotAndGetSame() {
        Bucket bucket = createMock(Bucket.class);
        bucket.keys();
        expectLastCall().andReturn(Sets.newHashSet("v", "c", "a")).once();
        
        replay(bucket);
        
        LocalSnapshotManager snapshotManager = new LocalSnapshotManager();
        SortedSnapshot snapshot = snapshotManager.getOrComputeSortedSnapshot(bucket, new StringComparator(), "string", 1000);
        assertNotNull(snapshot);
        
        SortedSnapshot read = snapshotManager.getOrComputeSortedSnapshot(bucket, new StringComparator(), "string", 1000);
        assertSame(snapshot, read);
                
        verify(bucket);
    }

    @Test
    public void testComputeTwoDifferentSortedSnapshots() {
        Bucket bucket = createMock(Bucket.class);
        bucket.keys();
        expectLastCall().andReturn(Sets.newHashSet("v", "c", "a")).times(2);

        replay(bucket);

        LocalSnapshotManager snapshotManager = new LocalSnapshotManager();

        SortedSnapshot snapshot1 = snapshotManager.getOrComputeSortedSnapshot(bucket, new StringComparator(), "string1", 1000);
        assertNotNull(snapshot1);
        SortedSnapshot snapshot2 = snapshotManager.getOrComputeSortedSnapshot(bucket, new StringComparator(), "string2", 1000);
        assertNotNull(snapshot2);
        assertNotSame(snapshot1, snapshot2);

        verify(bucket);
    }

    @Test
    public void testComputeNewSortedSnapshotAndComputeAgainBecauseExpired() throws InterruptedException {
        Bucket bucket = createMock(Bucket.class);
        bucket.keys();
        expectLastCall().andReturn(Sets.newHashSet("v", "c", "a")).times(2);

        replay(bucket);

        LocalSnapshotManager snapshotManager = new LocalSnapshotManager();
        SortedSnapshot snapshot = snapshotManager.getOrComputeSortedSnapshot(bucket, new StringComparator(), "string", 0);
        assertNotNull(snapshot);

        Thread.sleep(500);

        SortedSnapshot read = snapshotManager.getOrComputeSortedSnapshot(bucket, new StringComparator(), "string", 100);
        assertNotSame(snapshot, read);

        verify(bucket);
    }

    @Test
    public void testMultipleThreadsOnlyComputeSnapshotOneTime() throws ExecutionException, InterruptedException {
        int nThreads = 100;

        Bucket bucket = createMock(Bucket.class);
        bucket.keys();
        expectLastCall().andReturn(Sets.newHashSet("v", "c", "a")).times(1);
        makeThreadSafe(bucket, true);

        replay(bucket);

        LocalSnapshotManager snapshotManager = new LocalSnapshotManager();

        ExecutorService executor = Executors.newFixedThreadPool(nThreads);

        Collection<SnapshotThread> threads = new LinkedList<SnapshotThread>();
        for (int i = 0; i < nThreads; i++) {
            threads.add(new SnapshotThread(snapshotManager, bucket, 10000));
        }
        List<Future<SortedSnapshot>> futures = executor.invokeAll(threads);
        for (int i = 0; i < nThreads; i++) {
            assertSame(futures.get(0).get(), futures.get(i).get());
        }

        executor.shutdown();
        executor.awaitTermination(60, TimeUnit.SECONDS);

        verify(bucket);
    }

    @Test
    public void testMultipleThreadsAlwaysComputeNewSnapshot() throws ExecutionException, InterruptedException {
        int nThreads = 100;

        Bucket bucket = createMock(Bucket.class);
        bucket.keys();
        expectLastCall().andReturn(Sets.newHashSet("v", "c", "a")).times(nThreads);
        makeThreadSafe(bucket, true);

        replay(bucket);

        LocalSnapshotManager snapshotManager = new LocalSnapshotManager();

        ExecutorService executor = Executors.newFixedThreadPool(nThreads);

        Collection<SnapshotThread> threads = new LinkedList<SnapshotThread>();
        for (int i = 0; i < nThreads; i++) {
            threads.add(new SnapshotThread(snapshotManager, bucket, 0));
        }
        executor.invokeAll(threads);

        executor.shutdown();
        executor.awaitTermination(60, TimeUnit.SECONDS);

        verify(bucket);
    }

    private static class StringComparator implements Comparator<String> {

        @Override
        public int compare(String o1, String o2) {
            return o1.compareTo(o2);
        }
    }

    private static class SnapshotThread implements Callable<SortedSnapshot> {

        private LocalSnapshotManager snapshotManager;
        private Bucket bucket;
        private long timeToLive;

        public SnapshotThread(LocalSnapshotManager snapshotManager, Bucket bucket, long timeToLive) {
            this.snapshotManager = snapshotManager;
            this.bucket = bucket;
            this.timeToLive = timeToLive;
        }

        @Override
        public SortedSnapshot call() throws Exception {
            return snapshotManager.getOrComputeSortedSnapshot(bucket, new StringComparator(), "string", timeToLive);
        }
    }
}