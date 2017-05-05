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
package no.nb.nna.broprox.controller;

import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import no.nb.nna.broprox.db.DbAdapter;
import no.nb.nna.broprox.api.ControllerGrpc;
import no.nb.nna.broprox.model.ConfigProto.BrowserScript;
import no.nb.nna.broprox.model.ConfigProto.CrawlEntity;
import no.nb.nna.broprox.api.ControllerProto.BrowserScriptListReply;
import no.nb.nna.broprox.api.ControllerProto.BrowserScriptListRequest;
import no.nb.nna.broprox.api.ControllerProto.CrawlEntityListReply;
import no.nb.nna.broprox.api.ControllerProto.ListRequest;

/**
 *
 */
public class ControllerService extends ControllerGrpc.ControllerImplBase {

    private final DbAdapter db;

    public ControllerService(DbAdapter db) {
        this.db = db;
    }

    @Override
    public void saveEntity(CrawlEntity request, StreamObserver<CrawlEntity> respObserver) {
        try {
            respObserver.onNext(db.saveCrawlEntity(request));
            respObserver.onCompleted();
        } catch (Exception e) {
            respObserver.onError(e);
        }
    }

    @Override
    public void listCrawlEntities(ListRequest request, StreamObserver<CrawlEntityListReply> respObserver) {
        try {
            respObserver.onNext(db.listCrawlEntities(request));
            respObserver.onCompleted();
        } catch (Exception e) {
            respObserver.onError(e);
        }
    }

    @Override
    public void deleteEntity(CrawlEntity request, StreamObserver<Empty> respObserver) {
        try {
            respObserver.onNext(db.deleteCrawlEntity(request));
            respObserver.onCompleted();
        } catch (Exception e) {
            respObserver.onError(e);
        }
    }

    @Override
    public void saveBrowserScript(BrowserScript request, StreamObserver<BrowserScript> respObserver) {
        try {
            respObserver.onNext(db.saveBrowserScript(request));
            respObserver.onCompleted();
        } catch (Exception e) {
            respObserver.onError(e);
        }
    }

    @Override
    public void listBrowserScripts(BrowserScriptListRequest request, StreamObserver<BrowserScriptListReply> respObserver) {
        try {
            BrowserScriptListReply.Builder builder = BrowserScriptListReply.newBuilder();
            db.getBrowserScripts(request.getType()).forEach(bs -> builder.addValue(bs));
            respObserver.onNext(builder.build());
            respObserver.onCompleted();
        } catch (Exception e) {
            respObserver.onError(e);
        }
    }

}
