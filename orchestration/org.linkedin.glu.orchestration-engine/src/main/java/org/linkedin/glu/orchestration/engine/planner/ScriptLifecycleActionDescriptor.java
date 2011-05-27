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

package org.linkedin.glu.orchestration.engine.planner;

import java.util.Map;

/**
 * @author yan@pongasoft.com
 */
public class ScriptLifecycleActionDescriptor extends MountPointActionDescriptor
{

  private final ScriptLifecycle _scriptLifecycle;
  private final Map _initParameters;

  /**
   * Constructor
   */
  public ScriptLifecycleActionDescriptor(String agent,
                                         String mountPoint,
                                         ScriptLifecycle scriptLifecycle,
                                         Map initParameters,
                                         String description)
  {
    super(agent, mountPoint, description);
    _scriptLifecycle = scriptLifecycle;
    _initParameters = initParameters;
  }

  public ScriptLifecycle getScriptLifecycle()
  {
    return _scriptLifecycle;
  }

  public Map getInitParameters()
  {
    return _initParameters;
  }

  @Override
  public void toMetadata(Map<String, Object> metadata)
  {
    super.toMetadata(metadata);
    metadata.put("scriptLifecycle", _scriptLifecycle.toString().toLowerCase());
  }
}
