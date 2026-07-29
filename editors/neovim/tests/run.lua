-- Drives bsh-dap.lua through a real nvim-dap session, headless, and asserts on the DAP traffic --
-- the same script, breakpoint line and evaluate expression agent/checks/07-dap-transport.sh
-- already proves work over DapChannel, and editors/vscode's own test proves work through a real
-- VS Code session. This is the Neovim counterpart: it covers what dap-client.py cannot, namely
-- bsh-dap.lua's own launch() -- the jobstart spawn and the "DAP: listening" stdout watch -- the
-- same gap the VS Code test was written to close for BshDebugAdapterDescriptorFactory.launch().
--
-- Run via run-tests.sh, which resolves nvim-dap and the agent paths and sets the env vars below.

local REPO_ROOT = assert(os.getenv('REPO_ROOT'), 'REPO_ROOT not set')
local NVIM_DAP_DIR = assert(os.getenv('NVIM_DAP_DIR'), 'NVIM_DAP_DIR not set')
local AGENT_JAR = assert(os.getenv('BSH_AGENT_JAR'), 'BSH_AGENT_JAR not set')
local CLASSPATH = assert(os.getenv('BSH_CLASSPATH'), 'BSH_CLASSPATH not set')
local FIXTURE_SCRIPT = assert(os.getenv('FIXTURE_SCRIPT'), 'FIXTURE_SCRIPT not set')

local BREAKPOINT_LINE = 6 -- `return doubled + total;`

vim.opt.rtp:prepend(NVIM_DAP_DIR)
-- bsh-dap.lua is meant to be dropped directly onto runtimepath's lua/ (see its own README), not
-- nested under a lua/ subdirectory of its own, so it needs package.path rather than rtp:prepend.
package.path = REPO_ROOT .. '/editors/neovim/?.lua;' .. package.path

local dap = require('dap')
require('bsh-dap').setup()

vim.cmd.edit(FIXTURE_SCRIPT)
local bufnr = vim.api.nvim_get_current_buf()
require('dap.breakpoints').set({}, bufnr, BREAKPOINT_LINE)

local stopped_events = {}
dap.listeners.after.event_stopped['e2e'] = function(_, body)
  table.insert(stopped_events, body)
end

-- DapChannel never sends a terminated/exited DAP event (the JVM just exits and the socket
-- drops), so nvim-dap only learns the session is over the way it always does on an unexpected
-- disconnect: Session:close() firing on_close, the same hook a real client relies on -- not a
-- message on the wire. on_session fires as soon as dap.run() creates the session, before it has
-- even connected, so this is race-free against the session closing before the hook is attached.
local closed = false
dap.listeners.on_session['e2e'] = function(_, new_session)
  if new_session then
    new_session.on_close['e2e'] = function()
      closed = true
    end
  end
end

-- session:request() would resume a coroutine automatically if called from inside one, but this
-- script runs on the main coroutine, so it gets an explicit callback and vim.wait() polls for
-- it -- the same style nvim-dap's own test suite (spec/helpers.lua) uses.
local function request(session, command, args)
  local err, resp, done = nil, nil, false
  session:request(command, args, function(e, r)
    err, resp, done = e, r, true
  end)
  assert(vim.wait(10000, function() return done end, 20), command .. ' timed out')
  assert(not err, command .. ' failed: ' .. vim.inspect(err))
  return resp
end

dap.run({
  type = 'bsh',
  request = 'launch',
  name = 'e2e',
  script = FIXTURE_SCRIPT,
  agentJar = AGENT_JAR,
  classpath = CLASSPATH,
})

-- Mirrors agent/checks/07-dap-transport.sh and the VS Code test: the first stop is the script's
-- own first statement, reported before the agent could know any breakpoints existed, not yet
-- inside compute() -- so this rides out stops until one actually lands there.
local thread_id, frame_names, stack
for attempt = 1, 4 do
  assert(
    vim.wait(10000, function() return #stopped_events >= attempt end, 50),
    'stop ' .. attempt .. ' never arrived'
  )
  local body = stopped_events[attempt]
  -- DapChannel deliberately sends the same generic "pause" for every stop -- it does not
  -- distinguish "breakpoint" from "step" -- so that is what a real client sees here too.
  assert(body.reason == 'pause', 'expected reason=pause, got ' .. tostring(body.reason))
  thread_id = body.threadId

  local session = assert(dap.session(), 'no session after a stopped event')
  stack = request(session, 'stackTrace', { threadId = thread_id })
  frame_names = vim.tbl_map(function(f) return f.name end, stack.stackFrames)
  if vim.tbl_contains(frame_names, 'compute') then
    break
  end
  assert(attempt < 4, 'never reached a stop inside compute()')
  request(session, 'continue', { threadId = thread_id })
end
assert(#frame_names >= 2, 'expected the caller frame in the stack too')

local session = assert(dap.session())
local top_frame_id = stack.stackFrames[1].id
local scopes = request(session, 'scopes', { frameId = top_frame_id })
local scope_names = vim.tbl_map(function(s) return s.name end, scopes.scopes)
assert(vim.tbl_contains(scope_names, 'Locals'), 'expected a Locals scope, got ' .. vim.inspect(scope_names))
assert(vim.tbl_contains(scope_names, 'Global'), 'expected a Global scope, got ' .. vim.inspect(scope_names))

local locals_scope
for _, s in ipairs(scopes.scopes) do
  if s.name == 'Locals' then
    locals_scope = s
  end
end
local variables = request(session, 'variables', { variablesReference = locals_scope.variablesReference })
local doubled
for _, v in ipairs(variables.variables) do
  if v.name == 'doubled' then
    doubled = v
  end
end
assert(doubled and doubled.value == '14', 'expected doubled=14, got ' .. vim.inspect(doubled))

local evaluated = request(session, 'evaluate', { expression = 'doubled + 1', frameId = top_frame_id })
assert(evaluated.result == '15', 'expected evaluate result 15, got ' .. vim.inspect(evaluated.result))

request(session, 'continue', { threadId = thread_id })
assert(vim.wait(10000, function() return closed end, 50), 'session never closed after script completion')

print('bsh-dap.lua: all assertions passed')
os.exit(0)
