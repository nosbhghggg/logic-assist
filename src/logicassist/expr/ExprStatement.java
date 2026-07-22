package logicassist.expr;

import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import mindustry.logic.*;
import mindustry.logic.LExecutor.LInstruction;

import java.util.*;

/**
 * 表达式语句：在逻辑编辑器中以表达式形式显示，保存时自动展开为 op 链。
 *
 * 折叠态：[dest] = [expr text field]
 * 展开态：op cos _ a 0 / op mul _ _ 10 / op add x _ x
 *
 * 关键：write() 输出 op 链文本，保证保存的代码始终是标准 mlog。
 */
public class ExprStatement extends LStatement{

    /** 目标变量名 */
    public String dest = "result";
    /** 表达式字符串 */
    public String expr = "";

    /** 上次编译的 op 链（用于 fallback 和调试） */
    public transient List<ExprCompiler.OpLine> lastOps;

    @Override
    public void write(StringBuilder builder){
        List<ExprCompiler.OpLine> lines;
        try{
            lines = ExprCompiler.compile(dest, expr);
            lastOps = lines;
        }catch(Exception e){
            // 编译失败：使用上次成功的 ops，或输出 fallback
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
        table.left();
        // dest 字段（使用 LStatement.field 以保持样式一致）
        field(table, dest, str -> dest = str);
        table.add(" = ");
        // 表达式文本框（不使用 field() 以避免空格被替换为下划线）
        table.field(expr, str -> {
            expr = str;
            // 实时编译尝试，更新 lastOps
            try{
                lastOps = ExprCompiler.compile(dest, expr);
            }catch(Exception e){
                // 忽略输入中的语法错误
            }
        }).growX().padLeft(4f).get();
    }

    @Override
    public LInstruction build(LAssembler builder){
        // 执行时不直接调用——write() 输出 op 链后由 LExecutor 重新解析
        return null;
    }

    @Override
    public String name(){
        return "Expr";
    }
}
