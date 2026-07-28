package logicassist.hooks;

import logicassist.*;
import mindustry.logic.LCanvas;

/**
 * JumpLineColor 的 CanvasHook adapter。
 *
 * 在 afterDraw 阶段为画布中所有 JumpCurve 注入着色回调。
 * 必须在 BoxSelectHook.afterDraw 之前执行（绘制顺序）。
 */
public class JumpLineColorHook implements CanvasHook{

    @Override
    public void afterDraw(LCanvas canvas){
        JumpLineColor.patchAllCurves(canvas);
    }
}
