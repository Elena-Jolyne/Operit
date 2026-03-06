import type { ExecutionGraph, TaskNode } from "./PlanModels";
import { topologicalSort, validateExecutionGraph } from "./PlanParser";
import { resolveDeepSearchI18n } from "./i18n";
const FunctionType = Java.com.ai.assistance.operit.data.model.FunctionType;
const PromptFunctionType = Java.com.ai.assistance.operit.data.model.PromptFunctionType;
const SystemPromptConfig = Java.com.ai.assistance.operit.core.config.SystemPromptConfig;
const EmptyCoroutineContext = Java.kotlin.coroutines.EmptyCoroutineContext;
const BuildersKt = Java.kotlinx.coroutines.BuildersKt;
const Unit = Java.kotlin.Unit;
const ArrayList = Java.java.util.ArrayList;
const Pair = Java.kotlin.Pair;

const TAG = "TaskExecutor";

const TOOL_TAG = /<tool\b[\s\S]*?<\/tool>/gi;
const TOOL_SELF_CLOSING = /<tool\b[^>]*\/>/gi;
const TOOL_RESULT_TAG = /<tool_result\b[\s\S]*?<\/tool_result>/gi;
const TOOL_RESULT_SELF = /<tool_result\b[^>]*\/>/gi;
const STATUS_TAG = /<status\b[\s\S]*?<\/status>/gi;
const STATUS_SELF = /<status\b[^>]*\/>/gi;
const THINK_TAG = /<think(?:ing)?>[\s\S]*?(<\/think(?:ing)?>|\z)/gi;
const SEARCH_TAG = /<search>[\s\S]*?(<\/search>|\z)/gi;

function removeThinkingContent(raw: string): string {
  return raw.replace(THINK_TAG, "").replace(SEARCH_TAG, "").trim();
}

function stripMarkup(text: string): string {
  return text
    .replace(TOOL_TAG, "")
    .replace(TOOL_SELF_CLOSING, "")
    .replace(TOOL_RESULT_TAG, "")
    .replace(TOOL_RESULT_SELF, "")
    .replace(STATUS_TAG, "")
    .replace(STATUS_SELF, "")
    .trim();
}

function extractFinalNonToolAssistantContent(raw: string): string {
  const noThinking = removeThinkingContent(raw.trim());
  const lastToolLike = /(<tool\s+name="([^"]+)"[\s\S]*?<\/tool>)|(<tool_result([^>]*)>[\s\S]*?<\/tool_result>)/gi;
  let lastMatch: RegExpExecArray | null = null;
  let match: RegExpExecArray | null;
  while ((match = lastToolLike.exec(noThinking)) !== null) {
    lastMatch = match;
  }
  const tail = lastMatch ? noThinking.substring((lastMatch.index || 0) + lastMatch[0].length) : noThinking;
  const tailStripped = stripMarkup(tail);
  if (tailStripped) return tailStripped;

  const fullStripped = stripMarkup(noThinking);
  if (!fullStripped) return "";
  const parts = fullStripped.split(/\n\s*\n+/).map(s => s.trim()).filter(Boolean);
  return parts.length > 0 ? parts[parts.length - 1] : fullStripped;
}

function getI18n() {
  const locale = typeof getLang === "function" ? getLang() : "";
  return resolveDeepSearchI18n(locale);
}

function getEmptyCoroutineContext() {
  return EmptyCoroutineContext && EmptyCoroutineContext.INSTANCE ? EmptyCoroutineContext.INSTANCE : EmptyCoroutineContext;
}

function runBlocking(block: (scope: unknown, continuation: unknown) => unknown): unknown {
  return BuildersKt.runBlocking(getEmptyCoroutineContext(), (scope: unknown, continuation: unknown) => {
    return block(scope, continuation);
  });
}

function collectStreamToString(stream: unknown): string {
  let buffer = "";
  const collector = {
    emit: function (value: string, _continuation: unknown) {
      buffer += String(value ?? "");
      return Unit.INSTANCE;
    }
  };
  runBlocking((_scope, continuation) => {
    return (stream as { collect: (...args: unknown[]) => unknown }).collect(collector, continuation);
  });
  return buffer;
}

function toKotlinPairList(history: Array<[string, string]>): unknown {
  const list = new ArrayList();
  (history || []).forEach((item) => {
    const role = item && item.length > 0 ? String(item[0] ?? "") : "";
    const content = item && item.length > 1 ? String(item[1] ?? "") : "";
    list.add(new Pair(role, content));
  });
  return list;
}

function sendMessageBlocking(
  enhancedAIService: unknown,
  options: {
    message: string;
    chatHistory: Array<[string, string]>;
    workspacePath?: string | null;
    maxTokens: number;
    tokenUsageThreshold: number;
    customSystemPromptTemplate?: string | null;
    isSubTask: boolean;
    onToolInvocation?: (toolName: string) => void;
  }
): string {
  const onNonFatalError = (_value: string) => {};
  const onToolInvocation = options.onToolInvocation
    ? (toolName: string) => options.onToolInvocation?.(toolName)
    : null;

  const stream = runBlocking((_scope, continuation) => {
    return (enhancedAIService as { sendMessage: (...args: unknown[]) => unknown }).sendMessage(
      options.message,
      null,
      toKotlinPairList(options.chatHistory),
      options.workspacePath ?? null,
      null,
      FunctionType.CHAT,
      PromptFunctionType.CHAT,
      false,
      false,
      false,
      options.maxTokens,
      options.tokenUsageThreshold,
      onNonFatalError,
      null,
      options.customSystemPromptTemplate ?? null,
      options.isSubTask,
      null,
      null,
      null,
      false,
      null,
      onToolInvocation,
      null,
      null,
      true,
      continuation
    );
  });
  return collectStreamToString(stream);
}

export class TaskExecutor {
  private taskResults: Record<string, string> = {};
  private isCancelled = false;
  private context: unknown;
  private enhancedAIService: unknown;

  constructor(context: unknown, enhancedAIService: unknown) {
    this.context = context;
    this.enhancedAIService = enhancedAIService;
  }

  cancelAllTasks() {
    this.isCancelled = true;
    this.taskResults = {};
  }

  executeSubtasks(
    graph: ExecutionGraph,
    originalMessage: string,
    chatHistory: Array<[string, string]>,
    workspacePath: string | null | undefined,
    maxTokens: number,
    tokenUsageThreshold: number
  ): string {
    this.isCancelled = false;
    this.taskResults = {};

    const validation = validateExecutionGraph(graph);
    if (!validation.ok) {
      return `<error>❌ ${getI18n().planErrorGraphValidationFailed}: ${validation.error}</error>\n`;
    }

    const sortedTasks = topologicalSort(graph);
    if (sortedTasks.length === 0) {
      return `<error>❌ ${getI18n().planErrorTopologicalSortFailed}</error>\n`;
    }

    let output = "";
    output += `<log>📋 ${getI18n().planLogStartingExecution(String(sortedTasks.length))}</log>\n`;

    const completed = new Set<string>();
    const pending = [...sortedTasks];

    while (pending.length > 0 && !this.isCancelled) {
      const ready = pending.filter(task => (task.dependencies || []).every(dep => completed.has(dep)));
      if (ready.length === 0) {
        output += `<error>❌ ${getI18n().planErrorNoExecutableTasks}</error>\n`;
        break;
      }

      for (const task of ready) {
        const res = this.executeTask(task, originalMessage, chatHistory, workspacePath, maxTokens, tokenUsageThreshold);
        output += res;
        if (this.isCancelled) break;
      }

      if (this.isCancelled) break;

      ready.forEach(task => {
        completed.add(task.id);
        const idx = pending.findIndex(t => t.id === task.id);
        if (idx >= 0) pending.splice(idx, 1);
      });
    }

    this.isCancelled = false;
    return output;
  }

  summarize(
    graph: ExecutionGraph,
    originalMessage: string,
    chatHistory: Array<[string, string]>,
    workspacePath: string | null | undefined,
    maxTokens: number,
    tokenUsageThreshold: number
  ): string {
    try {
      return this.executeFinalSummary(graph, originalMessage, chatHistory, workspacePath, maxTokens, tokenUsageThreshold);
    } catch (e) {
      console.log(`${TAG} summary error`, String(e));
      return `${getI18n().planErrorSummaryFailed}: ${String(e)}`;
    }
  }

  private executeTask(
    task: TaskNode,
    originalMessage: string,
    _chatHistory: Array<[string, string]>,
    workspacePath: string | null | undefined,
    maxTokens: number,
    tokenUsageThreshold: number
  ): string {
    if (this.isCancelled) {
      return `<update id="${task.id}" status="FAILED" error="${getI18n().planErrorTaskCancelled}"/>\n`;
    }

    const outputParts: string[] = [];
    let toolCount = 0;

    outputParts.push(`<update id="${task.id}" status="IN_PROGRESS" tool_count="0"/>\n`);

    const contextInfo = this.buildTaskContext(task, originalMessage);
    const fullInstruction = this.buildFullInstruction(task, contextInfo);

    try {
      const raw = sendMessageBlocking(this.enhancedAIService, {
        message: fullInstruction,
        chatHistory: [],
        workspacePath: workspacePath ?? null,
        maxTokens,
        tokenUsageThreshold,
        customSystemPromptTemplate: String(
          SystemPromptConfig.SUBTASK_AGENT_PROMPT_TEMPLATE || ""
        ),
        isSubTask: true,
        onToolInvocation: (toolName: string) => {
          toolCount += 1;
          outputParts.push(
            `<update id="${task.id}" status="IN_PROGRESS" tool_count="${toolCount}"/>\n`
          );
        }
      });

      const finalText = extractFinalNonToolAssistantContent(raw);
      this.taskResults[task.id] = finalText;
      outputParts.push(`<update id="${task.id}" status="COMPLETED" tool_count="${toolCount}"/>\n`);
    } catch (e) {
      const errMsg = String(e || "Unknown error").replace(/"/g, "&quot;");
      outputParts.push(
        `<update id="${task.id}" status="FAILED" tool_count="${toolCount}" error="${errMsg}"/>\n`
      );
      this.taskResults[task.id] = getI18n().taskErrorExecutionFailed(String(e || ""));
    }

    return outputParts.join("");
  }

  private buildTaskContext(task: TaskNode, originalMessage: string): string {
    let contextText = "";
    contextText += `${getI18n().taskContextOriginalRequest(originalMessage)}\n`;
    contextText += `${getI18n().taskContextCurrentTask(task.name)}\n`;

    if ((task.dependencies || []).length > 0) {
      contextText += `${getI18n().taskContextDependencyResults}\n`;
      task.dependencies.forEach(depId => {
        const depResult = this.taskResults[depId];
        if (depResult) {
          contextText += `${getI18n().taskContextTaskResult(depId, depResult)}\n`;
        }
      });
    }

    return contextText;
  }

  private buildFullInstruction(task: TaskNode, contextInfo: string): string {
    return getI18n().taskInstructionWithContext(contextInfo, task.instruction).trim();
  }

  private executeFinalSummary(
    graph: ExecutionGraph,
    originalMessage: string,
    chatHistory: Array<[string, string]>,
    workspacePath: string | null | undefined,
    maxTokens: number,
    tokenUsageThreshold: number
  ): string {
    const summaryContext = this.buildSummaryContext(originalMessage, graph);
    const i18n = getI18n();
    const fullSummaryInstruction = `${summaryContext}\n\n${i18n.finalSummaryInstructionPrefix}\n${graph.finalSummaryInstruction}\n\n${i18n.finalSummaryInstructionSuffix}`;

    return sendMessageBlocking(this.enhancedAIService, {
      message: fullSummaryInstruction,
      chatHistory,
      workspacePath: workspacePath ?? null,
      maxTokens,
      tokenUsageThreshold,
      customSystemPromptTemplate: null,
      isSubTask: false
    });
  }

  private buildSummaryContext(originalMessage: string, graph: ExecutionGraph): string {
    let contextText = "";
    contextText += `${getI18n().taskContextOriginalRequest(originalMessage)}\n`;

    const allDependencyIds = new Set<string>();
    (graph.tasks || []).forEach(task => (task.dependencies || []).forEach(dep => allDependencyIds.add(dep)));
    const allTaskIds = new Set<string>((graph.tasks || []).map(t => t.id));
    const leafTaskIds = Array.from(allTaskIds).filter(id => !allDependencyIds.has(id));

    contextText += `${getI18n().taskSummaryKeyResults}\n`;

    const taskIdsToSummarize = leafTaskIds.length > 0 ? leafTaskIds : Array.from(allTaskIds);
    taskIdsToSummarize.forEach(taskId => {
      const result = this.taskResults[taskId];
      if (result) {
        const task = (graph.tasks || []).find(t => t.id === taskId);
        const taskName = task ? task.name : taskId;
        contextText += `- ${taskName}: ${result}\n\n`;
      }
    });

    return contextText;
  }
}
