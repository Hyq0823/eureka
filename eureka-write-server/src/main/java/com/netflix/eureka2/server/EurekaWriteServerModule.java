/*
 * Copyright 2014 Netflix, Inc.
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

package com.netflix.eureka2.server;

import com.google.inject.AbstractModule;
import com.google.inject.name.Names;
import com.netflix.eureka2.config.EurekaRegistryConfig;
import com.netflix.eureka2.metric.SerializedTaskInvokerMetrics;
import com.netflix.eureka2.registry.SourcedEurekaRegistry;
import com.netflix.eureka2.registry.SourcedEurekaRegistryImpl;
import com.netflix.eureka2.server.audit.AuditServiceController;
import com.netflix.eureka2.server.config.WriteServerConfig;
import com.netflix.eureka2.server.metric.InterestChannelMetrics;
import com.netflix.eureka2.server.config.EurekaCommonConfig;
import com.netflix.eureka2.server.config.EurekaServerConfig;
import com.netflix.eureka2.server.metric.RegistrationChannelMetrics;
import com.netflix.eureka2.server.metric.ReplicationChannelMetrics;
import com.netflix.eureka2.server.metric.WriteServerMetricFactory;
import com.netflix.eureka2.registry.PreservableEurekaRegistry;
import com.netflix.eureka2.registry.eviction.EvictionQueue;
import com.netflix.eureka2.registry.eviction.EvictionQueueImpl;
import com.netflix.eureka2.registry.eviction.EvictionStrategy;
import com.netflix.eureka2.registry.eviction.EvictionStrategyProvider;
import com.netflix.eureka2.server.service.EurekaServerHealthService;
import com.netflix.eureka2.server.service.EurekaWriteServerHealthService;
import com.netflix.eureka2.server.service.SelfRegistrationService;
import com.netflix.eureka2.server.service.replication.ReplicationService;
import com.netflix.eureka2.server.spi.ExtensionContext;
import com.netflix.eureka2.server.transport.tcp.discovery.TcpDiscoveryServer;
import com.netflix.eureka2.server.transport.tcp.registration.TcpRegistrationServer;
import com.netflix.eureka2.server.transport.tcp.replication.TcpReplicationServer;
import com.netflix.eureka2.metric.MessageConnectionMetrics;
import io.reactivex.netty.metrics.MetricEventsListenerFactory;
import io.reactivex.netty.servo.ServoEventsListenerFactory;

/**
 * @author Tomasz Bak
 */
public class EurekaWriteServerModule extends AbstractModule {

    private final WriteServerConfig config;

    public EurekaWriteServerModule() {
        this(null);
    }

    public EurekaWriteServerModule(WriteServerConfig config) {
        this.config = config;
    }

    @Override
    public void configure() {
        if (config == null) {
            bind(WriteServerConfig.class).asEagerSingleton();
            bind(EurekaRegistryConfig.class).to(WriteServerConfig.class);
        } else {
            bind(EurekaRegistryConfig.class).toInstance(config);
            bind(EurekaCommonConfig.class).toInstance(config);
            bind(EurekaServerConfig.class).toInstance(config);
            bind(WriteServerConfig.class).toInstance(config);
        }

        bind(SerializedTaskInvokerMetrics.class).toInstance(new SerializedTaskInvokerMetrics("registry"));

        bind(SourcedEurekaRegistry.class).annotatedWith(Names.named("delegate")).to(SourcedEurekaRegistryImpl.class).asEagerSingleton();
        bind(SourcedEurekaRegistry.class).to(PreservableEurekaRegistry.class).asEagerSingleton();
        bind(EvictionQueue.class).to(EvictionQueueImpl.class).asEagerSingleton();
        bind(EvictionStrategy.class).toProvider(EvictionStrategyProvider.class);
        bind(AuditServiceController.class).asEagerSingleton();

        bind(SelfRegistrationService.class).to(EurekaServerHealthService.class);
        bind(EurekaServerHealthService.class).to(EurekaWriteServerHealthService.class).asEagerSingleton();

        bind(MetricEventsListenerFactory.class).annotatedWith(Names.named("registration")).toInstance(new ServoEventsListenerFactory("registration-rx-client-", "registration-rx-server-"));
        bind(MetricEventsListenerFactory.class).annotatedWith(Names.named("discovery")).toInstance(new ServoEventsListenerFactory("discovery-rx-client-", "discovery-rx-server-"));
        bind(MetricEventsListenerFactory.class).annotatedWith(Names.named("replication")).toInstance(new ServoEventsListenerFactory("replication-rx-client-", "replication-rx-server-"));
        bind(TcpRegistrationServer.class).asEagerSingleton();
        bind(TcpDiscoveryServer.class).asEagerSingleton();
        bind(TcpReplicationServer.class).asEagerSingleton();

        bind(ReplicationService.class).asEagerSingleton();

        bind(ExtensionContext.class).asEagerSingleton();

        // Metrics
        bind(MessageConnectionMetrics.class).annotatedWith(Names.named("registration")).toInstance(new MessageConnectionMetrics("registration"));
        bind(MessageConnectionMetrics.class).annotatedWith(Names.named("replication")).toInstance(new MessageConnectionMetrics("replication"));
        bind(MessageConnectionMetrics.class).annotatedWith(Names.named("discovery")).toInstance(new MessageConnectionMetrics("discovery"));

//        bind(MessageConnectionMetrics.class).annotatedWith(Names.named("clientRegistration")).toInstance(new MessageConnectionMetrics("clientRegistration"));
//        bind(MessageConnectionMetrics.class).annotatedWith(Names.named("clientDiscovery")).toInstance(new MessageConnectionMetrics("clientDiscovery"));
        bind(MessageConnectionMetrics.class).annotatedWith(Names.named("clientReplication")).toInstance(new MessageConnectionMetrics("clientReplication"));

        bind(RegistrationChannelMetrics.class).toInstance(new RegistrationChannelMetrics());
        bind(ReplicationChannelMetrics.class).toInstance(new ReplicationChannelMetrics());
        bind(InterestChannelMetrics.class).toInstance(new InterestChannelMetrics());

        bind(WriteServerMetricFactory.class).asEagerSingleton();
    }
}
