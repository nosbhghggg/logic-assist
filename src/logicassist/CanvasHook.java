package logicassist;

import arc.struct.Seq;
import mindustry.logic.LCanvas;

/**
 * LogicCanvas 生命周期扩展点接口。
 *
 * LogicCanvas 在 act/draw 中按 hooks 注册顺序依次调用各阶段方法。
 * 跨阶段共享状态的 Hook（如 BoxSelect 需在 beforeAct 保存 scrollY、afterDraw 绘制高亮）
 * 由同一个 Hook 类实现多个方法，状态保留在 Hook 实例或其委托的 static 状态中。
 *
 * 注册顺序即执行顺序。新增 Hook 时需人工检查与现有 Hook 的依赖关系。
 * 详见 docs/adr/0002-canvas-hook-mechanism.md。
 */
public interface CanvasHook{

    /** super.act() 之前调用。用于保存原版行为可能覆盖的状态。 */
    default void beforeAct(LCanvas canvas, float delta){}

    /** super.act() 之后调用。用于注入功能逻辑（更新标志、行号、注入按钮等）。 */
    default void afterAct(LCanvas canvas, float delta){}

    /** super.draw() 之前调用。用于绘制需要在积木下方的元素（如插入指示器）。 */
    default void beforeDraw(LCanvas canvas){}

    /** super.draw() 之后调用。用于绘制覆盖在积木上方的元素（如高亮、彩色滚动条）。 */
    default void afterDraw(LCanvas canvas){}

    /** LogicCanvas 构造时调用，传入 hooks 列表供顺序注册。 */
    default void register(Seq<CanvasHook> hooks){
        hooks.add(this);
    }
}
