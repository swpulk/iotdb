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
package org.apache.iotdb.db.mpp.plan.plan.node.process;

import org.apache.iotdb.common.rpc.thrift.TConsensusGroupId;
import org.apache.iotdb.common.rpc.thrift.TConsensusGroupType;
import org.apache.iotdb.common.rpc.thrift.TRegionReplicaSet;
import org.apache.iotdb.commons.exception.IllegalPathException;
import org.apache.iotdb.db.metadata.path.MeasurementPath;
import org.apache.iotdb.db.mpp.plan.plan.node.PlanNodeDeserializeHelper;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.PlanNodeId;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.process.OffsetNode;
import org.apache.iotdb.db.mpp.plan.planner.plan.node.source.SeriesScanNode;
import org.apache.iotdb.db.mpp.plan.statement.component.OrderBy;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.filter.GroupByFilter;

import com.google.common.collect.Sets;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;

public class OffsetNodeSerdeTest {

  @Test
  public void testSerializeAndDeserialize() throws IllegalPathException {
    SeriesScanNode seriesScanNode =
        new SeriesScanNode(
            new PlanNodeId("TestSeriesScanNode"),
            new MeasurementPath("root.sg.d1.s1", TSDataType.INT32),
            Sets.newHashSet("s1"),
            OrderBy.TIMESTAMP_DESC,
            new GroupByFilter(1, 2, 3, 4),
            null,
            100,
            100,
            new TRegionReplicaSet(
                new TConsensusGroupId(TConsensusGroupType.DataRegion, 1), new ArrayList<>()));
    OffsetNode offsetNode = new OffsetNode(new PlanNodeId("TestOffsetNode"), seriesScanNode, 2);

    ByteBuffer byteBuffer = ByteBuffer.allocate(2048);
    offsetNode.serialize(byteBuffer);
    byteBuffer.flip();
    assertEquals(PlanNodeDeserializeHelper.deserialize(byteBuffer), offsetNode);
  }
}
