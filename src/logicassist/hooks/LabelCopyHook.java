package logicassist.hooks;

import logicassist.*;
import mindustry.logic.LCanvas;

/**
 * LabelCopy 的 CanvasHook adapter。
 *
 * <p>在 afterAct 阶段轮询 LogicDialog 的"编辑"按钮并挂载点击监听，
 * 用户打开导出菜单后向其中注入"标签复制"按钮。
 * 操作对象是 dialog 而非 canvas 内容，与其他 Hook 无顺序依赖。
 */
public class LabelCopyHook implements CanvasHook{

    @Override
    public void afterAct(LCanvas canvas, float delta){
        LabelCopy.inject(canvas);
    }
}
