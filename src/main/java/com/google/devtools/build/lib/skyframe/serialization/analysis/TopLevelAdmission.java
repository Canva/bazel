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
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Orders a reader's top-level targets and aspects so that the ones with work to do go first.
 *
 * <p>A reader of a large build asks for every top-level value at once: over a million of them for
 * a build of a whole monorepo. They are retrieved in the order they were asked for, by a client
 * that is short of CPU for all of it, and each stage of each retrieval takes its turn behind all
 * the others. A target that has to be built is in that queue too, and once for each step it takes:
 * its value, the record that says whether it was built, the results of the actions it needs. Its
 * first action started when the rest of the build had nearly finished.
 *
 * <p>Two things change that. The record of whether a top-level target or aspect was built is asked
 * for <em>before</em> its value. It is small, and all of them come back in a fraction of the time
 * the values take, so the reader knows early which targets have work to do. And those that do not
 * are then let through to fetch their values a bounded number at a time. The rest wait here, where
 * waiting costs nothing, and not in the queues of the executors, where they would be in the way.
 * A target with work to do never waits here, and what it asks for meets queues only as long as the
 * window.
 *
 * <p>One instance serves one command.
 */
public final class TopLevelAdmission {

  private final int window;

  /** What probing found, by {@code CompletedTopLevelKey}. */
  private final ConcurrentHashMap<Object, Object> probed = new ConcurrentHashMap<>();

  private final AtomicLong asked = new AtomicLong();
  private final AtomicLong askedValid = new AtomicLong();

  /** By top-level key. An entry stays for the command, so that a restart is not admitted twice. */
  private final ConcurrentHashMap<Object, Ticket> tickets = new ConcurrentHashMap<>();

  @GuardedBy("this")
  private int inFlight;

  @GuardedBy("this")
  private final ArrayDeque<Ticket> waiting = new ArrayDeque<>();

  @GuardedBy("this")
  private int peakWaiting;

  /** The artifact context of the command's top-level keys, which is the same for all of them. */
  @Nullable private volatile Object topLevelContext;

  private final long startNanos = System.nanoTime();
  private final AtomicLong admitted = new AtomicLong();
  private final Set<Object> withWork = ConcurrentHashMap.newKeySet();
  private final AtomicLong firstWithWorkNanos = new AtomicLong();
  private final AtomicLong lastProbeNanos = new AtomicLong();
  private final AtomicLong lastReleaseNanos = new AtomicLong();

  private static final class Ticket {
    private final SettableFuture<Void> admitted = SettableFuture.create();

    /** Guarded by the admission's lock. */
    private boolean released;
  }

  public TopLevelAdmission(int window) {
    this.window = window;
  }

  public void setTopLevelContext(Object context) {
    topLevelContext = context;
  }

  @Nullable
  public Object topLevelContext() {
    return topLevelContext;
  }

  /**
   * Records what asking the cache for {@code completedKey} found, for whoever needs the answer
   * next.
   *
   * @param valid whether that was a valid record
   */
  public void recordProbe(Object completedKey, Object outcome, boolean valid) {
    if (probed.putIfAbsent(completedKey, outcome) == null) {
      asked.incrementAndGet();
      if (valid) {
        askedValid.incrementAndGet();
      }
    }
    lastProbeNanos.set(System.nanoTime());
  }

  /**
   * Has what was found for one key answer for {@code completedKey} as well, which was not asked
   * for and now need not be.
   */
  public void shareProbe(Object completedKey, Object outcome) {
    probed.putIfAbsent(completedKey, outcome);
  }

  /** What was recorded or shared for the key, or null if nothing was. */
  @Nullable
  public Object probed(Object completedKey) {
    return probed.get(completedKey);
  }

  /** Notes that a top-level key has work to do, so that it is not held back. */
  public void noteHasWork(Object topLevelKey) {
    if (withWork.add(topLevelKey)) {
      firstWithWorkNanos.compareAndSet(0, System.nanoTime());
    }
    release(topLevelKey);
  }

  /**
   * Returns a future that is done when {@code topLevelKey} may fetch its value. The same future
   * for every call with the key, and done for good once it is: {@link #release} does not undo it.
   */
  public ListenableFuture<Void> admit(Object topLevelKey) {
    Ticket ticket = tickets.get(topLevelKey);
    if (ticket != null) {
      return ticket.admitted;
    }
    Ticket created = new Ticket();
    ticket = tickets.putIfAbsent(topLevelKey, created);
    if (ticket != null) {
      return ticket.admitted;
    }
    boolean now;
    synchronized (this) {
      if (created.released) {
        now = true; // Released before it asked: it does not count.
      } else if (inFlight < window) {
        inFlight++;
        now = true;
      } else {
        waiting.add(created);
        peakWaiting = Math.max(peakWaiting, waiting.size());
        now = false;
      }
    }
    if (now) {
      admitted.incrementAndGet();
      created.admitted.set(null);
    }
    return created.admitted;
  }

  /**
   * Gives up the place of {@code topLevelKey}, letting the next one in. Does nothing the second
   * time, and nothing for a key that never asked.
   */
  public void release(Object topLevelKey) {
    Ticket ticket = tickets.get(topLevelKey);
    if (ticket == null) {
      return;
    }
    Ticket next = null;
    boolean wasWaiting;
    synchronized (this) {
      if (ticket.released) {
        return;
      }
      ticket.released = true;
      wasWaiting = !ticket.admitted.isDone();
      if (!wasWaiting) {
        while ((next = waiting.poll()) != null && next.released) {}
        if (next == null) {
          inFlight--;
        }
      }
    }
    lastReleaseNanos.set(System.nanoTime());
    if (wasWaiting) {
      // Left in the queue, where it is skipped, and let through without taking a place.
      ticket.admitted.set(null);
    }
    if (next != null) {
      admitted.incrementAndGet();
      // Outside the lock: this runs whatever was waiting for it.
      next.admitted.set(null);
    }
  }

  /** A line for the end of the command, or an empty string if nothing was probed. */
  public String describe() {
    if (asked.get() == 0) {
      return "";
    }
    int peak;
    synchronized (this) {
      peak = peakWaiting;
    }
    return String.format(
        "%d records of built top-level targets and aspects asked for first, %d valid, the last"
            + " answered %.1f s into the command; %d top-level keys had work to do, the first"
            + " known at %.1f s; %d fetched their values at most %d at a time, the last at %.1f s,"
            + " with at most %d waiting",
        asked.get(),
        askedValid.get(),
        seconds(lastProbeNanos.get()),
        withWork.size(),
        seconds(firstWithWorkNanos.get()),
        admitted.get(),
        window,
        seconds(lastReleaseNanos.get()),
        peak);
  }

  private double seconds(long nanos) {
    return nanos == 0 ? 0 : (nanos - startNanos) / 1e9;
  }
}
