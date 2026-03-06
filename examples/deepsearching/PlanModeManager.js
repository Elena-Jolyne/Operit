"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.PlanModeManager = void 0;
const PlanParser_1 = require("./PlanParser");
const TaskExecutor_1 = require("./TaskExecutor");
const i18n_1 = require("./i18n");
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
function removeThinkingContent(raw) {
    return raw.replace(THINK_TAG, "").replace(SEARCH_TAG, "").trim();
}
function getI18n() {
    const locale = typeof getLang === "function" ? getLang() : "";
    return (0, i18n_1.resolveDeepSearchI18n)(locale);
}
function getEmptyCoroutineContext() {
    return EmptyCoroutineContext && EmptyCoroutineContext.INSTANCE ? EmptyCoroutineContext.INSTANCE : EmptyCoroutineContext;
}
function runBlocking(block) {
    return BuildersKt.runBlocking(getEmptyCoroutineContext(), (scope, continuation) => {
        return block(scope, continuation);
    });
}
function collectStreamToString(stream) {
    let buffer = "";
    const collector = {
        emit: function (value, _continuation) {
            buffer += String(value !== null && value !== void 0 ? value : "");
            return Unit.INSTANCE;
        }
    };
    runBlocking((_scope, continuation) => {
        return stream.collect(collector, continuation);
    });
    return buffer;
}
function toKotlinPairList(history) {
    const list = new ArrayList();
    (history || []).forEach((item) => {
        var _d, _f;
        const role = item && item.length > 0 ? String((_d = item[0]) !== null && _d !== void 0 ? _d : "") : "";
        const content = item && item.length > 1 ? String((_f = item[1]) !== null && _f !== void 0 ? _f : "") : "";
        list.add(new Pair(role, content));
    });
    return list;
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
    return Java.newInstance(base + kind, String(message !== null && message !== void 0 ? message : ""));
}
function sendPlanningMessageBlocking(aiService, context, message, chatHistory) {
    const emptyModelParams = Collections.emptyList();
    const onTokensUpdated = (_a, _b, _c) => { };
    const onNonFatalError = (_value) => { };
    const stream = runBlocking((_scope, continuation) => {
        return aiService.sendMessage(context, message, toKotlinPairList(chatHistory), emptyModelParams, false, true, null, false, onTokensUpdated, onNonFatalError, continuation);
    });
    return collectStreamToString(stream);
}
class PlanModeManager {
    constructor(context, enhancedAIService) {
        this.isCancelled = false;
        this.context = context;
        this.enhancedAIService = enhancedAIService;
        this.taskExecutor = new TaskExecutor_1.TaskExecutor(context, enhancedAIService);
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
        const normalized = String(message || "").trim();
        if (!normalized)
            return false;
        const i18n = getI18n();
        const indicators = (i18n.complexityIndicators || [])
            .map(item => String(item || "").trim())
            .filter(Boolean);
        return indicators.some(ind => normalized.toLowerCase().indexOf(ind.toLowerCase()) >= 0);
    }
    async executeDeepSearchMode(userMessage, chatHistory, workspacePath, maxTokens, tokenUsageThreshold) {
        this.isCancelled = false;
        let output = "";
        try {
            const i18n = getI18n();
            const processingState = newInputProcessingState("Processing", i18n.planModeExecutingDeepSearch);
            try {
                this.enhancedAIService
                    .setInputProcessingState(processingState);
            }
            finally {
                try {
                    if (processingState)
                        Java.release(processingState);
                }
                catch (_e) { }
            }
            output += `<log>🧠 ${i18n.planModeStarting}</log>\n`;
            output += `<log>📊 ${i18n.planModeAnalyzingRequest}</log>\n`;
            const executionGraph = await this.generateExecutionPlan(userMessage, chatHistory, workspacePath, maxTokens, tokenUsageThreshold);
            if (this.isCancelled) {
                output += `<log>🟡 ${i18n.planModeTaskCancelled}</log>\n`;
                return output;
            }
            if (!executionGraph) {
                output += `<error>❌ ${i18n.planModeFailedToGeneratePlan}</error>\n`;
                const idleState = newInputProcessingState("Idle");
                try {
                    this.enhancedAIService
                        .setInputProcessingState(idleState);
                }
                finally {
                    try {
                        if (idleState)
                            Java.release(idleState);
                    }
                    catch (_e) { }
                }
                return output;
            }
            output += `<plan>\n`;
            output += `<graph><![CDATA[${JSON.stringify(executionGraph)}]]></graph>\n`;
            const executingState = newInputProcessingState("Processing", i18n.planModeExecutingSubtasks);
            try {
                this.enhancedAIService
                    .setInputProcessingState(executingState);
            }
            finally {
                try {
                    if (executingState)
                        Java.release(executingState);
                }
                catch (_e) { }
            }
            const executionOutput = this.taskExecutor.executeSubtasks(executionGraph, userMessage, chatHistory, workspacePath, maxTokens, tokenUsageThreshold);
            output += executionOutput;
            if (this.isCancelled) {
                output += `<log>🟡 ${i18n.planModeCancelling}</log>\n`;
                output += `</plan>\n`;
                return output;
            }
            output += `<log>🎯 ${i18n.planModeAllTasksCompleted}</log>\n`;
            output += `</plan>\n`;
            const summaryState = newInputProcessingState("Processing", i18n.planModeSummarizingResults);
            try {
                this.enhancedAIService
                    .setInputProcessingState(summaryState);
            }
            finally {
                try {
                    if (summaryState)
                        Java.release(summaryState);
                }
                catch (_e) { }
            }
            const summary = this.taskExecutor.summarize(executionGraph, userMessage, chatHistory, workspacePath, maxTokens, tokenUsageThreshold);
            output += summary;
            const completedState = newInputProcessingState("Completed");
            try {
                this.enhancedAIService
                    .setInputProcessingState(completedState);
            }
            finally {
                try {
                    if (completedState)
                        Java.release(completedState);
                }
                catch (_e) { }
            }
            return output;
        }
        catch (e) {
            if (this.isCancelled) {
                output += `<log>🟡 ${getI18n().planModeCancelled}</log>\n`;
            }
            else {
                output += `<error>❌ ${getI18n().planModeExecutionFailed}: ${String(e)}</error>\n`;
            }
            const idleState = newInputProcessingState("Idle");
            try {
                this.enhancedAIService
                    .setInputProcessingState(idleState);
            }
            finally {
                try {
                    if (idleState)
                        Java.release(idleState);
                }
                catch (_e) { }
            }
            return output;
        }
        finally {
            this.isCancelled = false;
        }
    }
    buildPlanningRequest(userMessage) {
        const i18n = getI18n();
        return `${i18n.planGenerationPrompt}\n\n${i18n.planGenerationUserRequestPrefix}${userMessage}`.trim();
    }
    async generateExecutionPlan(userMessage, chatHistory, _workspacePath, _maxTokens, _tokenUsageThreshold) {
        try {
            const planningRequest = this.buildPlanningRequest(userMessage);
            const planningHistory = [["system", planningRequest]];
            const aiService = await EnhancedAIService.callSuspend("getAIServiceForFunction", this.context, FunctionType.CHAT);
            try {
                const planResponseRaw = sendPlanningMessageBlocking(aiService, this.context, getI18n().planGenerateDetailedPlan, planningHistory);
                const planResponse = removeThinkingContent(String(planResponseRaw !== null && planResponseRaw !== void 0 ? planResponseRaw : "").trim());
                console.log(`${TAG} plan response`, planResponse);
                const graph = (0, PlanParser_1.parseExecutionGraph)(planResponse);
                return graph;
            }
            finally {
                try {
                    if (aiService)
                        Java.release(aiService);
                }
                catch (_e) { }
            }
        }
        catch (e) {
            console.log(`${TAG} generate plan error`, String(e));
            return null;
        }
    }
}
exports.PlanModeManager = PlanModeManager;
