/*
 * Copyright (c) 2010-2010 LinkedIn, Inc
 * Portions Copyright (c) 2011 Andras Kovi
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


package test.agent.impl

import org.linkedin.glu.agent.impl.capabilities.ShellImpl
import org.linkedin.glu.agent.api.MountPoint
import org.linkedin.glu.agent.api.DuplicateMountPointException
import org.linkedin.glu.agent.api.NoSuchMountPointException
import org.linkedin.glu.agent.impl.script.AgentContext
import org.linkedin.glu.agent.impl.capabilities.MOPImpl
import org.linkedin.glu.agent.impl.script.ScriptManagerImpl
import org.linkedin.glu.agent.impl.script.FromClassNameScriptFactory
import org.linkedin.groovy.util.io.fs.FileSystem
import org.linkedin.glu.agent.api.ScriptIllegalStateException
import org.linkedin.util.io.ram.RAMDirectory
import org.linkedin.util.io.resource.internal.RAMResourceProvider
import org.linkedin.groovy.util.state.StateMachine
import org.linkedin.util.clock.SystemClock

/**
 * Test for ScriptManager
 *
 * @author ypujante@linkedin.com
 */
def class TestScriptManager extends GroovyTestCase
{
  def ram
  def fileSystem
  def shell
  def ScriptManagerImpl sm

  protected void setUp()
  {
    super.setUp();

    ram = new HashSet()
    RAMDirectory ramDirectory = new RAMDirectory()
    RAMResourceProvider rp = new RAMResourceProvider(ramDirectory)
    fileSystem = [
      mkdirs: { dir ->
        ram << dir
        ramDirectory.mkdirhier(dir.toString())
        return rp.createResource(dir.toString())
      },
      rmdirs: { dir ->
        ram.remove(dir)
        ramDirectory.rm(dir.toString())
      },

      getRoot: { rp.createResource('/') },

      getTmpRoot: { rp.createResource('/tmp') },

      newFileSystem: { r, t -> fileSystem }
    ] as FileSystem

    shell = new ShellImpl(fileSystem: fileSystem)

    def agentContext = [
      getShellForScripts: {shell},
      getMop: {new MOPImpl()},
      getClock: { SystemClock.instance() }
    ] as AgentContext

    sm = new ScriptManagerImpl(agentContext: agentContext)
    sm.installRootScript([:])
  }

  /**
   * Basic test for the script manager
   */
  void testScriptManager()
  {
    def rootMountPoint = MountPoint.fromPath('/')
    assertEquals(rootMountPoint, sm.rootScript.rootPath)
    assertEquals(new HashSet([rootMountPoint]), ram)

    def scriptMountPoint = MountPoint.fromPath('/s')
    sm.installScript(mountPoint: scriptMountPoint,
                     initParameters: [p1: 'v1'],
                     scriptFactory: new FromClassNameScriptFactory(MyScriptTestScriptManager))
    def node = sm.findScript(scriptMountPoint)
    assertEquals([currentState: StateMachine.NONE], node.state)
    assertTrue node.is(sm.findScript(scriptMountPoint))
    assertTrue node.is(sm.findScript('/s'))

    assertEquals([install: '1/v1'],
                 node.install(value: 1,
                              expectedMountPoint: scriptMountPoint,
                              expectedParentRootPath: rootMountPoint))

    assertEquals([currentState: 'installed'], node.state)

    assertEquals(new HashSet([MountPoint.fromPath('/'), MountPoint.fromPath('/s')]), ram)

    shouldFail(ScriptIllegalStateException) {
      sm.uninstallScript('/s', false)
    }

    // make sure it has not been removed
    assertTrue node.is(sm.findScript('/s'))

    // we uninstall the software first
    node.uninstall()

    // then we uninstall from the script manager
    sm.uninstallScript('/s', false)

    assertNull sm.findScript('/s')

    // reinstall the script
    sm.installScript(mountPoint: scriptMountPoint,
                     initParameters: [p1: 'v1'],
                     scriptFactory: new FromClassNameScriptFactory(MyScriptTestScriptManager))

    node = sm.findScript(scriptMountPoint)

    assertEquals([install: '1/v1'],
                 node.install(value: 1,
                              expectedMountPoint: scriptMountPoint,
                              expectedParentRootPath: rootMountPoint))

    assertEquals([currentState: 'installed'], node.state)

    shouldFail(ScriptIllegalStateException) {
      sm.uninstallScript('/s', false)
    }

    // when force=true then the script will be uninstalled
    sm.uninstallScript('/s', true)

    assertNull sm.findScript('/s')
  }

  /**
   * When state listener throws an exception it does not have any effect
   */
  public void testStateChangeListenerThrowsException()
  {
    def rootMountPoint = MountPoint.fromPath('/')

    def scriptMountPoint = MountPoint.fromPath('/s')
    def node = sm.installScript(mountPoint: scriptMountPoint,
                                initParameters: [p1: 'v1'],
                                scriptFactory: new FromClassNameScriptFactory(MyScriptTestScriptManager))
    assertEquals([currentState: StateMachine.NONE], node.state)

    node.scriptState.setStateChangeListener { oldState, newState ->
      throw new Exception("mine")
    }


    assertEquals([install: '1/v1'],
                 node.install(value: 1,
                              expectedMountPoint: scriptMountPoint,
                              expectedParentRootPath: rootMountPoint))

    assertEquals([currentState: 'installed'], node.state)
  }

  /**
   * Test error scnearios
   */
  void testErrors()
  {
    // mountPoint is required
    shouldFail(IllegalArgumentException) {
      sm.installScript(initParameters: [p1: 'v1'],
                       scriptFactory: new FromClassNameScriptFactory(MyScriptTestScriptManager))
    }

    def scriptMountPoint = MountPoint.fromPath('/s')
    sm.installScript(mountPoint: scriptMountPoint,
                     initParameters: [p1: 'v1'],
                     scriptFactory: new FromClassNameScriptFactory(MyScriptTestScriptManager))

    // duplicate mount point
    shouldFail(DuplicateMountPointException) {
      sm.installScript(mountPoint: scriptMountPoint,
                       initParameters: [p1: 'v1'],
                       scriptFactory: new FromClassNameScriptFactory(MyScriptTestScriptManager))
    }

    // creating child with no parent
    shouldFail(NoSuchMountPointException) {
      sm.installScript(mountPoint: MountPoint.fromPath('/a/b/c'),
                       parent: '/foo',
                       initParameters: [p1: 'v1'],
                       scriptFactory: new FromClassNameScriptFactory(MyScriptTestScriptManager))
    }
  }

  /**
   * test parent/child
   */
  void testParentChild()
  {
    assertNull sm.findScript('/a')
    assertNull sm.findScript('/a/b')

    // / (is always installed...)
    def rootNode = sm.findScript('/')
    assertEquals(MountPoint.ROOT, rootNode.rootPath)
    assertEquals(0, rootNode.childrenMountPoints.size())

    // we add a parent (which will be attached to root)
    def parentMountPoint = MountPoint.fromPath('/parent')
    sm.installScript(mountPoint: parentMountPoint,
                     initParameters: [p1: 'v1Parent'],
                     scriptFactory: new FromClassNameScriptFactory(MyScriptTestScriptManager))
    def parentNode = sm.findScript(parentMountPoint)

    assertEquals(MountPoint.ROOT, parentNode.parentMountPoint)
    assertEquals(1, rootNode.childrenMountPoints.size())
    assertEquals([parentMountPoint], rootNode.childrenMountPoints)
    assertEquals(0, parentNode.childrenMountPoints.size())

    assertEquals([install: '1/v1Parent'],
                 parentNode.install(value: 1,
                                    expectedMountPoint: parentMountPoint,
                                    expectedParentRootPath: MountPoint.ROOT))

    // we now create /a/b/c with a parent of /parent
    def scriptMountPoint = MountPoint.fromPath('/a/b/c')
    sm.installScript(mountPoint: scriptMountPoint,
                     parent: '/parent',
                     initParameters: [p1: 'v1'],
                     scriptFactory: new FromClassNameScriptFactory(MyScriptTestScriptManager))
    def node = sm.findScript(scriptMountPoint)

    assertTrue node.is(sm.findScript('/a/b/c'))

    // /a does not exist
    assertNull sm.findScript('/a')

    // /a/b does not exist
    assertNull sm.findScript('/a/b')

    // we make sure that the parent/children have been wired properly
    assertEquals(MountPoint.ROOT, parentNode.parentMountPoint)
    assertEquals(1, rootNode.childrenMountPoints.size())
    assertEquals([parentMountPoint], rootNode.childrenMountPoints)
    assertEquals(1, parentNode.childrenMountPoints.size())
    assertEquals([scriptMountPoint], parentNode.childrenMountPoints)

    // /a/b/c
    assertEquals([install: '1/v1'],
                 node.install(value: 1,
                              expectedMountPoint: scriptMountPoint,
                              expectedParentRootPath: MountPoint.fromPath('/parent')))

    shouldFail(ScriptIllegalStateException) {
      sm.uninstallScript(scriptMountPoint, false)
    }

    assertEquals("closure:/a/b/c: v1/v2", node.closure(p1: 'v1', p2: 'v2'))

    assertEquals("closure:/parent: v1/v2", node.configure(p1: 'v1', p2: 'v2'))

    node.unconfigure()

    // uninstall first
    node.uninstall()
    // then uninstall script
    sm.uninstallScript(scriptMountPoint, false)

    // we make sure that the parent/children have been wired properly
    assertEquals(MountPoint.ROOT, parentNode.parentMountPoint)
    assertEquals(1, rootNode.childrenMountPoints.size())
    assertEquals([parentMountPoint], rootNode.childrenMountPoints)
    assertEquals(0, parentNode.childrenMountPoints.size())
  }

  /**
   * Test support of transient modifier for script fields
   */
  void testTransientScript()
  {
    def rootMountPoint = MountPoint.fromPath('/')
    assertEquals(rootMountPoint, sm.rootScript.rootPath)
    assertEquals(new HashSet([rootMountPoint]), ram)

    def scriptMountPoint = MountPoint.fromPath('/transient')
    sm.installScript(mountPoint: scriptMountPoint,
                     initParameters: [],
                     scriptFactory: new FromClassNameScriptFactory(TransientFieldTestScript))
    def node = sm.findScript(scriptMountPoint)
    assertEquals([currentState: StateMachine.NONE], node.state)
    assertTrue node.is(sm.findScript(scriptMountPoint))
    assertTrue node.is(sm.findScript('/transient'))

    assertEquals(false, node.getFullState().scriptState.script.booleanField)
    assertEquals(0, node.getFullState().scriptState.script.intField)

    node.install(normalValue: "normalv",
                 nonSerializableValue: new Object(),
                 serializableWithNonSerializableContent: [new Object()],
                 transientValue: "transientv",
                 booleanValue: false,
                 intValue: 0,
                 staticValue: "static info")

    assertEquals([currentState: 'installed'], node.state)

    assertFalse("null value fields should not be included in the state",
                node.getFullState().scriptState.script.containsKey("nullField"))
    assertNotNull(node.getFullState().scriptState.script.booleanField)
    assertFalse(node.getFullState().scriptState.script.booleanField)
    assertNotNull(node.getFullState().scriptState.script.intField)
    assertEquals(0, node.getFullState().scriptState.script.intField)
    assertEquals("normalv", node.getFullState().scriptState.script.normalField)
    assertFalse(node.getFullState().scriptState.script.containsKey("staticField"))
    assertFalse(node.getFullState().scriptState.script.containsKey("nonSerializableField"))
    assertFalse("serializable with non serializable content",
                node.getFullState().scriptState.script.containsKey("serializableWithNonSerializableContent"))
    assertFalse("transient modifier not handled properly",
                node.getFullState().scriptState.script.containsKey("transientField"))
  }

  /**
   * Test support of transient modifier for script fields with
   * alternating (serializable/non-serializable) content
   */
  void testTransientScript2()
  {
    def rootMountPoint = MountPoint.fromPath('/')
    assertEquals(rootMountPoint, sm.rootScript.rootPath)
    assertEquals(new HashSet([rootMountPoint]), ram)

    def scriptMountPoint = MountPoint.fromPath('/transient2')
    sm.installScript(mountPoint: scriptMountPoint,
                     initParameters: [],
                     scriptFactory: new FromClassNameScriptFactory(TransientFieldTestScript2))
    def node = sm.findScript(scriptMountPoint)
    assertEquals([currentState: StateMachine.NONE], node.state)
    assertTrue node.is(sm.findScript(scriptMountPoint))
    assertTrue node.is(sm.findScript('/transient2'))
    assertEquals("initValue", node.getFullState().scriptState.script.keepOnChanging)

    node.install()
    assertEquals([currentState: 'installed'], node.state)
    assertNotNull(node.getFullState().scriptState.script.keepOnChanging)
    assertEquals(3, node.getFullState().scriptState.script.keepOnChanging)

    node.configure()
    assertEquals([currentState: 'stopped'], node.state)
    assertNull(node.getFullState().scriptState.script.keepOnChanging)
    assertFalse(node.getFullState().scriptState.script.containsKey("keepOnChanging"))

    node.executeAction(action: "start").get()
    assertEquals([currentState: 'running'], node.state)
    assertNotNull(node.getFullState().scriptState.script.keepOnChanging)
    assertEquals(3, node.getFullState().scriptState.script.keepOnChanging)
  }


}

private def class MyScriptTestScriptManager
{
  def rootPath

  def install = { args ->
    rootPath = mountPoint
    GroovyTestCase.assertEquals(args.expectedMountPoint, mountPoint)
    GroovyTestCase.assertEquals(args.expectedParentRootPath, parent.rootPath)
    shell.mkdirs(mountPoint)
    return [install: "${args.value}/${params.p1}".toString()]
  }

  def configure = { args ->
    return "${parent.closure(p1: args.p1, p2: args.p2)}".toString()
  }

  def createChild = { args ->
    return args.script
  }

  def destroyChild = { args ->
  }

  def closure = { args ->
    return "closure:${mountPoint}: ${args.p1}/${args.p2}".toString()
  }
}

/**
 * "Script" class for testing transient modifier support for fields.
 */
private def class TransientFieldTestScript
{
  def normalField
  def nullField
  def nonSerializableField
  def serializableWithNonSerializableContent
  def transient transientField
  def boolean booleanField
  def int intField
  static staticField

  def install = { args ->
    normalField = args.normalValue
    nullField = null
    nonSerializableField = args.nonSerializableValue
    serializableWithNonSerializableContent = args.serializableWithNonSerializableContent
    transientField = args.transientValue

    booleanField = args.booleanValue
    intField = args.intValue

    staticField = args.staticValue
  }
}

private def class TransientFieldTestScript2
{
  def Object keepOnChanging = "initValue";

  def install = {
    keepOnChanging = 3; // serializable... should be part of the state
  }

  def configure = {
    keepOnChanging = new Object(); // non-serializable
  }

  def start = {
    keepOnChanging = 3; // serializable again
  }
}
