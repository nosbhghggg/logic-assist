package logicassist.expr;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.input.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.logic.*;
import mindustry.logic.LExecutor.*;
import mindustry.logic.LStatements.*;
import mindustry.ui.Styles;

import java.util.*;

/**
 * 表达式语句：以表达式形式显示，保存时自动展开为 op 链。
 *
 * <p>折叠态：[dest] = [expr]（Label 覆盖 TextField，点击切换编辑）
 * write() 输出 op 链保证标准 mlog；copy() 直接复制字段不走序列化。
 */
public class ExprStatement extends LStatement{

    // 断行宽度测量复用（UI 单线程，测后立即取值）
    private static final GlyphLayout measureLayout = new GlyphLayout();

    // 显示行数上限：正常使用远达不到，仅防御性拦截极端粘贴把画布撑爆；
    // 编辑框内始终是全文
    private static final int maxLines = 64;

    // 断行宽度 = 积木总宽 - 对应模式的开销（dest 字段 144 + 等号 + 内边距）。
    // 仅作首帧（Label 尚未布局）的 fallback；布局后直接用 Label 实测宽度
    private static final float wrapOverheadRows = 200f;
    private static final float wrapOverheadWide = 250f;

    public String dest = "result";
    public String expr = "0";

    // 上次编译的 op 链（用于 fallback、行号计算和调试）
    public transient List<ExprCompiler.OpLine> lastOps;

    // 上次编译的错误消息（null = 无错误）。作为字段保持，避免 build() 重建时丢失错误状态
    public transient String lastError = null;

    @Override
    public void write(StringBuilder builder){
        List<ExprCompiler.OpLine> lines;
        try{
            lines = ExprCompiler.compile(dest, expr);
            lastOps = lines;
        }catch(Exception e){
            lines = lastOps;
            if(lines == null || lines.isEmpty()){
                builder.append("op add ").append(dest).append(" ").append(dest).append(" 0");
                return;
            }
        }
        for(int i = 0; i < lines.size(); i++){
            if(i > 0) builder.append("\n");
            builder.append(lines.get(i).toText());
        }
    }

    @Override
    public void build(Table table){
        // 重新验证：lastError 不为 null 时，expr 可能已被外部修正（如 foldAll），需重新编译检查
        if(lastError != null){
            try{
                ExprCompiler.compile(dest, expr);
                lastError = null;
            }catch(Exception e){
                lastError = e.getMessage();
            }
        }

        // 初始化 lastOps：手动添加的 Expr 可能还没有编译过
        if(lastOps == null){
            try{
                lastOps = ExprCompiler.compile(dest, expr);
            }catch(Exception e){
                lastError = e.getMessage();
            }
        }

        table.left();
        field(table, dest, str -> dest = str);
        table.add(" = ");

        // 表达式显示：Label（高亮 + 手动断行）与 TextField（编辑）切换
        // Label 复用 Styles.nodeField 的背景，保证非编辑态也有白色横杠
        Label exprLabel = new Label("");
        Label.LabelStyle labelStyle = new Label.LabelStyle(exprLabel.getStyle());
        if(Styles.nodeField.background != null){
            labelStyle.background = Styles.nodeField.background;
        }
        exprLabel.setStyle(labelStyle);
        exprLabel.setAlignment(Align.left);
        exprLabel.touchable = Touchable.enabled;

        // 错误信息 Label：显示在表达式下方，仅错误时可见
        Label errorLabel = new Label("");
        errorLabel.setStyle(new Label.LabelStyle(Styles.outlineLabel));
        errorLabel.setColor(Color.scarlet);
        errorLabel.setAlignment(Align.left);
        errorLabel.visible = false;

        Font exprFont = exprLabel.getStyle().font;
        Font errFont = errorLabel.getStyle().font;
        // lastWrapWidth 仅记录上次触发时的 Label 实测宽度，用作"宽度变化→重排"的触发信号
        float[] lastWrapWidth = {0f};

        // 断行宽度需扣除背景左右内边距：Label.layout() 绘制时文本区 = 宽度 - 背景内边距
        float bgPad = labelStyle.background != null
            ? labelStyle.background.getLeftWidth() + labelStyle.background.getRightWidth() : 0f;

        Runnable updateLabel = () -> {
            float w = exprLabel.getWidth();
            lastWrapWidth[0] = w;
            // 断行宽度基准：Label 实测宽度（Stack 的 pref/minWidth 均覆盖为 0 后，
            // 实测宽即为 cell 分配的真实可用宽度）- 背景内边距。首帧未布局时用
            // 结构公式估算（积木宽只可能是 Scl.scl(400/900)，DragLayout 强制）——
            // 固定开销不随 UI 比例缩放，仅作 fallback
            float sRows = Scl.scl(400f), sWide = Scl.scl(900f);
            float blockW = Math.abs(table.getWidth() - sRows) <= Math.abs(table.getWidth() - sWide) ? sRows : sWide;
            float fallback = Math.max(1f, blockW - (blockW == sRows ? wrapOverheadRows : wrapOverheadWide) - bgPad - 4f);
            float wrapWidth = w > 1f ? Math.max(1f, w - bgPad) : fallback;
            if(lastError != null){
                exprLabel.setColor(Color.scarlet);
                // 转义 [ ] 防止富文本解析错误
                String safe = lastError.replace("[", "[[").replace("]", "]]");
                errorLabel.setText("[#ff5555]" + wrapPlain(safe, errFont, wrapWidth));
                errorLabel.visible = true;
            }else{
                exprLabel.setColor(Color.white);
                errorLabel.visible = false;
                errorLabel.setText("");
            }
            exprLabel.setText(highlightExpr(expr, exprFont, wrapWidth));
        };
        updateLabel.run();

        TextField exprField = new TextField(expr);
        exprField.setStyle(Styles.nodeField);
        exprField.setMessageText("expr");
        // 允许所有字符（含空格、运算符），不走 LStatement.field() 的 sanitize
        exprField.setFilter((f, c) -> true);
        exprField.setMaxLength(0);
        exprField.changed(() -> {
            expr = exprField.getText();
            try{
                lastOps = ExprCompiler.compile(dest, expr);
                lastError = null;
            }catch(Exception e){
                // 输入中的语法错误，保留旧 lastOps，记录错误消息
                lastError = e.getMessage();
            }
            updateLabel.run();
        });

        // Stack 叠放 Label 和 TextField；getPrefWidth 与 getMinWidth 都覆盖为 0：
        // 只覆盖 pref 时，wrap=false 的 Label 会经 minWidth 通道用"最宽行宽"撑爆
        // 外层 cell（135% 缩放实测 tableW=611 挤出积木 elemW=540）
        arc.scene.ui.layout.Stack stack = new arc.scene.ui.layout.Stack(){
            @Override
            public float getPrefWidth(){ return 0; }

            @Override
            public float getMinWidth(){ return 0; }
        };
        stack.add(exprLabel);
        stack.add(exprField);
        table.add(stack).growX().fillX().padLeft(4f);
        // TextField 默认隐藏，Label 换行高度自然撑开 Stack
        exprField.visible = false;
        exprField.touchable = Touchable.disabled;

        // 错误信息行：占满整行（合并上方 3 列）
        table.row();
        table.add(errorLabel).growX().padLeft(4f).padTop(2f).colspan(3);

        // 点击 Label → 编辑表达式
        // 移动端弹原生输入框（TextField 隐藏导致 touchDown 不触发键盘），
        // 桌面端切换 Label/TextField 可见性进行内联编辑
        exprLabel.addListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                if(Core.app.isMobile() && !Core.input.useKeyboard()){
                    Input.TextInput input = new Input.TextInput();
                    input.text = expr;
                    input.accepted = text -> {
                        expr = text;
                        exprField.setText(text);
                        exprField.change();
                    };
                    Core.input.getTextInput(input);
                    event.stop();
                    return true;
                }
                exprField.setText(expr);
                exprLabel.visible = false;
                exprLabel.touchable = Touchable.disabled;
                exprField.visible = true;
                exprField.touchable = Touchable.enabled;
                Core.scene.setKeyboardFocus(exprField);
                Core.scene.setScrollFocus(exprField);
                event.stop();
                return true;
            }
        });

        // 编辑态退出检测挂在 stack 上（arc 的 Group.act() 跳过不可见子元素，
        // 挂在 Label/TextField 上的 update 回调在各自隐藏期间不执行）：
        // 1. 字段只在获焦时被设为可见，任何"可见但无焦点"状态（含获焦同帧
        //    焦点被抢走）立即切回 Label；
        // 2. arc Scene 点击空白处不会移除键盘焦点，点击积木外（画布空白、
        //    其他积木）会一直停留在编辑态，需用 justTouched 命中检测兜底提交
        //    （点击 Label 开启编辑的同一帧，点击位置在重叠的 field 内，不会误触发）
        stack.update(() -> {
            if(exprField.visible){
                boolean focused = Core.scene.getKeyboardFocus() == exprField;
                boolean clickOutside = false;
                if(Core.input.justTouched()){
                    exprField.screenToLocalCoordinates(Tmp.v1.set(Core.input.mouseX(), Core.input.mouseY()));
                    clickOutside = exprField.hit(Tmp.v1.x, Tmp.v1.y, true) == null;
                }
                if(!focused || clickOutside){
                    if(focused) Core.scene.setKeyboardFocus(null);
                    exprField.visible = false;
                    exprField.touchable = Touchable.disabled;
                    exprLabel.visible = true;
                    exprLabel.touchable = Touchable.enabled;
                    updateLabel.run();
                }
            }
            // Label 实际宽度变化时（首帧 fallback 断行、窗口缩放、UI 比例切换）
            // 按实际宽度重新断行
            float w = exprLabel.getWidth();
            if(w > 1f && Math.abs(w - lastWrapWidth[0]) > 0.5f){
                updateLabel.run();
            }
        });
    }

    // 把表达式转为带颜色标记的富文本，按 maxWidth 手动断行（插入 \n）。
    // 复用 ExprCompiler.tokenize 分类着色，用 token.start 保留原始空白：
    // - 数字：金色 / 函数名：珊瑚色（后跟左括号）/ 变量名：白色 / 其余：浅灰
    // 断行测量必须按"整行富文本"实测（含颜色标记，标记零宽）：逐 token 测宽
    // 求和与整行渲染存在约 10% 级系统偏差（UI 缩放下更明显），整行测量与
    // Label 渲染走同一条 GlyphLayout 路径，从根上消除偏差、无需事后校准。
    // 换行后行首空白丢弃；超过整行宽的单 token 在 token 内部按字符断行；
    // 超过 maxLines 行折叠显示 ...
    private String highlightExpr(String expr, Font font, float maxWidth){
        if(expr == null || expr.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        try{
            List<ExprCompiler.Token> tokens = ExprCompiler.tokenize(expr);
            int lines = 1;
            int lastEnd = 0;
            StringBuilder line = new StringBuilder();
            boolean truncated = false;
            for(int i = 0; i < tokens.size() && !truncated; i++){
                ExprCompiler.Token tok = tokens.get(i);
                if(tok.type == ExprCompiler.TokType.EOF) break;

                // token 前的原始空白
                String gap = tok.start > lastEnd ? expr.substring(lastEnd, tok.start) : "";
                lastEnd = tok.start + tok.text.length();

                String color;
                if(tok.type == ExprCompiler.TokType.NUM){
                    color = "goldenrod";
                }else if(tok.type == ExprCompiler.TokType.IDENT){
                    boolean isFunc = (i + 1 < tokens.size()
                        && tokens.get(i + 1).type == ExprCompiler.TokType.LPAREN);
                    color = isFunc ? "coral" : "white";
                }else{
                    color = "lightgray";
                }
                // 富文本中 [ ] 需转义为 [[ ]]
                String rich = "[" + color + "]" + tok.text.replace("[", "[[").replace("]", "]]") + "[]";

                // 候选行 = 当前行 + 空白 + token，整行实测
                if(!line.isEmpty()){
                    String cand = line + gap + rich;
                    if(textWidth(font, cand) <= maxWidth){
                        line.append(gap).append(rich);
                        continue;
                    }
                    if(lines >= maxLines){
                        truncated = true;
                        break;
                    }
                    sb.append(line).append('\n');
                    lines++;
                    line.setLength(0);
                }
                // 行首放置 token；超宽单 token（如反复粘贴拼接的超长变量名/数字）
                // 按字符逐个追加，整行实测超宽即断行
                if(textWidth(font, rich) <= maxWidth){
                    line.append(rich);
                }else{
                    String prefix = "[" + color + "]";
                    StringBuilder piece = new StringBuilder(prefix);
                    for(int ci = 0; ci < tok.text.length(); ci++){
                        char ch = tok.text.charAt(ci);
                        String esc = ch == '[' ? "[[" : ch == ']' ? "]]" : String.valueOf(ch);
                        if(piece.length() > prefix.length() && textWidth(font, piece + esc + "[]") > maxWidth){
                            if(lines >= maxLines){
                                truncated = true;
                                break;
                            }
                            sb.append(piece).append("[]").append('\n');
                            lines++;
                            piece = new StringBuilder(prefix);
                        }
                        piece.append(esc);
                    }
                    if(!truncated){
                        line.append(piece).append("[]");
                    }
                }
            }
            sb.append(line);
            // tokenize 成功时 lastEnd == expr.length()，正常无尾部剩余
            if(!truncated && lastEnd < expr.length()){
                sb.append(expr, lastEnd, expr.length());
            }
            if(truncated){
                sb.append("\n[lightgray]...[]");
            }
        }catch(Exception e){
            // 解析失败，原样返回（转义 [ ]）并按字符断行
            sb.setLength(0);
            sb.append(wrapPlain(expr.replace("[", "[[").replace("]", "]]"), font, maxWidth));
        }
        return sb.toString();
    }

    // 测量单行文本宽度（整行断行测量用；富文本标记按零宽解析）
    private static float textWidth(Font font, String text){
        if(text.isEmpty()) return 0f;
        measureLayout.setText(font, text);
        return measureLayout.width;
    }

    // 按字符对纯文本（无富文本颜色标记）断行，超宽处插入 \n；
    // 同样按整行实测保证与渲染一致。用于错误消息和解析失败的表达式文本
    private static String wrapPlain(String text, Font font, float maxWidth){
        if(text == null || text.isEmpty() || maxWidth <= 1f) return text;
        StringBuilder sb = new StringBuilder(text.length() + 8);
        StringBuilder line = new StringBuilder();
        int lines = 1;
        for(int i = 0; i < text.length(); i++){
            char ch = text.charAt(i);
            if(ch == '\n'){
                sb.append(line).append('\n');
                line.setLength(0);
                if(++lines >= maxLines) return sb.append("...").toString();
                continue;
            }
            line.append(ch);
            if(line.length() > 1 && textWidth(font, line.toString()) > maxWidth){
                line.deleteCharAt(line.length() - 1);
                if(lines >= maxLines) return sb.append(line).append("\n...").toString();
                sb.append(line).append('\n');
                lines++;
                line.setLength(0);
                line.append(ch);
            }
        }
        sb.append(line);
        return sb.toString();
    }

    @Override
    public LStatement copy(){
        Log.debug("[LogicAssist] ExprStatement.copy() called: dest=@ expr=@", dest, expr);
        ExprStatement copy = new ExprStatement();
        copy.dest = this.dest;
        copy.expr = this.expr;
        copy.lastOps = this.lastOps;
        copy.lastError = this.lastError;
        return copy;
    }

    @Override
    public LInstruction build(LAssembler builder){
        // 正常流程下不会走到这里：LogicCanvas.save() 会先 unfoldAll()，
        // ExprStatement 会被替换为 OperationStatement。
        // 但如果代码通过 customParsers 加载后直接执行（不经过编辑器 save），
        // 返回一个 no-op 指令防止静默跳过。
        List<ExprCompiler.OpLine> ops;
        try{
            ops = ExprCompiler.compile(dest, expr);
        }catch(Exception e){
            ops = lastOps;
        }
        if(ops == null || ops.isEmpty()){
            return new OpI(LogicOp.add, builder.var(dest), builder.var("0"), builder.var(dest));
        }
        // 返回第一条 op 的指令，后续 op 在 write() 中输出为文本
        ExprCompiler.OpLine first = ops.get(0);
        return new OpI(LogicOp.valueOf(first.op),
                        builder.var(first.a), builder.var(first.b), builder.var(first.dest));
    }

    // 返回该表达式编译后占用的 mlog 行数。
    // 用于 LogicCanvas 计算行号标签；调用方按需触发重新编译。
    // 编译失败时回退为 1 行（保留原占位）。
    public int getMlogLineCount(){
        if(lastOps == null){
            try{
                lastOps = ExprCompiler.compile(dest, expr);
            }catch(Exception ignored){}
        }
        return (lastOps != null) ? lastOps.size() : 1;
    }

    @Override
    public String name(){
        return "Expr";
    }

    @Override
    public LCategory category(){
        return LCategory.operation;
    }
}
