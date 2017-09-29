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
package no.nb.nna.broprox.frontier.worker;

import java.net.URISyntaxException;
import java.util.concurrent.TimeUnit;

import no.nb.nna.broprox.commons.client.DnsServiceClient;
import no.nb.nna.broprox.commons.client.RobotsServiceClient;
import no.nb.nna.broprox.commons.opentracing.OpenTracingWrapper;
import no.nb.nna.broprox.db.ProtoUtils;
import no.nb.nna.broprox.db.RethinkDbAdapter;
import no.nb.nna.broprox.model.ConfigProto.CrawlJob;
import no.nb.nna.broprox.model.ConfigProto.Seed;
import no.nb.nna.broprox.model.MessagesProto;
import no.nb.nna.broprox.model.MessagesProto.CrawlExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class Frontier implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Frontier.class);

    private final RethinkDbAdapter db;

    private final HarvesterClient harvesterClient;

    private final RobotsServiceClient robotsServiceClient;

    private final DnsServiceClient dnsServiceClient;

    private final QueueWorker queueWorker;

    public Frontier(RethinkDbAdapter db, HarvesterClient harvesterClient, RobotsServiceClient robotsServiceClient, DnsServiceClient dnsServiceClient) {
        this.db = db;
        this.harvesterClient = harvesterClient;
        this.robotsServiceClient = robotsServiceClient;
        this.dnsServiceClient = dnsServiceClient;
        this.queueWorker = new QueueWorker(this, 5);
    }

    public CrawlExecutionStatus newExecution(final CrawlJob job, final Seed seed) {
        OpenTracingWrapper otw = new OpenTracingWrapper("Frontier");
        return otw.map("scheduleSeed", this::scheduleSeed, job, seed);
    }

    public CrawlExecutionStatus scheduleSeed(final CrawlJob job, final Seed seed) {
        // Create execution
        StatusWrapper status = StatusWrapper.getStatusWrapper(CrawlExecutionStatus.newBuilder()
                .setJobId(job.getId())
                .setSeedId(seed.getId())
                .setState(CrawlExecutionStatus.State.CREATED)
                .setScope(seed.getScope()));
        status.saveStatus(db);
        LOG.debug("New crawl execution: " + status.getId());

        String uri = seed.getMeta().getName();

        try {
            QueuedUriWrapper qUri = QueuedUriWrapper.getQueuedUriWrapper(this, uri);
            Preconditions
                    .checkPreconditions(this, job.getCrawlConfig(), status, qUri, 1L);
            LOG.debug("Seed '{}' added to queue" + qUri.getUri());
        } catch (URISyntaxException ex) {
            status.incrementDocumentsFailed()
                    .setEndState(CrawlExecutionStatus.State.FAILED)
                    .saveStatus(db);
        }

        return status.getCrawlExecutionStatus();
    }

    public RethinkDbAdapter getDb() {
        return db;
    }

    public HarvesterClient getHarvesterClient() {
        return harvesterClient;
    }

    public RobotsServiceClient getRobotsServiceClient() {
        return robotsServiceClient;
    }

    public DnsServiceClient getDnsServiceClient() {
        return dnsServiceClient;
    }

    /**
     * Write crawl log entry for uris failing preconditions.
     * <p>
     * Normally the crawl log is written by the harvester, but when preconditions fail a fetch will never be tried and
     * the crawl log must be written by the frontier.
     *
     * @param frontier the frontier
     * @param qUri the uri with failed precondition
     */
    public void writeLog(Frontier frontier, QueuedUriWrapper qUri) {
        writeLog(frontier, qUri, qUri.getError().getCode());
    }

    public void writeLog(Frontier frontier, QueuedUriWrapper qUri, int statusCode) {
        MessagesProto.CrawlLog crawlLog = MessagesProto.CrawlLog.newBuilder()
                .setRequestedUri(qUri.getUri())
                .setExecutionId(qUri.getExecutionId())
                .setDiscoveryPath(qUri.getDiscoveryPath())
                .setReferrer(qUri.getReferrer())
                .setSurt(qUri.getSurt())
                .setRecordType("response")
                .setStatusCode(statusCode)
                .setError(qUri.getError())
                .setRetries(qUri.getRetries())
                .setFetchTimeStamp(ProtoUtils.getNowTs())
                .build();
        frontier.getDb().saveCrawlLog(crawlLog);
    }

    @Override
    public void close() {
        try {
            queueWorker.shutdown();
            queueWorker.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            throw new RuntimeException(ex);
        }
    }

}
