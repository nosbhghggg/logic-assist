package logicassist;

import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import logicassist.expr.*;
import mindustry.logic.*;
import mindustry.ui.*;

import java.lang.reflect.*;

/**
 * 夺舍版 LCanvas：继承原版 LCanvas，覆盖关键生命周期方法。
 *
 * 行号更新策略（v3 - 土方法覆盖绘制）：
 * 原版 DragLayout.layout() 通过 updateAddress(i) 将 addressLabel 设为 UI 索引。
 * layout() 在 validate() 中触发，validate() 可能在 act() 或 draw() 期间调用。
 *
 * v1 方案（label.update 回调）：setText() 触发 invalidateHierarchy()，
 * 同一帧 validate() → layout() → updateAddress() 覆盖文本。只有 dragging 时 layout 跳过元素才不覆盖。
 *
 * v2 方案（post-draw setText + 重置 invalidated）：DragLayout.invalidated 不是
 * 控制 validate→layout 的开关（WidgetGroup 内部的 needsLayout 才是），重置无效。
 *
 * v3 方案（土方法）：在 super.draw() 之后，用 stage 坐标系直接绘制覆盖层。
 * 不修改原版 label，不依赖 invalidate/layout 机制。
 * 每帧用背景矩形覆盖原版 addressLabel，然后绘制 mlog 行号。
 */
public class LogicCanvas extends LCanvas{

    private static Field addressLabelField;
    private static final Mat tmpMat = new Mat();
    private static final Mat tmpMat2 = new Mat();
    private static final Vec2 tmpVec1 = new Vec2();
    private static final Vec2 tmpVec2 = new Vec2();

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
    public void draw(){
        super.draw();
        JumpLineColor.patchAllCurves(this);
        drawMlogAddressOverlay();
    }

    /**
     * 土方法：在 super.draw() 之后直接绘制覆盖层。
     * 不修改原版 label，不依赖 invalidate/layout 机制。
     * 每帧用背景矩形覆盖原版 addressLabel，然后绘制 mlog 行号。
     */
    private void drawMlogAddressOverlay(){
        if(statements == null || addressLabelField == null) return;
        Seq<Element> children = statements.getChildren();
        if(children.isEmpty()) return;

        // ScrollPane 可视区域（stage 坐标），用于裁剪检查
        ScrollPane pane = this.pane;
        float paneX, paneY, paneRight, paneTop;
        if(pane != null){
            Vec2 pp = pane.localToStageCoordinates(tmpVec1.set(0, 0));
            paneX = pp.x;
            paneY = pp.y;
            paneRight = paneX + pane.getWidth();
            paneTop = paneY + pane.getHeight();
        }else{
            paneX = paneY = 0;
            paneRight = paneTop = Float.MAX_VALUE;
        }

        // 切换到 stage 坐标系
        Mat oldTrans = tmpMat.set(Draw.trans());
        Draw.trans(tmpMat2.idt());

        Font font = Fonts.outline;
        float oldScaleX = font.getData().scaleX;
        float oldScaleY = font.getData().scaleY;
        Color oldColor = font.getColor().cpy();
        float scale = Scl.scl(1f);

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
                if(label != null && label.getWidth() > 0){
                    String current = label.getText().toString();
                    // 只在原版文本和我们的不同时覆盖
                    if(!current.equals(text)){
                        Vec2 pos = label.localToStageCoordinates(tmpVec2.set(0, 0));
                        float x = pos.x;
                        float y = pos.y;
                        float w = label.getWidth();
                        float h = label.getHeight();

                        // 裁剪检查：label 不在 ScrollPane 可视区域内则跳过
                        if(x + w >= paneX && x <= paneRight && y + h >= paneY && y <= paneTop){
                            // 画背景覆盖原版文本
                            Draw.color(elem.color);
                            Fill.crect(x, y, w, h);

                            // 画我们的行号
                            font.getData().setScale(scale, scale);
                            font.setColor(Color.white);
                            float baselineY = y + (h + font.getCapHeight()) / 2f;
                            font.draw(text, x, baselineY, w, Align.center, false);
                        }
                    }
                }
            }catch(Exception ignored){}

            mlogLine += lineCount;
        }

        // 恢复
        font.getData().setScale(oldScaleX, oldScaleY);
        font.setColor(oldColor);
        Draw.trans(oldTrans);
        Draw.reset();
    }
}
