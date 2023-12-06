package ru.vk.itmo.solnyshkoksenia;

import ru.vk.itmo.Config;
import ru.vk.itmo.Entry;
import ru.vk.itmo.solnyshkoksenia.storage.DiskStorage;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class State {
    private static final Comparator<MemorySegment> comparator = new MemorySegmentComparator();
    protected final Config config;
    protected final NavigableMap<MemorySegment, Triple<MemorySegment>> storage;
    protected final NavigableMap<MemorySegment, Triple<MemorySegment>> flushingStorage;
    protected final DiskStorage diskStorage;
    private final AtomicLong storageByteSize = new AtomicLong();
    private final AtomicBoolean isClosed = new AtomicBoolean();
    private final AtomicBoolean overflow = new AtomicBoolean();

    public State(Config config,
                 NavigableMap<MemorySegment, Triple<MemorySegment>> storage,
                 NavigableMap<MemorySegment, Triple<MemorySegment>> flushingStorage,
                 DiskStorage diskStorage) {
        this.config = config;
        this.storage = storage;
        this.flushingStorage = flushingStorage;
        this.diskStorage = diskStorage;
    }

    public State(Config config,
                 DiskStorage diskStorage) {
        this.config = config;
        this.storage = new ConcurrentSkipListMap<>(comparator);
        this.flushingStorage = new ConcurrentSkipListMap<>(comparator);
        this.diskStorage = diskStorage;
    }

    public void putInMemory(Entry<MemorySegment> entry, Long ttl) {
        MemorySegment expiration = null;
        if (ttl != null) {
            long[] ar = {System.currentTimeMillis() + ttl};
            expiration = MemorySegment.ofArray(ar);
        }
        Triple<MemorySegment> triple = new Triple<>(entry.key(), entry.value(), expiration);
        Triple<MemorySegment> previousEntry = storage.put(triple.key(), triple);

        if (previousEntry != null) {
            storageByteSize.addAndGet(-getSize(previousEntry));
        }

        if (storageByteSize.addAndGet(getSize(triple)) > config.flushThresholdBytes()) {
            overflow.set(true);
        }
    }

    public void save() throws IOException {
        diskStorage.save(storage.values());
    }

    private static long getSize(Triple<MemorySegment> entry) {
        long valueSize = entry.value() == null ? 0 : entry.value().byteSize();
        long expirationSize = entry.expiration() == null ? 0 : entry.expiration().byteSize();
        return Long.BYTES + entry.key().byteSize() + Long.BYTES + valueSize + Long.BYTES + expirationSize;
    }

    public State checkAndGet() {
        if (isClosed.get()) {
            throw new DaoException("Dao is already closed");
        }
        return this;
    }

    public boolean isClosed() {
        return isClosed.get();
    }

    public boolean isOverflowed() {
        return overflow.get();
    }

    public boolean isFlushing() {
        return !flushingStorage.isEmpty();
    }

    public State moveStorage() {
        return new State(config, new ConcurrentSkipListMap<>(comparator), storage, diskStorage);
    }

    public void flush() throws IOException {
        diskStorage.save(flushingStorage.values());
    }

    public State close() {
        isClosed.set(true);
        return this;
    }

    public Entry<MemorySegment> get(MemorySegment key, Comparator<MemorySegment> comparator) {
        Triple<MemorySegment> entry = storage.get(key);
        if (entry != null) {
            if (entry.expiration() != null) {
                if (entry.expiration().toArray(ValueLayout.JAVA_LONG_UNALIGNED)[0] > System.currentTimeMillis()) {
                    return entry.value() == null ? null : entry;
                }
            } else {
                return entry.value() == null ? null : entry;
            }
        }

        entry = flushingStorage.get(key);
        if (entry != null) {
            if (entry.expiration() != null) {
                if (entry.expiration().toArray(ValueLayout.JAVA_LONG_UNALIGNED)[0] > System.currentTimeMillis()) {
                    return entry.value() == null ? null : entry;
                }
            } else {
                return entry.value() == null ? null : entry;
            }
        }

        Iterator<Triple<MemorySegment>> iterator = diskStorage.range(Collections.emptyIterator(), key, null);

        if (!iterator.hasNext()) {
            return null;
        }
        Triple<MemorySegment> next = iterator.next();
        if (comparator.compare(next.key(), key) == 0 && next.value() != null) {
            if (next.expiration() != null) {
                if (next.expiration().toArray(ValueLayout.JAVA_LONG_UNALIGNED)[0] > System.currentTimeMillis()) {
                    return next;
                }
            } else {
                return next;
            }
        }
        return null;
    }

    protected Iterator<Triple<MemorySegment>> getInMemory(NavigableMap<MemorySegment, Triple<MemorySegment>> memory,
                                                          MemorySegment from, MemorySegment to) {
        if (from == null && to == null) {
            return memory.values().iterator();
        }
        if (from == null) {
            return memory.headMap(to).values().iterator();
        }
        if (to == null) {
            return memory.tailMap(from).values().iterator();
        }
        return memory.subMap(from, to).values().iterator();
    }
}
