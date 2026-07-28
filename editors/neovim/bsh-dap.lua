-- nvim-dap wiring for the BeanShell debug agent's DAP transport (-Dbsh.debug.protocol=dap).
-- See ../vscode/README.md for the same transport explained at more length; this is its
-- Neovim counterpart, not a separate implementation.
--
-- Usage: require('bsh-dap').setup() once nvim-dap is loaded.

local M = {}

-- The literal line DapChannel.connect() logs once its ServerSocket is bound (see
-- agent/hook/src/main/java/cz/loplex/bsh/hook/DapChannel.java). ServerSocket.accept() is called
-- exactly once, so a throwaway probe connection would consume the one accept() meant for the
-- real nvim-dap session -- watching stdout for this line is the only safe way to know the agent
-- is ready to be attached to.
local LISTENING_MARKER = 'DAP: listening'

local function build_args(config, port)
  local args = {
    '-javaagent:' .. config.agentJar,
    '-Dbsh.debug.protocol=dap',
    '-Dbsh.debug.listen=' .. port,
  }
  if config.sourcesFile then
    table.insert(args, '-Dbsh.debug.sources.file=' .. config.sourcesFile)
  else
    local sources = config.sources or vim.fn.fnamemodify(config.script, ':t')
    table.insert(args, '-Dbsh.debug.sources=' .. sources)
  end
  vim.list_extend(args, config.vmArgs or {})
  vim.list_extend(args, { '-cp', config.classpath, 'bsh.Interpreter', config.script })
  vim.list_extend(args, config.args or {})
  return args
end

-- Starts the JVM under the agent, listening on `port`, and invokes `callback` with a `server`
-- adapter descriptor once it is ready to accept the one DAP client it will ever see. Mirrors
-- the VS Code extension's descriptorFactory.ts: the agent's own "launch" request handler does
-- nothing but answer success, since from its side the script is already running either way, so
-- something has to actually start the JVM -- here, that is this function.
local function launch(config, callback)
  local port = config.port or 4711
  local cmd = { config.javaExecutable or 'java' }
  vim.list_extend(cmd, build_args(config, port))

  local state = { attached = false }
  local function on_output(_, data)
    if state.attached or not data then
      return
    end
    for _, line in ipairs(data) do
      if line:find(LISTENING_MARKER, 1, true) then
        state.attached = true
        callback({ type = 'server', host = '127.0.0.1', port = port })
        return
      end
    end
  end

  vim.fn.jobstart(cmd, {
    cwd = config.cwd or vim.fn.fnamemodify(config.script, ':h'),
    env = config.env,
    on_stdout = on_output,
    on_stderr = on_output,
    on_exit = function(_, code)
      if not state.attached then
        vim.notify(
          'BeanShell debug agent exited before it started listening (code ' .. code .. ')',
          vim.log.levels.ERROR
        )
      end
    end,
  })
end

function M.setup()
  local dap = require('dap')

  dap.adapters.bsh = function(callback, config)
    if config.request == 'attach' then
      callback({ type = 'server', host = config.host or '127.0.0.1', port = config.port })
    else
      launch(config, callback)
    end
  end

  vim.filetype.add({ extension = { bsh = 'beanshell' } })

  dap.configurations.beanshell = {
    {
      type = 'bsh',
      request = 'attach',
      name = 'BeanShell: Attach',
      host = '127.0.0.1',
      port = 4711,
    },
    {
      type = 'bsh',
      request = 'launch',
      name = 'BeanShell: Launch',
      script = function()
        return vim.fn.expand('%:p')
      end,
      agentJar = function()
        return vim.fn.input('Path to bsh-debug-agent-*.jar: ', '', 'file')
      end,
      classpath = function()
        return vim.fn.input('BeanShell classpath: ', '', 'file')
      end,
    },
  }
end

return M
