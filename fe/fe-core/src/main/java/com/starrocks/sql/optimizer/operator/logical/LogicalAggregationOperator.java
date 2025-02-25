// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.
package com.starrocks.sql.optimizer.operator.logical;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.starrocks.sql.optimizer.ExpressionContext;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptExpressionVisitor;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.operator.AggType;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.OperatorVisitor;
import com.starrocks.sql.optimizer.operator.scalar.CallOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class LogicalAggregationOperator extends LogicalOperator {
    private final AggType type;
    // The flag for this aggregate operator has split to
    // two stage aggregate or three stage aggregate
    private boolean isSplit = false;
    /**
     * aggregation key is output variable of aggregate function
     */
    private final ImmutableMap<ColumnRefOperator, CallOperator> aggregations;
    private final ImmutableList<ColumnRefOperator> groupingKeys;

    // For normal aggregate function, partitionByColumns are same with groupingKeys
    // but for single distinct function, partitionByColumns are not same with groupingKeys
    private List<ColumnRefOperator> partitionByColumns;

    // When generate plan fragment, we need this info.
    // For SQL: select count(distinct id_bigint), sum(id_int) from test_basic;
    // In the distinct local (update serialize) agg stage:
    //|   5:AGGREGATE (update serialize)                                                      |
    //|   |  output: count(<slot 13>), sum(<slot 16>)                                         |
    //|   |  group by:                                                                        |
    // count function is update function, but sum is merge function
    // if singleDistinctFunctionPos is -1, means no single distinct function
    private int singleDistinctFunctionPos = -1;

    public LogicalAggregationOperator(AggType type,
                                      List<ColumnRefOperator> groupingKeys,
                                      Map<ColumnRefOperator, CallOperator> aggregations) {
        this(type, groupingKeys, groupingKeys, aggregations, false, -1, -1, null);
    }

    public LogicalAggregationOperator(
            AggType type,
            List<ColumnRefOperator> groupingKeys,
            List<ColumnRefOperator> partitionByColumns,
            Map<ColumnRefOperator, CallOperator> aggregations,
            boolean isSplit,
            int singleDistinctFunctionPos,
            long limit,
            ScalarOperator predicate) {
        super(OperatorType.LOGICAL_AGGR, limit, predicate);
        this.type = type;
        this.groupingKeys = ImmutableList.copyOf(groupingKeys);
        this.partitionByColumns = partitionByColumns;
        this.aggregations = ImmutableMap.copyOf(aggregations);
        this.isSplit = isSplit;
        this.singleDistinctFunctionPos = singleDistinctFunctionPos;
    }

    public AggType getType() {
        return type;
    }

    public Map<ColumnRefOperator, CallOperator> getAggregations() {
        return aggregations;
    }

    public List<ColumnRefOperator> getGroupingKeys() {
        return groupingKeys;
    }

    public boolean isSplit() {
        return isSplit;
    }

    public void setSplit() {
        isSplit = true;
    }

    public int getSingleDistinctFunctionPos() {
        return singleDistinctFunctionPos;
    }

    public void setSingleDistinctFunctionPos(int singleDistinctFunctionPos) {
        this.singleDistinctFunctionPos = singleDistinctFunctionPos;
    }

    public List<ColumnRefOperator> getPartitionByColumns() {
        return partitionByColumns;
    }

    public void setPartitionByColumns(List<ColumnRefOperator> partitionByColumns) {
        this.partitionByColumns = partitionByColumns;
    }

    @Override
    public ColumnRefSet getOutputColumns(ExpressionContext expressionContext) {
        ColumnRefSet columns = new ColumnRefSet();
        columns.union(groupingKeys);
        columns.union(new ArrayList<>(aggregations.keySet()));
        return columns;
    }

    @Override
    public String toString() {
        return "LogicalAggregation" + " type " + type.toString();
    }

    @Override
    public <R, C> R accept(OperatorVisitor<R, C> visitor, C context) {
        return visitor.visitLogicalAggregation(this, context);
    }

    @Override
    public <R, C> R accept(OptExpressionVisitor<R, C> visitor, OptExpression optExpression, C context) {
        return visitor.visitLogicalAggregate(optExpression, context);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        LogicalAggregationOperator that = (LogicalAggregationOperator) o;
        return type == that.type && Objects.equals(aggregations, that.aggregations) &&
                Objects.equals(groupingKeys, that.groupingKeys);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), type, aggregations, groupingKeys);
    }
}
