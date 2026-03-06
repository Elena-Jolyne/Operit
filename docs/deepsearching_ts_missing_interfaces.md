# DeepSearching 迁移到 TS 的缺失接口清单

更新时间：2026-03-05  
范围：仅讨论 `plugins/deepsearching` 从 Kotlin 迁移到 ToolPkg/TS 所需的接口能力，不包含具体实现代码。

## 1. 目标能力（与当前 Kotlin DeepSearching 对齐）

当前 Kotlin 版本具备以下关键能力：

1. 消息处理插件可接管对话，并返回可取消的流式执行（`MessageProcessingExecution`）。
2. 规划阶段、子任务阶段、汇总阶段均为流式输出。
3. 子任务可并发执行，支持依赖调度与取消。
4. 输入菜单开关可读写通用 feature toggle（如 `ai_planning` 仅作为插件内 key）。
5. `<plan>` 标签有专门渲染能力（非普通文本）。

## 2. 现状可用接口（已具备）

ToolPkg TS 目前已经可用：

1. `registerMessageProcessingPlugin`
2. `registerXmlRenderPlugin`
3. `registerInputMenuTogglePlugin`
4. Hook 事件参数基本字段（消息、历史、workspace、token 限制）

这些接口足够做“简化版 deepsearch”，但不足以做“等价迁移”。

## 3. 必补接口（阻塞等价迁移）

## I-01 消息处理 Hook 的流式结果协议

### 问题
当前桥接把 message hook 当成“一次性返回值”处理，缺少标准化流式协议；取消控制器也是 `Noop`，无法中断插件内执行。

### 建议新增（`examples/types/toolpkg.d.ts`）

```ts
export type ToolPkgMessageProcessingStreamEvent =
  | { type: "chunk"; chunk: string }
  | { type: "status"; status: string; message?: string }
  | { type: "error"; message: string }
  | { type: "done" };

export interface ToolPkgMessageProcessingStreamResult extends ToolPkgJsonObject {
  matched: true;
  mode: "stream";
  streamId: string;
}

export interface ToolPkgMessageProcessingSingleResult extends ToolPkgJsonObject {
  matched: true;
  mode?: "single";
  text?: string;
  content?: string;
  chunks?: string[];
}

export type ToolPkgMessageProcessingHookReturn =
  | { matched: false }
  | ToolPkgMessageProcessingSingleResult
  | ToolPkgMessageProcessingStreamResult
  | null
  | void
  | Promise<
      | { matched: false }
      | ToolPkgMessageProcessingSingleResult
      | ToolPkgMessageProcessingStreamResult
      | null
      | void
    >;
```

### 运行时要求（Kotlin）

1. `runToolPkgMainHook(...)` 增加 `onIntermediateResult`，接 `JsEngine.sendIntermediateResult`。
2. `ToolPkgCommonBridgePlugin` 将中间结果按 `ToolPkgMessageProcessingStreamEvent` 解码并推送到 `Stream<String>`。
3. `MessageProcessingController.cancel()` 需真正取消该 hook 对应执行上下文。

---

## I-02 消息处理 Hook 的取消语义

### 问题
当前 TS hook 无法感知“已经取消”，也无法被消息层真正中断。

### 建议新增

1. Hook 事件 payload 增加 `executionId`。
2. 运行时提供取消查询：

```ts
declare global {
  function isExecutionCancelled(executionId?: string): boolean;
}
```

3. Kotlin 侧建立 `executionId -> JsEngine context` 映射，取消时精准中断对应执行。

---

## I-03 高级 AI 调用接口（子任务执行必需）

### 问题
`Chat.sendMessage(...)` 是高层封装，缺少 deepsearch 子任务必须参数：
`isSubTask`、`customSystemPromptTemplate`、`onToolInvocation`、更明确的流式控制。

### 建议新增（`examples/types/chat.d.ts`）

```ts
export namespace Chat {
  interface SendMessageAdvancedParams {
    message: string;
    chatId?: string;
    chatHistory?: Array<[string, string]>;
    workspacePath?: string;
    functionType?: string;
    promptFunctionType?: string;
    enableThinking?: boolean;
    thinkingGuidance?: boolean;
    enableMemoryQuery?: boolean;
    maxTokens: number;
    tokenUsageThreshold: number;
    customSystemPromptTemplate?: string;
    isSubTask?: boolean;
    stream?: boolean;
  }

  function sendMessageAdvanced(
    params: SendMessageAdvancedParams
  ): Promise<MessageSendResultData>;
}
```

### Kotlin 对应

新增 tool（如 `send_message_to_ai_advanced`），透传到 `EnhancedAIService.sendMessage(...)` 对应字段。

---

## I-04 通用 Feature Toggle 接口（插件可直接读写）

### 问题
deepsearch 迁 TS 后，菜单 toggle 不能只靠临时状态，必须有通用持久化接口；且不能在非插件位置写死 `ai_planning` 常量。

### 建议新增（`examples/types/toolpkg.d.ts`）

```ts
export interface ToolPkgFeatureToggleApi {
  get(featureKey: string, defaultValue?: boolean): Promise<boolean>;
  set(featureKey: string, enabled: boolean): Promise<void>;
  toggle(featureKey: string): Promise<boolean>;
  getMany(featureKeys: string[]): Promise<Record<string, boolean>>;
}

declare global {
  const ToolPkgFeatures: ToolPkgFeatureToggleApi;
}
```

### Kotlin 对应

1. 基于 `ApiPreferences.featureTogglesFlow/saveFeatureToggle` 暴露通用 bridge。
2. 不在通用层引入任何插件专用 key。

---

## I-05 Input Menu Toggle 事件补充 feature 快照

### 问题
当前 `input_menu_toggle` 事件只有 `action/toggleId`，插件无法直接获知 feature 当前值，容易重复读配置。

### 建议新增（`examples/types/toolpkg.d.ts`）

```ts
export interface ToolPkgInputMenuToggleEventPayload extends ToolPkgJsonObject {
  action?: "create" | "toggle" | string;
  toggleId?: string;
  featureStates?: Record<string, boolean>;
}
```

Kotlin 在触发 `create/toggle` 时填充 `featureStates` 快照。

---

## I-06 XML Render Hook 结构化返回（计划渲染增强）

### 问题
当前 bridge 仅支持 `text/content`，不利于 deepsearch 的计划图/状态渲染扩展。

### 建议新增（`examples/types/toolpkg.d.ts`）

```ts
export interface ToolPkgXmlRenderTextResult extends ToolPkgJsonObject {
  handled: true;
  mode?: "text";
  text: string;
}

export interface ToolPkgXmlRenderStructuredResult extends ToolPkgJsonObject {
  handled: true;
  mode: "structured";
  data: ToolPkgJsonObject;
}

export type ToolPkgXmlRenderHookReturn =
  | string
  | { handled: false }
  | ToolPkgXmlRenderTextResult
  | ToolPkgXmlRenderStructuredResult
  | null
  | void
  | Promise<
      | string
      | { handled: false }
      | ToolPkgXmlRenderTextResult
      | ToolPkgXmlRenderStructuredResult
      | null
      | void
    >;
```

---

## I-07 Hook 事件名类型与运行时常量对齐

### 问题
TS 类型中事件名是 `message_processing/xml_render/input_menu_toggle`，运行时常量是 `toolpkg_message_processing/toolpkg_xml_render/toolpkg_input_menu_toggle`，契约不一致。

### 建议

统一到运行时常量，避免隐式映射和歧义。

```ts
export type ToolPkgHookEventName =
  | ToolPkgAppLifecycleEvent
  | "toolpkg_message_processing"
  | "toolpkg_xml_render"
  | "toolpkg_input_menu_toggle";
```

## 4. 代码落点（后续实现时改哪里）

1. `examples/types/toolpkg.d.ts`
2. `examples/types/chat.d.ts`
3. `examples/types/index.d.ts`
4. `app/src/main/java/com/ai/assistance/operit/plugins/toolpkg/ToolPkgCommonBridgePlugin.kt`
5. `app/src/main/java/com/ai/assistance/operit/core/tools/packTool/PackageManagerToolPkgFacade.kt`
6. `app/src/main/java/com/ai/assistance/operit/core/tools/javascript/JsEngine.kt`
7. `app/src/main/java/com/ai/assistance/operit/core/tools/defaultTool/standard/StandardChatManagerTool.kt`（新增 advanced tool）

## 5. 实施顺序（建议）

1. 先改类型：`toolpkg.d.ts/chat.d.ts/index.d.ts`
2. 再打通 message hook 流式 + cancel（I-01/I-02）
3. 接入通用 feature toggle（I-04/I-05）
4. 增加 advanced chat tool（I-03）
5. 最后扩展 xml structured render（I-06）

## 6. 验收标准

1. TS deepsearch 能用流式输出完整计划执行过程，不是一次性字符串。
2. 用户中止消息时，TS deepsearch 执行可立即终止。
3. 插件可通用读写 feature toggle，不依赖 Kotlin 专用通道。
4. Hook 事件名、payload 与 runtime 常量完全一致。
5. `<plan>` 渲染至少可结构化处理，不被迫降级为纯文本。

