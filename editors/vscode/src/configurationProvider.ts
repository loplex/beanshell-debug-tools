import * as path from 'path';
import * as vscode from 'vscode';

/**
 * Fills in defaults and fails loudly on a missing required field, rather than letting the debug
 * adapter factory discover the same problem later with a less actionable error.
 */
export class BshConfigurationProvider implements vscode.DebugConfigurationProvider {
    resolveDebugConfiguration(
        _folder: vscode.WorkspaceFolder | undefined,
        config: vscode.DebugConfiguration
    ): vscode.ProviderResult<vscode.DebugConfiguration> {
        if (!config.type && !config.request) {
            // F5 with no launch.json: build one from the .bsh file open in the active editor.
            const editor = vscode.window.activeTextEditor;
            if (editor && editor.document.languageId === 'beanshell') {
                config.type = 'bsh';
                config.name = 'BeanShell: Launch';
                config.request = 'launch';
                config.script = editor.document.fileName;
            }
        }

        if (!config.request) {
            return undefined;
        }

        if (config.request === 'launch') {
            if (!config.script) {
                vscode.window.showErrorMessage('BeanShell launch configuration is missing "script".');
                return undefined;
            }
            if (!config.agentJar) {
                vscode.window.showErrorMessage(
                    'BeanShell launch configuration is missing "agentJar" (the bsh-debug-agent jar ' +
                    'built by ./gradlew :agent:instrument:shadowJar).'
                );
                return undefined;
            }
            if (!config.classpath) {
                vscode.window.showErrorMessage(
                    'BeanShell launch configuration is missing "classpath" (it must include the ' +
                    'BeanShell jar).'
                );
                return undefined;
            }
            config.cwd = config.cwd || path.dirname(config.script);
            config.sources = config.sources || path.basename(config.script);
        } else if (config.request === 'attach') {
            if (!config.port) {
                vscode.window.showErrorMessage('BeanShell attach configuration is missing "port".');
                return undefined;
            }
            config.host = config.host || '127.0.0.1';
        }

        return config;
    }
}
