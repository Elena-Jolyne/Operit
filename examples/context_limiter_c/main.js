"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.registerToolPkg = registerToolPkg;
exports.onFinalize = onFinalize;
exports.onInputMenuToggle = onInputMenuToggle;

// 楼层限制数（与子包共享，通过全局变量）
var floorLimit = 5;
var limiterEnabled = true;

// 可选的楼层数循环列表
var FLOOR_OPTIONS = [3, 5, 8, 10, 15, 20, 30, 50, 100];

// 暴露给子包访问
if (typeof globalThis !== "undefined") {
    globalThis.__ctx_limiter_c_getLimit = function() { return floorLimit; };
    globalThis.__ctx_limiter_c_setLimit = function(n) { floorLimit = n; };
    globalThis.__ctx_limiter_c_getEnabled = function() { return limiterEnabled; };
    globalThis.__ctx_limiter_c_setEnabled = function(v) { limiterEnabled = v; };
}

function registerToolPkg() {
    // 注册 before_finalize_prompt hook
    ToolPkg.registerPromptFinalizeHook({
        id: "ctx_limiter_c_finalize",
        function: onFinalize,
    });

    // 注册菜单开关
    ToolPkg.registerInputMenuTogglePlugin({
        id: "ctx_limiter_c_menu",
        function: onInputMenuToggle,
    });

    return true;
}

function onInputMenuToggle(event) {
    var payload = event.eventPayload || {};
    var action = payload.action;

    if (action === "create") {
        // 创建菜单项
        return {
            toggles: [
                {
                    id: "ctx_limiter_toggle",
                    title: "楼层限制器",
                    description: limiterEnabled
                        ? "已开启 · 保留最近 " + floorLimit + " 层"
                        : "已关闭",
                    isChecked: limiterEnabled
                },
                {
                    id: "ctx_limiter_adjust",
                    title: "调节楼层数 ▶ " + floorLimit,
                    description: "点击切换: " + FLOOR_OPTIONS.join("/"),
                    isChecked: true
                }
            ]
        };
    }

    if (action === "toggle") {
        var toggleId = payload.toggleId;

        if (toggleId === "ctx_limiter_toggle") {
            // 开关限制器
            limiterEnabled = !limiterEnabled;
            if (typeof globalThis !== "undefined" && typeof globalThis.__ctx_limiter_c_setEnabled === "function") {
                globalThis.__ctx_limiter_c_setEnabled(limiterEnabled);
            }
            return { ok: true };
        }

        if (toggleId === "ctx_limiter_adjust") {
            // 循环切换楼层数
            var currentIdx = FLOOR_OPTIONS.indexOf(floorLimit);
            var nextIdx = (currentIdx + 1) % FLOOR_OPTIONS.length;
            floorLimit = FLOOR_OPTIONS[nextIdx];
            if (typeof globalThis !== "undefined" && typeof globalThis.__ctx_limiter_c_setLimit === "function") {
                globalThis.__ctx_limiter_c_setLimit(floorLimit);
            }
            return { ok: true };
        }
    }

    return { ok: false };
}

function onFinalize(input) {
    var payload = input.eventPayload || {};
    var history = payload.preparedHistory || payload.chatHistory || [];

    if (!history || history.length === 0) return null;

    // 读取最新状态（可能被子包工具或菜单修改过）
    if (typeof globalThis !== "undefined") {
        if (typeof globalThis.__ctx_limiter_c_getLimit === "function") {
            floorLimit = globalThis.__ctx_limiter_c_getLimit();
        }
        if (typeof globalThis.__ctx_limiter_c_getEnabled === "function") {
            limiterEnabled = globalThis.__ctx_limiter_c_getEnabled();
        }
    }

    // 如果限制器被关闭，不做任何处理
    if (!limiterEnabled) {
        console.log("[limiter_c] disabled, pass through " + history.length + " msgs");
        return null;
    }

    // 1. 分离 SYSTEM 消息和 非SYSTEM 消息
    var systemMsgs = [];
    var chatMsgs = [];
    for (var i = 0; i < history.length; i++) {
        if (history[i].kind === "SYSTEM") {
            systemMsgs.push(history[i]);
        } else {
            chatMsgs.push(history[i]);
        }
    }

    // 2. 只保留 USER 和 ASSISTANT 消息
    var uaMsgs = [];
    for (var i = 0; i < chatMsgs.length; i++) {
        var kind = chatMsgs[i].kind;
        if (kind === "USER" || kind === "ASSISTANT") {
            uaMsgs.push(chatMsgs[i]);
        }
    }

    // 3. 计算 USER 消息数（= 楼层数）
    var userCount = 0;
    for (var i = 0; i < uaMsgs.length; i++) {
        if (uaMsgs[i].kind === "USER") userCount++;
    }

    // 4. 如果楼层数不超过限制，不裁剪，但仍然只保留 SYSTEM + USER + ASSISTANT
    if (userCount <= floorLimit) {
        var result = systemMsgs.concat(uaMsgs);
        console.log("[limiter_c] " + userCount + " floors <= limit " + floorLimit + ", no trim, msgs: " + history.length + " -> " + result.length);
        return { preparedHistory: result };
    }

    // 5. 从后往前找到第 N 个 USER 消息的位置
    var keepFromIndex = 0;
    var countFromEnd = 0;
    for (var i = uaMsgs.length - 1; i >= 0; i--) {
        if (uaMsgs[i].kind === "USER") {
            countFromEnd++;
            if (countFromEnd === floorLimit) {
                keepFromIndex = i;
                break;
            }
        }
    }

    // 6. 截取最近 N 层
    var keptMsgs = uaMsgs.slice(keepFromIndex);

    // 7. 组合：SYSTEM 在前 + 截取的 USER/ASSISTANT 在后
    var finalMsgs = systemMsgs.concat(keptMsgs);

    console.log("[limiter_c] floors: " + userCount + ", limit: " + floorLimit + ", msgs: " + history.length + " -> " + finalMsgs.length + " (" + systemMsgs.length + " sys + " + keptMsgs.length + " chat)");

    return { preparedHistory: finalMsgs };
}