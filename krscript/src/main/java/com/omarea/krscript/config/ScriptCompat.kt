package com.omarea.krscript.config

import android.content.Context
import com.omarea.krscript.executor.ScriptEnvironmen
import com.omarea.krscript.model.NodeInfoBase

/**
 * Compatibility bridge for legacy KrScript XML parsing code.
 *
 * Older PageConfigReader code called executeResultRoot directly. Keep that
 * API local to the config package while delegating to the actual executor.
 */
internal fun executeResultRoot(
    context: Context,
    script: String?,
    nodeInfoBase: NodeInfoBase? = null
): String {
    if (script.isNullOrBlank()) return ""
    return ScriptEnvironmen.executeResultRoot(context, script, nodeInfoBase)
}
