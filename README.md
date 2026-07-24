# Logic Assist

[English](README.md) | [中文](README_zh.md)

A Mindustry mod that enhances the in-game logic editor with jump-line coloring, block multi-select, and a powerful expression editor.

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

### Expression Editor (`Expr` block)

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

**Jump safety**: if a `jump` targets the middle of an `op` chain, that chain stays unfolded to preserve semantics.

## Acknowledgements

- [MI2-utilities](https://github.com/anomaly-251/MI2-Utilities-Java) — drag-move and jump-index translation logic
- [mindcode](https://github.com/PizzaNX/mindcode) — op-chain decompilation, operator classification, optimization rules

## Build

```
gradlew deploy
```

Output: `build/libs/logic-assist.jar` (universal JAR for both desktop and Android). Drop into Mindustry's `mods/` folder.

## License

MIT
