"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.registerToolPkg = registerToolPkg;
exports.onApplicationCreate = onApplicationCreate;
exports.onMessageProcessing = onMessageProcessing;
exports.onXmlRender = onXmlRender;
exports.onInputMenuToggle = onInputMenuToggle;
const PlanModeManager_1 = require("./PlanModeManager");
const DeepSearchPlanXmlRenderPlugin_1 = require("./DeepSearchPlanXmlRenderPlugin");
const i18n_1 = require("./i18n");
const ApiPreferences = Java.com.ai.assistance.operit.data.preferences.ApiPreferences;
const EnhancedAIService = Java.com.ai.assistance.operit.api.chat.EnhancedAIService;
const FEATURE_KEY = "ai_planning";
function getAppContext() {
    if (typeof Java.getApplicationContext !== "function") {
        return null;
    }
    return Java.getApplicationContext();
}
function isDeepSearchEnabled(context) {
    return Boolean(ApiPreferences.getFeatureToggleBlocking(context, FEATURE_KEY, false));
}
function setDeepSearchEnabled(context, enabled) {
    ApiPreferences.setFeatureToggleBlocking(context, FEATURE_KEY, !!enabled);
}
function normalizePayload(input) {
    const record = input;
    if (record && record.eventPayload && typeof record.eventPayload === "object") {
        return record.eventPayload;
    }
    return record || {};
}
function getI18n() {
    const locale = typeof getLang === "function" ? getLang() : "";
    return (0, i18n_1.resolveDeepSearchI18n)(locale);
}
function registerToolPkg() {
    console.log("deepsearching registerToolPkg start");
    console.log("deepsearching skip: registerToolboxUiModule");
    ToolPkg.registerAppLifecycleHook({
        id: "deepsearching_app_create",
        event: "application_on_create",
        function: onApplicationCreate,
    });
    console.log("deepsearching registered: registerAppLifecycleHook");
    ToolPkg.registerMessageProcessingPlugin({
        id: "deepsearching_message_plugin",
        function: onMessageProcessing,
    });
    console.log("deepsearching registered: registerMessageProcessingPlugin");
    ToolPkg.registerXmlRenderPlugin({
        id: "deepsearching_xml_plan",
        tag: "plan",
        function: onXmlRender,
    });
    console.log("deepsearching registered: registerXmlRenderPlugin");
    ToolPkg.registerInputMenuTogglePlugin({
        id: "deepsearching_input_menu_toggle",
        function: onInputMenuToggle,
    });
    console.log("deepsearching registered: registerInputMenuTogglePlugin");
    console.log("deepsearching registerToolPkg done");
    return true;
}
function onApplicationCreate(input) {
    console.log("deepsearching onApplicationCreate", JSON.stringify(input !== null && input !== void 0 ? input : null));
}
async function onMessageProcessing(input) {
    var _a, _b, _c, _d;
    const payload = normalizePayload(input);
    const message = String((_a = payload.messageContent) !== null && _a !== void 0 ? _a : "").trim();
    if (!message) {
        return { matched: false };
    }
    let context = null;
    let enhancedAIService = null;
    try {
        context = getAppContext();
        if (!context)
            return { matched: false };
        const enabled = isDeepSearchEnabled(context);
        if (!enabled) {
            return { matched: false };
        }
        enhancedAIService = EnhancedAIService.getInstance(context);
        const manager = new PlanModeManager_1.PlanModeManager(context, enhancedAIService);
        const shouldUse = manager.shouldUseDeepSearchMode(message);
        if (!shouldUse) {
            return { matched: false };
        }
        const history = payload.chatHistory || [];
        const workspacePath = (_b = payload.workspacePath) !== null && _b !== void 0 ? _b : null;
        const maxTokens = Number((_c = payload.maxTokens) !== null && _c !== void 0 ? _c : 0);
        const tokenUsageThreshold = Number((_d = payload.tokenUsageThreshold) !== null && _d !== void 0 ? _d : 0);
        if (!maxTokens || !tokenUsageThreshold) {
            console.log("deepsearching missing maxTokens/tokenUsageThreshold");
            return { matched: false };
        }
        const text = await manager.executeDeepSearchMode(message, history, workspacePath, maxTokens, tokenUsageThreshold);
        if (!text) {
            return { matched: false };
        }
        return { matched: true, text };
    }
    catch (error) {
        console.log("deepsearching onMessageProcessing error", String(error));
        return { matched: false };
    }
    finally {
        try {
            if (context)
                Java.release(context);
        }
        catch (_e) { }
        try {
            if (enhancedAIService)
                Java.release(enhancedAIService);
        }
        catch (_e) { }
    }
}
function onXmlRender(event) {
    var _a, _b;
    const payload = normalizePayload(event);
    const xmlContent = String((_a = payload.xmlContent) !== null && _a !== void 0 ? _a : "");
    const tagName = String((_b = payload.tagName) !== null && _b !== void 0 ? _b : "");
    if (!xmlContent) {
        return { handled: false };
    }
    return (0, DeepSearchPlanXmlRenderPlugin_1.renderPlanXml)(xmlContent, tagName);
}
function onInputMenuToggle(input) {
    var _a;
    const payload = normalizePayload(input);
    const action = String((_a = payload.action) !== null && _a !== void 0 ? _a : "").toLowerCase();
    let context = null;
    try {
        context = getAppContext();
        if (!context)
            return [];
        if (action === "toggle") {
            const current = isDeepSearchEnabled(context);
            setDeepSearchEnabled(context, !current);
            return [];
        }
        if (action !== "create") {
            return [];
        }
        const enabled = isDeepSearchEnabled(context);
        const i18n = getI18n();
        return [
            {
                id: FEATURE_KEY,
                title: i18n.menuTitle,
                description: i18n.menuDescription,
                isChecked: enabled,
            },
        ];
    }
    catch (error) {
        console.log("deepsearching onInputMenuToggle error", String(error));
        return [];
    }
    finally {
        try {
            if (context)
                Java.release(context);
        }
        catch (_e) { }
    }
}
