/*
 * (C) Copyright IBM Corp. 2001
 */
//$Id$
/*
 * @author Perry Cheng
 */

import com.ibm.JikesRVM.VM_PragmaNoInline;
//-#if RVM_WITH_JMTK
import com.ibm.JikesRVM.memoryManagers.JMTk.Plan;
//-#endif

class LargeAlloc {

  static long allocSize = 0;  // in megabytes
  static int itemSize = 16 * 1024; 
  static int sizeCount = 10;
  static double sizeRatio = 1.5;
  public static byte [] junk;

  public static void main(String args[])  throws Throwable {
    boolean base = true;
    if (args.length == 0)
      System.out.println("No argument.  Assuming base");
    if (args[0].compareTo("opt") == 0 ||
	args[0].compareTo("perf") == 0)
      base = true;
    allocSize = base ? 500 : 3000;
    runTest();
  }


  public static void runTest() throws Throwable {

    System.out.println("LargeAlloc running with " + allocSize + " Mb of allocation");
    System.out.println("Run with verbose GC on and make sure space accounting is not leaking");
    System.out.println();

    //-#if RVM_WITH_JMTK
    Plan.verbose = 3;
    //-#endif

    long lastUsed = 0;
    long used = 0;
    long limit = allocSize * 1024 * 1024;
    long start = System.currentTimeMillis();
    System.gc();
    long startUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    while (used < limit) {
      int curSize = itemSize;
      for (int i=0; i<sizeCount; i++) {
	junk = new byte[curSize];
	used += itemSize;
	curSize = (int) (curSize * sizeRatio);
      }
      if (used - lastUsed > 100 * 1024 * 1024) {
	long cur = System.currentTimeMillis();
	System.out.println("Allocated " + (used >> 20) + " Mb at time " + ((cur - start) / 1000.0) + " sec");
	lastUsed = used;
      }
    }
    System.gc();
    long endUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    System.out.print("\nOverall: after allocation, usedMemory has increased by ");
    System.out.println(((endUsed - startUsed) / (1024.0 * 1024.0)) + " Mb");
  }


}
