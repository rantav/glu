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

package org.linkedin.glu.orchestration.engine.planner.impl;

import org.linkedin.glu.orchestration.engine.action.descriptor.InternalActionDescriptor;
import org.linkedin.glu.orchestration.engine.delta.impl.InternalSystemEntryDelta;

/**
 * @author yan@pongasoft.com
 */
public abstract class SingleEntryTransition extends Transition
{
  private final String _entryKey;

  /**
   * Constructor
   */
  public SingleEntryTransition(TransitionPlan transitionPlan, String key, String entryKey)
  {
    super(transitionPlan, key);
    _entryKey = entryKey;
  }

  public String getMountPoint()
  {
    return getSystemEntryDelta().getMountPoint();
  }

  public String getAgent()
  {
    return getSystemEntryDelta().getAgent();
  }

  public InternalSystemEntryDelta getSystemEntryDelta()
  {
    return getSystemModelDelta().findAnyEntryDelta(getEntryKey());
  }

  public String getEntryKey()
  {
    return _entryKey;
  }

  protected <T extends InternalActionDescriptor> T populateActionDescriptor(T actionDescriptor)
  {
    actionDescriptor = super.populateActionDescriptor(actionDescriptor);

    actionDescriptor.setValue("agent", getAgent());
    actionDescriptor.setValue("mountPoint", getMountPoint());

    return actionDescriptor;
  }
}