/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2003
 */
package com.ibm.JikesRVM.memoryManagers.JMTk;

import com.ibm.JikesRVM.memoryManagers.vmInterface.VM_Interface;
import com.ibm.JikesRVM.memoryManagers.vmInterface.Constants;
import com.ibm.JikesRVM.memoryManagers.vmInterface.ScanObject;
import com.ibm.JikesRVM.memoryManagers.vmInterface.Statistics;

import com.ibm.JikesRVM.VM_Magic;
import com.ibm.JikesRVM.VM_Address;
import com.ibm.JikesRVM.VM_PragmaInline;
import com.ibm.JikesRVM.VM_PragmaNoInline;
import com.ibm.JikesRVM.VM_PragmaUninterruptible;
import com.ibm.JikesRVM.VM_Uninterruptible;

/**
 * This class implements thread-local behavior for a reference counted
 * space.  Each instance of this class captures state associated with
 * one thread/CPU acting over a particular reference counted space.
 * Since all state is thread local, instance methods of this class are
 * not required to be synchronized.
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
final class RefCountLocal extends SegregatedFreeList
  implements Constants, VM_Uninterruptible {
  public final static String Id = "$Id$"; 

  ////////////////////////////////////////////////////////////////////////////
  //
  // Class variables
  //
  private static SharedQueue rootPool;
  private static SharedQueue tracingPool;

  private static final int DEC_COUNT_QUANTA = 2000; // do 2000 decs at a time
  private static final double DEC_TIME_FRACTION = 0.66; // 2/3 remaining time

  ////////////////////////////////////////////////////////////////////////////
  //
  // Instance variables
  //
  private RefCountSpace rcSpace;
  private RefCountLOSLocal los;
  private Plan plan;

  private AddressQueue incBuffer;
  private AddressQueue decBuffer;
  private AddressQueue rootSet;
  private AddressQueue tracingBuffer;

  private boolean decrementPhase = false;

  private TrialDeletion cycleDetector;

  // counters
  private int incCounter;
  private int decCounter;
  private int rootCounter;
  private int purpleCounter;

  private boolean cycleBufferAisOpen = true;

  protected final boolean preserveFreeList() { return true; }
  protected final boolean maintainInUse() { return true; }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Initialization
  //

  /**
   * Constructor
   *
   * @param space The ref count space with which this local thread is
   * associated.
   * @param plan The plan with which this local thread is associated.
   */
  RefCountLocal(RefCountSpace space, Plan plan_, RefCountLOSLocal los_, 
		AddressQueue inc, AddressQueue dec, AddressQueue root) {
    super(space.getVMResource(), space.getMemoryResource(), plan_);
    rcSpace = space;
    plan = plan_;
    los = los_;

    incBuffer = inc;
    decBuffer = dec;
    rootSet = root;
    if (Plan.REF_COUNT_SANITY_TRACING) {
      tracingBuffer = new AddressQueue("tracing buffer", tracingPool);
    }
    if (Plan.REF_COUNT_CYCLE_DETECTION)
      cycleDetector = new TrialDeletion(this, plan_);
  }

  /**
   * Class initializer.  This is executed <i>prior</i> to bootstrap
   * (i.e. at "build" time).  This is where key <i>global</i>
   * instances are allocated.  These instances will be incorporated
   * into the boot image by the build process.
   */
  static {
    rootPool = new SharedQueue(Plan.getMetaDataRPA(), 1);
    rootPool.newClient();
    if (Plan.REF_COUNT_SANITY_TRACING) {
      tracingPool = new SharedQueue(Plan.getMetaDataRPA(), 1);
      tracingPool.newClient();
    }

    cellSize = new int[SIZE_CLASSES];
    blockSizeClass = new byte[SIZE_CLASSES];
    cellsInBlock = new int[SIZE_CLASSES];
    blockHeaderSize = new int[SIZE_CLASSES];
    
    for (int sc = 0; sc < SIZE_CLASSES; sc++) {
      cellSize[sc] = getBaseCellSize(sc);
      for (byte blk = 0; blk < BlockAllocator.BLOCK_SIZE_CLASSES; blk++) {
	int avail = BlockAllocator.blockSize(blk) - FREE_LIST_HEADER_BYTES;
	int cells = avail/cellSize[sc];
	blockSizeClass[sc] = blk;
	cellsInBlock[sc] = cells;
	blockHeaderSize[sc] = FREE_LIST_HEADER_BYTES;
	if (((avail < PAGE_SIZE) && (cells*2 > MAX_CELLS)) ||
	    ((avail > (PAGE_SIZE>>1)) && (cells > MIN_CELLS)))
	  break;
      }
    }
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Allocation
  //
  public final void postAlloc(VM_Address cell, VM_Address block, int sizeClass,
			      int bytes, boolean inGC) throws VM_PragmaInline{}
  protected final void postExpandSizeClass(VM_Address block, int sizeClass){}
  protected final VM_Address advanceToBlock(VM_Address block, int sizeClass){
    return getFreeList(block);
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Collection
  //

  /**
   * Prepare for a collection.
   */
  public final void prepare(boolean time) { 
    flushFreeLists();
    if (Options.verbose > 2) processRootBufsAndCount(); else processRootBufs();
  }

  /**
   * Finish up after a collection.
   */
  public final void release(boolean time) {
    flushFreeLists();
    if (time) Statistics.rcIncTime.start();
    if (Options.verbose > 2) processIncBufsAndCount(); else processIncBufs();
    if (time) Statistics.rcIncTime.stop();
    VM_Interface.rendezvous(4400);
    if (time) Statistics.rcDecTime.start();
    processDecBufs();
    if (time) Statistics.rcDecTime.stop();
    if (Plan.REF_COUNT_CYCLE_DETECTION) {
      if (time) Statistics.cdTime.start();
      if (cycleDetector.collectCycles(time)) 
	processDecBufs();
      if (time) Statistics.cdTime.stop();
    }
    restoreFreeLists();
    
    if (Plan.REF_COUNT_SANITY_TRACING) rcSanityCheck();
  }

  /**
   * Process the increment buffers
   */
  private final void processIncBufs() {
    VM_Address tgt;
    while (!(tgt = incBuffer.pop()).isZero()) {
      rcSpace.increment(tgt);
    }
  }

  /**
   * Process the increment buffers and maintain statistics
   */
  private final void processIncBufsAndCount() {
    VM_Address tgt;
    incCounter = 0;
    while (!(tgt = incBuffer.pop()).isZero()) {
      rcSpace.increment(tgt);
      incCounter++;
    }
  }

  /**
   * Process the decrement buffers
   */
  private final void processDecBufs() {
    VM_Address tgt = VM_Address.zero();
    double tc = Plan.getTimeCap();
    double remaining =  tc - VM_Interface.now();
    double limit = tc - (remaining * (1 - DEC_TIME_FRACTION));
    decrementPhase = true;
    decCounter = 0;
    do {
      int count = 0;
      while (count < DEC_COUNT_QUANTA && !(tgt = decBuffer.pop()).isZero()) {
	decrement(tgt);
	count++;
      } 
      decCounter += count;
    } while (!tgt.isZero() && VM_Interface.now() < limit);
    decrementPhase = false;
  }

  /**
   * Process the root buffers, moving entries over to the decrement
   * buffers for the next GC.  FIXME this is inefficient
   */
  private final void processRootBufs() {
    VM_Address tgt;
    while (!(tgt = rootSet.pop()).isZero())
      decBuffer.push(tgt);
  }

  /**
   * Process the root buffers and maintain statistics.
   */
  private final void processRootBufsAndCount() {
    VM_Address tgt;
    rootCounter = 0;
    while (!(tgt = rootSet.pop()).isZero()) {
      decBuffer.push(tgt);
      rootCounter++;
    }
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Object processing and tracing
  //

  /**
   * Decrement the reference count of an object.  If the count drops
   * to zero, the release the object, performing recursive decremetns
   * and freeing the object.  If not, then if cycle detection is being
   * used, record this object as the possible source of a cycle of
   * garbage (all non-zero decrements are potential sources of new
   * cycles of garbage.
   *
   * @param object The object whose count is to be decremented
   */
  public final void decrement(VM_Address object) 
    throws VM_PragmaInline {
    int state = RCBaseHeader.decRC(object, true);
    if (state == RCBaseHeader.DEC_KILL)
      release(object);
    else if (Plan.REF_COUNT_CYCLE_DETECTION && state ==RCBaseHeader.DEC_BUFFER)
      cycleDetector.possibleCycleRoot(object);
  }

  /**
   * An object is dead, so before freeing it, scan the object for
   * recursive decrement (each outgoing pointer from this dead object
   * is now dead, so the targets must have their counts decremented).<p>
   *
   * If the object is being held in a buffer by the cycle detector,
   * then the object must not be freed.  It will be freed later when
   * the cycle detector processes its buffers.
   *
   * @param object The object to be released
   */
  private final void release(VM_Address object) 
    throws VM_PragmaInline {
    // this object is now dead, scan it for recursive decrement
    ScanObject.enumeratePointers(object, plan.decEnum);
    if (!Plan.REF_COUNT_CYCLE_DETECTION || !RCBaseHeader.isBuffered(object)) 
      free(object);
  }

  /**
   * Free an object.  First determine whether it is managed by the LOS
   * or the regular free list.  If managed by LOS, delegate freeing to
   * the LOS.  Otherwise, establish the cell, block and sizeclass for
   * this object and call the free method of our subclass.
   *
   * @param object The object to be freed.
   */
  public final void free(VM_Address object) 
    throws VM_PragmaInline {
    VM_Address ref = VM_Interface.refToAddress(object);
    byte space = VMResource.getSpace(ref);
    if (space == Plan.LOS_SPACE) {
      los.free(ref);
    } else {
      byte tag = VMResource.getTag(ref);
      
      VM_Address block = BlockAllocator.getBlockStart(ref, tag);
      int sizeClass = getBlockSizeClass(block);
      int index = (ref.diff(block.add(blockHeaderSize[sizeClass])).toInt())/cellSize[sizeClass];
      VM_Address cell = block.add(blockHeaderSize[sizeClass]).add(index*cellSize[sizeClass]);
      free(cell, block, sizeClass);
    }
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Methods relating to sanity tracing (tracing used to check
  // reference counts)
  //

  /**
   * Check the reference counts of all objects against those
   * established during the sanity scan.
   */
  private final void rcSanityCheck() {
    if (VM_Interface.VerifyAssertions) 
      VM_Interface._assert(Plan.REF_COUNT_SANITY_TRACING);
    VM_Address obj;
    int checked = 0;
    while (!(obj = tracingBuffer.pop()).isZero()) {
      checked++;
      int rc = RCBaseHeader.getRC(obj);
      int sanityRC = RCBaseHeader.getTracingRC(obj);
      RCBaseHeader.clearTracingRC(obj);
      if (rc != sanityRC) {
	Log.write("---> ");
	Log.write(checked);
	Log.write(" roots checked, RC mismatch: ");
	Log.write(obj); Log.write(" -> ");
	Log.write(rc); Log.write(" (rc) != ");
	Log.write(sanityRC); Log.writeln(" (sanity)");
	if (VM_Interface.VerifyAssertions) VM_Interface._assert(false);
      }
    }
  }

  /**
   * Set the mark bit appropriately in an immortal object so that the
   * traversal of immortal objects is performed correctly during
   * sanity scans.
   *
   * @param object An object just allocated to the immortal space
   */
  public final void postAllocImmortal(VM_Address object)
    throws VM_PragmaInline {
    if (Plan.REF_COUNT_SANITY_TRACING) {
      if (rcSpace.bootImageMark)
	RCBaseHeader.setBufferedBit(object);
      else
	RCBaseHeader.clearBufferedBit(object);
    }
  }

  /**
   * A boot or immortal object has been encountered during a root
   * scan.  Its mark bit needs to be set appropriately according to
   * the current state of the immortal mark bit.  Currently as a dirty
   * hack we overload the buffered bit for marking during sanity
   * scans.  FIXME
   *
   * @param object The immortal or boot image object encountered
   * during a root scan.
   */
  public void rootScan(VM_Address object) {
    if (VM_Interface.VerifyAssertions)
      VM_Interface._assert(Plan.REF_COUNT_SANITY_TRACING);
    // this object has been explicitly scanned as part of the root scanning
    // process.  Mark it now so that it does not get re-scanned.
    if (object.LE(Plan.RC_START) && object.GE(Plan.BOOT_START)) {
      if (rcSpace.bootImageMark)
	RCBaseHeader.setBufferedBit(object);
      else
	RCBaseHeader.clearBufferedBit(object);
    }
  }

  /**
   * Add an object to the tracing buffer (used for sanity
   * tracing---verifying ref counts through tracing).
   *
   * @param object The object to be added to the tracing buffer.
   */
  public final void addToTraceBuffer(VM_Address object) 
    throws VM_PragmaInline {
    if (VM_Interface.VerifyAssertions) 
      VM_Interface._assert(Plan.REF_COUNT_SANITY_TRACING);
    tracingBuffer.push(VM_Magic.objectAsAddress(object));
  }

  ////////////////////////////////////////////////////////////////////////////
  //
  // Misc
  //

  /**
   * Setter method for the purple counter.
   *
   * @param purple The new value for the purple counter.
   */
  public final void setPurpleCounter(int purple) {
    purpleCounter = purple;
  }

  /**
   * Print out statistics on increments, decrements, roots and
   * potential garbage cycles (purple objects).
   */
  public final void printStats() {
    Log.write("<GC "); Log.write(Statistics.gcCount); Log.write(" "); 
    Log.write(incCounter); Log.write(" incs, ");
    Log.write(decCounter); Log.write(" decs, ");
    Log.write(rootCounter); Log.write(" roots");
    if (Plan.REF_COUNT_CYCLE_DETECTION) {
      Log.write(", "); 
      Log.write(purpleCounter);Log.write(" purple");
    }
    Log.writeln(">");
  }


  /**
   * Print out timing info for last GC
   */
  public final void printTimes(boolean totals) {
    double time;
    time = (totals) ? Statistics.rcIncTime.sum() : Statistics.rcIncTime.lastMs();
    Log.write(" inc: "); Log.write(time);
    time = (totals) ? Statistics.rcDecTime.sum() : Statistics.rcDecTime.lastMs();
    Log.write(" dec: "); Log.write(time);
    if (Plan.REF_COUNT_CYCLE_DETECTION) {
      time = (totals) ? Statistics.cdTime.sum() : Statistics.cdTime.lastMs();
      Log.write(" cd: "); Log.write(time);
      cycleDetector.printTimes(totals);
    }
  }
}
