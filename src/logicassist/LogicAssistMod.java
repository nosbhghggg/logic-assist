package logicassist;

import arc.*;
import arc.scene.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import logicassist.expr.*;
import mindustry.*;
import mindustry.game.EventType.*;
import mindustry.logic.*;
import mindustry.mod.*;

/**
 * Logic Assist 模组主类。
 *
 * 逻辑编辑器增强模组，提供跳转线着色、框选批量操作、复杂表达式编辑等功能。
 * 继承 {@link Mod}，在 init() 中注册事件监听和设置项。
 *
 * 用 LogicCanvas 替换原版 LCanvas，实现零延迟表达式折叠。
 */
public class LogicAssistMod extends Mod{

    public LogicAssistMod(){
        Log.info("[LogicAssist] Mod constructor loaded.");
    }

    @Override
    public void loadContent(){
        // 本模组不添加游戏内容（方块/物品等），仅增强 UI
        Log.info("[LogicAssist] No content to load (UI-only mod).");
    }

    @Override
    public void init(){
        Events.on(ClientLoadEvent.class, e -> {
            Log.info("[LogicAssist] Client loaded, initializing features...");

            // 用 LogicCanvas 替换原版 LCanvas
            replaceCanvas();

            JumpLineColor.setupSettings();
            // JumpLineColor.patchAllCurves 内联到 LogicCanvas.draw()
            // 缓存清理在 dialog 关闭时触发
            Vars.ui.logic.hidden(() -> JumpLineColor.clearCache());
            BoxSelect.init();
            ExprHook.init();

            Log.info("[LogicAssist] Initialization complete.");
        });
    }

    /** 用 LogicCanvas 替换 LogicDialog 中的原版 LCanvas。 */
    private void replaceCanvas(){
        try{
            LogicDialog dialog = Vars.ui.logic;
            LCanvas old = dialog.canvas;
            if(old instanceof LogicCanvas){
                Log.debug("[LogicAssist] Canvas already replaced, skipping.");
                return;
            }

            // 记录原 canvas 在 children 中的索引，用于后续恢复 z-order
            // Cell.setElement 会把新元素添加到 children 末尾（最高 z-order），
            // 导致 canvas 覆盖在 MindustryX LogicSupport 面板之上，使面板按钮不可点击。
            // 替换后需将新 canvas 移回原位置，保持正确的 z-order。
            int canvasIndex = dialog.getChildren().indexOf(old, true);
            if(canvasIndex < 0) canvasIndex = 0;

            LogicCanvas lc = new LogicCanvas();

            // 通过 Cell.setElement 替换 UI 元素（自动移除旧元素、添加新元素到 table）
            boolean replaced = false;
            for(Cell<?> cell : dialog.getCells()){
                if(cell.get() == old){
                    cell.setElement(lc);
                    replaced = true;
                    break;
                }
            }

            if(replaced){
                dialog.canvas = lc;

                // 恢复 z-order：将 canvas 从 children 末尾移回原位置。
                // 直接操作 children Seq，避免 remove()/addChildAt() 触发 setScene 回调
                // （LCanvas.StatementElem.setScene 在 scene=null 时会移除 JumpCurve，导致状态损坏）。
                Seq<Element> children = dialog.getChildren();
                int lastIdx = children.size - 1;
                if(canvasIndex != lastIdx){
                    children.remove(lastIdx);
                    children.insert(canvasIndex, lc);
                }

                Log.info("[LogicAssist] Canvas replaced with LogicCanvas.");
            }else{
                Log.err("[LogicAssist] Failed to find canvas cell!");
            }
        }catch(Throwable t){
            Log.err("[LogicAssist] Canvas replacement error:", t);
        }
    }
}
