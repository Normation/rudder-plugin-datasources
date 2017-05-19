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

import com.normation.inventory.domain.NodeId
import com.normation.rudder.domain.nodes.CompareProperties
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.rudder.domain.parameters.Parameter
import com.normation.rudder.repository.RoParameterRepository
import com.normation.rudder.repository.WoNodeRepository
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.services.policies.InterpolatedValueCompiler
import monix.eval.Task
import monix.eval.TaskSemaphore
import monix.execution.Scheduler
import net.liftweb.common.Box
import net.liftweb.common.Empty
import net.liftweb.common.EmptyBox
import net.liftweb.common.Failure
import net.liftweb.common.Full
import scala.concurrent.Await

/*
 * This file contain the hight level logic to update
 * datasources by name:
 * - get the datasource by name,
 * - get the list of nodes to udpate and there context,
 * - update all nodes
 * - (event log are generated because we are just changing node properties,
 *   so same behaviour)
 */
trait QueryDataSourceService {
  /**
   * Here, we query the provided datasource and update
   * all the node with the correct logic.
   *
   * An other service is in charge to retrieve the datasource by
   * name, and correctly handle the case where the datasource was
   * deleted.
   */
  def queryAll(datasource: DataSource, cause: UpdateCause): Box[Set[NodeId]]

  /**
   * A version that use provided nodeinfo / parameters to only query a subpart of nodes
   */
  def querySubset(datasource: DataSource, info: PartialNodeUpdate, cause: UpdateCause): Box[Set[NodeId]]

  /**
   * A version that only query one node - do not use if you want to query several nodes
   */
  def queryOne(datasource: DataSource, nodeId: NodeId, cause: UpdateCause): Box[NodeId]
}

class HttpQueryDataSourceService(
    nodeInfo        : NodeInfoService
  , parameterRepo   : RoParameterRepository
  , nodeRepository  : WoNodeRepository
  , interpolCompiler: InterpolatedValueCompiler
) extends QueryDataSourceService {

  val getHttp = new GetDataset(interpolCompiler)

  /*
   * We need a scheduler tailored for I/O, we are mostly doing http requests and
   * database things here
   */
  import monix.execution.schedulers.ExecutionModel
  implicit lazy val scheduler = Scheduler.io(executionModel = ExecutionModel.AlwaysAsyncExecution)

  override def queryAll(datasource: DataSource, cause: UpdateCause): Box[Set[NodeId]] = {
    query[Set[NodeId]]("fetch data for all node", datasource, cause
        , (d:DataSourceType.HTTP) => queryAllByNode(datasource.id, d, cause)
        , (d:DataSourceType.HTTP) => queryAllByNode(datasource.id, d, cause)
        , s"All nodes data updated from data source '${datasource.name.value}' (${datasource.id.value})"
        , s"Error when fetching data from data source '${datasource.name.value}' (${datasource.id.value}) for all nodes"
    )
  }

  override def querySubset(datasource: DataSource, info: PartialNodeUpdate, cause: UpdateCause): Box[Set[NodeId]] = {
    query[Set[NodeId]](s"fetch data for a set of ${info.nodes.size}", datasource, cause
        , (d:DataSourceType.HTTP) => querySubsetByNode(datasource.id, d, info, cause)
        , (d:DataSourceType.HTTP) => querySubsetByNode(datasource.id, d, info, cause)
        , s"Requested nodes data updated from data source '${datasource.name.value}' (${datasource.id.value})"
        , s"Error when fetching data from data source '${datasource.name.value}' (${datasource.id.value}) for requested nodes"
    )
  }

  override def queryOne(datasource: DataSource, nodeId: NodeId, cause: UpdateCause): Box[NodeId] = {
    query[NodeId](s"fetch data for node '${nodeId.value}'", datasource, cause
        , (d:DataSourceType.HTTP) => queryNodeByNode(datasource.id, d, nodeId, cause)
        , (d:DataSourceType.HTTP) => queryNodeByNode(datasource.id, d, nodeId, cause)
        , s"Data for node '${nodeId.value}' updated from data source '${datasource.name.value}' (${datasource.id.value})"
        , s"Error when fetching data from data source '${datasource.name.value}' (${datasource.id.value}) for node '${nodeId.value}'"
    )
  }

  private[this] def query[T](
      actionName: String
    , datasource: DataSource
    , cause     : UpdateCause
    , oneByOne  : DataSourceType.HTTP => Box[T]
    , allInOne  : DataSourceType.HTTP => Box[T]
    , successMsg: String
    , errorMsg  : String
  ): Box[T] = {
    // We need to special case by type of datasource
    val time_0 = System.currentTimeMillis
    val res = datasource.sourceType match {
      case t:DataSourceType.HTTP =>
        (t.requestMode match {
          case HttpRequestMode.OneRequestByNode         => oneByOne
          case HttpRequestMode.OneRequestAllNodes(_, _) => allInOne
        })(t)
    }
    DataSourceTimingLogger.debug(s"[${System.currentTimeMillis-time_0} ms] '${actionName}' for data source '${datasource.name.value}' (${datasource.id.value})")
    res match {
      case eb: EmptyBox =>
        val e = (eb ?~! errorMsg)
        DataSourceLogger.error(e.messageChain)
        e.rootExceptionCause.foreach(ex =>
          DataSourceLogger.debug("Exception was:", ex)
        )
      case _ =>
        DataSourceLogger.trace(successMsg)
    }
    res
  }

  private[this] def buildOneNodeTask(
      datasourceId: DataSourceId
    , datasource    : DataSourceType.HTTP
    , nodeInfo      : NodeInfo
    , policyServers : Map[NodeId, NodeInfo]
    , parameters    : Set[Parameter]
    , cause         : UpdateCause
  ): Task[Box[NodeId]] = {
    Task(
      (for {
        policyServer <- (policyServers.get(nodeInfo.policyServerId) match {
                          case None    => Failure(s"PolicyServer with ID '${nodeInfo.policyServerId.value}' was not found for node '${nodeInfo.hostname}' ('${nodeInfo.id.value}'). Abort.")
                          case Some(p) => Full(p)
                        })
                        //connection timeout: 5s ; getdata timeout: freq ?
        optProperty  <- getHttp.getNode(datasourceId, datasource, nodeInfo, policyServer, parameters, datasource.requestTimeOut, datasource.requestTimeOut)
                        //on none, don't update anything, the life is wonderful
        nodeId       <- optProperty match {
                          case None           => Full(nodeInfo.id)
                          case Some(property) =>
                            for {
                              newProps     <- CompareProperties.updateProperties(nodeInfo.properties, Some(Seq(property)))
                              newNode      =  nodeInfo.node.copy(properties = newProps)
                              nodeUpdated  <- nodeRepository.updateNode(newNode, cause.modId, cause.actor, cause.reason) ?~! s"Cannot save value for node '${nodeInfo.id.value}' for property '${property.name}'"
                            } yield {
                              nodeUpdated.id
                            }
                        }
      } yield {
        nodeId
      }) match {
        case Failure(msg, x, y) => Failure(s"Error when getting data from datasource '${datasourceId.value}' for node ${nodeInfo.hostname} (${nodeInfo.id.value}): ${msg}", x, y)
        case x                  => x
      }
    )
  }

  def querySubsetByNode(datasourceId: DataSourceId, datasource: DataSourceType.HTTP, info: PartialNodeUpdate, cause: UpdateCause)(implicit scheduler: Scheduler): Box[Set[NodeId]] = {
    import com.normation.utils.Control.bestEffort
    import net.liftweb.util.Helpers.tryo

    def tasks(nodes: Map[NodeId, NodeInfo], policyServers: Map[NodeId, NodeInfo], parameters: Set[Parameter]): Task[List[Box[NodeId]]] = {

      /*
       * Here, we are executing all the task (one by node) in parallel. We want to limit the number of
       * parallel task, especially to limit the number of output request. It is not very fair to
       * do hundreds of simultaneous requests to the output server (and that make tests on macos
       * fail, see: http://www.rudder-project.org/redmine/issues/10341)
       */
      val semaphore = TaskSemaphore(maxParallelism = 50)

      Task.gatherUnordered(nodes.values.map { nodeInfo =>
        semaphore.greenLight(buildOneNodeTask(datasourceId, datasource, nodeInfo, policyServers, parameters, cause))
      })
    }

    // give a timeout for the whole tasks sufficiently large, but that won't overlap too much on following runs
    val timeout = datasource.requestTimeOut

    for {
      updated       <- tryo(Await.result(tasks(info.nodes, info.policyServers, info.parameters).runAsync, timeout))
      gatherErrors  <- compactFailure(bestEffort(updated)(identity).map( _.toSet ))
    } yield {
      gatherErrors
    }
  }

  def queryAllByNode(datasourceId: DataSourceId, datasource: DataSourceType.HTTP, cause: UpdateCause)(implicit scheduler: Scheduler): Box[Set[NodeId]] = {
    for {
      nodes         <- nodeInfo.getAll()
      policyServers  = nodes.filter { case (_, n) => n.isPolicyServer }
      parameters    <- parameterRepo.getAllGlobalParameters.map( _.toSet[Parameter] )
      updated       <- querySubsetByNode(datasourceId, datasource, PartialNodeUpdate(nodes, policyServers, parameters), cause)
    } yield {
      updated
    }
  }

  def queryNodeByNode(datasourceId: DataSourceId, datasource: DataSourceType.HTTP, nodeId: NodeId, cause: UpdateCause)(implicit scheduler: Scheduler): Box[NodeId] = {
    import net.liftweb.util.Helpers.tryo
    for {
      allNodes      <- nodeInfo.getAll()
      node          <- allNodes.get(nodeId) match {
                         case None => Failure(s"The node with id '${nodeId.value}' was not found")
                         case Some(n) => Full(n)
                       }
      policyServers  = allNodes.filterKeys( _ == node.policyServerId)
      parameters    <- parameterRepo.getAllGlobalParameters.map( _.toSet[Parameter] )
      updated       <- tryo(Await.result(buildOneNodeTask(datasourceId, datasource, node, policyServers, parameters, cause).runAsync, datasource.requestTimeOut))
      result        <- updated
    } yield {
      result
    }
  }

  // compact format a Failure(msg1, Failure(msg2, ...)) in to a Failure("msg1; msg2")
  private[this] def compactFailure[T](b: Box[T]): Box[T] = {
    b match {
      case f:Failure =>
        Failure(f.messageChain.replaceAll("<-", ";"), f.rootExceptionCause, Empty)
      case x => x
    }
  }

}
