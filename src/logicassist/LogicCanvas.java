package logicassist;

import arc.util.*;
import logicassist.expr.*;
import mindustry.logic.*;

/**
 * 夺舍版 LCanvas：继承原版 LCanvas，覆盖关键生命周期方法。
 *
 * 核心改进：
 * - load() 完成后立即折叠 op 链为零延迟（原方案等 5 帧）
 * - save() 保存前先展开所有 ExprStatement，保存纯 mlog 后重新折叠
 * - act() 每帧在原版 layout 之后重新设置 addressLabel，显示 mlog 行号区间
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
    public void act(float delta){
        super.act(delta);
        // 原版 layout() 中 updateAddress 会覆盖我们的行号
        // 在 act 后立即重新设置，确保 mlog 行号区间持续显示
        ExprHook.updateAddressLabels(this);
    }
}
