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
class bug {

   static byte x[] = {-1};
   
   public static void main(String args[]) {
      int X = x[0];
      int Y;
      if (X == 0)
         Y = 0;
      else if (X > 0)
         Y = 1;
     else
         Y = -1;
     System.out.println(Y);
   }
}
