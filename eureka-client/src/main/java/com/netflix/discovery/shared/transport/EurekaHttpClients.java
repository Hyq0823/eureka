/*
 * Copyright 2015 Netflix, Inc.
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

package com.netflix.discovery.shared.transport;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.resolver.AsyncResolver;
import com.netflix.discovery.shared.resolver.ClusterResolver;
import com.netflix.discovery.shared.resolver.EurekaEndpoint;
import com.netflix.discovery.shared.resolver.LegacyClusterResolver;
import com.netflix.discovery.shared.resolver.aws.ApplicationsResolver;
import com.netflix.discovery.shared.resolver.aws.AwsEndpoint;
import com.netflix.discovery.shared.resolver.aws.EurekaHttpResolver;
import com.netflix.discovery.shared.resolver.aws.ZoneAffinityClusterResolver;
import com.netflix.discovery.shared.transport.decorator.MetricsCollectingEurekaHttpClient;
import com.netflix.discovery.shared.transport.decorator.SessionedEurekaHttpClient;
import com.netflix.discovery.shared.transport.decorator.RedirectingEurekaHttpClient;
import com.netflix.discovery.shared.transport.decorator.RetryableEurekaHttpClient;
import com.netflix.discovery.shared.transport.decorator.ServerStatusEvaluators;
import com.netflix.discovery.shared.transport.decorator.SplitEurekaHttpClient;
import com.netflix.discovery.shared.transport.jersey.JerseyEurekaHttpClientFactory;

/**
 * @author Tomasz Bak
 */
public final class EurekaHttpClients {

    public static final long RECONNECT_INTERVAL_MINUTES = 30;

    private EurekaHttpClients() {
    }

    /**
     * Standard client, with legacy server resolver.
     */
    public static EurekaHttpClientFactory createStandardClientFactory(EurekaClientConfig clientConfig,
                                                                      InstanceInfo myInstanceInfo,
                                                                      ApplicationsResolver.ApplicationsSource applicationsSource,
                                                                      ScheduledExecutorService executorService,
                                                                      ThreadPoolExecutor threadPoolExecutor) {
        ClusterResolver resolver = createStandardClusterResolver(
                clientConfig, myInstanceInfo, applicationsSource, executorService, threadPoolExecutor
        );
        return createStandardClientFactory(clientConfig, myInstanceInfo, resolver);
    }

    /**
     * Standard client supports: registration/query connectivity split, connection re-balancing and retry.
     */
    static EurekaHttpClientFactory createStandardClientFactory(EurekaClientConfig clientConfig,
                                                               InstanceInfo myInstanceInfo,
                                                               final ClusterResolver<EurekaEndpoint> clusterResolver) {
        final TransportClientFactory jerseyFactory = JerseyEurekaHttpClientFactory.create(clientConfig, myInstanceInfo);
        final TransportClientFactory metricsFactory = MetricsCollectingEurekaHttpClient.createFactory(jerseyFactory);

        return new EurekaHttpClientFactory() {
            @Override
            public EurekaHttpClient newClient() {
                SessionedEurekaHttpClient registrationClient = new SessionedEurekaHttpClient(
                        RetryableEurekaHttpClient.createFactory(
                                clusterResolver,
                                RedirectingEurekaHttpClient.createFactory(metricsFactory),
                                ServerStatusEvaluators.legacyEvaluator()),
                        RECONNECT_INTERVAL_MINUTES * 60 * 1000
                );
                SessionedEurekaHttpClient queryClient = new SessionedEurekaHttpClient(
                        RetryableEurekaHttpClient.createFactory(
                                clusterResolver,
                                RedirectingEurekaHttpClient.createFactory(metricsFactory),
                                ServerStatusEvaluators.legacyEvaluator()),
                        RECONNECT_INTERVAL_MINUTES * 60 * 1000
                );

                return new SplitEurekaHttpClient(registrationClient, queryClient);
            }

            @Override
            public void shutdown() {
                jerseyFactory.shutdown();
                metricsFactory.shutdown();
            }
        };
    }

    static ClusterResolver<AwsEndpoint> createStandardClusterResolver(final EurekaClientConfig clientConfig,
                                                                      InstanceInfo myInstanceInfo,
                                                                      ApplicationsResolver.ApplicationsSource applicationsSource,
                                                                      ScheduledExecutorService executorService,
                                                                      ThreadPoolExecutor threadPoolExecutor) {
        String[] availZones = clientConfig.getAvailabilityZones(clientConfig.getRegion());
        String myZone = InstanceInfo.getZone(availZones, myInstanceInfo);
        ClusterResolver bootstrapResolver = new LegacyClusterResolver(clientConfig, myZone);

        EurekaHttpResolver remoteResolver = new EurekaHttpResolver(
                clientConfig,
                myInstanceInfo,
                bootstrapResolver,
                clientConfig.getReadClusterAppName()
        );

        ApplicationsResolver localResolver = new ApplicationsResolver(
                clientConfig,
                applicationsSource
        );

        ClusterResolver<AwsEndpoint> compoundResolver = new ClusterResolver<AwsEndpoint>() {
            @Override
            public String getRegion() {
                return clientConfig.getRegion();
            }

            @Override
            public List<AwsEndpoint> getClusterEndpoints() {
                List<AwsEndpoint> result = localResolver.getClusterEndpoints();
                if (result.isEmpty()) {
                    result = remoteResolver.getClusterEndpoints();
                }

                return result;
            }
        };

        return new AsyncResolver<>(
                clientConfig,
                new ZoneAffinityClusterResolver(compoundResolver, myZone, true),
                executorService,
                threadPoolExecutor
        );
    }
}
