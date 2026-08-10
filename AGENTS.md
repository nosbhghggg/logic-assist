# AGENTS.md — Logic-Assist 模组开发规范

## 注释风格

### 唯一规则

- `/** */` 仅用于文件顶部的类/接口 Javadoc（"头部注释"）
- 其余所有注释一律使用 `//`
- 不允许在方法、字段、代码块上使用 `/** */`

### 内容要求

注释必须满足以下至少一项：
1. 解释非显而易见的 WHY（隐藏约束、跨类加载器反射原因、API 陷阱）
2. 记录重要的试错结论（踩过的坑、被否决的方案及原因）
3. 标注与原版/MindustryX 的兼容性要点

禁止无信息量的注释（复述代码字面意思、过时的 TODO、被注释掉的代码）。

### 示例

```java
// 正确：头部 Javadoc
/**
 * 跳转线着色：为 jump 跳转线按目标着色，便于区分不同分支。
 */
public class JumpLineColor{

    // 正确：解释 WHY
    // fromHsv 不设置 alpha，需手动补 a = 1.0（arc Color.java 已知陷阱）
    c.fromHsv(...);
    c.a = 1.0f;

    // 正确：试错结论
    // Mathf.lerp 动画在此场景会闪烁，改用立即切换
    hoverJumpAnim = dragging ? 1f : 0f;
}
```

## 构建与部署

```powershell
cd 'd:\AI agent work space\mdt模组开发\logic-assist'
.\gradlew.bat deploy
Copy-Item 'build/libs/logic-assist.jar' 'C:\Users\NOSBhghgg\AppData\Roaming\Mindustry\mods\logic-assist.jar' -Force
```

## 关键约束

- LogicCanvas 必须继承 LCanvas（非替换），确保 MindustryX 兼容
- mod 类与游戏类由不同类加载器加载，包私有字段须用反射
- 当前分支 master，远端默认分支 main，推送用 `git push origin master:main`
