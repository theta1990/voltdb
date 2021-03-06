/* This file is part of VoltDB.
 * Copyright (C) 2008-2014 VoltDB Inc.
 *
 * This file contains original code and/or modifications of original code.
 * Any modifications made by VoltDB Inc. are licensed under the following
 * terms and conditions:
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
/* Copyright (C) 2008 by H-Store Project
 * Brown University
 * Massachusetts Institute of Technology
 * Yale University
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

#include "abstractexecutor.h"

#include "execution/VoltDBEngine.h"
#include "plannodes/abstractoperationnode.h"
#include "plannodes/abstractscannode.h"
#include "storage/tablefactory.h"
#include "storage/TableCatalogDelegate.hpp"

#include <vector>

using namespace std;
using namespace voltdb;

bool AbstractExecutor::init(VoltDBEngine* engine,
                            TempTableLimits* limits)
{
    assert (m_abstractNode);

    //
    // Grab the input tables directly from this node's children
    //
    vector<Table*> input_tables;
    for (int ctr = 0,
             cnt = static_cast<int>(m_abstractNode->getChildren().size());
         ctr < cnt;
         ctr++)
    {
        Table* table = m_abstractNode->getChildren()[ctr]->getOutputTable();
        if (table == NULL) {
            VOLT_ERROR("Output table from PlanNode '%s' is NULL",
                       m_abstractNode->getChildren()[ctr]->debug().c_str());
            return false;
        }
        input_tables.push_back(table);
    }
    m_abstractNode->setInputTables(input_tables);

    // Some tables have target tables (scans + operations) that are
    // based on tables under the control of the local storage manager
    // (as opposed to an intermediate result table). We'll grab them
    // from the VoltDBEngine. This is kind of a hack job here... is
    // there a better way?

    AbstractScanPlanNode* scan_node =
        dynamic_cast<AbstractScanPlanNode*>(m_abstractNode);
    AbstractOperationPlanNode* oper_node =
        dynamic_cast<AbstractOperationPlanNode*>(m_abstractNode);
    if (scan_node || oper_node)
    {
        Table* target_table = NULL;

        string targetTableName;
        if (scan_node) {
            targetTableName = scan_node->getTargetTableName();
            target_table = scan_node->getTargetTable();
        } else if (oper_node) {
            targetTableName = oper_node->getTargetTableName();
            target_table = oper_node->getTargetTable();
        }

        // If the target_table is NULL, then we need to ask the engine
        // for a reference to what we need
        // Really, we can't enforce this when we load the plan? --izzy 7/3/2010
        bool is_subquery = (scan_node != NULL && scan_node->isSubQuery());
        if (target_table == NULL && !is_subquery) {
            target_table = engine->getTable(targetTableName);
            if (target_table == NULL) {
                VOLT_ERROR("Failed to retrieve target table '%s' "
                           "from execution engine for PlanNode '%s'",
                           targetTableName.c_str(),
                           m_abstractNode->debug().c_str());
                return false;
            }
            TableCatalogDelegate * tcd = engine->getTableDelegate(targetTableName);
            assert(tcd != NULL);
            if (scan_node) {
                scan_node->setTargetTableDelegate(tcd);
            } else if (oper_node) {
                oper_node->setTargetTableDelegate(tcd);
            }
        }
    }
    needs_outputtable_clear_cached = needsOutputTableClear();

    // Call the p_init() method on our derived class
    if (!p_init(m_abstractNode, limits)) {
        return false;
    }
    Table* tmp_output_table_base = m_abstractNode->getOutputTable();
    m_tmpOutputTable = dynamic_cast<TempTable*>(tmp_output_table_base);

    // determines whether the output table should be cleared or not.
    // specific executor might not need (and must not do) clearing.
    if (!needs_outputtable_clear_cached) {
        VOLT_TRACE("Did not clear output table because the derived class"
                   " answered so");
        m_tmpOutputTable = NULL;
    }
    return true;
}

/**
 * Set up a multi-column temp output table for those executors that require one.
 * Called from p_init.
 */
void AbstractExecutor::setTempOutputTable(TempTableLimits* limits, const string tempTableName) {
    assert(limits);
    TupleSchema* schema = m_abstractNode->generateTupleSchema(true);
    int column_count = (int)m_abstractNode->getOutputSchema().size();
    std::vector<std::string> column_names(column_count);
    assert(column_count >= 1);
    for (int ctr = 0; ctr < column_count; ctr++)
    {
        column_names[ctr] = m_abstractNode->getOutputSchema()[ctr]->getColumnName();
    }
    m_abstractNode->setOutputTable(TableFactory::getTempTable(m_abstractNode->databaseId(),
                                                              tempTableName,
                                                              schema,
                                                              column_names,
                                                              limits));
}

/**
 * Set up a single-column temp output table for DML executors that require one to return their counts.
 * Called from p_init.
 */
void AbstractExecutor::setDMLCountOutputTable(TempTableLimits* limits) {
    TupleSchema* schema = m_abstractNode->generateDMLCountTupleSchema();
    const std::vector<std::string> columnNames(1, "modified_tuples");
    m_abstractNode->setOutputTable(TableFactory::getTempTable(m_abstractNode->databaseId(),
                                                              "temp",
                                                              schema,
                                                              columnNames,
                                                              limits));
}


AbstractExecutor::~AbstractExecutor() {}
