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

package com.hivemq.mqtt.handler.connect;

import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import com.hivemq.bootstrap.ClientConnection;
import com.hivemq.bootstrap.ClientConnectionContext;
import com.hivemq.bootstrap.ClientState;
import com.hivemq.bootstrap.UndefinedClientConnection;
import com.hivemq.bootstrap.netty.ChannelDependencies;
import com.hivemq.bootstrap.netty.ChannelHandlerNames;
import com.hivemq.configuration.service.FullConfigurationService;
import com.hivemq.configuration.service.InternalConfigurations;
import com.hivemq.configuration.service.entity.Listener;
import com.hivemq.extension.sdk.api.annotations.NotNull;
import com.hivemq.extension.sdk.api.annotations.Nullable;
import com.hivemq.extension.sdk.api.auth.parameter.TopicPermission;
import com.hivemq.extension.sdk.api.packets.auth.DefaultAuthorizationBehaviour;
import com.hivemq.extension.sdk.api.packets.auth.ModifiableDefaultPermissions;
import com.hivemq.extension.sdk.api.packets.disconnect.DisconnectReasonCode;
import com.hivemq.extension.sdk.api.packets.general.UserProperties;
import com.hivemq.extension.sdk.api.packets.publish.AckReasonCode;
import com.hivemq.extensions.auth.parameter.ModifiableClientSettingsImpl;
import com.hivemq.extensions.events.OnServerDisconnectEvent;
import com.hivemq.extensions.handler.IncomingPublishHandler;
import com.hivemq.extensions.handler.PluginAuthenticatorServiceImpl;
import com.hivemq.extensions.handler.PluginAuthorizerService;
import com.hivemq.extensions.handler.PluginAuthorizerServiceImpl.AuthorizeWillResultEvent;
import com.hivemq.extensions.handler.tasks.PublishAuthorizerResult;
import com.hivemq.extensions.packets.general.ModifiableDefaultPermissionsImpl;
import com.hivemq.extensions.services.auth.Authorizers;
import com.hivemq.extensions.services.builder.TopicPermissionBuilderImpl;
import com.hivemq.limitation.TopicAliasLimiterImpl;
import com.hivemq.logging.EventLog;
import com.hivemq.mqtt.handler.KeepAliveDisconnectHandler;
import com.hivemq.mqtt.handler.KeepAliveDisconnectService;
import com.hivemq.mqtt.handler.auth.AuthInProgressMessageHandler;
import com.hivemq.mqtt.handler.connack.MqttConnacker;
import com.hivemq.mqtt.handler.connack.MqttConnackerImpl;
import com.hivemq.mqtt.handler.disconnect.MqttServerDisconnectorImpl;
import com.hivemq.mqtt.handler.publish.DropOutgoingPublishesHandler;
import com.hivemq.mqtt.handler.publish.FlowControlHandler;
import com.hivemq.mqtt.handler.publish.OrderedTopicService;
import com.hivemq.mqtt.handler.publish.PublishFlowHandler;
import com.hivemq.mqtt.handler.publish.PublishFlushHandler;
import com.hivemq.mqtt.message.ProtocolVersion;
import com.hivemq.mqtt.message.QoS;
import com.hivemq.mqtt.message.connack.CONNACK;
import com.hivemq.mqtt.message.connack.Mqtt3ConnAckReturnCode;
import com.hivemq.mqtt.message.connect.CONNECT;
import com.hivemq.mqtt.message.connect.MqttWillPublish;
import com.hivemq.mqtt.message.disconnect.DISCONNECT;
import com.hivemq.mqtt.message.mqtt5.Mqtt5UserProperties;
import com.hivemq.mqtt.message.mqtt5.MqttUserProperty;
import com.hivemq.mqtt.message.reason.Mqtt5ConnAckReasonCode;
import com.hivemq.mqtt.message.reason.Mqtt5DisconnectReasonCode;
import com.hivemq.mqtt.message.subscribe.Topic;
import com.hivemq.mqtt.services.PublishPollService;
import com.hivemq.persistence.clientsession.ClientSessionPersistence;
import com.hivemq.persistence.clientsession.ClientSessionSubscriptionPersistence;
import com.hivemq.persistence.clientsession.SharedSubscriptionService;
import com.hivemq.persistence.connection.ConnectionPersistence;
import com.hivemq.persistence.connection.ConnectionPersistenceImpl;
import com.hivemq.persistence.qos.IncomingMessageFlowPersistence;
import com.hivemq.util.Checkpoints;
import com.hivemq.util.ReasonStrings;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateHandler;
import net.jodah.concurrentunit.Waiter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import util.CollectUserEventsHandler;
import util.DummyClientConnection;
import util.DummyHandler;
import util.TestConfigurationBootstrap;
import util.TestMqttDecoder;

import javax.inject.Provider;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.hivemq.configuration.service.InternalConfigurations.MQTT_CONNECTION_AUTH_CLEAR_PASSWORD;
import static com.hivemq.extension.sdk.api.auth.parameter.OverloadProtectionThrottlingLevel.NONE;
import static com.hivemq.mqtt.message.connack.CONNACK.KEEP_ALIVE_NOT_SET;
import static com.hivemq.mqtt.message.connect.Mqtt5CONNECT.SESSION_EXPIRY_MAX;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("NullabilityAnnotations")
public class ConnectHandlerTest {

    private EmbeddedChannel channel;

    @Mock
    private ClientSessionPersistence clientSessionPersistence;
    @Mock
    private EventLog eventLog;
    @Mock
    private ChannelDependencies channelDependencies;
    @Mock
    private Authorizers authorizers;
    @Mock
    private PluginAuthorizerService pluginAuthorizerService;
    @Mock
    private PluginAuthenticatorServiceImpl internalAuthServiceImpl;

    private FullConfigurationService configurationService;
    private MqttConnacker mqttConnacker;
    private ChannelHandlerContext ctx;
    private ConnectHandler handler;
    private ModifiableDefaultPermissions defaultPermissions;
    private MqttServerDisconnectorImpl serverDisconnector;
    private @NotNull ClientConnectionContext clientConnectionContext;
    private ConnectionPersistence connectionPersistence;

    @Before
    public void setUp() throws Exception {

        MockitoAnnotations.initMocks(this);
        when(clientSessionPersistence.isExistent(anyString())).thenReturn(false);
        when(clientSessionPersistence.clientConnected(anyString(),
                anyBoolean(),
                anyLong(),
                any(),
                isNull())).thenReturn(Futures.immediateFuture(null));

        channel = new EmbeddedChannel(new DummyHandler());
        clientConnectionContext =
                new UndefinedClientConnection(channel, mock(PublishFlushHandler.class), mock(Listener.class));
        channel.attr(ClientConnectionContext.CHANNEL_ATTRIBUTE_NAME).set(clientConnectionContext);
        clientConnectionContext.setQueueSizeMaximum(null);

        configurationService = new TestConfigurationBootstrap().getFullConfigurationService();
        InternalConfigurations.AUTH_DENY_UNAUTHENTICATED_CONNECTIONS.set(false);
        mqttConnacker = new MqttConnackerImpl(eventLog);
        serverDisconnector = new MqttServerDisconnectorImpl(eventLog);
        connectionPersistence = new ConnectionPersistenceImpl();

        when(channelDependencies.getAuthInProgressMessageHandler()).thenReturn(new AuthInProgressMessageHandler(
                mqttConnacker));

        defaultPermissions = new ModifiableDefaultPermissionsImpl();

        when(clientSessionPersistence.clientConnected(anyString(),
                anyBoolean(),
                anyLong(),
                any(MqttWillPublish.class),
                anyLong())).thenReturn(Futures.immediateFuture(null));
        when(clientSessionPersistence.clientConnected(anyString(),
                anyBoolean(),
                anyLong(),
                isNull(),
                anyLong())).thenReturn(Futures.immediateFuture(null));
        when(clientSessionPersistence.clientConnected(anyString(),
                anyBoolean(),
                anyLong(),
                isNull(),
                isNull())).thenReturn(Futures.immediateFuture(null));

        buildPipeline();
    }

    @After
    public void tearDown() {
        InternalConfigurations.AUTH_DENY_UNAUTHENTICATED_CONNECTIONS.set(true);
    }

    @Test
    public void test_connect_with_session_expiry_interval_zero() {

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withSessionExpiryInterval(0)
                .withClientIdentifier("1")
                .withUserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        assertTrue(channel.isOpen());
        channel.writeInbound(connect1);
        assertTrue(channel.isOpen());

        final Long expiry = ClientConnection.of(channel).getClientSessionExpiryInterval();

        assertNotNull(expiry);
        assertEquals(0, expiry.longValue());
    }

    @Test
    public void test_connect_with_keep_alive_zero() {

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withKeepAlive(0)
                .withClientIdentifier("1")
                .withUserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        assertTrue(channel.isOpen());
        channel.writeInbound(connect1);
        assertTrue(channel.isOpen());

        final Integer keepAlive = ClientConnection.of(channel).getConnectKeepAlive();

        boolean containsHandler = false;
        for (final Map.Entry<String, ChannelHandler> handler : channel.pipeline()) {
            if (handler.getValue() instanceof IdleStateHandler) {
                containsHandler = true;
                break;
            }
        }
        assertFalse(containsHandler);


        assertNotNull(keepAlive);
        assertEquals(0, keepAlive.longValue());
    }

    @Test
    public void test_connect_with_keep_alive_zero_not_allowed() {

        configurationService.mqttConfiguration().setKeepAliveMax(65535);
        configurationService.mqttConfiguration().setKeepAliveAllowZero(false);

        createHandler();

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withKeepAlive(0)
                .withClientIdentifier("1")
                .withUserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        assertTrue(channel.isOpen());
        channel.writeInbound(connect1);
        assertTrue(channel.isOpen());

        final Integer keepAlive = ClientConnection.of(channel).getConnectKeepAlive();
        final KeepAliveDisconnectHandler keepAliveDisconnectHandler =
                (KeepAliveDisconnectHandler) channel.pipeline().get(ChannelHandlerNames.MQTT_KEEPALIVE_IDLE_HANDLER);
        assertEquals(TimeUnit.SECONDS.toNanos(((Double) (65535 * 1.5)).intValue()),
                keepAliveDisconnectHandler.getReaderIdleTimeNanos());
        assertNotNull(keepAlive);

        assertNotNull(keepAlive);
        assertEquals(65535, keepAlive.longValue());
    }

    @Test
    public void test_connect_with_keep_alive_higher_than_server() {

        configurationService.mqttConfiguration().setKeepAliveMax(500);
        configurationService.mqttConfiguration().setKeepAliveAllowZero(false);

        createHandler();

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withKeepAlive(1000)
                .withClientIdentifier("1")
                .withUserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        final AtomicLong keepAliveFromCONNACK = new AtomicLong();
        final CountDownLatch connackLatch = new CountDownLatch(1);

        channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
                    throws Exception {
                if (msg instanceof CONNACK) {
                    keepAliveFromCONNACK.set(((CONNACK) msg).getServerKeepAlive());
                    connackLatch.countDown();
                }
                super.write(ctx, msg, promise);
            }
        });


        assertTrue(channel.isOpen());
        channel.writeInbound(connect1);
        assertTrue(channel.isOpen());
        final Integer keepAlive = ClientConnection.of(channel).getConnectKeepAlive();
        final KeepAliveDisconnectHandler keepAliveDisconnectHandler =
                (KeepAliveDisconnectHandler) channel.pipeline().get(ChannelHandlerNames.MQTT_KEEPALIVE_IDLE_HANDLER);
        assertEquals((long) (500 * 1.5 * 1000000000), keepAliveDisconnectHandler.getReaderIdleTimeNanos());
        assertNotNull(keepAlive);
        assertEquals(500, keepAlive.longValue());
        assertEquals(500, keepAliveFromCONNACK.get());
    }


    @Test
    public void test_connect_with_keep_alive_ok() {

        configurationService.mqttConfiguration().setKeepAliveMax(500);
        configurationService.mqttConfiguration().setKeepAliveAllowZero(false);

        createHandler();

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withKeepAlive(360)
                .withClientIdentifier("1")
                .withUserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        final AtomicLong keepAliveFromCONNACK = new AtomicLong();
        final CountDownLatch connackLatch = new CountDownLatch(1);

        channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
                    throws Exception {
                if (msg instanceof CONNACK) {
                    keepAliveFromCONNACK.set(((CONNACK) msg).getServerKeepAlive());
                    connackLatch.countDown();
                }
                super.write(ctx, msg, promise);
            }
        });


        assertTrue(channel.isOpen());
        channel.writeInbound(connect1);
        assertTrue(channel.isOpen());

        final Integer keepAlive = ClientConnection.of(channel).getConnectKeepAlive();

        final KeepAliveDisconnectHandler keepAliveDisconnectHandler =
                (KeepAliveDisconnectHandler) channel.pipeline().get(ChannelHandlerNames.MQTT_KEEPALIVE_IDLE_HANDLER);
        assertEquals((long) (360 * 1.5 * 1000000000), keepAliveDisconnectHandler.getReaderIdleTimeNanos());
        assertNotNull(keepAlive);

        assertNotNull(keepAlive);
        assertEquals(360, keepAlive.longValue());
        assertEquals(KEEP_ALIVE_NOT_SET, keepAliveFromCONNACK.get());
    }

    @Test
    public void test_connect_with_max_packet_size() {

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withMaximumPacketSize(300)
                .withClientIdentifier("1")
                .withUserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        assertTrue(channel.isOpen());
        channel.writeInbound(connect1);
        assertTrue(channel.isOpen());

        final Long maximumPacketSize = ClientConnection.of(channel).getMaxPacketSizeSend();

        assertNotNull(maximumPacketSize);
        assertEquals(300, maximumPacketSize.longValue());
    }

    @Test
    public void test_connect_with_session_expiry_interval_max() {

        configurationService.mqttConfiguration().setMaxSessionExpiryInterval(SESSION_EXPIRY_MAX);

        createHandler();

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withSessionExpiryInterval(SESSION_EXPIRY_MAX)
                .withClientIdentifier("1")
                .withUserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        assertTrue(channel.isOpen());
        channel.writeInbound(connect1);
        assertTrue(channel.isOpen());

        final Long expiry = ClientConnection.of(channel).getClientSessionExpiryInterval();

        assertNotNull(expiry);
        assertEquals(SESSION_EXPIRY_MAX, expiry.longValue());
    }

    @Test
    public void test_connect_with_topic_alias_enabled() {

        configurationService.mqttConfiguration().setTopicAliasMaxPerClient(5);
        configurationService.mqttConfiguration().setTopicAliasEnabled(true);

        createHandler();

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withSessionExpiryInterval(SESSION_EXPIRY_MAX)
                .withClientIdentifier("1")
                .withUserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        assertTrue(channel.isOpen());
        channel.writeInbound(connect1);
        assertTrue(channel.isOpen());

        final String[] mapping = ClientConnection.of(channel).getTopicAliasMapping();

        assertEquals(5, mapping.length);
    }

    @Test
    public void test_connect_with_topic_alias_disabled() {

        configurationService.mqttConfiguration().setTopicAliasMaxPerClient(5);
        configurationService.mqttConfiguration().setTopicAliasEnabled(false);

        createHandler();

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withSessionExpiryInterval(SESSION_EXPIRY_MAX)
                .withClientIdentifier("1")
                .withUserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        assertTrue(channel.isOpen());
        channel.writeInbound(connect1);
        assertTrue(channel.isOpen());

        final String[] mapping = ClientConnection.of(channel).getTopicAliasMapping();
        assertNull(mapping);

    }

    @Test
    public void test_connect_with_session_expiry_interval_overridden() {

        configurationService.mqttConfiguration().setMaxSessionExpiryInterval(10000L);

        createHandler();

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withSessionExpiryInterval(SESSION_EXPIRY_MAX)
                .withClientIdentifier("1")
                .withUserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();


        final AtomicLong sessionExpiryFromCONNACK = new AtomicLong();
        final CountDownLatch connackLatch = new CountDownLatch(1);

        channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
                    throws Exception {
                if (msg instanceof CONNACK) {
                    sessionExpiryFromCONNACK.set(((CONNACK) msg).getSessionExpiryInterval());
                    connackLatch.countDown();
                }
                super.write(ctx, msg, promise);
            }
        });

        assertTrue(channel.isOpen());
        channel.writeInbound(connect1);
        assertTrue(channel.isOpen());

        final Long expiryFromChannel = ClientConnection.of(channel).getClientSessionExpiryInterval();

        assertNotNull(expiryFromChannel);
        assertEquals(10000L, expiryFromChannel.longValue());
        assertEquals(10000L, sessionExpiryFromCONNACK.get());
    }

    @Test
    public void test_connect_with_assigned_client_identifier() throws InterruptedException {

        configurationService.securityConfiguration().setAllowServerAssignedClientId(true);

        createHandler();

        clientConnectionContext.setClientIdAssigned(true);

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withClientIdentifier("assigned")
                .withUserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        final AtomicReference<String> clientID = new AtomicReference<>();
        final CountDownLatch connackLatch = new CountDownLatch(1);

        channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
                    throws Exception {
                if (msg instanceof CONNACK) {
                    clientID.set(((CONNACK) msg).getAssignedClientIdentifier());
                    connackLatch.countDown();
                }
                super.write(ctx, msg, promise);
            }
        });

        assertTrue(channel.isOpen());
        channel.writeInbound(connect1);
        assertTrue(channel.isOpen());

        assertTrue(connackLatch.await(10, TimeUnit.SECONDS));

        assertEquals("assigned", clientID.get());
    }

    @Test
    public void test_connect_with_own_client_identifier() throws InterruptedException {

        configurationService.securityConfiguration().setAllowServerAssignedClientId(true);

        createHandler();

        clientConnectionContext.setClientIdAssigned(false);

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withClientIdentifier("ownId")
                .withUserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .build();

        final AtomicReference<String> clientID = new AtomicReference<>();
        final CountDownLatch connackLatch = new CountDownLatch(1);

        channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
                    throws Exception {
                if (msg instanceof CONNACK) {
                    clientID.set(((CONNACK) msg).getAssignedClientIdentifier());
                    connackLatch.countDown();
                }
                super.write(ctx, msg, promise);
            }
        });

        assertTrue(channel.isOpen());
        channel.writeInbound(connect1);
        assertTrue(channel.isOpen());

        assertTrue(connackLatch.await(10, TimeUnit.SECONDS));

        assertNull(clientID.get());
    }

    @Test
    public void test_connect_with_auth_user_props() throws InterruptedException {

        configurationService.securityConfiguration().setAllowServerAssignedClientId(true);

        createHandler();

        clientConnectionContext.setAuthUserProperties(Mqtt5UserProperties.of(MqttUserProperty.of("name", "value")));

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withClientIdentifier("ownId")
                .withUserProperties(Mqtt5UserProperties.of(MqttUserProperty.of("connect", "value")))
                .build();

        final AtomicReference<Mqtt5UserProperties> userProps = new AtomicReference<>();
        final CountDownLatch connackLatch = new CountDownLatch(1);

        channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise promise)
                    throws Exception {
                if (msg instanceof CONNACK) {
                    userProps.set(((CONNACK) msg).getUserProperties());
                    connackLatch.countDown();
                }
                super.write(ctx, msg, promise);
            }
        });

        assertTrue(channel.isOpen());
        channel.writeInbound(connect1);
        assertTrue(channel.isOpen());

        assertTrue(connackLatch.await(10, TimeUnit.SECONDS));

        assertEquals(1, userProps.get().asList().size());
        assertEquals("name", userProps.get().asList().get(0).getName());
        assertEquals("value", userProps.get().asList().get(0).getValue());

        assertNull(ClientConnection.of(channel).getAuthUserProperties());
    }

    @Test
    public void test_connect_handler_removed_from_pipeline() {

        System.out.println(channel.pipeline().names());
        assertTrue(channel.pipeline().names().contains(ChannelHandlerNames.MQTT_CONNECT_HANDLER));
        assertFalse(channel.pipeline().names().contains(ChannelHandlerNames.MQTT_DISALLOW_SECOND_CONNECT));

        final CONNECT connect = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withClientIdentifier("clientId")
                .withCleanStart(true)
                .build();

        channel.writeInbound(connect);

        System.out.println(channel.pipeline().names());
        assertFalse(channel.pipeline().names().contains(ChannelHandlerNames.MQTT_CONNECT_HANDLER));
    }

    @Test(timeout = 5_000)
    public void test_client_takeover_mqtt3() throws Exception {

        final CountDownLatch disconnectEventLatch = new CountDownLatch(1);
        final Waiter disconnectMessageWaiter = new Waiter();
        final TestDisconnectHandler testDisconnectHandler = new TestDisconnectHandler(disconnectMessageWaiter, false);

        final EmbeddedChannel oldChannel =
                new EmbeddedChannel(testDisconnectHandler, new TestDisconnectEventHandler(disconnectEventLatch));

        final ClientConnection oldClientConnection = new DummyClientConnection(oldChannel, null);
        oldChannel.attr(ClientConnectionContext.CHANNEL_ATTRIBUTE_NAME).set(oldClientConnection);
        oldClientConnection.setClientId("clientId");
        oldClientConnection.proposeClientState(ClientState.AUTHENTICATED);
        oldClientConnection.setProtocolVersion(ProtocolVersion.MQTTv3_1_1);

        final SettableFuture<Void> disconnectFuture = SettableFuture.create();
        oldClientConnection.setDisconnectFuture(disconnectFuture);

        connectionPersistence.persistIfAbsent(oldClientConnection);

        Checkpoints.callbackOnCheckpoint("on-client-disconnect", () -> {
            connectionPersistence.remove(oldClientConnection);
            disconnectFuture.set(null);
        });

        assertTrue(oldChannel.isOpen());
        assertTrue(channel.isOpen());

        final CONNECT connect1 = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withClientIdentifier("clientId")
                .build();

        channel.writeInbound(connect1);
        channel.runPendingTasks();
        oldChannel.runPendingTasks();

        assertTrue(channel.isOpen());
        assertFalse(oldChannel.isOpen());
        assertEquals(ClientState.DISCONNECTED_TAKEN_OVER, oldClientConnection.getClientState());

        assertTrue(disconnectEventLatch.await(5, TimeUnit.SECONDS));
        disconnectMessageWaiter.await();

        final DISCONNECT disconnectMessage = testDisconnectHandler.getDisconnectMessage();
        assertNull(disconnectMessage);
    }

    @Test(timeout = 5_000)
    public void test_client_takeover_mqtt5() throws Exception {

        final CountDownLatch disconnectEventLatch = new CountDownLatch(1);
        final Waiter disconnectMessageWaiter = new Waiter();
        final TestDisconnectHandler testDisconnectHandler = new TestDisconnectHandler(disconnectMessageWaiter, true);

        final EmbeddedChannel oldChannel =
                new EmbeddedChannel(testDisconnectHandler, new TestDisconnectEventHandler(disconnectEventLatch));
        final ClientConnection oldClientConnection = new DummyClientConnection(oldChannel, null);
        oldClientConnection.setClientId("clientId");
        oldClientConnection.proposeClientState(ClientState.AUTHENTICATED);
        oldClientConnection.setProtocolVersion(ProtocolVersion.MQTTv5);
        oldChannel.attr(ClientConnectionContext.CHANNEL_ATTRIBUTE_NAME).set(oldClientConnection);

        final SettableFuture<Void> disconnectFuture = SettableFuture.create();
        oldClientConnection.setDisconnectFuture(disconnectFuture);

        connectionPersistence.persistIfAbsent(oldClientConnection);

        Checkpoints.callbackOnCheckpoint("on-client-disconnect", () -> {
            connectionPersistence.remove(oldClientConnection);
            disconnectFuture.set(null);
        });

        assertTrue(oldChannel.isOpen());
        assertTrue(channel.isOpen());

        final CONNECT connect1 = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv5)
                .withClientIdentifier("clientId")
                .build();

        channel.writeInbound(connect1);
        oldChannel.runPendingTasks();
        channel.runPendingTasks();

        assertTrue(channel.isOpen());
        assertFalse(oldChannel.isOpen());
        assertEquals(ClientState.DISCONNECTED_TAKEN_OVER, oldClientConnection.getClientState());

        assertTrue(disconnectEventLatch.await(5, TimeUnit.SECONDS));
        disconnectMessageWaiter.await();

        final DISCONNECT disconnectMessage = testDisconnectHandler.getDisconnectMessage();
        assertNotNull(disconnectMessage);
        assertEquals(Mqtt5DisconnectReasonCode.SESSION_TAKEN_OVER, disconnectMessage.getReasonCode());
        assertEquals(ReasonStrings.DISCONNECT_SESSION_TAKEN_OVER, disconnectMessage.getReasonString());
    }

    @Test
    public void test_client_takeover_retry_mqtt5() throws Exception {

        final SettableFuture<Void> disconnectFuture = SettableFuture.create();

        final CountDownLatch disconnectEventLatch = new CountDownLatch(1);
        final Waiter disconnectMessageWaiter = new Waiter();
        final TestDisconnectHandler testDisconnectHandler = new TestDisconnectHandler(disconnectMessageWaiter, true);

        final EmbeddedChannel oldChannel =
                new EmbeddedChannel(testDisconnectHandler, new TestDisconnectEventHandler(disconnectEventLatch));

        final ClientConnection oldClientConnection = new DummyClientConnection(oldChannel, null);
        oldChannel.attr(ClientConnectionContext.CHANNEL_ATTRIBUTE_NAME).set(oldClientConnection);
        oldClientConnection.setProtocolVersion(ProtocolVersion.MQTTv5);
        oldClientConnection.setDisconnectFuture(disconnectFuture);
        oldClientConnection.proposeClientState(ClientState.DISCONNECTING);

        final AtomicReference<ClientConnection> oldClientConnectionRef = new AtomicReference<>(oldClientConnection);
        Checkpoints.callbackOnCheckpoint("on-client-disconnect", () -> {
            connectionPersistence.remove(oldClientConnection);
            disconnectFuture.set(null);
        });

        assertTrue(oldChannel.isOpen());
        assertTrue(channel.isOpen());

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withClientIdentifier("sameClientId").build();

        channel.writeInbound(connect1); // queue retry

        oldChannel.runPendingTasks();

        assertTrue(oldChannel.isOpen());
        assertTrue(channel.isOpen());

        oldClientConnection.getChannel()
                .eventLoop()
                .execute(() -> serverDisconnector.disconnect(oldClientConnection.getChannel(),
                        "Disconnecting already connected client with id {} and ip {} because another client connects with that id",
                        ReasonStrings.DISCONNECT_SESSION_TAKEN_OVER,
                        Mqtt5DisconnectReasonCode.SESSION_TAKEN_OVER,
                        ReasonStrings.DISCONNECT_SESSION_TAKEN_OVER));

        oldChannel.runPendingTasks(); // disconnect client
        channel.runPendingTasks(); // retry take over

        assertTrue(channel.isOpen());
        assertFalse(oldChannel.isOpen());
        assertEquals(ClientState.DISCONNECTED_TAKEN_OVER, oldClientConnection.getClientState());

        assertTrue(disconnectEventLatch.await(5, TimeUnit.SECONDS));
        disconnectMessageWaiter.await();

        final DISCONNECT disconnectMessage = testDisconnectHandler.getDisconnectMessage();
        assertNotNull(disconnectMessage);
        assertEquals(Mqtt5DisconnectReasonCode.SESSION_TAKEN_OVER, disconnectMessage.getReasonCode());
        assertEquals(ReasonStrings.DISCONNECT_SESSION_TAKEN_OVER, disconnectMessage.getReasonString());
    }

    @Test
    public void test_client_takeover_retry_mqtt3() throws Exception {

        final SettableFuture<Void> disconnectFuture = SettableFuture.create();

        final CountDownLatch disconnectEventLatch = new CountDownLatch(1);
        final Waiter disconnectMessageWaiter = new Waiter();
        final TestDisconnectHandler testDisconnectHandler = new TestDisconnectHandler(disconnectMessageWaiter, false);

        final EmbeddedChannel oldChannel =
                new EmbeddedChannel(testDisconnectHandler, new TestDisconnectEventHandler(disconnectEventLatch));
        final ClientConnection oldClientConnection = new DummyClientConnection(oldChannel, null);
        oldChannel.attr(ClientConnectionContext.CHANNEL_ATTRIBUTE_NAME).set(oldClientConnection);
        oldClientConnection.setProtocolVersion(ProtocolVersion.MQTTv3_1);
        oldClientConnection.setDisconnectFuture(disconnectFuture);
        oldClientConnection.proposeClientState(ClientState.DISCONNECTING);

        final AtomicReference<ClientConnection> oldClientConnectionRef = new AtomicReference<>(oldClientConnection);
        Checkpoints.callbackOnCheckpoint("on-client-disconnect", () -> {
            connectionPersistence.remove(oldClientConnection);
            disconnectFuture.set(null);
        });

        assertTrue(oldChannel.isOpen());
        assertTrue(channel.isOpen());

        final CONNECT connect1 = new CONNECT.Mqtt5Builder().withClientIdentifier("sameClientId").build();

        channel.writeInbound(connect1); // queue retry

        assertTrue(oldChannel.isOpen());
        assertTrue(channel.isOpen());

        oldClientConnection.getChannel()
                .eventLoop()
                .execute(() -> serverDisconnector.disconnect(oldClientConnection.getChannel(),
                        "Disconnecting already connected client with id {} and ip {} because another client connects with that id",
                        ReasonStrings.DISCONNECT_SESSION_TAKEN_OVER,
                        Mqtt5DisconnectReasonCode.SESSION_TAKEN_OVER,
                        ReasonStrings.DISCONNECT_SESSION_TAKEN_OVER));

        oldChannel.runPendingTasks(); // disconnect client
        channel.runPendingTasks(); // retry take over

        assertTrue(channel.isOpen());
        assertFalse(oldChannel.isOpen());
        assertEquals(ClientState.DISCONNECTED_TAKEN_OVER, oldClientConnection.getClientState());

        assertTrue(disconnectEventLatch.await(5, TimeUnit.SECONDS));
        disconnectMessageWaiter.await();

        final DISCONNECT disconnectMessage = testDisconnectHandler.getDisconnectMessage();
        assertNull(disconnectMessage);
    }

    @Test
    public void test_too_long_clientid() throws Exception {

        configurationService.restrictionsConfiguration().setMaxClientIdLength(5);
        createHandler();

        final CountDownLatch latch = new CountDownLatch(1);

        final CONNECT connect = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withClientIdentifier("123456")
                .build();

        final CountDownLatch eventLatch = new CountDownLatch(1);
        channel.pipeline().addLast(new TestDisconnectEventHandler(eventLatch));
        channel.closeFuture().addListener((ChannelFutureListener) future -> latch.countDown());

        channel.writeInbound(connect);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_will_topic_dollar() throws Exception {

        createHandler();

        final CountDownLatch latch = new CountDownLatch(1);

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt3Builder().withPayload(new byte[100])
                .withQos(QoS.EXACTLY_ONCE)
                .withHivemqId("hmqid")
                .withTopic("top/#")
                .build();

        final CONNECT connect = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withClientIdentifier("123456")
                .withWillPublish(willPublish)
                .build();

        final CountDownLatch eventLatch = new CountDownLatch(1);
        channel.pipeline().addLast(new TestDisconnectEventHandler(eventLatch));
        channel.closeFuture().addListener((ChannelFutureListener) future -> latch.countDown());

        channel.writeInbound(connect);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_will_topic_max_length_exceeded() throws Exception {
        configurationService.restrictionsConfiguration().setMaxTopicLength(5);

        createHandler();

        final CountDownLatch latch = new CountDownLatch(1);

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt3Builder().withPayload(new byte[100])
                .withQos(QoS.EXACTLY_ONCE)
                .withTopic("12345678890")
                .build();

        final CONNECT connect = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withClientIdentifier("123456")
                .withWillPublish(willPublish)
                .build();

        final CountDownLatch eventLatch = new CountDownLatch(1);
        channel.pipeline().addLast(new TestDisconnectEventHandler(eventLatch));
        channel.closeFuture().addListener((ChannelFutureListener) future -> latch.countDown());

        channel.writeInbound(connect);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_will_topic_max_length_exceeded_mqtt5() throws Exception {
        configurationService.restrictionsConfiguration().setMaxTopicLength(5);

        createHandler();

        final CountDownLatch latch = new CountDownLatch(1);

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withPayload(new byte[100])
                .withQos(QoS.EXACTLY_ONCE)
                .withTopic("12345678890")
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("123456").withWillPublish(willPublish).build();

        final CountDownLatch eventLatch = new CountDownLatch(1);
        channel.pipeline().addLast(new TestDisconnectEventHandler(eventLatch));
        channel.closeFuture().addListener((ChannelFutureListener) future -> latch.countDown());

        channel.writeInbound(connect);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_will_exceed_max_qos_mqtt5() throws Exception {

        createHandler();
        configurationService.mqttConfiguration().setMaximumQos(QoS.AT_MOST_ONCE);

        final CountDownLatch latch = new CountDownLatch(1);

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withPayload("message".getBytes())
                .withQos(QoS.EXACTLY_ONCE)
                .withUserProperties(Mqtt5UserProperties.NO_USER_PROPERTIES)
                .withTopic("topic")
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("123456").withWillPublish(willPublish).build();

        final CountDownLatch eventLatch = new CountDownLatch(1);
        channel.pipeline().addLast(new TestDisconnectEventHandler(eventLatch));
        channel.closeFuture().addListener((ChannelFutureListener) future -> latch.countDown());

        channel.writeInbound(connect);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_too_long_clientid_mqtt5() throws Exception {

        configurationService.restrictionsConfiguration().setMaxClientIdLength(5);
        createHandler();

        final CountDownLatch latch = new CountDownLatch(1);

        final CONNECT connect = new CONNECT.Mqtt5Builder().withClientIdentifier("123456").build();

        final CountDownLatch eventLatch = new CountDownLatch(1);
        channel.pipeline().addLast(new TestDisconnectEventHandler(eventLatch));
        channel.closeFuture().addListener((ChannelFutureListener) future -> latch.countDown());

        channel.writeInbound(connect);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_wrong_event_is_passed_through() throws Exception {

        final CollectUserEventsHandler<String> collectUserEventsHandler = new CollectUserEventsHandler<>(String.class);
        channel.pipeline().addLast(collectUserEventsHandler);

        final String test = "test";

        handler.userEventTriggered(ctx, test);

        assertNotNull(collectUserEventsHandler.pollEvent());

    }

    @Test
    public void test_will_retain_not_supported_mqtt3() throws InterruptedException {
        clientConnectionContext.setProtocolVersion(ProtocolVersion.MQTTv3_1_1);

        configurationService.mqttConfiguration().setRetainedMessagesEnabled(false);

        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt3Builder().withPayload(new byte[100])
                .withQos(QoS.EXACTLY_ONCE)
                .withHivemqId("hmqid")
                .withTopic("top")
                .withRetain(true)
                .build();

        final CONNECT connect = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withClientIdentifier("123456")
                .withWillPublish(willPublish)
                .build();

        final CountDownLatch eventLatch = new CountDownLatch(1);
        channel.pipeline().addLast(new TestDisconnectEventHandler(eventLatch));

        channel.writeInbound(connect);

        final CONNACK connack = channel.readOutbound();
        assertNotNull(connack);
        assertEquals(Mqtt3ConnAckReturnCode.REFUSED_NOT_AUTHORIZED, connack.getReturnCode());
        assertFalse(channel.isActive());
        assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_will_retain_supported_mqtt3() {
        clientConnectionContext.setProtocolVersion(ProtocolVersion.MQTTv3_1_1);

        configurationService.mqttConfiguration().setRetainedMessagesEnabled(true);

        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt3Builder().withPayload(new byte[100])
                .withQos(QoS.EXACTLY_ONCE)
                .withHivemqId("hmqid")
                .withTopic("top")
                .withRetain(true)
                .build();

        final CONNECT connect = new CONNECT.Mqtt3Builder().withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withClientIdentifier("123456")
                .withWillPublish(willPublish)
                .build();

        channel.writeInbound(connect);
        channel.runPendingTasks();

        final CONNACK connack = channel.readOutbound();
        assertNotNull(connack);
        assertEquals(Mqtt3ConnAckReturnCode.ACCEPTED, connack.getReturnCode());
        assertTrue(channel.isActive());

        assertNotNull(ClientConnection.of(channel).getAuthPermissions());
    }

    @Test
    public void test_will_retain_not_supported_mqtt5() throws InterruptedException {
        clientConnectionContext.setProtocolVersion(ProtocolVersion.MQTTv5);

        configurationService.mqttConfiguration().setRetainedMessagesEnabled(false);

        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt3Builder().withPayload(new byte[100])
                .withQos(QoS.EXACTLY_ONCE)
                .withHivemqId("hmqid")
                .withTopic("top")
                .withRetain(true)
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("123456").withWillPublish(willPublish).build();

        final CountDownLatch eventLatch = new CountDownLatch(1);
        channel.pipeline().addLast(new TestDisconnectEventHandler(eventLatch));
        channel.writeInbound(connect);

        final CONNACK connack = channel.readOutbound();
        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.RETAIN_NOT_SUPPORTED, connack.getReasonCode());
        assertFalse(channel.isActive());
        assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void test_will_retain_supported_mqtt5() {
        clientConnectionContext.setProtocolVersion(ProtocolVersion.MQTTv5);

        configurationService.mqttConfiguration().setRetainedMessagesEnabled(true);

        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withPayload(new byte[100])
                .withQos(QoS.EXACTLY_ONCE)
                .withHivemqId("hmqid")
                .withTopic("top")
                .withRetain(true)
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("123456").withWillPublish(willPublish).build();

        channel.writeInbound(connect);

        final CONNACK connack = channel.readOutbound();
        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.SUCCESS, connack.getReasonCode());
        assertTrue(channel.isActive());

        assertNotNull(ClientConnection.of(channel).getAuthPermissions());
    }

    /* ******
     * Auth *
     ********/

    @Test(timeout = 5000)
    public void test_auth_in_progress_message_handler_is_removed() {
        createHandler();
        clientConnectionContext.setAuthMethod("someMethod");
        channel.pipeline()
                .addAfter(ChannelHandlerNames.MQTT_MESSAGE_DECODER,
                        ChannelHandlerNames.AUTH_IN_PROGRESS_MESSAGE_HANDLER,
                        channelDependencies.getAuthInProgressMessageHandler());
        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withAuthMethod("someMethod").build();

        handler.connectSuccessfulAuthenticated(ctx, clientConnectionContext, connect, null);

        channel.runPendingTasks();

        assertNull(channel.pipeline().get(ChannelHandlerNames.AUTH_IN_PROGRESS_MESSAGE_HANDLER));
        assertEquals(ClientState.AUTHENTICATED, ClientConnection.of(channel).getClientState());
    }

    @Test(timeout = 5000)
    public void test_auth_is_performed() {
        createHandler();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withAuthMethod("someMethod").build();
        channel.writeInbound(connect);

        verify(internalAuthServiceImpl, times(1)).authenticateConnect(any(), any(), any(), any());
    }

    @Test(timeout = 5000)
    public void test_connack_success_if_no_authenticator_registered() {
        createHandler();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withAuthMethod("someMethod").build();
        channel.writeInbound(connect);

        final CONNACK connack = channel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.SUCCESS, connack.getReasonCode());
        assertTrue(channel.isActive());
    }

    @Test(timeout = 5000)
    public void test_connect_successfully_if_no_authenticator_present_and_no_auth_info_given() {
        createHandler();

        final CONNECT connect = new CONNECT.Mqtt5Builder().withClientIdentifier("client").build();
        channel.writeInbound(connect);

        final CONNACK connack = channel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.SUCCESS, connack.getReasonCode());
        assertTrue(channel.isActive());
        assertEquals(ClientState.AUTHENTICATED, ClientConnection.of(channel).getClientState());
    }

    @Test(timeout = 5000)
    public void test_will_authorization_success() {
        createHandler();

        when(clientSessionPersistence.clientConnected(anyString(),
                anyBoolean(),
                anyLong(),
                any(MqttWillPublish.class),
                anyLong())).thenReturn(Futures.immediateFuture(null));

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE)
                .withPayload(new byte[]{1, 2, 3})
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withWillPublish(willPublish).build();

        defaultPermissions.add(new TopicPermissionBuilderImpl(new TestConfigurationBootstrap().getFullConfigurationService()).topicFilter(
                "topic").type(TopicPermission.PermissionType.ALLOW).build());

        channel.writeInbound(connect);

        final CONNACK connack = channel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.SUCCESS, connack.getReasonCode());
        assertTrue(channel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorizer_success() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE)
                .withPayload(new byte[]{1, 2, 3})
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withWillPublish(willPublish).build();


        final PublishAuthorizerResult result = new PublishAuthorizerResult(AckReasonCode.SUCCESS, null, true);
        channel.pipeline().fireUserEventTriggered(new AuthorizeWillResultEvent(connect, result));

        channel.runPendingTasks();
        final CONNACK connack = channel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.SUCCESS, connack.getReasonCode());
        assertTrue(channel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorizer_fail() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE)
                .withPayload(new byte[]{1, 2, 3})
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withWillPublish(willPublish).build();


        final PublishAuthorizerResult result = new PublishAuthorizerResult(AckReasonCode.NOT_AUTHORIZED, null, true);
        channel.pipeline().fireUserEventTriggered(new AuthorizeWillResultEvent(connect, result));

        channel.runPendingTasks();
        final CONNACK connack = channel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.NOT_AUTHORIZED, connack.getReasonCode());
        assertFalse(channel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorizer_disconnect() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE)
                .withPayload(new byte[]{1, 2, 3})
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withWillPublish(willPublish).build();


        final PublishAuthorizerResult result = new PublishAuthorizerResult(AckReasonCode.NOT_AUTHORIZED,
                null,
                true,
                DisconnectReasonCode.PAYLOAD_FORMAT_INVALID);
        channel.pipeline().fireUserEventTriggered(new AuthorizeWillResultEvent(connect, result));

        channel.runPendingTasks();
        final CONNACK connack = channel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.PAYLOAD_FORMAT_INVALID, connack.getReasonCode());
        assertFalse(channel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorizer_next_no_perms() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE)
                .withPayload(new byte[]{1, 2, 3})
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withWillPublish(willPublish).build();


        final PublishAuthorizerResult result = new PublishAuthorizerResult(null, null, true);
        channel.pipeline().fireUserEventTriggered(new AuthorizeWillResultEvent(connect, result));

        channel.runPendingTasks();
        final CONNACK connack = channel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.NOT_AUTHORIZED, connack.getReasonCode());
        assertFalse(channel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorizer_next_perms_avail_allow() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE)
                .withPayload(new byte[]{1, 2, 3})
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withWillPublish(willPublish).build();

        final ModifiableDefaultPermissionsImpl permissions = new ModifiableDefaultPermissionsImpl();
        permissions.add(new TopicPermissionBuilderImpl(new TestConfigurationBootstrap().getFullConfigurationService()).topicFilter(
                "topic").type(TopicPermission.PermissionType.ALLOW).build());
        clientConnectionContext.setAuthPermissions(permissions);

        final PublishAuthorizerResult result = new PublishAuthorizerResult(null, null, true);
        channel.pipeline().fireUserEventTriggered(new AuthorizeWillResultEvent(connect, result));

        channel.runPendingTasks();
        final CONNACK connack = channel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.SUCCESS, connack.getReasonCode());
        assertTrue(channel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorizer_next_perms_avail_default_allow() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE)
                .withPayload(new byte[]{1, 2, 3})
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withWillPublish(willPublish).build();

        final ModifiableDefaultPermissionsImpl permissions = new ModifiableDefaultPermissionsImpl();
        permissions.setDefaultBehaviour(DefaultAuthorizationBehaviour.ALLOW);
        clientConnectionContext.setAuthPermissions(permissions);

        final PublishAuthorizerResult result = new PublishAuthorizerResult(null, null, true);
        channel.pipeline().fireUserEventTriggered(new AuthorizeWillResultEvent(connect, result));

        channel.runPendingTasks();
        final CONNACK connack = channel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.SUCCESS, connack.getReasonCode());
        assertTrue(channel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorizer_next_perms_avail_deny() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE)
                .withPayload(new byte[]{1, 2, 3})
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withWillPublish(willPublish).build();

        defaultPermissions.add(new TopicPermissionBuilderImpl(new TestConfigurationBootstrap().getFullConfigurationService()).topicFilter(
                "topic").type(TopicPermission.PermissionType.DENY).build());

        final PublishAuthorizerResult result = new PublishAuthorizerResult(null, null, true);
        channel.pipeline().fireUserEventTriggered(new AuthorizeWillResultEvent(connect, result));

        channel.runPendingTasks();
        final CONNACK connack = channel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.NOT_AUTHORIZED, connack.getReasonCode());
        assertFalse(channel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorizer_next_perms_avail_default_deny() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE)
                .withPayload(new byte[]{1, 2, 3})
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withWillPublish(willPublish).build();

        defaultPermissions.setDefaultBehaviour(DefaultAuthorizationBehaviour.DENY);

        final PublishAuthorizerResult result = new PublishAuthorizerResult(null, null, true);
        channel.pipeline().fireUserEventTriggered(new AuthorizeWillResultEvent(connect, result));

        channel.runPendingTasks();
        final CONNACK connack = channel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.NOT_AUTHORIZED, connack.getReasonCode());
        assertFalse(channel.isActive());
    }

    @Test(timeout = 5000)
    public void test_will_authorization_fail() {
        createHandler();

        final MqttWillPublish willPublish = new MqttWillPublish.Mqtt5Builder().withTopic("topic")
                .withQos(QoS.AT_LEAST_ONCE)
                .withPayload(new byte[]{1, 2, 3})
                .build();

        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withWillPublish(willPublish).build();

        defaultPermissions.add(new TopicPermissionBuilderImpl(new TestConfigurationBootstrap().getFullConfigurationService()).topicFilter(
                "topic").type(TopicPermission.PermissionType.DENY).build());

        channel.writeInbound(connect);

        final CONNACK connack = channel.readOutbound();

        assertNotNull(connack);
        assertEquals(Mqtt5ConnAckReasonCode.NOT_AUTHORIZED, connack.getReasonCode());
        assertFalse(channel.isActive());
    }

    @Test(timeout = 5000)
    public void test_set_client_settings() {
        createHandler();
        clientConnectionContext.setAuthMethod("someMethod");
        channel.pipeline()
                .addAfter(ChannelHandlerNames.MQTT_MESSAGE_DECODER,
                        ChannelHandlerNames.AUTH_IN_PROGRESS_MESSAGE_HANDLER,
                        channelDependencies.getAuthInProgressMessageHandler());
        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withAuthMethod("someMethod").build();

        final ModifiableClientSettingsImpl clientSettings = new ModifiableClientSettingsImpl(65535, null);
        clientSettings.setClientReceiveMaximum(123);
        clientSettings.setOverloadProtectionThrottlingLevel(NONE);
        handler.connectSuccessfulAuthenticated(ctx, clientConnectionContext, connect, clientSettings);

        channel.runPendingTasks();

        assertEquals(ClientState.AUTHENTICATED, ClientConnection.of(channel).getClientState());
        assertEquals(123, ClientConnection.of(channel).getClientReceiveMaximum().intValue());
        assertEquals(123, connect.getReceiveMaximum());
    }

    @Test(timeout = 5000)
    public void test_connectAuthenticated_connectMessageCleared() {
        createHandler();
        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withAuthMethod("someMethod").build();
        final ModifiableClientSettingsImpl clientSettings = new ModifiableClientSettingsImpl(65535, null);
        clientConnectionContext.setAuthConnect(connect);

        handler.connectSuccessfulAuthenticated(ctx, clientConnectionContext, connect, clientSettings);

        final ClientConnection clientConnection = ClientConnection.of(channel);
        assertEquals(ClientState.AUTHENTICATED, clientConnection.getClientState());
        assertNull(clientConnection.getAuthConnect());
    }

    @Test(timeout = 5000)
    public void test_connectUnauthenticated_connectMessageCleared() {
        createHandler();
        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").withAuthMethod("someMethod").build();
        final ModifiableClientSettingsImpl clientSettings = new ModifiableClientSettingsImpl(65535, null);
        clientConnectionContext.setAuthConnect(connect);

        handler.connectSuccessfulUndecided(ctx, clientConnectionContext, connect, clientSettings);

        final ClientConnection clientConnection = ClientConnection.of(channel);
        assertEquals(ClientState.AUTHENTICATED, clientConnection.getClientState());
        assertNull(clientConnection.getAuthConnect());
    }

    @Test(timeout = 5000)
    public void test_contextWantsPasswordErasure_passwordCleared() {
        createHandler();
        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").build();

        clientConnectionContext.setClearPasswordAfterAuth(true);
        clientConnectionContext.setAuthPassword("password".getBytes(StandardCharsets.UTF_8));

        final ModifiableClientSettingsImpl clientSettings = new ModifiableClientSettingsImpl(65535, null);
        handler.connectSuccessfulAuthenticated(ctx, clientConnectionContext, connect, clientSettings);

        final ClientConnection clientConnection = ClientConnection.of(channel);
        assertNull(clientConnection.getAuthPassword());
    }

    @Test(timeout = 5000)
    public void test_contextDoesNotWantPasswordErasure_passwordKept() {
        createHandler();
        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").build();

        clientConnectionContext.setClearPasswordAfterAuth(false);
        clientConnectionContext.setAuthPassword("password".getBytes(StandardCharsets.UTF_8));

        final ModifiableClientSettingsImpl clientSettings = new ModifiableClientSettingsImpl(65535, null);
        handler.connectSuccessfulAuthenticated(ctx, clientConnectionContext, connect, clientSettings);

        final ClientConnection clientConnection = ClientConnection.of(channel);
        assertNotNull(clientConnection.getAuthPassword());
    }

    @Test(timeout = 5000)
    public void test_contextDoesNotDecidePasswordErasure_passwordKeptOrCleared() {
        createHandler();
        final CONNECT connect =
                new CONNECT.Mqtt5Builder().withClientIdentifier("client").build();

        clientConnectionContext.setClearPasswordAfterAuth(null);
        clientConnectionContext.setAuthPassword("password".getBytes(StandardCharsets.UTF_8));

        final ModifiableClientSettingsImpl clientSettings = new ModifiableClientSettingsImpl(65535, null);
        handler.connectSuccessfulAuthenticated(ctx, clientConnectionContext, connect, clientSettings);

        final ClientConnection clientConnection = ClientConnection.of(channel);
        if(MQTT_CONNECTION_AUTH_CLEAR_PASSWORD) {
            assertNull(clientConnection.getAuthPassword());
        } else {
            assertNotNull(clientConnection.getAuthPassword());
        }
    }

    @Test
    public void test_start_connection_persistent() throws Exception {
        final CONNECT connect = new CONNECT.Mqtt3Builder().withClientIdentifier("client")
                .withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withCleanStart(false)
                .withSessionExpiryInterval(SESSION_EXPIRY_MAX)
                .build();

        clientConnectionContext.setClientId("client");
        clientConnectionContext.setClientSessionExpiryInterval(20000L);

        final ClientConnection clientConnection = ClientConnection.from(clientConnectionContext);

        handler.afterTakeover(ctx, clientConnection, connect);

        verify(clientSessionPersistence).clientConnected(eq("client"),
                eq(false),
                eq(SESSION_EXPIRY_MAX),
                isNull(),
                isNull());
    }

    @Test
    public void test_start_connection_persistent_queue_limit() throws Exception {
        final CONNECT connect = new CONNECT.Mqtt3Builder().withClientIdentifier("client")
                .withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withCleanStart(false)
                .withSessionExpiryInterval(SESSION_EXPIRY_MAX)
                .build();

        clientConnectionContext.setClientId("client");
        clientConnectionContext.setClientSessionExpiryInterval(20000L);
        clientConnectionContext.setQueueSizeMaximum(123L);

        final ClientConnection clientConnection = ClientConnection.from(clientConnectionContext);

        handler.afterTakeover(ctx, clientConnection, connect);

        verify(clientSessionPersistence).clientConnected(eq("client"),
                eq(false),
                eq(SESSION_EXPIRY_MAX),
                eq(null),
                eq(123L));
    }

    @Test
    public void test_update_persistence_data_fails() throws Exception {
        final CONNECT connect = new CONNECT.Mqtt3Builder().withClientIdentifier("client")
                .withProtocolVersion(ProtocolVersion.MQTTv3_1_1)
                .withCleanStart(false)
                .build();

        clientConnectionContext.setClientId("client");
        clientConnectionContext.setCleanStart(true);
        when(clientSessionPersistence.clientConnected(anyString(),
                anyBoolean(),
                anyLong(),
                isNull(),
                isNull())).thenReturn(Futures.immediateFailedFuture(new RuntimeException("test")));

        assertTrue(channel.isOpen());

        final ClientConnection clientConnection = ClientConnection.from(clientConnectionContext);

        handler.afterTakeover(ctx, clientConnection, connect);
        channel.runScheduledPendingTasks();
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
    }

    private void createHandler() {
        if (channel.pipeline().names().contains(ChannelHandlerNames.MQTT_CONNECT_HANDLER)) {
            channel.pipeline().remove(ChannelHandlerNames.MQTT_CONNECT_HANDLER);
        }
        if (channel.pipeline().names().contains(ChannelHandlerNames.MQTT_MESSAGE_BARRIER)) {
            channel.pipeline().remove(ChannelHandlerNames.MQTT_MESSAGE_BARRIER);
        }
        if (channel.pipeline().names().contains(ChannelHandlerNames.MQTT_AUTH_HANDLER)) {
            channel.pipeline().remove(ChannelHandlerNames.MQTT_AUTH_HANDLER);
        }
        if (channel.pipeline().names().contains(ChannelHandlerNames.MESSAGE_EXPIRY_HANDLER)) {
            channel.pipeline().remove(ChannelHandlerNames.MESSAGE_EXPIRY_HANDLER);
        }

        configurationService.mqttConfiguration().setServerReceiveMaximum(10);

        final Provider<PublishFlowHandler> publishFlowHandlerProvider =
                () -> new PublishFlowHandler(mock(PublishPollService.class),
                        mock(IncomingMessageFlowPersistence.class),
                        mock(OrderedTopicService.class),
                        mock(IncomingPublishHandler.class),
                        mock(DropOutgoingPublishesHandler.class));

        final Provider<FlowControlHandler> flowControlHandlerProvider =
                () -> new FlowControlHandler(configurationService.mqttConfiguration(), serverDisconnector);

        handler = new ConnectHandler(clientSessionPersistence,
                connectionPersistence,
                configurationService,
                publishFlowHandlerProvider,
                flowControlHandlerProvider,
                mqttConnacker,
                new TopicAliasLimiterImpl(),
                mock(PublishPollService.class),
                mock(SharedSubscriptionService.class),
                internalAuthServiceImpl,
                authorizers,
                pluginAuthorizerService,
                serverDisconnector,
                mock(KeepAliveDisconnectService.class));

        handler.postConstruct();
        channel.pipeline()
                .addAfter(ChannelHandlerNames.MQTT_MESSAGE_DECODER, ChannelHandlerNames.MQTT_CONNECT_HANDLER, handler);
        channel.pipeline()
                .addAfter(ChannelHandlerNames.MQTT_CONNECT_HANDLER,
                        ChannelHandlerNames.MQTT_MESSAGE_BARRIER,
                        new DummyHandler());
        channel.pipeline()
                .addBefore(ChannelHandlerNames.MQTT_MESSAGE_BARRIER,
                        ChannelHandlerNames.MQTT_AUTH_HANDLER,
                        new DummyHandler());
        channel.pipeline()
                .addBefore(ChannelHandlerNames.MQTT_MESSAGE_BARRIER,
                        ChannelHandlerNames.MESSAGE_EXPIRY_HANDLER,
                        new DummyHandler());

        doAnswer(invocation -> {
            ctx.channel()
                    .attr(ClientConnectionContext.CHANNEL_ATTRIBUTE_NAME)
                    .get()
                    .setAuthPermissions(defaultPermissions);
            handler.connectSuccessfulUndecided(invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3));
            return null;
        }).when(internalAuthServiceImpl).authenticateConnect(any(), any(), any(), any());

    }

    private void buildPipeline() {
        channel.pipeline().addFirst(ChannelHandlerNames.MQTT_MESSAGE_DECODER, TestMqttDecoder.create());
        channel.pipeline().addLast(ChannelHandlerNames.GLOBAL_THROTTLING_HANDLER, new DummyHandler());
        clientConnectionContext.setClientId("clientId");
        clientConnectionContext.setProtocolVersion(ProtocolVersion.MQTTv5);

        createHandler();

        final ClientSessionSubscriptionPersistence clientSessionSubscriptionPersistence =
                mock(ClientSessionSubscriptionPersistence.class);
        when(clientSessionSubscriptionPersistence.getSubscriptions(anyString())).thenReturn(ImmutableSet.of(new Topic(
                "t1",
                QoS.AT_LEAST_ONCE), new Topic("t2", QoS.AT_MOST_ONCE)));

        ctx = channel.pipeline().context(ConnectHandler.class);
        clientConnectionContext.proposeClientState(ClientState.AUTHENTICATED);
    }

    private static class TestDisconnectEventHandler extends SimpleChannelInboundHandler<CONNECT> {

        private final CountDownLatch eventLatch;

        public TestDisconnectEventHandler(final CountDownLatch eventLatch) {
            this.eventLatch = eventLatch;
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final CONNECT msg) {
            ctx.fireChannelRead(msg);
        }

        @Override
        public void userEventTriggered(final ChannelHandlerContext ctx, final Object evt) {
            if (evt instanceof OnServerDisconnectEvent) {
                final UserProperties userProperties = ((OnServerDisconnectEvent) evt).getUserProperties();
                if (userProperties == null || userProperties.isEmpty()) {
                    eventLatch.countDown();
                }
            }
        }
    }

    private static class TestDisconnectHandler extends ChannelDuplexHandler {

        private final Waiter waiter;
        private final boolean disconnectExpected;
        private DISCONNECT disconnectMessage;

        public TestDisconnectHandler(final Waiter waiter, final boolean disconnectExpected) {
            this.waiter = waiter;
            this.disconnectExpected = disconnectExpected;
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) throws Exception {
            super.channelInactive(ctx);
            if (!disconnectExpected) {
                waiter.resume();
            }
        }

        @Override
        public void write(
                final ChannelHandlerContext channelHandlerContext, final Object o, final ChannelPromise channelPromise)
                throws Exception {
            if (o instanceof DISCONNECT) {
                disconnectMessage = (DISCONNECT) o;
                if (disconnectExpected) {
                    waiter.resume();
                } else {
                    waiter.fail();
                }
            }
            super.write(channelHandlerContext, o, channelPromise);
        }

        @Nullable
        public DISCONNECT getDisconnectMessage() {
            return disconnectMessage;
        }
    }
}
