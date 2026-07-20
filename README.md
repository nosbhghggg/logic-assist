[English](README.md) | [中文](README_CN.md)

# Jump Line Color

Colors jump lines in Mindustry's logic editor by their target destination. Lines targeting the same destination share the same color, making it easier to distinguish different jump branches.

<img src="show.png" width="300" alt="Showcase">

## Color Modes

Configure in **Settings > Jump Line Coloring**:

| Mode | Description | Characteristics |
|------|-------------|-----------------|
| Off | White | No coloring |
| Index | By target index (golden angle) | Rich colors, changes when adding blocks |
| Block | By target block category color (brightened) | Stable colors, unaffected by adding blocks |

## Installation

Place the folder in Mindustry's mods directory:
- Windows: `%LOCALAPPDATA%\Mindustry\mods\`
- Or import the folder/zip via the in-game Mods menu

## Compatibility

- Min game version: 157.1
- Multiplayer compatible: Yes (client-side UI only)
- Languages: English / 中文

## How It Works

JS script mod. Replaces the `JumpButton.update` callback to apply coloring each frame.

Since `button.to` is package-private (inaccessible from JS/Rhino), uses the all-public chain:

```
curve.button -> button.elem -> elem.st -> st.dest -> dest.index / dest.st.category().color
```

- **Index mode**: Golden angle (137.508) HSV color generation
- **Block mode**: Uses `dest.st.category().color` brightened by 1.4x

## File Structure

```
jump-line-color/
├── mod.json
├── icon.png
├── show.png
├── scripts/main.js
├── bundles/
│   ├── bundle.properties       # English
│   └── bundle_zh_CN.properties # Chinese
├── README.md                   # English
└── README_CN.md                # Chinese
```