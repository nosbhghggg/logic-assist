package logicassist;

import arc.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.actions.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.graphics.*;
import mindustry.logic.*;
import mindustry.logic.LCanvas.*;
import mindustry.logic.LStatements.*;
import mindustry.ui.*;

import java.lang.reflect.*;

/**
 * JUMP 跳转按钮注入：对齐 MindustryX 的 0046-UI-ARC-logic-Support.patch。
 * JumpStatement 是原版类无法修改 build，通过 act() 定期检查注入。
 * 按钮插入到内容表 JumpButton 之前，与 X 端位置一致。
 *
 * 致谢：MindustryX (https://github.com/TinyLake/MindustryX/,
 * patches/client/0046-UI-ARC-logic-Support.patch, author: way-zer)
 * License: GPL-3.0-or-later
 */
public class JumpButtonHook{

    private static final String MARKER = "la_jump";
    private static Field destField;
    private static Method saveUIMethod;
    private static TextButton.TextButtonStyle jumpStyle;
    private static Class<?> jumpButtonClass;
    private static Boolean isMindustryX;
    private static int frameCounter = 0;

    static{
        try{
            destField = JumpStatement.class.getDeclaredField("dest");
            destField.setAccessible(true);
        }catch(Exception e){
            Log.err("[LogicAssist] JumpStatement.dest access failed", e);
        }
        try{
            saveUIMethod = JumpStatement.class.getDeclaredMethod("saveUI");
            saveUIMethod.setAccessible(true);
        }catch(Exception e){
            Log.err("[LogicAssist] JumpStatement.saveUI access failed", e);
        }
        // Styles.grayt 为 X 端样式，原版不存在时回退 defaultt
        try{
            Field f = Styles.class.getDeclaredField("grayt");
            jumpStyle = (TextButton.TextButtonStyle)f.get(null);
        }catch(Exception e){
            jumpStyle = Styles.defaultt;
        }
        try{
            jumpButtonClass = Class.forName("mindustry.logic.LCanvas$JumpButton");
        }catch(ClassNotFoundException e){
            jumpButtonClass = null;
        }
    }

    static boolean isMindustryX(){
        if(isMindustryX == null){
            try{
                Class.forName("mindustryX.Hooks");
                isMindustryX = true;
            }catch(ClassNotFoundException e){
                isMindustryX = false;
            }
        }
        return isMindustryX;
    }

    public static void inject(LCanvas canvas){
        if(canvas == null || canvas.statements == null || destField == null) return;
        if(frameCounter++ % 20 != 0) return;

        // 紧凑模式用行号跳转，不注入积木内 JUMP 按钮
        if(LCanvas.useRows()) return;

        // X 端桌面端已自带 JUMP 按钮，跳过
        if(isMindustryX() && (!Vars.mobile || !Core.graphics.isPortrait())) return;

        Seq<Element> children = canvas.statements.getChildren();
        for(Element child : children){
            if(!(child instanceof StatementElem)) continue;
            StatementElem elem = (StatementElem)child;
            if(!(elem.st instanceof JumpStatement)) continue;
            injectJumpButton(elem, (JumpStatement)elem.st);
        }
    }

    private static void injectJumpButton(StatementElem elem, JumpStatement jump){
        for(Element child : elem.getChildren()){
            if(!(child instanceof Table)) continue;
            Table contentTable = (Table)child;
            int jumpButtonIndex = findJumpButtonIndex(contentTable);
            if(jumpButtonIndex < 0) continue;

            if(hasJumpButton(contentTable)) return;

            TextButton btn = new TextButton("JUMP", jumpStyle);
            btn.clicked(() -> doJump(jump));
            btn.name = MARKER;

            // 添加到末尾后移动到 JumpButton 之前
            Cell<TextButton> cell = contentTable.add(btn).width(80f);
            Seq<Cell> cells = contentTable.getCells();
            cells.pop();
            cells.insert(jumpButtonIndex, cell);
            return;
        }
    }

    private static int findJumpButtonIndex(Table table){
        if(jumpButtonClass == null) return -1;
        Seq<Cell> cells = table.getCells();
        for(int i = 0; i < cells.size; i++){
            Element e = cells.get(i).get();
            if(jumpButtonClass.isInstance(e)) return i;
        }
        return -1;
    }

    private static boolean hasJumpButton(Table table){
        for(Cell cell : table.getCells()){
            Element e = cell.get();
            if(!(e instanceof Button)) continue;
            if(MARKER.equals(e.name)) return true;
            if(e instanceof TextButton && ((TextButton)e).getText().toString().equalsIgnoreCase("JUMP"))
                return true;
        }
        return false;
    }

    public static void doJump(JumpStatement jump){
        try{
            StatementElem dest = (StatementElem)destField.get(jump);
            if(dest == null) return;

            // dest.parent=DragLayout(.statements), .parent=ScrollPane 内容表(t)；canvas.parent=pane(ScrollPane)
            Element canvas = dest.parent.parent;
            if(!(canvas.parent instanceof ScrollPane)) return;
            ScrollPane scroll = (ScrollPane)canvas.parent;

            // 复位 fling/pan：JUMP 按钮 touchDown 被按钮消费，FlickScrollListener 不会自动复位
            // flingTimer/panning，否则 setScrollY() 落入 act() 的 else 分支瞬间跳转而无过渡
            scroll.cancel();
            scroll.fling(0, 0, 0);
            scroll.setVelocityY(0);

            // visualAmountY 在 ScrollPane.act() 中平滑逼近 amountY，产生过渡动画
            float targetY = Mathf.clamp(scroll.getMaxY() - dest.y + scroll.getHeight() * 0.5f, 0, scroll.getMaxY());
            scroll.setScrollY(targetY);

            if(saveUIMethod != null) saveUIMethod.invoke(jump);

            Element header = dest.getChildren().first();
            dest.clearActions();
            header.clearActions();
            dest.addAction(Actions.repeat(2, Actions.sequence(
                Actions.color(Pal.placing, 0.5f, Interp.smooth),
                Actions.color(dest.st.category().color, 0.5f, Interp.smooth)
            )));
            header.addAction(Actions.repeat(2, Actions.sequence(
                Actions.color(Pal.placing, 0.5f, Interp.smooth),
                Actions.color(dest.st.category().color, 0.5f, Interp.smooth)
            )));
        }catch(Exception e){
            Log.err("[LogicAssist] Jump action failed", e);
        }
    }
}
