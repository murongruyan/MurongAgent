package com.murong.agent.core.loop

import com.murong.agent.core.config.SessionProjectConfig

internal fun normalizeSessionProjectConfigs(
    configs: Map<String, SessionProjectConfig>,
    normalizePath: (String?) -> String?
): Map<String, SessionProjectConfig> {
    return configs.entries
        .mapNotNull { (path, config) ->
            normalizePath(path)?.let { normalizedPath ->
                normalizedPath to config
            }
        }
        .associate { it }
}
