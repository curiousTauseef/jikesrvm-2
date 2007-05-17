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
package org.jikesrvm.compilers.opt;

import java.util.Enumeration;
import org.jikesrvm.ArchitectureSpecific.OPT_PhysicalRegisterSet;
import org.jikesrvm.compilers.opt.ir.OPT_IR;
import org.jikesrvm.compilers.opt.ir.OPT_Register;

/**
 * The register allocator currently caches a bunch of state in the IR;
 * This class provides accessors to this state.
 * TODO: Consider caching the state in a lookaside structure.
 * TODO: Currently, the physical registers are STATIC! fix this.
 */
public class OPT_RegisterAllocatorState {

  /**
   *  Resets the physical register info
   */
  static void resetPhysicalRegisters(OPT_IR ir) {
    OPT_PhysicalRegisterSet phys = ir.regpool.getPhysicalRegisterSet();
    for (Enumeration<OPT_Register> e = phys.enumerateAll(); e.hasMoreElements();) {
      OPT_Register reg = e.nextElement();
      reg.deallocateRegister();
      reg.mapsToRegister = null;  // mapping from real to symbolic
      //    putPhysicalRegResurrectList(reg, null);
      reg.defList = null;
      reg.useList = null;
      setSpill(reg, 0);
    }
  }

  /**
   * Special use of scratchObject field as "resurrect lists" for real registers
   * TODO: use another field for safety; scratchObject is also used by
   *  clan OPT_LinearScanLiveAnalysis
   */
  /*
  static void putPhysicalRegResurrectList(OPT_Register r,
                                          OPT_LinearScanLiveInterval li) {
    if (VM.VerifyAssertions) VM._assert(r.isPhysical());
    r.scratchObject = li;
  }
  */
  /**
   *
   * Special use of scratchObject field as "resurrect lists" for real registers
   * TODO: use another field for safety; scratchObject is also used by
   *  clan OPT_LinearScanLiveAnalysis
   */
  /*
  static OPT_LinearScanLiveInterval getPhysicalRegResurrectList(OPT_Register r) {
    if (VM.VerifyAssertions) VM._assert(r.isPhysical());
    return (OPT_LinearScanLiveInterval) r.scratchObject;
  }
  */
  static void setSpill(OPT_Register reg, int spill) {
    reg.spillRegister();
    reg.scratch = spill;
  }

  public static int getSpill(OPT_Register reg) {
    return reg.scratch;
  }

  /**
   * Record that register A and register B are associated with each other
   * in a bijection.
   *
   * The register allocator uses this state to indicate that a symbolic
   * register is presently allocated to a physical register.
   */
  static void mapOneToOne(OPT_Register A, OPT_Register B) {
    OPT_Register aFriend = getMapping(A);
    OPT_Register bFriend = getMapping(B);
    if (aFriend != null) {
      aFriend.mapsToRegister = null;
    }
    if (bFriend != null) {
      bFriend.mapsToRegister = null;
    }
    A.mapsToRegister = B;
    B.mapsToRegister = A;
  }

  /**
   * @return the register currently mapped 1-to-1 to r
   */
  static OPT_Register getMapping(OPT_Register r) {
    return r.mapsToRegister;
  }

  /**
   * Clear any 1-to-1 mapping for register R.
   */
  static void clearOneToOne(OPT_Register r) {
    if (r != null) {
      OPT_Register s = getMapping(r);
      if (s != null) {
        s.mapsToRegister = null;
      }
      r.mapsToRegister = null;
    }
  }
}
