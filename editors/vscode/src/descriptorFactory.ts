import * as cp from 'child_process';
import * as net from 'net';
import * as path from 'path';
import * as vscode from 'vscode';

// The literal text DapChannel.connect() logs once its ServerSocket is bound (see
// agent/hook/src/main/java/cz/loplex/bsh/hook/DapChannel.java). ServerSocket.accept() is called
// exactly once, so probing the port with a throwaway connection would consume the one accept()
// meant for VS Code's real DAP session -- watching stdout for this line is the only safe way to
// know the agent is ready.
const LISTENING_MARKER = 'DAP: listening';
const LISTEN_TIMEOUT_MS = 20_000;

/**
 * Resolves a debug session to a TCP connection to the agent's DAP transport.
 *
 * `attach` connects to a JVM the user already started. `launch` starts that JVM itself -- the
 * agent's own "launch" request handler does nothing but answer success, since from its side the
 * script is already running either way (see DapChannel.readCommand()).
 */
export class BshDebugAdapterDescriptorFactory
    implements vscode.DebugAdapterDescriptorFactory, vscode.Disposable {
    private readonly output = vscode.window.createOutputChannel('BeanShell Debug');
    private readonly processes = new Map<string, cp.ChildProcess>();

    async createDebugAdapterDescriptor(
        session: vscode.DebugSession,
        _executable: vscode.DebugAdapterExecutable | undefined
    ): Promise<vscode.DebugAdapterDescriptor> {
        const config = session.configuration;

        if (config.request === 'attach') {
            return new vscode.DebugAdapterServer(config.port, config.host ?? '127.0.0.1');
        }

        const port = config.port ?? (await findFreePort());
        await this.launch(session, config, port);
        return new vscode.DebugAdapterServer(port, '127.0.0.1');
    }

    /**
     * Kills the JVM a `launch` session started. Needed because disconnecting from the DAP side
     * does not stop the script -- the agent's disconnect/terminate handler only drops the
     * connection, on the same "a session can end mid-run" footing as any other client that walks
     * away (see docs/PROTOCOL.md, Failure modes). An attach session owns no process here.
     */
    terminate(session: vscode.DebugSession): void {
        const proc = this.processes.get(session.id);
        if (proc) {
            this.processes.delete(session.id);
            proc.kill();
        }
    }

    dispose(): void {
        for (const proc of this.processes.values()) {
            proc.kill();
        }
        this.processes.clear();
        this.output.dispose();
    }

    private launch(
        session: vscode.DebugSession,
        config: vscode.DebugConfiguration,
        port: number
    ): Promise<void> {
        const classpath = Array.isArray(config.classpath)
            ? config.classpath.join(path.delimiter)
            : config.classpath;

        const args = [
            `-javaagent:${config.agentJar}`,
            '-Dbsh.debug.protocol=dap',
            `-Dbsh.debug.listen=${port}`,
        ];
        if (config.sourcesFile) {
            args.push(`-Dbsh.debug.sources.file=${config.sourcesFile}`);
        } else {
            args.push(`-Dbsh.debug.sources=${config.sources}`);
        }
        if (config.vmArgs) {
            args.push(...config.vmArgs);
        }
        args.push('-cp', classpath, 'bsh.Interpreter', config.script, ...(config.args ?? []));

        const javaExecutable = config.javaExecutable || 'java';
        this.output.appendLine(`> ${javaExecutable} ${args.join(' ')}`);
        this.output.show(true);

        const child = cp.spawn(javaExecutable, args, {
            cwd: config.cwd,
            env: { ...process.env, ...(config.env ?? {}) },
        });
        this.processes.set(session.id, child);

        return new Promise<void>((resolve, reject) => {
            let settled = false;
            let buffered = '';

            const timer = setTimeout(() => {
                if (!settled) {
                    settled = true;
                    reject(new Error(
                        'Timed out waiting for the BeanShell debug agent to start listening.\n' + buffered
                    ));
                }
            }, LISTEN_TIMEOUT_MS);

            const onOutput = (chunk: Buffer) => {
                const text = chunk.toString();
                buffered += text;
                this.output.append(text);
                if (!settled && text.includes(LISTENING_MARKER)) {
                    settled = true;
                    clearTimeout(timer);
                    resolve();
                }
            };
            child.stdout?.on('data', onOutput);
            child.stderr?.on('data', onOutput);

            child.on('exit', (code) => {
                this.processes.delete(session.id);
                if (!settled) {
                    settled = true;
                    clearTimeout(timer);
                    reject(new Error(
                        `The BeanShell debug agent process exited before it started listening ` +
                        `(code ${code}).\n${buffered}`
                    ));
                }
            });

            child.on('error', (err) => {
                if (!settled) {
                    settled = true;
                    clearTimeout(timer);
                    reject(err);
                }
            });
        });
    }
}

function findFreePort(): Promise<number> {
    return new Promise((resolve, reject) => {
        const server = net.createServer();
        server.on('error', reject);
        server.listen(0, '127.0.0.1', () => {
            const address = server.address();
            server.close(() => {
                if (address && typeof address === 'object') {
                    resolve(address.port);
                } else {
                    reject(new Error('Could not determine a free port.'));
                }
            });
        });
    });
}
