package cz.loplex.intellij.bsh.debug

/*
 * The debug wire protocol, version 2. Both ends of it live in this repository -- the
 * instrumenting agent in `agent/`, the rewriting fallback in `debug/agent/BshDebugAgent.java` --
 * and the agent jar ships inside the plugin, so there is no version skew to negotiate.
 *
 * Both directions are opcode-tagged. Version 1 had an untagged agent-to-IDE stream of statement
 * reports, which left nowhere for a reply to travel and so forced every variable to be pushed on
 * every step.
 *
 *   agent -> IDE
 *     EVT_STOPPED    int line, int callDepth, int frameCount,
 *                    (utf name, utf sourceFile, int line)*      innermost frame first
 *     EVT_SCOPES     int count, (utf name, int handle)*         answers CMD_SCOPES
 *     EVT_VARIABLES  int count,
 *                    (utf name, utf value, utf type, int childHandle)*   answers CMD_VARIABLES
 *
 *   IDE -> agent
 *     CMD_RESUME
 *     CMD_SET_BREAKPOINTS  int count, (utf file, int line)*
 *     CMD_SET_RUN_MODE     byte mode                            0 = running
 *     CMD_SCOPES           int frameId                          only while suspended
 *     CMD_VARIABLES        int handle                           only while suspended
 *
 * A handle is opaque and valid only until the next resume, which is what makes it safe: the IDE
 * can never hold a reference into a script that has moved on. This is DAP's `variablesReference`
 * in a smaller encoding, so adopting DAP later changes the serialisation and not the design.
 */

internal const val CMD_RESUME = 0x01
internal const val CMD_SET_BREAKPOINTS = 0x02
internal const val CMD_SET_RUN_MODE = 0x03
internal const val CMD_SCOPES = 0x04
internal const val CMD_VARIABLES = 0x05

internal const val EVT_STOPPED = 0x10
internal const val EVT_SCOPES = 0x11
internal const val EVT_VARIABLES = 0x12

internal const val MODE_RUN = 0
internal const val MODE_STEPPING = 1

/** Never issued by the agent, so it doubles as "this value has nothing to expand". */
internal const val NO_HANDLE = 0
