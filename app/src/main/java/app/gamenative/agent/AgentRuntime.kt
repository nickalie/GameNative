package app.gamenative.agent

/**
 * Static entry point for the agent hooks. The hooks live in code that cannot take new parameters
 * (XServerScreen, the launch funnel), so they reach the tracker and the options store through here.
 */
object AgentRuntime {
    val tracker: AgentSessionTracker = AgentSessionTracker()
    val launchOptions: AgentLaunchOptionsStore = AgentLaunchOptionsStore()
}
