/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 */
package org.mmtk.plan;

import org.mmtk.policy.CopySpace;
import org.mmtk.policy.MarkSweepLocal;
import org.mmtk.policy.MarkSweepSpace;
import org.mmtk.utility.alloc.Allocator;
import org.mmtk.utility.heap.FreeListVMResource;
import org.mmtk.utility.heap.VMResource;
import org.mmtk.vm.Assert;
import org.mmtk.vm.ObjectModel;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

/**
 * This class implements the functionality of a two-generation copying
 * collector where <b>the higher generation is a mark-sweep space</b>
 * (free list allocation, mark-sweep collection).  Nursery collections
 * occur when either the heap is full or the nursery is full.  The
 * nursery size is determined by an optional command line argument.
 * If undefined, the nursery size is "infinite", so nursery
 * collections only occur when the heap is full (this is known as a
 * flexible-sized nursery collector).  Thus both fixed and flexible
 * nursery sizes are supported.  Full heap collections occur when the
 * nursery size has dropped to a statically defined threshold,
 * <code>NURSERY_THRESHOLD</code><p>
 *
 * See the Jones & Lins GC book, chapter 7 for a detailed discussion
 * of generational collection and section 7.3 for an overview of the
 * flexible nursery behavior ("The Standard ML of New Jersey
 * collector"), or go to Appel's paper "Simple generational garbage
 * collection and fast allocation." SP&E 19(2):171--183, 1989.<p>
 *
 * All plans make a clear distinction between <i>global</i> and
 * <i>thread-local</i> activities.  Global activities must be
 * synchronized, whereas no synchronization is required for
 * thread-local activities.  Instances of Plan map 1:1 to "kernel
 * threads" (aka CPUs or in Jikes RVM, VM_Processors).  Thus instance
 * methods allow fast, unsychronized access to Plan utilities such as
 * allocation and collection.  Each instance rests on static resources
 * (such as memory and virtual memory resources) which are "global"
 * and therefore "static" members of Plan.  This mapping of threads to
 * instances is crucial to understanding the correctness and
 * performance proprties of this plan.
 *
 * $Id$
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
public class GenMS extends Generational implements Uninterruptible {

  /****************************************************************************
   *
   * Class variables
   */
  protected static final boolean copyMature = false;
  
  // virtual memory resources
  private static FreeListVMResource matureVM;

  // mature space collector
  private static MarkSweepSpace matureSpace;

  /****************************************************************************
   *
   * Instance variables
   */

  // allocators
  private MarkSweepLocal mature;

  /****************************************************************************
   *
   * Initialization
   */

  /**
   * Class initializer.  This is executed <i>prior</i> to bootstrap
   * (i.e. at "build" time).  This is where key <i>global</i>
   * instances are allocated.  These instances will be incorporated
   * into the boot image by the build process.
   */
  static {
    matureVM = new FreeListVMResource(MATURE_SPACE, "Mature", MATURE_START, MATURE_SIZE, VMResource.IN_VM, MarkSweepLocal.META_DATA_PAGES_PER_REGION);
    matureSpace = new MarkSweepSpace(matureVM, matureMR);
  }

  /**
   * Constructor
   */
  public GenMS() {
    super();
    mature = new MarkSweepLocal(matureSpace);
  }

  /****************************************************************************
   *
   * Allocation
   */

  /**
   * Allocate space (for an object) in the mature space
   *
   * @param bytes The size of the space to be allocated (in bytes)
   * @param align The requested alignment.
   * @param offset The alignment offset.
   * @return The address of the first byte of the allocated region
   */
  protected final Address matureAlloc(int bytes, int align, int offset) 
    throws InlinePragma {
    return mature.alloc(bytes, align, offset, false);
  }

  /**
   * Perform post-allocation initialization of an object
   *
   * @param object The newly allocated object
   */
  protected final void maturePostAlloc(Address object) 
    throws InlinePragma {
    matureSpace.initializeHeader(object);
  }

  /**
   * Allocate space for copying an object in the mature space (this
   * method <i>does not</i> copy the object, it only allocates space)
   *
   * @param bytes The size of the space to be allocated (in bytes)
   * @param align The requested alignment.
   * @param offset The alignment offset.
   * @return The address of the first byte of the allocated region
   */
  protected final Address matureCopy(int bytes, int align, int offset) 
    throws InlinePragma {
    return mature.alloc(bytes, align, offset, matureSpace.inMSCollection());
  }

  protected final byte getSpaceFromAllocator (Allocator a) {
    if (a == mature) return MATURE_SPACE;
    return super.getSpaceFromAllocator(a);
  }

  protected final Allocator getAllocatorFromSpace (byte s) {
    if (s == MATURE_SPACE) return mature;
    return super.getAllocatorFromSpace(s);
  }

  /****************************************************************************
   *
   * Collection
   */

  /**
   * Perform operations pertaining to the mature space with
   * <i>global</i> scope in preparation for a collection.  This is
   * called by <code>Generational</code>, which will ensure that
   * <i>only one thread</i> executes this.
   */
  protected final void globalMaturePrepare() {
    matureSpace.prepare(matureVM, matureMR);
  }

  /**
   * Perform operations pertaining to the mature space with
   * <i>thread-local</i> scope in preparation for a collection.  This
   * is called by <code>Generational</code>, which will ensure that
   * <i>all threads</i> execute this.
   */
  protected final void threadLocalMaturePrepare(int count) {
    if (fullHeapGC) mature.prepare();
  }

  /**
   * Perform operations pertaining to the mature space with
   * <i>thread-local</i> scope to clean up at the end of a collection.
   * This is called by <code>Generational</code>, which will ensure
   * that <i>all threads</i> execute this.<p>
   */
  protected final void threadLocalMatureRelease(int count) {
    if (fullHeapGC) mature.release();
  }

  /**
   * Perform operations pertaining to the mature space with
   * <i>global</i> scope to clean up at the end of a collection.  This
   * is called by <code>Generational</code>, which will ensure that
   * <i>only one</i> thread executes this.<p>
   */
  protected final void globalMatureRelease() {
    matureSpace.release();
  }

  /****************************************************************************
   *
   * Object processing and tracing
   */

  /**
   * Trace a reference into the mature space during GC.  This simply
   * involves the <code>traceObject</code> method of the mature
   * collector.
   *
   * @param obj The object reference to be traced.  This is <i>NOT</i> an
   * interior pointer.
   * @return The possibly moved reference.
   */
  protected static final Address traceMatureObject(byte space,
                                                   Address obj,
                                                   Address addr) {
    if (Assert.VERIFY_ASSERTIONS && space != MATURE_SPACE)
      spaceFailure(obj, space, "Plan.traceMatureObject()");
    return matureSpace.traceObject(obj);
  }

  /**  
   * Perform any post-copy actions.  In this case set the mature space
   * mark bit.
   *
   * @param object The newly allocated object
   * @param typeRef the type reference for the instance being created
   * @param bytes The size of the space to be allocated (in bytes)
   */
  public final void postCopy(Address object, Address typeRef, int bytes)
    throws InlinePragma {
    matureSpace.writeMarkBit(object);
    MarkSweepLocal.liveObject(object);
  }

  /**
   * Forward the mature space object referred to by a given address
   * and update the address if necessary.  This <i>does not</i>
   * enqueue the referent for processing; the referent must be
   * explicitly enqueued if it is to be processed.<p>
   *
   * <i>In this case do nothing since the mature space is non-copying.</i>
   *
   * @param location The location whose referent is to be forwarded if
   * necessary.  The location will be updated if the referent is
   * forwarded.
   * @param object The referent object.
   * @param space The space in which the referent object resides.
   */
  protected static void forwardMatureObjectLocation(Address location,
                                                    Address object,
                                                    byte space) {}

  /**
   * If the object in question has been forwarded, return its
   * forwarded value.  Mature objects are never forwarded in this
   * collector, so this method is a no-op.<p>
   *
   * @param object The object which may have been forwarded.
   * @param space The space in which the object resides.
   * @return The forwarded value for <code>object</code>.
   */
  public static final Address getForwardedMatureReference(Address object,
                                                          byte space) {
    return object;
  }

  /**
   * Return true if the object resides in a copying space (in this
   * case only nursery objects are in a copying space).
   *
   * @param obj The object in question
   * @return True if the object resides in a copying space.
   */
  public final static boolean isCopyObject(Address obj) {
    Address addr = ObjectModel.refToAddress(obj);
    return (addr.GE(NURSERY_START) && addr.LE(HEAP_END));
  }

  /**
   * Return true if <code>obj</code> is a live object.
   *
   * @param obj The object in question
   * @return True if <code>obj</code> is a live object.
   */
  public final static boolean isLive(Address obj) {
    if (obj.isZero()) return false;
    Address addr = ObjectModel.refToAddress(obj);
    byte space = VMResource.getSpace(addr);
    switch (space) {
    case NURSERY_SPACE:   return CopySpace.isLive(obj);
    case MATURE_SPACE:    return (!fullHeapGC) || matureSpace.isLive(obj);
    case LOS_SPACE:       return losSpace.isLive(obj);
    case IMMORTAL_SPACE:  return true;
    case BOOT_SPACE:        return true;
    case META_SPACE:        return true;
    default:
      if (Assert.VERIFY_ASSERTIONS) spaceFailure(obj, space, "Plan.isLive()");
      return false;
    }
  }

  /****************************************************************************
   *
   * Miscellaneous
   */

  /**
   * Show the status of the mature allocator.
   */
  protected final void showMature() {
    mature.show();
  }
}