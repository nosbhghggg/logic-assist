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
 * 逻辑编辑器增强：跳转线着色、框选批量操作、表达式编辑、JUMP 跳转。
 * 用 LogicCanvas 替换原版 LCanvas 实现零延迟表达式折叠。
 */
public class LogicAssistMod extends Mod{

    public LogicAssistMod(){
        Log.info("[LogicAssist] Mod constructor loaded.");
    }

    @Override
    public void loadContent(){
        Log.info("[LogicAssist] No content to load (UI-only mod).");
    }

    @Override
    public void init(){
        Events.on(ClientLoadEvent.class, e -> {
            Log.info("[LogicAssist] Client loaded, initializing features...");

            replaceCanvas();

            JumpLineColor.setupSettings();
            UpdateChecker.check();
            // JumpLineColor.patchAllCurves 内联到 LogicCanvas.draw()
            // 缓存清理在 dialog 关闭时触发
            Vars.ui.logic.hidden(() -> JumpLineColor.clearCache());
            BoxSelect.init();
            ExprHook.init();

            Log.info("[LogicAssist] Initialization complete.");
        });
    }

    private void replaceCanvas(){
        try{
            LogicDialog dialog = Vars.ui.logic;
            LCanvas old = dialog.canvas;
            if(old instanceof LogicCanvas){
                Log.debug("[LogicAssist] Canvas already replaced, skipping.");
                return;
            }

            // 记录原 canvas 索引，用于恢复 z-order
            // Cell.setElement 会把新元素加到末尾覆盖 MindustryX 面板，需移回原位
            int canvasIndex = dialog.getChildren().indexOf(old, true);
            if(canvasIndex < 0) canvasIndex = 0;

            LogicCanvas lc = new LogicCanvas();

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

                // 恢复 z-order：直接操作 children Seq 避免 setScene 回调损坏 JumpCurve 状态
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
