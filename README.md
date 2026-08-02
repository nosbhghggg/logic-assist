# Logic Assist

[English](README.md) | [中文](README_zh.md)

<p align="center"><img src="logo.svg" width="400" alt="Logic Assist Logo"></p>

A Mindustry mod that adds jump-line coloring, block multi-select, an expression editor, and JUMP navigation to the in-game logic editor.

## Features

### Jump Line Coloring

Colorizes `jump` curves by target so different branches are visually distinguishable. Toggle via in-game mod settings.

- **Off**: all lines white (vanilla)
- **Scattered**: golden-angle HSV color per target index
- **Block-tinted**: target block's category color, brightened 1.4×

### Box Select & Batch Operations

Select, move, copy, and delete blocks in bulk.

- Drag on empty canvas to box-select; **blue** = move, **green** = copy (toggle via the copy icon on selected blocks)
- Drag selected blocks to reposition; release at the insertion indicator
- `Ctrl` + click → copy-drag a single block; `Delete`/`Backspace` → delete selected; Right-click/`Esc` → cancel
- After selection, buttons on selected blocks become batch ops: trash → delete all, `+` → duplicate, copy icon → toggle mode

### Scrollbar Enhancement

Colored scrollbar (each segment tinted by its block's category color), click-to-jump, and hover-jump: when dragging a block or a JUMP arrow, hovering near the scrollbar instantly jumps to that position. Toggle via in-game mod settings.

### JUMP Button

Adds a `JUMP` button to `jump` blocks. Click to scroll the target block into view with a flashing highlight. Ported from [MindustryX](https://github.com/TinyLake/MindustryX/).

### Expression Editor (`Expr` block)

> [!CAUTION]
> The `Expr` block is experimental and may be unstable. Please back up your logic code before using it.

Write math expressions that compile to `op` chains, and fold `op` chains back into readable expressions.

**Compile**: `result = cos(a) * 10 + x` →

```
op cos _0 a 0
op mul _0 _0 10
op add x _0 x
```

- **Fold**: opening the editor folds consecutive `op` chains (using `_0`, `_1`, ... as linear temp variables) back into expression form
- **Save**: expressions unfold to standard `op` instructions — vanilla-compatible (players without the mod see normal `op` lines)
- **Syntax highlighting** (display mode): numbers (gold), functions (coral), variables (white), operators (light gray)
- **Error reporting**: syntax errors are shown in red beneath the expression with the specific reason

**Supported operators**

| Category | Operators |
|---|---|
| Unary functions | `not abs sign log log10 floor ceil round sqrt rand sin cos tan asin acos atan` |
| Binary functions | `max(a,b) min(a,b) angle(a,b) angleDiff(a,b) len(a,b) noise(a,b) logn(a,b)` |
| Logical | `\|\|` `&&` ` xor ` |
| Equality | `==` `!=` `===` `<` `>` `<=` `>=` |
| Bitwise | `&` `<<` `>>` `>>>` |
| Arithmetic | `+` `-` `*` `/` `//` `%` `%%` `^` |

`^` is right-associative (`2^3^2 = 2^9`). Precedence: function call > `^` > unary > `* / % //` > `+ -` > ...

**Expression optimization** (off by default; add `expr-opt:true` to a print block to enable. Inspired by [mindcode](https://github.com/cardillan/mindcode)):

- **Constant folding**: `1 + 2 * 3` → `7`
- **Algebraic simplification**: `a + 0` → `a`, `a * 1` → `a`, `a - a` → `0`, `a ^ 0` → `1`
- **Common Subexpression Elimination (CSE)**: `(a+b) * (a+b)` computes `a+b` once and reuses the result (always enabled)
- **Idempotent folding**: `abs(abs(x))` → `abs(x)`, `floor(floor(x))` → `floor(x)`
- **Comparison negation**: `!(a < b)` → `a >= b`
- **Absorption**: `min(a, max(a, b))` → `a`
- **Negation handling**: `a + (-b)` → `a - b`, `-(-x)` → `x`

## Acknowledgements

- [MI2-utilities](https://github.com/BlackDeluxeCat/MI2-Utilities-Java) — drag-move and jump-index translation logic
- [mindcode](https://github.com/cardillan/mindcode) — op-chain decompilation, operator classification, optimization rules (constant folding, CSE, temp variable elimination)
- [MindustryX](https://github.com/TinyLake/MindustryX/) — JUMP button implementation reference (`0046-UI-ARC-logic-Support.patch` by way-zer)

## Build

```
gradlew deploy
```

Output: `build/libs/logic-assist.jar` (universal JAR for both desktop and Android). Drop into Mindustry's `mods/` folder.

## License

GPL-3.0-or-later. This project incorporates code derived from [MindustryX](https://github.com/TinyLake/MindustryX/), which is licensed under GPL-3.0. See [LICENSE](LICENSE) for details.
