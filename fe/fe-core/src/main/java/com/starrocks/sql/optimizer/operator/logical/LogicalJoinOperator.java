// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

package com.starrocks.sql.optimizer.operator.logical;

import com.google.common.base.Preconditions;
import com.starrocks.analysis.JoinOperator;
import com.starrocks.sql.optimizer.ExpressionContext;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptExpressionVisitor;
import com.starrocks.sql.optimizer.base.ColumnRefSet;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.OperatorVisitor;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;

import java.util.List;
import java.util.Objects;

public class LogicalJoinOperator extends LogicalOperator {
    private final JoinOperator joinType;
    private final ScalarOperator onPredicate;
    private final String joinHint;
    // For mark the node has been push  down join on clause, avoid dead-loop
    private boolean hasPushDownJoinOnClause = false;
    // Output columns after PruneJoinColumnsRule apply.
    // PruneOutputColumns will not contains onPredicate/predicate used columns if parent node don't require these columns.
    // PruneOutputColumns need to be calculated because project nodes will be added on top of join nodes after choose best plan (AddProjectForJoinPruneRule),
    // so column statistics need to be pruned before calculating cost.
    private List<ColumnRefOperator> pruneOutputColumns;

    public LogicalJoinOperator(JoinOperator joinType, ScalarOperator onPredicate) {
        this(joinType, onPredicate, "", -1, null, null, false);
    }

    public LogicalJoinOperator(JoinOperator joinType, ScalarOperator onPredicate, String joinHint,
                               long limit,
                               ScalarOperator predicate,
                               List<ColumnRefOperator> pruneOutputColumns,
                               boolean hasPushDownJoinOnClause) {
        super(OperatorType.LOGICAL_JOIN, limit, predicate);
        this.joinType = joinType;
        this.onPredicate = onPredicate;
        Preconditions.checkNotNull(joinHint);
        this.joinHint = joinHint;

        this.pruneOutputColumns = pruneOutputColumns;
        this.hasPushDownJoinOnClause = hasPushDownJoinOnClause;
    }

    // Constructor for UT, don't use this ctor except ut
    public LogicalJoinOperator() {
        super(OperatorType.LOGICAL_JOIN);
        this.onPredicate = null;
        this.joinType = JoinOperator.INNER_JOIN;
        this.joinHint = "";
    }

    public boolean isHasPushDownJoinOnClause() {
        return hasPushDownJoinOnClause;
    }

    public void setHasPushDownJoinOnClause(boolean hasPushDownJoinOnClause) {
        this.hasPushDownJoinOnClause = hasPushDownJoinOnClause;
    }

    public JoinOperator getJoinType() {
        return joinType;
    }

    public boolean isInnerOrCrossJoin() {
        return joinType.isInnerJoin() || joinType.isCrossJoin();
    }

    public ScalarOperator getOnPredicate() {
        return onPredicate;
    }

    public String getJoinHint() {
        return joinHint;
    }

    public ColumnRefSet getRequiredChildInputColumns() {
        ColumnRefSet result = new ColumnRefSet();
        if (onPredicate != null) {
            result.union(onPredicate.getUsedColumns());
        }
        if (predicate != null) {
            result.union(predicate.getUsedColumns());
        }
        return result;
    }

    public void setPruneOutputColumns(List<ColumnRefOperator> pruneOutputColumns) {
        this.pruneOutputColumns = pruneOutputColumns;
    }

    public List<ColumnRefOperator> getPruneOutputColumns() {
        return this.pruneOutputColumns;
    }

    @Override
    public ColumnRefSet getOutputColumns(ExpressionContext expressionContext) {
        ColumnRefSet columns = new ColumnRefSet();
        for (int i = 0; i < expressionContext.arity(); ++i) {
            columns.union(expressionContext.getChildLogicalProperty(i).getOutputColumns());
        }
        return columns;
    }

    @Override
    public <R, C> R accept(OperatorVisitor<R, C> visitor, C context) {
        return visitor.visitLogicalJoin(this, context);
    }

    @Override
    public <R, C> R accept(OptExpressionVisitor<R, C> visitor, OptExpression optExpression, C context) {
        return visitor.visitLogicalJoin(optExpression, context);
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

        LogicalJoinOperator rhs = (LogicalJoinOperator) o;

        return joinType == rhs.joinType && Objects.equals(onPredicate, rhs.onPredicate) &&
                Objects.equals(pruneOutputColumns, rhs.pruneOutputColumns);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), opType, joinType, onPredicate);
    }

    @Override
    public String toString() {
        return "LOGICAL_JOIN" + " {" +
                joinType.toString() +
                ", onPredicate = " + onPredicate + ' ' +
                ", Predicate = " + predicate +
                '}';
    }
}
