/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2002
 */
package org.mmtk.plan;

import org.mmtk.policy.ImmortalSpace;
import org.mmtk.utility.alloc.AllocAdvice;
import org.mmtk.utility.alloc.Allocator;
import org.mmtk.utility.alloc.BumpPointer;
import org.mmtk.utility.CallSite;
import org.mmtk.utility.heap.*;
import org.mmtk.utility.scan.MMType;
import org.mmtk.vm.Assert;
import org.mmtk.vm.Memory;
import org.mmtk.vm.ObjectModel;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

/**
 * This class implements a simple allocator without a collector.
 *
 * $Id$
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @version $Revision$
 * @date $Date$
 */
public class NoGC extends StopTheWorldGC implements Uninterruptible {

  /****************************************************************************
   *
   * Class variables
   */
  public static final boolean MOVES_OBJECTS = false;
  public static final int GC_HEADER_BITS_REQUIRED = 0;
  public static final int GC_HEADER_BYTES_REQUIRED = 0;

  // virtual memory resources
  private static MonotoneVMResource defaultVM;

  // memory resources
  private static MemoryResource defaultMR;

  // Allocators
  public static final byte DEFAULT_SPACE = 0;

  // Miscellaneous constants
  private static final int POLL_FREQUENCY = DEFAULT_POLL_FREQUENCY;
  
  // Memory layout constants
  public  static final long            AVAILABLE = Memory.MAXIMUM_MAPPABLE.diff(PLAN_START).toLong();
  private static final Extent    DEFAULT_SIZE = Extent.fromIntZeroExtend((int)(AVAILABLE));
  public  static final Extent        MAX_SIZE = DEFAULT_SIZE;

  private static final Address  DEFAULT_START = PLAN_START;
  private static final Address    DEFAULT_END = DEFAULT_START.add(DEFAULT_SIZE);
  private static final Address       HEAP_END = DEFAULT_END;


  /****************************************************************************
   *
   * Instance variables
   */

  // allocators
  private BumpPointer def;

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
    defaultMR = new MemoryResource("def", POLL_FREQUENCY);
    defaultVM = new MonotoneVMResource(DEFAULT_SPACE, "default", defaultMR, DEFAULT_START, DEFAULT_SIZE, VMResource.IN_VM);

    addSpace(DEFAULT_SPACE, "Default Space");
  }

  /**
   * Constructor
   */
  public NoGC() {
    def = new BumpPointer(defaultVM);
  }

  /**
   * The boot method is called early in the boot process before any
   * allocation.
   */
  public static final void boot()
    throws InterruptiblePragma {
    StopTheWorldGC.boot();
  }


  /****************************************************************************
   *
   * Allocation
   */

  /**
   * Allocate space (for an object)
   *
   * @param bytes The size of the space to be allocated (in bytes)
   * @param align The requested alignment
   * @param offset The alignment offset
   * @param allocator The allocator number to be used for this allocation
   * @return The address of the first byte of the allocated region
   */
  public final Address alloc(int bytes, int align, int offset, int allocator)
    throws InlinePragma {
    switch (allocator) {
    case      LOS_SPACE:  // no los, so use default allocator
    case  DEFAULT_SPACE:  return def.alloc(bytes, align, offset);
    case IMMORTAL_SPACE:  return immortal.alloc(bytes, align, offset);
    default:
      if (Assert.VERIFY_ASSERTIONS) Assert.fail("No such allocator"); 
      return Address.zero();
    }
  }

  /**
   * Perform post-allocation actions.  For many allocators none are
   * required.
   *
   * @param ref The newly allocated object
   * @param typeRef the type reference for the instance being created
   * @param bytes The size of the space to be allocated (in bytes)
   * @param allocator The allocator number to be used for this allocation
   */
  public final void postAlloc(Address ref, Address typeRef, int bytes,
                              int allocator)
    throws InlinePragma {
    switch (allocator) {
    case      LOS_SPACE: // no los, so use default allocator
    case  DEFAULT_SPACE: return;
    case IMMORTAL_SPACE: return;
    default:
      if (Assert.VERIFY_ASSERTIONS) Assert.fail("No such allocator"); 
    }
  }

  /**
   * Allocate space for copying an object (this method <i>does not</i>
   * copy the object, it only allocates space)
   *
   * @param original A reference to the original object
   * @param bytes The size of the space to be allocated (in bytes)
   * @param align The requested alignment.
   * @param offset The alignment offset.
   * @return The address of the first byte of the allocated region
   */
  public final Address allocCopy(Address original, int bytes, 
                                    int align, int offset) 
    throws InlinePragma {
    Assert.fail("no allocCopy in noGC");
    //    return Address.zero();   // Trips some intel opt compiler bug...
    return Address.max();
  }

  /**  
   * Perform any post-copy actions.  In this case nothing is required.
   *
   * @param ref The newly allocated object
   * @param typeRef The type reference for the instance being created
   * @param tib The TIB of the newly allocated object
   * @param bytes The size of the space to be allocated (in bytes)
   */
  public final void postCopy(Address ref, Address typeRef, int bytes) {
    Assert.fail("no postCopy in noGC");
  } 

  protected final byte getSpaceFromAllocator (Allocator a) {
    if (a == def) return DEFAULT_SPACE;
    return super.getSpaceFromAllocator(a);
  }

  protected final Allocator getAllocatorFromSpace (byte s) {
    if (s == DEFAULT_SPACE) return def;
    return super.getAllocatorFromSpace(s);
  }


  /**
   * Give the compiler/runtime statically generated alloction advice
   * which will be passed to the allocation routine at runtime.
   *
   * @param type The type id of the type being allocated
   * @param bytes The size (in bytes) required for this object
   * @param callsite Information identifying the point in the code
   * where this allocation is taking place.
   * @param hint A hint from the compiler as to which allocator this
   * site should use.
   * @return Allocation advice to be passed to the allocation routine
   * at runtime
   */
  public final AllocAdvice getAllocAdvice(MMType type, int bytes,
                                          CallSite callsite,
                                          AllocAdvice hint) {
    return null;
  }

  /**
   * Return the initial header value for a newly allocated LOS
   * instance.
   *
   * @param bytes The size of the newly created instance in bytes.
   * @return The inital header value for the new instance.
   */
  public static final Word getInitialHeaderValue(int bytes)
    throws InlinePragma {
    Assert._assert(false);
    return Word.zero();
  }

  /**
   * This method is called periodically by the allocation subsystem
   * (by default, each time a page is consumed), and provides the
   * collector with an opportunity to collect.<p>
   *
   * We trigger a collection whenever an allocation request is made
   * that would take the number of pages in use (committed for use)
   * beyond the number of pages available.  Collections are triggered
   * through the runtime, and ultimately call the
   * <code>collect()</code> method of this class or its superclass.<p>
   *
   * This method is clearly interruptible since it can lead to a GC.
   * However, the caller is typically uninterruptible and this fiat allows 
   * the interruptibility check to work.  The caveat is that the caller 
   * of this method must code as though the method is interruptible. 
   * In practice, this means that, after this call, processor-specific
   * values must be reloaded.
   *
   * @param mustCollect True if a this collection is forced.
   * @param mr The memory resource that triggered this collection.
   * @return True if a collection is triggered
   */
  public final boolean poll(boolean mustCollect, MemoryResource mr) 
    throws LogicallyUninterruptiblePragma {
    if (getPagesReserved() > getTotalPages())
      error("Out of memory");
    return false;
  }

  /**
   * Perform operations with <i>global</i> scope in preparation for a
   * collection.  This is called by <code>StopTheWorld</code>, which will
   * ensure that <i>only one thread</i> executes this.<p>
   *
   * In this case, it means flipping semi-spaces, resetting the
   * semi-space memory resource, and preparing each of the collectors.
   */
  protected final void globalPrepare() {
    Assert.fail("\nGC Triggered in NoGC Plan.  Have you set -X:gc:ignoreSystemGC=true?");
  }

  /**
   * Perform operations with <i>thread-local</i> scope in preparation
   * for a collection.  This is called by <code>StopTheWorld</code>, which
   * will ensure that <i>all threads</i> execute this.<p>
   *
   * In this case, it means resetting the semi-space and large object
   * space allocators.
   */
  protected final void threadLocalPrepare(int count) {
    Assert._assert(false);
  }

  /**
   * Perform operations with <i>thread-local</i> scope to clean up at
   * the end of a collection.  This is called by
   * <code>StopTheWorld</code>, which will ensure that <i>all threads</i>
   * execute this.<p>
   *
   * In this case, it means releasing the large object space (which
   * triggers the sweep phase of the mark-sweep collector used by the
   * LOS).
   */
  protected final void threadLocalRelease(int count) {
    Assert._assert(false);
  }

  /**
   * Perform operations with <i>global</i> scope to clean up at the
   * end of a collection.  This is called by <code>StopTheWorld</code>,
   * which will ensure that <i>only one</i> thread executes this.<p>
   *
   * In this case, it means releasing each of the spaces and checking
   * whether the GC made progress.
   */
  protected final void globalRelease() {
    Assert._assert(false);
  }


  /****************************************************************************
   *
   * Object processing and tracing
   */

  /**
   * Trace a reference during GC.  This involves determining which
   * collection policy applies and calling the appropriate
   * <code>trace</code> method.
   *
   * @param obj The object reference to be traced.  This is <i>NOT</i> an
   * interior pointer.
   * @return The possibly moved reference.
   */
  public static final Address traceObject (Address obj) 
    throws InlinePragma {
    Assert._assert(false);
    return obj;
  }

  /**
   * Trace a reference during GC.  This involves determining which
   * collection policy applies and calling the appropriate
   * <code>trace</code> method.
   *
   * @param obj The object reference to be traced.  This is <i>NOT</i>
   * an interior pointer.
   * @param root True if this reference to <code>obj</code> was held
   * in a root.
   * @return The possibly moved reference.
   */
  public static final Address traceObject(Address obj, boolean root) {
    Assert._assert(false);
    return Address.zero();
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
    case     LOS_SPACE:   return true;
    case DEFAULT_SPACE:   return true;
    case IMMORTAL_SPACE:  return true;
    case BOOT_SPACE:      return true;
    case META_SPACE:      return true;
    default:
      if (Assert.VERIFY_ASSERTIONS) spaceFailure(obj, space, "Plan.isLive()");
      return false;
    }
  }


  public static boolean willNotMove (Address obj) {
    return true;
  }

  /****************************************************************************
   *
   * Space management
   */

  /**
   * Return the number of pages reserved for use given the pending
   * allocation.  This <i>includes</i> space reserved for copying.
   *
   * @return The number of pages reserved given the pending
   * allocation, including space reserved for copying.
   */
  protected static final int getPagesReserved() {
    int pages = defaultMR.reservedPages();
    pages += immortalMR.reservedPages();
    pages += metaDataMR.reservedPages();
    return pages;
  }

  /**
   * Return the number of pages reserved for use given the pending
   * allocation.  This is <i>exclusive of</i> space reserved for
   * copying.
   *
   * @return The number of pages reserved given the pending
   * allocation, excluding space reserved for copying.
   */
  protected static final int getPagesUsed() {
    int pages = defaultMR.reservedPages();
    pages += immortalMR.reservedPages();
    pages += metaDataMR.reservedPages();
    return pages;
  }

  /**
   * Return the number of pages available for allocation, <i>assuming
   * all future allocation is to the semi-space</i>.
   *
   * @return The number of pages available for allocation, <i>assuming
   * all future allocation is to the semi-space</i>.
   */
  protected static final int getPagesAvail() {
    return (getTotalPages() - defaultMR.reservedPages() 
            - immortalMR.reservedPages());
  }


  /****************************************************************************
   *
   * Miscellaneous
   */

  /**
   * Show the status of each of the allocators.
   */
  public final void show() {
    def.show();
    immortal.show();
  }
}