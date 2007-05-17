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
package org.mmtk.plan;

import org.mmtk.utility.Constants;
import org.mmtk.utility.statistics.Timer;
import org.mmtk.utility.options.Options;
import org.mmtk.utility.Log;

import org.mmtk.vm.VM;

import org.vmmagic.pragma.*;

/**
 * Phases of a garbage collection.
 * 
 * A complex phase is a sequence of phases.  They are constructed
 * from arrays of either the phases or phase IDs.
 * 
 * TODO write a replacePhase method.
 */
@Uninterruptible public final class ComplexPhase extends Phase
  implements Constants {

  /*
   * Instance fields
   */

  /**
   * The phases that comprise this phase.
   */
  final int[] subPhases;

  /**
   * Construct a complex phase from an array of phase IDs.
   * 
   * @param name The name of the phase.
   * @param subPhases The IDs of the supphases
   */
  public ComplexPhase(String name, int[] subPhases) {
    super(name);
    this.subPhases = subPhases;
  }

  /**
   * Construct a complex phase from an array of phase IDs, but using
   * the specified timer rather than creating one.
   * 
   * @param name The name of the phase.
   * @param timer The timer for this phase to contribute to.
   * @param subPhases The IDs of the supphases
   */
  public ComplexPhase(String name, Timer timer, int[] subPhases) {
    super(name, timer);
    this.subPhases = subPhases;
  }

  /**
   * Display a description of this phase, for debugging purposes.
   */
  protected void logPhase() {
    Log.write("complex phase ");
    Log.write(name);
    for (int subPhase : subPhases) {
      Log.write(" ");
      Log.write(getName(subPhase));
    }
    Log.writeln();
  }

  /**
   * Execute this phase, synchronizing initially.  Simply executes
   * the component phases in turn.
   * 
   * TODO are we oversynchronizing here ??
   */
  protected void delegatePhase() {
    int order = VM.collection.rendezvous(5000 + id);
    if (order == 1 && timer != null) timer.start();

    if (Options.verbose.getValue() >= 4) {
      Log.write("Delegating complex phase ");
      Log.writeln(name);
    }
    for (int subPhase : subPhases) {
      Phase.delegatePhase(subPhase);
    }

    if (order == 1 && timer != null) timer.stop();
  }

  /**
   * Replace a phase. For example to replace a placeholder
   * 
   * @param oldId The phase to replace.
   * @param newId The new phase.
   */
  public void replacePhase(int oldId, int newId) {
    for (int i = 0; i < subPhases.length; i++) {
      Phase p = getPhase(subPhases[i]);
      if (p.getId() == oldId) {
        // Replace
        subPhases[i] = newId;
      } else if (p instanceof ComplexPhase) {
        // Recurse
        ((ComplexPhase) p).replacePhase(oldId, newId);
      }
    }
  }
}
