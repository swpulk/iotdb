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

package org.apache.iotdb.db.query.udf.core.layer;

import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.qp.physical.crud.UDTFPlan;
import org.apache.iotdb.db.query.dataset.IUDFInputDataSet;
import org.apache.iotdb.db.query.dataset.RawQueryDataSetWithValueFilter;
import org.apache.iotdb.db.query.dataset.UDFRawQueryInputDataSetWithoutValueFilter;
import org.apache.iotdb.db.query.reader.series.IReaderByTimestamp;
import org.apache.iotdb.db.query.reader.series.ManagedSeriesReader;
import org.apache.iotdb.db.query.udf.core.layer.SafetyLine.SafetyPile;
import org.apache.iotdb.db.query.udf.core.reader.LayerPointReader;
import org.apache.iotdb.db.query.udf.datastructure.row.ElasticSerializableRowRecordList;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.query.timegenerator.TimeGenerator;
import org.apache.iotdb.tsfile.utils.Binary;

import java.io.IOException;
import java.util.List;

public class RawQueryInputLayer {

  private IUDFInputDataSet queryDataSet;
  private TSDataType[] dataTypes;
  private int timestampIndex;

  private ElasticSerializableRowRecordList rowRecordList;
  private SafetyLine safetyLine;

  /** InputLayerWithoutValueFilter */
  public RawQueryInputLayer(
      long queryId, float memoryBudgetInMB, UDTFPlan queryPlan, List<ManagedSeriesReader> readers)
      throws QueryProcessException, IOException, InterruptedException {
    construct(
        queryId,
        memoryBudgetInMB,
        new UDFRawQueryInputDataSetWithoutValueFilter(queryId, queryPlan, readers));
  }

  /** InputLayerWithValueFilter */
  public RawQueryInputLayer(
      long queryId,
      float memoryBudgetInMB,
      List<PartialPath> paths,
      List<TSDataType> dataTypes,
      TimeGenerator timeGenerator,
      List<IReaderByTimestamp> readers,
      List<List<Integer>> readerToIndexList,
      List<Boolean> cached)
      throws QueryProcessException {
    construct(
        queryId,
        memoryBudgetInMB,
        new RawQueryDataSetWithValueFilter(
            paths, dataTypes, timeGenerator, readers, readerToIndexList, cached, true));
  }

  public RawQueryInputLayer(long queryId, float memoryBudgetInMB, IUDFInputDataSet queryDataSet)
      throws QueryProcessException {
    construct(queryId, memoryBudgetInMB, queryDataSet);
  }

  private void construct(long queryId, float memoryBudgetInMB, IUDFInputDataSet queryDataSet)
      throws QueryProcessException {
    this.queryDataSet = queryDataSet;
    dataTypes = queryDataSet.getDataTypes().toArray(new TSDataType[0]);
    timestampIndex = dataTypes.length;
    rowRecordList =
        new ElasticSerializableRowRecordList(
            dataTypes, queryId, memoryBudgetInMB, 1 + dataTypes.length / 2);
    safetyLine = new SafetyLine();
  }

  public void updateRowRecordListEvictionUpperBound() {
    rowRecordList.setEvictionUpperBound(safetyLine.getSafetyLine());
  }

  public int getInputColumnCount() {
    return dataTypes.length;
  }

  public LayerPointReader constructTimePointReader() {
    return new TimePointReader();
  }

  public LayerPointReader constructValuePointReader(int columnIndex) {
    return new ValuePointReader(columnIndex);
  }

  private abstract class AbstractLayerPointReader implements LayerPointReader {

    protected final SafetyPile safetyPile;

    protected int currentRowIndex;

    protected boolean hasCachedRowRecord;
    protected Object[] cachedRowRecord;

    AbstractLayerPointReader() {
      safetyPile = safetyLine.addSafetyPile();

      hasCachedRowRecord = false;
      cachedRowRecord = null;
      currentRowIndex = -1;
    }

    @Override
    public final long currentTime() throws IOException {
      return (long) cachedRowRecord[timestampIndex];
    }

    @Override
    public final boolean isConstantPointReader() {
      return false;
    }

    @Override
    public final void readyForNext() {
      hasCachedRowRecord = false;
      cachedRowRecord = null;

      safetyPile.moveForwardTo(currentRowIndex + 1);
    }
  }

  private class ValuePointReader extends AbstractLayerPointReader {

    protected final int columnIndex;

    ValuePointReader(int columnIndex) {
      super();
      this.columnIndex = columnIndex;
    }

    @Override
    public boolean next() throws IOException, QueryProcessException {
      if (hasCachedRowRecord) {
        return true;
      }

      for (int i = currentRowIndex + 1; i < rowRecordList.size(); ++i) {
        Object[] rowRecordCandidate = rowRecordList.getRowRecord(i);
        // If any field in the current row are null, we should treat this row as valid.
        // Because in a GROUP BY time query, we must return every time window record even if there's
        // no data.
        // Under the situation, if hasCachedRowRecord is false, this row will be skipped and the
        // result is not as our expected.
        if (rowRecordCandidate[columnIndex] != null || rowRecordList.fieldsHasAnyNull(i)) {
          hasCachedRowRecord = true;
          cachedRowRecord = rowRecordCandidate;
          currentRowIndex = i;
          break;
        }
      }

      if (!hasCachedRowRecord) {
        while (queryDataSet.hasNextRowInObjects()) {
          Object[] rowRecordCandidate = queryDataSet.nextRowInObjects();
          rowRecordList.put(rowRecordCandidate);
          if (rowRecordCandidate[columnIndex] != null
              || rowRecordList.fieldsHasAnyNull(rowRecordList.size() - 1)) {
            hasCachedRowRecord = true;
            cachedRowRecord = rowRecordCandidate;
            currentRowIndex = rowRecordList.size() - 1;
            break;
          }
        }
      }

      return hasCachedRowRecord;
    }

    @Override
    public TSDataType getDataType() {
      return dataTypes[columnIndex];
    }

    @Override
    public int currentInt() {
      return (int) cachedRowRecord[columnIndex];
    }

    @Override
    public long currentLong() {
      return (long) cachedRowRecord[columnIndex];
    }

    @Override
    public float currentFloat() {
      return (float) cachedRowRecord[columnIndex];
    }

    @Override
    public double currentDouble() {
      return (double) cachedRowRecord[columnIndex];
    }

    @Override
    public boolean currentBoolean() {
      return (boolean) cachedRowRecord[columnIndex];
    }

    @Override
    public boolean isCurrentNull() {
      return cachedRowRecord[columnIndex] == null;
    }

    @Override
    public Binary currentBinary() {
      return (Binary) cachedRowRecord[columnIndex];
    }
  }

  private class TimePointReader extends AbstractLayerPointReader {

    @Override
    public boolean next() throws QueryProcessException, IOException {
      if (hasCachedRowRecord) {
        return true;
      }

      final int nextIndex = currentRowIndex + 1;
      if (nextIndex < rowRecordList.size()) {
        hasCachedRowRecord = true;
        cachedRowRecord = rowRecordList.getRowRecord(nextIndex);
        currentRowIndex = nextIndex;
        return true;
      }

      if (queryDataSet.hasNextRowInObjects()) {
        Object[] rowRecordCandidate = queryDataSet.nextRowInObjects();
        rowRecordList.put(rowRecordCandidate);

        hasCachedRowRecord = true;
        cachedRowRecord = rowRecordCandidate;
        currentRowIndex = rowRecordList.size() - 1;
        return true;
      }

      return false;
    }

    @Override
    public TSDataType getDataType() {
      return TSDataType.INT64;
    }

    @Override
    public int currentInt() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public long currentLong() throws IOException {
      return (long) cachedRowRecord[timestampIndex];
    }

    @Override
    public float currentFloat() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public double currentDouble() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean currentBoolean() throws IOException {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isCurrentNull() throws IOException {
      return false;
    }

    @Override
    public Binary currentBinary() throws IOException {
      throw new UnsupportedOperationException();
    }
  }
}
