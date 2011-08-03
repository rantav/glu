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

import org.linkedin.util.clock.Timespan

/**
 * @author yan@pongasoft.com */
public abstract class Deployment
{
  String id
  Date startDate = new Date()
  Date endDate
  String username
  String fabric
  String description

  public Timespan getDuration()
  {
    if(endDate)
      return new Timespan(endDate.time - startDate.time)
    else
      return null
  }

  abstract String getPlanXml()
}