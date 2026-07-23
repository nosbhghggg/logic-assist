package logicassist.expr;

import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import mindustry.logic.*;
import mindustry.logic.LExecutor.*;
import mindustry.logic.LStatements.*;

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
 * - 行号显示由 ExprHook.updateAddressLabels() 统一管理，不在此类处理
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
        // 表达式文本框（不使用 field() 以避免空格被替换为下划线）
        table.field(expr, str -> {
            expr = str;
            try{
                lastOps = ExprCompiler.compile(dest, expr);
            }catch(Exception e){
                // 忽略输入中的语法错误
            }
        }).growX().padLeft(4f).get();
    }

    @Override
    public LStatement copy(){
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
