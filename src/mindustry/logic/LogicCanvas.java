package mindustry.logic;

import arc.graphics.g2d.*;
import arc.util.*;
import logicassist.*;
import logicassist.expr.*;

/**
 * 夺舍版 LCanvas：继承原版 LCanvas，覆盖关键生命周期方法。
 * 放在 mindustry.logic 包下以直接访问包级字段（dragging, privileged 等）。
 *
 * 核心改进：
 * - load() 完成后立即折叠 op 链为零延迟（原方案等 5 帧）
 * - save() 保存前先展开所有 ExprStatement，保存纯 mlog 后重新折叠
 * - draw() 在原版绘制完成后重新设置 addressLabel，显示 mlog 行号区间
 *   然后清除 invalidated，阻止下一帧 layout() 的 updateAddress 覆盖
 * - 内联 JumpLineColor.patchAllCurves，消除独立循环
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
        // layout() 在 super.draw()→validate() 中执行，updateAddress 覆盖了我们的行号
        // draw 之后立即重新设置 mlog 行号区间
        ExprHook.updateAddressLabels(this);
        // 内联 JumpLineColor：patch 新增的 JumpCurve
        JumpLineColor.patchAllCurves(this);
        // 关键：updateAddressLabels 设置文本后 Label 宽度变化触发 invalidate
        // 清除 invalidated 阻止下一帧 layout() → updateAddress 覆盖我们的行号
        // 如果确实需要 layout（拖拽/添加积木），那些操作会重新 invalidate
        statements.invalidated = false;
    }

    // ===== 字段访问器：供 BoxSelect 跨包访问 =====

    public boolean isPrivilegedCanvas(){
        return privileged;
    }

    public void clearDraggingField(){
        dragging = null;
    }
}
