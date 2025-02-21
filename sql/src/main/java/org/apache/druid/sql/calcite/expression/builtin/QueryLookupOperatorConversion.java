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

package org.apache.druid.sql.calcite.expression.builtin;

import com.google.inject.Inject;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.math.expr.Evals;
import org.apache.druid.math.expr.Expr;
import org.apache.druid.query.lookup.LookupExtractorFactoryContainerProvider;
import org.apache.druid.query.lookup.RegisteredLookupExtractionFn;
import org.apache.druid.segment.column.RowSignature;
import org.apache.druid.sql.calcite.expression.BasicOperandTypeChecker;
import org.apache.druid.sql.calcite.expression.DruidExpression;
import org.apache.druid.sql.calcite.expression.OperatorConversions;
import org.apache.druid.sql.calcite.expression.SqlOperatorConversion;
import org.apache.druid.sql.calcite.planner.PlannerContext;

import java.util.List;

public class QueryLookupOperatorConversion implements SqlOperatorConversion
{
  private static final SqlFunction SQL_FUNCTION = OperatorConversions
      .operatorBuilder("LOOKUP")
      .operandTypeChecker(
          BasicOperandTypeChecker.builder()
                                 .operandTypes(
                                     SqlTypeFamily.CHARACTER,
                                     SqlTypeFamily.CHARACTER,
                                     SqlTypeFamily.CHARACTER
                                 )
                                 .requiredOperandCount(2)
                                 .literalOperands(2)
                                 .build())
      .returnTypeNullable(SqlTypeName.VARCHAR)
      .functionCategory(SqlFunctionCategory.STRING)
      .build();

  private final LookupExtractorFactoryContainerProvider lookupExtractorFactoryContainerProvider;

  @Inject
  public QueryLookupOperatorConversion(final LookupExtractorFactoryContainerProvider lookupExtractorFactoryContainerProvider)
  {
    this.lookupExtractorFactoryContainerProvider = lookupExtractorFactoryContainerProvider;
  }

  @Override
  public SqlFunction calciteOperator()
  {
    return SQL_FUNCTION;
  }

  @Override
  public DruidExpression toDruidExpression(
      final PlannerContext plannerContext,
      final RowSignature rowSignature,
      final RexNode rexNode
  )
  {
    return OperatorConversions.convertDirectCallWithExtraction(
        plannerContext,
        rowSignature,
        rexNode,
        StringUtils.toLowerCase(calciteOperator().getName()),
        inputExpressions -> {
          final DruidExpression arg = inputExpressions.get(0);
          final Expr lookupNameExpr = plannerContext.parseExpression(inputExpressions.get(1).getExpression());
          final String replaceMissingValueWith = getReplaceMissingValueWith(inputExpressions, plannerContext);

          if (arg.isSimpleExtraction() && lookupNameExpr.isLiteral()) {
            return arg.getSimpleExtraction().cascade(
                new RegisteredLookupExtractionFn(
                    lookupExtractorFactoryContainerProvider,
                    (String) lookupNameExpr.getLiteralValue(),
                    false,
                    replaceMissingValueWith,
                    null,
                    true
                )
            );
          } else {
            return null;
          }
        }
    );
  }

  private String getReplaceMissingValueWith(
      final List<DruidExpression> inputExpressions,
      final PlannerContext plannerContext
  )
  {
    if (inputExpressions.size() > 2) {
      final Expr missingValExpr = plannerContext.parseExpression(inputExpressions.get(2).getExpression());
      if (missingValExpr.isLiteral()) {
        return Evals.asString(missingValExpr.getLiteralValue());
      }
    }
    return null;
  }
}
