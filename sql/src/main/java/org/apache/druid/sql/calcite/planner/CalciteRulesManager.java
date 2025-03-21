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

package org.apache.druid.sql.calcite.planner;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import org.apache.calcite.interpreter.Bindables;
import org.apache.calcite.plan.RelOptLattice;
import org.apache.calcite.plan.RelOptMaterialization;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.hep.HepProgram;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.plan.volcano.AbstractConverter;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.metadata.DefaultRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.rel.rules.DateRangeRules;
import org.apache.calcite.rel.rules.JoinPushThroughJoinRule;
import org.apache.calcite.rel.rules.PruneEmptyRules;
import org.apache.calcite.sql.SqlExplainFormat;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql2rel.RelDecorrelator;
import org.apache.calcite.sql2rel.RelFieldTrimmer;
import org.apache.calcite.tools.Program;
import org.apache.calcite.tools.Programs;
import org.apache.calcite.tools.RelBuilder;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.sql.calcite.external.ExternalTableScanRule;
import org.apache.druid.sql.calcite.rule.DruidLogicalValuesRule;
import org.apache.druid.sql.calcite.rule.DruidRelToDruidRule;
import org.apache.druid.sql.calcite.rule.DruidRules;
import org.apache.druid.sql.calcite.rule.DruidTableScanRule;
import org.apache.druid.sql.calcite.rule.ExtensionCalciteRuleProvider;
import org.apache.druid.sql.calcite.rule.FilterJoinExcludePushToChildRule;
import org.apache.druid.sql.calcite.rule.ProjectAggregatePruneUnusedCallRule;
import org.apache.druid.sql.calcite.rule.SortCollapseRule;
import org.apache.druid.sql.calcite.rule.logical.DruidLogicalRules;
import org.apache.druid.sql.calcite.run.EngineFeature;

import java.util.List;
import java.util.Set;

public class CalciteRulesManager
{
  private static final Logger log = new Logger(CalciteRulesManager.class);

  public static final int DRUID_CONVENTION_RULES = 0;
  public static final int BINDABLE_CONVENTION_RULES = 1;
  public static final int DRUID_DAG_CONVENTION_RULES = 2;
  private static final String HEP_DEFAULT_MATCH_LIMIT_CONFIG_STRING = "druid.sql.planner.hepMatchLimit";
  private static final int HEP_DEFAULT_MATCH_LIMIT = Integer.parseInt(
      System.getProperty(HEP_DEFAULT_MATCH_LIMIT_CONFIG_STRING, "1200")
  );

  /**
   * Rules from {@link org.apache.calcite.plan.RelOptRules#BASE_RULES}, minus:
   *
   * 1) {@link CoreRules#AGGREGATE_EXPAND_DISTINCT_AGGREGATES} (it'll be added back later if approximate count distinct
   * is disabled)
   * 2) {@link CoreRules#AGGREGATE_REDUCE_FUNCTIONS} (it'll be added back for the Bindable rule set, but we don't want
   * it for Druid rules since it expands AVG, STDDEV, VAR, etc, and we have aggregators specifically designed for
   * those functions).
   * 3) {@link CoreRules#JOIN_COMMUTE}, {@link JoinPushThroughJoinRule#RIGHT}, {@link JoinPushThroughJoinRule#LEFT},
   * and {@link CoreRules#FILTER_INTO_JOIN}, which are part of {@link #FANCY_JOIN_RULES}.
   */
  private static final List<RelOptRule> BASE_RULES =
      ImmutableList.of(
          CoreRules.AGGREGATE_STAR_TABLE,
          CoreRules.AGGREGATE_PROJECT_STAR_TABLE,
          CoreRules.PROJECT_MERGE,
          CoreRules.FILTER_SCAN,
          CoreRules.PROJECT_FILTER_TRANSPOSE,
          CoreRules.FILTER_PROJECT_TRANSPOSE,
          CoreRules.JOIN_PUSH_EXPRESSIONS,
          CoreRules.AGGREGATE_EXPAND_WITHIN_DISTINCT,
          CoreRules.AGGREGATE_CASE_TO_FILTER,
          CoreRules.FILTER_AGGREGATE_TRANSPOSE,
          CoreRules.PROJECT_WINDOW_TRANSPOSE,
          CoreRules.MATCH,
          CoreRules.SORT_PROJECT_TRANSPOSE,
          CoreRules.SORT_JOIN_TRANSPOSE,
          CoreRules.SORT_REMOVE_CONSTANT_KEYS,
          CoreRules.SORT_UNION_TRANSPOSE,
          CoreRules.EXCHANGE_REMOVE_CONSTANT_KEYS,
          CoreRules.SORT_EXCHANGE_REMOVE_CONSTANT_KEYS
      );

  /**
   * Rules for scanning via Bindable.
   */
  private static final List<RelOptRule> DEFAULT_BINDABLE_RULES =
      ImmutableList.of(
          Bindables.BINDABLE_TABLE_SCAN_RULE,
          CoreRules.PROJECT_TABLE_SCAN,
          CoreRules.PROJECT_INTERPRETER_TABLE_SCAN
      );

  /**
   * Rules from {@link org.apache.calcite.plan.RelOptRules#CONSTANT_REDUCTION_RULES}, minus:
   *
   * 1) {@link CoreRules#JOIN_REDUCE_EXPRESSIONS}
   * Removed by https://github.com/apache/druid/pull/9941 due to issue in https://github.com/apache/druid/issues/9942
   */
  private static final List<RelOptRule> REDUCTION_RULES =
      ImmutableList.of(
          CoreRules.PROJECT_REDUCE_EXPRESSIONS,
          CoreRules.FILTER_REDUCE_EXPRESSIONS,
          CoreRules.CALC_REDUCE_EXPRESSIONS,
          CoreRules.WINDOW_REDUCE_EXPRESSIONS,
          CoreRules.FILTER_VALUES_MERGE,
          CoreRules.PROJECT_FILTER_VALUES_MERGE,
          CoreRules.PROJECT_VALUES_MERGE,
          CoreRules.SORT_PROJECT_TRANSPOSE,
          CoreRules.AGGREGATE_VALUES
      );

  /**
   * Rules from {@link org.apache.calcite.plan.RelOptRules#ABSTRACT_RULES}, minus:
   *
   * 1) {@link CoreRules#UNION_MERGE} since it isn't very effective given how Druid unions currently operate, and is
   * potentially expensive in terms of planning time.
   * 2) {@link DateRangeRules#FILTER_INSTANCE} due to https://issues.apache.org/jira/browse/CALCITE-1601.
   */
  private static final List<RelOptRule> ABSTRACT_RULES =
      ImmutableList.of(
          CoreRules.AGGREGATE_ANY_PULL_UP_CONSTANTS,
          CoreRules.UNION_PULL_UP_CONSTANTS,
          PruneEmptyRules.UNION_INSTANCE,
          PruneEmptyRules.INTERSECT_INSTANCE,
          PruneEmptyRules.MINUS_INSTANCE,
          PruneEmptyRules.PROJECT_INSTANCE,
          PruneEmptyRules.FILTER_INSTANCE,
          PruneEmptyRules.SORT_INSTANCE,
          PruneEmptyRules.AGGREGATE_INSTANCE,
          PruneEmptyRules.JOIN_LEFT_INSTANCE,
          PruneEmptyRules.JOIN_RIGHT_INSTANCE,
          PruneEmptyRules.SORT_FETCH_ZERO_INSTANCE,
          PruneEmptyRules.EMPTY_TABLE_INSTANCE,
          CoreRules.PROJECT_TO_LOGICAL_PROJECT_AND_WINDOW,
          CoreRules.FILTER_MERGE,
          CoreRules.INTERSECT_TO_DISTINCT
      );

  /**
   * Rules from {@link org.apache.calcite.plan.RelOptRules#ABSTRACT_RELATIONAL_RULES}, minus:
   *
   * 1) {@link CoreRules#AGGREGATE_MERGE} and related {@link CoreRules#PROJECT_AGGREGATE_MERGE}
   * (causes testDoubleNestedGroupBy2 to fail)
   * 2) {@link CoreRules#JOIN_TO_SEMI_JOIN}, {@link CoreRules#JOIN_ON_UNIQUE_TO_SEMI_JOIN}, and
   * {@link CoreRules#PROJECT_TO_SEMI_JOIN} (we don't need to detect semi-joins, because they are handled
   * fine as-is by {@link org.apache.druid.sql.calcite.rule.DruidJoinRule}).
   * 3) {@link CoreRules#JOIN_COMMUTE}, {@link CoreRules#FILTER_INTO_JOIN}, and {@link CoreRules#JOIN_CONDITION_PUSH},
   * which are part of {@link #FANCY_JOIN_RULES}.
   */
  private static final List<RelOptRule> ABSTRACT_RELATIONAL_RULES =
      ImmutableList.of(
          AbstractConverter.ExpandConversionRule.INSTANCE,
          CoreRules.AGGREGATE_REMOVE,
          CoreRules.UNION_TO_DISTINCT,
          CoreRules.PROJECT_REMOVE,
          CoreRules.AGGREGATE_JOIN_TRANSPOSE,
          CoreRules.AGGREGATE_PROJECT_MERGE,
          CoreRules.CALC_REMOVE,
          CoreRules.SORT_REMOVE
      );

  /**
   * Rules that are enabled when we consider join algorithms that require subqueries for all inputs, such as
   * {@link JoinAlgorithm#SORT_MERGE}.
   *
   * Native queries only support broadcast hash joins, and they do not require a subquery for the "base" (leftmost)
   * input. In fact, we really strongly *don't* want to do a subquery for the base input, as that forces materialization
   * of the base input on the Broker. The way we structure native queries causes challenges for the planner when it
   * comes to avoiding subqueries, such as those described in https://github.com/apache/druid/issues/9843. To work
   * around this, we omit the join-related rules in this list when planning queries that use broadcast joins.
   */
  private static final List<RelOptRule> FANCY_JOIN_RULES =
      ImmutableList.of(
          CoreRules.PROJECT_JOIN_TRANSPOSE,
          CoreRules.PROJECT_JOIN_REMOVE,
          CoreRules.FILTER_INTO_JOIN,
          CoreRules.JOIN_PUSH_EXPRESSIONS,
          CoreRules.SORT_JOIN_TRANSPOSE,
          JoinPushThroughJoinRule.LEFT,
          CoreRules.JOIN_COMMUTE
      );

  private final Set<ExtensionCalciteRuleProvider> extensionCalciteRuleProviderSet;

  /**
   * Manages the rules for planning of SQL queries via Calcite. Also provides methods for extensions to provide custom
   * rules for planning.
   *
   * @param extensionCalciteRuleProviderSet the set of custom rules coming from extensions
   */
  @Inject
  public CalciteRulesManager(final Set<ExtensionCalciteRuleProvider> extensionCalciteRuleProviderSet)
  {
    this.extensionCalciteRuleProviderSet = extensionCalciteRuleProviderSet;
  }

  public List<Program> programs(final PlannerContext plannerContext)
  {
    // Program that pre-processes the tree before letting the full-on VolcanoPlanner loose.
    final Program preProgram =
        Programs.sequence(
            Programs.subQuery(DefaultRelMetadataProvider.INSTANCE),
            DecorrelateAndTrimFieldsProgram.INSTANCE,
            buildHepProgram(REDUCTION_RULES, true, DefaultRelMetadataProvider.INSTANCE, HEP_DEFAULT_MATCH_LIMIT)
        );

    boolean isDebug = plannerContext.queryContext().isDebug();
    return ImmutableList.of(
        Programs.sequence(preProgram, Programs.ofRules(druidConventionRuleSet(plannerContext))),
        Programs.sequence(preProgram, Programs.ofRules(bindableConventionRuleSet(plannerContext))),
        Programs.sequence(
            // currently, adding logging program after every stage for easier debugging
            new LoggingProgram("Start", isDebug),
            Programs.subQuery(DefaultRelMetadataProvider.INSTANCE),
            new LoggingProgram("After subquery program", isDebug),
            DecorrelateAndTrimFieldsProgram.INSTANCE,
            new LoggingProgram("After trim fields and decorelate program", isDebug),
            buildHepProgram(REDUCTION_RULES, true, DefaultRelMetadataProvider.INSTANCE, HEP_DEFAULT_MATCH_LIMIT),
            new LoggingProgram("After hep planner program", isDebug),
            Programs.ofRules(logicalConventionRuleSet(plannerContext)),
            new LoggingProgram("After volcano planner program", isDebug)
        )
    );
  }

  private static class LoggingProgram implements Program
  {
    private final String stage;
    private final boolean isDebug;

    public LoggingProgram(String stage, boolean isDebug)
    {
      this.stage = stage;
      this.isDebug = isDebug;
    }

    @Override
    public RelNode run(
        RelOptPlanner planner,
        RelNode rel,
        RelTraitSet requiredOutputTraits,
        List<RelOptMaterialization> materializations,
        List<RelOptLattice> lattices
    )
    {
      if (isDebug) {
        log.info(
            "%s%n%s",
            stage,
            RelOptUtil.dumpPlan("", rel, SqlExplainFormat.TEXT, SqlExplainLevel.ALL_ATTRIBUTES)
        );
      }
      return rel;
    }
  }

  public Program buildHepProgram(
      final Iterable<? extends RelOptRule> rules,
      final boolean noDag,
      final RelMetadataProvider metadataProvider,
      final int matchLimit
  )
  {
    final HepProgramBuilder builder = HepProgram.builder();
    builder.addMatchLimit(matchLimit);
    for (RelOptRule rule : rules) {
      builder.addRuleInstance(rule);
    }
    return Programs.of(builder.build(), noDag, metadataProvider);
  }

  public List<RelOptRule> druidConventionRuleSet(final PlannerContext plannerContext)
  {
    final ImmutableList.Builder<RelOptRule> retVal = ImmutableList
        .<RelOptRule>builder()
        .addAll(baseRuleSet(plannerContext))
        .add(DruidRelToDruidRule.instance())
        .add(new DruidTableScanRule(plannerContext))
        .add(new DruidLogicalValuesRule(plannerContext))
        .add(new ExternalTableScanRule(plannerContext))
        .addAll(DruidRules.rules(plannerContext));

    for (ExtensionCalciteRuleProvider extensionCalciteRuleProvider : extensionCalciteRuleProviderSet) {
      retVal.add(extensionCalciteRuleProvider.getRule(plannerContext));
    }
    return retVal.build();
  }

  public List<RelOptRule> logicalConventionRuleSet(final PlannerContext plannerContext)
  {
    final ImmutableList.Builder<RelOptRule> retVal = ImmutableList
        .<RelOptRule>builder()
        .addAll(baseRuleSet(plannerContext))
        .add(new DruidLogicalRules(plannerContext).rules().toArray(new RelOptRule[0]));
    return retVal.build();
  }

  public List<RelOptRule> bindableConventionRuleSet(final PlannerContext plannerContext)
  {
    return ImmutableList.<RelOptRule>builder()
                        .addAll(baseRuleSet(plannerContext))
                        .addAll(Bindables.RULES)
                        .addAll(DEFAULT_BINDABLE_RULES)
                        .add(CoreRules.AGGREGATE_REDUCE_FUNCTIONS)
                        .build();
  }

  public List<RelOptRule> baseRuleSet(final PlannerContext plannerContext)
  {
    final PlannerConfig plannerConfig = plannerContext.getPlannerConfig();
    final ImmutableList.Builder<RelOptRule> rules = ImmutableList.builder();

    // Calcite rules.
    rules.addAll(BASE_RULES);
    rules.addAll(ABSTRACT_RULES);
    rules.addAll(ABSTRACT_RELATIONAL_RULES);

    if (plannerContext.getJoinAlgorithm().requiresSubquery()) {
      rules.addAll(FANCY_JOIN_RULES);
    }

    if (!plannerConfig.isUseApproximateCountDistinct()) {
      if (plannerConfig.isUseGroupingSetForExactDistinct()
          && plannerContext.featureAvailable(EngineFeature.GROUPING_SETS)) {
        rules.add(CoreRules.AGGREGATE_EXPAND_DISTINCT_AGGREGATES);
      } else {
        rules.add(CoreRules.AGGREGATE_EXPAND_DISTINCT_AGGREGATES_TO_JOIN);
      }
    }

    // Rules that we wrote.
    rules.add(FilterJoinExcludePushToChildRule.FILTER_ON_JOIN_EXCLUDE_PUSH_TO_CHILD);
    rules.add(SortCollapseRule.instance());
    rules.add(ProjectAggregatePruneUnusedCallRule.instance());

    return rules.build();
  }

  // Based on Calcite's Programs.DecorrelateProgram and Programs.TrimFieldsProgram, which are private and only
  // accessible through Programs.standard (which we don't want, since it also adds Enumerable rules).
  private static class DecorrelateAndTrimFieldsProgram implements Program
  {
    private static final DecorrelateAndTrimFieldsProgram INSTANCE = new DecorrelateAndTrimFieldsProgram();

    @Override
    public RelNode run(
        RelOptPlanner planner,
        RelNode rel,
        RelTraitSet requiredOutputTraits,
        List<RelOptMaterialization> materializations,
        List<RelOptLattice> lattices
    )
    {
      final RelBuilder relBuilder = RelFactories.LOGICAL_BUILDER.create(rel.getCluster(), null);
      final RelNode decorrelatedRel = RelDecorrelator.decorrelateQuery(rel, relBuilder);
      return new RelFieldTrimmer(null, relBuilder).trim(decorrelatedRel);
    }
  }
}
