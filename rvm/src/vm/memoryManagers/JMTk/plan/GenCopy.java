/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 */
package org.mmtk.plan;

import org.mmtk.policy.CopySpace;
import org.mmtk.policy.ImmortalSpace;
import org.mmtk.utility.alloc.Allocator;
import org.mmtk.utility.alloc.BumpPointer;
import org.mmtk.utility.Log;
import org.mmtk.utility.scan.MMType;
import org.mmtk.utility.heap.MonotoneVMResource;
import org.mmtk.utility.heap.VMResource;
import org.mmtk.vm.Assert;
import org.mmtk.vm.ObjectModel;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

/**
 * This class implements the functionality of a standard
 * two-generation copying collector.  Nursery collections occur when
 * either the heap is full or the nursery is full.  The nursery size
 * is determined by an optional command line argument.  If undefined,
 * the nursery size is "infinite", so nursery collections only occur
 * when the heap is full (this is known as a flexible-sized nursery
 * collector).  Thus both fixed and flexible nursery sizes are
 * supported.  Full heap collections occur when the nursery size has
 * dropped to a statically defined threshold,
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
public class GenCopy extends Generational implements Uninterruptible {

  /****************************************************************************
   *
   * Class variables
   */
  protected static final boolean copyMature = true;

  // virtual memory resources
  private static MonotoneVMResource mature0VM;
  private static MonotoneVMResource mature1VM;

  // GC state
  private static boolean hi = false; // True if copying to "higher" semispace 

  // Memory layout constants
  public static final byte LOW_MATURE_SPACE = 10;
  public static final byte HIGH_MATURE_SPACE = 11;
  private static final Address MATURE_LO_START = MATURE_START;
  private static final Address MATURE_HI_START = MATURE_START.add(MATURE_SS_SIZE);

  /****************************************************************************
   *
   * Instance variables
   */

  // allocators
  private BumpPointer mature;

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
    mature0VM  = new MonotoneVMResource(LOW_MATURE_SPACE, "Higher gen lo", matureMR, MATURE_LO_START, MATURE_SS_SIZE, VMResource.MOVABLE);
    mature1VM  = new MonotoneVMResource(HIGH_MATURE_SPACE, "Higher gen hi", matureMR, MATURE_HI_START, MATURE_SS_SIZE, VMResource.MOVABLE);
  }

  /**
   * Constructor
   */
  public GenCopy() {
    super();
    mature = new BumpPointer(mature0VM);
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
    return mature.alloc(bytes, align, offset);
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
    return mature.alloc(bytes, align, offset);
  }

  /**
   * Perform post-allocation initialization of an object
   *
   * @param object The newly allocated object
   */
  protected final void maturePostAlloc(Address object) 
    throws InlinePragma {
    // nothing to be done
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
    if (fullHeapGC) {
      matureMR.reset(); // reset the nursery semispace memory resource
      hi = !hi;         // flip the semi-spaces
    }
  }

  /**
   * Perform operations pertaining to the mature space with
   * <i>thread-local</i> scope in preparation for a collection.  This
   * is called by <code>Generational</code>, which will ensure that
   * <i>all threads</i> execute this.
   */
  protected final void threadLocalMaturePrepare(int count) {
    if (fullHeapGC) mature.rebind(((hi) ? mature1VM : mature0VM)); 
  }

  /**
   * Perform operations pertaining to the mature space with
   * <i>thread-local</i> scope to clean up at the end of a collection.
   * This is called by <code>Generational</code>, which will ensure
   * that <i>all threads</i> execute this.<p>
   */
  protected final void threadLocalMatureRelease(int count) {
  } // do nothing

  /**
   * Perform operations pertaining to the mature space with
   * <i>global</i> scope to clean up at the end of a collection.  This
   * is called by <code>Generational</code>, which will ensure that
   * <i>only one</i> thread executes this.<p>
   */
  protected final void globalMatureRelease() {
    if (fullHeapGC) ((hi) ? mature0VM : mature1VM).release();
  }

  /****************************************************************************
   *
   * Object processing and tracing
   */

  /**
   * Forward the mature space object referred to by a given address
   * and update the address if necessary.  This <i>does not</i>
   * enqueue the referent for processing; the referent must be
   * explicitly enqueued if it is to be processed.<p>
   *
   * @param location The location whose referent is to be forwarded if
   * necessary.  The location will be updated if the referent is
   * forwarded.
   * @param object The referent object.
   * @param space The space in which the referent object resides.
   */
  protected static final void forwardMatureObjectLocation(Address location,
                                                          Address object,
                                                          byte space) {
    Assert._assert(fullHeapGC);
    if ((hi && space == LOW_MATURE_SPACE) || 
        (!hi && space == HIGH_MATURE_SPACE))
      location.store(CopySpace.forwardObject(object));
  }

  /**
   * If the object in question has been forwarded, return its
   * forwarded value.<p>
   *
   * @param object The object which may have been forwarded.
   * @param space The space in which the object resides.
   * @return The forwarded value for <code>object</code>.
   */
  public static final Address getForwardedMatureReference(Address object,
                                                      byte space) {
    Assert._assert(fullHeapGC);
    if ((hi && space == LOW_MATURE_SPACE) || 
        (!hi && space == HIGH_MATURE_SPACE)) {
      Assert._assert(CopySpace.isForwarded(object));
      return CopySpace.getForwardingPointer(object);
    } else {
      return object;
    }
  }

  /**
   * Trace a reference into the mature space during GC.  This involves
   * determining whether the instance is in from space, and if so,
   * calling the <code>traceObject</code> method of the Copy
   * collector.
   *
   * @param obj The object reference to be traced.  This is <i>NOT</i> an
   * interior pointer.
   * @return The possibly moved reference.
   */
  protected static final Address traceMatureObject(byte space,
                                                      Address obj,
                                                      Address addr) {
    if (Assert.VERIFY_ASSERTIONS && space != LOW_MATURE_SPACE
        && space != HIGH_MATURE_SPACE)
      spaceFailure(obj, space, "Plan.traceMatureObject()");
    if ((!IGNORE_REMSET || fullHeapGC) && ((hi && addr.LT(MATURE_HI_START)) ||
					   (!hi && addr.GE(MATURE_HI_START))))
      return CopySpace.traceObject(obj);
    else if (IGNORE_REMSET)
      CopySpace.markObject(obj, ImmortalSpace.immortalMarkState);
    return obj;
  }

  /**  
   * Perform any post-copy actions.
   *
   * @param ref The newly allocated object
   * @param typeRef the type reference for the instance being created
   * @param bytes The size of the space to be allocated (in bytes)
   */
  public final void postCopy(Address ref, Address typeRef, int size)
    throws InlinePragma {
    CopySpace.clearGCBits(ref);
    if (IGNORE_REMSET)
      ImmortalSpace.postAlloc(ref);
  }

  /**
   * Return true if the object resides in a copying space (in this
   * case mature and nursery objects are in a copying space).
   *
   * @param obj The object in question
   * @return True if the object resides in a copying space.
   */
  public static final boolean isCopyObject(Address base) {
    Address addr = ObjectModel.refToAddress(base);
    return (addr.GE(MATURE_START) && addr.LE(HEAP_END));
  }

  /**
   * Return true if <code>obj</code> is a live object.
   *
   * @param obj The object in question
   * @return True if <code>obj</code> is a live object.
   */
  public static final boolean isLive(Address obj) {
    if (obj.isZero()) return false;
    Address addr = ObjectModel.refToAddress(obj);
    byte space = VMResource.getSpace(addr);
    switch (space) {
    case NURSERY_SPACE:       return CopySpace.isLive(obj);
    case LOW_MATURE_SPACE:    return (!fullHeapGC) || CopySpace.isLive(obj);
    case HIGH_MATURE_SPACE:   return (!fullHeapGC) || CopySpace.isLive(obj);
    case LOS_SPACE:           return losSpace.isLive(obj);
    case IMMORTAL_SPACE:      return true;
    case BOOT_SPACE:          return true;
    case META_SPACE:          return true;
    default:
      if (Assert.VERIFY_ASSERTIONS) spaceFailure(obj, space, "Plan.isLive()");
      return false;
    }
  }

  public static boolean willNotMove (Address obj) {
   boolean movable = VMResource.refIsMovable(obj);
   if (!movable) return true;
   Address addr = ObjectModel.refToAddress(obj);
   return (hi ? mature1VM : mature0VM).inRange(addr);
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