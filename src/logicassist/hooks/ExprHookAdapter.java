package logicassist.hooks;

import logicassist.*;
import logicassist.expr.*;
import mindustry.logic.LCanvas;

/**
 * ExprHook 的 CanvasHook adapter。
 *
 * 在 afterAct 阶段检测优化开关状态变化（print "expr-opt:true"），
 * 开关变化时清空 ExprStatement.lastOps 强制下次重新编译。
 *
 * 必须在 MlogAddressHook 之前执行，确保行号计算时 lastOps 已更新。
 */
public class ExprHookAdapter implements CanvasHook{

    @Override
    public void afterAct(LCanvas canvas, float delta){
        ExprHook.updateOptimizationFlag(canvas);
    }
}
