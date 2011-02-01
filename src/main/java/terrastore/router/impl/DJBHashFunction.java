/**
 * Copyright 2009 - 2011 Sergio Bossa (sergio.bossa@gmail.com)
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package terrastore.router.impl;

/**
 * Hash function based on Bernstein algorithm at: http://www.partow.net/programming/hashfunctions/#DJBHashFunction.
 *
 * @author Arash Partow
 * @author Sergio Bossa
 */
public class DJBHashFunction implements HashFunction {

    public int hash(String value, int maxValue) {
        long hash = doHash(value);
        return (int) Math.abs(hash % maxValue);
    }

    @Override
    public String toString() {
        return this.getClass().getName();
    }

    private long doHash(String str) {
        long hash = 5381;

        for (int i = 0; i < str.length(); i++) {
            hash = ((hash << 5) + hash) + str.charAt(i);
        }

        return hash;
    }
}
