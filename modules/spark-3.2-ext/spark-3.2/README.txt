Apache Ignite Spark 3.2 Module
------------------------------

Apache Ignite provides an implementation of Spark RDD abstraction which enables easy access to Ignite caches.
Ignite RDD does not keep it's state in the memory of the Spark application and provides a view of the corresponding
Ignite cache. Depending on the chosen deployment mode this state may exist only during the lifespan of the Spark
application (embedded mode) or may exist outside of the Spark application (standalone mode), allowing seamless
sharing of the state between multiple Spark jobs.
------------------------------

Activate 'spark-3.2-dev-profile' profile to let IDEA load the module.
