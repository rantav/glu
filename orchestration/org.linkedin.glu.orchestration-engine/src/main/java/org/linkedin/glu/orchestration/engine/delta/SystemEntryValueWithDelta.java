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

package org.linkedin.glu.orchestration.engine.delta;

/**
 * @author yan@pongasoft.com
 */
public class SystemEntryValueWithDelta<T> implements SystemEntryValue<T>
{
  private final T _expectedValue;
  private final T _currentValue;

  /**
   * Constructor
   */
  public SystemEntryValueWithDelta(T expectedValue, T currentValue)
  {
    _expectedValue = expectedValue;
    _currentValue = currentValue;
  }

  @Override
  public boolean hasDelta()
  {
    return true;
  }

  @Override
  public T getExpectedValue()
  {
    return _expectedValue;
  }

  @Override
  public T getCurrentValue()
  {
    return _currentValue;
  }

  @Override
  public String toString()
  {
    StringBuilder sb = new StringBuilder();
    sb.append(_expectedValue).append("!=").append(_currentValue);
    return sb.toString();
  }
}
