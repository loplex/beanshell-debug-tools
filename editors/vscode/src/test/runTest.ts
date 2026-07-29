import * as cp from 'child_process';
import * as path from 'path';
import { runTests } from '@vscode/test-electron';

/**
 * Resolves AGENT_JAR and BSH_CLASSPATH the same way agent/checks/lib.sh's need_paths() does:
 * by asking Gradle, since the BeanShell coordinates live in the version catalog and the agent
 * jar sits under a content hash -- any path guessed here would be wrong the moment either changed.
 */
function resolveAgentPaths(repoRoot: string): { agentJar: string; classpath: string } {
    const gradlew = path.join(repoRoot, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
    const output = cp.execFileSync(
        gradlew,
        ['-q', '-p', repoRoot, ':agent:samples:printPaths'],
        { encoding: 'utf8' }
    );

    const classpath = /^BSH_CLASSPATH=(.*)$/m.exec(output)?.[1];
    const agentJar = /^AGENT_JAR=(.*)$/m.exec(output)?.[1];
    if (!classpath || !agentJar) {
        throw new Error(`could not parse ':agent:samples:printPaths' output:\n${output}`);
    }
    return { agentJar, classpath };
}

async function main(): Promise<void> {
    const extensionDevelopmentPath = path.resolve(__dirname, '..', '..');
    const extensionTestsPath = path.resolve(__dirname, 'suite', 'index');
    const repoRoot = path.resolve(extensionDevelopmentPath, '..', '..');

    // Fixtures are plain data, not compiled sources, so they are read from src/ rather than out/.
    const workspacePath = path.join(extensionDevelopmentPath, 'src', 'test', 'fixtures', 'workspace');

    const { agentJar, classpath } = resolveAgentPaths(repoRoot);

    // On a real Wayland desktop, Electron's Ozone platform selection prefers the real
    // compositor over the X11 DISPLAY xvfb-run sets up -- an env hint alone was not enough to
    // stop it, so WAYLAND_DISPLAY is removed outright: with no Wayland socket to find, there is
    // nothing left for Electron to prefer over X11.
    const testEnv: NodeJS.ProcessEnv = {
        ...process.env,
        BSH_AGENT_JAR: agentJar,
        BSH_CLASSPATH: classpath,
    };
    delete testEnv.WAYLAND_DISPLAY;

    await runTests({
        extensionDevelopmentPath,
        extensionTestsPath,
        launchArgs: [workspacePath, '--disable-extensions', '--ozone-platform=x11'],
        extensionTestsEnv: testEnv,
    });
}

main().catch((err) => {
    console.error('Failed to run the VS Code extension tests:', err);
    process.exit(1);
});
