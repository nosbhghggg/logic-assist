package logicassist;

import arc.scene.*;
import arc.struct.*;
import arc.util.*;
import logicassist.expr.*;
import mindustry.logic.*;

import java.lang.reflect.*;

/**
 * 夺舍版 LCanvas：继承原版 LCanvas，覆盖关键生命周期方法。
 *
 * 由于模组类加载器 ≠ 游戏类加载器，即使放在同包也无法访问包级私有字段。
 * 因此本类放在 logicassist 包下，仅对必要的包级私有字段使用反射（缓存 Field）。
 *
 * 反射字段：
 * - StatementElem.addressLabel（包级私有）— 用于更新 mlog 行号
 * - LCanvas.dragging（包级私有）— BoxSelect 清除拖拽状态时访问
 * - LCanvas.privileged（包级私有）— BoxSelect 检查特权模式时访问
 *
 * public 字段直接访问：statements, pane, st
 */
public class LogicCanvas extends LCanvas{

    private static Field addressLabelField;

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
        // 在 act() 中更新行号：super.act() 已完成所有子元素的 layout()（含 DragLayout.layout()
        // 设置原版 UI 索引），此时覆盖为 mlog 行号不会被 layout() 再覆盖回去。
        // draw() 阶段 Label 会用自己的文本重新布局字形缓存，显示正确。
        updateMlogAddresses();
    }

    @Override
    public void draw(){
        super.draw();
        JumpLineColor.patchAllCurves(this);
    }

    /**
     * 遍历所有积木，将 addressLabel 从 UI 索引更新为 mlog 行号。
     * 通过反射访问包级私有字段 addressLabel。
     */
    private void updateMlogAddresses(){
        if(statements == null || addressLabelField == null) return;
        Seq<Element> children = statements.getChildren();
        int mlogLine = 0;
        for(Element child : children){
            if(!(child instanceof LCanvas.StatementElem)) continue;
            LCanvas.StatementElem elem = (LCanvas.StatementElem)child;

            if(elem.st instanceof ExprStatement){
                ExprStatement exprStmt = (ExprStatement)elem.st;
                if(exprStmt.lastOps == null){
                    try{
                        exprStmt.lastOps = ExprCompiler.compile(exprStmt.dest, exprStmt.expr);
                    }catch(Exception ignored){
                        // 编译失败，按单行处理
                    }
                }
                int lineCount = (exprStmt.lastOps != null) ? exprStmt.lastOps.size() : 1;
                int endLine = mlogLine + lineCount - 1;
                setAddrText(elem, lineCount > 1 ? (mlogLine + "->" + endLine) : (mlogLine + ""));
                mlogLine += lineCount;
            }else{
                setAddrText(elem, mlogLine + "");
                mlogLine++;
            }
        }
    }

    private static void setAddrText(LCanvas.StatementElem elem, String text){
        try{
            arc.scene.ui.Label label = (arc.scene.ui.Label)addressLabelField.get(elem);
            if(label != null) label.setText(text);
        }catch(Exception ignored){
        }
    }
}
