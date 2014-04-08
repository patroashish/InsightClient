/* Copyright 2013 Wisconsin Wireless and NetworkinG Systems (WiNGS) Lab, University of Wisconsin Madison.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wisc.insightlib;

public class FloatByteArrayUtil 
{  
    private static final int MASK = 0xff;  
  
    /** 
     * Convert byte array (of size 4) to float.
     * 
     * @param array 
     * 
     * @return float value
     */  
    public static float byteArrayToFloat(byte array[]) {  
        int bits = 0;  
        int i = 0;  
        for (int shifter = 3; shifter >= 0; shifter--) {  
            bits |= (array[i] & MASK) << (shifter * 8);  
            i++;  
        }  
  
        return Float.intBitsToFloat(bits);  
    }  
  
    /** 
     * Convert float to byte array (of size 4). 
     * 
     * @param floatVal
     *  
     * @return byte array 
     */  
    public static byte[] floatToByteArray(float floatVal) {  
        int i = Float.floatToRawIntBits(floatVal);  
        return intToByteArray(i);  
    }  
  
    /** 
     * Convert int to byte array (of size 4).
     *  
     * @param param - integer
     *  
     * @return byte array
     */  
    public static byte[] intToByteArray(int param) {  
        byte[] result = new byte[4];  
        for (int i = 0; i < 4; i++) {  
            int offset = (result.length - 1 - i) * 8;  
            result[i] = (byte) ((param >>> offset) & MASK);  
        }  
        return result;  
    } 
    
    /** 
     * Convert byte array (of size 4) to short.
     * 
     * @param param - byte array
     *  
     * @return short value 
     */  
    public static short byteArrayToShort(byte[] param) {  
    	return (short) (((param[0] & MASK) << 8) | (param[1] & MASK)); 
    } 
}
