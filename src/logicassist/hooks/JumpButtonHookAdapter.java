package logicassist.hooks;

import logicassist.*;
import mindustry.logic.LCanvas;

/**
 * JumpButtonHook 的 CanvasHook adapter。
 *
 * 在 afterAct 阶段定期检查 JumpStatement 内容表，注入 JUMP 按钮。
 * 跳转本身在 doJump() 内通过 scroll.setScrollY() + fling 复位完成，
 * 依赖 ScrollPane 内置平滑滚动产生过渡动画，无需在此额外驱动。
 * JumpButtonHook 内部已有 frameCounter 节流（每 20 帧检查一次）。
 */
public class JumpButtonHookAdapter implements CanvasHook{

    @Override
    public void afterAct(LCanvas canvas, float delta){
        JumpButtonHook.inject(canvas);
    }
}
