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

package test.orchestration.engine.delta

import org.linkedin.glu.provisioner.core.model.SystemModel
import org.linkedin.glu.provisioner.core.model.SystemEntry
import org.linkedin.groovy.util.collections.GroovyCollectionsUtils
import org.linkedin.groovy.util.json.JsonUtils
import org.linkedin.glu.orchestration.engine.delta.DeltaServiceImpl
import org.linkedin.glu.orchestration.engine.delta.DeltaMgrImpl

class TestDeltaService extends GroovyTestCase
{
  DeltaMgrImpl deltaMgr = new DeltaMgrImpl()
  DeltaServiceImpl deltaService = new DeltaServiceImpl(deltaMgr: deltaMgr)

  def DEFAULT_INCLUDED_IN_VERSION_MISMATCH = deltaMgr.includedInVersionMismatch

  void testDeltaService()
  {
    // empty
    def current = []
    def expected = []
    groovy.util.GroovyTestCase.assertEquals([], doComputeDelta(current, expected))

    // notDeployed
    current = []
    expected = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'ERROR',
                            status: 'notDeployed',
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w1'
                            ]
                           ],
                           doComputeDelta(current, expected))

    // notDeployed + cluster (GLU-393)
    current = []
    expected = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2', cluster: 'cl1']
        ]
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.cluster': 'cl1',
                            'metadata.container': 'c1',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'ERROR',
                            status: 'notDeployed',
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w1'
                            ]
                           ],
                           doComputeDelta(current, expected))

    // notExpectedState (with default = running)
    current = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            entryState: 'stopped',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2', currentState: 'stopped']
        ]
    ]
    expected = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            'metadata.currentState': 'stopped',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'ERROR',
                            status: 'notExpectedState',
                            statusInfo: 'running != stopped',
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w1'
                            ]
                           ],
                           doComputeDelta(current, expected))

    // notExectedState (with specific state=stopped)
    current = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            entryState: 'configured',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2', currentState: 'configured']
        ]
    ]
    expected = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            entryState: 'stopped',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            'metadata.currentState': 'configured',
                            entryState: 'stopped',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'ERROR',
                            status: 'notExpectedState',
                            statusInfo: 'stopped != configured',
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w1'
                            ]
                           ],
                           doComputeDelta(current, expected))

    // notRunning + versionMismatch => default is versionMismatch wins
    current = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            entryState: 'stopped',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2', currentState: 'stopped']
        ]
    ]
    expected = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w2'],
            metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            'metadata.currentState': 'stopped',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'ERROR',
                            status: 'delta',
                            statusInfo: ['entryState:running != entryState:stopped',
                                         'initParameters.wars:w2 != initParameters.wars:w1'],
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w2'
                            ]
                           ],
                           doComputeDelta(current, expected))

    // notRunning + versionMismatch => we force notRunning to win
    deltaService.notRunningOverridesVersionMismatch = true
    current = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            entryState: 'stopped',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2', currentState: 'stopped']
        ]
    ]
    expected = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w2'],
            metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            'metadata.currentState': 'stopped',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'ERROR',
                            status: 'notExpectedState',
                            statusInfo: 'running != stopped',
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w2'
                            ]
                           ],
                           doComputeDelta(current, expected))

    // restoring defaults
    deltaService.notRunningOverridesVersionMismatch = false

    // versionMismatch (wars)
    current = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            entryState: 'stopped',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2', currentState: 'stopped']
        ]
    ]
    expected = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w2'],
            metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            'metadata.currentState': 'stopped',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'ERROR',
                            status: 'delta',
                            statusInfo: ['entryState:running != entryState:stopped',
                                         'initParameters.wars:w2 != initParameters.wars:w1'],
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w2'
                            ]
                           ],
                           doComputeDelta(current, expected))

    // versionMismatch (config)
    current = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            entryState: 'stopped',
            initParameters: [wars: 'w1', config: 'cnf1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2', currentState: 'stopped']
        ]
    ]
    expected = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w1', config: 'cnf2'],
            metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            'initParameters.config': 'cnf2',
                            'metadata.currentState': 'stopped',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'ERROR',
                            status: 'delta',
                            statusInfo: ['entryState:running != entryState:stopped',
                                         'initParameters.config:cnf2 != initParameters.config:cnf1'],
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w1'
                            ]
                           ],
                           doComputeDelta(current, expected))

    // versionMismatch (wars & config)
    current = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            entryState: 'stopped',
            initParameters: [wars: 'w1', config: 'cnf1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2', currentState: 'stopped']
        ]
    ]
    expected = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w2', config: 'cnf2'],
            metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            'initParameters.config': 'cnf2',
                            'metadata.currentState': 'stopped',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'ERROR',
                            status: 'delta',
                            statusInfo: ['entryState:running != entryState:stopped',
                                         'initParameters.config:cnf2 != initParameters.config:cnf1',
                                         'initParameters.wars:w2 != initParameters.wars:w1'],
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w2'
                            ]
                           ],
                           doComputeDelta(current, expected))

    // versionMismatch (script)
    current = [
      [
        agent: 'a1', mountPoint: '/m1', script: 's1',
        entryState: 'stopped',
        initParameters: [wars: 'w1', config: 'cnf1'],
        metadata: [container: 'c1', product: 'p1', version: 'R2', currentState: 'stopped']
      ]
    ]
    expected = [
      [
        agent: 'a1', mountPoint: '/m1', script: 's2',
        initParameters: [wars: 'w1', config: 'cnf1'],
        metadata: [container: 'c1', product: 'p1', version: 'R2']
      ]
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            'initParameters.config': 'cnf1',
                            'metadata.currentState': 'stopped',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's2',
                            state: 'ERROR',
                            status: 'delta',
                            statusInfo: ['entryState:running != entryState:stopped',
                                         'script:s2 != script:s1'],
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w1'
                            ]
                           ],
                           doComputeDelta(current, expected))

    // versionMismatch (script) (with includedInVersionMismatch)
    withNewDeltaMgr(['script'], null ) {
      current = [
        [
          agent: 'a1', mountPoint: '/m1', script: 's1',
          initParameters: [wars: 'w1', config: 'cnf1'],
          metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
      ]
      expected = [
        [
          agent: 'a1', mountPoint: '/m1', script: 's2',
          initParameters: [wars: 'w1', config: 'cnf1'],
          metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
      ]
      assertEqualsIgnoreType([
                             [
                              'metadata.container': 'c1',
                              'initParameters.config': 'cnf1',
                              entryState: 'running',
                              key: 'a1:/m1',
                              agent: 'a1',
                              mountPoint: '/m1',
                              'metadata.product': 'p1',
                              script: 's2',
                              state: 'ERROR',
                              status: 'delta',
                              statusInfo: 'script:s2 != script:s1',
                              'metadata.version': 'R2',
                              'initParameters.wars': 'w1'
                              ]
                             ],
                             doComputeDelta(current, expected))
    }

    // versionMismatch (script) (with includedInVersionMismatch)
    withNewDeltaMgr(['initParameters.wars'], null) {
      current = [
        [
          agent: 'a1', mountPoint: '/m1', script: 's1',
          initParameters: [wars: 'w1', config: 'cnf1'],
          metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
      ]
      expected = [
        [
          agent: 'a1', mountPoint: '/m1', script: 's2',
          initParameters: [wars: 'w1', config: 'cnf1'],
          metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
      ]
      assertEqualsIgnoreType([
                             [
                              'metadata.container': 'c1',
                              'initParameters.config': 'cnf1',
                              entryState: 'running',
                              key: 'a1:/m1',
                              agent: 'a1',
                              mountPoint: '/m1',
                              'metadata.product': 'p1',
                              script: 's1',
                              state: 'OK',
                              status: 'expectedState',
                              statusInfo: 'running',
                              'metadata.version': 'R2',
                              'initParameters.wars': 'w1'
                              ]
                             ],
                             doComputeDelta(current, expected))
    }

    // versionMismatch (script) (with excludedInVersionMismatch)
    withNewDeltaMgr(DEFAULT_INCLUDED_IN_VERSION_MISMATCH, ['initParameters.wars']) {
      current = [
        [
          agent: 'a1', mountPoint: '/m1', script: 's1',
          initParameters: [wars: 'w1', config: 'cnf1'],
          metadata: [container: 'c1', product: 'p1', version: 'R2', currentState: 'stopped']
        ]
      ]
      expected = [
        [
          agent: 'a1', mountPoint: '/m1', script: 's2',
          initParameters: [wars: 'w1', config: 'cnf1'],
          metadata: [container: 'c1', product: 'p1', version: 'R2', currentState: 'stopped']
        ]
      ]
      assertEqualsIgnoreType([
                             [
                             'metadata.container': 'c1',
                             'initParameters.config': 'cnf1',
                             'metadata.currentState': 'stopped',
                             entryState: 'running',
                             key: 'a1:/m1',
                             agent: 'a1',
                             mountPoint: '/m1',
                             'metadata.product': 'p1',
                             script: 's2',
                             state: 'ERROR',
                             status: 'delta',
                             statusInfo: 'script:s2 != script:s1',
                             'metadata.version': 'R2',
                             'initParameters.wars': 'w1'
                             ]
                             ],
                             doComputeDelta(current, expected))
    }

    // versionMismatch (script) (with excludedInVersionMismatch)
    withNewDeltaMgr(DEFAULT_INCLUDED_IN_VERSION_MISMATCH, ['script']) {
      current = [
        [
          agent: 'a1', mountPoint: '/m1', script: 's1',
          initParameters: [wars: 'w1', config: 'cnf1'],
          metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
      ]
      expected = [
        [
          agent: 'a1', mountPoint: '/m1', script: 's2',
          initParameters: [wars: 'w1', config: 'cnf1'],
          metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
      ]
      assertEqualsIgnoreType([
                             [
                              'metadata.container': 'c1',
                              'initParameters.config': 'cnf1',
                              entryState: 'running',
                              key: 'a1:/m1',
                              agent: 'a1',
                              mountPoint: '/m1',
                              'metadata.product': 'p1',
                              script: 's1',
                              state: 'OK',
                              status: 'expectedState',
                              statusInfo: 'running',
                              'metadata.version': 'R2',
                              'initParameters.wars': 'w1'
                              ]
                             ],
                             doComputeDelta(current, expected))
    }

    // unexpected
    current = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
    ]
    expected = [
        [
            agent: 'a2', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
    ]

    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'ERROR',
                            status: 'unexpected',
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w1'
                            ],
                           [
                            'metadata.container': 'c1',
                            entryState: 'running',
                            key: 'a2:/m1',
                            agent: 'a2',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'ERROR',
                            status: 'notDeployed',
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w1'
                            ]
                           ],
                           doComputeDelta(current, expected))

    // error
    current = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2', currentState: 'running', error: 'in error']
        ]
    ]
    expected = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2']
        ]
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            'metadata.currentState': 'running',
                            'metadata.error': 'in error',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'ERROR',
                            status: 'error',
                            statusInfo: 'in error',
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w1'
                            ]
                           ],
                           doComputeDelta(current, expected))

    // unknown
    current = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2', currentState: 'stopped']
        ]
    ]
    expected = null
    assertEqualsIgnoreType([],
                           doComputeDelta(current, expected))

    // ok
    current = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w1'],
            entryState: 'running',
            metadata: [container: 'c1', product: 'p1', version: 'R2', currentState: 'running'],
            tags: ['ec:1', 'ec:2']
        ]
    ]
    expected = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2'],
            tags: ['ee:1']
        ]
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            'metadata.currentState': 'running',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'OK',
                            status: 'expectedState',
                            statusInfo: 'running',
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w1',
                            'tags.ee:1': 'a1:/m1',
                             tags: ['ee:1']
                            ]
                            ],
                           doComputeDelta(current, expected))

    // ok (with cluster)
    current = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            entryState: 'running',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2', currentState: 'running']
        ]
    ]
    expected = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', cluster: 'cl1', product: 'p1', version: 'R2']
        ]
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            'metadata.cluster': 'cl1',
                            'metadata.currentState': 'running',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'OK',
                            status: 'expectedState',
                            statusInfo: 'running',
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w1'
                            ]
                           ],
                           doComputeDelta(current, expected))

    // ok (with cluster)
    current = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            entryState: 'running',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', cluster: 'cl1', product: 'p1', version: 'R2', currentState: 'running']
        ]
    ]
    expected = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', cluster: 'cl2', product: 'p1', version: 'R2']
        ]
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            'metadata.cluster': 'cl1',
                            'metadata.currentState': 'running',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'OK',
                            status: 'expectedState',
                            statusInfo: 'running',
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w1'
                            ]
                           ],
                           doComputeDelta(current, expected))

    // nothing deployed on the agent at all
    current = [
    ]
    expected = [
    ]

    assertEqualsIgnoreType([
                           [
                            'metadata.currentState': 'NA',
                            agent: 'a1',
                            state: 'NA',
                            status: 'NA'
                            ]
                           ],
                           doComputeDelta(current, expected) { SystemModel cs, SystemModel es ->
                             cs.metadata.emptyAgents = ['a1']
                           })

    // (system) tags
    current = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            entryState: 'running',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2', currentState: 'running'],
            tags: ['ec:1', 'ec:2']
        ]
    ]
    expected = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2'],
            tags: ['ee:1']
        ]
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            'metadata.currentState': 'running',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'OK',
                            status: 'expectedState',
                            statusInfo: 'running',
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w1',
                            'tags.ee:1': 'a1:/m1',
                            'tags.a:2': 'a1:/m1',
                             tags: ['a:2', 'ee:1']
                            ]
                           ],
                           doComputeDelta(current, expected) { SystemModel cs, SystemModel es ->
                             cs.addAgentTags('a1', ['a:1'])
                             es.addAgentTags('a1', ['a:2'])
                           })

    current = [
    ]
    expected = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2'],
            tags: ['ee:1']
        ]
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'ERROR',
                            status: 'notDeployed',
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w1',
                            'tags.ee:1': 'a1:/m1',
                            'tags.a:2': 'a1:/m1',
                             tags: ['a:2', 'ee:1']
                            ]
                           ],
                           doComputeDelta(current, expected) { SystemModel cs, SystemModel es ->
                             cs.addAgentTags('a1', ['a:1'])
                             es.addAgentTags('a1', ['a:2'])
                           })

    current = [
        [
            agent: 'a1', mountPoint: '/m1', script: 's1',
            initParameters: [wars: 'w1'],
            metadata: [container: 'c1', product: 'p1', version: 'R2', currentState: 'running'],
            tags: ['ec:1', 'ec:2']
        ]
    ]
    expected = [
    ]
    assertEqualsIgnoreType([
                           [
                            'metadata.container': 'c1',
                            'metadata.currentState': 'running',
                            entryState: 'running',
                            key: 'a1:/m1',
                            agent: 'a1',
                            mountPoint: '/m1',
                            'metadata.product': 'p1',
                            script: 's1',
                            state: 'ERROR',
                            status: 'unexpected',
                            'metadata.version': 'R2',
                            'initParameters.wars': 'w1'
                            ]
                           ],
                           doComputeDelta(current, expected) { SystemModel cs, SystemModel es ->
                             cs.addAgentTags('a1', ['a:1'])
                             es.addAgentTags('a1', ['a:2'])
                           })

    current = [
    ]
    expected = [
    ]

    assertEqualsIgnoreType([
                           [
                            'metadata.currentState': 'NA',
                            agent: 'a1',
                            state: 'NA',
                            status: 'NA',
                             tags: ['a:2']
                            ]
                           ],
                           doComputeDelta(current, expected) { SystemModel cs, SystemModel es ->
                             cs.addAgentTags('a1', ['a:1'])
                             es.addAgentTags('a1', ['a:2'])
                             cs.metadata.emptyAgents = ['a1']
                           })

  }


  // Testing for use case where metadata changes (version in this case)
  // entry | current | expected
  // e1    | null    | null
  // e2    | null    | R1016
  // e3    | null    | R1036
  // e4    | R1016   | null
  // e5    | R1016   | R1016
  // e6    | R1016   | R1036
  // e7    | R1036   | null
  // e8    | R1036   | R1016
  // e9    | R1036   | R1036
  def static CURRENT =
    [
        'm1': null, 'm2': null, 'm3': null,
        'm4': 'R1016', 'm5': 'R1016', 'm6': 'R1016',
        'm7': 'R1036', 'm8': 'R1036', 'm9': 'R1036'
    ]

  // building expected
  def static EXPECTED =
    [
        'm1': null, 'm2': 'R1016', 'm3': 'R1036',
        'm4': null, 'm5': 'R1016', 'm6': 'R1036',
        'm7': null, 'm8': 'R1016', 'm9': 'R1036'
    ]

  public void testMetadataChanges()
  {
    // Testing for use case where metadata changes (version in this case)
    // e1 | null  | null
    // e2 | null  | R1016
    // e3 | null  | R1036
    // e4 | R1016 | null
    // e5 | R1016 | R1016
    // e6 | R1016 | R1036
    // e7 | R1036 | null
    // e8 | R1036 | R1016
    // e9 | R1036 | R1036

    // full system computeDelta (everything up and running)
    def currentSystem = toSystem(CURRENT, 'running')
    def expectedSystem = toSystem(EXPECTED, null)
    doDeltaAndCheck(currentSystem, expectedSystem, CURRENT.keySet(), 'running')

    // full system computeDelta (everything stopped)
    currentSystem = toSystem(CURRENT, 'stopped')
    doDeltaAndCheck(currentSystem, expectedSystem, CURRENT.keySet(), 'stopped')

    // expectedSystem filtered by R1036 (everything up and running)
    currentSystem = toSystem(CURRENT, 'running')
    expectedSystem = expectedSystem.filterByMetadata('version', 'R1036')

    def expectedMountPoints = new TreeSet()
    expectedMountPoints.addAll(CURRENT.findAll { k,v -> v == 'R1036'}.collect { k,v -> k })
    expectedMountPoints.addAll(EXPECTED.findAll { k,v -> v == 'R1036'}.collect { k,v -> k })

    doDeltaAndCheck(currentSystem, expectedSystem, expectedMountPoints, 'running')

    // expectedSystem filtered by R1036 (everything stopped)
    currentSystem = toSystem(CURRENT, 'stopped')
    doDeltaAndCheck(currentSystem, expectedSystem, expectedMountPoints, 'stopped')
  }

  private void doDeltaAndCheck(SystemModel currentSystem,
                               SystemModel expectedSystem,
                               def expectedMountPoints,
                               String state)
  {
    def expectedDelta = []

    expectedMountPoints.each { mountPoint ->
      def entry =
      [
          agent: 'a1',
          entryState: 'running',
          mountPoint: "/${mountPoint}".toString(),
          script: 's1',
          'initParameters.wars': 'w1',
          'metadata.currentState': state,
          key: "a1:/${mountPoint}".toString(),
          status: state == 'running' ? 'expectedState' : 'notExpectedState',
          statusInfo: state == 'running' ? 'running' : 'running != stopped',
          state: state == 'running' ? 'OK' : 'ERROR'
      ]

      // when not in error, then priority comes from 'current'
      if(state == 'running')
      {
        if(CURRENT[mountPoint])
          entry['metadata.version'] = CURRENT[mountPoint]
        else
        {
          if(EXPECTED[mountPoint])
            entry['metadata.version'] = EXPECTED[mountPoint]
        }
      }
      else
      {
        // when in error, then priority comes from 'expected'
        if(EXPECTED[mountPoint])
          entry['metadata.version'] = EXPECTED[mountPoint]
        else
        {
          if(CURRENT[mountPoint])
            entry['metadata.version'] = CURRENT[mountPoint]
        }
      }

      expectedDelta << entry
    }

    assertEqualsIgnoreType(expectedDelta, deltaService.computeDelta(expectedSystem, currentSystem))
  }

  private SystemModel toSystem(Map system, String currentState)
  {
    def entries = []

    system.each { mountPoint, version ->

      def entry =
      [
          agent: 'a1', mountPoint: "/${mountPoint}".toString(), script: 's1',
          initParameters: [wars: 'w1'],
          metadata: [:]
      ]

      if(currentState)
      {
        entry.metadata.currentState = currentState
        entry.entryState = currentState
      }

      if(version)
        entry.metadata.version = version

      entries << entry
    }

    return toSystem(entries)
  }
  
  private def doComputeDelta(def current, def expected)
  {
    doComputeDelta(current, expected) { SystemModel cs, SystemModel es ->
      // nothing to do
    }
  }

  private def doComputeDelta(def current, def expected, Closure closure)
  {
    SystemModel currentSystem = createEmptySystem(current)
    SystemModel expectedSystem = createEmptySystem(expected)

    closure(currentSystem, expectedSystem)

    addEntries(currentSystem, current)
    addEntries(expectedSystem, expected)

    return deltaService.computeDelta(expectedSystem, currentSystem)
  }

  private SystemModel toSystem(def system)
  {
    SystemModel res = createEmptySystem(system)
    addEntries(res, system)
    return res
  }

  private SystemModel createEmptySystem(def system)
  {
    system != null ? new SystemModel(fabric: 'f1') : null
  }

  private void addEntries(SystemModel model, def entries)
  {
    entries?.each { e ->
      model.addEntry(SystemEntry.fromExternalRepresentation(e))
    }
  }

  private void withNewDeltaMgr(def includedInVersionMismatch,
                               def excludedInVersionMismatch,
                               Closure closure)
  {
    def oldi = deltaMgr.includedInVersionMismatch
    def olde = deltaMgr.excludedInVersionMismatch
    deltaMgr.includedInVersionMismatch = includedInVersionMismatch as Set
    deltaMgr.excludedInVersionMismatch = excludedInVersionMismatch as Set
    try
    {
      closure()
    }
    finally
    {
      deltaMgr.excludedInVersionMismatch = olde
      deltaMgr.includedInVersionMismatch = oldi
    }
  }
  
  /**
   * Convenient call to compare and ignore type
   */
  void assertEqualsIgnoreType(o1, o2)
  {
    assertEquals(JsonUtils.toJSON(o1).toString(2), JsonUtils.toJSON(o2).toString(2))
    assertTrue("expected <${o1}> but was <${o2}>", GroovyCollectionsUtils.compareIgnoreType(o1, o2))
  }

}
