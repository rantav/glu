/*
 * Copyright (c) 2011 Yan Pujante
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

package org.linkedin.glu.orchestration.engine.deployment

import org.linkedin.glu.provisioner.core.environment.Environment
import org.linkedin.glu.provisioner.core.model.SystemModel
import org.linkedin.glu.provisioner.impl.agent.DefaultDescriptionProvider
import org.linkedin.glu.provisioner.plan.api.IPlanExecutionProgressTracker
import org.linkedin.glu.provisioner.plan.api.IStep
import org.linkedin.glu.provisioner.plan.api.LeafStep
import org.linkedin.glu.provisioner.plan.api.Plan
import org.linkedin.glu.orchestration.engine.agents.AgentsService
import org.linkedin.glu.orchestration.engine.authorization.AuthorizationService
import org.linkedin.glu.orchestration.engine.fabric.Fabric
import org.linkedin.glu.orchestration.engine.fabric.FabricService
import org.linkedin.util.annotations.Initializable
import org.linkedin.util.clock.Clock
import org.linkedin.util.clock.SystemClock
import org.linkedin.util.clock.Timespan
import org.linkedin.glu.provisioner.core.action.IDescriptionProvider
import org.linkedin.glu.orchestration.engine.delta.DeltaMgr
import org.linkedin.glu.orchestration.engine.delta.SystemModelDelta
import org.linkedin.glu.orchestration.engine.planner.Planner
import org.linkedin.glu.provisioner.plan.api.IStep.Type
import org.linkedin.glu.orchestration.engine.action.descriptor.ActionDescriptor

/**
 * System service.
 *
 * @author ypujante@linkedin.com */
class DeploymentServiceImpl implements DeploymentService
{
  // we keep entries in the plan cache no more than 5m
  @Initializable
  Timespan planCacheTimeout = Timespan.parse('5m')

  @Initializable
  Clock clock = SystemClock.INSTANCE

  @Initializable(required = true)
  AgentsService agentsService

  @Initializable(required = true)
  FabricService fabricService

  @Initializable(required = true)
  DeltaMgr deltaMgr

  @Initializable(required = true)
  Planner planner

  @Initializable(required = true)
  Deployer deployer

  @Initializable(required = true)
  DeploymentStorage deploymentStorage

  @Initializable
  AuthorizationService authorizationService

  @Initializable
  IDescriptionProvider descriptionProvider = DefaultDescriptionProvider.INSTANCE

  private Map<String, CurrentDeployment> _deployments = [:]
  private Map<String, Plan> _plans = [:]

  /**
   * Compute deployment plans between the system provided (params.system) and the current
   * system.
   */
  Collection<Plan<ActionDescriptor>> computeDeployPlans(params, def metadata)
  {
    computeDeploymentPlans(params, metadata) { Type type, SystemModelDelta delta ->
      planner.computeDeploymentPlan(type, delta)
    }
  }

  private Collection<Plan<ActionDescriptor>> computeDeploymentPlans(params,
                                                                    def metadata,
                                                                    Closure closure)
  {
    SystemModel expectedModel = params.system

    if(!expectedModel)
      return null

    if(expectedModel.fabric != params.fabric?.name)
      throw new IllegalArgumentException("mismatch fabric: ${expectedModel.fabric} != ${params.fabric?.name}")

    SystemModel currentModel = agentsService.getCurrentSystemModel(params.fabric)

    // 1. compute delta between expectedModel and currentModel
    SystemModelDelta delta = deltaMgr.computeDelta(expectedModel, currentModel)

    if(!delta.hasDelta())
      return []

    Collection<Type> types = []
    if(params.type)
      types << params.type
    else
      types = [Type.SEQUENTIAL, Type.PARALLEL]

    if(metadata == null)
      metadata = [:]

    types.collect { Type type ->
      // 2. compute the deployment plan for the delta (and the given type)
      Plan<ActionDescriptor> plan = closure(type, delta)

      // 3. set name and metadata for the plan
      plan.setMetadata('fabric', expectedModel.fabric)
      plan.setMetadata('systemId', expectedModel.id)
      plan.setMetadata(metadata)

      plan.step?.metadata?.putAll(metadata)

      def name = params.name ?: metadata.name
      if(!name)
      {
        name = metadata.collect { k,v -> "${k}=$v"}.join(' - ')
      }
      name = "${name} - ${type}".toString()
      plan.name = name

      return plan
    }.findAll { Plan plan -> plan.hasLeafSteps() }
  }

  /**
   * @return the current environment (live from zookeeper)
   */
  private def getCurrentEnvironment(Fabric fabric)
  {
    agentsService.getCurrentEnvironment(fabric)
  }

  /**
   * @return the current environment (filtered by the provided system if provided)
   */
  private def getCurrentEnvironment(params)
  {
    def currentSystem = agentsService.getCurrentSystemModel(params.fabric)
    currentSystem = currentSystem.filterBy(params.system)
    agentsService.computeEnvironment(params.fabric, currentSystem)
  }

  /**
   * Computes a transition plan.
   * @param metadata any metadata to add to the plan(s)
   */
  Collection<Plan<ActionDescriptor>> computeTransitionPlans(params, def metadata)
  {
    computeDeploymentPlans(params, metadata) { Type type, SystemModelDelta delta ->
      planner.computeTransitionPlan(type, delta, [params.state])
    }
  }

  /**
   * Compute a bounce plan to bounce (= stop/start) containers.
   * @param metadata any metadata to add to the plan(s)
   */
  Collection<Plan<ActionDescriptor>> computeBouncePlans(params, def metadata)
  {
    computeDeploymentPlans(params, metadata) { Type type, SystemModelDelta delta ->
      planner.computeTransitionPlan(type, delta, ['stopped', '<expected>'])
    }
  }

  /**
   * Compute an undeploy plan.
   * @param metadata any metadata to add to the plan(s)
   */
  Collection<Plan<ActionDescriptor>> computeUndeployPlans(params, def metadata)
  {
    computeDeploymentPlans(params, metadata) { Type type, SystemModelDelta delta ->
      planner.computeTransitionPlan(type, delta, [null])
    }
  }

  /**
   * Compute a redeploy plan (= undeploy/deploy).
   * @param metadata any metadata to add to the plan(s)
   */
  Collection<Plan<ActionDescriptor>> computeRedeployPlans(params, def metadata)
  {
    computeDeploymentPlans(params, metadata) { Type type, SystemModelDelta delta ->
      planner.computeTransitionPlan(type, delta, [null, '<expected>'])
    }
  }

  public Plan createPlan(String name,
                         Environment currentEnvironment,
                         Environment expectedEnvironment,
                         Closure closure)
  {
    deploymentMgr.createPlan(name,
                             currentEnvironment,
                             expectedEnvironment,
                             descriptionProvider,
                             closure)
  }

  /**
   * Shortcut to group the plan by instance in both sequential and parallel types.
   */
  Collection<Plan> groupByInstance(Plan plan, def metadata)
  {
    def plans = []

    [IStep.Type.SEQUENTIAL, IStep.Type.PARALLEL].each { IStep.Type stepType ->
      def newPlan = groupByInstance(plan, stepType, metadata)
      if(newPlan)
        plans << newPlan
    }

    return plans
  }

  /**
   * Create a new plan of the given type where the entries going to be grouped by instance.
   */
  Plan groupByInstance(Plan plan, IStep.Type type, def metadata)
  {
    def leafSteps = plan?.leafSteps

    if(leafSteps)
    {
      if(metadata == null)
        metadata = [:]
      def planBuilder = plan.createEmptyPlanBuilder()
      planBuilder.metadata = plan.metadata
      def compositeStepsBuilder = planBuilder.addCompositeSteps(type)
      compositeStepsBuilder.metadata = metadata

      def metadatas = [:]

      def instances = leafSteps.groupBy { LeafStep<ActionDescriptor> step ->
        def key = "${step.action.descriptorProperties.agentName}:${step.action.descriptorProperties.mountPoint}".toString()
        metadatas[key] = [
            agent: step.action.descriptorProperties.agentName,
            mountPoint: step.action.descriptorProperties.mountPoint
        ]
        return key
      }

      // we sort them by instance
      def sortedInstances = new TreeMap(instances)

      sortedInstances.each { key, leaves ->
        def instanceStepsBuilder = compositeStepsBuilder.addSequentialSteps()
        instanceStepsBuilder.metadata = metadatas[key]
        leaves.each { instanceStepsBuilder.addLeafStep (it) }
      }

      def name = plan.name
      if(!name)
      {
        name = metadata.collect { k,v -> "${k}=$v"}.join(' - ')
      }
      name = "${name} - ${type}".toString()
      planBuilder.name = name

      return planBuilder.toPlan()
    }
    else
    {
      return null
    }
  }

  Plan getPlan(String id)
  {
    synchronized(_plans)
    {
      return _plans[id]
    }
  }

  void savePlan(Plan plan)
  {
    synchronized(_plans)
    {
      // first we remove all old entries
      def cutoffTime = planCacheTimeout.pastTimeMillis(clock)
      _plans = _plans.findAll { k,v -> v.metadata.savedTime > cutoffTime }

      plan.metadata.savedTime = clock.currentTimeMillis()
      _plans[plan.id] = plan
    }
  }

  Collection<CurrentDeployment> getDeployments(String fabric)
  {
    synchronized(_deployments)
    {
      return _deployments.values().findAll { it.fabric == fabric }
    }
  }

  /**
   * Returns all the deployments matching the closure
   */
  Collection<CurrentDeployment> getDeployments(String fabric, Closure closure)
  {
    synchronized(_deployments)
    {
      _deployments.values().findAll { it.fabric == fabric && closure(it) }
    }
  }

  boolean archiveDeployment(String id)
  {
    synchronized(_deployments)
    {
      def deployment = _deployments[id]
      if(deployment)
      {
        if(deployment.planExecution.isCompleted())
        {
          _deployments.remove(id)
          return true
        }
        else
        {
          throw new IllegalStateException("cannot archive a running deployment")
        }
      }
      else
      {
        return false
      }
    }
  }

  /**
   * Archive all deployments (that are completed of course)
   * @return the number of archived deployments
   */
  int archiveAllDeployments(String fabric)
  {
    synchronized(_deployments)
    {
      def deploymentsToArchive = getDeployments(fabric) {
        it.planExecution.isCompleted()
      }

      deploymentsToArchive.each { _deployments.remove(it.id) }

      return deploymentsToArchive.size()
    }
  }
  
  CurrentDeployment getDeployment(String id)
  {
    synchronized(_deployments)
    {
      _deployments[id]
    }
  }

  ArchivedDeployment getArchivedDeployment(String id)
  {
    deploymentStorage.getArchivedDeployment(id)
  }

  boolean isExecutingDeploymentPlan(String fabric)
  {
    synchronized(_deployments)
    {
      return _deployments.values().any { it.fabric == fabric && !it.planExecution.isCompleted() }
    }
  }

  CurrentDeployment executeDeploymentPlan(SystemModel system, Plan plan)
  {
    executeDeploymentPlan(system, plan, plan.name, null)
  }

  CurrentDeployment executeDeploymentPlan(SystemModel system,
                                          Plan plan,
                                          String description,
                                          IPlanExecutionProgressTracker progressTracker)
  {
    synchronized(_deployments)
    {
      String username = authorizationService.getExecutingPrincipal()

      ArchivedDeployment deployment =
        deploymentStorage.startDeployment(description,
                                                   system.fabric,
                                                   username,
                                                   plan.toXml())

      def id = deployment.id

      def tracker = new ProgressTracker(deploymentStorage,
                                        progressTracker,
                                        id,
                                        system)

      def planExecution = deployer.executePlan(plan, tracker)

      CurrentDeployment currentDeployment = new CurrentDeployment(id: id,
                                                                  username: username,
                                                                  fabric: system.fabric,
                                                                  systemId: system.id,
                                                                  planExecution: planExecution,
                                                                  description: description,
                                                                  progressTracker: progressTracker)
      _deployments[id] = currentDeployment

      return currentDeployment
    }
  }
}

