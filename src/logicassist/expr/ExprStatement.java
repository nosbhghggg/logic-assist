package logicassist.expr;

import arc.*;
import arc.graphics.*;
import arc.math.*;
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
 * 表达式语句：在逻辑编辑器中以表达式形式显示，保存时自动展开为 op 链。
 *
 * 折叠态：[dest] = [expr text field]
 * 展开态：op cos _ a 0 / op mul _ _ 10 / op add x _ x
 *
 * 关键设计：
 * - write() 输出 op 链文本，保证保存的代码始终是标准 mlog
 * - copy() 直接复制字段，不走 write→read 序列化（防止复制时展开）
 * - 行号显示由 LogicDragLayout.layout() 自动管理，不在此类处理
 */
public class ExprStatement extends LStatement{

    /** 目标变量名 */
    public String dest = "result";
    /** 表达式字符串 */
    public String expr = "0";

    /** 上次编译的 op 链（用于 fallback、行号计算和调试） */
    public transient List<ExprCompiler.OpLine> lastOps;

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
        // 初始化 lastOps：手动添加的 Expr 可能还没有编译过
        if(lastOps == null){
            try{
                lastOps = ExprCompiler.compile(dest, expr);
            }catch(Exception e){
                // 表达式无效，lastOps 保持 null，行号显示为单行
            }
        }

        table.left();
        // dest 字段
        field(table, dest, str -> dest = str);
        table.add(" = ");

        // 表达式显示：Label（高亮 + 自动换行）与 TextField（编辑）切换
        Label exprLabel = new Label("");
        exprLabel.setWrap(true);
        exprLabel.setAlignment(Align.left);
        exprLabel.touchable = Touchable.enabled;
        // 限制 Label 最大宽度，防止 Stack 被 prefWidth 撑大导致溢出
        exprLabel.setLayoutEnabled(true);

        // 语法错误状态：错误时 Label 文字变红
        final boolean[] hasError = {false};

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
                hasError[0] = false;
            }catch(Exception e){
                // 输入中的语法错误，保留旧 lastOps，标记错误状态
                hasError[0] = true;
            }
        });

        // 更新 Label 的高亮文本（错误时用红色）
        // pack() 强制 Label 在固定宽度下重新 layout，计算 wrap 后的真实高度
        Runnable updateLabel = () -> {
            if(hasError[0]){
                exprLabel.setColor(Color.scarlet);
            }else{
                exprLabel.setColor(Color.white);
            }
            exprLabel.setText(highlightExpr(expr));
            exprLabel.pack();
        };
        updateLabel.run();

        // Stack 叠放 Label 和 TextField，占同一空间
        // 巧思：覆盖 getPrefWidth() 返回 0，让 Table 不被 Stack 的 prefWidth 撑开
        // 实际宽度由 cell growX 填满 dest 右边剩余空间，自动适应窗口大小
        arc.scene.ui.layout.Stack stack = new arc.scene.ui.layout.Stack(){
            @Override
            public float getPrefWidth(){ return 0; }
        };
        stack.add(exprLabel);
        stack.add(exprField);
        table.add(stack).growX().padLeft(4f).fillX();
        exprField.visible = false;

        // 点击 Label → 进入编辑模式
        exprLabel.addListener(new ClickListener(){
            @Override
            public void clicked(InputEvent event, float x, float y){
                exprField.setText(expr);
                exprLabel.visible = false;
                exprField.visible = true;
                Core.scene.setKeyboardFocus(exprField);
                Core.scene.setScrollFocus(exprField);
            }
        });

        // 失焦检测：用 update 轮询焦点，切回 Label 显示
        final boolean[] wasFocused = {false};
        exprField.update(() -> {
            boolean focused = Core.scene.getKeyboardFocus() == exprField;
            if(wasFocused[0] && !focused){
                exprField.visible = false;
                exprLabel.visible = true;
                updateLabel.run();
            }
            wasFocused[0] = focused;
        });
    }

    /** 把表达式转为带颜色标记的富文本，用于 Label 高亮显示。
     *  复用 ExprCompiler.tokenize 分类着色，用 token.start 保留原始空白：
     *  - 数字：金色
     *  - 函数名：珊瑚色（后跟左括号）
     *  - 变量名：白色
     *  - 运算符/括号/逗号：浅灰 */
    private String highlightExpr(String expr){
        if(expr == null || expr.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        try{
            List<ExprCompiler.Token> tokens = ExprCompiler.tokenize(expr);
            int lastEnd = 0;
            for(int i = 0; i < tokens.size(); i++){
                ExprCompiler.Token tok = tokens.get(i);
                if(tok.type == ExprCompiler.TokType.EOF) break;

                // 输出上一个 token 到当前 token 之间的原始空白
                if(tok.start > lastEnd){
                    sb.append(expr, lastEnd, tok.start);
                }

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
                String text = tok.text.replace("[", "[[").replace("]", "]]");
                sb.append("[").append(color).append("]").append(text).append("[]");
                lastEnd = tok.start + tok.text.length();
            }
            // 尾部空白
            if(lastEnd < expr.length()){
                sb.append(expr, lastEnd, expr.length());
            }
        }catch(Exception e){
            // 解析失败，原样返回（转义 [ ]）
            sb.setLength(0);
            sb.append(expr.replace("[", "[[").replace("]", "]]"));
        }
        return sb.toString();
    }

    @Override
    public LStatement copy(){
        Log.info("[LogicAssist] ExprStatement.copy() called: dest=@ expr=@", dest, expr);
        ExprStatement copy = new ExprStatement();
        copy.dest = this.dest;
        copy.expr = this.expr;
        copy.lastOps = this.lastOps;
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

    @Override
    public String name(){
        return "Expr";
    }

    @Override
    public LCategory category(){
        return LCategory.operation;
    }
}
