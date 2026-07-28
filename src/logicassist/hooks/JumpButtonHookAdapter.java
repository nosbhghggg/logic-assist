package logicassist.hooks;

import logicassist.*;
import mindustry.logic.LCanvas;

/**
 * JumpButtonHook 的 CanvasHook adapter。
 *
 * 在 afterAct 阶段定期检查 JumpStatement 内容表，注入 JUMP 按钮。
 * JumpButtonHook 内部已有 frameCounter 节流（每 20 帧检查一次）。
 */
public class JumpButtonHookAdapter implements CanvasHook{

    @Override
    public void afterAct(LCanvas canvas, float delta){
        JumpButtonHook.inject(canvas);
    }
}
