/*
 * Copyright 2019-present HiveMQ GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hivemq.configuration.entity.listener;

import com.hivemq.extension.sdk.api.annotations.NotNull;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Dominik Obermaier
 */
@XmlRootElement(name = "websocket-listener")
@XmlAccessorType(XmlAccessType.NONE)
@SuppressWarnings({"FieldMayBeFinal", "FieldCanBeLocal"})
public class WebsocketListenerEntity extends ListenerEntity {

    @XmlElement(name = "path", required = true)
    private @NotNull String path = "/mqtt";

    @XmlElementWrapper(name = "subprotocols")
    @XmlElement(name = "subprotocol")
    private @NotNull List<String> subprotocols = defaultProtocols();

    @XmlElement(name = "allow-extensions", defaultValue = "false")
    private @NotNull Boolean allowExtensions = false;

    public @NotNull String getPath() {
        return path;
    }

    public @NotNull List<String> getSubprotocols() {
        return subprotocols;
    }

    public boolean isAllowExtensions() {
        return allowExtensions;
    }

    private @NotNull List<String> defaultProtocols() {
        final List<String> protocols = new ArrayList<>();

        protocols.add("mqttv3.1");
        protocols.add("mqtt");
        return protocols;
    }
}
