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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;
import javax.cache.Cache;
import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.springdata.examples.model.Person;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.domain.PageRequest;

/**
 * The example demonstrates how to interact with an Apache Ignite cluster by means of Spring Data API.
 *
 * Additional cluster nodes can be started with special configuration file which
 * enables P2P class loading: {@code 'ignite.{sh|bat} modules/spring-data-2.2/examples/config/example-spring-data.xml'}.
 */
public class SpringDataExample {
    /** Spring Application Context. */
    private static AnnotationConfigApplicationContext ctx;

    /** Ignite Spring Data repository. */
    private static PersonRepository repo;

    /**
     * Execute examples involving both approaches to configure Spring Data repository access to an Ignite cluster:
     *      through Ignite thin client and through Ignite node.
     * @param args Command line arguments, none required.
     */
    public static void main(String[] args) {
        try (Ignite ignored = startIgniteNode()) {
            // Ignite node instance is used to configure access to the Ignite cluster.
            doSpringDataExample(SpringApplicationConfiguration.class);

            // Ignite thin client instance is used to configure access to the Ignite cluster.
            doSpringDataExample(IgniteClientSpringApplicationConfiguration.class);
        }
    }

    /** Starts an Ignite node that simulates an Ignite cluster to which Spring Data repository will perform access. */
    private static Ignite startIgniteNode() {
        IgniteConfiguration cfg = new IgniteConfiguration()
            .setPeerClassLoadingEnabled(true)
            .setCacheConfiguration(new CacheConfiguration<Long, Person>("PersonCache")
                .setIndexedTypes(Long.class, Person.class));

        return Ignition.start(cfg);
    }

    /**
     * Performs basic Spring Data repository operation.
     *
     * @param springAppCfg Class of Spring application configuration that will be used for Spring context initialization.
     */
    private static void doSpringDataExample(Class<?> springAppCfg) {
        igniteSpringDataInit(springAppCfg);

        populateRepository();

        findPersons();

        queryRepository();

        System.out.println("\n>>> Cleaning out the repository...");

        repo.deleteAll();

        System.out.println("\n>>> Repository size: " + repo.count());

        // Destroying the context.
        ctx.destroy();
    }

    /**
     * Initializes Spring Data and Ignite repositories.
     *
     * @param springAppCfg Class of Spring application configuration that will be used for Spring context initialization.
     */
    private static void igniteSpringDataInit(Class<?> springAppCfg) {
        ctx = new AnnotationConfigApplicationContext();

        // Explicitly registering Spring configuration.
        ctx.register(springAppCfg);

        ctx.refresh();

        // Getting a reference to PersonRepository.
        repo = ctx.getBean(PersonRepository.class);
    }

    /**
     * Fills the repository in with sample data.
     */
    private static void populateRepository() {
        TreeMap<Long, Person> persons = new TreeMap<>();

        persons.put(1L, new Person(1L, 2000L, "John", "Smith", 15000, "Worked for Apple"));
        persons.put(2L, new Person(2L, 2000L, "Brad", "Pitt", 16000, "Worked for Oracle"));
        persons.put(3L, new Person(3L, 1000L, "Mark", "Tomson", 10000, "Worked for Sun"));
        persons.put(4L, new Person(4L, 2000L, "Erick", "Smith", 13000, "Worked for Apple"));
        persons.put(5L, new Person(5L, 1000L, "John", "Rozenberg", 25000, "Worked for RedHat"));
        persons.put(6L, new Person(6L, 2000L, "Denis", "Won", 35000, "Worked for CBS"));
        persons.put(7L, new Person(7L, 1000L, "Abdula", "Adis", 45000, "Worked for NBC"));
        persons.put(8L, new Person(8L, 2000L, "Roman", "Ive", 15000, "Worked for Sun"));

        // Adding data into the repository.
        repo.save(persons);

        System.out.println("\n>>> Added " + repo.count() + " Persons into the repository.");
    }

    /**
     * Gets a list of Persons using standard read operations.
     */
    private static void findPersons() {
        // Getting Person with specific ID.
        Person person = repo.findById(2L).orElse(null);

        System.out.println("\n>>> Found Person [id=" + 2L + ", val=" + person + "]");

        // Getting a list of Persons.

        ArrayList<Long> ids = new ArrayList<>();

        for (long i = 0; i < 5; i++)
            ids.add(i);

        Iterator<Person> persons = repo.findAllById(ids).iterator();

        System.out.println("\n>>> Persons list for specific ids: ");

        while (persons.hasNext())
            System.out.println("   >>>   " + persons.next());
    }

    /**
     * Execute advanced queries over the repository.
     */
    private static void queryRepository() {
        System.out.println("\n>>> Persons with name 'John':");

        List<Person> persons = repo.findByFirstName("John");

        for (Person person : persons)
            System.out.println("   >>>   " + person);

        Cache.Entry<Long, Person> topPerson = repo.findTopByLastNameLike("Smith");

        System.out.println("\n>>> Top Person with surname 'Smith': " + topPerson.getValue());

        List<Long> ids = repo.selectId(1000L, PageRequest.of(0, 4));

        System.out.println("\n>>> Persons working for organization with ID > 1000: ");

        for (Long id: ids)
            System.out.println("   >>>   [id=" + id + "]");
    }
}
