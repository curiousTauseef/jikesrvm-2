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

/**
 * Use this exception when the opt compiler attempts to
 * compile/optimize a method containing a currently
 * unsupported (but expected) operation.
 * The main intended use is in prototype/incomplete optimizations
 * which may not handle every case, but which will eventually be
 * extended to handle the excepting case. If the unsupported operation
 * really is a serious problem, then one should use
 * an OptimzingCompilerException.
 *
 * We define this to be a non-fatal OPT_OptimizingCompilerException.
 */
public class OPT_OperationNotImplementedException extends OPT_OptimizingCompilerException {
  /** Support for exception serialization */
  static final long serialVersionUID = -7215437494493545076L;

  public OPT_OperationNotImplementedException(String s) {
    super(s, false);
  }
}



