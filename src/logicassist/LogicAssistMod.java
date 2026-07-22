package logicassist;

import arc.*;
import arc.scene.ui.layout.*;
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
 * 夺舍架构：用 LogicCanvas 替换原版 LCanvas，实现零延迟表达式折叠。
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

            // 夺舍：用 LogicCanvas 替换原版 LCanvas
            replaceCanvas();

            JumpLineColor.setupSettings();
            JumpLineColor.startLoop();
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
                Log.info("[LogicAssist] Canvas replaced with LogicCanvas.");
            }else{
                Log.err("[LogicAssist] Failed to find canvas cell!");
            }
        }catch(Throwable t){
            Log.err("[LogicAssist] Canvas replacement error:", t);
        }
    }
}
