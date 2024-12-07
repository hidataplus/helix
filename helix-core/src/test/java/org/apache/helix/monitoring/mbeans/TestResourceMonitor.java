package org.apache.helix.monitoring.mbeans;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.helix.ZNRecord;
import org.apache.helix.model.ExternalView;
import org.apache.helix.model.IdealState;
import org.apache.helix.tools.DefaultIdealStateCalculator;
import org.testng.Assert;
import org.testng.annotations.Test;

public class TestResourceMonitor {
  String _clusterName = "Test-cluster";
  String _dbName = "TestDB";
  int _replicas = 3;
  int _partitions = 50;

  @Test()
  public void testReportData() {
    final int n = 5;
    ResourceMonitor monitor = new ResourceMonitor(_clusterName, _dbName);

    List<String> instances = new ArrayList<String>();
    for (int i = 0; i < n; i++) {
      String instance = "localhost_" + (12918 + i);
      instances.add(instance);
    }

    ZNRecord idealStateRecord =
        DefaultIdealStateCalculator.calculateIdealState(instances, _partitions, _replicas, _dbName,
            "MASTER", "SLAVE");
    IdealState idealState = new IdealState(idealStateRecord);
    ExternalView externalView = new ExternalView(idealStateRecord);

    monitor.updateResource(externalView, idealState, "MASTER");

    Assert.assertEquals(monitor.getDifferenceWithIdealStateGauge(), 0);
    Assert.assertEquals(monitor.getErrorPartitionGauge(), 0);
    Assert.assertEquals(monitor.getExternalViewPartitionGauge(), _partitions);
    Assert.assertEquals(monitor.getPartitionGauge(), _partitions);
    // monitor.getBeanName();

    final int m = n - 1;
    for (int i = 0; i < m; i++) {
      Map<String, String> map = externalView.getStateMap(_dbName + "_" + 3 * i);
      String key = map.keySet().toArray()[0].toString();
      map.put(key, "ERROR");
      externalView.setStateMap(_dbName + "_" + 3 * i, map);
    }

    monitor.updateResource(externalView, idealState, "MASTER");
    Assert.assertEquals(monitor.getDifferenceWithIdealStateGauge(), 0);
    Assert.assertEquals(monitor.getErrorPartitionGauge(), m);
    Assert.assertEquals(monitor.getExternalViewPartitionGauge(), _partitions);
    Assert.assertEquals(monitor.getPartitionGauge(), _partitions);

    for (int i = 0; i < n; i++) {
      externalView.getRecord().getMapFields().remove(_dbName + "_" + 4 * i);
    }

    monitor.updateResource(externalView, idealState, "MASTER");
    Assert.assertEquals(monitor.getDifferenceWithIdealStateGauge(), n * (_replicas + 1));
    Assert.assertEquals(monitor.getErrorPartitionGauge(), 3);
    Assert.assertEquals(monitor.getExternalViewPartitionGauge(), _partitions - n);
    Assert.assertEquals(monitor.getPartitionGauge(), _partitions);
  }
}
