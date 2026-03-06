import type { ToolParams } from './core';
import type { ComposeDslScreen, ComposeNode } from './compose-dsl';

export type ToolPkgLocalizedText = string | { [lang: string]: string };
export type ToolPkgJsonPrimitive = string | number | boolean | null;
export type ToolPkgJsonValue = ToolPkgJsonPrimitive | ToolPkgJsonValue[] | { [key: string]: ToolPkgJsonValue };
export interface ToolPkgJsonObject {
    [key: string]: ToolPkgJsonValue;
}
export type ToolPkgAppLifecycleEvent =
    | "application_on_create"
    | "application_on_foreground"
    | "application_on_background"
    | "application_on_low_memory"
    | "application_on_trim_memory"
    | "application_on_terminate"
    | "activity_on_create"
    | "activity_on_start"
    | "activity_on_resume"
    | "activity_on_pause"
    | "activity_on_stop"
    | "activity_on_destroy";
export type ToolPkgHookEventName =
    | ToolPkgAppLifecycleEvent
    | "message_processing"
    | "xml_render"
    | "input_menu_toggle";

export type ToolPkgHookReturn = ToolPkgJsonValue | void | Promise<ToolPkgJsonValue | void>;

export type ToolPkgHookHandler<TEvent> = (event: TEvent) => ToolPkgHookReturn;

export type ToolPkgAppLifecycleHookReturn =
    | ToolPkgJsonValue
    | void
    | Promise<ToolPkgJsonValue | void>;

export interface ToolPkgMessageProcessingHookObjectResult extends ToolPkgJsonObject {
    matched?: boolean;
    text?: string;
    content?: string;
    chunks?: string[];
}

export type ToolPkgMessageProcessingHookReturnValue =
    | boolean
    | string
    | ToolPkgMessageProcessingHookObjectResult
    | null
    | void;

export type ToolPkgMessageProcessingHookReturn =
    | ToolPkgMessageProcessingHookReturnValue
    | Promise<ToolPkgMessageProcessingHookReturnValue>;

export interface ToolPkgXmlRenderHookObjectResult {
    handled?: boolean;
    text?: string;
    content?: string;
    composeDsl?: {
        screen: ComposeDslScreen;
        state?: ToolPkgJsonObject;
        memo?: ToolPkgJsonObject;
        moduleSpec?: ToolPkgJsonObject;
    };
}

export type ToolPkgXmlRenderHookReturn =
    | string
    | ToolPkgXmlRenderHookObjectResult
    | null
    | void
    | Promise<string | ToolPkgXmlRenderHookObjectResult | null | void>;

export interface ToolPkgInputMenuToggleDefinitionResult extends ToolPkgJsonObject {
    id: string;
    title: string;
    description?: string;
    isChecked?: boolean;
}

export interface ToolPkgInputMenuToggleObjectResult extends ToolPkgJsonObject {
    toggles?: ToolPkgInputMenuToggleDefinitionResult[];
}

export type ToolPkgInputMenuToggleHookReturn =
    | ToolPkgInputMenuToggleDefinitionResult[]
    | ToolPkgInputMenuToggleObjectResult
    | null
    | void
    | Promise<ToolPkgInputMenuToggleDefinitionResult[] | ToolPkgInputMenuToggleObjectResult | null | void>;

export type ToolPkgAppLifecycleHookHandler =
    (event: ToolPkgAppLifecycleHookEvent) => ToolPkgAppLifecycleHookReturn;

export type ToolPkgMessageProcessingHookHandler =
    (event: ToolPkgMessageProcessingHookEvent) =>
        ToolPkgMessageProcessingHookReturnValue
        | Promise<ToolPkgMessageProcessingHookReturnValue>;

export type ToolPkgXmlRenderHookHandler =
    (event: ToolPkgXmlRenderHookEvent) => ToolPkgXmlRenderHookReturn;

export type ToolPkgInputMenuToggleHookHandler =
    (event: ToolPkgInputMenuToggleHookEvent) => ToolPkgInputMenuToggleHookReturn;

export interface ToolPkgHookEventBase<
    TEventName extends string,
    TPayload extends ToolPkgJsonObject = ToolPkgJsonObject
> {
    event: TEventName;
    eventName: TEventName;
    eventPayload: TPayload;
    toolPkgId?: string;
    containerPackageName?: string;
    functionName?: string;
    pluginId?: string;
    hookId?: string;
    timestampMs?: number;
}

export interface ToolPkgAppLifecycleEventPayload extends ToolPkgJsonObject {
    extras?: ToolPkgJsonObject;
}

export interface ToolPkgMessageProcessingEventPayload extends ToolPkgJsonObject {
    messageContent?: string;
    chatHistory?: Array<[string, string]>;
    workspacePath?: string;
    maxTokens?: number;
    tokenUsageThreshold?: number;
}

export interface ToolPkgXmlRenderEventPayload extends ToolPkgJsonObject {
    xmlContent?: string;
    tagName?: string;
}

export interface ToolPkgInputMenuToggleEventPayload extends ToolPkgJsonObject {
    action?: "create" | "toggle" | string;
    toggleId?: string;
}

export interface ToolPkgAppLifecycleHookEvent
    extends ToolPkgHookEventBase<ToolPkgAppLifecycleEvent, ToolPkgAppLifecycleEventPayload> {
    extras?: ToolPkgJsonObject;
}

export interface ToolPkgMessageProcessingHookEvent
    extends ToolPkgHookEventBase<"message_processing", ToolPkgMessageProcessingEventPayload> {
    messageContent?: string;
    chatHistory?: Array<[string, string]>;
    workspacePath?: string;
    maxTokens?: number;
    tokenUsageThreshold?: number;
}

export interface ToolPkgXmlRenderHookEvent
    extends ToolPkgHookEventBase<"xml_render", ToolPkgXmlRenderEventPayload> {
    xmlContent?: string;
    tagName?: string;
}

export interface ToolPkgInputMenuToggleHookEvent
    extends ToolPkgHookEventBase<"input_menu_toggle", ToolPkgInputMenuToggleEventPayload> {
    action?: "create" | "toggle" | string;
    toggleId?: string;
}

export interface ToolPkgToolboxUiModuleRegistration {
    id: string;
    runtime?: string;
    screen: ComposeDslScreen;
    params?: ToolParams;
    title?: ToolPkgLocalizedText;
}

export interface ToolPkgAppLifecycleHookRegistration {
    id: string;
    event: ToolPkgAppLifecycleEvent;
    function: ToolPkgAppLifecycleHookHandler;
}

export interface ToolPkgMessageProcessingPluginRegistration {
    id: string;
    function: ToolPkgMessageProcessingHookHandler;
}

export interface ToolPkgXmlRenderPluginRegistration {
    id: string;
    tag: string;
    function: ToolPkgXmlRenderHookHandler;
}

export interface ToolPkgInputMenuTogglePluginRegistration {
    id: string;
    function: ToolPkgInputMenuToggleHookHandler;
}

export interface ToolPkgRegistry {
    registerToolboxUiModule(definition: ToolPkgToolboxUiModuleRegistration): void;
    registerAppLifecycleHook(definition: ToolPkgAppLifecycleHookRegistration): void;
    registerMessageProcessingPlugin(definition: ToolPkgMessageProcessingPluginRegistration): void;
    registerXmlRenderPlugin(definition: ToolPkgXmlRenderPluginRegistration): void;
    registerInputMenuTogglePlugin(definition: ToolPkgInputMenuTogglePluginRegistration): void;
}

declare global {
    function registerToolPkgToolboxUiModule(definition: ToolPkgToolboxUiModuleRegistration): void;

    function registerToolPkgAppLifecycleHook(definition: ToolPkgAppLifecycleHookRegistration): void;

    function registerToolPkgMessageProcessingPlugin(definition: ToolPkgMessageProcessingPluginRegistration): void;

    function registerToolPkgXmlRenderPlugin(definition: ToolPkgXmlRenderPluginRegistration): void;

    function registerToolPkgInputMenuTogglePlugin(definition: ToolPkgInputMenuTogglePluginRegistration): void;

    const ToolPkg: ToolPkgRegistry;
}
