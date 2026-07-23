package logicassist;

import arc.scene.*;
import arc.scene.ui.*;
import arc.struct.*;
import arc.util.*;
import logicassist.expr.*;
import mindustry.logic.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * 夺舍版 LCanvas：继承原版 LCanvas，覆盖关键生命周期方法。
 *
 * 行号更新策略：
 * 原版 DragLayout.layout() 在 act()/draw() 期间的 validate() 中都可能触发，
 * 通过 updateAddress(i) 设置 addressLabel 文本为 UI 索引。
 * 无法用单一覆盖点（act/draw）修正，因为 layout 时机不确定。
 *
 * 解决方案：给每个 addressLabel 注册 update 回调（每帧执行，在 layout 之后）。
 * 回调中根据积木在列表中的位置计算 mlog 行号。
 * 用 HashSet 追踪已 hook 的 Label，避免重复注册。
 */
public class LogicCanvas extends LCanvas{

    private static Field addressLabelField;
    private static final Set<Label> hookedLabels = Collections.newSetFromMap(new WeakHashMap<>());

    static{
        try{
            addressLabelField = LCanvas.StatementElem.class.getDeclaredField("addressLabel");
            addressLabelField.setAccessible(true);
        }catch(Exception e){
            Log.err("[LogicAssist] Failed to access StatementElem.addressLabel", e);
        }
    }

    public LogicCanvas(){
        super();
    }

    @Override
    public void load(String asm){
        super.load(asm);
        ExprHook.foldAll(this);
        hookAddressLabels();
    }

    @Override
    public String save(){
        ExprHook.unfoldAll(this);
        String result = super.save();
        ExprHook.foldAll(this);
        hookAddressLabels();
        return result;
    }

    @Override
    public void draw(){
        super.draw();
        JumpLineColor.patchAllCurves(this);
    }

    /** 为所有未 hook 的 StatementElem 的 addressLabel 注册 update 回调 */
    private void hookAddressLabels(){
        if(statements == null || addressLabelField == null) return;
        Seq<Element> children = statements.getChildren();
        for(Element child : children){
            if(!(child instanceof LCanvas.StatementElem)) continue;
            LCanvas.StatementElem elem = (LCanvas.StatementElem)child;
            try{
                Label label = (Label)addressLabelField.get(elem);
                if(label == null || hookedLabels.contains(label)) continue;
                hookedLabels.add(label);
                label.update(() -> updateSingleAddress(elem, label));
            }catch(Exception ignored){
            }
        }
    }

    /** 计算目标积木的 mlog 行号并更新 addressLabel */
    private void updateSingleAddress(LCanvas.StatementElem target, Label label){
        if(statements == null) return;
        Seq<Element> children = statements.getChildren();
        int mlogLine = 0;
        for(Element child : children){
            if(!(child instanceof LCanvas.StatementElem)) continue;
            LCanvas.StatementElem elem = (LCanvas.StatementElem)child;

            if(elem == target){
                String text;
                if(elem.st instanceof ExprStatement){
                    ExprStatement exprStmt = (ExprStatement)elem.st;
                    if(exprStmt.lastOps == null){
                        try{
                            exprStmt.lastOps = ExprCompiler.compile(exprStmt.dest, exprStmt.expr);
                        }catch(Exception ignored){}
                    }
                    int lineCount = (exprStmt.lastOps != null) ? exprStmt.lastOps.size() : 1;
                    int endLine = mlogLine + lineCount - 1;
                    text = lineCount > 1 ? (mlogLine + "->" + endLine) : (mlogLine + "");
                }else{
                    text = mlogLine + "";
                }
                if(!label.getText().toString().equals(text)){
                    label.setText(text);
                }
                return;
            }

            if(elem.st instanceof ExprStatement){
                ExprStatement exprStmt = (ExprStatement)elem.st;
                if(exprStmt.lastOps == null){
                    try{
                        exprStmt.lastOps = ExprCompiler.compile(exprStmt.dest, exprStmt.expr);
                    }catch(Exception ignored){}
                }
                mlogLine += (exprStmt.lastOps != null) ? exprStmt.lastOps.size() : 1;
            }else{
                mlogLine++;
            }
        }
    }
}
