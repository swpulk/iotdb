/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.mpp.execution.operator.process.fill.constant;

import org.apache.iotdb.db.mpp.execution.operator.process.fill.IFill;
import org.apache.iotdb.tsfile.read.common.block.TsBlock;
import org.apache.iotdb.tsfile.read.common.block.column.BinaryColumn;
import org.apache.iotdb.tsfile.read.common.block.column.Column;
import org.apache.iotdb.tsfile.read.common.block.column.RunLengthEncodedColumn;
import org.apache.iotdb.tsfile.utils.Binary;

import java.util.Optional;

public class BinaryConstantFill implements IFill {

  // fill value
  private final Binary value;
  // index of the column which is need to be filled
  private final int columnIndex;
  // used for constructing RunLengthEncodedColumn, size of it must be 1
  private final Binary[] valueArray;

  public BinaryConstantFill(Binary value, int columnIndex) {
    this.value = value;
    this.columnIndex = columnIndex;
    this.valueArray = new Binary[] {value};
  }

  @Override
  public Column fill(TsBlock tsBlock) {
    Column column = tsBlock.getColumn(columnIndex);
    int size = column.getPositionCount();
    // if this column doesn't have any null value, or it's empty, just return itself;
    if (!column.mayHaveNull() || size == 0) {
      return column;
    }
    // if its values are all null
    if (column instanceof RunLengthEncodedColumn) {
      return new RunLengthEncodedColumn(new BinaryColumn(1, Optional.empty(), valueArray), size);
    } else {
      Binary[] array = new Binary[size];
      for (int i = 0; i < size; i++) {
        if (column.isNull(i)) {
          array[i] = value;
        } else {
          array[i] = column.getBinary(i);
        }
      }
      return new BinaryColumn(size, Optional.empty(), array);
    }
  }
}
