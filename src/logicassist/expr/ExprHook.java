package logicassist.expr;

import arc.*;
import arc.func.*;
import arc.scene.*;
import arc.scene.ui.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.logic.*;
import mindustry.logic.LCanvas.*;
import mindustry.logic.LStatements.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * 表达式集成钩子：提供 op 链 ↔ 表达式的双向转换。
 *
 * 夺舍架构后：
 * - foldAll() 由 LogicCanvas.load() 直接调用，零延迟
 * - save() 由 LogicCanvas.save() 调用：unfoldAll → super.save → foldAll
 * - updateJumpHeights 是 DragLayout 的 public 字段，直接访问
 */
public class ExprHook{

    private static boolean statementRegistered = false;

    // 反射缓存：StatementElem.addressLabel
    private static Field addressLabelField;
    private static boolean addressLabelFieldChecked = false;

    public static void init(){
        registerStatement();
    }

    /** 将 ExprStatement 注入 LogicIO.allStatements，使其出现在编辑器的积木列表中。 */
    @SuppressWarnings("unchecked")
    private static void registerStatement(){
        if(statementRegistered) return;
        try{
            Class<?> logicIO = Class.forName("mindustry.gen.LogicIO");
            Field allStatementsField = logicIO.getField("allStatements");
            Seq<Prov<LStatement>> allStatements = (Seq<Prov<LStatement>>)allStatementsField.get(null);

            for(Prov<LStatement> prov : allStatements){
                if(prov.get() instanceof ExprStatement) return;
            }

            allStatements.add(() -> new ExprStatement());
            statementRegistered = true;
            Log.info("[LogicAssist] ExprStatement registered to LogicIO.allStatements");
        }catch(Exception e){
            Log.err("[LogicAssist] Failed to register ExprStatement", e);
        }
    }

    // ===== 折叠：op 链 → ExprStatement =====

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
                String expr = ExprCompiler.rebuild(ops);
                if(expr != null){
                    String dest = ops.get(ops.size() - 1).dest;

                    ExprStatement exprStmt = new ExprStatement();
                    exprStmt.dest = dest;
                    exprStmt.expr = expr;
                    exprStmt.lastOps = ops;

                    for(int k = 0; k < chainLen; k++){
                        ((StatementElem)children.get(i)).remove();
                    }

                    canvas.addAt(i, exprStmt);

                    // 关键修复：先钳制再调整
                    // 1. 先钳制：destIndex 指向 op 链内部 [i, i+chainLen-1] 的改为 i
                    clampJumpIndices(canvas, i, i + chainLen - 1, i);
                    // 2. 后调整：destIndex 在链后面的 ( > i+chainLen-1 ) 减 (chainLen-1)
                    adjustJumpIndices(canvas, i + chainLen - 1, -(chainLen - 1));

                    changed = true;
                }
            }
            i++;
        }

        if(changed){
            setupUIAll(canvas);
            updateAddressLabels(canvas);
            canvas.statements.updateJumpHeights = true;
            Log.debug("[LogicAssist] Expression chains folded");
        }
    }

    // ===== 展开：ExprStatement → op 链 =====

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
                ops = exprStmt.lastOps;
                if(ops == null || ops.isEmpty()){
                    Log.warn("[LogicAssist] Failed to unfold expression: @", exprStmt.expr);
                    continue;
                }
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

    // ===== 跳转索引调整 =====

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

    private static void clampJumpIndices(LCanvas canvas, int lo, int hi, int value){
        Seq<Element> children = canvas.statements.getChildren();
        for(Element child : children){
            if(!(child instanceof StatementElem)) continue;
            StatementElem elem = (StatementElem)child;
            if(elem.st instanceof JumpStatement){
                JumpStatement jump = (JumpStatement)elem.st;
                if(jump.destIndex >= lo && jump.destIndex <= hi){
                    jump.destIndex = value;
                }
            }
        }
    }

    // ===== 行号显示：更新所有积木的 addressLabel 为 mlog 行号 =====

    /** 折叠后更新所有积木的地址标签，显示展开后的真实 mlog 行号。
     *  - ExprStatement 显示行号区间（如 "0→2"）
     *  - 其他积木显示单行号
     *  - 后面积木的行号自动跳过 Expr 展开的行数
     */
    public static void updateAddressLabels(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return;
        Seq<Element> children = canvas.statements.getChildren();

        int mlogLine = 0;
        for(Element child : children){
            if(!(child instanceof StatementElem)) continue;
            StatementElem elem = (StatementElem)child;

            if(elem.st instanceof ExprStatement){
                ExprStatement exprStmt = (ExprStatement)elem.st;
                int lineCount = (exprStmt.lastOps != null) ? exprStmt.lastOps.size() : 1;
                int endLine = mlogLine + lineCount - 1;
                // 多行显示区间，单行显示数字
                setAddressLabel(elem, lineCount > 1 ? (mlogLine + "-" + endLine) : (mlogLine + ""));
                mlogLine += lineCount;
            }else{
                setAddressLabel(elem, mlogLine + "");
                mlogLine++;
            }
        }
    }

    /** 通过反射设置 StatementElem.addressLabel 的文本 */
    private static void setAddressLabel(StatementElem elem, String text){
        try{
            if(!addressLabelFieldChecked){
                addressLabelFieldChecked = true;
                addressLabelField = StatementElem.class.getDeclaredField("addressLabel");
                addressLabelField.setAccessible(true);
            }
            if(addressLabelField != null){
                Label label = (Label)addressLabelField.get(elem);
                if(label != null){
                    label.setText(text);
                }
            }
        }catch(Exception e){
            // 反射失败，保持原版显示
        }
    }

    // ===== 工具方法 =====

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
