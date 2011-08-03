/*
 * Copyright (c) 2010-2010 LinkedIn, Inc
 * Portions Copyright (c) 2011 Yan Pujante
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.linkedin.glu.orchestration.engine.agents

import org.linkedin.glu.agent.api.Agent
import org.linkedin.glu.agent.api.MountPoint
import org.linkedin.glu.agent.rest.client.AgentFactory
import org.linkedin.glu.agent.tracker.AgentInfo
import org.linkedin.glu.agent.tracker.MountPointInfo
import org.linkedin.glu.provisioner.core.model.SystemEntry
import org.linkedin.glu.provisioner.core.model.SystemModel
import org.linkedin.glu.orchestration.engine.fabric.Fabric
import org.linkedin.glu.orchestration.engine.tracker.TrackerService
import org.linkedin.groovy.util.io.DataMaskingInputStream
import org.linkedin.groovy.util.state.StateMachine
import org.linkedin.groovy.util.state.StateMachineImpl
import org.linkedin.util.lang.LangUtils
import org.linkedin.glu.orchestration.engine.authorization.AuthorizationService
import org.linkedin.util.annotations.Initializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.linkedin.glu.orchestration.engine.action.descriptor.AgentURIProvider

/**
 * @author ypujante
 */
class AgentsServiceImpl implements AgentsService, AgentURIProvider
{
  public static final String MODULE = AgentsServiceImpl.class.getName ();
  public static final Logger log = LoggerFactory.getLogger(MODULE);

  private final StateMachine stateMachine =
    new StateMachineImpl(transitions: Agent.DEFAULT_TRANSITIONS)

  private final def availableStates = stateMachine.availableStates as Set

  // will be dependency injected
  @Initializable(required = true)
  AgentFactory agentFactory

  @Initializable(required = true)
  TrackerService trackerService

  @Initializable
  AuthorizationService authorizationService

  @Override
  URI getAgentURI(String fabric, String agent) throws NoSuchAgentException
  {
    AgentInfo info = trackerService.getAgentInfo(fabric, agent)
    if(!info)
      throw new NoSuchAgentException(agent)
    return info.getURI()
  }

  @Override
  URI findAgentURI(String fabric, String agent)
  {
    return trackerService.getAgentInfo(fabric, agent)?.URI
  }

  def getAllInfosWithAccuracy(Fabric fabric)
  {
    return trackerService.getAllInfosWithAccuracy(fabric)
  }

  Map<String, AgentInfo> getAgentInfos(Fabric fabric)
  {
    return trackerService.getAgentInfos(fabric)
  }

  AgentInfo getAgentInfo(Fabric fabric, String agentName)
  {
    return trackerService.getAgentInfo(fabric, agentName)
  }

  Map<MountPoint, MountPointInfo> getMountPointInfos(Fabric fabric, String agentName)
  {
    trackerService.getMountPointInfos(fabric, agentName)
  }

  MountPointInfo getMountPointInfo(Fabric fabric, String agentName, mountPoint)
  {
    trackerService.getMountPointInfo(fabric, agentName, mountPoint)
  }

  def getFullState(args)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      agent.getFullState(args)
    }
  }

  def clearError(args)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      agent.clearError(args)
    }
  }

  def uninstallScript(args)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      moveToState(agent, args.mountPoint, StateMachine.NONE, args.timeout)
      agent.uninstallScript(args)
    }
  }

  def forceUninstallScript(args)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      agent.uninstallScript(*:args, force: true)
    }
  }

  def interruptAction(args)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      agent.interruptAction(args)
      agent.waitForState(mountPoint: args.mountPoint,
                         state: args.state,
                         timeout: args.timeout)
    }
  }

  def ps(args)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      agent.ps()
    }
  }

  def sync(args)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      agent.sync()
    }
  }

  def kill(args)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      agent.kill(args.pid as long, args.signal as int)
    }
  }

  void tailLog(args, Closure closure)
  {
    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      closure(agent.tailAgentLog(args))
    }
  }

  void streamFileContent(args, Closure closure)
  {
    authorizationService?.checkStreamFileContent(args.location)

    withRemoteAgent(args.fabric, args.id) { Agent agent ->
      def res = agent.getFileContent(args)
      if(res instanceof InputStream) {
        res = new DataMaskingInputStream(res)
      }

      closure(res)
    }
  }

  /**
   * Builds the current system model based on the live data from ZooKeeper
   */
  SystemModel getCurrentSystemModel(Fabric fabric)
  {
    def allInfosAndAccuracy = getAllInfosWithAccuracy(fabric)
    def agents = allInfosAndAccuracy.allInfos
    def accuracy = allInfosAndAccuracy.accuracy

    SystemModel systemModel = new SystemModel(fabric: fabric.name)

    // 1. add the agent tags
    agents.values().each { agent ->
      def agentName = agent.info.agentName
      def agentTags = agent.info.tags
      
      if(agentTags)
        systemModel.addAgentTags(agentName, agentTags)
    }

    // 2. add entries
    agents.values().each { agent ->
      def agentName = agent.info.agentName

      if(agent.mountPoints)
      {
        agent.mountPoints.values().each { MountPointInfo mp ->
          systemModel.addEntry(createSystemEntry(agentName, mp))
        }
      }
      else
      {
        // empty agent
        SystemEntry emptyAgentEntry = new SystemEntry(agent: agentName)
        emptyAgentEntry.metadata.emptyAgent = true
        emptyAgentEntry.metadata.currentState = 'NA'
        systemModel.addEntry(emptyAgentEntry)
      }
    }

    systemModel.metadata.accuracy = accuracy

    return systemModel
  }

  /**
   * Create the system entry for the given agent and mountpoint.
   */
  protected SystemEntry createSystemEntry(agentName, MountPointInfo mp)
  {
    SystemEntry se = new SystemEntry()

    se.agent = agentName
    se.mountPoint = mp.mountPoint.toString()
    se.parent = mp.parent
    Map data = LangUtils.deepClone(mp.data)
    se.script = data?.scriptDefinition?.scriptFactory?.location

    // all the necessary values are stored in the init parameters
    data?.scriptDefinition?.initParameters?.each { k, v ->
      if(v != null)
      {
        switch(k)
        {
          case 'metadata':
            se.metadata = v
            break

          case 'tags':
            se.setTags(v)
            break

          default:
            se.initParameters[k] = v
            break
        }
      }
    }

    se.metadata.currentState = mp.currentState
    se.entryState = mp.currentState
    if(mp.transitionState)
      se.metadata.transitionState = mp.transitionState
    if(mp.error)
      se.metadata.error = mp.error
    se.metadata.modifiedTime = mp.modifiedTime
    if(data?.scriptState)
    {
      se.metadata.scriptState = data.scriptState
    }
    
    return se
  }

  protected def moveToState(agent, mountPoint, toState, timeout)
  {
    def state = agent.getState(mountPoint: mountPoint)

    if(state.error)
    {
      agent.clearError(mountPoint: mountPoint)
    }

    def path = stateMachine.findShortestPath(state.currentState, toState)

    path.each { transition ->
      agent.executeAction(mountPoint: mountPoint,
                          action: transition.action)
      agent.waitForState(mountPoint: mountPoint,
                         state: transition.to,
                         timeout: timeout)
    }
  }

  protected def withRemoteAgent(Fabric fabric, String agentName, Closure closure)
  {
    AgentInfo info = getAgentInfo(fabric, agentName)

    if(!info)
      throw new NoSuchAgentException(agentName)
    
    agentFactory.withRemoteAgent(info.getURI()) { Agent agent ->
      closure(agent)
    }
  }

}
