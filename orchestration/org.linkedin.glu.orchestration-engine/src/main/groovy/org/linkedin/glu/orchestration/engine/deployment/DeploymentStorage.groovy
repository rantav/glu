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

import org.linkedin.glu.provisioner.plan.api.IStepCompletionStatus

/**
 * @author yan@pongasoft.com */
public interface DeploymentStorage
{
  ArchivedDeployment getArchivedDeployment(String id)

  /**
   * params can be what grails accept for paginating queries: <code>max</code>,
   * <code>offset</code>, <code>sort</code>, <code>order</code>
   * @return the list of archived deployments
   */
  Map getArchivedDeployments(String fabric,
                             boolean includeDetails,
                             params)

  /**
   * @return number of archived deployments in this fabric
   */
  int getArchivedDeploymentsCount(String fabric)

  ArchivedDeployment startDeployment(String description,
                                     String fabric,
                                     String username,
                                     String details)

  ArchivedDeployment endDeployment(String id,
                                   IStepCompletionStatus status,
                                   String details)
}