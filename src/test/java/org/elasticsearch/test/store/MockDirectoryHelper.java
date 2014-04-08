/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.test.store;

import com.carrotsearch.randomizedtesting.SeedUtils;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.Lock;
import org.apache.lucene.store.MMapDirectory;
import org.apache.lucene.store.MockDirectoryWrapper;
import org.apache.lucene.store.MockDirectoryWrapper.Throttling;
import org.apache.lucene.util.Constants;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.DirectoryService;
import org.elasticsearch.index.store.IndexStore;
import org.elasticsearch.index.store.fs.FsDirectoryService;
import org.elasticsearch.index.store.fs.MmapFsDirectoryService;
import org.elasticsearch.index.store.fs.NioFsDirectoryService;
import org.elasticsearch.index.store.fs.SimpleFsDirectoryService;
import org.elasticsearch.index.store.ram.RamDirectoryService;

import java.io.IOException;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MockDirectoryHelper {
    public static final String RANDOM_IO_EXCEPTION_RATE = "index.store.mock.random.io_exception_rate";
    public static final String RANDOM_IO_EXCEPTION_RATE_ON_OPEN = "index.store.mock.random.io_exception_rate_on_open";
    public static final String RANDOM_THROTTLE = "index.store.mock.random.throttle";
    public static final String RANDOM_PREVENT_DOUBLE_WRITE = "index.store.mock.random.prevent_double_write";
    public static final String RANDOM_NO_DELETE_OPEN_FILE = "index.store.mock.random.no_delete_open_file";
    public static final String CRASH_INDEX = "index.store.mock.random.crash_index";

    public static final Set<ElasticsearchMockDirectoryWrapper> wrappers = ConcurrentCollections.newConcurrentSet();


    private final Random random;
    private final double randomIOExceptionRate;
    private final double randomIOExceptionRateOnOpen;
    private final Throttling throttle;
    private final Settings indexSettings;
    private final ShardId shardId;
    private final boolean preventDoubleWrite;
    private final boolean noDeleteOpenFile;
    private final ESLogger logger;
    private final boolean crashIndex;

    public MockDirectoryHelper(ShardId shardId, Settings indexSettings, ESLogger logger, Random random, long seed) {
        this.random = random;
        randomIOExceptionRate = indexSettings.getAsDouble(RANDOM_IO_EXCEPTION_RATE, 0.0d);
        randomIOExceptionRateOnOpen = indexSettings.getAsDouble(RANDOM_IO_EXCEPTION_RATE_ON_OPEN, 0.0d);
        preventDoubleWrite = indexSettings.getAsBoolean(RANDOM_PREVENT_DOUBLE_WRITE, true); // true is default in MDW
        noDeleteOpenFile = indexSettings.getAsBoolean(RANDOM_NO_DELETE_OPEN_FILE, random.nextBoolean()); // true is default in MDW
        random.nextInt(shardId.getId() + 1); // some randomness per shard
        throttle = Throttling.valueOf(indexSettings.get(RANDOM_THROTTLE, random.nextDouble() < 0.1 ? "SOMETIMES" : "NEVER"));
        crashIndex = indexSettings.getAsBoolean(CRASH_INDEX, true);

        if (logger.isDebugEnabled()) {
            logger.debug("Using MockDirWrapper with seed [{}] throttle: [{}] crashIndex: [{}]", SeedUtils.formatSeed(seed),
                    throttle, crashIndex);
        }
        this.indexSettings = indexSettings;
        this.shardId = shardId;
        this.logger = logger;
    }

    public Directory wrap(Directory dir) {
        final ElasticsearchMockDirectoryWrapper w = new ElasticsearchMockDirectoryWrapper(random, dir, logger, this.crashIndex);
        w.setRandomIOExceptionRate(randomIOExceptionRate);
        w.setRandomIOExceptionRateOnOpen(randomIOExceptionRateOnOpen);
        w.setThrottling(throttle);
        w.setCheckIndexOnClose(false); // we do this on the index level
        w.setPreventDoubleWrite(preventDoubleWrite);
        w.setNoDeleteOpenFile(noDeleteOpenFile);
        wrappers.add(w);
        return w;
    }

    public Directory[] wrapAllInplace(Directory[] dirs) {
        for (int i = 0; i < dirs.length; i++) {
            dirs[i] = wrap(dirs[i]);
        }
        return dirs;
    }

    public FsDirectoryService randomDirectorService(IndexStore indexStore) {
        if ((Constants.WINDOWS || Constants.SUN_OS) && Constants.JRE_IS_64BIT && MMapDirectory.UNMAP_SUPPORTED) {
            return new MmapFsDirectoryService(shardId, indexSettings, indexStore);
        } else if (Constants.WINDOWS) {
            return new SimpleFsDirectoryService(shardId, indexSettings, indexStore);
        }
        switch (random.nextInt(3)) {
        case 1:
            return new MmapFsDirectoryService(shardId, indexSettings, indexStore);
        case 0:
            return new SimpleFsDirectoryService(shardId, indexSettings, indexStore);
        default:
            return new NioFsDirectoryService(shardId, indexSettings, indexStore);
        }
    }

    public DirectoryService randomRamDirectoryService() {
        return new RamDirectoryService(shardId, indexSettings);
    }

    public static final class ElasticsearchMockDirectoryWrapper extends MockDirectoryWrapper {

        private final Map<String, Exception> locks = new ConcurrentHashMap<>();
        private final ESLogger logger;
        private final boolean crash;
        private RuntimeException closeException;

        public ElasticsearchMockDirectoryWrapper(Random random, Directory delegate, ESLogger logger, boolean crash) {
            super(random, delegate);
            this.crash = crash;
            this.logger = logger;
        }

        @Override
        public synchronized void close() throws IOException {
            for (Exception ex : locks.values()) {
                logger.info("Lock still open - opened from: ", ex);
            }
            try {
                super.close();
            } catch (RuntimeException ex) {
                logger.info("MockDirectoryWrapper#close() threw exception", ex);
                closeException = ex;
                throw ex;
            }
        }

        public synchronized boolean successfullyClosed() {
            return closeException == null && !isOpen();
        }

        public synchronized RuntimeException closeException() {
            return closeException;
        }

        @Override
        public synchronized void crash() throws IOException {
            if (crash) {
                super.crash();
            }
        }

        @Override
        public Lock makeLock(String lockName) {
            Lock l = super.makeLock(lockName);
            if (l != null) {
                locks.put(lockName, new Exception());
            }
            return l;
        }

        public void clearLock(String lockName) throws IOException {
            try {
                super.clearLock(lockName);
            } finally {
                locks.remove(lockName);
            }
        }
    }
}
