/*
 * Copyright 2018 National Library of Norway.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package no.nb.nna.veidemann.db.initializer;

import no.nb.nna.veidemann.db.RethinkDbAdapter.TABLES;

public class Upgrade0_1To0_2 extends UpgradeDbBase {
    public Upgrade0_1To0_2(String dbName) {
        super(dbName);
    }

    final void upgrade() {
        conn.exec(r.table(TABLES.EXECUTIONS.name).indexCreate("jobId"));
        conn.exec(r.table(TABLES.EXECUTIONS.name).indexCreate("state"));
        conn.exec(r.table(TABLES.EXECUTIONS.name).indexCreate("seedId"));
        conn.exec(r.table(TABLES.EXECUTIONS.name).indexCreate("jobExecutionId"));

        conn.exec(r.tableCreate(TABLES.JOB_EXECUTIONS.name));
        conn.exec(r.table(TABLES.JOB_EXECUTIONS.name).indexCreate("startTime"));
        conn.exec(r.table(TABLES.JOB_EXECUTIONS.name).indexCreate("jobId"));
        conn.exec(r.table(TABLES.JOB_EXECUTIONS.name).indexCreate("state"));

        conn.exec(r.table(TABLES.EXECUTIONS.name).indexWait("jobId", "state", "seedId", "jobExecutionId"));
        conn.exec(r.table(TABLES.JOB_EXECUTIONS.name).indexWait("startTime", "jobId", "state"));
    }

    @Override
    String fromVersion() {
        return "0.1";
    }

    @Override
    String toVersion() {
        return "0.2";
    }
}
