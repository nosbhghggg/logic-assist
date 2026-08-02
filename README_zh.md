# Logic Assist

[English](README.md) | [中文](README_zh.md)

<p align="center"><img src="logo.svg" width="400" alt="Logic Assist Logo"></p>

一个 Mindustry 模组，为游戏内逻辑编辑器增加跳转线着色、积木框选批量操作、表达式编辑和 JUMP 跳转导航功能。

## 功能

### 跳转线着色

按目标为 `jump` 跳转线着色，不同分支一目了然。在游戏模组设置中切换。

- **关闭**：所有线白色（原版行为）
- **分散色**：按目标 index 用黄金角度 HSV 着色
- **积木色**：用目标积木类别颜色提亮 1.4×

### 框选批量操作

在画布中批量选择、移动、复制、删除积木。

- 在空白区域拖动框选；**蓝色** = 移动，**绿色** = 复制（点击选中积木上的复制图标切换）
- 拖动选中积木重新定位，释放后插入到指示器位置
- `Ctrl` + 点击单积木 → 复制拖动；`Delete`/`Backspace` → 删除选中；右键/`Esc` → 取消
- 选中后，选中积木上的按钮变为批量操作：垃圾桶 → 批量删除，`+` → 向下复制，复制图标 → 切换模式

### 滚动条增强

彩色滚动条（每个积木按类别颜色着色）、点击跳转和悬浮跳转：拖动积木或 JUMP 箭头时鼠标接近滚动条即跳转到对应位置。可在游戏模组设置中开关。

### JUMP 跳转按钮

为 `jump` 积木添加 `JUMP` 按钮。点击后滚动到目标积木位置并闪烁高亮。移植自 [MindustryX](https://github.com/TinyLake/MindustryX/)

### 表达式编辑器（`Expr` 积木）

> [!CAUTION]
> `Expr` 积木为实验性功能，可能不稳定。使用前请务必备份你的逻辑代码。

编写数学表达式，自动编译为 `op` 指令链；打开编辑器时自动把 `op` 链折叠回表达式形式。

**编译**：`result = cos(a) * 10 + x` →

```
op cos _0 a 0
op mul _0 _0 10
op add x _0 x
```

- **折叠**：打开编辑器时，连续的 `op` 链（用 `_0`、`_1`、... 作线性临时变量）被折叠回表达式形式
- **保存**：表达式展开为标准 `op` 指令，保存的代码与原版兼容（未装模组的玩家看到的是普通 `op` 行）
- **语法高亮**（仅显示态）：数字（金色）、函数名（珊瑚色）、变量（白色）、运算符（浅灰）
- **错误提示**：语法错误时在表达式下方以红色显示具体原因

**支持的运算符**

| 类别   | 运算符                                                                            |
| ---- | ------------------------------------------------------------------------------ |
| 一元函数 | `not abs sign log log10 floor ceil round sqrt rand sin cos tan asin acos atan` |
| 二元函数 | `max(a,b) min(a,b) angle(a,b) angleDiff(a,b) len(a,b) noise(a,b) logn(a,b)`    |
| 逻辑   | `\|\|` `&&` `xor`                                                              |
| 等于   | `==` `!=` `===` `<` `>` `<=` `>=`                                              |
| 位运算  | `&` `<<` `>>` `>>>`                                                            |
| 算术   | `+` `-` `*` `/` `//` `%` `%%` `^`                                              |

`^` 右结合（`2^3^2 = 2^9`）。优先级：函数调用 > `^` > 一元 > `* / % //` > `+ -` > ...

**表达式优化**（默认关闭，在 print 积木输入 `expr-opt:true` 开启。参考 [mindcode](https://github.com/cardillan/mindcode)）：

- **常量折叠**：`1 + 2 * 3` → `7`
- **代数化简**：`a + 0` → `a`、`a * 1` → `a`、`a - a` → `0`、`a ^ 0` → `1`
- **公共子表达式消除（CSE）**：`(a+b) * (a+b)` 只计算一次 `a+b` 并复用结果（始终开启）
- **幂等函数折叠**：`abs(abs(x))` → `abs(x)`、`floor(floor(x))` → `floor(x)`
- **比较取反**：`!(a < b)` → `a >= b`
- **吸收律**：`min(a, max(a, b))` → `a`
- **负号处理**：`a + (-b)` → `a - b`、`-(-x)` → `x`

## 致谢

- [MI2-utilities](https://github.com/BlackDeluxeCat/MI2-Utilities-Java) —— 拖动移动和跳转索引转换逻辑
- [mindcode](https://github.com/cardillan/mindcode) —— op 链反编译、运算符分类、优化规则（常量折叠、CSE、临时变量消除）
- [MindustryX](https://github.com/TinyLake/MindustryX/) —— JUMP 按钮实现参考

## 编译

```
gradlew deploy
```

输出 `build/libs/logic-assist.jar`（通用 JAR，电脑端与安卓端通用），放入 Mindustry 的 `mods/` 目录即可。

## 许可证

GPL-3.0-or-later。本项目包含源自 [MindustryX](https://github.com/TinyLake/MindustryX/) 的代码，该作品基于 GPL-3.0 协议发布。详见 [LICENSE](LICENSE)。
