/* METADATA
{
    "name": "ctx_limiter_c",
    "description": "截取最近N层上下文，保留SYSTEM消息+最近N层USER/ASSISTANT",
    "tools": [
        {
            "name": "set_floor_limit",
            "description": "设置保留的最近楼层数",
            "parameters": [
                {
                    "name": "n",
                    "description": "保留最近N个楼层（默认5）",
                    "type": "number",
                    "required": true
                }
            ]
        },
        {
            "name": "get_floor_limit",
            "description": "查看当前楼层数限制",
            "parameters": []
        }
    ]
}
*/

function set_floor_limit(params) {
    var n = parseInt(params.n);
    if (isNaN(n) || n < 1) {
        complete({ success: false, error: "n 必须是大于0的整数" });
        return;
    }
    if (typeof globalThis !== "undefined" && typeof globalThis.__ctx_limiter_c_setLimit === "function") {
        globalThis.__ctx_limiter_c_setLimit(n);
    }
    complete({ success: true, floor_limit: n, message: "已设置保留最近 " + n + " 个楼层" });
}

function get_floor_limit(params) {
    var current = 5;
    if (typeof globalThis !== "undefined" && typeof globalThis.__ctx_limiter_c_getLimit === "function") {
        current = globalThis.__ctx_limiter_c_getLimit();
    }
    complete({ success: true, floor_limit: current, message: "当前保留最近 " + current + " 个楼层" });
}

exports.set_floor_limit = set_floor_limit;
exports.get_floor_limit = get_floor_limit;