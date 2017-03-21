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
package no.nb.nna.broprox.harvester.proxy;

import java.io.File;
import java.io.IOException;

import no.nb.nna.broprox.db.DbAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.mitm.CertificateSniffingMitmManager;
import org.littleshoot.proxy.mitm.RootCertificateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Recording proxy.
 */
public class RecordingProxy implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(RecordingProxy.class);

    private final HttpProxyServer server;

    private final AlreadyCrawledCache cache;

    /**
     * Construct a new Recording Proxy.
     * <p>
     * @param workDir a directory for storing temporary files
     * @param port the port to listen to
     * @throws RootCertificateException is thrown if there where problems with the root certificate
     * @throws IOException is thrown if certificate directory could not be created
     */
    public RecordingProxy(File workDir, int port, DbAdapter db, final ContentWriterClient contentWriterClient)
            throws RootCertificateException, IOException {
        LOG.info("Starting recording proxy listening on port {}.", port);

        File certificateDir = new File(workDir, "certificates");

        cache = new AlreadyCrawledCache();

        server = DefaultHttpProxyServer.bootstrap()
                .withAllowLocalOnly(false)
                .withPort(port)
                .withTransparent(true)
                .withManInTheMiddle(new CertificateSniffingMitmManager(new SelfSignedAuthority(certificateDir)))
                .withFiltersSource(new RecorderFilterSourceAdapter(db, contentWriterClient, cache))
                .start();
    }

    @Override
    public void close() {
        LOG.info("Shutting down recording proxy.");
        server.stop();
    }

    public void cleanCache(String executionId) {
        cache.cleanExecution(executionId);
    }
}
