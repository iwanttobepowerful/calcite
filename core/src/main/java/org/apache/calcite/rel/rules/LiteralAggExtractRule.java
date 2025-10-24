/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.rel.rules;

import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.tools.RelBuilder;

import com.google.common.collect.ImmutableSet;

import org.immutables.value.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LiteralAggExtractRule gets rid of the LITERAL_AGG into most databases can handle.
 */
@Value.Enclosing
public class LiteralAggExtractRule
    extends RelRule<LiteralAggExtractRule.Config>
    implements TransformationRule {

  protected LiteralAggExtractRule(Config config) {
    super(config);
  }

  @Override public void onMatch(RelOptRuleCall call) {
    final LogicalAggregate aggregate = call.rel(0);

    final List<AggregateCall> aggCalls = aggregate.getAggCallList();
    if (aggCalls == null || aggCalls.isEmpty()) {
      return;
    }

    // Collect indices of LITERAL_AGG calls.
    final List<Integer> literalAggIndices = new ArrayList<>();
    for (int i = 0; i < aggCalls.size(); i++) {
      final AggregateCall ac = aggCalls.get(i);
      if (ac.getAggregation().getName().equals("LITERAL_AGG")) {
        literalAggIndices.add(i);
      }
    }

    if (literalAggIndices.isEmpty()) {
      // nothing to do
      return;
    }

    // Build new AggregateCall list without LITERAL_AGG entries.
    final List<AggregateCall> newAggCalls = new ArrayList<>();
    final Map<Integer, Integer> oldAggIndexToNewAggIndex = new HashMap<>();
    int newAggPos = 0;
    for (int i = 0; i < aggCalls.size(); i++) {
      if (!literalAggIndices.contains(i)) {
        newAggCalls.add(aggCalls.get(i));
        oldAggIndexToNewAggIndex.put(i, newAggPos++);
      }
    }

    // Create new Aggregate preserving groupSet/groupSets/hints.
    final LogicalAggregate newAggregate =
        LogicalAggregate.create(aggregate.getInput(),
          aggregate.getHints(),
          aggregate.getGroupSet(),
          aggregate.getGroupSets(),
          newAggCalls);

    final RexBuilder rexBuilder = aggregate.getCluster().getRexBuilder();

    // Number of group columns in output (group keys appear first).
    final int groupCount = aggregate.getGroupSet().cardinality();
    final int origAggCount = aggCalls.size();
    final int origOutputCount = groupCount + origAggCount;

    // Build projection expressions to restore original output layout.
    final List<RexNode> projects = new ArrayList<>(origOutputCount);

    for (int outPos = 0; outPos < origOutputCount; outPos++) {
      if (outPos < groupCount) {
        // Group key columns remain in the same positions.
        projects.add(rexBuilder.makeInputRef(newAggregate, outPos));
      } else {
        // Aggregate output: determine original aggregate index.
        final int origAggIndex = outPos - groupCount;
        if (literalAggIndices.contains(origAggIndex)) {
          // Replacement for LITERAL_AGG: try to extract literal from the original AggregateCall.
          final AggregateCall origAgg = aggCalls.get(origAggIndex);
          RexNode literalExpr = null;

          if (origAgg.rexList != null && !origAgg.rexList.isEmpty()
              && origAgg.rexList.get(0) instanceof RexLiteral) {
            literalExpr = origAgg.rexList.get(0);
            // Cast literal to expected output type if needed.
            final RelDataType targetType =
                aggregate.getRowType().getFieldList().get(outPos).getType();
            if (!literalExpr.getType().equals(targetType)) {
              literalExpr = rexBuilder.makeCast(targetType, literalExpr);
            }
          } else {
            // No literal available on the AggregateCall; create a typed NULL as a safe fallback.
            final RelDataType targetType =
                aggregate.getRowType().getFieldList().get(outPos).getType();
            literalExpr = rexBuilder.makeNullLiteral(targetType);
          }
          projects.add(literalExpr);
        } else {
          // Non-literal aggregate: compute its new output index in newAggregate.
          final Integer newAggIndex = oldAggIndexToNewAggIndex.get(origAggIndex);
          final int newOutputIndex = groupCount + newAggIndex;
          projects.add(rexBuilder.makeInputRef(newAggregate, newOutputIndex));
        }
      }
    }

    final RelNode projectRelNode =
        LogicalProject.create(newAggregate, new ArrayList<>(), projects,
            aggregate.getRowType(), ImmutableSet.of());
    call.transformTo(projectRelNode);
  }

  /** Rule configuration. */
  @Value.Immutable
  public interface Config extends RelRule.Config {
    Config DEFAULT = ImmutableLiteralAggExtractRule.Config.of()
        .withRelBuilderFactory(RelBuilder.proto())
        .withOperandSupplier(b0 ->
                b0.operand(Aggregate.class).anyInputs());

    @Override default LiteralAggExtractRule toRule() {
      return new LiteralAggExtractRule(this);
    }
  }
}
