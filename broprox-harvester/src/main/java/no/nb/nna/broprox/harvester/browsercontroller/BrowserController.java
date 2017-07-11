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
package no.nb.nna.broprox.harvester.browsercontroller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import io.opentracing.tag.Tags;
import no.nb.nna.broprox.api.ControllerProto;
import no.nb.nna.broprox.chrome.client.ChromeDebugProtocol;
import no.nb.nna.broprox.chrome.client.Session;
import no.nb.nna.broprox.commons.opentracing.OpenTracingWrapper;
import no.nb.nna.broprox.db.DbAdapter;
import no.nb.nna.broprox.model.ConfigProto.BrowserScript;
import no.nb.nna.broprox.model.ConfigProto.CrawlConfig;
import no.nb.nna.broprox.model.MessagesProto.QueuedUri;
import no.nb.nna.broprox.commons.BroproxHeaderConstants;
import no.nb.nna.broprox.db.ProtoUtils;
import no.nb.nna.broprox.harvester.OpenTracingSpans;
import no.nb.nna.broprox.harvester.proxy.RobotsServiceClient;
import no.nb.nna.broprox.model.MessagesProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 *
 */
public class BrowserController implements AutoCloseable, BroproxHeaderConstants {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserController.class);

    private final ChromeDebugProtocol chrome;

    private final DbAdapter db;

    private final Map<String, BrowserScript> scriptCache = new HashMap<>();

    private final RobotsServiceClient robotsServiceClient;

    public BrowserController(String chromeHost, int chromePort, DbAdapter db,
            final RobotsServiceClient robotsServiceClient) throws IOException {

        this.chrome = new ChromeDebugProtocol(chromeHost, chromePort);
        this.db = db;
        this.robotsServiceClient = robotsServiceClient;
    }

    public List<QueuedUri> render(QueuedUri queuedUri, CrawlConfig config)
            throws ExecutionException, InterruptedException, IOException, TimeoutException {
        List<QueuedUri> outlinks;

        MDC.put("eid", queuedUri.getExecutionId());
        MDC.put("uri", queuedUri.getUri());

        OpenTracingWrapper otw = new OpenTracingWrapper("BrowserController", Tags.SPAN_KIND_CLIENT)
                .setParentSpan(OpenTracingSpans.get(queuedUri.getExecutionId()));

        // Check robots.txt
        if (robotsServiceClient.isAllowed(queuedUri, config)) {

            try (Session session = chrome.newSession(
                    config.getBrowserConfig().getWindowWidth(),
                    config.getBrowserConfig().getWindowHeight())) {

                LOG.debug("Browser session created");

                CompletableFuture.allOf(
                        session.debugger.enable(),
                        session.page.enable(),
                        session.runtime.enable(),
                        session.network.enable(null, null),
                        session.network.setCacheDisabled(true),
                        session.runtime
                                .evaluate("navigator.userAgent;", null, false, false, null, false, false, false, false)
                                .thenAccept(e -> {
                                    session.network.setUserAgentOverride(((String) e.result.value)
                                            .replace("HeadlessChrome", session.version()));
                                }),
                        session.debugger
                                .setBreakpointByUrl(1, null, "https?://www.google-analytics.com/analytics.js", null, null),
                        session.debugger
                                .setBreakpointByUrl(1, null, "https?://www.google-analytics.com/ga.js", null, null),
                        session.page.setControlNavigations(Boolean.TRUE)
                ).get(config.getBrowserConfig().getPageLoadTimeoutMs(), MILLISECONDS);

                session.debugger.onPaused(p -> {
                    String scriptId = p.callFrames.get(0).location.scriptId;
                    System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>> SCRIPT BLE PAUSET: " + scriptId);
                    session.debugger.setScriptSource(scriptId, "console.log(\"google analytics is no more!\");", null);
                    session.debugger.resume();
                    System.out.println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>> SCRIPT RESUMED: " + scriptId);
                });

                session.network.onLoadingFailed(f -> {
                    MDC.put("eid", queuedUri.getExecutionId());
                    MDC.put("uri", queuedUri.getUri());
                    LOG.error(
                            "Failed fetching page: Error '{}', Blocked reason '{}', Resource type: '{}', Canceled: {}",
                            f.errorText, f.blockedReason, f.type, f.canceled);
                    MDC.clear();
                    throw new RuntimeException("Failed fetching page " + f.errorText);
                });

                LOG.debug("Browser session configured");

                // set cookies
                CompletableFuture.allOf(queuedUri.getCookiesList().stream()
                        .map(c -> session.network
                        .setCookie(queuedUri.getUri(), c.getName(), c.getValue(), c.getDomain(),
                                c.getPath(), c.getSecure(), c.getHttpOnly(), c.getSameSite(), c.getExpires()))
                        .collect(Collectors.toList()).toArray(new CompletableFuture[]{}))
                        .get(config.getBrowserConfig().getPageLoadTimeoutMs(), MILLISECONDS);

                LOG.debug("Browser cookies initialized");

                PageExecution pex = new PageExecution(queuedUri, session, config.getBrowserConfig()
                        .getPageLoadTimeoutMs(), db, config.getBrowserConfig().getSleepAfterPageloadMs());

                LOG.info("Navigate to page");

                otw.run("navigatePage", pex::navigatePage);
                if (config.getExtra().getCreateSnapshot()) {
                    LOG.debug("Save screenshot");
                    otw.run("saveScreenshot", pex::saveScreenshot);
                }

//                System.out.println("LINKS >>>>>>");
//                for (PageDomain.FrameResource fs : session.page.getResourceTree().get().frameTree.resources) {
//                    System.out.println("T: " + fs);
//                    if ("Script".equals(fs.type)) {
//                        System.out.println(">: " + fs.toString());
//                    }
//                }
//                System.out.println("<<<<<<");
                LOG.debug("Extract outlinks");

                List<BrowserScript> scripts = getScripts(config);
                outlinks = otw.map("extractOutlinks", pex::extractOutlinks, scripts);

                pex.getDocumentUrl();
                pex.scrollToTop();
            }
        } else {
            LOG.info("Precluded by robots.txt");

            // Precluded by robots.txt
            if (db != null) {
                MessagesProto.CrawlLog crawlLog = MessagesProto.CrawlLog.newBuilder()
                        .setRequestedUri(queuedUri.getUri())
                        .setSurt(queuedUri.getSurt())
                        .setRecordType("response")
                        .setStatusCode(-9998)
                        .setFetchTimeStamp(ProtoUtils.getNowTs())
                        .build();
                db.addCrawlLog(crawlLog);
            }

            outlinks = Collections.EMPTY_LIST;
        }

        MDC.clear();
        return outlinks;

    }

    private List<BrowserScript> getScripts(CrawlConfig config) {
        List<BrowserScript> scripts = new ArrayList<>();
        for (String scriptId : config.getBrowserConfig().getScriptIdList()) {
            BrowserScript script = scriptCache.get(scriptId);
            if (script == null) {
                ControllerProto.BrowserScriptListRequest req = ControllerProto.BrowserScriptListRequest.newBuilder()
                        .setId(scriptId)
                        .build();
                script = db.listBrowserScripts(req).getValue(0);
                scriptCache.put(scriptId, script);
            }
            scripts.add(script);
        }
        if (config.getBrowserConfig().hasScriptSelector()) {
            ControllerProto.BrowserScriptListRequest req = ControllerProto.BrowserScriptListRequest.newBuilder()
                    .setSelector(config.getBrowserConfig().getScriptSelector())
                    .build();
            for (BrowserScript script : db.listBrowserScripts(req).getValueList()) {
                if (!scriptCache.containsKey(script.getId())) {
                    scriptCache.put(script.getId(), script);
                }
                scripts.add(script);
            }
        }
        return scripts;
    }

    @Override
    public void close() {
        chrome.close();
    }

}
