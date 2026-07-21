package logicassist;

import arc.*;
import arc.util.*;
import mindustry.game.EventType.*;
import mindustry.mod.*;

/**
 * Logic Assist 模组主类。
 *
 * 逻辑编辑器增强模组，提供跳转线着色等功能。
 * 继承 {@link Mod}，在 init() 中注册事件监听和设置项。
 *
 * 原 jump-line-color JS 脚本模组的 Java 重写版本。
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
        // ClientLoadEvent 在游戏完全加载后触发，此时 Vars.ui 等已初始化
        Events.on(ClientLoadEvent.class, e -> {
            Log.info("[LogicAssist] Client loaded, initializing features...");

            JumpLineColor.setupSettings();
            JumpLineColor.startLoop();
            BoxSelect.init();

            Log.info("[LogicAssist] Initialization complete.");
        });
    }
}
