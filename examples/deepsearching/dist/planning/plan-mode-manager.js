"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.PlanModeManager = void 0;
const plan_parser_1 = require("./plan-parser");
const task_executor_1 = require("./task-executor");
const i18n_1 = require("../i18n");
const prompt_turns_1 = require("../prompt-turns");
const EnhancedAIService = Java.com.ai.assistance.operit.api.chat.EnhancedAIService;
const FunctionType = Java.com.ai.assistance.operit.data.model.FunctionType;
const Unit = Java.kotlin.Unit;
const Collections = Java.java.util.Collections;
const InputProcessingStateBase = "com.ai.assistance.operit.data.model.InputProcessingState$";
const TAG = "PlanModeManager";
const THINK_TAG = /<think(?:ing)?>[\s\S]*?(<\/think(?:ing)?>|\z)/gi;
const SEARCH_TAG = /<search>[\s\S]*?(<\/search>|\z)/gi;
function removeThinkingContent(raw) {
    return raw.replace(THINK_TAG, "").replace(SEARCH_TAG, "").trim();
}
function getI18n() {
    const locale = getLang();
    return (0, i18n_1.resolveDeepSearchI18n)(locale);
}
async function collectStreamToString(stream) {
    let buffer = "";
    const collector = {
        emit: function (value) {
            buffer += String(value ?? "");
            return Unit.INSTANCE;
        }
    };
    await stream.callSuspend("collect", collector);
    return buffer;
}
function newInputProcessingState(kind, message) {
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
async function sendPlanningMessage(aiService, context, chatHistory) {
    const emptyModelParams = Collections.emptyList();
    const onTokensUpdated = (_a, _b, _c) => Unit.INSTANCE;
    const onNonFatalError = (_value) => Unit.INSTANCE;
    const stream = await aiService.callSuspend("sendMessage", context, (0, prompt_turns_1.toKotlinPromptTurnList)(chatHistory), emptyModelParams, false, true, null, false, onTokensUpdated, onNonFatalError, true);
    return collectStreamToString(stream);
}
class PlanModeManager {
    constructor(context, enhancedAIService) {
        this.isCancelled = false;
        this.context = context;
        this.enhancedAIService = enhancedAIService;
        this.taskExecutor = new task_executor_1.TaskExecutor(context, enhancedAIService);
    }
    cancel() {
        this.isCancelled = true;
        this.taskExecutor.cancelAllTasks();
        try {
            this.enhancedAIService.cancelConversation();
        }
        catch (_e) { }
        console.log(`${TAG} cancel called`);
    }
    shouldUseDeepSearchMode(message) {
        const startTime = Date.now();
        const normalized = String(message || "").trim();
        if (!normalized) {
            console.log(`${TAG} shouldUseDeepSearchMode empty message elapsedMs=${Date.now() - startTime}`);
            return false;
        }
        console.log(`${TAG} shouldUseDeepSearchMode elapsedMs=${Date.now() - startTime} matched=true mode=always_on`);
        return true;
    }
    async executeDeepSearchMode(userMessage, chatHistory, workspacePath, maxTokens, tokenUsageThreshold, onChunk) {
        this.isCancelled = false;
        let output = "";
        this.taskExecutor.setChunkEmitter(onChunk);
        const append = (chunk) => {
            output += chunk;
            if (onChunk) {
                try {
                    onChunk(chunk);
                }
                catch (_e) { }
            }
        };
        try {
            const i18n = getI18n();
            const processingState = newInputProcessingState("Processing", i18n.planModeExecutingDeepSearch);
            this.enhancedAIService
                .setInputProcessingState(processingState);
            const executionGraph = await this.generateExecutionPlan(userMessage, chatHistory, workspacePath, maxTokens, tokenUsageThreshold);
            if (this.isCancelled) {
                append(`<log>🟡 ${i18n.planModeTaskCancelled}</log>\n`);
                return output;
            }
            if (!executionGraph) {
                append(`<error>❌ ${i18n.planModeFailedToGeneratePlan}</error>\n`);
                const idleState = newInputProcessingState("Idle");
                this.enhancedAIService
                    .setInputProcessingState(idleState);
                return output;
            }
            append(`<plan>\n`);
            append(`<graph><![CDATA[${JSON.stringify(executionGraph)}]]></graph>\n`);
            const executingState = newInputProcessingState("Processing", i18n.planModeExecutingSubtasks);
            this.enhancedAIService
                .setInputProcessingState(executingState);
            const executionOutput = await this.taskExecutor.executeSubtasks(executionGraph, userMessage, chatHistory, workspacePath, maxTokens, tokenUsageThreshold);
            output += executionOutput;
            if (this.isCancelled) {
                append(`<log>🟡 ${i18n.planModeCancelling}</log>\n`);
                append(`</plan>\n`);
                return output;
            }
            append(`<log>🎯 ${i18n.planModeAllTasksCompleted}</log>\n`);
            append(`</plan>\n`);
            const summaryState = newInputProcessingState("Processing", i18n.planModeSummarizingResults);
            this.enhancedAIService
                .setInputProcessingState(summaryState);
            const summary = await this.taskExecutor.summarize(executionGraph, userMessage, chatHistory, workspacePath, maxTokens, tokenUsageThreshold);
            output += summary;
            const completedState = newInputProcessingState("Completed");
            this.enhancedAIService
                .setInputProcessingState(completedState);
            return output;
        }
        catch (e) {
            if (this.isCancelled) {
                append(`<log>🟡 ${getI18n().planModeCancelled}</log>\n`);
            }
            else {
                append(`<error>❌ ${getI18n().planModeExecutionFailed}: ${String(e)}</error>\n`);
            }
            const idleState = newInputProcessingState("Idle");
            this.enhancedAIService
                .setInputProcessingState(idleState);
            return output;
        }
        finally {
            this.isCancelled = false;
            this.taskExecutor.setChunkEmitter(undefined);
        }
    }
    buildPlanningRequest(userMessage) {
        const i18n = getI18n();
        return `${i18n.planGenerationPrompt}\n\n${i18n.planGenerationUserRequestPrefix}${userMessage}`.trim();
    }
    async generateExecutionPlan(userMessage, _chatHistory, _workspacePath, _maxTokens, _tokenUsageThreshold) {
        try {
            const planningRequest = this.buildPlanningRequest(userMessage);
            const planningHistory = [
                (0, prompt_turns_1.createPromptTurn)("SYSTEM", planningRequest),
                (0, prompt_turns_1.createPromptTurn)("USER", getI18n().planGenerateDetailedPlan),
            ];
            const aiService = await EnhancedAIService.callSuspend("getAIServiceForFunction", this.context, FunctionType.CHAT);
            const planResponseRaw = await sendPlanningMessage(aiService, this.context, planningHistory);
            const planResponse = removeThinkingContent(String(planResponseRaw ?? "").trim());
            console.log(`${TAG} plan response`, planResponse);
            const graph = (0, plan_parser_1.parseExecutionGraph)(planResponse);
            return graph;
        }
        catch (e) {
            console.log(`${TAG} generate plan error`, String(e));
            return null;
        }
    }
}
exports.PlanModeManager = PlanModeManager;
