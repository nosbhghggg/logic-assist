package logicassist.hooks;

import arc.graphics.g2d.*;
import arc.math.*;
import logicassist.*;
import mindustry.logic.LCanvas;

/**
 * BoxSelect 功能的 CanvasHook 实现。
 *
 * 职责：
 * - beforeAct：拖动期间保存 scrollY（在 super.act() 修改之前）
 * - afterAct：紧接 super.act() 之后恢复 scrollY，抑制原版边缘自动滚动
 * - beforeDraw：拖动期间在积木下方绘制插入指示器
 * - afterDraw：非选中/非拖动状态下绘制高亮和彩色滚动条
 *
 * 跨阶段状态（savedY/suppress）保留在 Hook 实例字段上。
 * BoxSelect 内部状态机保留 static 不动，本 Hook 只承担生命周期 dispatch。
 */
public class BoxSelectHook implements CanvasHook{

    private boolean suppress = false;
    private float savedY = 0;

    @Override
    public void beforeAct(LCanvas canvas, float delta){
        // 抑制原版 LCanvas.act() 的边缘自动滚动：点击滚动条跳转时鼠标靠近屏幕边缘会触发自动滚动，
        // 导致跳转后位置偏移。保存滚动位置，super.act() 后恢复。
        suppress = BoxSelect.shouldSuppressEdgeScroll();
        savedY = suppress ? canvas.pane.getScrollY() : 0;
    }

    @Override
    public void afterAct(LCanvas canvas, float delta){
        if(suppress){
            canvas.pane.setScrollYForce(savedY);
            suppress = false;
        }
    }

    @Override
    public void beforeDraw(LCanvas canvas){
        if(BoxSelect.isDragging()){
            BoxSelect.drawInsertIndicatorUnder(canvas);
        }
    }

    @Override
    public void afterDraw(LCanvas canvas){
        // 当 overlay 不在前景时（IDLE/SELECTED），在画布绘制周期内绘制高亮和彩色滚动条。
        // 避免 overlay.toFront() 干扰 MindustryX 等第三方 UI 的层级管理。
        if(!BoxSelect.isSelecting() && !BoxSelect.isDragging()){
            Mat oldTrans = new Mat().set(Draw.trans());
            Draw.trans().idt();
            BoxSelect.drawHighlights(canvas);
            if(BoxSelect.isScrollbarEnabled()){
                BoxSelect.drawColorScrollbar(canvas);
            }
            Draw.trans(oldTrans);
        }
    }
}
