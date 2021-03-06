/*
 *
 *  * Copyright (c) 2011-2015 EPFL DATA Laboratory
 *  * Copyright (c) 2014-2015 The Squall Collaboration (see NOTICE)
 *  *
 *  * All rights reserved.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package ch.epfl.data.squall.examples.imperative.traditional;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;

import ch.epfl.data.squall.components.Component;
import ch.epfl.data.squall.components.DataSourceComponent;
import ch.epfl.data.squall.components.hyper_cube.HyperCubeJoinComponentFactory;
import ch.epfl.data.squall.expressions.ColumnReference;
import ch.epfl.data.squall.expressions.Multiplication;
import ch.epfl.data.squall.expressions.Subtraction;
import ch.epfl.data.squall.expressions.ValueExpression;
import ch.epfl.data.squall.expressions.ValueSpecification;
import ch.epfl.data.squall.operators.AggregateOperator;
import ch.epfl.data.squall.operators.AggregateSumOperator;
import ch.epfl.data.squall.operators.ProjectOperator;
import ch.epfl.data.squall.operators.SelectOperator;
import ch.epfl.data.squall.predicates.ComparisonPredicate;
import ch.epfl.data.squall.query_plans.QueryBuilder;
import ch.epfl.data.squall.query_plans.QueryPlan;
import ch.epfl.data.squall.query_plans.ThetaQueryPlansParameters;
import ch.epfl.data.squall.types.DateType;
import ch.epfl.data.squall.types.DoubleType;
import ch.epfl.data.squall.types.IntegerType;
import ch.epfl.data.squall.types.NumericType;
import ch.epfl.data.squall.types.StringType;
import ch.epfl.data.squall.types.Type;

public class TraditionalHashTPCH3Plan extends QueryPlan {
    
    private static Logger LOG = Logger.getLogger(TraditionalHashTPCH3Plan.class);

    private static final IntegerType _ic = new IntegerType();

    private static final String _customerMktSegment = "BUILDING";
    private static final String _dateStr = "1995-03-15";

    private static final Type<Date> _dateConv = new DateType();
    private static final NumericType<Double> _doubleConv = new DoubleType();
    private static final Type<String> _sc = new StringType();
    private static final Date _date = _dateConv.fromString(_dateStr);

    private QueryBuilder _queryBuilder = new QueryBuilder();

    public TraditionalHashTPCH3Plan(String dataPath, String extension, Map conf) {
        // -------------------------------------------------------------------------------------
        List<Integer> hashCustomer = Arrays.asList(0);

        SelectOperator selectionCustomer = new SelectOperator(
            new ComparisonPredicate(new ColumnReference(_sc, 6),
                new ValueSpecification(_sc, _customerMktSegment)));

        ProjectOperator projectionCustomer = new ProjectOperator(
            new int[] { 0 });

        DataSourceComponent relationCustomer = new DataSourceComponent(
            "CUSTOMER", dataPath + "customer" + extension, conf)
            .setOutputPartKey(hashCustomer).add(selectionCustomer)
            .add(projectionCustomer);
        _queryBuilder.add(relationCustomer);

        // -------------------------------------------------------------------------------------
        List<Integer> hashOrders = Arrays.asList(1);

        SelectOperator selectionOrders = new SelectOperator(
            new ComparisonPredicate(ComparisonPredicate.LESS_OP,
                new ColumnReference(_dateConv, 4),
                new ValueSpecification(_dateConv, _date)));

        ProjectOperator projectionOrders = new ProjectOperator(new int[] { 0,
            1, 4, 7 });

        DataSourceComponent relationOrders = new DataSourceComponent("ORDERS",
            dataPath + "orders" + extension, conf).setOutputPartKey(hashOrders)
            .add(selectionOrders).add(projectionOrders);
        _queryBuilder.add(relationOrders);

        // -------------------------------------------------------------------------------------
        List<Integer> hashLineitem = Arrays.asList(0);

        SelectOperator selectionLineitem = new SelectOperator(
            new ComparisonPredicate(ComparisonPredicate.GREATER_OP,
                new ColumnReference(_dateConv, 10),
                new ValueSpecification(_dateConv, _date)));

        ProjectOperator projectionLineitem = new ProjectOperator(new int[] { 0,
            5, 6 });

        DataSourceComponent relationLineitem = new DataSourceComponent(
            "LINEITEM", dataPath + "lineitem" + extension, conf)
            .setOutputPartKey(hashLineitem).add(selectionLineitem)
            .add(projectionLineitem);
        _queryBuilder.add(relationLineitem);


        // set up aggregation function on the StormComponent(Bolt) where join is
        // performed
        // 1 - discount
        ValueExpression<Double> substract = new Subtraction(
            new ValueSpecification(_doubleConv, 1.0), new ColumnReference(
                _doubleConv, 7));
         // extendedPrice*(1-discount)
        ValueExpression<Double> product = new Multiplication(
            new ColumnReference(_doubleConv, 6), substract);
        AggregateOperator agg = new AggregateSumOperator(product, conf)
            .setGroupByColumns(Arrays.asList(5, 3, 4));

       HyperCubeJoinComponentFactory lastJoiner = 
            new HyperCubeJoinComponentFactory(new Component[]{
                relationCustomer, 
                relationOrders,
                relationLineitem});
        // Hypecube
        // CUSTKEY
        lastJoiner.addRelation(relationCustomer, new Type[]{_ic}, new String[]{"CUSTKEY"});
        
        // ORDERKEY CUSTKEY ORDERDATE SHIPPRIORITY
        lastJoiner.addRelation(relationOrders, new Type[]{_ic, _ic, _dateConv, _ic}, 
            new String[]{"ORDERKEY", "CUSTKEY", "ORDERDATE", "SHIPPRIORITY"}); 
        
        // ORDERKEY EXTENDEDPRICE DISCOUNT
        lastJoiner.addRelation(relationLineitem, new Type[]{_ic, _doubleConv, _doubleConv},
            new String[]{"ORDERKEY", "EXTENDEDPRICE", "DISCOUNT"});

        // Predicates
        ColumnReference colC_CUSTKEY = new ColumnReference(_ic, 0);
        ColumnReference colO_CUSTKEY = new ColumnReference(_ic, 1);
        ComparisonPredicate C_O_comp = new ComparisonPredicate(ComparisonPredicate.EQUAL_OP, 
            colC_CUSTKEY, colO_CUSTKEY);

        lastJoiner.addPredicate("CUSTOMERORDERS", C_O_comp);


        ColumnReference colO_ORDERKEY = new ColumnReference(_ic, 0);
        ColumnReference colL_ORDERKEY = new ColumnReference(_ic, 0);
        ComparisonPredicate O_L_comp = new ComparisonPredicate(ComparisonPredicate.EQUAL_OP, 
            colO_ORDERKEY, colL_ORDERKEY);

        lastJoiner.addPredicate("LINEITEMORDERS", O_L_comp);

        _queryBuilder.add(lastJoiner.createHyperCubeJoinOperator().add(agg).setContentSensitiveThetaJoinWrapper(_ic));

        // -------------------------------------------------------------------------------------


    }

    public QueryBuilder getQueryPlan() {
        return _queryBuilder;
    }
}
