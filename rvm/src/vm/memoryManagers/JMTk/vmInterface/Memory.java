/*
 * (C) Copyright Department of Computer Science,
 * Australian National University. 2004
 *
 * (C) Copyright IBM Corp. 2001, 2003
 */
package org.mmtk.vm;

import com.ibm.JikesRVM.VM;
import com.ibm.JikesRVM.VM_BootRecord;
import com.ibm.JikesRVM.VM_Magic;
import com.ibm.JikesRVM.VM_Memory;

import org.vmmagic.unboxed.*;
import org.vmmagic.pragma.*;

/**
 * $Id$ 
 *
 * @author <a href="http://cs.anu.edu.au/~Steve.Blackburn">Steve Blackburn</a>
 * @author Perry Cheng
 *
 * @version $Revision$
 * @date $Date$
 */
public class Memory implements Constants, Uninterruptible {
 /**
   * Returns the start of the boot image.
   *
   * @return the address of the start of the boot image
   */
  public static Address bootImageStart() throws UninterruptiblePragma {
    return  VM_BootRecord.the_boot_record.bootImageStart;
  }

  /**
   * Return the end of the boot image.
   *
   * @return the address of the end of the boot image
   */
  public static Address bootImageEnd() throws UninterruptiblePragma {
    return  VM_BootRecord.the_boot_record.bootImageEnd;
  }

  /**
   * The address of the start of the boot image.
   */
  public static final Address bootImageAddress = 
    //-#if RVM_FOR_32_ADDR
    Address.fromIntZeroExtend
    //-#elif RVM_FOR_64_ADDR
    Address.fromLong
    //-#endif
    (
     //-#value BOOTIMAGE_LOAD_ADDRESS
     );

  /**
   * The address in virtual memory that is the highest that can be mapped.
   */
  public static Address MAXIMUM_MAPPABLE = 
    //-#if RVM_FOR_32_ADDR
    Address.fromIntZeroExtend
    //-#elif RVM_FOR_64_ADDR
    Address.fromLong
    //-#endif
    (
     //-#value MAXIMUM_MAPPABLE_ADDRESS
     );


  /**
   * Sets the range of addresses associated with a heap.
   *
   * @param id the heap identifier
   * @param start the address of the start of the heap
   * @param end the address of the end of the heap
   */
  public static void setHeapRange(int id, Address start, Address end)
    throws UninterruptiblePragma {
    VM_BootRecord.the_boot_record.setHeapRange(id, start, end);
  }

 /**
   * Maps an area of virtual memory.
   *
   * @param start the address of the start of the area to be mapped
   * @param size the size, in bytes, of the area to be mapped
   * @return 0 if successful, otherwise the system errno
   */
  public static int mmap(Address start, int size) {
    Address result = VM_Memory.mmap(start, Extent.fromIntZeroExtend(size),
                                       VM_Memory.PROT_READ | VM_Memory.PROT_WRITE | VM_Memory.PROT_EXEC, 
                                       VM_Memory.MAP_PRIVATE | VM_Memory.MAP_FIXED | VM_Memory.MAP_ANONYMOUS);
    if (result.EQ(start)) return 0;
    if (result.GT(Address.fromIntZeroExtend(127))) {
      VM.sysWrite("mmap with MAP_FIXED on ", start);
      VM.sysWriteln(" returned some other address", result);
      VM.sysFail("mmap with MAP_FIXED has unexpected behavior");
    }
    return result.toInt();
  }
  
  /**
   * Protects access to an area of virtual memory.
   *
   * @param start the address of the start of the area to be mapped
   * @param size the size, in bytes, of the area to be mapped
   * @return <code>true</code> if successful, otherwise
   * <code>false</code>
   */
  public static boolean mprotect(Address start, int size) {
    return VM_Memory.mprotect(start, Extent.fromIntZeroExtend(size),
                              VM_Memory.PROT_NONE);
  }

  /**
   * Allows access to an area of virtual memory.
   *
   * @param start the address of the start of the area to be mapped
   * @param size the size, in bytes, of the area to be mapped
   * @return <code>true</code> if successful, otherwise
   * <code>false</code>
   */
  public static boolean munprotect(Address start, int size) {
    return VM_Memory.mprotect(start, Extent.fromIntZeroExtend(size),
                              VM_Memory.PROT_READ | VM_Memory.PROT_WRITE | VM_Memory.PROT_EXEC);
  }

  /**
   * Zero a region of memory.
   * @param start Start of address range (inclusive)
   * @param len Length in bytes of range to zero
   * Returned: nothing
   */
  public static void zero(Address start, Extent len) {
    VM_Memory.zero(start,len);
  }

  /**
   * Zero a range of pages of memory.
   * @param start Start of address range (must be a page address)
   * @param len Length in bytes of range (must be multiple of page size)
   */
  public static void zeroPages(Address start, int len) {
      /* AJG: Add assertions to check conditions documented above. */
    VM_Memory.zeroPages(start,len);
  }

  /**
   * Logs the contents of an address and the surrounding memory to the
   * error output.
   *
   * @param start the address of the memory to be dumped
   * @param beforeBytes the number of bytes before the address to be
   * included
   * @param afterBytes the number of bytes after the address to be
   * included
   */
  public static void dumpMemory(Address start, int beforeBytes,
                                int afterBytes) {
    VM_Memory.dumpMemory(start,beforeBytes,afterBytes);
  }

  /*
   * Utilities from the VM class
   */

  public static void sync() throws InlinePragma {
    VM_Magic.sync();
  }

  public static void isync() throws InlinePragma {
    VM_Magic.isync();
  }
}