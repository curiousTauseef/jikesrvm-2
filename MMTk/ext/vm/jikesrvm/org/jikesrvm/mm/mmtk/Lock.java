/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Common Public License (CPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/cpl1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.mm.mmtk;

import org.jikesrvm.VM;
import org.jikesrvm.VM_Services;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

import org.jikesrvm.runtime.VM_Entrypoints;
import org.jikesrvm.scheduler.VM_Synchronization;
import org.jikesrvm.runtime.VM_Magic;
import org.jikesrvm.scheduler.VM_Scheduler;
import org.jikesrvm.scheduler.VM_Thread;
import org.jikesrvm.runtime.VM_Time;

import org.mmtk.utility.Log;


/**
 * Simple, fair locks with deadlock detection.
 *
 * The implementation mimics a deli-counter and consists of two values:
 * the ticket dispenser and the now-serving display, both initially zero.
 * Acquiring a lock involves grabbing a ticket number from the dispenser
 * using a fetchAndIncrement and waiting until the ticket number equals
 * the now-serving display.  On release, the now-serving display is
 * also fetchAndIncremented.
 *
 * This implementation relies on there being less than 1<<32 waiters.
 */
@Uninterruptible public class Lock extends org.mmtk.vm.Lock {

  // Internal class fields
  private static final Offset dispenserFieldOffset = VM_Entrypoints.dispenserField.getOffset();
  private static final Offset servingFieldOffset = VM_Entrypoints.servingField.getOffset();
  private static final Offset threadFieldOffset = VM_Entrypoints.lockThreadField.getOffset();
  private static final Offset startFieldOffset = VM_Entrypoints.lockStartField.getOffset();
  /**
   * A lock operation is considered slow if it takes more than 200 milliseconds.
   * The value is represented in nanoSeconds (for use with VM_Time.nanoTime()).
   */
  private static long SLOW_THRESHOLD = 200 * ((long)1e6);
  /**
   * A lock operation times out if it takes more than 10x SLOW_THRESHOLD.
   * The value is represented in nanoSeconds (for use with VM_Time.nanoTime()).
   */
  private static long TIME_OUT = 10 * SLOW_THRESHOLD;

  // Debugging
  private static final boolean REPORT_SLOW = true;
  private static int TIMEOUT_CHECK_FREQ = 1000;
  public static final int verbose = 0; // show who is acquiring and releasing the locks
  private static int lockCount = 0;

  // Core Instance fields
  private String name;        // logical name of lock
  private final int id;       // lock id (based on a non-resetting counter)

  @SuppressWarnings({"unused", "UnusedDeclaration", "CanBeFinal"}) // Accessed via VM_EntryPoints
  @Entrypoint
  private int dispenser;      // ticket number of next customer
  @Entrypoint
  private int serving;        // number of customer being served
  // Diagnosis Instance fields
  @Entrypoint
  private VM_Thread thread;   // if locked, who locked it?
  @Entrypoint
  private long start;         // if locked, when was it locked?
  private int where = -1;     // how far along has the lock owner progressed?
  private final int[] servingHistory = new int[100];
  private final int[] tidHistory = new int[100];
  private final long[] startHistory = new long[100];
  private final long[] endHistory = new long[100];

  public Lock(String name) {
    this();
    this.name = name;
  }

  public Lock() {
    dispenser = serving = 0;
    id = lockCount++;
  }

  public void setName(String str) {
    name = str;
  }

  // Try to acquire a lock and spin-wait until acquired.
  // (1) The isync at the end is important to prevent hardware instruction re-ordering
  //       from floating instruction below the acquire above the point of acquisition.
  // (2) A deadlock is presumed to have occurred if the number of retries exceeds MAX_RETRY.
  // (3) When a lock is acquired, the time of acquisition and the identity of acquirer is recorded.
  //
  public void acquire() {

    int ticket = VM_Synchronization.fetchAndAdd(this, dispenserFieldOffset, 1);

    int retryCountdown = TIMEOUT_CHECK_FREQ;
    long localStart = 0; // Avoid getting time unnecessarily
    long lastSlowReport = 0;

    while (ticket != serving) {
      if (localStart == 0) lastSlowReport = localStart = VM_Time.nanoTime();
      if (--retryCountdown == 0) {
        retryCountdown = TIMEOUT_CHECK_FREQ;
        long now = VM_Time.nanoTime();
        long lastReportDuration = now - lastSlowReport;
        long waitTime = now - localStart;
        if (lastReportDuration >
            SLOW_THRESHOLD + ((200 * (VM_Scheduler.getCurrentThread().getIndex() % 5))) * 1e6) {
            lastSlowReport = now;
            Log.write("GC Warning: slow/deadlock - thread ");
            writeThreadIdToLog(VM_Scheduler.getCurrentThread());
            Log.write(" with ticket "); Log.write(ticket);
            Log.write(" failed to acquire lock "); Log.write(id);
            Log.write(" ("); Log.write(name);
            Log.write(") serving "); Log.write(serving);
            Log.write(" after ");
            Log.write(VM_Time.nanosToMillis(waitTime)); Log.write(" ms");
            Log.writelnNoFlush();

            VM_Thread t = thread;
            if (t == null)
              Log.writeln("GC Warning: Locking thread unknown", false);
            else {
              Log.write("GC Warning: Locking thread: ");
              writeThreadIdToLog(t);
              Log.write(" at position ");
              Log.writeln(where, false);
            }
            Log.write("GC Warning: my start = ");
            Log.writeln(localStart, false);
            // Print the last 10 entries preceding serving
            for (int i=(serving + 90) % 100; i != (serving%100); i = (i+1)%100) {
              if (VM.VerifyAssertions) VM._assert(i >= 0 && i < 100);
              Log.write("GC Warning: ");
              Log.write(i);
              Log.write(": index "); Log.write(servingHistory[i]);
              Log.write("   tid "); Log.write(tidHistory[i]);
              Log.write("    start = "); Log.write(startHistory[i]);
              Log.write("    end = "); Log.write(endHistory[i]);
              Log.write("    start-myStart = ");
              Log.write(VM_Time.nanosToMillis(startHistory[i] - localStart));
              Log.writelnNoFlush();
            }
            Log.flush();
        }
        if (waitTime > TIME_OUT) {
            Log.write("GC Warning: Locked out thread: ");
            writeThreadIdToLog(VM_Scheduler.getCurrentThread());
            Log.writeln();
            VM_Scheduler.dumpStack();
            VM.sysFail("Deadlock or someone holding on to lock for too long");
        }
      }
    }

    if (REPORT_SLOW) {
      servingHistory[serving % 100] = serving;
      tidHistory[serving % 100] = VM_Scheduler.getCurrentThread().getIndex();
      startHistory[serving % 100] = VM_Time.nanoTime();
      setLocker(VM_Time.nanoTime(), VM_Scheduler.getCurrentThread(), -1);
    }

    if (verbose > 1) {
      Log.write("Thread ");
      writeThreadIdToLog(thread);
      Log.write(" acquired lock "); Log.write(id);
      Log.write(" "); Log.write(name);
      Log.writeln();
    }
    VM_Magic.isync();
  }

  public void check (int w) {
    if (!REPORT_SLOW) return;
    if (VM.VerifyAssertions) VM._assert(VM_Scheduler.getCurrentThread() == thread);
    long diff = (REPORT_SLOW) ? VM_Time.nanoTime() - start : 0;
    boolean show = (verbose > 1) || (diff > SLOW_THRESHOLD);
    if (show) {
      Log.write("GC Warning: Thread ");
      writeThreadIdToLog(thread);
      Log.write(" reached point "); Log.write(w);
      Log.write(" while holding lock "); Log.write(id);
      Log.write(" "); Log.write(name);
      Log.write(" at "); Log.write(VM_Time.nanosToMillis(diff));
      Log.writeln(" ms");
    }
    where = w;
  }

  // Release the lock by incrementing serving counter.
  // (1) The sync is needed to flush changes made while the lock is held and also prevent
  //        instructions floating into the critical section.
  // (2) When verbose, the amount of time the lock is ehld is printed.
  //
  public void release() {
    long diff = (REPORT_SLOW) ? VM_Time.nanoTime() - start : 0;
    boolean show = (verbose > 1) || (diff > SLOW_THRESHOLD);
    if (show) {
      Log.write("GC Warning: Thread ");
      writeThreadIdToLog(thread);
      Log.write(" released lock "); Log.write(id);
      Log.write(" "); Log.write(name);
      Log.write(" after ");
      Log.write(VM_Time.nanosToMillis(diff));
      Log.writeln(" ms");
    }

    if (REPORT_SLOW) {
      endHistory[serving % 100] = VM_Time.nanoTime();
      setLocker(0, null, -1);
    }

    VM_Magic.sync();
    VM_Synchronization.fetchAndAdd(this, servingFieldOffset, 1);
  }

  // want to avoid generating a putfield so as to avoid write barrier recursion
  @Inline
  private void setLocker(long start, VM_Thread thread, int w) {
    VM_Magic.setLongAtOffset(this, startFieldOffset, start);
    VM_Magic.setObjectAtOffset(this, threadFieldOffset, thread);
    where = w;
  }

  /** Write thread <code>t</code>'s identifying info via the MMTk Log class.
   * Does not use any newlines, nor does it flush.
   *
   *  This function may be called during GC; it avoids write barriers and
   *  allocation.
   *
   *  @param t  The {@link VM_Thread} we are interested in.
   */
  private static void writeThreadIdToLog(VM_Thread t) {
    char[] buf = VM_Services.grabDumpBuffer();
    int len = t.dump(buf);
    Log.write(buf, len);
    VM_Services.releaseDumpBuffer();
  }
}
