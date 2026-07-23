# Logic Assist

[English](#english) | [中文](#中文)

---

<a id="english"></a>
## English

A Mindustry mod that enhances the in-game logic editor with jump-line coloring, block multi-select, and a powerful expression editor.

### Features

#### 1. Jump Line Coloring
Colorizes `jump` curves in the logic editor by target, so different branches are visually distinguishable.

- **Off**: all lines white (vanilla behavior)
- **Scattered**: golden-angle HSV color per target index
- **Block-tinted**: target block's category color, brightened 1.4×

Toggle via the in-game mod settings.

#### 2. Box Select & Batch Operations
Select, move, copy, and delete blocks in bulk inside the canvas.

- Drag on empty canvas area to box-select
- **Blue** = move mode, **Green** = copy mode (toggle via the hijacked copy button)
- Drag selected blocks to reposition; release to drop at the insertion indicator
- `Ctrl` + click a block to copy-drag a single block
- `Delete` / `Backspace` to delete all selected blocks
- `Right-click` / `Esc` to cancel a drag

After selection, the hijacked buttons on selected blocks become batch operations:
- Trash → delete all selected
- `+` → duplicate selected below
- Copy icon → toggle move/copy mode

#### 3. Expression Editor (`Expr` block)
Write complex math expressions that compile to `op` instruction chains, and fold `op` chains back into readable expressions.

**How it works**

- **Compile direction**: `result = cos(a) * 10 + x` expands to
  ```
  op cos _ a 0
  op mul _ _ 10
  op add x _ x
  ```
- **Fold direction**: opening the editor folds consecutive `op` chains (using `_` as a linear temporary variable) back into the expression form
- **Save**: expressions are unfolded to standard `op` instructions, so the saved code is vanilla-compatible (players without the mod see normal `op` lines)

**Syntax highlighting** (display mode only): numbers (gold), function names (coral), variables (white), operators/brackets/commas (light gray).

**Supported operators**

| Category | Operators |
|---|---|
| Unary functions | `not abs sign log log10 floor ceil round sqrt rand sin cos tan asin acos atan` |
| Binary functions | `max(a,b) min(a,b) angle(a,b) angleDiff(a,b) len(a,b) noise(a,b) logn(a,b)` |
| Logical | `\|\|` `&&` ` xor ` |
| Equality | `==` `!=` `===` `<` `>` `<=` `>=` |
| Bitwise | `&` `<<` `>>` `>>>` |
| Arithmetic | `+` `-` `*` `/` `//` `%` `%%` `^` |

`^` is right-associative (`2^3^2 = 2^9`). Function-call precedence is highest, then `^`, then unary, then `* / % //`, then `+ -`, etc.

**Jump safety**: if a `jump` targets the middle of an `op` chain (e.g. someone wrote code without the mod and a `jump` lands inside what would be folded), that chain is left unfolded to preserve semantics.

### Acknowledgements

This project builds on ideas from:

- **MI2-Utilities** — drag-move and jump-index translation logic (`LogicHelperMindow.doCutPaste`)
  - https://github.com/anomaly-251/MI2-Utilities-Java
- **mindcode** — op-chain → expression decompilation (`MlogDecompiler.collapseExpressions`), operator classification (`Operation`), expression optimization rules
  - https://github.com/PizzaNX/mindcode

### Build

```
gradlew jar
```

The universal JAR appears at `build/libs/logic-assist.jar`. Drop it into the Mindustry `mods/` folder.

### License

MIT

---

<a id="中文"></a>
## 中文

一个 Mindustry 模组，为游戏内的逻辑编辑器增加跳转线着色、积木框选批量操作和表达式编辑功能。

### 功能

#### 1. 跳转线着色
按目标为 `jump` 跳转线着色，不同分支一目了然。

- **关闭**：所有线白色（原版行为）
- **分散色**：按目标 index 用黄金角度 HSV 着色
- **积木色**：用目标积木类别颜色提亮 1.4×

在游戏模组设置中切换。

#### 2. 框选批量操作
在画布中批量选择、移动、复制、删除积木。

- 在空白区域拖动进行框选
- **蓝色** = 移动模式，**绿色** = 复制模式（点击夺舍的复制按钮切换）
- 拖动选中积木重新定位，释放后插入到指示器位置
- `Ctrl` + 点击单积木 → 复制拖动单个积木
- `Delete` / `Backspace` → 删除所有选中积木
- `右键` / `Esc` → 取消拖动

选中后，选中积木上的按钮被夺舍为批量操作：
- 垃圾桶 → 批量删除
- `+` → 向下复制选中积木
- 复制图标 → 切换移动/复制模式

#### 3. 表达式编辑器（`Expr` 积木）
编写复杂数学表达式，自动编译为 `op` 指令链；打开编辑器时自动把 `op` 链折叠回表达式形式。

**工作原理**

- **编译方向**：`result = cos(a) * 10 + x` 展开为
  ```
  op cos _ a 0
  op mul _ _ 10
  op add x _ x
  ```
- **折叠方向**：打开编辑器时，连续的 `op` 链（用 `_` 作线性临时变量）被折叠回表达式形式
- **保存**：表达式展开为标准 `op` 指令，保存的代码与原版兼容（未装插件的玩家看到的是普通 `op` 行）

**语法高亮**（仅显示态）：数字（金色）、函数名（珊瑚色）、变量（白色）、运算符/括号/逗号（浅灰）。

**支持的运算符**

| 类别 | 运算符 |
|---|---|
| 一元函数 | `not abs sign log log10 floor ceil round sqrt rand sin cos tan asin acos atan` |
| 二元函数 | `max(a,b) min(a,b) angle(a,b) angleDiff(a,b) len(a,b) noise(a,b) logn(a,b)` |
| 逻辑 | `\|\|` `&&` ` xor ` |
| 等于 | `==` `!=` `===` `<` `>` `<=` `>=` |
| 位运算 | `&` `<<` `>>` `>>>` |
| 算术 | `+` `-` `*` `/` `//` `%` `%%` `^` |

`^` 右结合（`2^3^2 = 2^9`）。函数调用优先级最高，依次是 `^`、一元、`* / % //`、`+ -` 等。

**跳转安全**：若某个 `jump` 指向 `op` 链中间（例如未装插件的人写的代码中 `jump` 落入会被折叠的区间），该链保持不折叠，以保证语义一致。

### 致谢

本项目参考了以下项目的思路：

- **MI2-Utilities** —— 拖动移动和跳转索引转换逻辑（`LogicHelperMindow.doCutPaste`）
  - https://github.com/anomaly-251/MI2-Utilities-Java
- **mindcode** —— op 链反编译为表达式（`MlogDecompiler.collapseExpressions`）、运算符分类（`Operation`）、表达式优化规则
  - https://github.com/PizzaNX/mindcode

### 编译

```
gradlew jar
```

通用 JAR 输出在 `build/libs/logic-assist.jar`，放入 Mindustry 的 `mods/` 目录即可。

### 许可证

MIT
