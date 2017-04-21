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
package no.nb.nna.broprox.frontier.settings;

import no.nb.nna.broprox.commons.settings.CommonSettings;

/**
 * Configuration settings for Broprox.
 */
public class Settings extends CommonSettings {

    private int apiPort;

    private String workDir;

    private String harvesterHost;

    private int harvesterPort;

    public int getApiPort() {
        return apiPort;
    }

    public void setApiPort(int apiPort) {
        this.apiPort = apiPort;
    }

    public String getWorkDir() {
        return workDir;
    }

    public void setWorkDir(String workDir) {
        this.workDir = workDir;
    }

    public String getHarvesterHost() {
        return harvesterHost;
    }

    public void setHarvesterHost(String harvesterHost) {
        this.harvesterHost = harvesterHost;
    }

    public int getHarvesterPort() {
        return harvesterPort;
    }

    public void setHarvesterPort(int harvesterPort) {
        this.harvesterPort = harvesterPort;
    }

}