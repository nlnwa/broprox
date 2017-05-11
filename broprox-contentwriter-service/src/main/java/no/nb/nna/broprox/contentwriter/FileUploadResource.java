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
package no.nb.nna.broprox.contentwriter;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;

import com.google.protobuf.util.JsonFormat;
import io.opentracing.SpanContext;
import io.opentracing.tag.Tags;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import no.nb.nna.broprox.commons.opentracing.OpenTracingJersey;
import no.nb.nna.broprox.commons.opentracing.OpenTracingWrapper;
import no.nb.nna.broprox.contentwriter.text.TextExtracter;
import no.nb.nna.broprox.contentwriter.warc.SingleWarcWriter;
import no.nb.nna.broprox.contentwriter.warc.WarcWriterPool;
import no.nb.nna.broprox.db.DbAdapter;
import no.nb.nna.broprox.model.MessagesProto.CrawlLog;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataParam;

import static io.netty.handler.codec.http.HttpConstants.CR;
import static io.netty.handler.codec.http.HttpConstants.LF;

/**
 *
 */
@Path("/")
public class FileUploadResource {

    static final byte[] CRLF = {CR, LF};

    @Context
    WarcWriterPool warcWriterPool;

    @Context
    DbAdapter db;

    @Context
    TextExtracter textExtracter;

    @Context
    HttpHeaders httpHeaders;

    public FileUploadResource() {
    }

    @Path("warcrecord")
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response postWarcRecord(
            @FormDataParam("logEntry") final String logEntryJson,
            @FormDataParam("headers") final InputStream headers,
            @FormDataParam("payload") final FormDataBodyPart payload) {

        SpanContext parentSpan = OpenTracingJersey.extractSpanHeaders(httpHeaders.getRequestHeaders());
        OpenTracingWrapper otw = new OpenTracingWrapper("FileUploadResource", Tags.SPAN_KIND_SERVER)
                .setParentSpan(parentSpan);

        try (WarcWriterPool.PooledWarcWriter pooledWarcWriter = warcWriterPool.borrow()) {
            return otw.call("postWarcRecord", new Callable<Response>() {
                @Override
                public Response call() throws Exception {
                    long size = 0L;

                    CrawlLog.Builder logEntryBuilder = CrawlLog.newBuilder();
                    JsonFormat.parser().merge(logEntryJson, logEntryBuilder);

                    SingleWarcWriter warcWriter = pooledWarcWriter.getWarcWriter();

                    URI ref = warcWriter.writeHeader(logEntryBuilder.build());
                    logEntryBuilder.setStorageRef(ref.toString());

                    CrawlLog logEntry = db.updateCrawlLog(logEntryBuilder.build());

                    if (headers != null) {
                        size += warcWriter.addPayload(headers);
                    }

                    if (payload != null) {
                        ForkJoinTask<Long> writeWarcJob = ForkJoinPool.commonPool().submit(new Callable<Long>() {
                            @Override
                            public Long call() throws Exception {
                                return warcWriter.addPayload(payload.getValueAs(InputStream.class));
                            }

                        });
                        ForkJoinTask<Void> extractTextJob = ForkJoinPool.commonPool().submit(new Callable<Void>() {
                            @Override
                            public Void call() throws Exception {
                                textExtracter.analyze(payload.getValueAs(InputStream.class), logEntry, db);
                                return null;
                            }

                        });

                        // If both headers and payload are present, add separator
                        if (headers != null) {
                            size += warcWriter.addPayload(CRLF);
                        }

                        size += writeWarcJob.get();
                        extractTextJob.get();
                    }

                    try {
                        warcWriter.closeRecord();
                    } catch (IOException ex) {
                        if (logEntry.getSize() != size) {
                            throw new WebApplicationException("Size doesn't match metadata. Expected " + logEntry
                                    .getSize()
                                    + ", but was " + size, Response.Status.NOT_ACCEPTABLE);
                        } else {
                            ex.printStackTrace();
                            throw new WebApplicationException(ex, Response.Status.NOT_ACCEPTABLE);
                        }
                    }

                    return Response.created(ref).build();
                }

            });
        } catch (Exception ex) {
            ex.printStackTrace();
            throw new WebApplicationException(ex.getMessage(), ex);
        }
    }

}
