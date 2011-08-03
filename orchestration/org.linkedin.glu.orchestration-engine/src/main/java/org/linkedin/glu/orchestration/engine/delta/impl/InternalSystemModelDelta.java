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

package org.linkedin.glu.orchestration.engine.delta.impl;

import org.linkedin.glu.orchestration.engine.delta.SystemModelDelta;

import java.util.Collection;
import java.util.Set;

/**
 * @author yan@pongasoft.com
 */
public interface InternalSystemModelDelta extends SystemModelDelta
{
  InternalSystemEntryDelta findAnyEntryDelta(String key);
  void setEntryDelta(InternalSystemEntryDelta delta);

  /* parent (getter) */
  InternalSystemEntryDelta findExpectedParentEntryDelta(String key);
  InternalSystemEntryDelta findCurrentParentEntryDelta(String key);
  Set<String> getParentKeys(Set<String> keys);

  /* children (getter) */
  Collection<InternalSystemEntryDelta> findExpectedChildrenEntryDelta(String key);
  Collection<InternalSystemEntryDelta> findCurrentChildrenEntryDelta(String key);

  /* dependencies */
  EntryDependencies getExpectedDependencies();
  EntryDependencies getCurrentDependencies();

  /**
   * Remove all the entries referring to an an empty agent (which is not really empty)
   * @param nonEmptyAgents
   */
  void removeNonEmptyAgents(Set<String> nonEmptyAgents);
}
