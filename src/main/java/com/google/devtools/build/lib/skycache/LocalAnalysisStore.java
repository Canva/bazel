// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skycache;

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import com.canva.skycache.v1.LookupSnapshotRequest;
import com.canva.skycache.v1.LookupSnapshotResponse;
import com.canva.skycache.v1.RecordSnapshotRequest;
import com.canva.skycache.v1.RecordSnapshotResponse;
import com.canva.skycache.v1.Snapshot;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatus;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses;
import com.google.devtools.build.lib.util.Bucket;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32;
import javax.annotation.Nullable;

/**
 * An analysis cache in a directory on local disk, shared by every Bazel server of a user on a host.
 *
 * <p>Uploads put hundreds of thousands of entries of about a hundred bytes each, so the store is
 * not a file per entry. Entries are appended to segment files, each written by one command of one
 * server, and found through a single open-addressing hash table of key hashes to locations, mapped
 * into every reader:
 *
 * <pre>
 *   v1/lock              held while the table, the segment counter or the snapshots change
 *   v1/table             header, then (key hash, segment &lt;&lt; 40 | offset) slots
 *   v1/segments/N.seg    records: magic, key length, value length, key, value, CRC-32
 *   v1/snapshots         length-delimited {@link Snapshot}s, as the service's index keeps them
 * </pre>
 *
 * <p>A command appends without the lock and indexes what it wrote, under the lock, when it closes
 * the store, after forcing the segment to disk; an entry is visible once indexed. Readers take no
 * lock. They verify each record's key and checksum, so a slot read while another server writes it,
 * a table replaced while mapped, or a segment removed by garbage collection is a miss, never a
 * wrong value.
 *
 * <p>When the segments exceed the size limit, the oldest are removed and the table rebuilt without
 * them. Collection is first in, first out: nothing records which entries are read.
 */
final class LocalAnalysisStore implements FingerprintValueStore, SnapshotIndex, Closeable {

  static final long DEFAULT_MAX_BYTES = 20L << 30;

  /** A new segment is started past this size. */
  private static final long SEGMENT_BYTES = 1L << 30;

  private static final long TABLE_MAGIC = 0x534b595442313031L; // "SKYTB101"
  private static final int TABLE_HEADER_BYTES = 64;
  private static final int CAPACITY_OFFSET = 8;
  private static final int COUNT_OFFSET = 16;
  private static final int NEXT_SEGMENT_OFFSET = 24;
  private static final int SLOT_BYTES = 16;
  private static final long MIN_CAPACITY = 1L << 16;

  /** A gigabyte of table: one mapping, and tens of millions of entries. */
  private static final long MAX_CAPACITY = 1L << 26;

  private static final double MAX_LOAD = 0.6;

  /** An empty slot. Hashes are never zero. */
  private static final long EMPTY = 0;

  private static final int OFFSET_BITS = 40;
  private static final long OFFSET_MASK = (1L << OFFSET_BITS) - 1;
  private static final int MAX_SEGMENT = (1 << 23) - 1;

  private static final byte RECORD_MAGIC = (byte) 0xa5;
  private static final int RECORD_HEADER_BYTES = 9;
  private static final int RECORD_TRAILER_BYTES = 4;

  /** Garbage collection stops once the segments are back under this fraction of the limit. */
  private static final double GC_TARGET = 0.8;

  private static final ConcurrentHashMap<Path, ReentrantLock> IN_PROCESS_LOCKS =
      new ConcurrentHashMap<>();

  private final Path segmentsDir;
  private final Path lockFile;
  private final Path tableFile;
  private final Path snapshotsFile;
  private final long maxBytes;

  /** The table as it was when last mapped; another server may since have replaced the file. */
  private volatile Table table;

  private final ConcurrentHashMap<Integer, FileChannel> openSegments = new ConcurrentHashMap<>();

  /** Hashes put by this command, so a repeated put is not appended twice. */
  private final Set<Long> putHashes = ConcurrentHashMap.newKeySet();

  private final Object appendLock = new Object();

  @GuardedBy("appendLock")
  @Nullable
  private Segment current;

  @GuardedBy("appendLock")
  private final List<Segment> written = new ArrayList<>();

  @GuardedBy("appendLock")
  private long[] pendingHashes = new long[1024];

  @GuardedBy("appendLock")
  private long[] pendingLocators = new long[1024];

  @GuardedBy("appendLock")
  private int pendingCount;

  private final AtomicLong entriesWritten = new AtomicLong();
  private final AtomicLong keyBytesSent = new AtomicLong();
  private final AtomicLong valueBytesSent = new AtomicLong();
  private final AtomicLong entriesFound = new AtomicLong();
  private final AtomicLong entriesNotFound = new AtomicLong();
  private final AtomicLong valueBytesReceived = new AtomicLong();

  /** A segment this command is appending to. */
  private static final class Segment {
    private final int number;
    private final FileOutputStream file;
    private final BufferedOutputStream out;
    private long size;

    private Segment(int number, FileOutputStream file) {
      this.number = number;
      this.file = file;
      this.out = new BufferedOutputStream(file, 1 << 20);
    }
  }

  /** A read-only mapping of the table file. */
  private record Table(MappedByteBuffer slots, long capacity) {
    long hashAt(long slot) {
      return slots.getLong(TABLE_HEADER_BYTES + (int) (slot * SLOT_BYTES));
    }

    long locatorAt(long slot) {
      return slots.getLong(TABLE_HEADER_BYTES + (int) (slot * SLOT_BYTES) + 8);
    }
  }

  private LocalAnalysisStore(Path root, long maxBytes) {
    this.segmentsDir = root.resolve("segments");
    this.lockFile = root.resolve("lock");
    this.tableFile = root.resolve("table");
    this.snapshotsFile = root.resolve("snapshots");
    this.maxBytes = maxBytes;
  }

  /**
   * Opens, creating if needed, the store in {@code directory}.
   *
   * @param maxBytes the segment size above which the oldest are removed when the store is closed
   */
  static LocalAnalysisStore open(Path directory, long maxBytes) throws IOException {
    Path root = directory.resolve("v1");
    Files.createDirectories(root.resolve("segments"));
    var store = new LocalAnalysisStore(root, maxBytes);
    try (var lock = store.lock()) {
      if (!Files.exists(store.tableFile)) {
        store.writeTable(store.tableFile, MIN_CAPACITY, /* nextSegment= */ 1, new long[0], new long[0]);
      }
    }
    store.table = store.mapTable();
    return store;
  }

  @Override
  public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
    byte[] key = fingerprint.toBytes();
    long hash = hash(key);
    // Keys are content addresses, so an entry already present is this one. A different key with
    // the same 64-bit hash would lose its write, which only costs a miss.
    if (!putHashes.add(hash) || isIndexed(hash)) {
      return WriteStatuses.immediateWriteStatus(false);
    }
    try {
      append(hash, key, serializedBytes);
    } catch (IOException e) {
      return WriteStatuses.immediateFailedWriteStatus(e);
    }
    entriesWritten.incrementAndGet();
    keyBytesSent.addAndGet(key.length);
    valueBytesSent.addAndGet(serializedBytes.length);
    return WriteStatuses.immediateWriteStatus(true);
  }

  @Override
  public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) {
    byte[] value;
    try {
      value = read(fingerprint.toBytes());
    } catch (IOException e) {
      return immediateFailedFuture(e);
    }
    if (value == null) {
      entriesNotFound.incrementAndGet();
      return immediateFailedFuture(new MissingFingerprintValueException(fingerprint));
    }
    entriesFound.incrementAndGet();
    valueBytesReceived.addAndGet(value.length);
    return immediateFuture(value);
  }

  /** Returns the value stored for {@code key}, or null if there is none to be found. */
  @VisibleForTesting
  @Nullable
  byte[] read(byte[] key) throws IOException {
    long hash = hash(key);
    Table t = table;
    long mask = t.capacity() - 1;
    long slot = hash & mask;
    for (long probes = 0; probes < t.capacity(); probes++, slot = (slot + 1) & mask) {
      long slotHash = t.hashAt(slot);
      if (slotHash == EMPTY) {
        return null;
      }
      if (slotHash == hash) {
        byte[] value = readRecord(t.locatorAt(slot), key);
        if (value != null) {
          return value;
        }
      }
    }
    return null;
  }

  private boolean isIndexed(long hash) {
    Table t = table;
    long mask = t.capacity() - 1;
    long slot = hash & mask;
    for (long probes = 0; probes < t.capacity(); probes++, slot = (slot + 1) & mask) {
      long slotHash = t.hashAt(slot);
      if (slotHash == EMPTY) {
        return false;
      }
      if (slotHash == hash) {
        return true;
      }
    }
    return false;
  }

  /** Returns the value at {@code locator} if the record there is intact and is for {@code key}. */
  @Nullable
  private byte[] readRecord(long locator, byte[] key) throws IOException {
    FileChannel channel = segment((int) (locator >>> OFFSET_BITS));
    if (channel == null) {
      return null; // Collected.
    }
    long offset = locator & OFFSET_MASK;
    ByteBuffer header = ByteBuffer.allocate(RECORD_HEADER_BYTES);
    if (readFully(channel, header, offset) < RECORD_HEADER_BYTES
        || header.get(0) != RECORD_MAGIC
        || header.getInt(1) != key.length) {
      return null;
    }
    int valueLength = header.getInt(5);
    long available = channel.size() - offset - RECORD_HEADER_BYTES;
    if (valueLength < 0 || available < (long) key.length + valueLength + RECORD_TRAILER_BYTES) {
      return null;
    }
    ByteBuffer body = ByteBuffer.allocate(key.length + valueLength + RECORD_TRAILER_BYTES);
    if (readFully(channel, body, offset + RECORD_HEADER_BYTES) < body.capacity()) {
      return null;
    }
    byte[] bytes = body.array();
    if (!Arrays.equals(bytes, 0, key.length, key, 0, key.length)) {
      return null; // Another key with the same hash.
    }
    CRC32 crc = new CRC32();
    crc.update(bytes, 0, key.length + valueLength);
    if ((int) crc.getValue() != body.getInt(key.length + valueLength)) {
      return null;
    }
    return Arrays.copyOfRange(bytes, key.length, key.length + valueLength);
  }

  private static int readFully(FileChannel channel, ByteBuffer buffer, long position)
      throws IOException {
    int total = 0;
    while (buffer.hasRemaining()) {
      int read = channel.read(buffer, position + total);
      if (read < 0) {
        break;
      }
      total += read;
    }
    return total;
  }

  @Nullable
  private FileChannel segment(int number) throws IOException {
    FileChannel channel = openSegments.get(number);
    if (channel != null) {
      return channel;
    }
    try {
      channel = FileChannel.open(segmentPath(number), READ);
    } catch (NoSuchFileException e) {
      return null;
    }
    FileChannel raced = openSegments.putIfAbsent(number, channel);
    if (raced != null) {
      channel.close();
      return raced;
    }
    return channel;
  }

  private void append(long hash, byte[] key, byte[] value) throws IOException {
    int length = RECORD_HEADER_BYTES + key.length + value.length + RECORD_TRAILER_BYTES;
    ByteBuffer header = ByteBuffer.allocate(RECORD_HEADER_BYTES);
    header.put(RECORD_MAGIC).putInt(key.length).putInt(value.length);
    CRC32 crc = new CRC32();
    crc.update(key);
    crc.update(value);
    byte[] trailer = ByteBuffer.allocate(RECORD_TRAILER_BYTES).putInt((int) crc.getValue()).array();
    synchronized (appendLock) {
      if (current == null || current.size + length > SEGMENT_BYTES) {
        startSegment();
      }
      long locator = ((long) current.number << OFFSET_BITS) | current.size;
      current.out.write(header.array());
      current.out.write(key);
      current.out.write(value);
      current.out.write(trailer);
      current.size += length;
      if (pendingCount == pendingHashes.length) {
        pendingHashes = Arrays.copyOf(pendingHashes, pendingCount * 2);
        pendingLocators = Arrays.copyOf(pendingLocators, pendingCount * 2);
      }
      pendingHashes[pendingCount] = hash;
      pendingLocators[pendingCount] = locator;
      pendingCount++;
    }
  }

  @GuardedBy("appendLock")
  private void startSegment() throws IOException {
    int number;
    try (var lock = lock();
        FileChannel channel = FileChannel.open(tableFile, READ, WRITE)) {
      ByteBuffer counter = ByteBuffer.allocate(8);
      readFully(channel, counter, NEXT_SEGMENT_OFFSET);
      long next = counter.getLong(0);
      if (next > MAX_SEGMENT) {
        throw new IOException("the local analysis cache ran out of segment numbers; delete it");
      }
      number = (int) next;
      channel.write(ByteBuffer.allocate(8).putLong(0, next + 1), NEXT_SEGMENT_OFFSET);
    }
    Path path = segmentPath(number);
    Files.createFile(path);
    current = new Segment(number, new FileOutputStream(path.toFile(), /* append= */ true));
    written.add(current);
  }

  /**
   * Makes everything put so far readable by every server, and collects garbage if over the limit.
   *
   * <p>The segments reach the disk before the table points into them, so a crash loses entries
   * rather than indexing bytes that were never written.
   */
  void seal() throws IOException {
    synchronized (appendLock) {
      for (Segment segment : written) {
        segment.out.flush();
        segment.file.getChannel().force(false);
        segment.file.close();
      }
      Set<Integer> ours = new HashSet<>();
      for (Segment segment : written) {
        ours.add(segment.number);
      }
      written.clear();
      current = null;
      if (pendingCount == 0) {
        return;
      }
      try (var lock = lock()) {
        index(Arrays.copyOf(pendingHashes, pendingCount), Arrays.copyOf(pendingLocators, pendingCount));
        collectGarbage(ours);
      }
      pendingCount = 0;
    }
    table = mapTable();
  }

  /** Adds entries to the table, growing it if needed. Requires the lock. */
  private void index(long[] hashes, long[] locators) throws IOException {
    try (FileChannel channel = FileChannel.open(tableFile, READ, WRITE)) {
      ByteBuffer header = ByteBuffer.allocate(TABLE_HEADER_BYTES);
      readFully(channel, header, 0);
      long capacity = header.getLong(CAPACITY_OFFSET);
      long count = header.getLong(COUNT_OFFSET);
      if (count + hashes.length > capacity * MAX_LOAD && capacity < MAX_CAPACITY) {
        rebuild(/* removed= */ Set.of(), hashes, locators);
        return;
      }
      MappedByteBuffer slots =
          channel.map(FileChannel.MapMode.READ_WRITE, 0, TABLE_HEADER_BYTES + capacity * SLOT_BYTES);
      long added = 0;
      // At the largest table, entries past the load limit go unindexed until collection frees
      // room: misses, not a slower table.
      for (int i = 0; i < hashes.length && count + added < capacity * MAX_LOAD; i++) {
        if (insert(slots, capacity, hashes[i], locators[i])) {
          added++;
        }
      }
      slots.putLong(COUNT_OFFSET, count + added);
      slots.force();
    }
  }

  /**
   * Inserts into a writable mapping, returning false if the hash is already there.
   *
   * <p>The locator is written before the hash, so a reader that sees the hash sees where it points,
   * or at worst a stale location whose record fails verification.
   */
  private static boolean insert(MappedByteBuffer slots, long capacity, long hash, long locator) {
    long mask = capacity - 1;
    long slot = hash & mask;
    while (true) {
      int position = TABLE_HEADER_BYTES + (int) (slot * SLOT_BYTES);
      long slotHash = slots.getLong(position);
      if (slotHash == hash) {
        return false;
      }
      if (slotHash == EMPTY) {
        slots.putLong(position + 8, locator);
        slots.putLong(position, hash);
        return true;
      }
      slot = (slot + 1) & mask;
    }
  }

  /** Removes the oldest segments until under the limit. Requires the lock. */
  private void collectGarbage(Set<Integer> ours) throws IOException {
    TreeMap<Integer, Long> sizes = new TreeMap<>();
    long total = 0;
    try (var entries = Files.newDirectoryStream(segmentsDir, "*.seg")) {
      for (Path entry : entries) {
        String name = entry.getFileName().toString();
        try {
          long size = Files.size(entry);
          sizes.put(Integer.parseInt(name.substring(0, name.length() - ".seg".length())), size);
          total += size;
        } catch (NumberFormatException | NoSuchFileException e) {
          // Not a segment, or removed meanwhile.
        }
      }
    }
    if (total <= maxBytes) {
      return;
    }
    Set<Integer> removed = new HashSet<>();
    for (var segment : sizes.entrySet()) {
      if (total <= maxBytes * GC_TARGET) {
        break;
      }
      if (ours.contains(segment.getKey())) {
        continue; // Just written: the oldest are the ones to go.
      }
      Files.deleteIfExists(segmentPath(segment.getKey()));
      FileChannel open = openSegments.remove(segment.getKey());
      if (open != null) {
        open.close();
      }
      removed.add(segment.getKey());
      total -= segment.getValue();
    }
    if (!removed.isEmpty()) {
      rebuild(removed, new long[0], new long[0]);
    }
  }

  /**
   * Replaces the table with one holding its entries outside {@code removed} plus the given ones,
   * sized for them. Requires the lock.
   *
   * <p>Written beside the table and renamed over it, so a reader's mapping keeps the old file.
   */
  private void rebuild(Set<Integer> removed, long[] hashes, long[] locators) throws IOException {
    long nextSegment;
    var keptHashes = new ArrayList<long[]>();
    try (FileChannel channel = FileChannel.open(tableFile, READ)) {
      ByteBuffer header = ByteBuffer.allocate(TABLE_HEADER_BYTES);
      readFully(channel, header, 0);
      long capacity = header.getLong(CAPACITY_OFFSET);
      nextSegment = header.getLong(NEXT_SEGMENT_OFFSET);
      MappedByteBuffer slots =
          channel.map(FileChannel.MapMode.READ_ONLY, 0, TABLE_HEADER_BYTES + capacity * SLOT_BYTES);
      for (long slot = 0; slot < capacity; slot++) {
        int position = TABLE_HEADER_BYTES + (int) (slot * SLOT_BYTES);
        long hash = slots.getLong(position);
        long locator = slots.getLong(position + 8);
        if (hash != EMPTY && !removed.contains((int) (locator >>> OFFSET_BITS))) {
          keptHashes.add(new long[] {hash, locator});
        }
      }
    }
    long[] allHashes = new long[keptHashes.size() + hashes.length];
    long[] allLocators = new long[allHashes.length];
    for (int i = 0; i < keptHashes.size(); i++) {
      allHashes[i] = keptHashes.get(i)[0];
      allLocators[i] = keptHashes.get(i)[1];
    }
    System.arraycopy(hashes, 0, allHashes, keptHashes.size(), hashes.length);
    System.arraycopy(locators, 0, allLocators, keptHashes.size(), locators.length);

    long capacity = MIN_CAPACITY;
    while (allHashes.length > capacity * MAX_LOAD && capacity < MAX_CAPACITY) {
      capacity <<= 1;
    }
    Path partial = Files.createTempFile(tableFile.getParent(), "table", ".tmp");
    try {
      writeTable(partial, capacity, nextSegment, allHashes, allLocators);
      Files.move(partial, tableFile, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(partial);
    }
  }

  /** Writes a complete table file, keeping as many of the entries as the capacity allows. */
  private void writeTable(
      Path path, long capacity, long nextSegment, long[] hashes, long[] locators)
      throws IOException {
    try (FileChannel channel = FileChannel.open(path, READ, WRITE, CREATE)) {
      channel.truncate(0);
      MappedByteBuffer slots =
          channel.map(FileChannel.MapMode.READ_WRITE, 0, TABLE_HEADER_BYTES + capacity * SLOT_BYTES);
      long count = 0;
      for (int i = 0; i < hashes.length && count < capacity * MAX_LOAD; i++) {
        if (insert(slots, capacity, hashes[i], locators[i])) {
          count++;
        }
      }
      slots.putLong(0, TABLE_MAGIC);
      slots.putLong(CAPACITY_OFFSET, capacity);
      slots.putLong(COUNT_OFFSET, count);
      slots.putLong(NEXT_SEGMENT_OFFSET, nextSegment);
      slots.force();
    }
  }

  private Table mapTable() throws IOException {
    try (FileChannel channel = FileChannel.open(tableFile, READ)) {
      ByteBuffer header = ByteBuffer.allocate(TABLE_HEADER_BYTES);
      if (readFully(channel, header, 0) < TABLE_HEADER_BYTES
          || header.getLong(0) != TABLE_MAGIC) {
        throw new IOException(tableFile + " is not a local analysis cache table");
      }
      long capacity = header.getLong(CAPACITY_OFFSET);
      if (Long.bitCount(capacity) != 1
          || capacity > MAX_CAPACITY
          || channel.size() < TABLE_HEADER_BYTES + capacity * SLOT_BYTES) {
        throw new IOException(tableFile + " is truncated or corrupt");
      }
      // The mapping outlives the channel, and survives the file being renamed over.
      return new Table(
          channel.map(FileChannel.MapMode.READ_ONLY, 0, TABLE_HEADER_BYTES + capacity * SLOT_BYTES),
          capacity);
    }
  }

  /**
   * Holds the store's lock.
   *
   * <p>A file lock excludes other servers but belongs to the whole JVM, which throws rather than
   * wait on a second lock of the same file, so threads of one JVM queue on a monitor first.
   */
  private Closeable lock() throws IOException {
    ReentrantLock inProcess =
        IN_PROCESS_LOCKS.computeIfAbsent(lockFile.toAbsolutePath(), unused -> new ReentrantLock());
    inProcess.lock();
    FileChannel channel = null;
    FileLock lock;
    try {
      channel = FileChannel.open(lockFile, READ, WRITE, CREATE);
      lock = channel.lock();
    } catch (IOException | RuntimeException e) {
      if (channel != null) {
        channel.close();
      }
      inProcess.unlock();
      throw e;
    }
    FileChannel locked = channel;
    return () -> {
      try {
        lock.release();
        locked.close();
      } finally {
        inProcess.unlock();
      }
    };
  }

  private Path segmentPath(int number) {
    return segmentsDir.resolve(number + ".seg");
  }

  private static long hash(byte[] key) {
    long hash = Hashing.murmur3_128().hashBytes(key).asLong();
    return hash == EMPTY ? 1 : hash;
  }

  @Override
  public ListenableFuture<RecordSnapshotResponse> record(RecordSnapshotRequest request) {
    try {
      // Recorded only once the upload is readable, as the service only records acknowledged ones.
      seal();
      try (var lock = lock();
          var out = new FileOutputStream(snapshotsFile.toFile(), /* append= */ true)) {
        request.getSnapshot().writeDelimitedTo(out);
        out.getChannel().force(false);
      }
    } catch (IOException e) {
      return immediateFailedFuture(e);
    }
    return immediateFuture(RecordSnapshotResponse.getDefaultInstance());
  }

  @Override
  public ListenableFuture<LookupSnapshotResponse> lookup(LookupSnapshotRequest request) {
    Snapshot newest = null;
    try {
      for (Snapshot snapshot : readSnapshots()) {
        if (snapshot.getBazelInstallMd5().equals(request.getBazelInstallMd5())
            && snapshot.getConfigurationHash().equals(request.getConfigurationHash())
            && snapshot.getMasterOrdinal() <= request.getMaxMasterOrdinal()
            // A later record at the same ordinal replaces an earlier one, as in the service.
            && (newest == null || snapshot.getMasterOrdinal() >= newest.getMasterOrdinal())) {
          newest = snapshot;
        }
      }
    } catch (IOException e) {
      return immediateFailedFuture(e);
    }
    var response = LookupSnapshotResponse.newBuilder();
    if (newest != null) {
      response.setSnapshot(newest);
    }
    return immediateFuture(response.build());
  }

  /** Every recorded snapshot; one being appended meanwhile may be missing, never half-read. */
  private ImmutableList<Snapshot> readSnapshots() throws IOException {
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(snapshotsFile);
    } catch (NoSuchFileException e) {
      return ImmutableList.of();
    }
    var snapshots = ImmutableList.<Snapshot>builder();
    InputStream in = new ByteArrayInputStream(bytes);
    while (true) {
      try {
        Snapshot snapshot = Snapshot.parseDelimitedFrom(in);
        if (snapshot == null) {
          break;
        }
        snapshots.add(snapshot);
      } catch (InvalidProtocolBufferException e) {
        break; // A record still being appended.
      }
    }
    return snapshots.build();
  }

  /** Seals what this command wrote and releases the open segments. */
  @Override
  public void close() throws IOException {
    try {
      seal();
    } finally {
      for (FileChannel channel : openSegments.values()) {
        channel.close();
      }
      openSegments.clear();
    }
  }

  @Override
  public Stats getStats() {
    long entriesWritten = this.entriesWritten.get();
    long keyBytesSent = this.keyBytesSent.get();
    long valueBytesSent = this.valueBytesSent.get();
    long entriesFound = this.entriesFound.get();
    long entriesNotFound = this.entriesNotFound.get();
    long valueBytesReceived = this.valueBytesReceived.get();
    return new Stats() {
      @Override
      public long valueBytesReceived() {
        return valueBytesReceived;
      }

      @Override
      public long valueBytesSent() {
        return valueBytesSent;
      }

      @Override
      public long keyBytesSent() {
        return keyBytesSent;
      }

      @Override
      public long entriesWritten() {
        return entriesWritten;
      }

      @Override
      public long entriesFound() {
        return entriesFound;
      }

      @Override
      public long entriesNotFound() {
        return entriesNotFound;
      }

      @Override
      public long getBatches() {
        return entriesFound + entriesNotFound;
      }

      @Override
      public long setBatches() {
        return entriesWritten;
      }

      @Override
      public List<Bucket> getLatencyMicros() {
        return ImmutableList.of();
      }

      @Override
      public List<Bucket> setLatencyMicros() {
        return ImmutableList.of();
      }

      @Override
      public List<Bucket> getBatchLatencyMicros() {
        return ImmutableList.of();
      }

      @Override
      public List<Bucket> setBatchLatencyMicros() {
        return ImmutableList.of();
      }
    };
  }
}
