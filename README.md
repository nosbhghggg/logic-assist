# Logic Assist

[English](README.md) | [中文](README.zh.md)

A Mindustry mod that enhances the in-game logic editor with jump-line coloring, block multi-select, and a powerful expression editor.

## Features

### Jump Line Coloring

Colorizes `jump` curves by target so different branches are visually distinguishable.

- **Off**: all lines white (vanilla)
- **Scattered**: golden-angle HSV color per target index
- **Block-tinted**: target block's category color, brightened 1.4×

Toggle via in-game mod settings.

### Box Select & Batch Operations

Select, move, copy, and delete blocks in bulk.

- Drag on empty canvas to box-select
- **Blue** = move mode, **Green** = copy mode (toggle via the copy/move button on selected blocks)
- Drag selected blocks to reposition; release at the insertion indicator
- `Ctrl` + click → copy-drag a single block
- `Delete` / `Backspace` → delete all selected
- Right-click / `Esc` → cancel drag

After selection, buttons on selected blocks become batch operations:

- Trash → delete all selected
- `+` → duplicate selected below
- Copy icon → toggle move/copy mode

### Expression Editor (`Expr` block)

Write math expressions that compile to `op` chains, and fold `op` chains back into readable expressions.

**Compile**: `result = cos(a) * 10 + x` →

```
op cos _ a 0
op mul _ _ 10
op add x _ x
```

**Fold**: opening the editor folds consecutive `op` chains (using `_` as a linear temp variable) back into expression form.

**Save**: expressions unfold to standard `op` instructions — vanilla-compatible (players without the mod see normal `op` lines).

**Syntax highlighting** (display mode): numbers (gold), functions (coral), variables (white), operators (light gray).

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

- **MI2-utilities** — drag-move and jump-index translation logic
  - https://github.com/anomaly-251/MI2-Utilities-Java
- **mindcode** — op-chain decompilation, operator classification, optimization rules
  - https://github.com/PizzaNX/mindcode

## Build

```
gradlew jar
```

Output: `build/libs/logic-assist.jar`. Drop into Mindustry's `mods/` folder.

## License

MIT
