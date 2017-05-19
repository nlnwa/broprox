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

import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;

import com.google.common.reflect.TypeToken;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import no.nb.nna.broprox.db.ProtoUtils;
import no.nb.nna.broprox.commons.BroproxHeaderConstants;
import no.nb.nna.broprox.model.MessagesProto.QueuedUri.IdSeq;
import org.cache2k.Cache;
import org.cache2k.Cache2kBuilder;
import org.cache2k.CacheEntry;
import org.cache2k.expiry.ExpiryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public class AlreadyCrawledCache {

    private static final Logger LOG = LoggerFactory.getLogger(AlreadyCrawledCache.class);

    private final Cache<CacheKey, FullHttpResponse> cache;

    private final ByteBuf headerPayloadSep = Unpooled.wrappedBuffer(new byte[]{'\r', '\n'}).asReadOnly();

    public AlreadyCrawledCache() {
        cache = new Cache2kBuilder<CacheKey, FullHttpResponse>() {
        }
                .name("embedsCache")
                .entryCapacity(10000)
                .expiryPolicy(new ExpiryPolicy<CacheKey, FullHttpResponse>() {
                    @Override
                    public long calculateExpiryTime(CacheKey key, FullHttpResponse value,
                            long loadTime, CacheEntry<CacheKey, FullHttpResponse> oldEntry) {
                        if (value == null || value.content().readableBytes() > 256 * 1024) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("Won't cache {} content too big", key);
                            }
                            return NO_CACHE;
                        }
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("Caching {}", key);
                        }
                        return ETERNAL;
                    }

                })
                .build();
    }

    public FullHttpResponse get(String uri, String exIdHeader, String allExIdHeader) {
        if (exIdHeader == null || BroproxHeaderConstants.MANUAL_EXID.equals(exIdHeader)) {
            return null;
        }

        CacheKey key = new CacheKey(uri, exIdHeader);
        FullHttpResponse cacheValue = cache.peek(key);

        if (cacheValue == null) {
            List<IdSeq> allExId = ProtoUtils.jsonListToProto(allExIdHeader, IdSeq.class);
            for (IdSeq eId : allExId) {
                if (!exIdHeader.equals(eId.getId())) {
                    CacheKey altKey = new CacheKey(uri, (String) eId.getId());
                    cacheValue = cache.peek(altKey);
                    if (cacheValue != null) {
                        cache.put(key, cacheValue);
                        break;
                    }
                }
            }
        }

        if (cacheValue != null) {
            return cacheValue.retainedDuplicate();
        } else {
            return null;
        }
    }

    public void put(HttpVersion httpVersion,
            HttpResponseStatus status,
            String uri,
            String exIdHeader,
            ByteBuf headers,
            ByteBuf payload) {

        if (exIdHeader == null) {
            return;
        }

        ByteBuf data = Unpooled.copiedBuffer(headers, headerPayloadSep, payload).asReadOnly();
        FullHttpResponse httpResponse = new DefaultFullHttpResponse(httpVersion, status, data);

        CacheKey key = new CacheKey(uri, exIdHeader);
        cache.put(key, httpResponse);
    }

    public void cleanExecution(String executionId) {
        for (CacheKey key : cache.keys()) {
            if (key.executionId.equals(executionId)) {
                FullHttpResponse removed = cache.peekAndRemove(key);
                if (removed != null) {
                    removed.release();
                }
            }
        }
    }

    public static final class CacheKey {

        private final String uri;

        private final String executionId;

        public CacheKey(final String uri, final String executionId) {
            this.uri = uri;
            this.executionId = executionId;
        }

        public String getUri() {
            return uri;
        }

        public String getExecutionId() {
            return executionId;
        }

        @Override
        public int hashCode() {
            int hash = 5;
            hash = 71 * hash + Objects.hashCode(this.uri);
            hash = 71 * hash + Objects.hashCode(this.executionId);
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final CacheKey other = (CacheKey) obj;
            if (!Objects.equals(this.uri, other.uri)) {
                return false;
            }
            if (!Objects.equals(this.executionId, other.executionId)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "CacheKey{" + "uri=" + uri + ", executionId=" + executionId + '}';
        }

    }
}
