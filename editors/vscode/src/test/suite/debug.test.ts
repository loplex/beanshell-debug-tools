import * as assert from 'assert';
import { EventEmitter } from 'events';
import * as vscode from 'vscode';

// Same fixture, same breakpoint line and evaluate expression as
// agent/checks/07-dap-transport.sh, one layer up the stack: this drives them through a real
// VS Code debug session instead of the standalone dap-client.py.
const FIXTURE_SCRIPT = 'script.bsh';
const BREAKPOINT_LINE = 7; // `return doubled + total;`

function waitFor(events: EventEmitter, event: string): Promise<any> {
    return new Promise((resolve) => events.once(event, resolve));
}

suite('BeanShell debug adapter (VS Code)', function () {
    this.timeout(60_000);

    test('launches the agent, hits a breakpoint, evaluates, and runs to completion', async () => {
        const agentJar = process.env.BSH_AGENT_JAR;
        const classpath = process.env.BSH_CLASSPATH;
        assert.ok(agentJar, 'BSH_AGENT_JAR must be set by runTest.ts');
        assert.ok(classpath, 'BSH_CLASSPATH must be set by runTest.ts');

        const folder = vscode.workspace.workspaceFolders?.[0];
        assert.ok(folder, 'expected the fixture workspace to be open');
        const scriptUri = vscode.Uri.joinPath(folder.uri, FIXTURE_SCRIPT);

        // The adapter never fires the initialize/configurationDone handshake by hand here --
        // vscode.debug.startDebugging() drives that internally. What is worth watching is
        // everything a UI would otherwise trigger by clicking: stackTrace, scopes, variables,
        // evaluate, continue -- so those go through session.customRequest() below, exactly as
        // dap-client.py drives them explicitly against the raw socket.
        const events = new EventEmitter();
        const trackerDisposable = vscode.debug.registerDebugAdapterTrackerFactory('bsh', {
            createDebugAdapterTracker(): vscode.DebugAdapterTracker {
                return {
                    onDidSendMessage(message: any) {
                        if (message.type === 'event') {
                            events.emit(message.event, message);
                        }
                    },
                };
            },
        });

        try {
            vscode.debug.addBreakpoints([
                new vscode.SourceBreakpoint(
                    new vscode.Location(scriptUri, new vscode.Position(BREAKPOINT_LINE - 1, 0))
                ),
            ]);

            let stopped = waitFor(events, 'stopped');
            const started = await vscode.debug.startDebugging(folder, {
                type: 'bsh',
                request: 'launch',
                name: 'e2e',
                script: scriptUri.fsPath,
                agentJar,
                classpath,
            });
            assert.ok(started, 'startDebugging did not start a session');

            const session = vscode.debug.activeDebugSession;
            assert.ok(session, 'expected an active debug session');

            // Mirrors agent/checks/07-dap-transport.sh: the first stop is the script's own
            // first statement (reported before the agent could know any breakpoints existed),
            // not yet inside compute() -- so this rides out stops until one actually lands in
            // compute(), the same way dap-client.py's --stops loop does, rather than assuming
            // the first "stopped" event is the breakpoint.
            let threadId: number;
            let frameNames: string[];
            let stack: any;
            for (let attempt = 0; ; attempt++) {
                assert.ok(attempt < 4, 'never reached a stop inside compute()');
                const stoppedMessage = await stopped;
                // DapChannel deliberately sends the same generic "pause" for every stop --
                // it does not distinguish "breakpoint" from "step" -- so that is what a real
                // client sees here too, not "breakpoint".
                assert.strictEqual(stoppedMessage.body.reason, 'pause');
                threadId = stoppedMessage.body.threadId;

                stack = await session.customRequest('stackTrace', { threadId });
                frameNames = stack.stackFrames.map((f: any) => f.name);
                if (frameNames.includes('compute')) {
                    break;
                }
                stopped = waitFor(events, 'stopped');
                await session.customRequest('continue', { threadId });
            }
            assert.ok(frameNames.length >= 2, 'expected the caller frame in the stack too');

            const topFrameId = stack.stackFrames[0].id;
            const scopes = await session.customRequest('scopes', { frameId: topFrameId });
            const scopeNames = scopes.scopes.map((s: any) => s.name);
            assert.ok(scopeNames.includes('Locals'), `expected a Locals scope, got: ${scopeNames}`);
            assert.ok(scopeNames.includes('Global'), `expected a Global scope, got: ${scopeNames}`);

            const localsScope = scopes.scopes.find((s: any) => s.name === 'Locals');
            const variables = await session.customRequest('variables', {
                variablesReference: localsScope.variablesReference,
            });
            const doubled = variables.variables.find((v: any) => v.name === 'doubled');
            assert.strictEqual(doubled?.value, '14');

            const evaluated = await session.customRequest('evaluate', {
                expression: 'doubled + 1',
                frameId: topFrameId,
            });
            assert.strictEqual(evaluated.result, '15');

            // DapChannel never sends a "terminated"/"exited" DAP event -- the JVM just exits and
            // the socket drops once the script runs to completion, so a real client only learns
            // the session is over the way VS Code itself does: onDidTerminateDebugSession, not a
            // message on the wire.
            const ended = new Promise<void>((resolve) => {
                const sub = vscode.debug.onDidTerminateDebugSession((endedSession) => {
                    if (endedSession.id === session.id) {
                        sub.dispose();
                        resolve();
                    }
                });
            });
            await session.customRequest('continue', { threadId });
            await ended;
        } finally {
            trackerDisposable.dispose();
            vscode.debug.removeBreakpoints(vscode.debug.breakpoints);
        }
    });
});
