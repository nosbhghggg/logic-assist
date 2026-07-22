package logicassist.expr;

import arc.*;
import arc.scene.*;
import arc.scene.event.VisibilityListener;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.logic.*;
import mindustry.logic.LCanvas.*;
import mindustry.logic.LStatements.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * 表达式集成钩子：在逻辑编辑器打开时折叠 op 链为表达式 UI，关闭前展开。
 *
 * 折叠流程：编辑器打开 → 延迟数帧（等待 rebuild 完成）→ 扫描 op 链 → 替换为 ExprStatement
 * 展开流程：dialog.hidden 监听器（插入到列表头部，先于 save 执行）→ 编译表达式 → 替换回 OperationStatement
 *
 * 跳转索引调整：
 * - 折叠时：N 个 op → 1 个 ExprStmt，destIndex >= i+N 的减 (N-1)
 * - 展开时：1 个 ExprStmt → N 个 op，destIndex > i 的加 (N-1)
 */
public class ExprHook{

    private static boolean initialized = false;
    private static int foldDelay = 0;

    // 反射缓存：updateJumpHeights 是 DragLayout 的包私有字段
    private static Field updateJumpHeightsField;
    private static boolean updateJumpHeightsFieldChecked = false;

    public static void init(){
        Core.app.post(ExprHook::tick);
    }

    private static void tick(){
        Core.app.post(ExprHook::tick);

        try{
            LogicDialog dialog = Vars.ui.logic;
            if(dialog == null) return;

            // 初始化：注册 capture 阶段的 hidden 监听器（capture 先于 regular 执行，确保在 save 之前展开）
            if(!initialized){
                dialog.addCaptureListener(new VisibilityListener(){
                    @Override
                    public boolean hidden(){
                        unfoldAll(dialog.canvas);
                        return false;
                    }
                });
                initialized = true;
            }

            if(dialog.isShown()){
                if(foldDelay > 0){
                    foldDelay--;
                    if(foldDelay == 0){
                        foldAll(dialog.canvas);
                    }
                }
            }else{
                foldDelay = 5; // 对话框关闭后重置延迟
            }
        }catch(Throwable t){
            Log.debug("[LogicAssist] ExprHook tick error: @", t);
        }
    }

    // ===== 折叠：op 链 → ExprStatement =====

    private static void foldAll(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return;

        Seq<Element> children = canvas.statements.getChildren();
        if(children.isEmpty()) return;

        // 先 saveUI 同步所有跳转的 destIndex
        saveUIAll(canvas);

        boolean changed = false;
        int i = 0;
        while(i < children.size){
            if(!(children.get(i) instanceof StatementElem) ||
               !(((StatementElem)children.get(i)).st instanceof OperationStatement)){
                i++;
                continue;
            }

            // 从 i 开始查找表达式链
            List<ExprCompiler.OpLine> ops = new ArrayList<>();
            int j = i;
            while(j < children.size){
                if(!(children.get(j) instanceof StatementElem)) break;
                StatementElem elem = (StatementElem)children.get(j);
                if(!(elem.st instanceof OperationStatement)) break;

                OperationStatement opStmt = (OperationStatement)elem.st;
                ops.add(new ExprCompiler.OpLine(
                    opStmt.op.name(), opStmt.dest, opStmt.a, opStmt.b));

                // 链终止：dest 不是临时变量
                if(!ExprCompiler.isTemp(opStmt.dest)){
                    j++;
                    break;
                }
                j++;
            }

            int chainLen = j - i;
            if(chainLen >= 2){
                // 尝试逆向重建表达式
                String expr = ExprCompiler.rebuild(ops);
                if(expr != null){
                    // 成功重建，执行折叠
                    String dest = ops.get(ops.size() - 1).dest;

                    ExprStatement exprStmt = new ExprStatement();
                    exprStmt.dest = dest;
                    exprStmt.expr = expr;
                    exprStmt.lastOps = ops;

                    // 移除旧的 StatementElem
                    for(int k = 0; k < chainLen; k++){
                        ((StatementElem)children.get(i)).remove();
                    }

                    // 在位置 i 插入新的 ExprStatement
                    canvas.addAt(i, exprStmt);

                    // 调整跳转索引
                    adjustJumpIndices(canvas, i + chainLen - 1, -(chainLen - 1));
                    // 指向链内部的跳转改为指向 ExprStatement
                    clampJumpIndices(canvas, i, i + chainLen - 1, i);

                    changed = true;
                    // i 不变，新元素在位置 i
                }
            }
            i++;
        }

        if(changed){
            setupUIAll(canvas);
            markUpdateJumpHeights(canvas);
            Log.debug("[LogicAssist] Expression chains folded");
        }
    }

    // ===== 展开：ExprStatement → op 链 =====

    private static void unfoldAll(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return;

        Seq<Element> children = canvas.statements.getChildren();
        if(children.isEmpty()) return;

        // 先 saveUI 同步所有跳转的 destIndex
        saveUIAll(canvas);

        boolean changed = false;
        for(int i = 0; i < children.size; i++){
            if(!(children.get(i) instanceof StatementElem)) continue;
            StatementElem elem = (StatementElem)children.get(i);
            if(!(elem.st instanceof ExprStatement)) continue;

            ExprStatement exprStmt = (ExprStatement)elem.st;

            // 编译表达式
            List<ExprCompiler.OpLine> ops;
            try{
                ops = ExprCompiler.compile(exprStmt.dest, exprStmt.expr);
            }catch(Exception e){
                // 编译失败：使用 lastOps 或跳过
                ops = exprStmt.lastOps;
                if(ops == null || ops.isEmpty()){
                    Log.warn("[LogicAssist] Failed to unfold expression: @", exprStmt.expr);
                    continue;
                }
            }

            int chainLen = ops.size();

            // 移除 ExprStatementElem
            elem.remove();

            // 插入 OperationStatement StatementElems
            for(int k = 0; k < chainLen; k++){
                ExprCompiler.OpLine line = ops.get(k);
                OperationStatement opStmt = new OperationStatement();
                opStmt.op = LogicOp.valueOf(line.op);
                opStmt.dest = line.dest;
                opStmt.a = line.a;
                opStmt.b = line.b;

                canvas.addAt(i + k, opStmt);
            }

            // 调整跳转索引：destIndex > i 的加 (chainLen - 1)
            adjustJumpIndices(canvas, i, chainLen - 1);

            changed = true;
            // 跳过已插入的元素
            i += chainLen - 1;
        }

        if(changed){
            setupUIAll(canvas);
            markUpdateJumpHeights(canvas);
            Log.debug("[LogicAssist] Expression statements unfolded");
        }
    }

    // ===== 跳转索引调整 =====

    /** destIndex > threshold 的加上 delta */
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

    /** destIndex 在 [lo, hi] 范围内的设为 value */
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

    // ===== 工具方法 =====

    /** 通过反射设置 updateJumpHeights = true（DragLayout 包私有字段） */
    private static void markUpdateJumpHeights(LCanvas canvas){
        try{
            if(!updateJumpHeightsFieldChecked){
                updateJumpHeightsFieldChecked = true;
                updateJumpHeightsField = LCanvas.DragLayout.class.getDeclaredField("updateJumpHeights");
                updateJumpHeightsField.setAccessible(true);
            }
            if(updateJumpHeightsField != null){
                updateJumpHeightsField.set(canvas.statements, true);
            }
        }catch(Exception e){
            Log.debug("[LogicAssist] Failed to set updateJumpHeights: @", e);
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
