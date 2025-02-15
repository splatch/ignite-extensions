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

package org.apache.spark.sql.ignite

import java.net.URI

import org.apache.ignite.internal.processors.query.QueryUtils.DFLT_SCHEMA
import org.apache.ignite.spark.IgniteDataFrameSettings.OPTION_TABLE
import org.apache.ignite.spark.IgniteContext
import org.apache.ignite.spark.IgniteDataFrameSettings._
import org.apache.ignite.spark.impl.IgniteSQLRelation.schema
import org.apache.ignite.{Ignite, IgniteException}
import org.apache.spark.sql.{AnalysisException, SparkSession}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog.CatalogTypes.TablePartitionSpec
import org.apache.spark.sql.catalyst.catalog.{ExternalCatalog, _}
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.util.StringUtils
import org.apache.spark.sql.types.StructType
import org.apache.ignite.spark.impl._
import org.apache.spark.sql.catalyst.catalog.SessionCatalog.DEFAULT_DATABASE
import org.apache.spark.sql.ignite.IgniteExternalCatalog.{IGNITE_PROTOCOL, IGNITE_URI, OPTION_GRID}

import scala.collection.JavaConversions._

/**
  * External catalog implementation to provide transparent access to SQL tables existed in Ignite.
  *
  * @param igniteContext Ignite context to provide access to Ignite instance.
  */
private[ignite] class IgniteExternalCatalog(igniteContext: IgniteContext)
    extends ExternalCatalog {
    /**
      * Default Ignite instance.
      */
    @transient private val ignite: Ignite = igniteContext.ignite()

    @transient private var currentSchema = DEFAULT_DATABASE

    /**
      * @param db Ignite instance name.
      * @return Description of Ignite instance.
      */
    override def getDatabase(db: String): CatalogDatabase =
        CatalogDatabase(db, db, IGNITE_URI, Map.empty)

    /**
      * Checks Ignite schema with provided name exists.
      *
      * @param schema Ignite schema name or <code>SessionCatalog.DEFAULT_DATABASE</code>.
      * @return True is Ignite schema exists.
      */
    override def databaseExists(schema: String): Boolean =
        schema == DEFAULT_DATABASE || allSchemas(ignite).exists(schema.equalsIgnoreCase)

    /**
      * @return List of all known Ignite schemas.
      */
    override def listDatabases(): Seq[String] =
        allSchemas(ignite)

    /**
      * @param pattern Pattern to filter databases names.
      * @return List of all known Ignite schema names filtered by pattern.
      */
    override def listDatabases(pattern: String): Seq[String] =
        StringUtils.filterPattern(listDatabases(), pattern)

    /**
      * Sets default Ignite schema.
      *
      * @param schema Name of Ignite schema.
      */
    override def setCurrentDatabase(schema: String): Unit =
        currentSchema = schema

    /** @inheritdoc */
    override def getTable(db: String, table: String): CatalogTable = getTableOption(db, table).get

    def getTableOption(db: String, tabName: String): Option[CatalogTable] = {
        val gridName = igniteName(ignite)

        val schemaName = schemaOrDefault(db, currentSchema)

        sqlTableInfo(ignite, tabName, Some(db)) match {
            case Some(table) ⇒
                val tableName = table.tableName

                Some(new CatalogTable(
                    identifier = new TableIdentifier(tableName, Some(schemaName)),
                    tableType = CatalogTableType.EXTERNAL,
                    storage = CatalogStorageFormat(
                        locationUri = Some(URI.create(IGNITE_PROTOCOL + schemaName + "/" + tableName)),
                        inputFormat = Some(FORMAT_IGNITE),
                        outputFormat = Some(FORMAT_IGNITE),
                        serde = None,
                        compressed = false,
                        properties = Map(
                            OPTION_GRID → gridName,
                            OPTION_TABLE → tableName)
                    ),
                    schema = schema(table),
                    provider = Some(FORMAT_IGNITE),
                    partitionColumnNames =
                        if (!allKeyFields(table).isEmpty)
                            allKeyFields(table).toSeq
                        else
                            Seq(table.keyFieldName),
                    bucketSpec = None))
            case None ⇒ None
        }
    }

    /** @inheritdoc */
    override def tableExists(db: String, table: String): Boolean =
        sqlTableExists(ignite, table, Some(schemaOrDefault(db, currentSchema)))

    /** @inheritdoc */
    override def listTables(db: String): Seq[String] = listTables(db, ".*")

    /** @inheritdoc */
    override def listTables(db: String, pattern: String): Seq[String] =
        StringUtils.filterPattern(
            cachesForSchema[Any,Any](ignite, Some(schemaOrDefault(db, currentSchema)))
                .flatMap(_.getQueryEntities.map(_.getTableName)), pattern)

    /** @inheritdoc */
    override def loadTable(db: String, table: String,
        loadPath: String, isOverwrite: Boolean, isSrcLocal: Boolean): Unit = { /* no-op */ }

    /** @inheritdoc */
    override def getPartition(db: String, table: String, spec: TablePartitionSpec): CatalogTablePartition = null

    /** @inheritdoc */
    override def getPartitionOption(db: String, table: String,
        spec: TablePartitionSpec): Option[CatalogTablePartition] = None

    /** @inheritdoc */
    override def listPartitionNames(db: String, table: String, partialSpec: Option[TablePartitionSpec]): Seq[String] = {
        sqlCacheName(ignite, table, Some(schemaOrDefault(db, currentSchema))).map { cacheName ⇒
            val parts = ignite.affinity(cacheName).partitions()

            (0 until parts).map(_.toString)
        }.getOrElse(Seq.empty)
    }

    /** @inheritdoc */
    override def listPartitions(db: String, table: String,
        partialSpec: Option[TablePartitionSpec]): Seq[CatalogTablePartition] = {

        val partitionNames = listPartitionNames(db, table, partialSpec)

        if (partitionNames.isEmpty)
            Seq.empty
        else {
            val cacheName = sqlCacheName(ignite, table, Some(schemaOrDefault(db, currentSchema))).get

            val aff = ignite.affinity[Any](cacheName)

            partitionNames.map { name ⇒
                val nodes = aff.mapPartitionToPrimaryAndBackups(name.toInt)

                if (nodes.isEmpty)
                    throw new AnalysisException(s"Nodes for parition is empty [grid=${ignite.name},table=$table,partition=$name].")

                CatalogTablePartition (
                    Map[String, String] (
                        "name" → name,
                        "primary" → nodes.head.id.toString,
                        "backups" → nodes.tail.map(_.id.toString).mkString(",")
                    ),
                    CatalogStorageFormat.empty
                )
            }
        }
    }

    /** @inheritdoc */
    override def listPartitionsByFilter(db: String,
        table: String,
        predicates: Seq[Expression],
        defaultTimeZoneId: String): Seq[CatalogTablePartition] =
        listPartitions(db, table, None)

    /** @inheritdoc */
    override def loadPartition(db: String,
        table: String,
        loadPath: String,
        partition: TablePartitionSpec, isOverwrite: Boolean,
        inheritTableSpecs: Boolean, isSrcLocal: Boolean): Unit = { /* no-op */ }

    /** @inheritdoc */
    override def loadDynamicPartitions(db: String, table: String,
        loadPath: String,
        partition: TablePartitionSpec, replace: Boolean,
        numDP: Int): Unit = { /* no-op */ }

    /** @inheritdoc */
    override def getFunction(db: String, funcName: String): CatalogFunction =
        throw new UnsupportedOperationException("unsupported")

    /** @inheritdoc */
    override def functionExists(db: String, funcName: String): Boolean = false

    /** @inheritdoc */
    override def listFunctions(db: String, pattern: String): Seq[String] = Seq.empty[String]

    /** @inheritdoc */
    override def alterDatabase(dbDefinition: CatalogDatabase): Unit =
        throw new UnsupportedOperationException("unsupported")

    /** @inheritdoc */
    override def alterFunction(db: String, funcDefinition: CatalogFunction): Unit =
        throw new UnsupportedOperationException("unsupported")

    /** @inheritdoc */
    override def alterTableStats(db: String, table: String, stats: Option[CatalogStatistics]): Unit =
        throw new UnsupportedOperationException("unsupported")

	/** @inheritdoc */
	override def alterTable(tableDefinition: CatalogTable): Unit =
		throw new UnsupportedOperationException("unsupported")

	/** @inheritdoc */
	override def alterTableDataSchema(db: String, table: String, schema: StructType): Unit =
		throw new UnsupportedOperationException("unsupported")

    /** @inheritdoc */
    override def createFunction(db: String, funcDefinition: CatalogFunction): Unit = { /* no-op */ }

    /** @inheritdoc */
    override def dropFunction(db: String, funcName: String): Unit = { /* no-op */ }

    /** @inheritdoc */
    override def renameFunction(db: String, oldName: String, newName: String): Unit = { /* no-op */ }

    /** @inheritdoc */
    override def createDatabase(dbDefinition: CatalogDatabase, ignoreIfExists: Boolean): Unit =
        throw new UnsupportedOperationException("unsupported")

    /** @inheritdoc */
    override def dropDatabase(db: String, ignoreIfNotExists: Boolean, cascade: Boolean): Unit =
        throw new UnsupportedOperationException("unsupported")

    /** @inheritdoc */
    override def createTable(tableDefinition: CatalogTable, ignoreIfExists: Boolean): Unit = {
        sqlTableInfo(ignite, tableDefinition.identifier.table, tableDefinition.identifier.database) match {
            case Some(_) ⇒
                /* no-op */

            case None ⇒
                val schema = tableDefinition.identifier.database

                if(schema.isDefined && !schema.contains(DFLT_SCHEMA) && !schema.contains(DEFAULT_DATABASE))
                    throw new IgniteException("Can only create new tables in PUBLIC schema, not " + schema.get)

                val props = tableDefinition.storage.properties

                QueryHelper.createTable(tableDefinition.schema,
                    tableDefinition.identifier.table,
                    props(OPTION_CREATE_TABLE_PRIMARY_KEY_FIELDS).split(","),
                    props.get(OPTION_CREATE_TABLE_PARAMETERS),
                    ignite)
        }
    }

    /** @inheritdoc */
    override def dropTable(db: String, tabName: String, ignoreIfNotExists: Boolean, purge: Boolean): Unit =
        sqlTableInfo(ignite, tabName, Some(schemaOrDefault(db, currentSchema))) match {
            case Some(table) ⇒
                val tableName = table.tableName

                QueryHelper.dropTable(tableName, ignite)

            case None ⇒
                if (!ignoreIfNotExists)
                    throw new IgniteException(s"Table $tabName doesn't exists.")
        }

    /** @inheritdoc */
    override def renameTable(db: String, oldName: String, newName: String): Unit =
        throw new UnsupportedOperationException("unsupported")

    /** @inheritdoc */
    override def createPartitions(db: String, table: String,
        parts: Seq[CatalogTablePartition],
        ignoreIfExists: Boolean): Unit =
        throw new UnsupportedOperationException("unsupported")

    /** @inheritdoc */
    override def dropPartitions(db: String, table: String,
        parts: Seq[TablePartitionSpec],
        ignoreIfNotExists: Boolean, purge: Boolean, retainData: Boolean): Unit =
        throw new UnsupportedOperationException("unsupported")

    /** @inheritdoc */
    override def renamePartitions(db: String, table: String,
        specs: Seq[TablePartitionSpec],
        newSpecs: Seq[TablePartitionSpec]): Unit =
        throw new UnsupportedOperationException("unsupported")

    /** @inheritdoc */
    override def alterPartitions(db: String, table: String,
        parts: Seq[CatalogTablePartition]): Unit =
        throw new UnsupportedOperationException("unsupported")

    override def getTablesByName(db: String, tables: Seq[String]): Seq[CatalogTable] = tables.map(getTable(db, _))

    override def listViews(db: String, pattern: String): Seq[String] = {
        throw new UnsupportedOperationException("unsupported") // FIXME
    }
}

object IgniteExternalCatalog {
    /**
      * Config option to specify named grid instance to connect when loading data.
      * For internal use only.
      *
      * @see [[org.apache.ignite.Ignite#name()]]
      */
    private[apache] val OPTION_GRID = "grid"

    /**
      * Location of ignite tables.
      */
    private[apache] val IGNITE_PROTOCOL = "ignite:/"

    /**
      * URI location of ignite tables.
      */
    private val IGNITE_URI = new URI(IGNITE_PROTOCOL)
}
