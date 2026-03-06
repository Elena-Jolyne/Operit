import type { ToolPkgJsonObject, ToolPkgXmlRenderHookObjectResult } from "../types/toolpkg";
import PlanExecutionRenderer from "./PlanExecutionRenderer.js";

export function supportsPlanTag(tagName: string): boolean {
  return String(tagName || "").toLowerCase() === "plan";
}

export function renderPlanXml(
  xmlContent: string,
  tagName?: string
): ToolPkgXmlRenderHookObjectResult {
  if (!supportsPlanTag(tagName || "plan")) {
    return { handled: false };
  }
  const emptyState: ToolPkgJsonObject = {};
  return {
    handled: true,
    composeDsl: {
      screen: PlanExecutionRenderer,
      state: { ...emptyState, xmlContent: String(xmlContent || "") },
      memo: emptyState
    }
  };
}
