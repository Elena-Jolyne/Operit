import type { ExecutionGraph } from "./PlanModels";
import { parseExecutionGraph } from "./PlanParser";
import { TaskExecutor } from "./TaskExecutor";
import { resolveDeepSearchI18n } from "./i18n";

const EnhancedAIService = Java.com.ai.assistance.operit.api.chat.EnhancedAIService;
const FunctionType = Java.com.ai.assistance.operit.data.model.FunctionType;
const EmptyCoroutineContext = Java.kotlin.coroutines.EmptyCoroutineContext;
const BuildersKt = Java.kotlinx.coroutines.BuildersKt;
const Unit = Java.kotlin.Unit;
const ArrayList = Java.java.util.ArrayList;
const Pair = Java.kotlin.Pair;
const Collections = Java.java.util.Collections;
const InputProcessingStateBase = "com.ai.assistance.operit.data.model.InputProcessingState$";

const TAG = "PlanModeManager";

const THINK_TAG = /<think(?:ing)?>[\s\S]*?(<\/think(?:ing)?>|\z)/gi;
const SEARCH_TAG = /<search>[\s\S]*?(<\/search>|\z)/gi;

function removeThinkingContent(raw: string): string {
  return raw.replace(THINK_TAG, "").replace(SEARCH_TAG, "").trim();
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

function newInputProcessingState(kind: string, message?: string) {
  const base = InputProcessingStateBase;
  if (kind === "Idle") {
    const idleCls = Java.type(base + "Idle");
    return idleCls.INSTANCE;
  }
  if (kind === "Completed") {
    const completedCls = Java.type(base + "Completed");
    return completedCls.INSTANCE;
  }
  return Java.newInstance(base + kind, String(message ?? ""));
}

function sendPlanningMessageBlocking(
  aiService: unknown,
  context: unknown,
  message: string,
  chatHistory: Array<[string, string]>
): string {
  const emptyModelParams = Collections.emptyList();
  const onTokensUpdated = (_a: number, _b: number, _c: number) => {};
  const onNonFatalError = (_value: string) => {};
  const stream = runBlocking((_scope, continuation) => {
    return (aiService as { sendMessage: (...args: unknown[]) => unknown }).sendMessage(
      context,
      message,
      toKotlinPairList(chatHistory),
      emptyModelParams,
      false,
      true,
      null,
      false,
      onTokensUpdated,
      onNonFatalError,
      continuation
    );
  });
  return collectStreamToString(stream);
}

export class PlanModeManager {
  private taskExecutor: TaskExecutor;
  private isCancelled = false;
  private context: unknown;
  private enhancedAIService: unknown;

  constructor(context: unknown, enhancedAIService: unknown) {
    this.context = context;
    this.enhancedAIService = enhancedAIService;
    this.taskExecutor = new TaskExecutor(context, enhancedAIService);
  }

  cancel() {
    this.isCancelled = true;
    this.taskExecutor.cancelAllTasks();
    try {
      (this.enhancedAIService as { cancelConversation: () => void }).cancelConversation();
    } catch (_e) {}
    console.log(`${TAG} cancel called`);
  }

  shouldUseDeepSearchMode(message: string): boolean {
    const normalized = String(message || "").trim();
    if (!normalized) return false;
    const i18n = getI18n();

    const indicators = (i18n.complexityIndicators || [])
      .map(item => String(item || "").trim())
      .filter(Boolean);

    return indicators.some(ind => normalized.toLowerCase().indexOf(ind.toLowerCase()) >= 0);
  }

  async executeDeepSearchMode(
    userMessage: string,
    chatHistory: Array<[string, string]>,
    workspacePath: string | null | undefined,
    maxTokens: number,
    tokenUsageThreshold: number
  ): Promise<string> {
    this.isCancelled = false;
    let output = "";
    try {
      const i18n = getI18n();
      const processingState = newInputProcessingState(
        "Processing",
        i18n.planModeExecutingDeepSearch
      );
      try {
        (this.enhancedAIService as { setInputProcessingState: (s: unknown) => void })
          .setInputProcessingState(processingState);
      } finally {
        try { if (processingState) Java.release(processingState); } catch (_e) {}
      }

      output += `<log>🧠 ${i18n.planModeStarting}</log>\n`;
      output += `<log>📊 ${i18n.planModeAnalyzingRequest}</log>\n`;

      const executionGraph = await this.generateExecutionPlan(
        userMessage,
        chatHistory,
        workspacePath,
        maxTokens,
        tokenUsageThreshold
      );

      if (this.isCancelled) {
        output += `<log>🟡 ${i18n.planModeTaskCancelled}</log>\n`;
        return output;
      }

      if (!executionGraph) {
        output += `<error>❌ ${i18n.planModeFailedToGeneratePlan}</error>\n`;
        const idleState = newInputProcessingState("Idle");
        try {
          (this.enhancedAIService as { setInputProcessingState: (s: unknown) => void })
            .setInputProcessingState(idleState);
        } finally {
          try { if (idleState) Java.release(idleState); } catch (_e) {}
        }
        return output;
      }

      output += `<plan>\n`;
      output += `<graph><![CDATA[${JSON.stringify(executionGraph)}]]></graph>\n`;

      const executingState = newInputProcessingState(
        "Processing",
        i18n.planModeExecutingSubtasks
      );
      try {
        (this.enhancedAIService as { setInputProcessingState: (s: unknown) => void })
          .setInputProcessingState(executingState);
      } finally {
        try { if (executingState) Java.release(executingState); } catch (_e) {}
      }

      const executionOutput = this.taskExecutor.executeSubtasks(
        executionGraph,
        userMessage,
        chatHistory,
        workspacePath,
        maxTokens,
        tokenUsageThreshold
      );
      output += executionOutput;

      if (this.isCancelled) {
        output += `<log>🟡 ${i18n.planModeCancelling}</log>\n`;
        output += `</plan>\n`;
        return output;
      }

      output += `<log>🎯 ${i18n.planModeAllTasksCompleted}</log>\n`;
      output += `</plan>\n`;

      const summaryState = newInputProcessingState(
        "Processing",
        i18n.planModeSummarizingResults
      );
      try {
        (this.enhancedAIService as { setInputProcessingState: (s: unknown) => void })
          .setInputProcessingState(summaryState);
      } finally {
        try { if (summaryState) Java.release(summaryState); } catch (_e) {}
      }

      const summary = this.taskExecutor.summarize(
        executionGraph,
        userMessage,
        chatHistory,
        workspacePath,
        maxTokens,
        tokenUsageThreshold
      );
      output += summary;

      const completedState = newInputProcessingState("Completed");
      try {
        (this.enhancedAIService as { setInputProcessingState: (s: unknown) => void })
          .setInputProcessingState(completedState);
      } finally {
        try { if (completedState) Java.release(completedState); } catch (_e) {}
      }

      return output;
    } catch (e) {
      if (this.isCancelled) {
        output += `<log>🟡 ${getI18n().planModeCancelled}</log>\n`;
      } else {
        output += `<error>❌ ${getI18n().planModeExecutionFailed}: ${String(e)}</error>\n`;
      }
      const idleState = newInputProcessingState("Idle");
      try {
        (this.enhancedAIService as { setInputProcessingState: (s: unknown) => void })
          .setInputProcessingState(idleState);
      } finally {
        try { if (idleState) Java.release(idleState); } catch (_e) {}
      }
      return output;
    } finally {
      this.isCancelled = false;
    }
  }

  private buildPlanningRequest(userMessage: string): string {
    const i18n = getI18n();
    return `${i18n.planGenerationPrompt}\n\n${i18n.planGenerationUserRequestPrefix}${userMessage}`.trim();
  }

  private async generateExecutionPlan(
    userMessage: string,
    chatHistory: Array<[string, string]>,
    _workspacePath: string | null | undefined,
    _maxTokens: number,
    _tokenUsageThreshold: number
  ): Promise<ExecutionGraph | null> {
    try {
      const planningRequest = this.buildPlanningRequest(userMessage);
      const planningHistory: Array<[string, string]> = [["system", planningRequest]];

      const aiService = await EnhancedAIService.callSuspend(
        "getAIServiceForFunction",
        this.context,
        FunctionType.CHAT
      );

      try {
        const planResponseRaw = sendPlanningMessageBlocking(
          aiService,
          this.context,
          getI18n().planGenerateDetailedPlan,
          planningHistory
        );
        const planResponse = removeThinkingContent(String(planResponseRaw ?? "").trim());
        console.log(`${TAG} plan response`, planResponse);

        const graph = parseExecutionGraph(planResponse);
        return graph;
      } finally {
        try {
          if (aiService) Java.release(aiService as any);
        } catch (_e) {}
      }
    } catch (e) {
      console.log(`${TAG} generate plan error`, String(e));
      return null;
    }
  }
}
