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

import com.google.protobuf.util.Timestamps;
import no.nb.nna.broprox.db.ProtoUtils;
import no.nb.nna.broprox.model.ConfigProto;
import no.nb.nna.broprox.model.MessagesProto.CrawlExecutionStatus.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class LimitsCheck {

    private static final Logger LOG = LoggerFactory.getLogger(LimitsCheck.class);

    private LimitsCheck() {
    }

    /**
     * Checks that a URI which is about to be queued is within the limits required.
     *
     * @param limits the limits configuration
     * @param status the status object which might be updated by this method
     * @param qUri the URI to check
     * @return true if the submitted URI is within limits for queueing
     */
    public static boolean isQueueable(ConfigProto.CrawlLimitsConfig limits, StatusWrapper status,
            QueuedUriWrapper qUri) {

        if (limits.getDepth() > 0 && limits.getDepth() <= calculateDepth(qUri)) {
            LOG.debug("Maximum configured depth reached for: {}, skipping.", qUri.getSurt());
            status.incrementDocumentsDenied(1L);
            return false;
        }
        return true;
    }

    /**
     * Checks that should be run after fetching a URI to see if the limits for crawling are reached.
     *
     * @param frontier the frontier
     * @param limits the limits configuration
     * @param status the status object which might be updated by this method
     * @param qUri the URI to check
     * @return true if crawl should be stopped
     */
    public static boolean isLimitReached(Frontier frontier, ConfigProto.CrawlLimitsConfig limits, StatusWrapper status,
            QueuedUriWrapper qUri) {

        if (limits.getMaxBytes() > 0 && status.getBytesCrawled() > limits.getMaxBytes()) {
            switch (status.getState()) {
                case CREATED:
                case FETCHING:
                case SLEEPING:
                case UNDEFINED:
                case UNRECOGNIZED:
                    status.setEndState(State.ABORTED_SIZE);
                    status.incrementDocumentsDenied(frontier.getDb().deleteQueuedUrisForExecution(status.getId()));
                    frontier.getHarvesterClient().cleanupExecution(status.getId());
            }
            return true;
        }

        if (limits.getMaxDurationS() > 0
                && Timestamps.between(status.getStartTime(), ProtoUtils.getNowTs()).getSeconds() > limits
                .getMaxDurationS()) {

            switch (status.getState()) {
                case CREATED:
                case FETCHING:
                case SLEEPING:
                case UNDEFINED:
                case UNRECOGNIZED:
                    status.setEndState(State.ABORTED_TIMEOUT);
                    status.incrementDocumentsDenied(frontier.getDb().deleteQueuedUrisForExecution(status.getId()));
                    frontier.getHarvesterClient().cleanupExecution(status.getId());
            }
            return true;
        }

        return false;
    }

    private static int calculateDepth(QueuedUriWrapper qUri) {
        return qUri.getDiscoveryPath().length();
    }

}
