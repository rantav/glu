
/*
 * Copyright (c) 2010-2011 LinkedIn, Inc
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

import org.linkedin.glu.agent.api.ShellExecException

class JettyGluScript
{
  static requires = {
    agent(version: '1.6.0')
  }

  def version = '@script.version@'
  def serverRoot
  def serverCmd
  def logsDir
  def serverLog
  def pid
  def port
  def webapps

  def install = {
    log.info "Installing..."

    // fetching/installing jetty
    def jettySkeleton = shell.fetch(params.skeleton)
    def distribution = shell.untar(jettySkeleton)
    shell.rmdirs(mountPoint)
    serverRoot = shell.mv(shell.ls(distribution)[0], mountPoint)

    // assigning variables
    logsDir = serverRoot.'logs'
    serverLog = logsDir.'jetty.log'
    serverCmd = "JETTY_RUN=${logsDir.file} ${serverRoot.'bin/jetty.sh'.file}"

    shell.rmdirs(serverRoot.'contexts')
    shell.rmdirs(serverRoot.'webapps')

    // make sure all bin/*.sh files are executable
    shell.ls(serverRoot.bin) {
      include(name: '*.sh')
    }.each { shell.chmodPlusX(it) }

    // creating etc/jetty-glu.xml
    shell.saveContent(serverRoot.'etc/jetty-glu.xml', DEFAULT_JETTY_XML)

    log.info "Install complete."
  }

  def configure = {
    log.info "Configuring..."

    // first we configure the server
    configureServer()

    // second we configure the apps
    configureWebapps()

    // setting up a timer to monitor the server
    timers.schedule(timer: serverMonitor,
                    repeatFrequency: params?.serverMonitorFrequency ?: '15s')

    log.info "Configuration complete."
  }

  def start = {
    log.info "Starting..."

    pid = isProcessUp()

    if(pid)
    {
      log.info "Server already up."
    }
    else
    {
      // we execute the start command (return right away)
      String cmd = "JAVA_OPTIONS=\"-Djetty.port=${port} -Dcom.sun.management.jmxremote\" ${serverCmd} start > /dev/null 2>&1 &"
      shell.exec(cmd)
      shell.saveContent(logsDir.'jetty.cmd', cmd)

      // we wait for the process to be started (should be quick)
      shell.waitFor(timeout: '5s', heartbeat: '250') {
        pid = isProcessUp()
      }
    }

    // now that the process should be up, we wait for the server to be up
    // when jetty starts, it also starts all the contexts and only then start listening on
    // the port... this will effectively wait for all the apps to be up!
    shell.waitFor(timeout: params?.startTimeout, heartbeat: '1s') { duration ->
      log.info "${duration}: Waiting for server to be up"

      // we check if the server is down already... in which case we throw an exception
      if(isProcessDown())
        shell.fail("Server could not start. Check the log file for errors.")

      return isServerUp()
    }

    if(checkWebapps() == 'dead')
    {
      shell.fail("Webapps did not deploy properly, server has been shutdown. Check the log file for errors.")
    }
    else
    {
      log.info "Started jetty on port ${port}."
    }
  }

  def stop = { args ->
    log.info "Stopping..."

    doStop()

    log.info "Stopped."
  }

  def unconfigure = {
    log.info "Unconfiguring..."

    timers.cancel(timer: serverMonitor)

    port = null

    log.info "Unconfiguration complete."
  }

  def uninstall = {
    // nothing
  }

  private def doStop = {
    if(isProcessDown())
    {
      log.info "Server already down."
    }
    else
    {
      // invoke the stop command
      shell.exec("${serverCmd} stop")

      // we wait for the process to be stopped
      shell.waitFor(timeout: params?.stopTimeout, heartbeat: '1s') { duration ->
        log.info "${duration}: Waiting for server to be down"
        isProcessDown()
      }
    }

    pid = null
  }

  private Integer isProcessUp()
  {
    try
    {
      def output = shell.exec("${serverCmd} check")
      def matcher = output =~ /Jetty running pid=([0-9]+)/
      if(matcher)
        return matcher[0][1] as int
      else
        return null
    }
    catch(ShellExecException e)
    {
      return null
    }
  }

  private Integer isServerUp()
  {
    Integer pid = isProcessUp()
    if(pid && shell.listening('localhost', port))
      return pid
    else
      return null
  }

  private boolean isProcessDown()
  {
    isProcessUp() == null
  }

  private def configureServer = {
    port = (params.port ?: 8080) as int
    def c = []
    c << DEFAULT_JETTY_CONFIG
    c << '--pre=etc/jetty-logging.xml'
    c << '--daemon'
    c << '\n' // forces an empty line
    shell.saveContent(serverRoot.'start.ini', c.join('\n'))
  }

  private def configureWebapps = {
    def w = params.webapps ?: []

    // case when only one webapp provided as a map
    if(w instanceof Map)
      w = [w]

    def ws = [:]

    w.each {
      def webapp = configureWebapp(it)
      if(ws.containsKey(webapp.contextPath))
        shell.fail("deplicate contextPath ${webapp.contextPath}")
      ws[webapp.contextPath] = webapp
    }

    webapps = ws
  }

  private def configureWebapp = { webapp ->
    if(webapp.war)
      configureWar(webapp)
    else
    {
      if(webapp.resources)
        configureResources(webapp)
      else
        shell.fail("cannot configure webapp: ${webapp}")
    }
  }

  private def configureWar = { webapp ->
    String contextPath = (webapp.contextPath ?: '/').toString()
    String name = contextPath.replace('/', '_')

    def war = shell.fetch(webapp.war, serverRoot."wars/${name}.war")

    def context = shell.saveContent(serverRoot."contexts/${name}.xml",
                                    WAR_CONTEXT,
                                    ['war.localWar': war.file.canonicalPath,
                                    'war.contextPath': contextPath])

    return [
      remoteWar: webapp.war,
      localWar: war,
      contextPath: contextPath,
      context: context,
      monitor: webapp.monitor
    ]
  }

  private def configureResources = { webapp ->
    // TODO MED YP: add configuration for resource only wars
  }

  /**
   * @return a map of failed apps. The map is empty if there is none. The key is the context path
   * and the value can be 'busy', 'dead' or 'unknown' if in the process of being deployed
   */
  private Map<String, String> getFailedWebapps()
  {
    Map<String, String> failedWebapps = [:]

    // when no webapps at all there is no need to talk to the server
    if(!webapps)
      return failedWebapps

    webapps.keySet().each { String contextPath ->
      def monitor = webapps[contextPath]?.monitor
      if(monitor)
      {
        try
        {
          def head = shell.httpHead("http://localhost:${port}${contextPath}${monitor}")

          switch(head.responseCode)
          {
            case 200:
              failedWebapps.remove(contextPath)
              break

            case 503:
              if(head.responseMessage == 'BUSY')
                failedWebapps[contextPath] = 'busy'
              else
                failedWebapps[contextPath] = 'dead'
              break

            default:
              log.warn "Unexpected response code: ${head.responseCode} for ${contextPath}"
              failedWebapps[contextPath] = 'dead'
          }
        }
        catch(IOException e)
        {
          log.debug("Could not talk to ${contextPath}", e)
          failedWebapps[contextPath] = 'dead'
        }
      }
    }

    return failedWebapps
  }

  /**
   * @return 'ok' if all apps are good, 'dead' if any app is dead, 'busy' if any app is busy,
   *         otherwise 'unknown' (which is when the apps are in the process of being deployed)
   */
  private String checkWebapps()
  {
    def failedApps = getFailedWebapps()

    if(failedApps.isEmpty())
      return 'ok'

    if(failedApps.values().find { it == 'dead' })
    {
      log.warn ("Failed apps: ${failedApps}. Shutting down server...")
      doStop()
      return 'dead'
    }
    else
    {
      if(failedApps.values().find { it == 'busy'} )
        return 'busy'
    }

    return 'unknown'
  }

  /**
   * Check that both server and webapps are up
   */
  private def checkServerAndWebapps = {
    def up = [server: false, webapps: 'unknown']

    pid = isServerUp()
    up.server = pid != null
    if(up.server)
      up.webapps = checkWebapps()

    return up
  }

  /**
   * Defines the timer that will check for the server to be up and running and will act
   * according if not (change state)
   */
  def serverMonitor = {
    try
    {
      def up = checkServerAndWebapps()

      def currentState = stateManager.state.currentState
      def currentError = stateManager.state.error

      def newState = null
      def newError = null

      // case when current state is running
      if(currentState == 'running')
      {
        if(!up.server || up.webapps == 'dead')
        {
          newState = 'stopped'
          pid = null
          newError = 'Server down detected. Check the log file for errors.'
          log.warn "${newError} => forcing new state ${newState}"
        }
        else
        {
          if(up.webapps == 'busy')
          {
            newError = 'Server is up but some webapps are busy. Check the log file for errors.'
            if(newError != currentError)
            {
              newState = 'running' // remain running but set in error
              log.warn newError
            }
          }
          else
          {
            if(up.webapps == 'ok' && currentError)
            {
              newState = 'running' // remain running
              log.info "All webapps are up, clearing error status."
            }
          }
        }
      }
      else
      {
        if(up.server && up.webapps == 'ok')
        {
          newState = 'running'
          log.info "Server up detected."
        }
      }

      if(newState)
        stateManager.forceChangeState(newState, newError)

      log.debug "Server Monitor: ${stateManager.state.currentState} / ${up}"
    }
    catch(Throwable th)
    {
      log.warn "Exception while running serverMonitor: ${th.message}"
      log.debug("Exception while running serverMonitor (ignored)", th)
    }
  }

  static String DEFAULT_JETTY_CONFIG = """
OPTIONS=Server,jsp,jmx,resources,websocket,ext
etc/jetty-glu.xml
etc/jetty-deploy.xml
etc/jetty-webapps.xml
etc/jetty-contexts.xml
"""

  /**
   * The 'default' file etc/jetty.xml contains a lot of hardcoded values and does not use
   * SystemProperty which is a bug because jetty.sh use -Djetty.port for the port! This script
   * will create a etc/jetty-glu.xml and use it instead
   */
  static String DEFAULT_JETTY_XML = """<?xml version="1.0"?>
<!DOCTYPE Configure PUBLIC "-//Jetty//Configure//EN" "http://www.eclipse.org/jetty/configure.dtd">
<Configure id="Server" class="org.eclipse.jetty.server.Server">
    <Set name="ThreadPool">
      <!-- Default queued blocking threadpool -->
      <New class="org.eclipse.jetty.util.thread.QueuedThreadPool">
        <Set name="minThreads"><SystemProperty name="jetty.minThreads" default="10"/></Set>
        <Set name="maxThreads"><SystemProperty name="jetty.maxThreads" default="200"/></Set>
      </New>
    </Set>
    <Call name="addConnector">
      <Arg>
          <New class="org.eclipse.jetty.server.nio.SelectChannelConnector">
            <Set name="host"><SystemProperty name="jetty.host" /></Set>
            <Set name="port"><SystemProperty name="jetty.port" default="8080"/></Set>
            <Set name="maxIdleTime"><SystemProperty name="jetty.maxIdleTime" default="300000"/></Set>
            <Set name="Acceptors"><SystemProperty name="jetty.Acceptors" default="2"/></Set>
            <Set name="statsOn"><SystemProperty name="jetty.statsOn" default="false"/></Set>
            <Set name="confidentialPort"><SystemProperty name="jetty.confidentialPort" default="8443"/></Set>
	          <Set name="lowResourcesConnections"><SystemProperty name="jetty.lowResourcesConnections" default="20000"/></Set>
	          <Set name="lowResourcesMaxIdleTime"><SystemProperty name="jetty.lowResourcesMaxIdleTime" default="5000"/></Set>
          </New>
      </Arg>
    </Call>
    <Set name="handler">
      <New id="Handlers" class="org.eclipse.jetty.server.handler.HandlerCollection">
        <Set name="handlers">
         <Array type="org.eclipse.jetty.server.Handler">
           <Item>
             <New id="Contexts" class="org.eclipse.jetty.server.handler.ContextHandlerCollection"/>
           </Item>
           <Item>
             <New id="DefaultHandler" class="org.eclipse.jetty.server.handler.DefaultHandler"/>
           </Item>
         </Array>
        </Set>
      </New>
    </Set>
    <Set name="stopAtShutdown"><SystemProperty name="jetty.stopAtShutdown" default="true"/></Set>
    <Set name="sendServerVersion"><SystemProperty name="jetty.sendServerVersion" default="true"/></Set>
    <Set name="sendDateHeader"><SystemProperty name="jetty.sendDateHeader" default="true"/></Set>
    <Set name="gracefulShutdown"><SystemProperty name="jetty.gracefulShutdown" default="1000"/></Set>

</Configure>
"""

  static String WAR_CONTEXT = """<?xml version="1.0"  encoding="ISO-8859-1"?>
<!DOCTYPE Configure PUBLIC "-//Mort Bay Consulting//DTD Configure//EN" "http://jetty.eclipse.org/configure.dtd">
<Configure class="org.eclipse.jetty.webapp.WebAppContext">
  <Call class="org.eclipse.jetty.util.log.Log" name="debug"><Arg>Configure war=@war.localWar@ contextPath=@war.contextPath@</Arg></Call>
  <Set name="contextPath">@war.contextPath@</Set>
  <Set name="war">@war.localWar@</Set>
</Configure>
"""
}