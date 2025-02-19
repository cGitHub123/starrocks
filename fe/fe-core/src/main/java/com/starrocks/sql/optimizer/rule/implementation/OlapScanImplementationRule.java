// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.

package com.starrocks.sql.optimizer.rule.implementation;

import com.google.common.collect.Lists;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerContext;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalOlapScanOperator;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.operator.physical.PhysicalOlapScanOperator;
import com.starrocks.sql.optimizer.rule.RuleType;

import java.util.List;

public class OlapScanImplementationRule extends ImplementationRule {
    public OlapScanImplementationRule() {
        super(RuleType.IMP_OLAP_LSCAN_TO_PSCAN,
                Pattern.create(OperatorType.LOGICAL_OLAP_SCAN));
    }

    @Override
    public List<OptExpression> transform(OptExpression input, OptimizerContext context) {
        LogicalOlapScanOperator scan = (LogicalOlapScanOperator) input.getOp();
        PhysicalOlapScanOperator physicalOlapScan = new PhysicalOlapScanOperator(
                scan.getTable(),
                scan.getOutputColumns(),
                scan.getColRefToColumnMetaMap(),
                scan.getDistributionSpec());

        physicalOlapScan.setSelectedIndexId(scan.getSelectedIndexId());
        physicalOlapScan.setSelectedPartitionId(Lists.newArrayList(scan.getSelectedPartitionId()));
        physicalOlapScan.setSelectedTabletId(Lists.newArrayList(scan.getSelectedTabletId()));
        physicalOlapScan.setPredicate(scan.getPredicate());
        physicalOlapScan.setLimit(scan.getLimit());

        OptExpression result = new OptExpression(physicalOlapScan);
        return Lists.newArrayList(result);
    }
}
