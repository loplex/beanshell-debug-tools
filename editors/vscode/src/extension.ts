// noinspection JSUnusedGlobalSymbols

import * as vscode from 'vscode';
import { BshConfigurationProvider } from './configurationProvider';
import { BshDebugAdapterDescriptorFactory } from './descriptorFactory';

export function activate(context: vscode.ExtensionContext): void {
    context.subscriptions.push(
        vscode.debug.registerDebugConfigurationProvider('bsh', new BshConfigurationProvider())
    );

    const factory = new BshDebugAdapterDescriptorFactory();
    context.subscriptions.push(
        vscode.debug.registerDebugAdapterDescriptorFactory('bsh', factory),
        vscode.debug.onDidTerminateDebugSession((session) => factory.terminate(session)),
        factory
    );
}

export function deactivate(): void {
    // Registrations are disposed through context.subscriptions.
}
