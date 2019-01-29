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
package no.nb.nna.veidemann.harvester.proxy;

import com.google.protobuf.Duration;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.opentracing.ActiveSpan;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import no.nb.nna.veidemann.api.config.v1.ConfigRef;
import no.nb.nna.veidemann.api.config.v1.Kind;
import no.nb.nna.veidemann.api.contentwriter.v1.RecordType;
import no.nb.nna.veidemann.api.contentwriter.v1.WriteRequestMeta;
import no.nb.nna.veidemann.api.contentwriter.v1.WriteResponseMeta;
import no.nb.nna.veidemann.api.frontier.v1.CrawlLog;
import no.nb.nna.veidemann.commons.ExtraStatusCodes;
import no.nb.nna.veidemann.commons.VeidemannHeaderConstants;
import no.nb.nna.veidemann.commons.client.ContentWriterClient;
import no.nb.nna.veidemann.commons.client.ContentWriterClient.ContentWriterSession;
import no.nb.nna.veidemann.commons.db.DbAdapter;
import no.nb.nna.veidemann.commons.db.DbException;
import no.nb.nna.veidemann.commons.db.DbService;
import no.nb.nna.veidemann.db.ProtoUtils;
import no.nb.nna.veidemann.harvester.BrowserSessionRegistry;
import no.nb.nna.veidemann.harvester.browsercontroller.BrowserSession;
import no.nb.nna.veidemann.harvester.browsercontroller.CrawlLogRegistry;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.impl.ProxyUtils;
import org.netpreserve.commons.uri.Uri;
import org.netpreserve.commons.uri.UriConfigs;
import org.netpreserve.commons.uri.UriException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 */
public class RecorderFilter extends HttpFiltersAdapter implements VeidemannHeaderConstants {

    private static final Logger LOG = LoggerFactory.getLogger(RecorderFilter.class);

    private final int proxyId;

    private static final AtomicLong nextProxyRequestId = new AtomicLong();

    private final String uri;

    private CrawlLogRegistry.Entry crawlLogEntry;

    private final BrowserSessionRegistry sessionRegistry;

    private BrowserSession browserSession;

    private ContentCollector requestCollector;

    private ContentCollector responseCollector;

    private final DbAdapter db;

    private String jobExecutionId;

    private String executionId;

    private ConfigRef collectionRef;

    private Timestamp fetchTimeStamp;

    private InetSocketAddress resolvedRemoteAddress;

    private HttpResponseStatus httpResponseStatus;

    private CrawlLog.Builder crawlLog;

    private final ContentWriterClient contentWriterClient;

    private final DnsServiceHostResolver hostResolver;

    private ContentWriterSession contentWriterSession;

    private boolean foundInCache = false;

    private boolean finalResponseSent = false;

    public RecorderFilter(final int proxyId, final String uri, final HttpRequest originalRequest,
                          final ChannelHandlerContext ctx, final ContentWriterClient contentWriterClient,
                          final BrowserSessionRegistry sessionRegistry, final DnsServiceHostResolver hostResolver) {

        super(originalRequest, ctx);
        this.proxyId = proxyId;
        this.db = DbService.getInstance().getDbAdapter();
        this.uri = uri;

        this.contentWriterClient = contentWriterClient;
        this.requestCollector = new ContentCollector(0, RecordType.REQUEST, uri, db);
        this.responseCollector = new ContentCollector(1, RecordType.RESPONSE, uri, db);
        this.sessionRegistry = sessionRegistry;
        this.hostResolver = hostResolver;
        this.fetchTimeStamp = ProtoUtils.getNowTs();
    }

    private synchronized ContentWriterSession getContentWriterSession() {
        if (contentWriterSession == null) {
            contentWriterSession = contentWriterClient.createSession();
        }
        return contentWriterSession;
    }

    @Override
    public HttpResponse clientToProxyRequest(HttpObject httpObject) {
        try {
            if (httpObject instanceof HttpRequest) {
                HttpRequest request = (HttpRequest) httpObject;

                // Lookup browser session, but skip if it is a robots.txt request since that is not coming from browser.
                if (!uri.endsWith("robots.txt")) {
                    browserSession = sessionRegistry.get(proxyId);
                    crawlLogEntry = browserSession.getCrawlLogs().registerProxyRequest(nextProxyRequestId.getAndIncrement(), uri);
                    executionId = browserSession.getCrawlExecutionId();
                    jobExecutionId = browserSession.getJobExecutionId();
                    collectionRef = browserSession.getCollectionRef();
                } else {
                    executionId = request.headers().get(EXECUTION_ID);
                    jobExecutionId = request.headers().get(JOB_EXECUTION_ID);
                    collectionRef = ConfigRef.newBuilder()
                            .setKind(Kind.collection)
                            .setId(request.headers().get(COLLECTION_ID))
                            .build();
                }

                if (executionId == null || executionId.isEmpty()) {
                    LOG.error("Missing executionId for {}", uri);
                    finalResponseSent = true;
                    return ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY);
                }

                if (jobExecutionId == null || jobExecutionId.isEmpty()) {
                    LOG.error("Missing jobExecutionId for {}", uri);
                    finalResponseSent = true;
                    return ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY);
                }

                MDC.put("eid", executionId);
                MDC.put("uri", uri);

                LOG.debug("Proxy got request");

                Uri parsedUri;
                try {
                    parsedUri = UriConfigs.WHATWG.buildUri(uri);
                } catch (Exception e) {
                    crawlLog = buildCrawlLog()
                            .setError(ExtraStatusCodes.ILLEGAL_URI.toFetchError(e.getMessage()));
                    writeCrawlLog(crawlLog);
                    LOG.debug("URI parsing failed");
                    finalResponseSent = true;
                    return ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SERVICE_UNAVAILABLE);
                }

                try {
                    resolvedRemoteAddress = hostResolver.resolve(parsedUri.getHost(), parsedUri.getDecodedPort(), collectionRef);
                } catch (UnknownHostException e) {
                    crawlLog = buildCrawlLog()
                            .setError(ExtraStatusCodes.DOMAIN_LOOKUP_FAILED.toFetchError());
                    writeCrawlLog(crawlLog);
                    LOG.debug("DNS lookup failed");
                    finalResponseSent = true;
                    return ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SERVICE_UNAVAILABLE);
                }

                try (ActiveSpan span = GlobalTracer.get().makeActive(getRequestSpan())) {
                    // Fix headers before sending to final destination
                    request.headers().set("Accept-Encoding", "identity");

                    // Store request
                    requestCollector.setHeaders(ContentCollector.createRequestPreamble(request), request.headers(), getContentWriterSession());
                    LOG.debug("Proxy is sending request to final destination.");
                }
            } else if (httpObject instanceof HttpContent) {
                HttpContent request = (HttpContent) httpObject;
                requestCollector.addPayload(request.content(), getContentWriterSession());
            } else {
                LOG.debug("Got something else than http request: {}", httpObject);
            }
        } catch (Exception t) {
            String ipAddress = "";
            if (resolvedRemoteAddress != null) {
                ipAddress = resolvedRemoteAddress.getAddress().getHostAddress();
            }
            crawlLog = buildCrawlLog()
                    .setIpAddress(ipAddress)
                    .setError(ExtraStatusCodes.RUNTIME_EXCEPTION.toFetchError(t.toString()));
            writeCrawlLog(crawlLog);
            LOG.error("Error handling request", t);
            finalResponseSent = true;
            return ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SERVICE_UNAVAILABLE);
        }
        return null;
    }

    @Override
    public HttpObject proxyToClientResponse(HttpObject httpObject) {
        MDC.put("eid", executionId);
        MDC.put("uri", uri);
        if (finalResponseSent) {
            LOG.info("Already sent final response");
            return null;
        }

        if (browserSession != null && browserSession.isClosed()) {
            LOG.warn("Browser session was closed, aborting request");
            cancelContentWriterSession("Session was aborted");
            finalResponseSent = true;
            return ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY);
        }

        try {
            try (ActiveSpan span = GlobalTracer.get().makeActive(getResponseSpan())) {

                boolean handled = false;

                if (httpObject instanceof HttpResponse) {
                    try {
                        HttpResponse res = (HttpResponse) httpObject;
                        LOG.trace("Got response headers {}", res.status());

                        httpResponseStatus = res.status();
                        responseCollector.setHeaders(ContentCollector.createResponsePreamble(res), res.headers(), getContentWriterSession());
                        span.log("Got response headers");

                        crawlLog = buildCrawlLog()
                                .setStatusCode(httpResponseStatus.code())
                                .setContentType(res.headers().get(HttpHeaderNames.CONTENT_TYPE, ""));

                        if (res.headers().get("X-Cache-Lookup", "MISS").contains("HIT")) {
                            foundInCache = true;

                            LOG.debug("Found in cache");
                            span.log("Loaded from cache");
                        }

                        handled = true;
                        LOG.trace("Handled response headers");
                    } catch (Exception ex) {
                        LOG.error("Error handling response headers", ex);
                        span.log("Error handling response headers: " + ex.toString());
                        if (crawlLog != null) {
                            crawlLog.setError(ExtraStatusCodes.RUNTIME_EXCEPTION.toFetchError(ex.getMessage()));
                            writeCrawlLog(crawlLog);
                        }
                        finalResponseSent = true;
                        return ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY);
                    }
                }

                if (httpObject instanceof HttpContent) {
                    LOG.trace("Got response content");
                    if (foundInCache) {
                        handled = true;
                    } else {
                        try {
                            HttpContent res = (HttpContent) httpObject;
                            span.log("Got response content. Size: " + res.content().readableBytes());
                            responseCollector.addPayload(res.content(), getContentWriterSession());

                            handled = true;
                            LOG.trace("Handled response content");
                        } catch (Exception ex) {
                            LOG.error("Error handling response content", ex);
                            span.log("Error handling response content: " + ex.toString());
                            cancelContentWriterSession("Got error while writing response content");
                            if (crawlLog != null) {
                                crawlLog.setError(ExtraStatusCodes.RUNTIME_EXCEPTION.toFetchError(ex.getMessage()));
                                writeCrawlLog(crawlLog);
                            }
                            finalResponseSent = true;
                            return ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY);
                        }
                    }
                }

                if (ProxyUtils.isLastChunk(httpObject)) {
                    LOG.debug("Got last response chunk. Response status: {}", httpResponseStatus);
                    if (foundInCache) {
                        writeCrawlLog(crawlLog);
                        span.log("Last response chunk");
                        LOG.debug("Handled last response chunk");
                        cancelContentWriterSession("OK: Loaded from cache");
                    } else {
                        WriteResponseMeta.RecordMeta responseRecordMeta = null;
                        try {
                            Duration fetchDuration = Timestamps.between(fetchTimeStamp, ProtoUtils.getNowTs());

                            String ipAddress = resolvedRemoteAddress.getAddress().getHostAddress();

                            WriteRequestMeta meta = WriteRequestMeta.newBuilder()
                                    .setExecutionId(executionId)
                                    .setCollectionRef(collectionRef)
                                    .setFetchTimeStamp(fetchTimeStamp)
                                    .setTargetUri(uri)
                                    .setIpAddress(ipAddress)
                                    .putRecordMeta(requestCollector.getRecordNum(), requestCollector.getRecordMeta())
                                    .putRecordMeta(responseCollector.getRecordNum(), responseCollector.getRecordMeta())
                                    .build();

                            // Finish ContentWriter session
                            getContentWriterSession().sendMetadata(meta);

                            WriteResponseMeta writeResponse = getContentWriterSession().finish();
                            contentWriterSession = null;

                            // Write CrawlLog
                            responseRecordMeta = writeResponse.getRecordMetaOrDefault(1, null);
                            crawlLog.setIpAddress(ipAddress)
                                    .setWarcId(responseRecordMeta.getWarcId())
                                    .setStorageRef(responseRecordMeta.getStorageRef())
                                    .setRecordType(responseRecordMeta.getType().name().toLowerCase())
                                    .setBlockDigest(responseRecordMeta.getBlockDigest())
                                    .setPayloadDigest(responseRecordMeta.getPayloadDigest())
                                    .setFetchTimeMs(Durations.toMillis(fetchDuration))
                                    .setSize(responseCollector.getSize())
                                    .setWarcRefersTo(responseRecordMeta.getRevisitReferenceId())
                                    .setCollectionFinalName(responseRecordMeta.getCollectionFinalName());

                            writeCrawlLog(crawlLog);

                        } catch (Exception ex) {
                            LOG.error("Error writing response", ex);
                            span.log("Error writing response: " + ex.toString());
                            cancelContentWriterSession("Got error while writing response metadata");
                            crawlLog.setError(ExtraStatusCodes.RUNTIME_EXCEPTION.toFetchError(ex.toString()));
                            writeCrawlLog(crawlLog);
                            finalResponseSent = true;
                            return ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY);
                        }

                        span.log("Last response chunk");
                        LOG.debug("Handled last response chunk");
                    }
                }

                if (!handled) {
                    // If we get here, handling for the response type should be added
                    LOG.error("Got unknown response type '{}', this is a bug", httpObject.getClass());
                }
            }
        } catch (Exception ex) {
            LOG.error("Error handling response", ex);
            if (crawlLog != null) {
                crawlLog.setError(ExtraStatusCodes.RUNTIME_EXCEPTION.toFetchError(ex.getMessage()));
                writeCrawlLog(crawlLog);
                finalResponseSent = true;
                return ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY);
            }
        }
        return httpObject;
    }

    @Override
    public void proxyToServerResolutionSucceeded(String serverHostAndPort, InetSocketAddress resolvedRemoteAddress) {
        MDC.put("eid", executionId);
        MDC.put("uri", uri);
        LOG.debug("Resolved {} to {}", serverHostAndPort, resolvedRemoteAddress);
        this.resolvedRemoteAddress = resolvedRemoteAddress;
    }

    @Override
    public void proxyToServerResolutionFailed(String hostAndPort) {
        MDC.put("eid", executionId);
        MDC.put("uri", uri);

        CrawlLog.Builder crawlLog = buildCrawlLog()
                .setRecordType("response")
                .setStatusCode(ExtraStatusCodes.FAILED_DNS.getCode());
        writeCrawlLog(crawlLog);

        LOG.debug("DNS lookup failed for {}", hostAndPort);
    }

    @Override
    public void proxyToServerConnectionFailed() {
        MDC.put("eid", executionId);
        MDC.put("uri", uri);

        CrawlLog.Builder crawlLog = buildCrawlLog()
                .setRecordType("response")
                .setStatusCode(ExtraStatusCodes.CONNECT_FAILED.getCode());
        writeCrawlLog(crawlLog);

        LOG.info("Http connect failed");
    }

    @Override
    public void serverToProxyResponseTimedOut() {
        MDC.put("eid", executionId);
        MDC.put("uri", uri);

        CrawlLog.Builder crawlLog = buildCrawlLog()
                .setRecordType("response")
                .setStatusCode(ExtraStatusCodes.HTTP_TIMEOUT.getCode());
        writeCrawlLog(crawlLog);

        LOG.info("Http connect timed out");
    }

    private void writeCrawlLog(CrawlLog.Builder crawlLog) {
        if (uri.endsWith("robots.txt")) {
            crawlLog.setDiscoveryPath("P");
            try {
                db.saveCrawlLog(crawlLog.build());
            } catch (DbException e) {
                LOG.warn("Could not write crawl log for robots.txt entry", e);
            }
        } else if (browserSession != null) {
            crawlLogEntry.setCrawlLog(crawlLog);
        } else {
            LOG.error("Browser session missing");
        }
    }

    private CrawlLog.Builder buildCrawlLog() {
        Timestamp now = ProtoUtils.getNowTs();
        Duration fetchDuration = Timestamps.between(fetchTimeStamp, now);

        CrawlLog.Builder crawlLog = CrawlLog.newBuilder()
                .setTimeStamp(now)
                .setExecutionId(executionId)
                .setJobExecutionId(jobExecutionId)
                .setRequestedUri(uri)
                .setFetchTimeStamp(fetchTimeStamp)
                .setFetchTimeMs(Durations.toMillis(fetchDuration));

        try {
            Uri surtUri = UriConfigs.SURT_KEY.buildUri(uri);
            crawlLog.setSurt(surtUri.toString());
        } catch (UriException ex) {
            crawlLog.setError(ExtraStatusCodes.ILLEGAL_URI.toFetchError(ex.getMessage()));
        }

        return crawlLog;
    }

    private Span getRequestSpan() {
        return buildSpan("clientToProxyRequest");
    }

    private Span getResponseSpan() {
        return buildSpan("serverToProxyResponse");
    }

    private Span buildSpan(String operationName) {
        Tracer.SpanBuilder newSpan = GlobalTracer.get()
                .buildSpan(operationName)
                .withTag(Tags.COMPONENT.getKey(), "RecorderFilter")
                .withTag("executionId", executionId)
                .withTag("uri", uri);

        if (browserSession != null && browserSession.getUriRequests().getPageSpan() != null) {
            return newSpan.asChildOf(browserSession.getUriRequests().getPageSpan()).startManual();
        } else {
            return newSpan.ignoreActiveSpan().startManual();
        }
    }

    protected void cancelContentWriterSession(String msg) {
        if (contentWriterSession != null && contentWriterSession.isOpen()) {
            try {
                contentWriterSession.cancel(msg);
            } catch (Exception e) {
                LOG.info("Proxy got error while closing content writer session: {}", e.toString());
            }
        }
    }

    @Override
    protected void finalize() {
        cancelContentWriterSession("Session was not completed");
    }
}
