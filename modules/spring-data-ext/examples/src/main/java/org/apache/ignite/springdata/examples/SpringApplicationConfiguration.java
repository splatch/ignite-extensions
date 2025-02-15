/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.springdata.examples;

import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.springdata.repository.IgniteRepository;
import org.apache.ignite.springdata.repository.config.EnableIgniteRepositories;
import org.apache.ignite.springdata.repository.support.IgniteRepositoryFactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Every {@link IgniteRepository} is bound to a specific Apache Ignite that it communicates to in order to mutate and
 * read data via Spring Data API. To pass an instance of Apache Ignite cache to an {@link IgniteRepository} it's
 * required to initialize {@link IgniteRepositoryFactoryBean} with one of the following:
 * <ul>
 * <li>{@link Ignite} instance bean</li>
 * <li>{@link IgniteConfiguration} bean</li>
 * <li>A path to Ignite's Spring XML configuration named "igniteSpringCfgPath"</li>
 * <ul/>
 * In this example the first approach is utilized.
 */
@Configuration
@EnableIgniteRepositories
public class SpringApplicationConfiguration {
    /**
     * Creating Apache Ignite instance bean. A bean will be passed to {@link IgniteRepositoryFactoryBean} to initialize
     * all Ignite based Spring Data repositories and connect to a cluster.
     *
     * @return Ignite instance.
     */
    @Bean
    public Ignite igniteInstance() {
        return Ignition.start("modules/spring-data-2.2-ext/examples/config/example-spring-data.xml");
    }
}
