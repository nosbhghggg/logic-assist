package logicassist.hooks;

import arc.graphics.*;
import arc.input.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import logicassist.*;
import logicassist.expr.*;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.logic.LStatements.*;

import java.lang.reflect.*;

/**
 * mlog 行号标签更新 Hook。
 *
 * 在 afterAct 阶段更新每个 StatementElem 的行号标签，反映 mlog 实际行号。
 * ExprStatement 通过 getMlogLineCount() 自报占用的行数，其他积木固定 1 行。
 *
 * 同时更新 JumpStatement 标题中的目标行号（原版显示 block 索引 dest.index，
 * 此处覆盖为 mlog 行号）。原版 update 回调在 super.act() 中已执行，
 * afterAct 在其后运行，覆盖为正确值。
 *
 * 行号更新策略：强制 invalidate+validate 完成 layout 触发原版 updateAddress 设置原版文本，
 * 然后修改 label 为 mlog 行号，最后反射重置 needsLayout=false 防止 draw() 中
 * validate() 重新 layout 覆盖文本。
 *
 * 必须在 ExprHookAdapter 之后执行（lastOps 已更新），
 * 在 JumpButtonHookAdapter 之前（无依赖但顺序稳定）。
 */
public class MlogAddressHook implements CanvasHook{

    private static final String INTERACTIVE_MARKER = "la-interactive-jump-line";

    private static Field addressLabelField;
    private static Field needsLayoutField;

    static{
        try{
            addressLabelField = LCanvas.StatementElem.class.getDeclaredField("addressLabel");
            addressLabelField.setAccessible(true);
        }catch(Exception e){
            Log.err("[LogicAssist] Failed to access StatementElem.addressLabel", e);
        }
        try{
            needsLayoutField = WidgetGroup.class.getDeclaredField("needsLayout");
            needsLayoutField.setAccessible(true);
        }catch(Exception e){
            Log.err("[LogicAssist] Failed to access WidgetGroup.needsLayout", e);
        }
    }

    @Override
    public void afterAct(LCanvas canvas, float delta){
        if(canvas.statements == null || addressLabelField == null) return;
        Seq<Element> children = canvas.statements.getChildren();
        if(children.isEmpty()) return;

        canvas.statements.invalidate();
        canvas.statements.validate();

        boolean changed = false;
        int mlogLine = 0;
        // block 索引 → mlog 起始行号映射，供 JumpStatement 标题使用
        int[] blockToMlog = new int[children.size];

        for(int idx = 0; idx < children.size; idx++){
            Element child = children.get(idx);
            if(!(child instanceof LCanvas.StatementElem)) continue;
            LCanvas.StatementElem elem = (LCanvas.StatementElem)child;

            blockToMlog[idx] = mlogLine;

            int lineCount;
            if(elem.st instanceof ExprStatement){
                lineCount = ((ExprStatement)elem.st).getMlogLineCount();
            }else{
                lineCount = 1;
            }

            String text = lineCount > 1
                ? (mlogLine + "->" + (mlogLine + lineCount - 1))
                : (mlogLine + "");

            try{
                Label label = (Label)addressLabelField.get(elem);
                if(label != null){
                    String current = label.getText().toString();
                    if(!current.equals(text)){
                        label.setText(text);
                        changed = true;
                    }
                }
            }catch(Exception ignored){}

            mlogLine += lineCount;
        }

        // 覆盖 JumpStatement 标题中的目标行号：
        // 原版 update 回调显示 dest.index（block 索引），改为 mlog 行号
        for(Element child : children){
            if(!(child instanceof LCanvas.StatementElem)) continue;
            LCanvas.StatementElem elem = (LCanvas.StatementElem)child;
            if(!(elem.st instanceof JumpStatement)) continue;
            JumpStatement jump = (JumpStatement)elem.st;
            if(jump.dest == null) continue;

            int destBlockIdx = children.indexOf(jump.dest);
            if(destBlockIdx < 0 || destBlockIdx >= blockToMlog.length) continue;

            Label title = (Label)elem.find("statement-name");
            if(title != null){
                String expected = jump.name() + " -> " + blockToMlog[destBlockIdx];
                if(!title.getText().toString().equals(expected)){
                    title.setText(expected);
                }

                // 紧凑模式（useRows=true）启用行号跳转，细长条模式用积木内 JUMP 按钮
                if(!LCanvas.useRows()) continue;

                if(!INTERACTIVE_MARKER.equals(title.userObject)){
                    title.userObject = INTERACTIVE_MARKER;
                    title.touchable = Touchable.enabled;

                    // 半透明黑色背景 + 上下 padding 增加按钮高度
                    Label.LabelStyle newStyle = new Label.LabelStyle(title.getStyle());
                    BaseDrawable bg = (BaseDrawable)((TextureRegionDrawable)Tex.whiteui).tint(0, 0, 0, 0.4f);
                    bg.setTopHeight(Scl.scl(4f));
                    bg.setBottomHeight(Scl.scl(4f));
                    newStyle.background = bg;
                    title.setStyle(newStyle);

                    final JumpStatement capturedJump = jump;
                    title.addListener(new ClickListener(){
                        @Override
                        public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                            boolean result = super.touchDown(event, x, y, pointer, button);
                            if(result) event.stop();
                            return result;
                        }

                        @Override
                        public void clicked(InputEvent event, float x, float y){
                            JumpButtonHook.doJump(capturedJump);
                        }
                    });
                }
            }
        }

        // setText() 触发了 invalidateHierarchy() → statements.needsLayout = true
        // 重置为 false，防止 draw() 中 validate() → layout() → updateAddress() 覆盖
        if(changed && needsLayoutField != null){
            try{
                needsLayoutField.setBoolean(canvas.statements, false);
            }catch(Exception ignored){}
        }
    }
}
