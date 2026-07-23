package logicassist;

import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import logicassist.expr.*;
import mindustry.logic.*;

import java.lang.reflect.*;

/**
 * 夺舍版 LCanvas：继承原版 LCanvas，覆盖关键生命周期方法。
 *
 * 行号更新策略（v2）：
 * 原版 DragLayout.layout() 在 validate() 中触发，通过 updateAddress(i)
 * 将 addressLabel 设为 UI 索引。validate() 可能在 act() 或 draw() 期间调用。
 *
 * 旧方案用 label.update() 回调，但 setText() 触发 invalidateHierarchy()，
 * 导致同一帧 validate() → layout() → updateAddress() 覆盖我们的文本。
 * 唯一不被覆盖的情况是 dragging != null 时 layout() 跳过拖动元素。
 *
 * 新方案：在 super.draw()（含 layout → updateAddress → drawChildren）之后
 * 统一更新所有标签。若文本有变，重置 WidgetGroup.invalidated = false，
 * 防止下一帧 validate() → layout() → updateAddress() 覆盖。
 * 稳态下文本不变，setText() 空操作，不会触发 invalidation。
 */
public class LogicCanvas extends LCanvas{

    private static Field addressLabelField;
    private static Field wgInvalidatedField;

    static{
        try{
            addressLabelField = LCanvas.StatementElem.class.getDeclaredField("addressLabel");
            addressLabelField.setAccessible(true);
        }catch(Exception e){
            Log.err("[LogicAssist] Failed to access StatementElem.addressLabel", e);
        }
        try{
            wgInvalidatedField = WidgetGroup.class.getDeclaredField("invalidated");
            wgInvalidatedField.setAccessible(true);
        }catch(Exception e){
            Log.err("[LogicAssist] Failed to access WidgetGroup.invalidated", e);
        }
    }

    public LogicCanvas(){
        super();
    }

    @Override
    public void load(String asm){
        super.load(asm);
        ExprHook.foldAll(this);
    }

    @Override
    public String save(){
        ExprHook.unfoldAll(this);
        String result = super.save();
        ExprHook.foldAll(this);
        return result;
    }

    @Override
    public void draw(){
        super.draw();
        JumpLineColor.patchAllCurves(this);
        updateMlogAddresses();
    }

    /**
     * 在 super.draw() 之后更新所有积木的 mlog 行号。
     *
     * 单次遍历 O(N)，比旧方案的每标签 O(N) 回调（总 O(N²)）更高效。
     * 表达式积木显示行号区间（如 0->2），普通积木显示单行号。
     */
    private void updateMlogAddresses(){
        if(statements == null || addressLabelField == null) return;
        Seq<Element> children = statements.getChildren();
        if(children.isEmpty()) return;

        boolean changed = false;
        int mlogLine = 0;
        for(Element child : children){
            if(!(child instanceof LCanvas.StatementElem)) continue;
            LCanvas.StatementElem elem = (LCanvas.StatementElem)child;

            int lineCount = 1;
            if(elem.st instanceof ExprStatement){
                ExprStatement exprStmt = (ExprStatement)elem.st;
                if(exprStmt.lastOps == null){
                    try{
                        exprStmt.lastOps = ExprCompiler.compile(exprStmt.dest, exprStmt.expr);
                    }catch(Exception ignored){}
                }
                lineCount = (exprStmt.lastOps != null) ? exprStmt.lastOps.size() : 1;
            }

            String text = lineCount > 1
                ? (mlogLine + "->" + (mlogLine + lineCount - 1))
                : (mlogLine + "");

            try{
                Label label = (Label)addressLabelField.get(elem);
                if(label != null){
                    String current = label.getText().toString();
                    if(!current.equals(text)){
                        label.setText(text);
                        changed = true;
                    }
                }
            }catch(Exception ignored){}

            mlogLine += lineCount;
        }

        // setText() 触发了 invalidateHierarchy() → WidgetGroup.invalidated = true。
        // 重置为 false，防止下一帧 validate() → layout() → updateAddress() 覆盖。
        if(changed && wgInvalidatedField != null){
            try{
                wgInvalidatedField.setBoolean(statements, false);
            }catch(Exception ignored){}
        }
    }
}
