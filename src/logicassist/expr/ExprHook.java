package logicassist.expr;

import arc.*;
import arc.func.*;
import arc.scene.*;
import arc.struct.*;
import arc.util.*;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.logic.LCanvas.*;
import mindustry.logic.LStatements.*;

import java.util.*;

/**
 * 表达式集成钩子：提供 op 链 ↔ 表达式的双向转换。
 *
 * foldAll() 由 LogicCanvas.load() 调用，save() 由 LogicCanvas.save() 调用
 * （unfoldAll → super.save → foldAll）。
 *
 * 致谢：mindcode (https://github.com/cardillan/mindcode)
 * op 链折叠思路参考自 MlogDecompiler.collapseExpressions()，
 * 用 hasJumpInRange() 实现等价的跳转安全检查。
 */
public class ExprHook{

    private static boolean statementRegistered = false;

    public static void init(){
        registerStatement();
    }

    // 将 ExprStatement 注入 LogicIO.allStatements，使其出现在编辑器的积木列表中。
    private static void registerStatement(){
        if(statementRegistered) return;
        for(Prov<LStatement> prov : LogicIO.allStatements){
            if(prov.get() instanceof ExprStatement) return;
        }
        LogicIO.allStatements.add(() -> new ExprStatement());
        statementRegistered = true;
        Log.info("[LogicAssist] ExprStatement registered to LogicIO.allStatements");
    }

    public static void foldAll(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return;

        Seq<Element> children = canvas.statements.getChildren();
        if(children.isEmpty()) return;

        saveUIAll(canvas);

        boolean changed = false;
        int i = 0;
        while(i < children.size){
            if(!(children.get(i) instanceof StatementElem) ||
               !(((StatementElem)children.get(i)).st instanceof OperationStatement)){
                i++;
                continue;
            }

            List<ExprCompiler.OpLine> ops = new ArrayList<>();
            int j = i;
            while(j < children.size){
                if(!(children.get(j) instanceof StatementElem)) break;
                StatementElem elem = (StatementElem)children.get(j);
                if(!(elem.st instanceof OperationStatement)) break;

                OperationStatement opStmt = (OperationStatement)elem.st;
                ops.add(new ExprCompiler.OpLine(
                    opStmt.op.name(), opStmt.dest, opStmt.a, opStmt.b));

                if(!ExprCompiler.isTemp(opStmt.dest)){
                    j++;
                    break;
                }
                j++;
            }

            int chainLen = j - i;
            if(chainLen >= 2){
                // 安全检查：若有 jump 指向链中间 [i+1, i+chainLen-1]，放弃折叠。
                // 场景：别人没装插件时写的 jump 指向 op 链中间，折叠会改变语义。
                // 指向链首 i 是允许的，折叠后仍指向 expr 积木。
                if(hasJumpInRange(canvas, i + 1, i + chainLen - 1)){
                    i = j; // 跳过整条链，不折叠
                    continue;
                }
                String expr = ExprCompiler.rebuild(ops);
                if(expr != null){
                    String dest = ops.get(ops.size() - 1).dest;

                    ExprStatement exprStmt = new ExprStatement();
                    exprStmt.dest = dest;
                    exprStmt.expr = expr;
                    // 重新编译优化后的表达式，使 lastOps 反映实际 op 行数
                    // （rebuild 优化可能减少 op 数量，原 ops 是优化前的）
                    try{
                        exprStmt.lastOps = ExprCompiler.compile(dest, expr);
                    }catch(Exception ignored){
                        exprStmt.lastOps = ops;
                    }

                    for(int k = 0; k < chainLen; k++){
                        ((StatementElem)children.get(i)).remove();
                    }

                    canvas.addAt(i, exprStmt);

                    // 链后面的 jump destIndex 减 (chainLen-1)
                    adjustJumpIndices(canvas, i + chainLen - 1, -(chainLen - 1));

                    changed = true;
                }else{
                    // rebuild 失败（如链不完整），跳过整条链，
                    // 避免 i++ 后从链中间重新查找子链导致误折叠
                    i = j;
                    continue;
                }
            }
            i++;
        }

        if(changed){
            setupUIAll(canvas);
            // 行号由 LogicDragLayout.layout() 自动更新，无需手动调用
            canvas.statements.updateJumpHeights = true;
            Log.debug("[LogicAssist] Expression chains folded");
        }
    }

    public static void unfoldAll(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return;

        Seq<Element> children = canvas.statements.getChildren();
        if(children.isEmpty()) return;

        saveUIAll(canvas);

        boolean changed = false;
        for(int i = 0; i < children.size; i++){
            if(!(children.get(i) instanceof StatementElem)) continue;
            StatementElem elem = (StatementElem)children.get(i);
            if(!(elem.st instanceof ExprStatement)) continue;

            ExprStatement exprStmt = (ExprStatement)elem.st;

            List<ExprCompiler.OpLine> ops;
            try{
                ops = ExprCompiler.compile(exprStmt.dest, exprStmt.expr);
            }catch(Exception e){
                // 编译失败：保留 ExprStatement 不展开，write() 会输出 lastOps
                continue;
            }

            // 单个 op 不展开：foldAll 要求 chainLen>=2 才折叠，
            // 展开后无法折叠回来会导致 ExprStatement 丢失
            if(ops.size() < 2){
                continue;
            }

            int chainLen = ops.size();

            elem.remove();

            for(int k = 0; k < chainLen; k++){
                ExprCompiler.OpLine line = ops.get(k);
                OperationStatement opStmt = new OperationStatement();
                opStmt.op = LogicOp.valueOf(line.op);
                opStmt.dest = line.dest;
                opStmt.a = line.a;
                opStmt.b = line.b;

                canvas.addAt(i + k, opStmt);
            }

            adjustJumpIndices(canvas, i, chainLen - 1);

            changed = true;
            i += chainLen - 1;
        }

        if(changed){
            setupUIAll(canvas);
            canvas.statements.updateJumpHeights = true;
            Log.debug("[LogicAssist] Expression statements unfolded");
        }
    }

    private static void adjustJumpIndices(LCanvas canvas, int threshold, int delta){
        if(delta == 0) return;
        Seq<Element> children = canvas.statements.getChildren();
        for(Element child : children){
            if(!(child instanceof StatementElem)) continue;
            StatementElem elem = (StatementElem)child;
            if(elem.st instanceof JumpStatement){
                JumpStatement jump = (JumpStatement)elem.st;
                if(jump.destIndex > threshold){
                    jump.destIndex += delta;
                }
            }
        }
    }

    // 检查是否有 JumpStatement 的 destIndex 落在 [lo, hi] 范围内
    private static boolean hasJumpInRange(LCanvas canvas, int lo, int hi){
        if(lo > hi) return false;
        Seq<Element> children = canvas.statements.getChildren();
        for(Element child : children){
            if(!(child instanceof StatementElem)) continue;
            StatementElem elem = (StatementElem)child;
            if(elem.st instanceof JumpStatement){
                JumpStatement jump = (JumpStatement)elem.st;
                if(jump.destIndex >= lo && jump.destIndex <= hi){
                    return true;
                }
            }
        }
        return false;
    }

    // 检测 canvas 中是否有 print 指令包含 "expr-opt:true"，据此更新优化开关。
    // 开关状态变化时清空所有 ExprStatement.lastOps，强制下次重新编译。
    public static void updateOptimizationFlag(LCanvas canvas){
        boolean enabled = false;
        if(canvas != null && canvas.statements != null){
            for(Element child : canvas.statements.getChildren()){
                if(!(child instanceof StatementElem)) continue;
                LStatement st = ((StatementElem)child).st;
                if(st instanceof PrintStatement){
                    String value = ((PrintStatement)st).value;
                    if(value != null && value.contains("expr-opt:true")){
                        enabled = true;
                        break;
                    }
                }
            }
        }
        // 开关状态变化时清空 lastOps，强制 updateMlogAddresses 重新编译
        if(enabled != ExprCompiler.optimizationEnabled){
            ExprCompiler.optimizationEnabled = enabled;
            if(canvas != null && canvas.statements != null){
                for(Element child : canvas.statements.getChildren()){
                    if(!(child instanceof StatementElem)) continue;
                    LStatement st = ((StatementElem)child).st;
                    if(st instanceof ExprStatement){
                        ((ExprStatement)st).lastOps = null;
                    }
                }
            }
        }
    }

    private static void saveUIAll(LCanvas canvas){
        for(Element child : canvas.statements.getChildren()){
            if(child instanceof StatementElem){
                ((StatementElem)child).st.saveUI();
            }
        }
    }

    private static void setupUIAll(LCanvas canvas){
        for(Element child : canvas.statements.getChildren()){
            if(child instanceof StatementElem){
                ((StatementElem)child).st.setupUI();
            }
        }
    }
}
