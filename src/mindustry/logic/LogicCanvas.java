package mindustry.logic;

import arc.scene.*;
import arc.struct.*;
import arc.util.*;
import logicassist.*;
import logicassist.expr.*;

/**
 * 夺舍版 LCanvas：继承原版 LCanvas，覆盖关键生命周期方法。
 * 放在 mindustry.logic 包下以直接访问包级字段（dragging, privileged, addressLabel 等）。
 *
 * 核心改进：
 * - 不覆盖 rebuild()，让原版自己创建 DragLayout（避免 159.5/159.6 版本差异导致 statements 未初始化）
 * - load() 完成后立即折叠 op 链为零延迟
 * - save() 保存前先展开所有 ExprStatement，保存纯 mlog 后重新折叠
 * - draw() 中直接更新 addressLabel 为 mlog 行号（同包访问，无需反射）
 * - draw() 内联 JumpLineColor.patchAllCurves，消除独立循环
 */
public class LogicCanvas extends LCanvas{

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
        updateMlogAddresses();
        JumpLineColor.patchAllCurves(this);
    }

    /**
     * 遍历所有积木，将 addressLabel 从 UI 索引更新为 mlog 行号。
     *
     * 原版 DragLayout.layout() 每帧设置 addressLabel 为 UI 索引（0,1,2...）。
     * 这里在 draw() 中覆盖为 mlog 行号，考虑 ExprStatement 展开后的真实行数。
     * 因为 LogicCanvas 在 mindustry.logic 包下，可直接访问 StatementElem.addressLabel。
     */
    private void updateMlogAddresses(){
        if(statements == null) return;
        Seq<Element> children = statements.getChildren();
        int mlogLine = 0;
        for(Element child : children){
            if(!(child instanceof StatementElem)) continue;
            StatementElem elem = (StatementElem)child;

            if(elem.st instanceof ExprStatement){
                ExprStatement exprStmt = (ExprStatement)elem.st;
                // 确保 lastOps 已编译（从 Add 菜单新添加的 ExprStatement 可能还没编译）
                if(exprStmt.lastOps == null){
                    try{
                        exprStmt.lastOps = ExprCompiler.compile(exprStmt.dest, exprStmt.expr);
                    }catch(Exception ignored){
                        // 编译失败，按单行处理
                    }
                }
                int lineCount = (exprStmt.lastOps != null) ? exprStmt.lastOps.size() : 1;
                int endLine = mlogLine + lineCount - 1;
                elem.addressLabel.setText(lineCount > 1 ? (mlogLine + "->" + endLine) : (mlogLine + ""));
                mlogLine += lineCount;
            }else{
                elem.addressLabel.setText(mlogLine + "");
                mlogLine++;
            }
        }
    }

    // ===== 字段访问器：供 BoxSelect 跨包访问 =====

    public boolean isPrivilegedCanvas(){
        return privileged;
    }

    public void clearDraggingField(){
        dragging = null;
    }
}
