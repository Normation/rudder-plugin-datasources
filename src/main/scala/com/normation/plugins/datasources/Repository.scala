/*
*************************************************************************************
* Copyright 2016 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.plugins.datasources

import com.normation.eventlog.EventActor
import com.normation.eventlog.ModificationId
import com.normation.inventory.domain.NodeId
import com.normation.plugins.datasources.DataSourceSchedule._
import com.normation.rudder.db.Doobie
import com.normation.rudder.db.Doobie._
import com.normation.rudder.domain.eventlog._
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.parameters.Parameter
import com.normation.utils.StringUuidGenerator
import doobie.imports._
import net.liftweb.common.Box
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.common.Loggable
import org.joda.time.DateTime
import scala.concurrent.duration._
import scalaz.{ Failure => _ }
import scalaz.Scalaz._

final case class PartialNodeUpdate(
    nodes        : Map[NodeId, NodeInfo] //the node to update
  , policyServers: Map[NodeId, NodeInfo] //there policy servers
  , parameters   : Set[Parameter]
)

trait DataSourceRepository {

  /*
   * Retrieve IDs. This is useful to know what are the reserved
   * node properties names.
   * We only need the id because for now, the semantic is that
   * as soon as the datasource is defined, even if disabled,
   * the property can not be interactively managed.
   */
  def getAllIds: Box[Set[DataSourceId]]

  def getAll : Box[Map[DataSourceId,DataSource]]

  def get(id : DataSourceId) : Box[Option[DataSource]]

  def save(source : DataSource) : Box[DataSource]

  def delete(id : DataSourceId) : Box[DataSourceId]
}

/*
 * A trait that exposes interactive callbacks for
 * data sources, i.e the method to call when one
 * need to update datasources.
 */
trait DataSourceUpdateCallbacks {

  def onNewNode(node: NodeId): Unit
  def onGenerationStarted(generationTimeStamp: DateTime): Unit
  def onUserAskUpdateAllNodes(actor: EventActor): Unit
  def onUserAskUpdateAllNodesFor(actor: EventActor, datasourceId: DataSourceId): Unit
  def onUserAskUpdateNode(actor: EventActor, nodeId: NodeId): Unit
  def onUserAskUpdateNodeFor(actor: EventActor, nodeId: NodeId, datasourceId: DataSourceId): Unit

  /*
   * Initialise all datasource so that they are ready to schedule their
   * first data fetch or wait for other callbacks.
   *
   * Non periodic data source won't be updated with that call.
   * Periodic one will be updated in a random interval between
   * 1 minute and min(period / 2, 30 minute) to avoid to extenghish
   * all resources on them.
   */
  def startAll(): Unit

  /*
   * Define all datasource scheduler from data sources in backend
   */
  def initialize(): Unit
}

trait NoopDataSourceCallbacks extends DataSourceUpdateCallbacks {
  def onNewNode(node: NodeId): Unit = ()
  def onGenerationStarted(generationTimeStamp: DateTime): Unit = ()
  def onUserAskUpdateAllNodes(actor: EventActor): Unit = ()
  def onUserAskUpdateAllNodesFor(actor: EventActor, datasourceId: DataSourceId): Unit = ()
  def onUserAskUpdateNode(actor: EventActor, nodeId: NodeId): Unit = ()
  def onUserAskUpdateNodeFor(actor: EventActor, nodeId: NodeId, datasourceId: DataSourceId): Unit = ()
  def startAll(): Unit = ()
  def initialize(): Unit = ()
}

class MemoryDataSourceRepository extends DataSourceRepository {

  private[this] var sources : Map[DataSourceId,DataSource] = Map()

  def getAllIds() = synchronized(Full(sources.keySet))

  def getAll() = synchronized(Full(sources))

  def get(id : DataSourceId) : Box[Option[DataSource]]= synchronized(Full(sources.get(id)))

  def save(source : DataSource) = synchronized {
    sources = sources +  ((source.id,source))
    Full(source)
  }

  def delete(id : DataSourceId) : Box[DataSourceId] = synchronized {
     sources = sources - (id)
     Full(id)
  }
}

/**
 * This is the higher level repository facade that is managine the "live"
 * instance of datasources, with the scheduling initialisation and update
 * on different repository action.
 *
 * It doesn't deal at all with the serialisation / deserialisation of data source
 * in data base.
 */
class DataSourceRepoImpl(
    backend: DataSourceRepository
  , fetch  : QueryDataSourceService
  , uuidGen: StringUuidGenerator
) extends DataSourceRepository with DataSourceUpdateCallbacks {

  private[this] var datasources = Map[DataSourceId, DataSourceScheduler]()

  // Initialize data sources scheduler, with all sources present in backend
  def initialize() = {
    getAll match {
      case Full(sources) =>
        for {
          (_,source) <- sources
        } yield {
          updateDataSourceScheduler(source, Some(source.runParam.schedule.duration))
        }
      case eb: EmptyBox  =>
        val e = eb ?~! "Error when initializing datasources"
        throw new RuntimeException(e.messageChain)
    }
  }
  // utility methods on datasources
  // stop a datasource - must be called when the datasource still in "datasources"
  private[this] def stop(id: DataSourceId) = {
    DataSourceLogger.debug(s"Stopping data source with id '${id.value}'")
    datasources.get(id) match {
      case None      => s"Data source with id ${id.value} was not found running"
      case Some(dss) => dss.cancel()
    }
  }
  // get datasource scheduler which match the condition
  private[this] def foreachDatasourceScheduler(condition: DataSource => Boolean)(action: DataSourceScheduler => Unit): Unit = {
    datasources.filter { case(_, dss) => condition(dss.datasource) }.foreach { case (_, dss) => action(dss) }
    datasources.foreach { case (_, dss) =>
      if(condition(dss.datasource)) {
        action(dss)
      } else {
        DataSourceLogger.debug(s"Skipping data source '${dss.datasource.name}' (${dss.datasource.id.value}): disabled or trigger not configured")
      }
    }

  }
  private[this] def updateDataSourceScheduler(source: DataSource, delay: Option[FiniteDuration]): Unit = {
    //need to cancel if one exists
    stop(source.id)
    // create live instance
    import monix.execution.Scheduler.Implicits.global
    val dss = new DataSourceScheduler(
          source
        , global
        , () => ModificationId(uuidGen.newUuid)
        , (cause: UpdateCause) => fetch.queryAll(source, cause)
    )
    datasources = datasources + (source.id -> dss)
    //start new
    delay match {
      case None    => dss.restartScheduleTask()
      case Some(d) => dss.startWithDelay(d)
    }
  }

  ///
  ///         DB READ ONLY
  /// read only method are just forwarder to backend
  ///
  override def getAllIds : Box[Set[DataSourceId]] = backend.getAllIds
  override def getAll : Box[Map[DataSourceId,DataSource]] = {
    DataSourceLogger.debug(s"Live data sources: ${datasources.map {case(_, dss) =>
      s"'${dss.datasource.name.value}' (${dss.datasource.id.value}): ${if(dss.datasource.enabled) "enabled" else "disabled"}"
    }.mkString("; ")}")

    backend.getAll
  }
  override def get(id : DataSourceId) : Box[Option[DataSource]] = backend.get(id)

  ///
  ///         DB WRITE ONLY
  /// write methods need to manage the "live" scheduler
  /// write methods need to be synchronised to keep consistancy in
  /// "live" scheduler and avoid a missed add in a doube save for ex.
  ///

  /*
   * on update, we need to stop the corresponding optionnaly existing
   * scheduler, and update with the new one.
   */
  override def save(source : DataSource) : Box[DataSource] = synchronized {
    //only create/update the "live" instance if the backend succeed
    backend.save(source) match {
      case eb: EmptyBox =>
        val msg = (eb ?~! s"Error when saving data source '${source.name.value}' (${source.id.value})").messageChain
        DataSourceLogger.error(msg)
        eb
      case Full(s)      =>
        updateDataSourceScheduler(source, delay = None)
        DataSourceLogger.debug(s"Data source '${source.name.value}' (${source.id.value}) udpated")
        Full(s)
    }
  }

  /*
   * delete need to clean existing live resource
   */
  override def delete(id : DataSourceId) : Box[DataSourceId] = synchronized {
    //start by cleaning
    stop(id)
    datasources = datasources - (id)
    backend.delete(id)
  }

  ///
  ///        CALLBACKS
  ///

  // no need to synchronize callback, they only
  // need a reference to the immutable datasources map.

  override def onNewNode(nodeId: NodeId): Unit = {
    DataSourceLogger.info(s"Fetching data from data source for new node '${nodeId}'")
    foreachDatasourceScheduler(ds => ds.enabled && ds.runParam.onNewNode){ dss =>
      val msg = s"Fetching data for data source ${dss.datasource.name.value} (${dss.datasource.id.value}) for new node '${nodeId.value}'"
      DataSourceLogger.debug(msg)
      //no scheduler reset for new node
      fetch.queryOne(dss.datasource, nodeId, UpdateCause(
          ModificationId(uuidGen.newUuid)
        , RudderEventActor
        , Some(msg)
      ))
    }
  }

  override def onGenerationStarted(generationTimeStamp: DateTime): Unit = {
    DataSourceLogger.info(s"Fetching data from data source for all node for generation ${generationTimeStamp.toString()}")
    foreachDatasourceScheduler(ds => ds.enabled && ds.runParam.onGeneration){ dss =>
      //for that one, do a scheduler restart
      val msg = s"Getting data for source ${dss.datasource.name.value} for policy generation started at ${generationTimeStamp.toString()}"
      DataSourceLogger.debug(msg)
      dss.doActionAndSchedule(fetch.queryAll(dss.datasource, UpdateCause(ModificationId(uuidGen.newUuid), RudderEventActor, Some(msg))
      ))
    }
  }

  override def onUserAskUpdateAllNodes(actor: EventActor): Unit = {
    DataSourceLogger.info(s"Fetching data from data sources for all node because ${actor.name} asked for it")
    fetchAllNode(actor, None)
  }

  override def onUserAskUpdateAllNodesFor(actor: EventActor, datasourceId: DataSourceId): Unit = {
    DataSourceLogger.info(s"Fetching data from data source '${datasourceId.value}' for all node because ${actor.name} asked for it")
    fetchAllNode(actor, Some(datasourceId))
  }

  // just to factorise the same code
  private[this] def fetchAllNode(actor: EventActor, datasourceId: Option[DataSourceId]) = {
    foreachDatasourceScheduler(ds => ds.enabled && datasourceId.fold(true)(id => ds.id == id)){ dss =>
      //for that one, do a scheduler restart
      val msg = s"Refreshing data from data source ${dss.datasource.name.value} on user ${actor.name} request"
      DataSourceLogger.debug(msg)
      dss.doActionAndSchedule(fetch.queryAll(dss.datasource, UpdateCause(ModificationId(uuidGen.newUuid), actor, Some(msg))
      ))
    }
  }

  override def onUserAskUpdateNode(actor: EventActor, nodeId: NodeId): Unit = {
    DataSourceLogger.info(s"Fetching data from data source for node '${nodeId.value}' because '${actor.name}' asked for it")
    fetchOneNode(actor, nodeId, None)
  }

  override def onUserAskUpdateNodeFor(actor: EventActor, nodeId: NodeId, datasourceId: DataSourceId): Unit = {
    DataSourceLogger.info(s"Fetching data from data source for node '${nodeId.value}' because '${actor.name}' asked for it")
    fetchOneNode(actor, nodeId, Some(datasourceId))
  }

  private[this] def fetchOneNode(actor: EventActor, nodeId: NodeId, datasourceId: Option[DataSourceId]) = {
    foreachDatasourceScheduler(ds => ds.enabled && datasourceId.fold(true)(id => ds.id == id)){ dss =>
      //for that one, no scheduler restart
      val msg = s"Fetching data for data source ${dss.datasource.name.value} (${dss.datasource.id.value}) for node '${nodeId.value}' on user '${actor.name}' request"
      DataSourceLogger.debug(msg)
      fetch.queryOne(dss.datasource, nodeId, UpdateCause(ModificationId(uuidGen.newUuid), RudderEventActor, Some(msg)))
    }
  }

  override def startAll() = {
    //sort by period (the least frequent the last),
    //then start them every minutes
    val toStart = datasources.values.flatMap { dss =>
      dss.datasource.runParam.schedule match {
        case Scheduled(d) => Some((d, dss))
        case _            => None
      }
    }.toList.sortBy( _._1.toMillis ).zipWithIndex

    toStart.foreach { case ((period, dss), i) =>
      dss.startWithDelay((i+1).minutes)
    }
  }

}

class DataSourceJdbcRepository(
    doobie    : Doobie
) extends DataSourceRepository with Loggable {

  import doobie._

  implicit val DataSourceComposite: Composite[DataSource] = {
    import com.normation.plugins.datasources.DataSourceJsonSerializer._
    import net.liftweb.json.compactRender
    import net.liftweb.json.parse
    Composite[(DataSourceId,String)].xmap(
        tuple => DataSourceExtractor.CompleteJson.extractDataSource(tuple._1,parse(tuple._2)) match {
          case Full(s) => s
          case eb : EmptyBox  =>
            val fail = eb ?~! s"Error when deserializing data source ${tuple._1} from following data: ${tuple._2}"
            throw new RuntimeException(fail.messageChain)
        }
      , source => (source.id, compactRender(serialize(source)))
      )
  }

  override def getAllIds(): Box[Set[DataSourceId]] = {
    query[DataSourceId]("""select id from datasources""").to[Set].attempt.transact(xa).unsafePerformSync
  }

  override def getAll(): Box[Map[DataSourceId,DataSource]] = {
    query[DataSource]("""select id, properties from datasources""").vector.map { _.map( ds => (ds.id,ds)).toMap }.attempt.transact(xa).unsafePerformSync
  }

  override def get(sourceId : DataSourceId): Box[Option[DataSource]] = {
    sql"""select id, properties from datasources where id = ${sourceId.value}""".query[DataSource].option.attempt.transact(xa).unsafePerformSync
  }

  override def save(source : DataSource): Box[DataSource] = {
    import net.liftweb.json.compactRender
    val json = compactRender(DataSourceJsonSerializer.serialize(source))
    val insert = """insert into datasources (id, properties) values (?, ?)"""
    val update = s"""update datasources set properties = ? where id = ?"""

    val sql = for {
      rowsAffected <- Update[(String,String)](update).run((json, source.id.value))
      result       <- rowsAffected match {
                        case 0 =>
                          logger.debug(s"source ${source.id} is not present in database, creating it")
                          Update[DataSource](insert).run(source)
                        case 1 => 1.point[ConnectionIO]
                        case n => throw new RuntimeException(s"Expected 0 or 1 change, not ${n} for ${source.id}")
                      }
    } yield {
      result
    }


    DataSource.reservedIds.get(source.id) match {
      case None =>
        sql.map(_ => source).attempt.transact(xa).run

      case Some(msg) =>
        Failure(s"You can't use the reserved data sources id '${source.id.value}': ${msg}")
    }
  }

  override def delete(sourceId : DataSourceId): Box[DataSourceId] = {
    val query = sql"""delete from datasources where id = ${sourceId}"""
    query.update.run.map(_ => sourceId).attempt.transact(xa).unsafePerformSync
  }

}
