package com.levi.dock

import org.json.JSONArray
import org.json.JSONObject

/** `dev` message from the host (see docs/protocol-v1.md). */
data class DevData(
    val projects: List<Project>,
    val agents: List<Named>,
    val tools: List<Named>,
    val servers: List<Server>,
    val apps: List<App>,
) {
    data class App(val id: String, val name: String, val running: Boolean, val detail: String, val launchable: Boolean)
    data class Project(
        val name: String,
        val active: Boolean,
        val git: Boolean,
        val branch: String,
        val changed: Int,
        val ahead: Int,
        val behind: Int,
        val commit: String,
        val commitAgo: String,
        val modifiedAgo: String,
    )
    data class Named(val name: String, val processes: Int)
    data class Server(val port: Int, val name: String, val network: Boolean)

    companion object {
        fun parse(o: JSONObject) = DevData(
            projects = list(o.optJSONArray("projects")) {
                Project(
                    name = it.optString("name").take(40),
                    active = it.optBoolean("active"),
                    git = it.optBoolean("git"),
                    branch = it.optString("branch", ""),
                    changed = it.optInt("changed", 0),
                    ahead = it.optInt("ahead", 0),
                    behind = it.optInt("behind", 0),
                    commit = it.optString("commit", ""),
                    commitAgo = it.optString("commitAgo", ""),
                    modifiedAgo = it.optString("modifiedAgo", ""),
                )
            },
            agents = list(o.optJSONArray("agents")) { Named(it.optString("name").take(24), it.optInt("processes")) },
            tools = list(o.optJSONArray("tools")) { Named(it.optString("name").take(24), it.optInt("processes")) },
            servers = list(o.optJSONArray("servers")) {
                Server(it.optInt("port"), it.optString("name").take(28), it.optString("scope") == "network")
            },
            apps = list(o.optJSONArray("apps")) {
                App(
                    id = it.optString("id").take(32),
                    name = it.optString("name").take(40),
                    running = it.optBoolean("running"),
                    detail = it.optString("detail").take(48),
                    launchable = it.optBoolean("launchable"),
                )
            },
        )

        private fun <T> list(a: JSONArray?, f: (JSONObject) -> T): List<T> {
            if (a == null) return emptyList()
            return (0 until minOf(a.length(), 12)).mapNotNull { a.optJSONObject(it)?.let(f) }
        }
    }
}
