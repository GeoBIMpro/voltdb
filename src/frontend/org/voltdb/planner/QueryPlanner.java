/* This file is part of VoltDB.
 * Copyright (C) 2008-2015 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.planner;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hsqldb_voltpatches.HSQLInterface;
import org.hsqldb_voltpatches.HSQLInterface.HSQLParseException;
import org.hsqldb_voltpatches.VoltXMLElement;
import org.voltdb.ParameterSet;
import org.voltdb.VoltType;
import org.voltdb.catalog.Cluster;
import org.voltdb.catalog.Constraint;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Table;
import org.voltdb.compiler.DatabaseEstimates;
import org.voltdb.compiler.DeterminismMode;
import org.voltdb.compiler.ScalarValueHints;
import org.voltdb.plannodes.AbstractPlanNode;
import org.voltdb.plannodes.AbstractReceivePlanNode;
import org.voltdb.plannodes.InsertPlanNode;
import org.voltdb.plannodes.SchemaColumn;
import org.voltdb.plannodes.SendPlanNode;
import org.voltdb.types.ConstraintType;
import org.voltdb.types.PlanNodeType;

/**
 * The query planner accepts catalog data, SQL statements from the catalog, then
 * outputs the plan with the lowest cost according to the cost model.
 *
 */
public class QueryPlanner {
    String m_sql;
    String m_stmtName;
    String m_procName;
    HSQLInterface m_HSQL;
    DatabaseEstimates m_estimates;
    Cluster m_cluster; // It's not used, that gets removed in ENG-12133
    Database m_db;
    String m_recentErrorMsg;
    StatementPartitioning m_partitioning;
    int m_maxTablesPerJoin;
    AbstractCostModel m_costModel;
    ScalarValueHints[] m_paramHints;
    String m_joinOrder;
    DeterminismMode m_detMode;
    PlanSelector m_planSelector;
    boolean m_isUpsert;

    // generated by parse(..)
    VoltXMLElement m_xmlSQL = null;
    ParameterizationInfo m_paramzInfo = null;

    // generated by parameterize(...)
    int m_adhocUserParamsCount = 0;

    // generated by plan(...)
    boolean m_hasExceptionWhenParameterized = false;

    static boolean m_debuggingStaticModeToRetryOnError = true;

    public static String UPSERT_TAG = "isUpsert";

    /**
     * Initialize planner with physical schema info and a reference to HSQLDB parser.
     *
     * @param sql Literal SQL statement to parse
     * @param stmtName The name of the statement for logging/debugging
     * @param procName The name of the proc for logging/debugging
     * @param catalogDb Catalog info about schema, metadata and procedures.
     * @param partitioning Describes the specified and inferred partition context.
     * @param HSQL HSQLInterface pointer used for parsing SQL into XML.
     * @param estimates
     * @param suppressDebugOutput
     * @param maxTablesPerJoin
     * @param costModel The current cost model to evaluate plans with.
     * @param paramHints
     * @param joinOrder
     */
    public QueryPlanner(String sql,
                        String stmtName,
                        String procName,
                        Database catalogDb,
                        StatementPartitioning partitioning,
                        HSQLInterface HSQL,
                        DatabaseEstimates estimates,
                        boolean suppressDebugOutput,
                        int maxTablesPerJoin,
                        AbstractCostModel costModel,
                        ScalarValueHints[] paramHints,
                        String joinOrder,
                        DeterminismMode detMode)
    {
        assert(sql != null);
        assert(stmtName != null);
        assert(procName != null);
        assert(HSQL != null);
        assert(catalogDb != null);
        assert(costModel != null);
        assert(detMode != null);

        m_sql = sql;
        m_stmtName = stmtName;
        m_procName = procName;
        m_HSQL = HSQL;
        m_db = catalogDb;
        m_estimates = estimates;
        m_partitioning = partitioning;
        m_maxTablesPerJoin = maxTablesPerJoin;
        m_costModel = costModel;
        m_paramHints = paramHints;
        m_joinOrder = joinOrder;
        m_detMode = detMode;
        m_planSelector = new PlanSelector(m_cluster, m_db, m_estimates, m_stmtName,
                m_procName, m_sql, m_costModel, m_paramHints, m_detMode,
                suppressDebugOutput);
        m_isUpsert = false;
    }

    /**
     * Parse a SQL literal statement into an unplanned, intermediate representation.
     * This is normally followed by a call to
     * {@link this#plan(AbstractCostModel, String, String, String, String, int, ScalarValueHints[]) },
     * but splitting these two affords an opportunity to check a cache for a plan matching
     * the auto-parameterized parsed statement.
     */
    public void parse() throws PlanningErrorException {
        // reset any error message
        m_recentErrorMsg = null;

        // Reset plan node ids to start at 1 for this plan
        AbstractPlanNode.resetPlanNodeIds();

        // determine the type of the query
        //
        // (Hmmm...  seems like this pre-processing of the SQL text
        // and subsequent placement of UPSERT_TAG should be pushed down
        // into getXMLCompiledStatement)
        m_sql = m_sql.trim();
        if (m_sql.length() > 6 && m_sql.substring(0,6).toUpperCase().startsWith("UPSERT")) { // ENG-7395
            m_isUpsert = true;
            m_sql = "INSERT" + m_sql.substring(6);
        }

        // use HSQLDB to get XML that describes the semantics of the statement
        // this is much easier to parse than SQL and is checked against the catalog
        try {
            m_xmlSQL = m_HSQL.getXMLCompiledStatement(m_sql);
        } catch (HSQLParseException e) {
            // XXXLOG probably want a real log message here
            throw new PlanningErrorException(e.getMessage());
        }

        if (m_isUpsert) {
            assert(m_xmlSQL.name.equalsIgnoreCase("INSERT"));
            // for AdHoc cache distinguish purpose which is based on the XML
            m_xmlSQL.attributes.put(UPSERT_TAG, "true");
        }

        m_planSelector.outputCompiledStatement(m_xmlSQL);
    }

    /**
     * This method behaves similarly to parse(), but allows the caller to pass in XML
     * to avoid re-parsing SQL text that has already gone through HSQL.
     *
     * @param  xmlSql  XML produced by previous invocation of HSQL
     * */
    public void parseFromXml(VoltXMLElement xmlSQL) {
        m_recentErrorMsg = null;
        m_xmlSQL = xmlSQL;
        if (m_xmlSQL.attributes.containsKey(UPSERT_TAG)) {
            m_isUpsert = true;
        }

        m_planSelector.outputCompiledStatement(m_xmlSQL);
    }

    /**
     * Auto-parameterize all of the literals in the parsed SQL statement.
     *
     * @return An opaque token representing the parsed statement with (possibly) parameterization.
     */
    public String parameterize() {
        m_paramzInfo = ParameterizationInfo.parameterize(m_xmlSQL);

        Set<Integer> paramIds = new HashSet<Integer>();
        ParameterizationInfo.findUserParametersRecursively(m_xmlSQL, paramIds);
        m_adhocUserParamsCount = paramIds.size();

        // skip plans with pre-existing parameters and plans that don't parameterize
        // assume a user knows how to cache/optimize these
        if (m_paramzInfo != null) {
            // if requested output the second version of the parsed plan
            m_planSelector.outputParameterizedCompiledStatement(m_paramzInfo.parameterizedXmlSQL);
            return m_paramzInfo.parameterizedXmlSQL.toMinString();
        }

        // fallback when parameterization is
        return m_xmlSQL.toMinString();
    }

    public String[] extractedParamLiteralValues() {
        if (m_paramzInfo == null) {
            return null;
        }
        return m_paramzInfo.paramLiteralValues;
    }

    public ParameterSet extractedParamValues(VoltType[] parameterTypes) throws Exception {
        if (m_paramzInfo == null) {
            return null;
        }
        return m_paramzInfo.extractedParamValues(parameterTypes);
    }

    /**
     * Get the best plan for the SQL statement given, assuming the given costModel.
     *
     * @return The best plan found for the SQL statement.
     * @throws PlanningErrorException on failure.
     */
    public CompiledPlan plan() throws PlanningErrorException {
        // reset any error message
        m_recentErrorMsg = null;

        // what's going to happen next:
        //  If a parameterized statement exists, try to make a plan with it
        //  On success return the plan.
        //  On failure, try the plan again without parameterization

        if (m_paramzInfo != null) {
            try {
                // compile the plan with new parameters
                CompiledPlan plan = compileFromXML(m_paramzInfo.parameterizedXmlSQL,
                                                   m_paramzInfo.paramLiteralValues);
                if (plan != null) {
                    if (m_isUpsert) {
                        replacePlanForUpsert(plan);
                    }
                    if (plan.extractParamValues(m_paramzInfo)) {
                        return plan;
                    }
                } else {
                    if (m_debuggingStaticModeToRetryOnError) {
                         plan = compileFromXML(m_paramzInfo.parameterizedXmlSQL,
                                               m_paramzInfo.paramLiteralValues);
                    }
                }
                // fall through to try replan without parameterization.
            }
            catch (Exception e) {
                // ignore any errors planning with parameters
                // fall through to re-planning without them
                m_hasExceptionWhenParameterized = true;

                // note, expect real planning errors ignored here to be thrown again below
                m_recentErrorMsg = null;
                m_partitioning.resetAnalysisState();
            }
        }

        // if parameterization isn't requested or if it failed, plan here
        CompiledPlan plan = compileFromXML(m_xmlSQL, null);
        if (plan == null) {
            if (m_debuggingStaticModeToRetryOnError) {
                plan = compileFromXML(m_xmlSQL, null);
            }
            throw new PlanningErrorException(m_recentErrorMsg);
        }

        if (m_isUpsert) {
            replacePlanForUpsert(plan);
        }

        return plan;
    }

    private static void replacePlanForUpsert (CompiledPlan plan) {
        plan.rootPlanGraph = replaceInsertPlanNodeWithUpsert(plan.rootPlanGraph);
        plan.subPlanGraph  = replaceInsertPlanNodeWithUpsert(plan.subPlanGraph);

        if (plan.explainedPlan != null) {
            plan.explainedPlan = plan.explainedPlan.replace("INSERT", "UPSERT");
        }
    }

    /**
     * @return Was this statement planned with auto-parameterization?
     */
    public boolean compiledAsParameterizedPlan() {
        return m_paramzInfo != null;
    }

    public int getAdhocUserParamsCount() {
        return m_adhocUserParamsCount;
    }

    public boolean wasBadPameterized() {
        return m_hasExceptionWhenParameterized;
    }

    private CompiledPlan compileFromXML(VoltXMLElement xmlSQL, String[] paramValues) {
        // Get a parsed statement from the xml
        // The callers of compilePlan are ready to catch any exceptions thrown here.
        AbstractParsedStmt parsedStmt = AbstractParsedStmt.parse(m_sql, xmlSQL, paramValues, m_db, m_joinOrder);
        if (parsedStmt == null)
        {
            m_recentErrorMsg = "Failed to parse SQL statement: " + getOriginalSql();
            return null;
        }

        if (m_isUpsert) {
            // no insert/upsert with joins
            if (parsedStmt.m_tableList.size() != 1) {
                m_recentErrorMsg = "UPSERT is support only with one single table: " + getOriginalSql();
                return null;
            }

            Table tb = parsedStmt.m_tableList.get(0);
            Constraint pkey = null;
            for (Constraint ct: tb.getConstraints()) {
                if (ct.getType() == ConstraintType.PRIMARY_KEY.getValue()) {
                    pkey = ct;
                    break;
                }
            }

            if (pkey == null) {
                m_recentErrorMsg = "Unsupported UPSERT table without primary key: " + getOriginalSql();
                return null;
            }
        }

        m_planSelector.outputParsedStatement(parsedStmt);

        // Init Assembler. Each plan assembler requires a new instance of the PlanSelector
        // to keep track of the best plan
        PlanAssembler assembler = new PlanAssembler(m_cluster, m_db, m_partitioning,
                (PlanSelector) m_planSelector.clone());
        // find the plan with minimal cost
        CompiledPlan bestPlan = assembler.getBestCostPlan(parsedStmt);

        // This processing of bestPlan outside/after getBestCostPlan
        // allows getBestCostPlan to be called both here and
        // in PlanAssembler.getNextUnion on each branch of a union.

        // make sure we got a winner
        if (bestPlan == null) {
            if (m_debuggingStaticModeToRetryOnError) {
                assembler.getBestCostPlan(parsedStmt);
            }
            m_recentErrorMsg = assembler.getErrorMessage();
            if (m_recentErrorMsg == null) {
                m_recentErrorMsg = "Unable to plan for statement. Error unknown.";
            }
            return null;
        }

        if (bestPlan.isReadOnly()) {
            SendPlanNode sendNode = new SendPlanNode();
            // connect the nodes to build the graph
            sendNode.addAndLinkChild(bestPlan.rootPlanGraph);
            // this plan is final, generate schema and resolve all the column index references
            bestPlan.rootPlanGraph = sendNode;
        }

        // Execute the generateOutputSchema and resolveColumnIndexes once for the best plan
        bestPlan.rootPlanGraph.generateOutputSchema(m_db);
        bestPlan.rootPlanGraph.resolveColumnIndexes();

        if (parsedStmt instanceof ParsedSelectStmt) {
            List<SchemaColumn> columns = bestPlan.rootPlanGraph.getOutputSchema().getColumns();
            ((ParsedSelectStmt)parsedStmt).checkPlanColumnMatch(columns);
        }

        // Output the best plan debug info
        assembler.finalizeBestCostPlan();

        // reset all the plan node ids for a given plan
        // this makes the ids deterministic
        bestPlan.resetPlanNodeIds(1);

        // split up the plan everywhere we see send/recieve into multiple plan fragments
        List<AbstractPlanNode> receives = bestPlan.rootPlanGraph.findAllNodesOfClass(AbstractReceivePlanNode.class);
        if (receives.size() > 1) {
            // Have too many receive node for two fragment plan limit
            m_recentErrorMsg = "This join of multiple partitioned tables is too complex. "
                    + "Consider simplifying its subqueries: " + getOriginalSql();
            return null;
        }

        /*/ enable for debug ...
        if (receives.size() > 1) {
            System.out.println(plan.rootPlanGraph.toExplainPlanString());
        }
        // ... enable for debug */
        if (receives.size() == 1) {
            AbstractReceivePlanNode recvNode = (AbstractReceivePlanNode) receives.get(0);
            fragmentize(bestPlan, recvNode);
        }

        return bestPlan;
    }

    private static void fragmentize(CompiledPlan plan, AbstractReceivePlanNode recvNode) {
        assert(recvNode.getChildCount() == 1);
        AbstractPlanNode childNode = recvNode.getChild(0);
        assert(childNode instanceof SendPlanNode);
        SendPlanNode sendNode = (SendPlanNode) childNode;

        // disconnect the send and receive nodes
        sendNode.clearParents();
        recvNode.clearChildren();

        plan.subPlanGraph = sendNode;
        return;
    }

    public static AbstractPlanNode replaceInsertPlanNodeWithUpsert(AbstractPlanNode root) {
        if (root == null) {
            return null;
        }

        List<AbstractPlanNode> inserts = root.findAllNodesOfType(PlanNodeType.INSERT);
        if (inserts.size() == 1) {
            InsertPlanNode insertNode = (InsertPlanNode)inserts.get(0);
            insertNode.setUpsert(true);
        }

        return root;
    }

    private String getOriginalSql() {
        if (! m_isUpsert) {
            return m_sql;
        }

        return "UPSERT" + m_sql.substring(6);
    }
}
