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
package no.nb.nna.veidemann.integrationtests;

import no.nb.nna.veidemann.api.MessagesProto.CrawlLog;
import no.nb.nna.veidemann.api.ReportProto.CrawlLogListReply;
import no.nb.nna.veidemann.api.ReportProto.CrawlLogListRequest;
import no.nb.nna.veidemann.commons.db.DbAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class CrawlLogHelper {
    final CrawlLogListReply crawlLogListReply;
    final Map<String, List<CrawlLog>> crawlLogsByType = new HashMap();
    final Map<String, List<CrawlLog>> crawlLogsByEid = new HashMap();
    final Map<String, List<CrawlLog>> crawlLogsByJobEid = new HashMap();
    int reportedCount;

    public CrawlLogHelper(DbAdapter db) {
        crawlLogListReply = db.listCrawlLogs(CrawlLogListRequest.newBuilder().setPageSize(500).build());
        reportedCount = (int) crawlLogListReply.getCount();
        crawlLogListReply.getValueList().forEach(c -> addCrawlLog(c));
        checkCount();
    }

    private void addCrawlLog(CrawlLog crawlLog) {
        String type;
        if (crawlLog.getRequestedUri().startsWith("dns")) {
            type = "dns";
        } else {
            type = crawlLog.getRecordType();
            assertThat(crawlLog.getExecutionId())
                    .as("Execution id should not be empty for crawl log entry %s", crawlLog)
                    .isNotEmpty();
            assertThat(crawlLog.getJobExecutionId())
                    .as("Job execution id should not be empty for crawl log entry %s", crawlLog)
                    .isNotEmpty();
        }

        List<CrawlLog> typeList = crawlLogsByType.get(type);
        if (typeList == null) {
            typeList = new ArrayList<>();
            crawlLogsByType.put(type, typeList);
        }
        typeList.add(crawlLog);

        List<CrawlLog> eidList = crawlLogsByEid.get(crawlLog.getExecutionId());
        if (eidList == null) {
            eidList = new ArrayList<>();
            crawlLogsByEid.put(crawlLog.getExecutionId(), eidList);
        }
        eidList.add(crawlLog);

        List<CrawlLog> jobEidList = crawlLogsByJobEid.get(crawlLog.getJobExecutionId());
        if (jobEidList == null) {
            jobEidList = new ArrayList<>();
            crawlLogsByJobEid.put(crawlLog.getJobExecutionId(), jobEidList);
        }
        jobEidList.add(crawlLog);
    }

    public int getTypeCount(String type) {
        return getCrawlLogsByType(type).size();
    }

    private void checkCount() {
        assertThat(getCrawlLog().size()).isEqualTo(reportedCount);
    }

    public List<CrawlLog> getCrawlLog() {
        return crawlLogListReply.getValueList();
    }

    public List<CrawlLog> getCrawlLogsByType(String type) {
        return crawlLogsByType.getOrDefault(type, Collections.emptyList());
    }

    public List<CrawlLog> getCrawlLogsByExecutionId(String eid) {
        return crawlLogsByEid.getOrDefault(eid, Collections.emptyList());
    }

    public List<CrawlLog> getCrawlLogsByJobExecutionId(String jobEid) {
        return crawlLogsByJobEid.getOrDefault(jobEid, Collections.emptyList());
    }

    CrawlLog getCrawlLogEntry(String warcId) {
        for (CrawlLog c : getCrawlLog()) {
            if (c.getWarcId().equals(warcId)) {
                return c;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer();
        crawlLogsByType.forEach((k, v) -> {
            sb.append(k).append("(").append(v.size()).append(") {\n");
            v.forEach(crawlLog -> sb.append("  ").append(crawlLog.getRequestedUri()).append("\n"));
            sb.append("}\n");
        });
        return sb.toString();
    }
}
