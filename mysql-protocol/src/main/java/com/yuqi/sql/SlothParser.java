package com.yuqi.sql;

import com.google.common.collect.ImmutableList;
import com.yuqi.sql.rule.SlothRules;
import com.yuqi.sql.trait.SlothConvention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.metadata.ChainedRelMetadataProvider;
import org.apache.calcite.rel.metadata.DefaultRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.rules.CoreRules;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;

import java.util.List;


/**
 * @author yuqi
 * @mail yuqi4733@gmail.com
 * @description your description
 * @time 10/7/20 19:50
 **/
public class SlothParser implements RelOptTable.ViewExpander {
    private SqlParser sqlParser;
    private RelOptPlanner relOptPlanner;
    private SqlValidator sqlValidator;
    private CalciteCatalogReader calciteCatalogReader;
    private int expansionDepth;

    private SqlNode sqlNode;
    private RelNode relNode;
    private RelDataType relDataType;
    List<List<String>> fieldOrigins;

    public CalciteCatalogReader getCalciteCatalogReader() {
        return calciteCatalogReader;
    }

    public SlothParser(SqlParser sqlParser, RelOptPlanner relOptPlanner,
                       CalciteCatalogReader calciteCatalogReader, SqlValidator sqlValidator) {
        this.sqlParser = sqlParser;
        this.relOptPlanner = relOptPlanner;
        this.sqlValidator = sqlValidator;
        this.calciteCatalogReader = calciteCatalogReader;
    }

    private SqlToRelConverter getSqlToRelConverter() {
        final SqlToRelConverter.ConfigBuilder builder =
                SqlToRelConverter.configBuilder()
                        .withTrimUnusedFields(true);

        final RexBuilder rexBuilder = new RexBuilder(calciteCatalogReader.getTypeFactory());
        final RelOptCluster relOptCluster = RelOptCluster.create(relOptPlanner, rexBuilder);
        RelMetadataProvider planChain = ChainedRelMetadataProvider.of(ImmutableList.of(DefaultRelMetadataProvider.INSTANCE));
        relOptCluster.setMetadataProvider(planChain);

        return new SqlToRelConverter(this, sqlValidator, calciteCatalogReader,
                relOptCluster, StandardConvertletTable.INSTANCE, builder.build());
    }

    //db元数据存在mysql, 起动的时候再恢复
    public SqlNode getSqlNode(String sql) throws SqlParseException {
        return sqlParser.parseQuery(sql);
    }

    public SqlNode getSqlNode() throws SqlParseException {
        return sqlParser.parseStmt();
    }

    public RelNode getPlan(SqlNode sqlNode) {
        final SqlToRelConverter sqlToRelConverter = getSqlToRelConverter();
        RelRoot relRoot = sqlToRelConverter.convertQuery(sqlNode, true, true);

        relDataType = sqlValidator.getValidatedNodeType(sqlNode);
        fieldOrigins = sqlValidator.getFieldOrigins(sqlNode);

        //
        relRoot = trimUnusedFields(relRoot, sqlToRelConverter);
        relNode = sqlToRelConverter.flattenTypes(relRoot.rel, true);
        relNode = sqlToRelConverter.decorrelate(sqlNode, relNode);
        return optimize(relNode);
    }

    public RelNode getPlan(String sql) throws SqlParseException {
        final SqlNode sqlNode = sqlParser.parseQuery(sql);
        return getPlan(sqlNode);
    }

    protected RelRoot trimUnusedFields(RelRoot root, SqlToRelConverter converter) {
        final boolean ordered = !root.collation.getFieldCollations().isEmpty();
        final boolean dml = SqlKind.DML.contains(root.kind);
        return root.withRel(converter.trimUnusedFields(dml || ordered, root.rel));
    }

    @Override
    public RelRoot expandView(RelDataType rowType, String queryString, List<String> schemaPath, List<String> viewPath) {
        expansionDepth++;

        SqlParser parser = ParserFactory.getSqlParser(queryString);
        SqlNode sqlNode;
        try {
            sqlNode = parser.parseQuery();
        } catch (SqlParseException e) {
            throw new RuntimeException("parse failed", e);
        }

        // View may have different schema path than current connection.
        SqlToRelConverter sqlToRelConverter = getSqlToRelConverter();
        RelRoot root =
                sqlToRelConverter.convertQuery(sqlNode, true, false);

        --expansionDepth;
        return root;
    }


    private RelNode optimize(RelNode relNode) {

        //before cbo
        final HepPlanner hepPlannerBefore = buildHepPlannerBeforeCbo();
        hepPlannerBefore.setRoot(relNode);
        relNode = hepPlannerBefore.findBestExp();

        //cbo
        // Here: reqiredTraitSet is the RelTraitSet you want, basicly you should at
        // least assure the convention you want.
        RelTraitSet reqiredTraitSet = relNode.getTraitSet()
                .replace(SlothConvention.INSTANCE)
                //TODO FIX relcollation bug
                .plus(RelCollations.EMPTY)
                .simplify();
        RelNode relNode1 = relNode.getTraitSet().equals(reqiredTraitSet)
                ? relNode : relOptPlanner.changeTraits(relNode, reqiredTraitSet);

        relOptPlanner.setRoot(relNode1);
        relNode = relOptPlanner.chooseDelegate().findBestExp();

        //after cbo
        final HepPlanner hepPlannerAfter = buildHepPlannerAfterCbo();
        hepPlannerAfter.setRoot(relNode);
        return hepPlannerAfter.findBestExp();
    }


    private HepPlanner buildHepPlannerBeforeCbo() {
        HepProgramBuilder builder = new HepProgramBuilder();
        for (RelOptRule relOptRule : SlothRules.CONSTANT_REDUCTION_RULES) {
            builder.addRuleInstance(relOptRule);
        }

        for (RelOptRule relOptRule : SlothRules.BASE_RULES) {
            builder.addRuleInstance(relOptRule);
        }

        for (RelOptRule relOptRule : SlothRules.ABSTRACT_RELATIONAL_RULES) {
            builder.addRuleInstance(relOptRule);
        }

        for (RelOptRule relOptRule : SlothRules.ABSTRACT_RULES) {
            builder.addRuleInstance(relOptRule);
        }

        final HepPlanner hepPlanner = new HepPlanner(builder.build());

        //Why remove the join rule here
        hepPlanner.removeRule(CoreRules.JOIN_COMMUTE);
        hepPlanner.removeRule(CoreRules.JOIN_ASSOCIATE);
        return hepPlanner;
    }

    public HepPlanner buildHepPlannerAfterCbo() {
        HepProgramBuilder builder = new HepProgramBuilder();
        for (RelOptRule relOptRule : SlothRules.AFTER_CBO_RULES) {
            builder.addRuleInstance(relOptRule);
        }
        return new HepPlanner(builder.build());
    }
}
