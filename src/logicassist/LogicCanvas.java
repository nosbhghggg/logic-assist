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
 * 继承原版 LCanvas，覆盖关键生命周期方法。
 *
 * 行号更新策略（v4 - act 中修改 + 重置 needsLayout）：
 *
 * 原版流程：validate() → layout() → updateAddress(i) → addressLabel.setText(i)
 * validate() 在 act() 和 draw() 中都可能触发。
 *
 * v1（label.update 回调）：setText() 触发 invalidateHierarchy()，同一帧被覆盖。
 * v2（post-draw setText + 重置 invalidated）：DragLayout.invalidated 不是 validate 的开关。
 * v3（post-draw 覆盖绘制）：super.draw() 后绘制状态可能不正确，覆盖层不可见。
 *
 * v4 方案：
 * 1. 覆盖 act()：super.act() 后强制 invalidate+validate（完成 layout → updateAddress）
 * 2. 立即修改 label 为 mlog 行号（setText 触发 invalidateHierarchy → needsLayout=true）
 * 3. 反射重置 WidgetGroup.needsLayout=false，防止 draw() 中 validate() 重新 layout
 * 4. draw() → super.draw() → validate() → needsLayout==false → 不 layout → 不 updateAddress
 * 5. label 用我们的文本绘制
 */
public class LogicCanvas extends LCanvas{

    private static Field addressLabelField;
    private static Field needsLayoutField;

    static{
        try{
            addressLabelField = LCanvas.StatementElem.class.getDeclaredField("addressLabel");
            addressLabelField.setAccessible(true);
        }catch(Exception e){
            Log.err("[LogicAssist] Failed to access StatementElem.addressLabel", e);
        }
        try{
            needsLayoutField = WidgetGroup.class.getDeclaredField("needsLayout");
            needsLayoutField.setAccessible(true);
        }catch(Exception e){
            Log.err("[LogicAssist] Failed to access WidgetGroup.needsLayout", e);
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
    public void act(float delta){
        super.act(delta);
        updateMlogAddresses();
    }

    @Override
    public void draw(){
        super.draw();
        JumpLineColor.patchAllCurves(this);
    }

    /**
     * 在 act() 中 super.act() 之后更新 mlog 行号。
     *
     * 1. 强制 invalidate+validate 完成 layout（updateAddress 设置原版文本）
     * 2. 修改 label 为 mlog 行号
     * 3. 重置 needsLayout=false，防止 draw() 中 validate() 重新 layout 覆盖
     */
    private void updateMlogAddresses(){
        if(statements == null || addressLabelField == null) return;
        Seq<Element> children = statements.getChildren();
        if(children.isEmpty()) return;

        // 强制完成布局（触发 layout → updateAddress 设置原版文本）
        statements.invalidate();
        statements.validate();

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

        // setText() 触发了 invalidateHierarchy() → statements.needsLayout = true
        // 重置为 false，防止 draw() 中 validate() → layout() → updateAddress() 覆盖
        if(changed && needsLayoutField != null){
            try{
                needsLayoutField.setBoolean(statements, false);
            }catch(Exception ignored){}
        }
    }
}
