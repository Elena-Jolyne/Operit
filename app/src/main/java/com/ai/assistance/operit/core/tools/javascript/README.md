# JavaScript 工具调用

JavaScript 工具调用是一种功能强大的脚本机制，允许在 Android 应用中使用 JavaScript 编写自定义工具并与现有工具系统集成。本实现利用 WebView 执行 JavaScript 代码，并提供与原生代码的交互能力。

## 特性概览

- **完整的 JavaScript 支持**：使用标准 ES6+ JavaScript 语法
- **第三方 JS 库**：内置了常用的工具库，如 Lodash 核心功能
- **工具调用集成**：通过 `toolCall` 函数与现有工具系统无缝对接
- **参数传递**：支持通过 `params` 对象访问传入的参数
- **结果返回**：使用 `complete()` 函数返回执行结果
- **错误处理**：支持 JavaScript 标准的 try-catch 错误处理
- **异步操作**：支持 Promise 和异步/等待模式
- **Java/Kotlin 类桥接**：支持 `Java.xxx` 包链语法糖 + `Java.type(...)`（Rhino 风格）

## 使用指南

### 基本语法

JavaScript 工具使用标准的 JavaScript 语法，支持 ES6+ 特性：

```javascript
// 使用参数
const userId = params.userId;

// 计算和逻辑
const result = Math.sqrt(16) + Math.pow(2, 3);

// 使用函数和库
const isEmpty = _.isEmpty([]);

// 返回结果
complete({
    status: "success",
    data: result
});
```

### 参数使用

参数通过 `params` 对象传入，可以直接访问：

```javascript
// 访问参数
const query = params.query || "默认查询";
const limit = parseInt(params.limit) || 10;

// 使用参数
console.log(`查询: ${query}, 限制: ${limit}`);
```

### 工具调用

使用 `toolCall` 函数调用其他工具：

```javascript
// 调用格式: toolCall(工具类型, 工具名称, 参数对象)
const result = toolCall("default", "calculate", {
    expression: "sqrt(16) + pow(2, 3)"
});

// 使用调用结果
console.log("计算结果:", result);
```

工具调用可以嵌套或在条件语句中使用：

```javascript
// 条件调用
if (toolCall("default", "file_exists", { path: "/sdcard/my_file.txt" })) {
    const fileContent = toolCall("default", "read_file", { path: "/sdcard/my_file.txt" });
    // 处理文件内容
}
```

### Java/Kotlin 类桥接

新模式提供两层 bridge：

1. 高层 API：`Java` / `Kotlin`（推荐，Rhino 风格）  
2. 底层 API：`NativeInterface.java*`（调试/底层控制）

#### 高层 API（推荐）

- `Java.xxx`：包路径链式获取类代理（推荐）
- `Java.type(className)`：获取类代理
- `Java.use(className)` / `Java.importClass(className)`：`type` 的别名
- `Java.package(packageName)`：获取包命名空间代理
- `Java.implement(interfaceNameOrNames, impl)`：创建 Java 接口实现标记（支持 `string`、`string[]`、`Java.xxx` 类代理）
- `Java.implement(impl)`：省略接口名，交给目标参数类型推断（适合入参本身就是 interface）
- `Java.proxy(...)`：`implement(...)` 的别名
- **语法糖**：当 Java/Kotlin 方法参数是接口类型时，可直接传 JS 函数/对象，桥接会自动推断接口并创建代理（无需显式 `Java.implement`）
- `Java.releaseJs(markerOrId)`：释放 `implement` 注册的 JS 回调对象
- `Java.classExists(className)`：判断类是否存在
- `Java.callStatic(className, methodName, ...args)`：直接调用静态方法
- `Java.callSuspend(className, methodName, ...args, callback?)`：调用 Kotlin suspend 方法（回调或 Promise）
- `Java.newInstance(className, ...args)`：直接创建实例
- `Java.release(instanceOrHandle)`：释放单个实例句柄
- `Java.releaseAll()`：释放当前引擎下全部实例句柄（返回释放数量）

`Kotlin` 是 `Java` 的同义别名，API 完全一致。
另外还支持包路径链式访问：`Java.java.lang.System.currentTimeMillis()`。
内部类也支持点语法：`Java.android.os.Build.VERSION`、`Java.android.app.AlertDialog.Builder`。
如遇到大小写不一致的类名，也可使用精确类名写法：`Java.android.os["Build$VERSION"]`。

#### 类代理（`Java.type(...)` 返回值）

- `Cls.exists()`：类是否存在
- `Cls.newInstance(...args)`：创建实例
- `new Cls(...args)`：语法糖，等价于 `Cls.newInstance(...)`
- `Cls(...args)`：语法糖，等价于 `Cls.newInstance(...)`
- `Cls.callStatic(methodName, ...args)`：调用静态方法
- `Cls.callSuspend(methodName, ...args, callback?)`：调用 suspend 静态方法（回调或 Promise）
- `Cls.getStatic(fieldName)`：读取静态字段/属性
- `Cls.setStatic(fieldName, value)`：写入静态字段/属性
- `Cls.someStaticMethod(...)`：动态静态方法调用（语法糖）
- `Cls.someStaticField` / `Cls.someStaticField = x`：动态静态字段访问（语法糖）

#### 实例代理（`newInstance` 返回值）

- `obj.call(methodName, ...args)`：调用实例方法
- `obj.callSuspend(methodName, ...args, callback?)`：调用 suspend 实例方法（回调或 Promise）
- `obj.get(fieldName)`：读取字段/属性
- `obj.set(fieldName, value)`：写入字段/属性
- `obj.release()`：释放该实例句柄
- `obj.someMethod(...)`：动态实例方法调用（语法糖）
- `obj.someField` / `obj.someField = x`：动态字段访问（语法糖）

#### 生命周期与释放（建议）

- Java 对象跨桥接会以句柄形式持有，短生命周期对象建议在 `finally` 中显式 `release`。
- `Java.implement(...)` / `Java.proxy(...)` 产生的回调标记建议用完后调用 `Java.releaseJs(...)`。
- 运行时已接入自动回收（代理对象被 GC 后会尝试释放句柄），但 GC 时机不确定，仍建议关键路径显式释放。

#### 接口实现代理（Java interface / implements）

- `Java.implement("java.lang.Runnable", () => { ... })`：函数式实现（单方法接口最常见）
- `Java.implement(Java.java.lang.Runnable, () => { ... })`：等价语法糖（类代理写法）
- `Java.implement("com.xxx.Listener", { onStart(){}, onStop(){} })`：对象式实现（多方法）
- `Java.implement(["a.InterfaceA", Java.b.InterfaceB], impl)`：一次声明多接口（目标参数为 `Object` 时尤其有用）
- **语法糖**：JS 里直接传函数/对象给 Java（例如 `new Thread(() => {})` 或 `stream.collect({ emit(v){...} })`）也支持，桥接会自动推断接口
- 回调对象不再使用时请调用 `Java.releaseJs(markerOrId)` 释放，避免持有多余引用

示例 1：`Runnable`

```javascript
const Thread = Java.java.lang.Thread;
const task = Java.implement(Java.java.lang.Runnable, () => {
  console.log("run!");
});
new Thread(task).start();
Java.releaseJs(task);
```

示例 2：对象式 listener

```javascript
const listener = Java.implement("com.example.Listener", {
  onStart() {
    console.log("start");
  },
  onData(value) {
    return value + 1;
  }
});
```

#### 底层 NativeInterface bridge 函数（完整）

- `NativeInterface.javaClassExists(className): boolean`
- `NativeInterface.javaNewInstance(className, argsJson): string`
- `NativeInterface.javaCallStatic(className, methodName, argsJson): string`
- `NativeInterface.javaCallStaticSuspend(className, methodName, argsJson, callbackId): void`
- `NativeInterface.javaCallInstance(instanceHandle, methodName, argsJson): string`
- `NativeInterface.javaCallInstanceSuspend(instanceHandle, methodName, argsJson, callbackId): void`
- `NativeInterface.javaGetStaticField(className, fieldName): string`
- `NativeInterface.javaSetStaticField(className, fieldName, valueJson): string`
- `NativeInterface.javaGetInstanceField(instanceHandle, fieldName): string`
- `NativeInterface.javaSetInstanceField(instanceHandle, fieldName, valueJson): string`
- `NativeInterface.javaReleaseInstance(instanceHandle): string`
- `NativeInterface.javaReleaseAllInstances(): string`

#### 内部 runtime hooks（通常无需手动调用）

- `globalThis.__operitJavaBridgeInvokeJsObject(jsObjectId, methodName, args)`：供原生动态代理回调 JS 对象
- `globalThis.__operitJavaBridgeReleaseJsObject(jsObjectId)`：释放 JS 回调对象注册项

除 `javaClassExists` 外，其余 `java*` 方法返回统一 JSON：

```json
{ "success": true, "data": ... }
```

或

```json
{ "success": false, "error": "..." }
```

当 `data` 无法被 JSON 直接表示时，会返回句柄对象：

```json
{ "__javaHandle": "...", "__javaClass": "fully.qualified.ClassName" }
```

高层 API 会自动把这个句柄包装成实例代理对象。

#### 参数/返回转换规则（关键）

- JS `number/string/boolean/null/array/object` 会尽量映射到 Java 参数类型。
- 复杂 Java 对象默认通过句柄跨桥接传递，不做深拷贝。
- 字段访问优先字段，再 fallback 到 getter/setter（`getX/isX/setX`）。
- 接口回调是同步桥接；若 Java 在主线程触发且该接口方法需要返回值，会报错（`void` 回调会记录日志并返回 `null`）。
- 出错会抛 JS `Error`（高层 API）或返回 `success:false`（底层 API）。

#### 示例

可以通过 `Java.type("全限定类名")` 直接访问类的静态方法/字段，或创建实例后调用实例方法：

```javascript
const System = Java.type("java.lang.System");
const now = System.currentTimeMillis();

const StringBuilder = Java.type("java.lang.StringBuilder");
const sb = StringBuilder.newInstance();
sb.append("hello ");
sb.append("operit");

complete({
    now,
    text: sb.toString()
});
```

包路径链式语法糖：

```javascript
const now = Java.java.lang.System.currentTimeMillis();
const ArrayList = Java.java.util.ArrayList;
const Version = Java.android.os.Build.VERSION;
const AlertDialogBuilder = Java.android.app.AlertDialog.Builder;
const list = new ArrayList();
list.add("a");
list.add("b");
complete({
  now,
  size: list.size(),
  sdkInt: Number(Version.SDK_INT),
  dialogBuilderExists: !!AlertDialogBuilder
});
```

底层调用（不推荐常规使用）：

```javascript
const raw = NativeInterface.javaCallStatic(
  "java.lang.System",
  "currentTimeMillis",
  "[]"
);
const parsed = JSON.parse(raw);
if (!parsed.success) throw new Error(parsed.error);
complete(parsed.data);
```

### 返回结果

使用 `complete()` 函数返回结果：

```javascript
// 返回简单值
complete("操作成功");

// 返回复杂对象
complete({
    status: "success",
    data: {
        id: 123,
        name: "测试数据",
        items: [1, 2, 3]
    },
    timestamp: new Date().toISOString()
});
```

如果未明确调用 `complete()`，脚本会自动返回一个默认结果。

### 错误处理

使用标准的 JavaScript 错误处理：

```javascript
try {
    // 可能出错的代码
    const data = JSON.parse(invalidJson);
    complete(data);
} catch (e) {
    // 处理错误
    console.log("发生错误:", e.message);
    complete({
        status: "error",
        message: e.message
    });
}
```

## 内置库说明

### Lodash 核心功能

提供常用的实用函数：

```javascript
// 类型检查
_.isString("test");  // true
_.isNumber(42);      // true
_.isArray([1,2,3]);  // true
_.isObject({a:1});   // true

// 集合操作
_.isEmpty([]);       // true
_.forEach([1,2,3], item => console.log(item));
_.map({a:1, b:2}, (v, k) => `${k}=${v}`);  // ["a=1", "b=2"]
```

### 数据工具库

提供数据处理辅助函数：

```javascript
// JSON处理
const obj = dataUtils.parseJson('{"name":"test"}');
const json = dataUtils.stringifyJson({name:"test"});

// 日期格式化
const formattedDate = dataUtils.formatDate(new Date());
```

## 示例

### 简单计算

```javascript
const a = 5;
const b = 7;
const result = a * b + Math.sqrt(a*a + b*b);
complete(result);  // 返回 43.6023
```

### 参数使用与工具调用

```javascript
const query = params.query || "默认查询";
const searchResult = toolCall("default", "search", {
    query: query,
    maxResults: 10
});
complete({
    query: query,
    results: searchResult
});
```

### 数据处理

```javascript
// 示例JSON数据
const data = {
    users: [
        { id: 1, name: "张三", age: 28 },
        { id: 2, name: "李四", age: 32 },
        { id: 3, name: "王五", age: 45 }
    ]
};

// 数据处理
const userNames = data.users.map(user => user.name);
const averageAge = data.users.reduce((sum, user) => sum + user.age, 0) / data.users.length;

complete({
    names: userNames,
    averageAge: averageAge
});
```

## 与 OperScript 的区别

与原有的 OperScript 相比，JavaScript 工具调用具有以下优势：

1. **标准语法**：使用广泛采用的 JavaScript 语法，无需学习自定义语言
2. **丰富的内置功能**：利用 JavaScript 内置的丰富功能和标准库
3. **生态系统**：可以集成大量现有的 JavaScript 库
4. **开发工具支持**：享受成熟的 JavaScript IDE 和开发工具支持
5. **更灵活的错误处理**：使用标准的 try-catch 错误处理机制
6. **更强大的数据处理**：支持现代 JavaScript 数组和对象操作方法
7. **异步支持**：支持 Promise、async/await 等现代异步编程模式

## 在代码中使用 JavaScript 工具

```kotlin
// 获取 JavaScript 工具管理器实例
val jsToolManager = JsToolManager.getInstance(context, packageManager)

// 创建要执行的工具
val tool = AITool(
    name = "CustomJsTool",
    parameters = listOf(
        ToolParameter(name = "param1", value = "value1"),
        ToolParameter(name = "param2", value = "value2")
    )
)

// 执行脚本
val script = """
    const result = "处理参数: " + params.param1 + ", " + params.param2;
    complete(result);
"""
val result = jsToolManager.executeScript(script, tool)

// 处理结果
if (result.success) {
    println("脚本执行结果: ${result.result}")
} else {
    println("脚本执行失败: ${result.error}")
}
``` 
