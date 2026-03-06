"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.supportsPlanTag = supportsPlanTag;
exports.renderPlanXml = renderPlanXml;
const PlanExecutionRenderer_js_1 = __importDefault(require("./PlanExecutionRenderer.js"));
function supportsPlanTag(tagName) {
    return String(tagName || "").toLowerCase() === "plan";
}
function renderPlanXml(xmlContent, tagName) {
    if (!supportsPlanTag(tagName || "plan")) {
        return { handled: false };
    }
    const emptyState = {};
    return {
        handled: true,
        composeDsl: {
            screen: PlanExecutionRenderer_js_1.default,
            state: { ...emptyState, xmlContent: String(xmlContent || "") },
            memo: emptyState
        }
    };
}
