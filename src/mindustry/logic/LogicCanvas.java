package mindustry.logic;

import arc.graphics.g2d.*;
import arc.util.*;
import arc.scene.ui.Label;
import logicassist.*;
import logicassist.expr.*;

import java.lang.reflect.*;

/**
 * 夺舍版 LCanvas：继承原版 LCanvas，覆盖关键生命周期方法。
 *
 * 注意：虽然放在 mindustry.logic 包下，但模组类加载器与游戏类加载器不同，
 * 包级字段（dragging, privileged）仍需通过反射访问。
 *
 * 核心改进：
 * - load() 完成后立即折叠 op 链为零延迟
 * - save() 保存前先展开，保存后重新折叠
 * - act() 中先 validate() 强制 layout() 提前执行（updateAddress 设置原版行号），
 *   然后立即 updateAddressLabels 覆盖为 mlog 行号。
 *   这样 draw() 时 needsLayout=false，layout() 不再执行，label 用我们的文本绘制。
 */
public class LogicCanvas extends LCanvas{

    private static Field draggingField;
    private static Field privilegedField;

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
        // 关键：在 act 阶段强制 validate，让 layout() → updateAddress() 提前执行
        // 然后立即覆盖为 mlog 行号。
        // 这样 draw() 时 needsLayout=false，layout() 不再覆盖我们的标签。
        statements.validate();
        ExprHook.updateAddressLabels(this);
    }

    @Override
    public void draw(){
        super.draw();
        // 保险：draw 后再设置一次（万一 act 和 draw 之间有 invalidate）
        ExprHook.updateAddressLabels(this);
        JumpLineColor.patchAllCurves(this);
    }

    // ===== 字段访问器：反射访问包级字段（跨类加载器） =====

    public boolean isPrivilegedCanvas(){
        try{
            if(privilegedField == null){
                privilegedField = LCanvas.class.getDeclaredField("privileged");
                privilegedField.setAccessible(true);
            }
            return privilegedField.getBoolean(this);
        }catch(Exception e){
            return false;
        }
    }

    public void clearDraggingField(){
        try{
            if(draggingField == null){
                draggingField = LCanvas.class.getDeclaredField("dragging");
                draggingField.setAccessible(true);
            }
            draggingField.set(this, null);
        }catch(Exception e){
            // ignore
        }
    }
}
