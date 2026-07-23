package mindustry.logic;

import arc.graphics.g2d.*;
import arc.math.*;
import arc.util.*;
import arc.scene.ui.*;
import logicassist.*;
import logicassist.expr.*;

import java.lang.reflect.*;

/**
 * 夺舍版 LCanvas：继承原版 LCanvas，覆盖关键生命周期方法。
 *
 * 核心改进：
 * - load() 完成后立即折叠 op 链为零延迟
 * - save() 保存前先展开，保存后重新折叠
 * - draw() 中 super.draw() 后，重画每个 addressLabel 覆盖原版行号
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
    public void draw(){
        // 原版流程：validate() → layout() → updateAddress(i) → drawChildren
        super.draw();

        // 此时所有 label 已经用原版行号画完
        // 修改文本后重画 label 覆盖原版画面
        ExprHook.redrawAddressLabels(this);

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
