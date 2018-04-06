/*
 * Copyright 2017 National Library of Norway.
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

import no.nb.nna.veidemann.api.ConfigProto;
import no.nb.nna.veidemann.api.ConfigProto.CrawlJob;
import no.nb.nna.veidemann.api.ConfigProto.CrawlLimitsConfig;
import no.nb.nna.veidemann.api.ControllerProto;
import no.nb.nna.veidemann.api.ReportProto.CrawlLogListReply;
import no.nb.nna.veidemann.api.ReportProto.CrawlLogListRequest;
import no.nb.nna.veidemann.api.ReportProto.PageLogListReply;
import no.nb.nna.veidemann.api.ReportProto.PageLogListRequest;
import no.nb.nna.veidemann.commons.VeidemannHeaderConstants;
import org.junit.Test;

import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 *
 */
public class SimpleCrawlIT extends CrawlTestBase implements VeidemannHeaderConstants {

    @Test
    public void testHarvest() throws InterruptedException, ExecutionException {
        CrawlJob job = controllerClient.listCrawlJobs(ControllerProto.ListRequest.newBuilder()
                .setName("unscheduled").build())
                .getValue(0);

        CrawlLimitsConfig limits = job.getLimits().toBuilder().setDepth(10).setMaxDurationS(300).build();
        job = job.toBuilder().setLimits(limits).build();
        job = controllerClient.saveCrawlJob(job);
        String jobId = job.getId();

        ConfigProto.CrawlEntity entity = ConfigProto.CrawlEntity.newBuilder().setMeta(ConfigProto.Meta.newBuilder()
                .setName("Test entity 1")).build();
        entity = controllerClient.saveEntity(entity);
        ConfigProto.Seed seed = ConfigProto.Seed.newBuilder()
                .setMeta(ConfigProto.Meta.newBuilder().setName("http://a1.com"))
//                .setMeta(ConfigProto.Meta.newBuilder().setName("https://www.nb.no"))
                .setEntityId(entity.getId())
                .addJobId(jobId)
                .build();
        seed = controllerClient.saveSeed(seed);

        ControllerProto.RunCrawlRequest request = ControllerProto.RunCrawlRequest.newBuilder()
                .setJobId(jobId)
                .setSeedId(seed.getId())
                .build();

        System.out.println("Job execution result:\n" + JobCompletion.executeJob(statusClient, request).get());

        // TODO: check these values instead of just printing
        System.out.println("WARC RECORDS");
        WarcInspector.getWarcFiles().getRecordStream().forEach(r -> System.out.println(r.header.warcTypeStr + " -- "
                + r.header.warcTargetUriStr + ", ip: " + r.header.warcIpAddress));

        CrawlLogListReply crawlLog = db.listCrawlLogs(CrawlLogListRequest.newBuilder().setPageSize(500).build());
        PageLogListReply pageLog = db.listPageLogs(PageLogListRequest.getDefaultInstance());

        System.out.println("\nPAGE LOG");
        pageLog.getValueList().forEach(p -> {
            System.out.println(p.getUri() + ", eid: " + p.getExecutionId());
            p.getResourceList().forEach(r -> System.out.println("  - " + r.getUri() + ", cache: " + r.getFromCache()));
        });

        // The goal is to get as low as 25 when we cache 404, 302, etc
        // assertThat(WarcInspector.getWarcFiles().getRecordCount()).isEqualTo(25L);
        assertThat(WarcInspector.getWarcFiles().getRecordCount()).isEqualTo(40L);

        // TODO: check these values instead of just printing
        System.out.println("\nCRAWL LOG");
        crawlLog.getValueList().forEach(r -> System.out.println(r.getRequestedUri() + " -- " + r.getStatusCode()
                + " -- " + r.getContentType() + " -- " + r.getRecordType() + " -- " + r.getReferrer() + ", ip: " + r.getIpAddress()));

        // The goal is to get as low as 14 when we cache 404, 302, etc
        // assertThat(crawlLog.getCount()).isEqualTo(14L);
        assertThat(crawlLog.getCount()).isEqualTo(20L);
        assertThat(pageLog.getCount()).isEqualTo(6L);

        new CrawlExecutionValidator(db).validate();

        System.out.println("Job execution result:\n" + JobCompletion.executeJob(statusClient, request).get());
        crawlLog = db.listCrawlLogs(CrawlLogListRequest.newBuilder().setPageSize(500).build());
        pageLog = db.listPageLogs(PageLogListRequest.getDefaultInstance());

        System.out.println("\nPAGE LOG");
        pageLog.getValueList().forEach(p -> {
            System.out.println(p.getUri() + ", eid: " + p.getExecutionId());
            p.getResourceList().forEach(r -> System.out.println("  - " + r.getUri() + ", cache: " + r.getFromCache()));
        });

        // TODO: check these values instead of just printing
        System.out.println("---------------");
        crawlLog.getValueList().forEach(r -> System.out.println(r.getRequestedUri() + " -- " + r.getStatusCode()
                + " -- " + r.getContentType() + " -- " + r.getRecordType() + " -- " + r.getReferrer()));

        // The goal is to get as low as 24 when we cache 404, 302, etc
        // assertThat(crawlLog.getCount()).isEqualTo(24);
        assertThat(crawlLog.getCount()).isEqualTo(40);

        new CrawlExecutionValidator(db).validate();
    }

}
