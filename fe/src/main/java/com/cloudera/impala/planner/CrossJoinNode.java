// Copyright 2012 Cloudera Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.cloudera.impala.planner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.analysis.Analyzer;
import com.cloudera.impala.analysis.TableRef;
import com.cloudera.impala.catalog.AuthorizationException;
import com.cloudera.impala.common.InternalException;
import com.cloudera.impala.planner.HashJoinNode.DistributionMode;
import com.cloudera.impala.thrift.TExplainLevel;
import com.cloudera.impala.thrift.TPlanNode;
import com.cloudera.impala.thrift.TPlanNodeType;
import com.cloudera.impala.thrift.TQueryOptions;
import com.google.common.base.Objects;

/**
 * Cross join between left child and right child.
 */
public class CrossJoinNode extends PlanNode {
  private final static Logger LOG = LoggerFactory.getLogger(CrossJoinNode.class);

  // Default per-host memory requirement used if no valid stats are available.
  // TODO: Come up with a more useful heuristic (e.g., based on scanned partitions).
  private final static long DEFAULT_PER_HOST_MEM = 2L * 1024L * 1024L * 1024L;
  private final TableRef innerRef;

  public CrossJoinNode(PlanNode outer, PlanNode inner, TableRef innerRef) {
    super("CROSS JOIN");
    this.innerRef = innerRef;
    tupleIds.addAll(outer.getTupleIds());
    tupleIds.addAll(inner.getTupleIds());
    tblRefIds_.addAll(outer.getTblRefIds());
    tblRefIds_.addAll(inner.getTblRefIds());
    children.add(outer);
    children.add(inner);

    // Inherits all the nullable tuple from the children
    // Mark tuples that form the "nullable" side of the outer join as nullable.
    nullableTupleIds.addAll(outer.getNullableTupleIds());
    nullableTupleIds.addAll(inner.getNullableTupleIds());
  }

  public TableRef getInnerRef() { return innerRef; }

  @Override
  public void init(Analyzer analyzer) throws InternalException, AuthorizationException {
    super.init(analyzer);
    assignedConjuncts_ = analyzer.getAssignedConjuncts();
  }

  @Override
  public void computeStats(Analyzer analyzer) {
    super.computeStats(analyzer);
    if (getChild(0).cardinality == -1 || getChild(1).cardinality == -1) {
      cardinality = -1;
    } else {
      cardinality = getChild(0).cardinality * getChild(1).cardinality;
      if (computeSelectivity() != -1) {
        cardinality = Math.round(((double) cardinality) * computeSelectivity());
      }
    }
    LOG.debug("stats CrossJoin: cardinality=" + Long.toString(cardinality));
  }

  @Override
  protected String debugString() {
    return Objects.toStringHelper(this)
        .addValue(super.debugString())
        .toString();
  }

  @Override
  protected void toThrift(TPlanNode msg) {
    msg.node_type = TPlanNodeType.CROSS_JOIN_NODE;
  }

  @Override
  protected String getNodeExplainString(String detailPrefix,
      TExplainLevel detailLevel) {
    StringBuilder output = new StringBuilder();
    // Always a BROADCAST, but print it anyway so it's clear to users
    output.append(detailPrefix + "(" + DistributionMode.BROADCAST.toString() + ")\n");
    if (!conjuncts.isEmpty()) {
      output.append(detailPrefix + "predicates: ")
          .append(getExplainString(conjuncts) + "\n");
    }
    return output.toString();
  }

  @Override
  public void computeCosts(TQueryOptions queryOptions) {
    if (getChild(1).getCardinality() == -1 || getChild(1).getAvgRowSize() == -1
        || numNodes == 0) {
      perHostMemCost = DEFAULT_PER_HOST_MEM;
      return;
    }
    perHostMemCost = (long) Math.ceil(getChild(1).cardinality * getChild(1).avgRowSize);
  }
}
