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

package com.cloudera.impala.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cloudera.impala.catalog.AuthorizationException;
import com.cloudera.impala.catalog.ColumnStats;
import com.cloudera.impala.catalog.PrimitiveType;
import com.cloudera.impala.common.AnalysisException;
import com.cloudera.impala.common.InternalException;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

/**
 * Representation of a union with its list of operands, and optional order by and limit.
 * A union materializes its results, and its resultExprs are slotrefs into the
 * materialized tuple.
 * During analysis, the operands are normalized (separated into a single sequence of
 * DISTINCT followed by a single sequence of ALL operands) and unnested to the extent
 * possible. This also creates the AggregationInfo for DISTINCT operands.
 */
public class UnionStmt extends QueryStmt {
  private final static Logger LOG = LoggerFactory.getLogger(UnionStmt.class);

  public static enum Qualifier {
    ALL,
    DISTINCT
  }

  /**
   * Represents an operand to a union, created by the parser.
   * Contains a query statement and the all/distinct qualifier
   * of the union operator (null for the first queryStmt).
   */
  public static class UnionOperand {
    private final QueryStmt queryStmt;
    // Null for the first operand.
    private Qualifier qualifier;

    // Analyzer used for this operand. Set in analyze().
    // We must preserve the conjuncts registered in the analyzer for partition pruning.
    private Analyzer analyzer;

    // map from UnionStmts result slots to our resultExprs; useful during plan generation
    private final Expr.SubstitutionMap smap = new Expr.SubstitutionMap();

    public UnionOperand(QueryStmt queryStmt, Qualifier qualifier) {
      this.queryStmt = queryStmt;
      this.qualifier = qualifier;
    }

    public void analyze(Analyzer parent)
        throws AnalysisException, AuthorizationException {
      analyzer = new Analyzer(parent, parent.getUser());
      queryStmt.analyze(analyzer);
    }

    public QueryStmt getQueryStmt() { return queryStmt; }
    public Qualifier getQualifier() { return qualifier; }
    // Used for propagating DISTINCT.
    public void setQualifier(Qualifier qualifier) { this.qualifier = qualifier; }
    public Analyzer getAnalyzer() { return analyzer; }
    public Expr.SubstitutionMap getSmap() { return smap; }

    @Override
    public UnionOperand clone() {
      return new UnionOperand(queryStmt.clone(), qualifier);
    }
  }

  // before analysis, this contains the list of union operands derived verbatim
  // from the query;
  // after analysis, this contains all of distinctOperands followed by allOperands
  protected final List<UnionOperand> operands;

  // filled during analyze(); contains all operands that need to go through
  // distinct aggregation
  protected final List<UnionOperand> distinctOperands = Lists.newArrayList();

  // filled during analyze(); contains all operands that can be aggregated with
  // a simple merge without duplicate elimination (also needs to merge the output
  // of the DISTINCT operands)
  protected final List<UnionOperand> allOperands = Lists.newArrayList();

  protected AggregateInfo distinctAggInfo;  // only set if we have DISTINCT ops

 // Single tuple materialized by the union. Set in analyze().
  protected TupleId tupleId;

  // set prior to unnesting
  protected String toSqlString = null;

  public UnionStmt(List<UnionOperand> operands,
      ArrayList<OrderByElement> orderByElements, LimitElement limitElement) {
    super(orderByElements, limitElement);
    this.operands = operands;
  }

  public List<UnionOperand> getOperands() { return operands; }
  public List<UnionOperand> getDistinctOperands() { return distinctOperands; }
  public boolean hasDistinctOps() { return !distinctOperands.isEmpty(); }
  public List<UnionOperand> getAllOperands() { return allOperands; }
  public boolean hasAllOps() { return !allOperands.isEmpty(); }
  public AggregateInfo getDistinctAggInfo() { return distinctAggInfo; }

  public void removeAllOperands() {
    operands.removeAll(allOperands);
    allOperands.clear();
  }

  /**
   * Propagates DISTINCT from left to right, and checks that all
   * union operands are union compatible, adding implicit casts if necessary.
   */
  @Override
  public void analyze(Analyzer analyzer)
      throws AnalysisException, AuthorizationException {
    super.analyze(analyzer);
    Preconditions.checkState(operands.size() > 0);

    // Propagates DISTINCT from right to left
    propagateDistinct();

    // Make sure all operands return an equal number of exprs.
    QueryStmt firstQuery = operands.get(0).getQueryStmt();
    operands.get(0).analyze(analyzer);
    List<Expr> firstQueryExprs = firstQuery.getBaseTblResultExprs();
    for (int i = 1; i < operands.size(); ++i) {
      QueryStmt query = operands.get(i).getQueryStmt();
      operands.get(i).analyze(analyzer);
      List<Expr> exprs = query.getBaseTblResultExprs();
      if (firstQueryExprs.size() != exprs.size()) {
        throw new AnalysisException("Operands have unequal number of columns:\n" +
            "'" + queryStmtToSql(firstQuery) + "' has " +
            firstQueryExprs.size() + " column(s)\n" +
            "'" + queryStmtToSql(query) + "' has " + exprs.size() + " column(s)");
      }
    }

    // Determine compatible types for exprs, position by position.
    for (int i = 0; i < firstQueryExprs.size(); ++i) {
      // Type compatible with the i-th exprs of all selects.
      // Initialize with type of i-th expr in first select.
      PrimitiveType compatibleType = firstQueryExprs.get(i).getType();
      // Remember last compatible expr for error reporting.
      Expr lastCompatibleExpr = firstQueryExprs.get(i);
      for (int j = 1; j < operands.size(); ++j) {
        List<Expr> resultExprs = operands.get(j).getQueryStmt().getBaseTblResultExprs();
        compatibleType = analyzer.getCompatibleType(compatibleType,
            lastCompatibleExpr, resultExprs.get(i));
        lastCompatibleExpr = resultExprs.get(i);
      }
      // Now that we've found a compatible type, add implicit casts if necessary.
      for (int j = 0; j < operands.size(); ++j) {
        List<Expr> resultExprs = operands.get(j).getQueryStmt().getBaseTblResultExprs();
        if (resultExprs.get(i).getType() != compatibleType) {
          Expr castExpr = resultExprs.get(i).castTo(compatibleType);
          resultExprs.set(i, castExpr);
        }
      }
    }
    // TODO: remove
    for (UnionOperand op: operands) {
      LOG.info("resultexprs: " + Expr.toSql(op.getQueryStmt().getBaseTblResultExprs()) + " " +
          Expr.debugString(op.getQueryStmt().getBaseTblResultExprs()));
    }

    // Create tuple descriptor materialized by this UnionStmt,
    // its resultExprs, and its sortInfo if necessary.
    createMetadata(analyzer);
    createSortInfo(analyzer);

    toSqlString = toSql();

    // fill distinct-/allOperands
    unnestOperands(analyzer);
  }

  /**
   * Marks the baseTblResultExprs of its operands as materialized, based on
   * which of the output slots have been marked.
   * Calls materializeRequiredSlots() on the operands themselves.
   */
  @Override
  public void materializeRequiredSlots(Analyzer analyzer) {
    TupleDescriptor tupleDesc = analyzer.getDescTbl().getTupleDesc(tupleId);
    if (!distinctOperands.isEmpty()) {
      // to keep things simple we materialize all grouping exprs = output slots,
      // regardless of what's being referenced externally
      for (SlotDescriptor slotDesc: tupleDesc.getSlots()) {
        slotDesc.setIsMaterialized(true);
      }
    }

    // collect operands' result exprs
    List<SlotDescriptor> outputSlots = tupleDesc.getSlots();
    List<Expr> exprs = Lists.newArrayList();
    for (int i = 0; i < outputSlots.size(); ++i) {
      SlotDescriptor slotDesc = outputSlots.get(i);
      if (!slotDesc.isMaterialized()) continue;
      for (UnionOperand op: operands) {
        exprs.add(op.getQueryStmt().getBaseTblResultExprs().get(i));
      }
      if (distinctAggInfo != null) {
        // also mark the corresponding slot in the distinct agg tuple as being
        // materialized
        distinctAggInfo.getAggTupleDesc().getSlots().get(i).setIsMaterialized(true);
      }
    }
    materializeSlots(analyzer, exprs);

    for (UnionOperand op: operands) {
      op.getQueryStmt().materializeRequiredSlots(analyzer);
    }
  }

  /**
   * Fill distinct-/allOperands and performs possible unnesting of UnionStmt
   * operands in the process.
   */
  private void unnestOperands(Analyzer analyzer)
      throws AnalysisException, AuthorizationException {
    if (operands.size() == 1) {
      // ValuesStmt for a single row
      allOperands.add(operands.get(0));
      return;
    }

    // find index of first ALL operand
    int firstUnionAllIdx = operands.size();
    for (int i = 1; i < operands.size(); ++i) {
      UnionOperand operand = operands.get(i);
      if (operand.getQualifier() == Qualifier.ALL) {
        firstUnionAllIdx = (i == 1 ? 0 : i);
        break;
      }
    }
    // operands[0] is always implicitly ALL, so operands[1] can't be the
    // first one
    Preconditions.checkState(firstUnionAllIdx != 1);

    // unnest DISTINCT operands
    Preconditions.checkState(distinctOperands.isEmpty());
    for (int i = 0; i < firstUnionAllIdx; ++i) {
      unnestOperand(distinctOperands, Qualifier.DISTINCT, operands.get(i));
    }

    // unnest ALL operands
    Preconditions.checkState(allOperands.isEmpty());
    for (int i = firstUnionAllIdx; i < operands.size(); ++i) {
      unnestOperand(allOperands, Qualifier.ALL, operands.get(i));
    }

    operands.clear();
    operands.addAll(distinctOperands);
    operands.addAll(allOperands);

    TupleDescriptor tupleDesc = analyzer.getDescTbl().getTupleDesc(tupleId);
    // create unnested operands' smaps
    for (UnionOperand operand: operands) {
      // operands' smaps were already set in the operands' analyze()
      operand.getSmap().clear();
      for (int i = 0; i < tupleDesc.getSlots().size(); ++i) {
        SlotDescriptor outputSlot = tupleDesc.getSlots().get(i);
        operand.getSmap().addMapping(
            new SlotRef(outputSlot),
            // TODO: baseTblResultExprs?
            operand.getQueryStmt().getResultExprs().get(i).clone(null));
      }
    }

    // create distinctAggInfo, if necessary
    if (!distinctOperands.isEmpty()) {
      // Aggregate produces exactly the same tuple as the original union stmt.
      ArrayList<Expr> groupingExprs = Expr.cloneList(resultExprs_, null);
      try {
        distinctAggInfo =
            AggregateInfo.create(groupingExprs, null,
              analyzer.getDescTbl().getTupleDesc(tupleId), analyzer);
      } catch (AnalysisException e) {
        // this should never happen
        throw new AnalysisException("error creating agg info in UnionStmt.analyze()");
      } catch (InternalException e) {
        // this should never happen
        throw new AnalysisException("error creating agg info in UnionStmt.analyze()");
      }
    }
  }

  /**
   * Add a single operand to the target list; if the operand itself is a UnionStmt,
   * apply unnesting to the extent possible (possibly modifying 'operand' in the process).
   */
  private void unnestOperand(
      List<UnionOperand> target, Qualifier targetQualifier, UnionOperand operand) {
    QueryStmt queryStmt = operand.getQueryStmt();
    if (queryStmt instanceof SelectStmt) {
      target.add(operand);
      return;
    }

    Preconditions.checkState(queryStmt instanceof UnionStmt);
    UnionStmt unionStmt = (UnionStmt) queryStmt;
    if (unionStmt.hasLimitClause()) {
      // we must preserve the nested Union
      target.add(operand);
    } else if (targetQualifier == Qualifier.DISTINCT || !unionStmt.hasDistinctOps()) {
      // there is no limit in the nested Union and we can absorb all of its
      // operands as-is
      target.addAll(unionStmt.getDistinctOperands());
      target.addAll(unionStmt.getAllOperands());
    } else {
      // the nested Union contains some Distinct ops and we're accumulating
      // into our All ops; unnest only the All ops and leave the rest in place
      target.addAll(unionStmt.getAllOperands());
      unionStmt.removeAllOperands();
      target.add(operand);
    }
  }

  /**
   * String representation of queryStmt used in reporting errors.
   * Allow subclasses to override this.
   */
  protected String queryStmtToSql(QueryStmt queryStmt) {
    return queryStmt.toSql();
  }

  /**
   * Propagates DISTINCT (if present) from right to left.
   * Implied associativity:
   * A UNION ALL B UNION DISTINCT C = (A UNION ALL B) UNION DISTINCT C
   * = A UNION DISTINCT B UNION DISTINCT C
   */
  private void propagateDistinct() {
    int lastDistinctPos = -1;
    for (int i = operands.size() - 1; i > 0; --i) {
      UnionOperand operand = operands.get(i);
      if (lastDistinctPos != -1) {
        // There is a DISTINCT somewhere to the right.
        operand.setQualifier(Qualifier.DISTINCT);
      } else if (operand.getQualifier() == Qualifier.DISTINCT) {
        lastDistinctPos = i;
      }
    }
  }

  /**
   * Create a descriptor for the tuple materialized by the union.
   * Set resultExprs to be slot refs into that tuple.
   * Also fills the substitution map, such that "order by" can properly resolve
   * column references from the result of the union.
   */
  private void createMetadata(Analyzer analyzer) throws AnalysisException {
    // Create tuple descriptor for materialized tuple created by the union.
    TupleDescriptor tupleDesc = analyzer.getDescTbl().createTupleDescriptor();
    tupleDesc.setIsMaterialized(true);
    tupleId = tupleDesc.getId();
    LOG.info("UnionStmt.createMetadata: tupleId=" + tupleId.toString());

    // One slot per expr in the select blocks. Use first select block as representative.
    List<Expr> firstSelectExprs = operands.get(0).getQueryStmt().getBaseTblResultExprs();

    // Compute column stats for the materialized slots from the source exprs.
    List<ColumnStats> columnStats = Lists.newArrayList();
    for (int i = 0; i < operands.size(); ++i) {
      List<Expr> selectExprs = operands.get(i).getQueryStmt().getBaseTblResultExprs();
      for (int j = 0; j < selectExprs.size(); ++j) {
        ColumnStats statsToAdd = ColumnStats.fromExpr(selectExprs.get(j));
        if (i == 0) {
          columnStats.add(statsToAdd);
        } else {
          columnStats.get(j).add(statsToAdd);
        }
      }
    }

    // Create tuple descriptor and slots.
    for (int i = 0; i < firstSelectExprs.size(); ++i) {
      Expr expr = firstSelectExprs.get(i);
      SlotDescriptor slotDesc = analyzer.addSlotDescriptor(tupleDesc);
      slotDesc.setLabel(getColLabels().get(i));
      slotDesc.setType(expr.getType());
      slotDesc.setStats(columnStats.get(i));
      SlotRef outputSlotRef = new SlotRef(slotDesc);
      resultExprs_.add(outputSlotRef);

      // Add to aliasSMap so that column refs in "order by" can be resolved.
      if (orderByElements_ != null) {
        SlotRef aliasRef = new SlotRef(null, getColLabels().get(i));
        if (aliasSmap_.containsMappingFor(aliasRef)) {
          ambiguousAliasList_.add(aliasRef);
        } else {
          aliasSmap_.addMapping(aliasRef, outputSlotRef);
        }
      }

      // register single-directional value transfers from output slot
      // to operands' result exprs (if those happen to be slotrefs)
      for (UnionOperand op: operands) {
        Expr resultExpr = op.getQueryStmt().getBaseTblResultExprs().get(i);
        SlotRef slotRef = resultExpr.unwrapSlotRef(true);
        if (slotRef == null) continue;
        analyzer.registerValueTransfer(outputSlotRef.getSlotId(), slotRef.getSlotId());
      }
    }
    baseTblResultExprs_ = resultExprs_;
  }

  /**
   * Substitute exprs of the form "<number>" with the corresponding
   * expressions from the resultExprs.
   */
  @Override
  protected void substituteOrdinals(List<Expr> exprs, String errorPrefix)
      throws AnalysisException {
    // Substitute ordinals.
    ListIterator<Expr> i = exprs.listIterator();
    while (i.hasNext()) {
      Expr expr = i.next();
      if (!(expr instanceof IntLiteral)) continue;
      long pos = ((IntLiteral) expr).getValue();
      if (pos < 1) {
        throw new AnalysisException(
            errorPrefix + ": ordinal must be >= 1: " + expr.toSql());
      }
      if (pos > resultExprs_.size()) {
        throw new AnalysisException(
            errorPrefix + ": ordinal exceeds number of items in select list: "
            + expr.toSql());
      }
      // Create copy to protect against accidentally shared state.
      i.set(resultExprs_.get((int) pos - 1).clone(null));
    }
  }

  public TupleId getTupleId() {
    return tupleId;
  }

  @Override
  public void getMaterializedTupleIds(ArrayList<TupleId> tupleIdList) {
    tupleIdList.add(tupleId);
  }

  @Override
  public String toSql() {
    if (toSqlString != null) return toSqlString;
    StringBuilder strBuilder = new StringBuilder();
    Preconditions.checkState(operands.size() > 0);

    if (withClause_ != null) {
      strBuilder.append(withClause_.toSql());
      strBuilder.append(" ");
    }

    strBuilder.append(operands.get(0).getQueryStmt().toSql());
    for (int i = 1; i < operands.size() - 1; ++i) {
      strBuilder.append(" UNION " +
          ((operands.get(i).getQualifier() == Qualifier.ALL) ? "ALL " : ""));
      if (operands.get(i).getQueryStmt() instanceof UnionStmt) {
        strBuilder.append("(");
      }
      strBuilder.append(operands.get(i).getQueryStmt().toSql());
      if (operands.get(i).getQueryStmt() instanceof UnionStmt) {
        strBuilder.append(")");
      }
    }
    // Determine whether we need parenthesis around the last union operand.
    UnionOperand lastOperand = operands.get(operands.size() - 1);
    QueryStmt lastQueryStmt = lastOperand.getQueryStmt();
    strBuilder.append(" UNION " +
        ((lastOperand.getQualifier() == Qualifier.ALL) ? "ALL " : ""));
    if (lastQueryStmt instanceof UnionStmt ||
        ((hasOrderByClause() || hasLimitClause()) && !lastQueryStmt.hasLimitClause() &&
            !lastQueryStmt.hasOrderByClause())) {
      strBuilder.append("(");
      strBuilder.append(lastQueryStmt.toSql());
      strBuilder.append(")");
    } else {
      strBuilder.append(lastQueryStmt.toSql());
    }
    // Order By clause
    if (hasOrderByClause()) {
      strBuilder.append(" ORDER BY ");
      for (int i = 0; i < orderByElements_.size(); ++i) {
        strBuilder.append(orderByElements_.get(i).toSql());
        strBuilder.append((i+1 != orderByElements_.size()) ? ", " : "");
      }
    }
    // Limit clause.
    strBuilder.append(limitElement_.toSql());
    return strBuilder.toString();
  }

  @Override
  public ArrayList<String> getColLabels() {
    Preconditions.checkState(operands.size() > 0);
    return operands.get(0).getQueryStmt().getColLabels();
  }

  @Override
  public QueryStmt clone() {
    List<UnionOperand> operandClones = Lists.newArrayList();
    for (UnionOperand operand: operands) {
      operandClones.add(operand.clone());
    }
    UnionStmt unionClone = new UnionStmt(operandClones, cloneOrderByElements(),
        limitElement_ == null ? null : limitElement_.clone(null));
    unionClone.setWithClause(cloneWithClause());
    return unionClone;
  }
}
