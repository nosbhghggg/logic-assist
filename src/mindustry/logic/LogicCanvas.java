package mindustry.logic;

import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import logicassist.*;
import logicassist.expr.*;

/**
 * 夺舍版 LCanvas：继承原版 LCanvas，覆盖关键生命周期方法。
 * 放在 mindustry.logic 包下以直接访问包级字段（dragging, privileged, addressLabel 等）。
 *
 * 核心改进：
 * - 使用 LogicDragLayout 替代原版 DragLayout，在 layout() 中直接设置 mlog 行号
 *   （不再在 draw() 中每帧反射覆盖 addressLabel）
 * - load() 完成后立即折叠 op 链为零延迟
 * - save() 保存前先展开所有 ExprStatement，保存纯 mlog 后重新折叠
 * - draw() 内联 JumpLineColor.patchAllCurves，消除独立循环
 */
public class LogicCanvas extends LCanvas{

    public LogicCanvas(){
        super();
    }

    /**
     * 自定义 DragLayout：在原版布局完成后，直接修正 addressLabel 为 mlog 行号。
     *
     * 原版 layout() 第 230 行调用 updateAddress(i) 设置 UI 索引行号。
     * 这里在 super.layout() 之后覆盖为 mlog 行号（考虑 ExprStatement 展开后的真实行号）。
     * 因为 LogicCanvas 在 mindustry.logic 包下，可直接访问 StatementElem.addressLabel，无需反射。
     */
    public class LogicDragLayout extends DragLayout{

        @Override
        public void layout(){
            super.layout();
            updateMlogAddresses();
        }

        /** 遍历所有积木，设置 addressLabel 为展开后的 mlog 行号 */
        private void updateMlogAddresses(){
            Seq<Element> children = getChildren();
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
    }

    @Override
    public void rebuild(){
        targetWidth = useRows() ? 400f : 900f;
        float s = pane != null ? pane.getVisualScrollY() : 0f;
        String toLoad = statements != null ? save() : null;

        clear();

        statements = new LogicDragLayout();

        pane = pane(t -> {
            t.center();
            t.add(statements).pad(2f).center().width(targetWidth);
            t.addChild(statements.jumps);

            statements.jumps.touchable = Touchable.disabled;
            statements.jumps.update(() -> statements.jumps.setCullingArea(t.getCullingArea()));
            statements.jumps.cullable = false;
        }).grow().get();
        pane.setFlickScroll(false);
        pane.setScrollYForce(s);

        if(toLoad != null){
            load(toLoad);
        }
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
        // 内联 JumpLineColor：patch 新增的 JumpCurve
        // 行号已由 LogicDragLayout.layout() 设置，无需在此覆盖
        JumpLineColor.patchAllCurves(this);
    }

    // ===== 字段访问器：供 BoxSelect 跨包访问 =====

    public boolean isPrivilegedCanvas(){
        return privileged;
    }

    public void clearDraggingField(){
        dragging = null;
    }
}
