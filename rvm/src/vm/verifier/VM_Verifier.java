/*
 * (C) Copyright IBM Corp. 2002
 */
//$Id$

/**
 * Verify a method or a class
 *
 * TODO: add javadoc comments
 *
 * @see VM_BasicBlock.java
 * @see VM_BuildBB.java
 *
 * @author Lingli Zhang  06/20/02
 */
import java.util.Stack;
import java.lang.Exception;

public class VM_Verifier  implements VM_BytecodeConstants , VM_JBCOpcodeName {
  //type of local variable and stack cell
  static final int V_NULL = 0; 
  static final int V_INT = -1;
  static final int V_FLOAT = -2;
  static final int V_RETURNADDR = -3;
  static final int V_UNDEF = -4;
  static final int V_VOID = -5;
  static final int V_LONG =-6;
  static final int V_DOUBLE = -7;
  static final int V_REF =1;


  static final private byte ONEWORD = 1;
  static final private byte DOUBLEWORD = 2;

  private int workStkTop;
  private short[] workStk = null;

  private int currBBNum ;
  private int []currBBMap;
  private int currBBStkEmpty ;
  private int currBBStkTop ;
  private VM_PendingJSRInfo currPendingJsr;
  private String currMethodName;
  private boolean [] newObjectInfo; 

  private int	opcode;
  private int opLength;

  private boolean processNextBlock;
  private boolean inJSRSub = false;

  private VM_BasicBlock[] basicBlocks= null;
  private short[] byteToBlockMap = null;
  private int jsrCount =0;
  private int typeMaps[][] = null;
  private int[] blockStkTop = null;
  private VM_PendingJSRInfo[] bbPendingJsrs = null;


  public void verifyClass(VM_Class cls) {
    if(cls == null){
      VM.sysWrite("No class to be verified. \n");
      return;
    }

    if(!cls.isLoaded())
      try{
        cls.load();
      }catch(Exception e){
        e.printStackTrace();
        VM.sysWrite("Verify error: class can't be loaded. \n");
        return;
      }

    boolean success = true;
    VM_Method methods[] = cls.getDeclaredMethods();
    for(int i =0; success && i< methods.length; i++){
      VM_Method method = methods[i];
      try{
        success = verifyMethod(method);
      }catch(Exception e){
        //for debug
        //e.printStackTrace();
        success = false;
      }
    }

    if(success)
      VM.sysWrite("!!! Class " + cls + " passes the bytecode verification ! \n");
    else
      VM.sysWrite("!!! Class " + cls + " failes the bytecode verification ! \n");

  }

  public boolean verifyMethod(VM_Method method) throws Exception{

    currMethodName = method.toString();
    if(!method.isLoaded()){
      VM.sysWrite("Verify error: method " + method + " hasn't been loaded.\n");
      return false;
    }

    //VM.sysWrite("Start to verify method " + currMethodName + "\n");
    //get method bytecode
    byte bytecodes[] = method.getBytecodes();
    VM_Class declaringClass = method.getDeclaringClass();
    int paramCount = method.getParameterWords();
    if(!method.isStatic()) paramCount ++;

    //basic block information from VM_BuildBB()
    VM_BuildBB buildBB = new VM_BuildBB();
    buildBB.determineTheBasicBlocks(method);

    basicBlocks = buildBB.basicBlocks;
    byteToBlockMap = buildBB.byteToBlockMap;
    jsrCount = buildBB.numJsrs;

    //hold type dictionary id if typemap is reference
    typeMaps = new int[basicBlocks.length +1][];
    //stack height of each basic block entry
    blockStkTop = new int[typeMaps.length];

    //initialize the local register and stack
    currBBStkEmpty = method.getLocalWords()-1;
    currBBMap = new int[method.getOperandWords() + currBBStkEmpty +1];
    newObjectInfo = new boolean[method.getOperandWords()];
    for(int k =0; k < newObjectInfo.length; k++)
      newObjectInfo[k] = false;

    //step 1 --parameter types
    VM_Type[] parameterTypes = method.getParameterTypes();
    int paramStart;
    if(!method.isStatic()){
      currBBMap[0] = declaringClass.getDictionaryId();
      paramStart =1;
    }
    else
      paramStart = 0;

    for(int i=0; i<parameterTypes.length; i++,paramStart++){
      VM_Type paramType = parameterTypes[i];
      if(paramType.isIntLikeType())
        currBBMap[paramStart] = V_INT;
      else if(paramType.isLongType())
        currBBMap[paramStart] = currBBMap[paramStart+1] = V_LONG;
      else if(paramType.isFloatType())
        currBBMap[paramStart] = V_FLOAT;
      else if(paramType.isDoubleType())
        currBBMap[paramStart] = currBBMap[paramStart+1] = V_DOUBLE;
      else if(paramType.isReferenceType())
        currBBMap[paramStart] = paramType.getDictionaryId();

      if(paramType.getStackWords() == DOUBLEWORD)
        paramStart ++;
    }

    //step 2  -- others local variables: set to UNDEF

    for(int k =paramStart; k <method.getLocalWords(); k++)
      currBBMap[k] = V_UNDEF;


    //step 3 -- handle the exceptions
    VM_ExceptionHandlerMap exceptions;
    int tryStartPC[];
    int	tryEndPC[];
    int tryHandlerPC[];
    int tryHandlerLength;
    int reachableHandlerBBNums[];
    int reachableHandlersCount;
    boolean	handlerProcessed[];
    boolean handlersAllDone;


    exceptions       = method.getExceptionHandlerMap();
    if (exceptions != null) {
      tryStartPC       = exceptions.getStartPC();
      tryEndPC         = exceptions.getEndPC();
      tryHandlerPC     = exceptions.getHandlerPC();
      tryHandlerLength = tryHandlerPC.length;

      reachableHandlerBBNums = new int[tryStartPC.length];
      handlerProcessed       = new boolean[tryStartPC.length];
      if (jsrCount > 0) 
        bbPendingJsrs = new VM_PendingJSRInfo[typeMaps.length];

      handlersAllDone = (tryHandlerLength == 0);

      // write poison values to help distinguish different errors
      for(int ii = 0; ii < reachableHandlerBBNums.length; ii++)
        reachableHandlerBBNums[ii] = -1;

    }
    else {
      tryHandlerLength       = 0;
      handlersAllDone        = true;
      tryStartPC             = null; 
      tryEndPC               = null;
      tryHandlerPC           = null;
      reachableHandlerBBNums = null;
      handlerProcessed       = null;
    }
    reachableHandlersCount = 0;

    //step 4 -- start to interprete the first block
    workStk = new short [10 + tryHandlerLength];
    workStkTop = 0;
    workStk[workStkTop] = byteToBlockMap[0];

    currBBStkTop = currBBStkEmpty;
    typeMaps[byteToBlockMap[0]]=currBBMap;
    blockStkTop[byteToBlockMap[0]] = currBBStkTop;

    /* debug
       VM.sysWrite("initial currBBMap: \n");
       for(int l=0; l < currBBMap.length; l++)
       VM.sysWrite("currBBMap["+l+"] = " + currBBMap[l] + "\n");
     */

    currBBMap = new int[currBBMap.length];

    //keep doing until worklist is empty
    while(workStkTop >-1){
      currBBNum = workStk[workStkTop];
      workStkTop --;

      inJSRSub = false;
      if(typeMaps[currBBNum]!=null){
        currBBStkTop = blockStkTop[currBBNum];
        for(int k=0; k<=currBBStkTop; k++)
          currBBMap[k] = typeMaps[currBBNum][k];

        if(jsrCount>0 && basicBlocks[currBBNum].isInJSR())
          inJSRSub = true;
      }
      else{
        VM.sysWrite("Verify error: found a block on work stack without starting map.\n");
        throw new Exception();
      }


      int start = basicBlocks[currBBNum].getStart();
      int end = basicBlocks[currBBNum].getEnd();

      if(jsrCount > 0 && inJSRSub){
        currPendingJsr = bbPendingJsrs[currBBNum];

        if (basicBlocks[currBBNum].isTryStart()) {
          if(currPendingJsr == null)
            currPendingJsr = bbPendingJsrs[currBBNum] = bbPendingJsrs[basicBlocks[currBBNum].pred1];
          for (int k = 0; k < tryHandlerLength; k++) {
            if (tryStartPC[k] == start && exceptions.getExceptionType(k) != null) {
              int handlerBBNum = byteToBlockMap[tryHandlerPC[k]];
              bbPendingJsrs[handlerBBNum] = currPendingJsr;
            }
          }
        }
      }
      else
        currPendingJsr = null;

      boolean inTryBlock;
      reachableHandlersCount = 0;
      if (basicBlocks[currBBNum].isTryBlock()) {
        inTryBlock = true;
        for (int i=0; i<tryHandlerLength; i++)
          if (tryStartPC[i] <= start && tryEndPC[i] >= end && exceptions.getExceptionType(i)!=null) {
            reachableHandlerBBNums[reachableHandlersCount] = byteToBlockMap[tryHandlerPC[i]];
            reachableHandlersCount++;

            int handlerBBNum = byteToBlockMap[tryHandlerPC[i]];
            if (typeMaps[handlerBBNum] == null) {
              typeMaps[handlerBBNum] = new int[currBBMap.length];
              for (int k=0; k<=currBBStkEmpty; k++)
                typeMaps[handlerBBNum][k] = currBBMap[k];
              typeMaps[handlerBBNum][currBBStkEmpty+1] = exceptions.getExceptionType(i).getDictionaryId();
              blockStkTop[handlerBBNum] = currBBStkEmpty+1;
            }
          }
      }
      else
        inTryBlock = false;


      processNextBlock = true;
      int i;
      for(i=start; i <= end; ){
        opcode = ((int)bytecodes[i]) & 0x000000FF;
        opLength = JBC_length[opcode];

        /* debug
           VM.sysWrite("#" + i + ": " + opcode + " , length: "+ opLength + "\n");
           VM.sysWrite("currBBStkTop: "+ currBBStkTop + "\n");
           if(currBBStkTop != -1)
           VM.sysWrite("currBBMap[Top]: "+ currBBMap[currBBStkTop] + "\n");
         */

        switch(opcode){
          case JBC_nop:
            break;
            //reference kind of load
          case JBC_aconst_null:
            load_like(V_NULL, -1, 1, false);
            break;
          case JBC_aload_0:
            load_like(V_REF, 0, 1, true);
            break;
          case JBC_aload_1:
            load_like(V_REF, 1, 1, true);
            break;
          case JBC_aload_2:
            load_like(V_REF, 2, 1, true);
            break;
          case JBC_aload_3:
            load_like(V_REF, 3, 1, true);
            break;
          case JBC_aload:{
                           int index =((int)bytecodes[i+1])&0xFF;
                           load_like(V_REF, index, 1, true);
                           break;
                         }
                         //int kind of load
          case JBC_iconst_m1:
          case JBC_iconst_0:
          case JBC_iconst_1:
          case JBC_iconst_2:
          case JBC_iconst_3:
          case JBC_iconst_4:
          case JBC_iconst_5:
          case JBC_bipush:
          case JBC_sipush:
                         load_like(V_INT, -1, 1, false);
                         break;
          case JBC_iload_0:
                         load_like(V_INT, 0, 1, true);
                         break;
          case JBC_iload_1:
                         load_like(V_INT, 1, 1, true);
                         break;
          case JBC_iload_2:
                         load_like(V_INT, 2, 1, true);
                         break;
          case JBC_iload_3:
                         load_like(V_INT, 3, 1, true);
                         break;
          case JBC_iload:{
                           int index = ((int)bytecodes[i+1])&0xFF;
                           load_like(V_INT, index, 1, true);
                           break;
                         }
                         //float kind of load
          case JBC_fconst_0:
          case JBC_fconst_1:
          case JBC_fconst_2:
                         load_like(V_FLOAT, -1, 1, false);
                         break;
          case JBC_fload_0:
                         load_like(V_FLOAT, 0, 1,true);
                         break;
          case JBC_fload_1:
                         load_like(V_FLOAT, 1, 1,true);
                         break;
          case JBC_fload_2:
                         load_like(V_FLOAT, 2, 1, true);
                         break;
          case JBC_fload_3:
                         load_like(V_FLOAT, 3,  1, true);
                         break;
          case JBC_fload:{
                           int index = ((int)bytecodes[i+1])&0xFF;
                           load_like(V_FLOAT, index, 1, true);
                           break;
                         }
                         //long kind of load
          case JBC_lconst_0:
          case JBC_lconst_1:
                         load_like(V_LONG, -1, 2, false);
                         break;
          case JBC_lload_0:
                         load_like(V_LONG, 0, 2, true);
                         break;
          case JBC_lload_1:
                         load_like(V_LONG, 1, 2, true);
                         break;
          case JBC_lload_2:
                         load_like(V_LONG, 2, 2, true);
                         break;
          case JBC_lload_3:
                         load_like(V_LONG, 3, 2, true);
                         break;
          case JBC_lload:{
                           int index = ((int)bytecodes[i+1])&0xFF;
                           load_like(V_LONG, index, 2, true);
                           break;
                         }
                         //double kind of load
          case JBC_dconst_0:
          case JBC_dconst_1:
                         load_like(V_DOUBLE, -1, 2, false);
                         break;
          case JBC_dload_0:
                         load_like(V_DOUBLE, 0, 2, true);
                         break;
          case JBC_dload_1:
                         load_like(V_DOUBLE, 1, 2, true);
                         break;
          case JBC_dload_2:
                         load_like(V_DOUBLE, 2, 2, true);
                         break;
          case JBC_dload_3:
                         load_like(V_DOUBLE, 3, 2, true);
                         break;
          case JBC_dload:{
                           int index = ((int)bytecodes[i+1])&0xFF;
                           load_like(V_DOUBLE, index, 2, true);
                           break;
                         }
                         //cast_like bytecode
          case JBC_int2byte:
          case JBC_int2char:
          case JBC_int2short:
                         cast_like(V_INT,V_INT,1,1);
                         break;
          case JBC_i2l:
                         cast_like(V_INT,V_LONG, 1, 2);
                         break;
          case JBC_i2f:
                         cast_like(V_INT,V_FLOAT, 1, 1);
                         break;
          case JBC_i2d:
                         cast_like(V_INT,V_DOUBLE, 1, 2);
                         break;
          case JBC_l2i:
                         cast_like(V_LONG,V_INT, 2, 1);
                         break;
          case JBC_l2f:
                         cast_like(V_LONG,V_FLOAT, 2, 1);
                         break;
          case JBC_l2d:
                         cast_like(V_LONG,V_DOUBLE, 2, 2);
                         break;
          case JBC_f2i:
                         cast_like(V_FLOAT,V_INT, 1, 1);
                         break;
          case JBC_f2l:
                         cast_like(V_FLOAT,V_LONG, 1, 2);
                         break;
          case JBC_f2d:
                         cast_like(V_FLOAT,V_DOUBLE, 1, 2);
                         break;
          case JBC_d2i:
                         cast_like(V_DOUBLE,V_INT, 2, 1);
                         break;
          case JBC_d2l:
                         cast_like(V_DOUBLE,V_LONG, 2, 2);
                         break;
          case JBC_d2f:
                         cast_like(V_DOUBLE,V_FLOAT, 2, 1);
                         break;

                         //store like bytecodes
          case JBC_istore_0:
                         store_like(V_INT, 1, 0, 
                                    reachableHandlerBBNums, reachableHandlersCount); 
                         break;
          case JBC_istore_1:
                         store_like(V_INT, 1, 1,
                                    reachableHandlerBBNums, reachableHandlersCount); 
                         break;
          case JBC_istore_2:
                         store_like(V_INT, 1, 2,
                                    reachableHandlerBBNums, reachableHandlersCount); 
                         break;
          case JBC_istore_3:
                         store_like(V_INT, 1, 3,
                                    reachableHandlerBBNums, reachableHandlersCount); 
                         break;
          case JBC_istore:{
                            int index = ((int)bytecodes[i+1])&0xFF;
                            store_like(V_INT, 1, index,
                                       reachableHandlerBBNums, reachableHandlersCount); 
                            break;
                          }
          case JBC_fstore_0:
                          store_like(V_FLOAT, 1, 0,
                                     reachableHandlerBBNums, reachableHandlersCount); 
                          break;
          case JBC_fstore_1:
                          store_like(V_FLOAT, 1, 1,
                                     reachableHandlerBBNums, reachableHandlersCount); 
                          break;
          case JBC_fstore_2:
                          store_like(V_FLOAT, 1, 2,
                                     reachableHandlerBBNums, reachableHandlersCount); 
                          break;
          case JBC_fstore_3:
                          store_like(V_FLOAT, 1, 3,
                                     reachableHandlerBBNums, reachableHandlersCount); 
                          break;
          case JBC_fstore:{
                            int index = ((int)bytecodes[i+1])&0xFF;
                            store_like(V_FLOAT, 1, index,
                                       reachableHandlerBBNums, reachableHandlersCount); 
                            break;
                          }
          case JBC_dstore_0:
                          store_like(V_DOUBLE, 2, 0,
                                     reachableHandlerBBNums, reachableHandlersCount); 
                          break;
          case JBC_dstore_1:
                          store_like(V_DOUBLE, 2, 1,
                                     reachableHandlerBBNums, reachableHandlersCount); 
                          break;
          case JBC_dstore_2:
                          store_like(V_DOUBLE, 2, 2,
                                     reachableHandlerBBNums, reachableHandlersCount); 
                          break;
          case JBC_dstore_3:
                          store_like(V_DOUBLE, 2, 3,
                                     reachableHandlerBBNums, reachableHandlersCount); 
                          break;
          case JBC_dstore:{
                            int index = ((int)bytecodes[i+1])&0xFF;
                            store_like(V_DOUBLE, 2, index,
                                       reachableHandlerBBNums, reachableHandlersCount); 
                            break;
                          }
          case JBC_lstore_0:
                          store_like(V_LONG, 2, 0,
                                     reachableHandlerBBNums, reachableHandlersCount); 
                          break;
          case JBC_lstore_1:
                          store_like(V_LONG, 2, 1,
                                     reachableHandlerBBNums, reachableHandlersCount); 
                          break;
          case JBC_lstore_2:
                          store_like(V_LONG, 2, 2,
                                     reachableHandlerBBNums, reachableHandlersCount); 
                          break;
          case JBC_lstore_3:
                          store_like(V_LONG, 2, 3,
                                     reachableHandlerBBNums, reachableHandlersCount); 
                          break;
          case JBC_lstore:{
                            int index = ((int)bytecodes[i+1])&0xFF;
                            store_like(V_LONG, 2, index,
                                       reachableHandlerBBNums, reachableHandlersCount); 
                            break;
                          }
          case JBC_astore_0:
                          store_like(V_REF, 1, 0,
                                     reachableHandlerBBNums, reachableHandlersCount); 
                          break;
          case JBC_astore_1:
                          store_like(V_REF, 1, 1,
                                     reachableHandlerBBNums, reachableHandlersCount); 
                          break;
          case JBC_astore_2:
                          store_like(V_REF, 1, 2,
                                     reachableHandlerBBNums, reachableHandlersCount); 
                          break;
          case JBC_astore_3:
                          store_like(V_REF, 1, 3,
                                     reachableHandlerBBNums, reachableHandlersCount); 
                          break;
          case JBC_astore:{
                            int index = ((int)bytecodes[i+1])&0xFF;
                            store_like(V_REF, 1, index,
                                       reachableHandlerBBNums, reachableHandlersCount); 
                            break;
                          }
                          //stack manipulate bytecode
          case JBC_pop:
                          currBBStkTop--;
                          if(currBBStkTop < currBBStkEmpty){
                            VM.sysWrite("Verify error: stack overflow when "+ JBC_name[opcode] +
                                        " in method " + currMethodName+ " \n");
                            return false;
                          }
                          break;
          case JBC_pop2:
                          currBBStkTop-=2;
                          if(currBBStkTop < currBBStkEmpty){
                            VM.sysWrite("Verify error: stack overflow when "+ JBC_name[opcode] +
                                        " in method " + currMethodName+ " \n");
                            return false;
                          }
                          break;
          case JBC_dup:{
                         dup_like(1,0);
                         //###if this "dup" is after "new", set new object info for it
                         if(newObjectInfo[currBBStkTop-currBBStkEmpty-2 ])
                           newObjectInfo[currBBStkTop-currBBStkEmpty-1] = true; 
                         break;
                       }
          case JBC_dup_x1:
                       dup_like(1,1);
                       break;
          case JBC_dup_x2:
                       dup_like(1,2);
                       break;
          case JBC_dup2:
                       dup_like(2,0);
                       break;
          case JBC_dup2_x1:
                       dup_like(2,1);
                       break;
          case JBC_dup2_x2:
                       dup_like(2,2);
                       break;

          case JBC_swap:{
                          //check stack underflow
                          if(currBBStkTop -1 <= currBBStkEmpty){
                            VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                                        " in method " + currMethodName+ " \n");
                            return false;
                          }
                          //check type, can't be 64-bits data
                          if(currBBMap[currBBStkTop]<=V_LONG || currBBMap[currBBStkTop-1] <= V_LONG){
                            VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                                        +" in method " + currMethodName+ " \n");
                            return false;
                          }
                          //swap the type
                          int temp = currBBMap[currBBStkTop-1];
                          currBBMap[currBBStkTop-1] = currBBMap[currBBStkTop];
                          currBBMap[currBBStkTop] = temp;
                          break;
                        }
                        //arithmetic bytecodes
          case JBC_iadd:
          case JBC_isub:
          case JBC_imul:
          case JBC_idiv:
          case JBC_irem:
          case JBC_ishl:
          case JBC_ishr:
          case JBC_iushr:
          case JBC_iand:
          case JBC_ior:
          case JBC_ixor:
                        arith_like(V_INT, 2, 1);
                        break;

          case JBC_ladd:
          case JBC_lsub:
          case JBC_lmul:
          case JBC_ldiv:
          case JBC_lrem:
          case JBC_land:
          case JBC_lor:
          case JBC_lxor:
                        arith_like(V_LONG, 2, 2);
                        break;

          case JBC_lshl:
          case JBC_lshr:
          case JBC_lushr:
                        {
                          /* Since these two bytecodes are a little special: the stack is supposed to
                           * be ...V_LONG, V_LONG, V_INT => ...V_LONG, V_LONG
                           * Handle "V_INT" first to use arith_like
                           */
                          //check stack underflow
                          if(currBBStkTop <= currBBStkEmpty){
                            VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                                        " in method " + currMethodName+ " \n");
                            throw new Exception();
                          }

                          if(currBBMap[currBBStkTop] != V_INT){
                            VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                                        +" in method " + currMethodName+ " \n");
                            throw new Exception();
                          }

                          currBBStkTop--;
                          arith_like(V_LONG, 1, 2);
                          break;

                        }

          case JBC_fadd:
          case JBC_fsub:
          case JBC_fmul:
          case JBC_fdiv:
          case JBC_frem:
                        arith_like(V_FLOAT, 2, 1);
                        break;

          case JBC_dadd:
          case JBC_dsub:
          case JBC_dmul:
          case JBC_ddiv:
          case JBC_drem:
                        arith_like(V_DOUBLE, 2, 2);
                        break;

          case JBC_ineg:
                        arith_like(V_INT, 1, 1);
                        break;

          case JBC_lneg:
                        arith_like(V_LONG, 1, 2);
                        break;

          case JBC_fneg:
                        arith_like(V_FLOAT, 1, 1);
                        break;

          case JBC_dneg:
                        arith_like(V_DOUBLE, 1, 2);
                        break;

          case JBC_iinc:{
                          //check index validity
                          int index = ((int)bytecodes[i+1])&0xFF;
                          if(index <0 || index > currBBStkEmpty){
                            VM.sysWrite("Verify error: invalid register index when " + JBC_name[opcode]
                                        + " index: " + index + " in method "+ currMethodName+ " \n");
                            return false;
                          }
                          //check type in the register
                          if(currBBMap[index]!=V_INT){
                            VM.sysWrite("Verify error: register " + index +" has wrong type when " +
                                        JBC_name[opcode] + " in method "+ currMethodName+ " \n");
                            return false;
                          }

                          //####
                          if(inJSRSub && currPendingJsr != null && !currPendingJsr.updateOnce)
                            currPendingJsr.setUsed(index);

                          break; 
                        }

                        //return like bytecode
          case JBC_return:
                        return_like(V_VOID, 0, method);
                        break;
          case JBC_ireturn:
                        return_like(V_INT, 1, method);
                        break;
          case JBC_lreturn:
                        return_like(V_LONG, 2, method);
                        break;
          case JBC_freturn:
                        return_like(V_FLOAT, 1, method);
                        break;
          case JBC_dreturn:
                        return_like(V_DOUBLE, 2, method);
                        break;
          case JBC_areturn:
                        return_like(V_REF, 1, method);
                        break;

                        //*aload bytecode
          case JBC_iaload:
          case JBC_baload:
          case JBC_caload:
          case JBC_saload:
                        aaload_like(V_INT, 1);
                        break;
          case JBC_laload:
                        aaload_like(V_LONG, 2);
                        break;
          case JBC_faload:
                        aaload_like(V_FLOAT, 1);
                        break;
          case JBC_daload:
                        aaload_like(V_DOUBLE, 2);
                        break;
          case JBC_aaload:
                        aaload_like(V_REF, 1);
                        break;

                        //*astore bytecode
          case JBC_iastore:
          case JBC_bastore:
          case JBC_castore:
          case JBC_sastore:
                        aastore_like(V_INT, 1);
                        break;
          case JBC_lastore:
                        aastore_like(V_LONG, 2);
                        break;
          case JBC_fastore:
                        aastore_like(V_FLOAT, 1);
                        break;
          case JBC_dastore:
                        aastore_like(V_DOUBLE, 2);
                        break;
          case JBC_aastore:
                        aastore_like(V_REF, 1);
                        break;

                        //ldc* bytecode
          case JBC_ldc:{
                         int cpindex = ((int)bytecodes[i+1])&0xFF;
                         ldc_like(1, cpindex, declaringClass);
                         break;
                       }
          case JBC_ldc_w:{
                           int cpindex = (((int)bytecodes[i+1])<<8 |
                                          (((int)bytecodes[i+2])&0xFF)) & 0xFFFF;;
                           ldc_like(1, cpindex, declaringClass);
                           break;
                         }
          case JBC_ldc2_w:{
                            int cpindex = (((int)bytecodes[i+1])<<8 |
                                           (((int)bytecodes[i+2])&0xFF)) & 0xFFFF;;
                            ldc_like(2, cpindex, declaringClass);
                            break;
                          }
                          //cmp, no branch bytecode
          case JBC_lcmp:
                          cmp_like(V_LONG, 2, 2, 1);
                          break;
          case JBC_fcmpl:
                          cmp_like(V_FLOAT, 1, 2, 1);
                          break;
          case JBC_fcmpg:
                          cmp_like(V_FLOAT,1, 2, 1);
                          break;
          case JBC_dcmpl:
                          cmp_like(V_DOUBLE, 2, 2, 1);
                          break;
          case JBC_dcmpg:
                          cmp_like(V_DOUBLE, 2, 2, 1);
                          break;

                          //ifnull like bytecode
          case JBC_ifnull:
          case JBC_ifnonnull:{
                               cmp_like(V_REF, 1, 1, 0);
                               short offset =(short)(((int)bytecodes[i+1])<<8 |
                                                     (((int)bytecodes[i+2])&0xFF));
                               //check the validity of branch offset
                               if( (i+offset)<0 || (i+offset) > bytecodes.length){
                                 VM.sysWrite("Verify error: invalid branch offset when " + JBC_name[opcode]
                                             +" in method "+ currMethodName+ " \n");
                                 return false;
                               }

                               if( offset < 0){  //backward branch
                                 short NextBBNum = byteToBlockMap[i+3];
                                 processBranchBB(NextBBNum);
                                 processNextBlock = false;
                               }
                               short brBBNum = byteToBlockMap[i+offset];
                               processBranchBB(brBBNum);
                               break;
                             }

                             //ifeq like bytecode
          case JBC_ifeq:
          case JBC_ifne:
          case JBC_iflt:
          case JBC_ifle:
          case JBC_ifgt:
          case JBC_ifge:{
                          cmp_like(V_INT, 1,1, 0);
                          short offset =(short)(((int)bytecodes[i+1])<<8 |
                                                (((int)bytecodes[i+2])&0xFF));
                          //check the validity of branch offset
                          if( (i+offset)<0 || (i+offset) > bytecodes.length){
                            VM.sysWrite("Verify error: invalid branch offset when " + JBC_name[opcode]
                                        +" in method "+ currMethodName+ " \n");
                            return false;
                          }

                          if( offset < 0){  //backward branch
                            short NextBBNum = byteToBlockMap[i+3];
                            processBranchBB(NextBBNum);
                            processNextBlock = false;
                          }
                          short brBBNum = byteToBlockMap[i+offset];
                          processBranchBB(brBBNum);
                          break;
                        }

                        //cmp_branch like bytecode
          case JBC_if_icmpeq:	
          case JBC_if_icmpne:
          case JBC_if_icmplt:
          case JBC_if_icmpge:
          case JBC_if_icmpgt:
          case JBC_if_icmple:{
                               cmp_like(V_INT, 1,2, 0);
                               short offset =(short)(((int)bytecodes[i+1])<<8 |
                                                     (((int)bytecodes[i+2])&0xFF));

                               //check the validity of branch offset
                               if( (i+offset)<0 || (i+offset) > bytecodes.length){
                                 VM.sysWrite("Verify error: invalid branch offset when " + JBC_name[opcode]
                                             +" in method "+ currMethodName+ " \n");
                                 return false;
                               }

                               if( offset < 0){  //backward branch
                                 short NextBBNum = byteToBlockMap[i+3];
                                 processBranchBB(NextBBNum);
                                 processNextBlock = false;
                               }
                               short brBBNum = byteToBlockMap[i+offset];
                               processBranchBB(brBBNum);
                               break;
                             }

          case JBC_if_acmpeq:
          case JBC_if_acmpne:{
                               cmp_like(V_REF, 1,2, 0);
                               short offset =(short)(((int)bytecodes[i+1])<<8 |
                                                     (((int)bytecodes[i+2])&0xFF));

                               //check the validity of branch offset
                               if( (i+offset)<0 || (i+offset) > bytecodes.length){
                                 VM.sysWrite("Verify error: invalid branch offset when " + JBC_name[opcode]
                                             +" in method "+ currMethodName+ " \n");
                                 return false;
                               }

                               if( offset < 0){  //backward branch
                                 short NextBBNum = byteToBlockMap[i+3];
                                 processBranchBB(NextBBNum);
                                 processNextBlock = false;
                               }
                               short brBBNum = byteToBlockMap[i+offset];
                               processBranchBB(brBBNum);
                               break;
                             }

                             //goto instructions:
          case JBC_goto:{
                          short offset =(short)(((int)bytecodes[i+1])<<8 |
                                                (((int)bytecodes[i+2])&0xFF));

                          //check the validity of branch offset
                          if( (i+offset)<0 || (i+offset) > bytecodes.length){
                            VM.sysWrite("Verify error: invalid branch offset when " + JBC_name[opcode]
                                        +" in method "+ currMethodName+ " \n");
                            return false;
                          }

                          short brBBNum = byteToBlockMap[i+offset];
                          processBranchBB(brBBNum);
                          processNextBlock = false;
                          break;
                        }

          case JBC_goto_w:{
                            int offset = getIntOffset(i, bytecodes);

                            //check the validity of branch offset
                            if( (i+offset)<0 || (i+offset) > bytecodes.length){
                              VM.sysWrite("Verify error: invalid branch offset when " + JBC_name[opcode]
                                          +" in method "+ currMethodName+ " \n");
                              return false;
                            }
                            short brBBNum = byteToBlockMap[i+offset];
                            processBranchBB(brBBNum);
                            processNextBlock = false;
                            break;
                          }

                          //switch
          case JBC_tableswitch : {
                                   int j = i;           // save initial value
                                   opLength = 0;

                                   //check stack underflow
                                   if(currBBStkTop <= currBBStkEmpty){
                                     VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                                                 " in method " + currMethodName+ " \n");
                                     return false;
                                   }
                                   //top of stack: index must be int
                                   if(currBBMap[currBBStkTop]!=V_INT){
                                     VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                                                 +" in method " + currMethodName+ " \n");
                                     return false;
                                   }
                                   currBBStkTop--; 
                                   i = i + 1;           // space past op code
                                   i = (((i + 3)/4)*4); // align to next word boundary
                                   // get default offset and generate basic block at default offset
                                   int def = getIntOffset(i-1,bytecodes);  // getIntOffset expects byte before 
                                   // offset
                                   if(j+def <0 || j+def > bytecodes.length){
                                     VM.sysWrite("Verify error: invalid branch offset when " + JBC_name[opcode]
                                                 +" in method "+ currMethodName+ " \n");
                                     return false;
                                   }
                                   processBranchBB(byteToBlockMap[j+def]);

                                   // get low offset
                                   i = i + 4;           // go past default br offset
                                   int low = getIntOffset(i-1,bytecodes);
                                   i = i + 4;           // space past low offset

                                   // get high offset
                                   int high = getIntOffset(i-1,bytecodes);
                                   i = i + 4;           // go past high offset

                                   // generate labels for offsets
                                   for (int k = 0; k < (high - low +1); k++) {
                                     int l = i + k*4; // point to next offset
                                     // get next offset
                                     int offset = getIntOffset(l-1,bytecodes);
                                     if(j+offset <0 || j+offset > bytecodes.length){
                                       VM.sysWrite("Verify error: invalid branch offset when " + JBC_name[opcode]
                                                   +" in method "+ currMethodName+ " \n");
                                       return false;
                                     }
                                     processBranchBB(byteToBlockMap[j+offset]);
                                   }
                                   processNextBlock = false;       
                                   i = i + (high - low +1) * 4; // space past offsets
                                   break;
                                 }

          case JBC_lookupswitch : {
                                    int j = i;           // save initial value
                                    opLength = 0;

                                    //check stack underflow
                                    if(currBBStkTop <= currBBStkEmpty){
                                      VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                                                  " in method " + currMethodName+ " \n");
                                      return false;
                                    }
                                    //top of stack: key must be int
                                    if(currBBMap[currBBStkTop]!=V_INT){
                                      VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                                                  +" in method " + currMethodName+ " \n");
                                      return false;
                                    }
                                    currBBStkTop--; 
                                    i = i + 1;           // space past op code
                                    i = (((i + 3)/4)*4); // align to next word boundary
                                    // get default offset and generate basic block at default offset
                                    int def = getIntOffset(i-1,bytecodes);  // getIntOffset expects byte before 
                                    // offset
                                    if(j+def <0 || j+def > bytecodes.length){
                                      VM.sysWrite("Verify error: invalid branch offset when " + JBC_name[opcode]
                                                  +" in method "+ currMethodName+ " \n");
                                      return false;
                                    }
                                    processBranchBB(byteToBlockMap[j+def]);

                                    i = i + 4;           // go past default  offset


                                    // get number of pairs
                                    int npairs = getIntOffset(i-1,bytecodes);
                                    i = i + 4;           // space past  number of pairs

                                    // generate label for each offset in table
                                    for (int k = 0; k < npairs; k++) {
                                      int l = i + k*8 + 4; // point to next offset
                                      // get next offset
                                      int offset = getIntOffset(l-1,bytecodes);
                                      if(j+offset <0 || j+offset > bytecodes.length){
                                        VM.sysWrite("Verify error: invalid branch offset when " + JBC_name[opcode]
                                                    +" in method "+ currMethodName+ " \n");
                                        return false;
                                      }
                                      processBranchBB(byteToBlockMap[j+offset]);
                                    }
                                    processNextBlock = false;
                                    i = i + (npairs) *8; // space past match-offset pairs
                                    break;
                                  }

                                  //jsr
          case JBC_jsr : {
                           processNextBlock = false;
                           short offset = (short)(((int)bytecodes[i+1]) << 8 | 
                                                  (((int)bytecodes[i+2]) & 0xFF));
                           currBBStkTop++;
                           //check stack overflow
                           if(currBBStkTop >= currBBMap.length){
                             VM.sysWrite("Verify error: stack overflow when "+ JBC_name[opcode] +
                                         " in method " + currMethodName+ " \n");
                             return false;
                           }
                           currBBMap[currBBStkTop] = V_RETURNADDR; 
                           if(i+offset <0 || i+offset > bytecodes.length){
                             VM.sysWrite("Verify error: invalid jsr offset in method "+ 
                                         currMethodName+ " \n");
                             return false;
                           }

                           //#### 
                           short brBBNum = byteToBlockMap[i+offset];
                           short nextBBNum = byteToBlockMap[i+3];


                           if(bbPendingJsrs[brBBNum]==null)
                             bbPendingJsrs[brBBNum] = new VM_PendingJSRInfo(i+offset, currBBStkEmpty,
                                                                            currBBMap,	currBBStkTop, currPendingJsr);
                           else{
                             //compute type map for the instruction right after "jsr" if
                             //the jsr subroutine is already processed once
                             int[] endMap = bbPendingJsrs[brBBNum].endMap;
                             if(typeMaps[nextBBNum]==null && endMap != null){
                               typeMaps[nextBBNum] = new int[endMap.length];
                               boolean[] used = bbPendingJsrs[brBBNum].getUsedMap();
                               for(int j =0; j <= currBBStkEmpty; j++){
                                 if(used[j])
                                   typeMaps[nextBBNum][j] = endMap[j];
                                 else
                                   typeMaps[nextBBNum][j] = currBBMap[j];
                               }	
                               for(int j = currBBStkEmpty+1; j <= currBBStkTop; j++)
                                 typeMaps[nextBBNum][j] = endMap[j];
                               //-1 to get rid of the return address on the top of stack now
                               blockStkTop[nextBBNum] = currBBStkTop -1;

                               addToWorkStk(nextBBNum);
                             }
                           }

                           bbPendingJsrs[brBBNum].addSitePair(currBBMap, nextBBNum);

                           if(currPendingJsr!= null)
                             bbPendingJsrs[nextBBNum] = currPendingJsr;

                           processBranchBB(byteToBlockMap[i+offset]);
                           break;
                         }

          case JBC_jsr_w : {
                             processNextBlock = false;
                             int offset = getIntOffset(i, bytecodes);
                             currBBStkTop++;
                             //check stack overflow
                             if(currBBStkTop >= currBBMap.length){
                               VM.sysWrite("Verify error: stack overflow when "+ JBC_name[opcode] +
                                           " in method " + currMethodName+ " \n");
                               return false;
                             }
                             currBBMap[currBBStkTop] = V_RETURNADDR; 
                             if(i+offset <0 || i+offset > bytecodes.length){
                               VM.sysWrite("Verify error: invalid jsr offset in method "+ 
                                           currMethodName+ " \n");
                               return false;
                             }

                             //#### 
                             short brBBNum = byteToBlockMap[i+offset];
                             short nextBBNum = byteToBlockMap[i+3];

                             if(bbPendingJsrs[brBBNum]==null)
                               bbPendingJsrs[brBBNum] = new VM_PendingJSRInfo(i+offset, currBBStkEmpty,
                                                                              currBBMap,	currBBStkTop, currPendingJsr);
                             else{
                               //compute type map for the instruction right after "jsr" if
                               //the jsr subroutine is already processed once
                               int[] endMap = bbPendingJsrs[brBBNum].endMap;
                               if(typeMaps[nextBBNum]==null && endMap != null){
                                 typeMaps[nextBBNum] = new int[endMap.length];
                                 boolean[] used = bbPendingJsrs[brBBNum].getUsedMap();
                                 for(int j =0; j <= currBBStkEmpty; j++){
                                   if(used[j])
                                     typeMaps[nextBBNum][j] = endMap[j];
                                   else
                                     typeMaps[nextBBNum][j] = currBBMap[j];
                                 }	
                                 for(int j = currBBStkEmpty+1; j <= currBBStkTop; j++)
                                   typeMaps[nextBBNum][j] = endMap[j];

                                 //-1 to get rid of the return address on the top of stack now
                                 blockStkTop[nextBBNum] = currBBStkTop -1;
                                 addToWorkStk(nextBBNum);
                               }
                             }

                             bbPendingJsrs[brBBNum].addSitePair(currBBMap, nextBBNum);

                             if(currPendingJsr!= null)
                               bbPendingJsrs[nextBBNum] = currPendingJsr;

                             processBranchBB(byteToBlockMap[i+offset]);

                             break;
                           }

          case JBC_ret:{
                         //#### 
                         //index of local variable (unsigned byte)
                         int index = ((int)bytecodes[i+1])& 0xFF;
                         //can not be used again as a return addr.
                         currBBMap[index] = V_UNDEF;
                         processNextBlock = false;

                         currPendingJsr.updateOnce = true;
                         computeJSRNextMaps();

                         break;
                       }

                       //invoke like bytecodes
          case JBC_invokespecial:
          case JBC_invokevirtual:
          case JBC_invokeinterface:{
                                     int index = ((int)bytecodes[i+1]<<8 |
                                                  (((int)bytecodes[i+2])&0xFF))&0xFFFF;
                                     VM_Method calledMethod = declaringClass.getMethodRef(index);
                                     /*debug
                                       VM.sysWrite("calledMethod: "+ calledMethod + "\n");
                                      */
                                     processInvoke(calledMethod,false);

                                     break;
                                   }

          case JBC_invokestatic:{
                                  int index = ((int)bytecodes[i+1]<<8 |
                                               (((int)bytecodes[i+2])&0xFF))&0xFFFF;
                                  VM_Method calledMethod = declaringClass.getMethodRef(index);
                                  /*debug
                                    VM.sysWrite("calledMethod: "+ calledMethod + "\n");
                                   */
                                  processInvoke(calledMethod,true);

                                  break;
                                }

                                //get
          case JBC_getstatic:{
                               int index = ((int)bytecodes[i+1]<<8 |
                                            (((int)bytecodes[i+2])&0xFF))&0xFFFF;
                               VM_Field field = declaringClass.getFieldRef(index);
                               get_like(field, true);
                               break;
                             }

          case JBC_getfield:{
                              int index = ((int)bytecodes[i+1]<<8 |
                                           (((int)bytecodes[i+2])&0xFF))&0xFFFF;
                              VM_Field field = declaringClass.getFieldRef(index);
                              get_like(field, false);
                              break;
                            }

                            //put
          case JBC_putstatic:{
                               int index = ((int)bytecodes[i+1]<<8 |
                                            (((int)bytecodes[i+2])&0xFF))&0xFFFF;
                               VM_Field field = declaringClass.getFieldRef(index);
                               put_like(field, true);
                               break;
                             }

          case JBC_putfield:{
                              int index = ((int)bytecodes[i+1]<<8 |
                                           (((int)bytecodes[i+2])&0xFF))&0xFFFF;
                              VM_Field field = declaringClass.getFieldRef(index);
                              put_like(field, false);
                              break;
                            }

          case JBC_checkcast:{
                               //check whether toType is a reference type
                               int index = ((int)bytecodes[i+1]<<8 |
                                            (((int)bytecodes[i+2])&0xFF))&0xFFFF;
                               VM_Type toType = declaringClass.getTypeRef(index);
                               if(!toType.isReferenceType()){
                                 VM.sysWrite("Vefity error: checkcast dest type isn't reference type in method " + 
                                             currMethodName + "\n");
                                 return false;
                               }

                               //check stack underflow
                               if(currBBStkTop <= currBBStkEmpty){
                                 VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                                             " in method " + currMethodName+ " \n");
                                 return false;
                               }

                               //check whether fromType is a reference type
                               if(currBBMap[currBBStkTop]<0){
                                 VM.sysWrite("Vefity error: checkcast from type isn't reference type in method " + 
                                             currMethodName + "\n");
                                 return false;
                               }

                               //check whether fromType is assignable to the totype
                               //Note: if toType is subclass of fromType, it should be passed by verifier
                               if(currBBMap[currBBStkTop]!=V_NULL && 
                                  !VM_TypeDictionary.getValue(currBBMap[currBBStkTop]).isAssignableWith(toType)&&
                                  !toType.isAssignableWith(VM_TypeDictionary.getValue(currBBMap[currBBStkTop]))){
                                 VM.sysWrite("Vefity error: checkcast from type isn't assignable to toType in method " + 
                                             currMethodName + "\n");
                                 VM.sysWrite("======toType: " + toType + " id:" + toType.getDictionaryId()
                                             + " fromType: "+ VM_TypeDictionary.getValue(currBBMap[currBBStkTop])
                                             + " id: " + currBBMap[currBBStkTop] + "\n");
                                 return false;
                               }
                               currBBMap[currBBStkTop] = toType.getDictionaryId();

                               break;
                             }

          case JBC_instanceof:{
                                //check whether toType is a reference type
                                int index = ((int)bytecodes[i+1]<<8 |
                                             (((int)bytecodes[i+2])&0xFF))&0xFFFF;
                                if(!declaringClass.getTypeRef(index).isReferenceType()){
                                  VM.sysWrite("Vefity error: instanceof dest type isn't reference type in method " + 
                                              currMethodName + "\n");
                                  return false;
                                }

                                //check stack underflow
                                if(currBBStkTop <= currBBStkEmpty){
                                  VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                                              " in method " + currMethodName+ " \n");
                                  return false;
                                }

                                //check whether fromType is a reference type
                                if(currBBMap[currBBStkTop]<0){
                                  VM.sysWrite("Vefity error: instanceof from type isn't reference type in method " + 
                                              currMethodName + "\n");
                                  return false;
                                }

                                //pop fromtype from the stack
                                currBBStkTop--;
                                //push the int result onto the stack
                                currBBMap[++currBBStkTop] = V_INT;
                                break;
                              }

                              //new
          case JBC_new:{
                         int index = ((int)bytecodes[i+1]<<8 |
                                      (((int)bytecodes[i+2])&0xFF))&0xFFFF;
                         // the type in constant pool must be a class
                         VM_Type newType = declaringClass.getTypeRef(index);
                         if(!newType.isClassType()){
                           VM.sysWrite("Vefity error: new type isn't a class type in method " + 
                                       currMethodName + "\n");
                           return false;
                         }
                         //check stack overflow
                         currBBStkTop ++;
                         if(currBBStkTop >= currBBMap.length){
                           VM.sysWrite("Verify error: stack overflow when "+ JBC_name[opcode] +
                                       " in method " + currMethodName+ " \n");
                           return false;
                         }
                         //push the class type onto the stack
                         currBBMap[currBBStkTop] = newType.getDictionaryId(); 

                         //####use the bytecode index as the label of uninitiated new object
                         newObjectInfo[currBBStkTop-currBBStkEmpty -1] = true;

                         break;
                       }

          case JBC_newarray:{
                              //check stack underflow
                              if(currBBStkTop <= currBBStkEmpty){
                                VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                                            " in method " + currMethodName+ " \n");
                                return false;
                              }
                              //check whether the top of stack is int
                              if(currBBMap[currBBStkTop]!=V_INT){
                                VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                                            +" in method " + currMethodName+ " \n");
                                return false;
                              }
                              //pop the count
                              currBBStkTop--;

                              //push the array type
                              byte atype = bytecodes[i+1];
                              if(atype<4 || atype >11){
                                VM.sysWrite("Vefity error: invalid atype for newarray in method " + currMethodName + "\n");
                                return false;
                              }
                              currBBMap[++currBBStkTop] = VM_Array.getPrimitiveArrayType(atype).getDictionaryId();
                              break;
                            }

          case JBC_anewarray:{
                               //check stack underflow
                               if(currBBStkTop <= currBBStkEmpty){
                                 VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                                             " in method " + currMethodName+ " \n");
                                 return false;
                               }
                               //check whether the top of stack is int
                               if(currBBMap[currBBStkTop]!=V_INT){
                                 VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                                             +" in method " + currMethodName+ " \n");
                                 return false;
                               }
                               //pop the count
                               currBBStkTop--;

                               int index = ((int)bytecodes[i+1]<<8 |
                                            (((int)bytecodes[i+2])&0xFF))&0xFFFF;
                               // the type in constant pool must be a reference type 
                               VM_Type newType = declaringClass.getTypeRef(index);
                               if(!newType.isReferenceType()){
                                 VM.sysWrite("Vefity error: anewarray type isn't a reference type in method " + 
                                             currMethodName + "\n");
                                 return false;
                               }

                               //push the new reference array onto the stack
                               currBBMap[++currBBStkTop] = newType.getArrayTypeForElementType().getDictionaryId();

                               break;
                             }

          case JBC_multianewarray:{
                                    int index = ((int)bytecodes[i+1]<<8 |
                                                 (((int)bytecodes[i+2])&0xFF))&0xFFFF;
                                    // the type in constant pool must be a reference type 
                                    VM_Type newType = declaringClass.getTypeRef(index);
                                    if(!newType.isReferenceType()){
                                      VM.sysWrite("Vefity error: multianewarray type isn't a reference type in method " + 
                                                  currMethodName + "\n");
                                      return false;
                                    }

                                    int dimension = ((int)bytecodes[i+3]) & 0xFFFF;
                                    //check stack underflow
                                    if(currBBStkTop - dimension < currBBStkEmpty){
                                      VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                                                  " in method " + currMethodName+ " \n");
                                      return false;
                                    }

                                    for(int k=0; k<dimension; k++){
                                      //check whether the top of stack is int
                                      if(currBBMap[currBBStkTop]!=V_INT){
                                        VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                                                    +" in method " + currMethodName+ " \n");
                                        return false;
                                      }
                                      //pop the count
                                      currBBStkTop--;
                                    }

                                    //push the new reference array onto the stack
                                    currBBMap[++currBBStkTop] = newType.getDictionaryId();
                                    break;
                                  }

          case JBC_arraylength:
                                  //check stack underflow
                                  if(currBBStkTop <= currBBStkEmpty){
                                    VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                                                " in method " + currMethodName+ " \n");
                                    return false;
                                  }

                                  //check whether stack top is an array reference
                                  if(currBBMap[currBBStkTop]<=0 || 
                                     !VM_TypeDictionary.getValue(currBBMap[currBBStkTop]).isArrayType()){
                                    VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                                                +" in method " + currMethodName+ " \n");
                                    return false;
                                  }

                                  //push int type (length) onto stack
                                  currBBMap[currBBStkTop] = V_INT;
                                  break;

          case JBC_athrow:
                                  {
                                    //check whether type of top stack is a subclass of Throwable
                                    if(currBBMap[currBBStkTop] < 0){	//not a reference
                                      VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                                                  +" in method " + currMethodName+ " \n");
                                      return false;
                                    }
                                    int typeId = currBBMap[currBBStkTop];
                                    if(typeId == V_NULL){
                                      currBBStkTop = currBBStkEmpty +1;
                                      currBBMap[currBBStkTop] = typeId;
                                      processNextBlock = false;
                                      break;
                                    }
                                    VM_Class cls = VM_TypeDictionary.getValue(typeId).asClass();

                                    if(!cls.isClassType()){   // not a object reference
                                      VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                                                  +" in method " + currMethodName+ " \n");
                                      return false;
                                    }

                                    //resolve class
                                    if(!cls.isLoaded())
                                      cls.load();

                                    VM_Atom d = VM_Atom.findOrCreateAsciiAtom("Ljava/lang/Throwable;");
                                    VM_Class throwType = VM_ClassLoader.findOrCreateType(d, 
                                                                                         VM_SystemClassLoader.getVMClassLoader()).asClass(); 
                                                                                         //resolve class
                                                                                         if(!throwType.isLoaded())
                                                                                           throwType.load();

                                                                                         while(cls!= null && cls!= throwType)
                                                                                           cls = cls.getSuperClass();

                                                                                         if(cls==null){
                                                                                           VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                                                                                                       +" in method " + currMethodName+ " \n");
                                                                                           return false;
                                                                                         }
                                                                                         currBBStkTop = currBBStkEmpty +1;
                                                                                         currBBMap[currBBStkTop] = typeId;
                                                                                         processNextBlock = false;
                                                                                         break;
                                  }
          case JBC_monitorenter:
          case JBC_monitorexit:
                                  if(currBBMap[currBBStkTop] < 0){  // not a reference
                                    VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                                                +" in method " + currMethodName+ " \n");
                                    return false;
                                  }
                                  currBBStkTop--;
                                  break;

          case JBC_wide:{
                          int wopcode = ((int)bytecodes[i+1]) & 0xFF;

                          opLength = JBC_length[wopcode]*2;			// 4 or 6

                          if(wopcode != JBC_iinc){
                            int index = (((int)bytecodes[i+2]) << 8 |
                                         (((int)bytecodes[i+3]) & 0xFF)) & 0xFFFF;
                            switch(wopcode){
                              case JBC_iload:
                                load_like(V_INT, index, 1, true);
                                break;
                              case JBC_fload:
                                load_like(V_FLOAT, index, 1, true);
                                break;
                              case JBC_aload:
                                load_like(V_REF, index, 1, true);
                                break;
                              case JBC_lload:
                                load_like(V_LONG, index, 2, true);
                                break;
                              case JBC_dload:
                                load_like(V_DOUBLE, index, 2, true);
                                break;
                              case JBC_istore:
                                store_like(V_INT, 1, index,
                                           reachableHandlerBBNums, reachableHandlersCount); 
                                break;
                              case JBC_fstore:
                                store_like(V_FLOAT, 1, index,
                                           reachableHandlerBBNums, reachableHandlersCount); 
                                break;
                              case JBC_astore:
                                store_like(V_REF, 1, index,
                                           reachableHandlerBBNums, reachableHandlersCount); 
                                break;
                              case JBC_lstore:
                                store_like(V_LONG, 2, index,
                                           reachableHandlerBBNums, reachableHandlersCount); 
                                break;
                              case JBC_dstore:
                                store_like(V_DOUBLE, 2, index,
                                           reachableHandlerBBNums, reachableHandlersCount); 
                                break;
                              case JBC_ret: {
                                              //#### 
                                              //can not be used again as a return addr.
                                              if(currBBMap[index] != V_RETURNADDR){
                                                VM.sysWrite("Vefity error: wrong register type when ret in method " + currMethodName + "\n");
                                                return false;
                                              }
                                              currBBMap[index] = V_UNDEF;
                                              processNextBlock = false;

                                              currPendingJsr.updateOnce = true;
                                              computeJSRNextMaps();
                                              break;
                                            }
                              default:{
                                        VM.sysWrite("Vefity error: wrong wide opcode in method " + currMethodName + "\n");
                                        return false;
                                      }
                            }
                          }//end if wopcode! iinc
                          else{
                            //check index validity
                            int index = (((int)bytecodes[i+2]) << 8 |
                                         (((int)bytecodes[i+3]) & 0xFF)) & 0xFFFF;
                            if(index <0 || index > currBBStkEmpty){
                              VM.sysWrite("Verify error: invalid register index when " + JBC_name[opcode]
                                          + " index: " + index + " in method "+ currMethodName+ " \n");
                              return false;
                            }
                            //check type in the register
                            if(currBBMap[index]!=V_INT){
                              VM.sysWrite("Verify error: register " + index +" has wrong type when " +
                                          JBC_name[opcode] + " in method "+ currMethodName+ " \n");
                              return false;
                            }

                            //####
                            if(inJSRSub && currPendingJsr != null && !currPendingJsr.updateOnce)
                              currPendingJsr.setUsed(index);


                          }
                          break;
                        }	
                        //####
          default:{
                    VM.sysWrite("Vefity error: wrong opcode in method " + currMethodName + "\n");
                    return false;
                  }
        }//end of switch

        i = i+opLength;

      }//end of for start to end

      if(processNextBlock){
        short nextBBNum = byteToBlockMap[i];
        processBranchBB(nextBBNum);
      }

      if((workStkTop ==-1) && !handlersAllDone){
        int ii;
        for(ii =0; ii < tryHandlerLength; ii++){
          if(handlerProcessed[ii] || typeMaps[byteToBlockMap[tryHandlerPC[ii]]]== null)
            continue;
          else
            break;
        }
        if(ii == tryHandlerLength)
          handlersAllDone = true;
        else{
          int considerIndex = ii;

          while (ii != tryHandlerLength) {

            int tryStart = tryStartPC[considerIndex];
            int tryEnd   = tryEndPC[considerIndex];

            for (ii=0; ii<tryHandlerLength; ii++)
              // For every handler that has not yet been processed, 
              // but already has a known starting map,
              // make sure it is not in the try block part of the handler
              // we are considering working on. 
              if (!handlerProcessed[ii] && tryStart <= tryHandlerPC[ii] &&
                  tryHandlerPC[ii] < tryEnd && typeMaps[byteToBlockMap[tryHandlerPC[ii]]] != null)
                break;
            if (ii != tryHandlerLength)
              considerIndex = ii;
          }//end while

          short blockNum = byteToBlockMap[tryHandlerPC[considerIndex]];
          handlerProcessed[considerIndex] = true;
          addToWorkStk(blockNum);
        } // end else
      }// end if

    }//end of while workStk


    //set initial value to work data structure
    workStkTop =0;
    currBBNum  =0;
    currBBMap  = null;
    currBBStkEmpty  = 0;
    currBBStkTop  = 0;
    currMethodName = null;
    currPendingJsr = null;
    processNextBlock = true;

    return true;
  }

  /* for bytecode like *load* or *const*/
  private void load_like(int expectType, int index, int stackWords, boolean checkIndex)
    throws Exception { 


      currBBStkTop += stackWords;
      //check stack overflow   ---- must be done for all load like instructions
      if(currBBStkTop >= currBBMap.length){
        VM.sysWrite("Verify error: stack overflow when "+ JBC_name[opcode] +
                    " in method " + currMethodName+ " \n");
        throw new Exception();
      }

      if(checkIndex == true){			//*load_<n>*
        //check register index
        if(index > currBBStkEmpty){
          VM.sysWrite("Verify error: invalid register index when " + JBC_name[opcode]
                      + " index: " + index + " in method "+ currMethodName+ " \n");
          throw new Exception();
        }
        //check register type
        boolean correct = true;
        if(expectType == V_REF)
          correct = (currBBMap[index]>=0 && currBBMap[index +stackWords -1] >= 0);	
        else
          correct = (currBBMap[index]==expectType && currBBMap[index+stackWords -1] == expectType);

        if(correct == false){
          VM.sysWrite("Verify error: register " + index +" has wrong type when " +
                      JBC_name[opcode] + " in method "+ currMethodName+ " \n");
          throw new Exception();
        }
        //update type states
        currBBMap[currBBStkTop-stackWords +1] = currBBMap[index]; 
        currBBMap[currBBStkTop] = currBBMap[index];

        //if in JSR, set this register as used
        if(inJSRSub && currPendingJsr != null && !currPendingJsr.updateOnce)
          currPendingJsr.setUsed(index);
      }
      else			//*const_*
        //update type states
        currBBMap[currBBStkTop] = currBBMap[currBBStkTop-stackWords +1] = expectType;

    }

  //for ldc* instructions
  private void ldc_like(int numOfWord, int cpindex, VM_Class declaringClass)
    throws Exception {
      currBBStkTop +=numOfWord;
      //check stack overflow 
      if(currBBStkTop >= currBBMap.length){
        VM.sysWrite("Verify error: stack overflow when "+ JBC_name[opcode] +
                    " in method " + currMethodName+ " \n");
        throw new Exception();
      }

      //check constant pool cell type
      byte cpType = declaringClass.getLiteralDescription(cpindex);
      if((numOfWord == 1 && cpType != VM_Statics.INT_LITERAL && 
          cpType != VM_Statics.FLOAT_LITERAL && cpType!=VM_Statics.STRING_LITERAL)
         ||(numOfWord == 2 && 
            cpType != VM_Statics.LONG_LITERAL && cpType!=VM_Statics.DOUBLE_LITERAL)){
        VM.sysWrite("Verify error: wrong constant pool type in method " + currMethodName+ " \n");
        throw new Exception();
      }

      //update stack top type
      switch(cpType){
        case VM_Statics.INT_LITERAL:
          currBBMap[currBBStkTop]= V_INT;
          break;
        case VM_Statics.FLOAT_LITERAL:
          currBBMap[currBBStkTop]= V_FLOAT;
          break;
        case VM_Statics.STRING_LITERAL:
          currBBMap[currBBStkTop]= VM_Type.JavaLangStringType.getDictionaryId();
          break;
        case VM_Statics.LONG_LITERAL:
          currBBMap[currBBStkTop]= currBBMap[currBBStkTop-1] = V_LONG;
          break;
        case VM_Statics.DOUBLE_LITERAL:
          currBBMap[currBBStkTop]= currBBMap[currBBStkTop-1] = V_DOUBLE;
          break;
        default:
          VM.sysWrite("Verify error: wrong constant pool type in method " + currMethodName+ " \n");
          throw new Exception();
      }
    }
  //for *2* like i2l instructions
  private void cast_like(int fromType, int toType, int fromWord, int toWord)
    throws Exception {
      boolean correct = true;
      //check from type on the top of stack
      int i;
      for(i = 0; i < fromWord; i++)
        correct = (currBBMap[currBBStkTop-i]==fromType);
      if(correct == false){
        VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                    +" in method " + currMethodName+ " \n");
        throw new Exception();
      }
      //check stack underflow
      if(currBBStkTop-fromWord +1 <= currBBStkEmpty){
        VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                    " in method " + currMethodName+ " \n");
        throw new Exception();
      }
      //check stack overflow
      if(fromWord < toWord && currBBStkTop +1 >= currBBMap.length){	
        VM.sysWrite("Verify error: stack overflow when "+ JBC_name[opcode] +
                    " in method " + currMethodName+ " \n");
        throw new Exception();
      }

      //update type states
      //pop fromType 
      for(i=0; i < fromWord; i++)
        currBBMap[currBBStkTop--]=0;
      //puch toType
      for(i=0; i < toWord; i ++)
        currBBMap[ ++currBBStkTop] = toType;

    }

  //for *store instructions
  private void store_like(int expectType, int storeWord, int index, 
                          int[] reachableHandlerBBNums, int	reachableHandlersCount) throws Exception {


    //check storeType
    int i;
    boolean correct = true;
    for(i = 0; correct && i<storeWord; i++){
      if(expectType == V_REF)
        correct = !newObjectInfo[currBBStkTop-currBBStkEmpty-1-i] && 
          ((currBBMap[currBBStkTop-i]>=0) || (currBBMap[currBBStkTop-i]==V_RETURNADDR));
      else 
        correct = (currBBMap[currBBStkTop-i] == expectType);
    }
    if(correct == false){
      VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                  +" in method " + currMethodName+ " \n");
      throw new Exception();
    }
    //check validity of index
    if(index + storeWord -1 > currBBStkEmpty || index <0 ){
      VM.sysWrite("Verify error: invalid register index when " + JBC_name[opcode]
                  + " index: " + index + " in method "+ currMethodName+ " \n");
      throw new Exception();
    }

    //check stack underflow
    if(currBBStkTop-storeWord +1 <= currBBStkEmpty){
      VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                  " in method " + currMethodName+ " \n");
      throw new Exception();
    }

    //update type states
    for(i=0; i< storeWord; i++)
      currBBMap[index+i] = currBBMap[currBBStkTop--];

    //if in JSR, set the register to be used
    if(inJSRSub && currPendingJsr != null && !currPendingJsr.updateOnce){
      currPendingJsr.setUsed(index);
      if(currBBMap[index]==V_RETURNADDR)
        currPendingJsr.updateReturnAddressLocation(index);
    }

    setHandlersMaps(currBBMap[index], index, storeWord, 
                    reachableHandlerBBNums, reachableHandlersCount); 

  }

  //for dup like instructions
  private void dup_like(int numTodup, int numTodown) throws Exception {
    //check stack overflow
    if(currBBStkTop +numTodup >= currBBMap.length){	
      VM.sysWrite("Verify error: stack overflow when "+ JBC_name[opcode] +
                  " in method " + currMethodName+ " \n");
      throw new Exception();
    }
    //check the type
    if((numTodup == 1 && currBBMap[currBBStkTop] <= V_LONG)
       || (numTodup + numTodown > 1 && 
           currBBMap[currBBStkTop-numTodup - numTodown +1]<=V_LONG &&
           currBBMap[currBBStkTop-numTodup - numTodown +2]>V_LONG )){
      VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                  +" in method " + currMethodName+ " \n");
      throw new Exception();
    }
    //update the stack
    for(int i =0; i < numTodown+numTodup; i++)
      currBBMap[currBBStkTop+numTodup-i] = currBBMap[currBBStkTop-i];
    for(int j=0; j < numTodup; j++)
      currBBMap[currBBStkTop - numTodown -j] = currBBMap[currBBStkTop+numTodup-j];
    currBBStkTop += numTodup;
  }

  //for all arithmetic instructions and logic instructions
  private void arith_like(int expectType, int numOfOpd, int numOfWord) throws Exception{
    //check stack underflow
    if(currBBStkTop-numOfWord +1 <= currBBStkEmpty){
      VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                  " in method " + currMethodName+ " \n");
      throw new Exception();
    }

    //check type
    boolean correct = true;
    for(int i=0; correct && i< numOfWord*numOfOpd; i++)
      correct = (currBBMap[currBBStkTop-i]==expectType);
    if(correct == false){
      VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                  +" in method " + currMethodName+ " \n");
      throw new Exception();
    }

    //update the stack, pop operands, push result
    if(numOfOpd != 1)
      currBBStkTop = currBBStkTop - numOfWord;
  }

  //for *return instructions
  private void return_like(int expectType, int numOfWord, VM_Method method)
    throws Exception {
      //check stack underflow
      if(currBBStkTop-numOfWord +1 <= currBBStkEmpty){
        VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                    " in method " + currMethodName+ " \n");
        throw new Exception();
      }
      //check stack type
      boolean correct = true;
      for(int i=0; i< numOfWord; i++)
        if(expectType == V_REF)
          correct = !newObjectInfo[currBBStkTop-currBBStkEmpty-1-i] && (currBBMap[currBBStkTop-i]>=0);
        else
          correct = (currBBMap[currBBStkTop-i]==expectType);

      if(correct == false){
        VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                    +" in method " + currMethodName+ " \n");
        throw new Exception();
      }

      //check return type
      VM_Type returnType = method.getReturnType();
      switch(expectType){
        case V_VOID:
          correct = returnType.isVoidType();
          break;
        case V_INT:
          correct = returnType.isIntLikeType();
          break;
        case V_LONG:
          correct = returnType.isLongType();
          break;
        case V_FLOAT:
          correct = returnType.isFloatType();
          break;
        case V_DOUBLE:
          correct = returnType.isDoubleType();
          break;
        case V_REF:
          if(currBBMap[currBBStkTop]==V_NULL)
            correct = returnType.isReferenceType();
          else
            correct = returnType.isAssignableWith(VM_TypeDictionary.
                                                  getValue(currBBMap[currBBStkTop]));
          break;
        default:
          VM.sysWrite("Verify error: invalid return type when " + JBC_name[opcode]
                      +" in method " + currMethodName+ " \n");
          throw new Exception();

      }
      if(correct == false){
        VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                    +" in method " + currMethodName+ " \n");
        throw new Exception();
      }

      currBBStkTop-= numOfWord;
      processNextBlock = false;
    }

  //for aaload like instructions
  private void aaload_like(int expectType, int numOfWord) throws Exception{
    //check stack underflow
    if((currBBStkTop-2)  < currBBStkEmpty){
      VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                  " in method " + currMethodName+ " \n");
      throw new Exception();
    }
    //check stack type
    if(currBBMap[currBBStkTop]!=V_INT || currBBMap[currBBStkTop-1]<= 0){ 
      VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                  +" in method " + currMethodName+ " \n");
      throw new Exception();
    }	

    //check whether the second top of stack is an arrayType
    VM_Type arrayType = VM_TypeDictionary.getValue(currBBMap[currBBStkTop-1]);
    if( !arrayType.isArrayType()){
      VM.sysWrite("Verify error: not arrayRef when " + JBC_name[opcode] +
                  " in method " + currMethodName+ " \n");
      throw new Exception();
    }

    //check the compatibility of the expectType and the element type of array
    VM_Type eleType = ((VM_Array)arrayType).getElementType();
    if((eleType.isIntLikeType() && expectType != V_INT) ||
       (eleType.isLongType() && expectType != V_LONG) ||
       (eleType.isFloatType() && expectType != V_FLOAT) ||
       (eleType.isDoubleType() && expectType != V_DOUBLE) ||
       (eleType.isReferenceType() && expectType != V_REF)){
      VM.sysWrite("Verify error: incompatible element type when " + JBC_name[opcode]
                  +" in method " + currMethodName+ " \n");
      throw new Exception();
    }

    //update the stack type
    currBBStkTop -= 2;
    for(int i = 0; i< numOfWord; i++)
      if(expectType ==V_REF)
        currBBMap[++currBBStkTop] = eleType.getDictionaryId();
      else
        currBBMap[++currBBStkTop] = expectType;

  }

  //for aastore like instructions
  private void aastore_like(int expectType, int numOfWord)
    throws Exception {
      //check stack underflow
      if((currBBStkTop-2-numOfWord)  < currBBStkEmpty){
        VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                    " in method " + currMethodName+ " \n");
        throw new Exception();
      }

      //check the value type
      boolean correct = true;
      for(int i=0; correct && i<numOfWord; i++)
        if(expectType == V_REF)
          correct = !newObjectInfo[currBBStkTop-currBBStkEmpty-1-i] && (currBBMap[currBBStkTop-i]>= 0);
        else 
          correct = (currBBMap[currBBStkTop-i] == expectType);

      if(correct == false){
        VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                    +" in method " + currMethodName+ " \n");
        throw new Exception();
      }
      //check index and arrayRef type
      if(currBBMap[currBBStkTop-numOfWord]!=V_INT || currBBMap[currBBStkTop-numOfWord-1]<= 0){ 
        VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                    +" in method " + currMethodName+ " \n");
        throw new Exception();
      }	

      //check whether the third top of stack is an arrayType
      VM_Type arrayType = VM_TypeDictionary.getValue(currBBMap[currBBStkTop- numOfWord - 1]);
      if( !arrayType.isArrayType()){
        VM.sysWrite("Verify error: not arrayRef when " + JBC_name[opcode] +
                    " in method " + currMethodName+ " \n");
        throw new Exception();
      }

      //check the compatibility of the expectType and the element type of array
      VM_Type eleType = ((VM_Array)arrayType).getElementType();
      if((eleType.isIntLikeType() && expectType != V_INT) ||
         (eleType.isLongType() && expectType != V_LONG) ||
         (eleType.isFloatType() && expectType != V_FLOAT) ||
         (eleType.isDoubleType() && expectType != V_DOUBLE) ||
         (eleType.isReferenceType() && (expectType!=V_REF || (currBBMap[currBBStkTop]!=V_NULL 
                                                              && !eleType.isAssignableWith(VM_TypeDictionary.getValue(currBBMap[currBBStkTop])))))){
        VM.sysWrite("Verify error: incompatible element type when " + JBC_name[opcode]
                    + " in method " + currMethodName+ " \n");
        throw new Exception();
      }

      //update the stack type, pop all three 
      currBBStkTop = currBBStkTop - 2 - numOfWord;

    }

  //for cmp_like instructions, both branch or non-branch, just for type check
  private void cmp_like(int expectType, int numOfWord, int numOfOpd, int pushWord)
    throws Exception {
      //check stack underflow
      if((currBBStkTop-numOfWord*numOfOpd)  < currBBStkEmpty){
        VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                    " in method " + currMethodName+ " \n");
        throw new Exception();
      }
      //check stack type
      boolean correct = true;
      for(int i=0; i< numOfWord*numOfOpd; i++)
        if(expectType == V_REF)
          correct = (currBBMap[currBBStkTop-i] >= 0);
        else
          correct = (currBBMap[currBBStkTop-i]==expectType);
      if(correct == false){
        VM.sysWrite("Verify error: stack has wrong type when " + JBC_name[opcode]
                    +" in method " + currMethodName+ " \n");
        throw new Exception();
      }
      //update the stack
      currBBStkTop -= numOfWord*numOfOpd;
      if(pushWord == 1)
        currBBMap[++currBBStkTop] = V_INT;
    }

  //for getstatic and getfield
  private void get_like(VM_Field field, boolean isStatic)
    throws Exception {

      //if not static, check whether object type is compatible with field's declaring class
      if(!isStatic){
        //check stack underflow
        if(currBBStkTop-1 < currBBStkEmpty){
          VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                      " in method " + currMethodName+ " \n");
          throw new Exception();
        }
        //check the compatibility
        if(currBBMap[currBBStkTop]<0 || currBBMap[currBBStkTop]!=V_NULL
           && !field.getDeclaringClass().
           isAssignableWith(VM_TypeDictionary.getValue(currBBMap[currBBStkTop]))){
          VM.sysWrite("Verify error: incompatible object reference when " + JBC_name[opcode]
                      + " in method " + currMethodName+ " \n");
          throw new Exception();
        }

        if(newObjectInfo[currBBStkTop-currBBStkEmpty-1]){	//uninitialized object
          VM.sysWrite("Verify error: uninitialized object reference when getfield " + 
                      field + " in method " + currMethodName+ " \n");
          throw new Exception();
        }
        //pop the "this" reference
        currBBStkTop --;
      }

      VM_Type fieldType = field.getType();
      //check stack overflow
      currBBStkTop += fieldType.getStackWords();
      if(currBBStkTop >= currBBMap.length){
        VM.sysWrite("Verify error: stack overflow when "+ JBC_name[opcode] +
                    " in method " + currMethodName+ " \n");
        throw new Exception();
      }
      //push the field onto the stack
      if(fieldType.isIntLikeType())
        currBBMap[currBBStkTop] = V_INT;
      else if(fieldType.isFloatType())
        currBBMap[currBBStkTop] = V_FLOAT;
      else if(fieldType.isLongType())
        currBBMap[currBBStkTop] = currBBMap[currBBStkTop-1] = V_LONG ;
      else if(fieldType.isDoubleType())
        currBBMap[currBBStkTop] = currBBMap[currBBStkTop-1] = V_DOUBLE ;
      else if(fieldType.isReferenceType())
        currBBMap[currBBStkTop] = fieldType.getDictionaryId(); 

    }

  //for putstatic and putfield
  private void put_like(VM_Field field, boolean isStatic)
    throws Exception {

      VM_Type fieldType = field.getType();
      //check stack underflow
      if(currBBStkTop-fieldType.getStackWords() < currBBStkEmpty){
        VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                    " in method " + currMethodName+ " \n");
        throw new Exception();
      }

      //pop the field from the stack
      boolean correct = true;
      if(fieldType.isIntLikeType())
        correct = (currBBMap[currBBStkTop] == V_INT);
      else if(fieldType.isFloatType())
        correct = (currBBMap[currBBStkTop] == V_FLOAT);
      else if(fieldType.isLongType())
        correct = (currBBMap[currBBStkTop] == V_LONG && currBBMap[currBBStkTop-1] == V_LONG );
      else if(fieldType.isDoubleType())
        correct = (currBBMap[currBBStkTop] == V_DOUBLE && currBBMap[currBBStkTop-1] == V_DOUBLE) ;
      else if(fieldType.isReferenceType())
        correct = !newObjectInfo[currBBStkTop-currBBStkEmpty -1] && 
          ((currBBMap[currBBStkTop] == V_NULL || 
            fieldType.isAssignableWith(VM_TypeDictionary.getValue(currBBMap[currBBStkTop]))));
      if(correct == false){
        VM.sysWrite("Verify error: incompatible field type when " + JBC_name[opcode]
                    + " in method " + currMethodName+ " \n");
      }
      currBBStkTop -= fieldType.getStackWords();

      //if not static, check whether object type is compatible with field's declaring class
      if(!isStatic){
        //check stack underflow
        if(currBBStkTop-1 < currBBStkEmpty){
          VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                      " in method " + currMethodName+ " \n");
          throw new Exception();
        }
        //check the compatibility
        if(currBBMap[currBBStkTop]<0 || !field.getDeclaringClass().
           isAssignableWith(VM_TypeDictionary.getValue(currBBMap[currBBStkTop]))){
          VM.sysWrite("Verify error: incompatible object reference when " + JBC_name[opcode]
                      + " in method " + currMethodName+ " \n");
          throw new Exception();
        }

        if(newObjectInfo[currBBStkTop-currBBStkEmpty-1]){	//uninitialized object
          VM.sysWrite("Verify error: uninitialized object reference when putfield " + 
                      field + " in method " + currMethodName+ " \n");
          throw new Exception();
        }
        //pop the "this" reference
        currBBStkTop --;
      }


    }

  //merge type information and add new block to work list
  private void MergeMaps(short brBBNum, int[] newBBMap, int newBBStkTop)  throws Exception {

    //if the destination block doesn't already have a map, then use this map as its map
    if(typeMaps[brBBNum] == null){
      typeMaps[brBBNum] = new int[newBBMap.length];
      for(int i=0; i<=newBBStkTop; i++)
        typeMaps[brBBNum][i] = newBBMap[i];
      blockStkTop[brBBNum] = newBBStkTop;
      addToWorkStk(brBBNum);
    }
    else{ 
      //if the destination block already has a map
      //fist check the height of stack
      if(blockStkTop[brBBNum] != newBBStkTop){
        VM.sysWrite("Verify error: different stack height when merge type maps in method " 
                    + currMethodName+ " \n");
        throw new Exception();
      }

      boolean changed = false;
      //second compare each cell of the map, use the least common type as new map cell
      for(int j=0; j<=newBBStkTop; j++){
        int newType = newBBMap[j];
        int originalType = typeMaps[brBBNum][j];
        if(newType == originalType)
          continue;
        int resultType = MergeOneCell(newType, originalType);
        if(resultType != originalType){
          typeMaps[brBBNum][j]=resultType;
          changed = true;
        }
        /*
        //exactly the same
        if(typeMaps[brBBNum][j]==newBBMap[j])
        continue;

        //one of them is V_NULL, use the not null type
        if(typeMaps[brBBNum][j]==V_NULL && newBBMap[j]>0){
        typeMaps[brBBNum][j] = newBBMap[j];
        changed = true;
        continue;
        }
        if(newBBMap[j] == V_NULL && typeMaps[brBBNum][j]>0)
        continue;

        //both are reference type
        if(typeMaps[brBBNum][j]>0 && newBBMap[j]>0){
        int oldtype = typeMaps[brBBNum][j];
        typeMaps[brBBNum][j] = findCommonSuperClassId(typeMaps[brBBNum][j], newBBMap[j]);
        if(oldtype != typeMaps[brBBNum][j])
        changed = true;
        continue;
        }
        //other situation, set to undefined
        if(typeMaps[brBBNum][j] == V_UNDEF)
        continue;

        typeMaps[brBBNum][j] = V_UNDEF;
        changed = true;
         */
      } // end of for

      if(changed){
        addToWorkStk(brBBNum);
      }
    }//end if else

  }

  private int MergeOneCell(int newType, int originalType) throws Exception{

    //exactly the same
    if(originalType == newType)
      return originalType;

    //one of them is V_NULL, use the not null type
    if(originalType ==V_NULL && newType >0)
      return newType;

    if(newType == V_NULL && originalType>0)
      return originalType;

    //both are reference type
    if(originalType > 0 && newType>0)
      return findCommonSuperClassId(originalType, newType);

    //other situation, set to undefined
    if(originalType == V_UNDEF)
      return originalType;

    return V_UNDEF;
  }

  private void processBranchBB(short brBBNum) throws Exception {


    MergeMaps(brBBNum, currBBMap, currBBStkTop);
    //####
    if(inJSRSub && currPendingJsr != null && bbPendingJsrs[brBBNum] == null)
      bbPendingJsrs[brBBNum] = currPendingJsr;

  }


  //computer maps for the instructions right after "jsr"
  private void computeJSRNextMaps() throws Exception {

    currPendingJsr.newEndMap(currBBMap, currBBStkTop);

    for(int i=0; i< currPendingJsr.successorLength; i ++){
      short successorBBNum = currPendingJsr.getSuccessorBBNum(i);		
      int[] preMap = currPendingJsr.getSuccessorPreMap(i);		
      int[] newMap = new int[currBBMap.length];
      boolean[] used = currPendingJsr.getUsedMap();
      for(int j =0; j <= currBBStkEmpty; j++){
        if(used[j])
          newMap[j] = currBBMap[j];
        else
          newMap[j] = preMap[j];
      }	
      for(int j = currBBStkEmpty+1; j <= currBBStkTop; j++)
        newMap[j] = currBBMap[j];

      currPendingJsr.addUsedInfoToParent();

      MergeMaps(successorBBNum, newMap, currBBStkTop); 
    }
  }

  //add a new block number on to the top of the work list
  private void addToWorkStk(short blockNum) {
    workStkTop++;
    if (workStkTop >= workStk.length) {
      short[] biggerQ = new short[workStk.length + 20];
      for (int i=0; i<workStk.length; i++) {
        biggerQ[i] = workStk[i];
      }
      workStk = biggerQ;
      biggerQ = null;
    }
    workStk[workStkTop] = blockNum;
    //VM.sysWrite("-----------add " + blockNum + " to worklist\n");
  }

  private void addUniqueToWorkStk(short blockNum) {
    if ((workStkTop+1) >= workStk.length) {
      short[] biggerQ = new short[workStk.length + 20];
      boolean matchFound = false;
      for (int i=0; i<workStk.length; i++) {
        biggerQ[i] = workStk[i];
        matchFound =  (workStk[i] == blockNum);
      }
      workStk = biggerQ;
      biggerQ = null;
      if (matchFound) return ;
    }
    else {
      for (int i=0; i<=workStkTop; i++) {
        if (workStk[i] == blockNum)
          return;
      }
    }
    workStkTop++;
    workStk[workStkTop] = blockNum;
    return;
  }
  private int getIntOffset(int index, byte[] bytecodes){
    return (int)((((int)bytecodes[index+1])<<24) |
                 ((((int)bytecodes[index+2])&0xFF)<<16) |
                 ((((int)bytecodes[index+3])&0xFF)<<8) |
                 (((int)bytecodes[index+4])&0xFF));
  }

  /*assumption: 
   * 1.both id1 and id2 are reference type 
   * 2.they are not the same type
   * 3.none of them is null type
   */
  private int findCommonSuperClassId(int id1, int id2) 
    throws VM_ResolutionException {
      VM_Type t1 = VM_TypeDictionary.getValue(id1);
      VM_Type t2 = VM_TypeDictionary.getValue(id2);

      // Strip off all array junk.
      int arrayDimensions = 0;
      while (t1.isArrayType() && t2.isArrayType()) {
        ++arrayDimensions;
        t1 = ((VM_Array)t1).getElementType();
        t2 = ((VM_Array)t2).getElementType();
      }
      // at this point, they are not both array types.
      // if one is a primitive, then we want an object array of one less
      // dimensionality

      if (t1.isPrimitiveType() || t2.isPrimitiveType()) {
        VM_Type type = VM_Type.JavaLangObjectType;
        --arrayDimensions;
        while (arrayDimensions-- > 0)
          type = type.getArrayTypeForElementType();
        return  type.getDictionaryId();
      }

      // neither is a primitive, and they are not both array types.
      if (!t1.isClassType() || !t2.isClassType()) {
        // one is a class type, while the other isn't.
        VM_Type type = VM_Type.JavaLangObjectType;
        while (arrayDimensions-- > 0)
          type = type.getArrayTypeForElementType();
        return  type.getDictionaryId();
      }

      // they both must be class types.
      // technique: push heritage of each type on a separate stack,
      // then find the highest point in the stack where they differ.
      VM_Class c1 = (VM_Class)t1;
      VM_Class c2 = (VM_Class)t2;

      if(!c1.isLoaded())
        c1.load();
      if(!c2.isLoaded())
        c2.load();

      Stack s1 = new Stack();
      do {
        s1.push(c1);
        c1 = c1.getSuperClass();
      } while (c1 != null);

      Stack s2 = new Stack();
      do {
        s2.push(c2);
        c2 = c2.getSuperClass();
      }while (c2 != null);

      VM_Type best = VM_Type.JavaLangObjectType;
      while (!s1.empty() && !s2.empty()) {
        VM_Class temp = (VM_Class)s1.pop();
        if (temp == s2.pop())
          best = temp; 
        else 
          break;
      }
      while (arrayDimensions-- > 0)
        best = best.getArrayTypeForElementType();
      return  best.getDictionaryId();
    }

  //for invoke* instructions
  private void processInvoke(VM_Method calledMethod, boolean isStatic)
    throws Exception {

      VM_Type[] parameterTypes = calledMethod.getParameterTypes();
      int paramNum = parameterTypes.length;

      //pop the arguments and check the type at the same time
      for(int i=paramNum-1; i>=0; i--){
        int numOfWord = parameterTypes[i].getStackWords();
        //check stack underflow
        if(currBBStkTop-numOfWord < currBBStkEmpty){
          VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                      " in method " + currMethodName+ " \n");
          throw new Exception();
        }
        //check parameter type
        boolean correct = true;
        if(parameterTypes[i].isIntLikeType())
          correct = (currBBMap[currBBStkTop] == V_INT);
        else if(parameterTypes[i].isFloatType())
          correct = (currBBMap[currBBStkTop] == V_FLOAT);
        else if(parameterTypes[i].isLongType())
          correct = (currBBMap[currBBStkTop] == V_LONG && currBBMap[currBBStkTop-1] == V_LONG );
        else if(parameterTypes[i].isDoubleType())
          correct = (currBBMap[currBBStkTop] == V_DOUBLE && currBBMap[currBBStkTop-1] == V_DOUBLE) ;
        else if(parameterTypes[i].isReferenceType())
          correct = (currBBMap[currBBStkTop] == V_NULL || 
                     parameterTypes[i].isAssignableWith(VM_TypeDictionary.getValue(currBBMap[currBBStkTop])));
        if(correct == false){
          VM.sysWrite("Verify error: incompatible parameter when call " + calledMethod.getName() +
                      " in method " + currMethodName+ " \n");
          throw new Exception();
        }

        //pop this argument
        currBBStkTop -= numOfWord;
      }//end of for

      //if not static, check call object type
      if(!isStatic){
        //check stack underflow
        if(currBBStkTop-1 < currBBStkEmpty){
          VM.sysWrite("Verify error: stack underflow when "+ JBC_name[opcode] +
                      " in method " + currMethodName+ " \n");
          throw new Exception();
        }

        //this isn't a reference type or isn't a compatible reference type
        if(currBBMap[currBBStkTop]<0 || !calledMethod.getDeclaringClass().
           isAssignableWith(VM_TypeDictionary.getValue(currBBMap[currBBStkTop]))){
          VM.sysWrite("Verify error: incompatible this reference when call " + calledMethod +
                      " in method " + currMethodName+ " \n");
          throw new Exception();
        }

        if(calledMethod.getName() != VM_ClassLoader.StandardObjectInitializerMethodName){
          if(newObjectInfo[currBBStkTop-currBBStkEmpty-1]){	//uninitialized object
            VM.sysWrite("Verify error: uninitialized object reference when call " + 
                        calledMethod + " in method " + currMethodName+ " \n");
            throw new Exception();
          }
        }else{ //set the new object to be initialized
          if( newObjectInfo[currBBStkTop - currBBStkEmpty -1]){
            newObjectInfo[currBBStkTop - currBBStkEmpty -1] = false;
            if((currBBStkTop-currBBStkEmpty) >= 2)
              newObjectInfo[currBBStkTop - currBBStkEmpty -2] = false;
          }
        }
        //pop this reference
        currBBStkTop--;
      }//end if static

      //add the return type to the stack
      VM_Type returnType = calledMethod.getReturnType();
      if(returnType.getStackWords()!=0){
        currBBStkTop += returnType.getStackWords();
        //check stack overflow
        if(currBBStkTop >= currBBMap.length){
          VM.sysWrite("Verify error: stack overflow when "+ JBC_name[opcode] +
                      " in method " + currMethodName+ " \n");
          throw new Exception();
        }

        if(returnType.isIntLikeType())
          currBBMap[currBBStkTop] = V_INT;
        else if(returnType.isFloatType())
          currBBMap[currBBStkTop] = V_FLOAT;
        else if(returnType.isLongType())
          currBBMap[currBBStkTop] = currBBMap[currBBStkTop-1] = V_LONG ;
        else if(returnType.isDoubleType())
          currBBMap[currBBStkTop] = currBBMap[currBBStkTop-1] = V_DOUBLE ;
        else if(returnType.isReferenceType())
          currBBMap[currBBStkTop] = returnType.getDictionaryId(); 
      }

    }

  private void setHandlersMaps(int newType, int localVariable, int wordCount, int[] reachableHandlerBBNums, 
                               int reachableHandlersCount)  throws Exception{

    for (int i=0; i<reachableHandlersCount; i++) {
      for(int j=0; j < wordCount; j++){
        int originalType = typeMaps[reachableHandlerBBNums[i]][localVariable +j];
        if( originalType == newType)
          continue;
        typeMaps[reachableHandlerBBNums[i]][localVariable+j] = MergeOneCell(newType, originalType);
      }
    }
  }
}
