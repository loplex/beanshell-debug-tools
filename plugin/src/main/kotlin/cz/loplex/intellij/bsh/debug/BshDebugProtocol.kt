package cz.loplex.intellij.bsh.debug

/*
 * The debug wire protocol, version 2. The full specification -- framing, every field, the
 * invariants and the failure modes -- is `docs/PROTOCOL.md`; this file is the IDE end's copy of the
 * opcodes plus the notes that matter when reading the code around them.
 *
 * Both ends of it live in this repository -- the instrumenting agent in `agent/`, the rewriting
 * fallback in `debug/agent/BshDebugAgent.java` -- and the agent jar ships inside the plugin, so
 * there is no version skew to negotiate.
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
 *     EVT_EVALUATED  byte ok, utf value, utf type, int childHandle       answers CMD_EVALUATE
 *     EVT_VARIABLE_SET  byte ok, utf value, utf type, int childHandle    answers CMD_SET_VARIABLE
 *
 *   IDE -> agent
 *     CMD_RESUME
 *     CMD_SET_BREAKPOINTS  int count, (utf file, int line)*
 *     CMD_SET_RUN_MODE     byte mode                            0 = running
 *     CMD_SCOPES           int frameId                          only while suspended
 *     CMD_VARIABLES        int handle                           only while suspended
 *     CMD_EVALUATE         int frameId, utf expression          only while suspended
 *     CMD_SET_VARIABLE     int frameId, int handle, utf name, utf expression   while suspended
 *
 * A handle is opaque and valid only until the next resume, which is what makes it safe: the IDE
 * can never hold a reference into a script that has moved on. This is DAP's `variablesReference`
 * in a smaller encoding, so adopting DAP later changes the serialisation and not the design.
 *
 * The two failable requests answer with `ok`, and on failure carry the reason in `value` rather
 * than dropping the connection: a mistyped watch expression is ordinary use, not a protocol error.
 * Their replies have the same shape but separate opcodes, for the same reason the two variable
 * replies do -- a reader that can name the reply it expected can tell a desync from a bad answer.
 *
 * `CMD_SET_VARIABLE` carries a frame as well as the container, because the new value is an
 * expression and has to be evaluated somewhere: `h.count = other + 1` needs the frame's scope even
 * when the container is a plain object.
 */

internal const val CMD_RESUME = 0x01
internal const val CMD_SET_BREAKPOINTS = 0x02
internal const val CMD_SET_RUN_MODE = 0x03
internal const val CMD_SCOPES = 0x04
internal const val CMD_VARIABLES = 0x05
internal const val CMD_EVALUATE = 0x06
internal const val CMD_SET_VARIABLE = 0x07

internal const val EVT_STOPPED = 0x10
internal const val EVT_SCOPES = 0x11
internal const val EVT_VARIABLES = 0x12
internal const val EVT_EVALUATED = 0x13
internal const val EVT_VARIABLE_SET = 0x14

internal const val MODE_RUN = 0
internal const val MODE_STEPPING = 1

/** Never issued by the agent, so it doubles as "this value has nothing to expand". */
internal const val NO_HANDLE = 0
