package logicassist;

import arc.*;
import arc.graphics.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.actions.Actions;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.core.GameState;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.logic.*;
import mindustry.logic.LExecutor.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.lang.reflect.*;

/**
 * 变量显示面板增强 Hook。
 *
 * <p>原版「变量」面板的两个溢出问题：
 * 1. 变量名 Label 无宽度限制且不换行，长变量名把整行撑出屏幕；
 * 2. 值 Label 固定宽 140 且整行锁死 45px 高，长内容换行后被裁掉。
 *
 * <p>修复方式：替换「变量」按钮的点击行为，打开模组自建面板——
 * 变量名列限定最大宽度并自动换行，值列加宽并自动换行，
 * 行高不再锁死，随内容自动撑开；其余布局（色条、类型列、数值刷新闪烁）与原版一致。
 */
public class VariablePanelHook{

    private static boolean installed = false;

    private static Field executorField;
    private static Field globalsDialogField;
    private static Field listenersField;

    static{
        try{
            executorField = LogicDialog.class.getDeclaredField("executor");
            executorField.setAccessible(true);
        }catch(Exception e){
            Log.err("[LogicAssist] Failed to access LogicDialog.executor", e);
        }
        try{
            globalsDialogField = LogicDialog.class.getDeclaredField("globalsDialog");
            globalsDialogField.setAccessible(true);
        }catch(Exception e){
            Log.err("[LogicAssist] Failed to access LogicDialog.globalsDialog", e);
        }
        try{
            listenersField = Element.class.getDeclaredField("listeners");
            listenersField.setAccessible(true);
        }catch(Exception e){
            Log.err("[LogicAssist] Failed to access Element.listeners", e);
        }
    }

    // Element.listeners 为 private：反射取出监听器 Seq，移除原版 clicked() 添加的 ClickListener
    @SuppressWarnings("unchecked")
    private static void removeClickListeners(Element btn){
        if(listenersField == null) return;
        try{
            Seq<EventListener> listeners = (Seq<EventListener>)listenersField.get(btn);
            for(int i = listeners.size - 1; i >= 0; i--){
                if(listeners.get(i) instanceof ClickListener){
                    listeners.remove(i);
                }
            }
        }catch(Throwable t){
            Log.err("[LogicAssist] Failed to remove vanilla click listener", t);
        }
    }

    /**
     * 把逻辑界面上的「变量」按钮点击行为替换为模组面板。
     *
     * <p>注意：按钮在 LogicDialog.setup() 中创建，而 setup() 每次打开逻辑界面
     * 都会重新执行、按钮随之重建——一次性补丁会随旧按钮一起失效。
     * 因此挂到对话框 shown 事件（注册晚于原版 setup 回调，执行在其后），
     * 每次按钮重建后重新应用补丁。
     */
    public static void install(){
        if(installed) return;
        try{
            LogicDialog dialog = Vars.ui.logic;
            if(dialog == null || dialog.buttons == null) return;

            dialog.shown(() -> Core.app.post(VariablePanelHook::patchButton));
            patchButton();

            installed = true;
            Log.info("[LogicAssist] Variable panel button patched.");
        }catch(Throwable t){
            Log.err("[LogicAssist] Failed to patch variable panel", t);
        }
    }

    private static void patchButton(){
        try{
            LogicDialog dialog = Vars.ui.logic;
            if(dialog == null || dialog.buttons == null) return;

            Element btn = dialog.buttons.find("variables");
            if(!(btn instanceof TextButton)){
                Log.warn("[LogicAssist] Variables button not found, panel patch skipped.");
                return;
            }

            // 移除原版 clicked() 添加的 ClickListener（HandCursorListener 不受影响），
            // 同时也移除上次补丁加的监听器，避免叠加
            removeClickListeners(btn);
            ((TextButton)btn).clicked(VariablePanelHook::show);
        }catch(Throwable t){
            Log.err("[LogicAssist] Failed to patch variable panel button", t);
        }
    }

    public static void show(){
        LogicDialog dialog = Vars.ui.logic;
        if(dialog == null) return;

        // 与原版行为一致：非编辑器/无变量时显示全局变量列表
        if(!dialog.shouldShowVariables()){
            showGlobals(dialog);
            return;
        }

        LExecutor executor = getExecutor(dialog);
        if(executor == null || executor.vars.length == 0) return;

        boolean wasPaused = Vars.state.isPaused();
        BaseDialog d = new BaseDialog("@variables");
        d.hidden(() -> {
            if(!wasPaused && !Vars.net.active() && !Vars.state.isMenu()){
                Vars.state.set(GameState.State.paused);
            }
        });
        d.shown(() -> {
            if(!wasPaused && !Vars.net.active() && !Vars.state.isMenu()){
                Vars.state.set(GameState.State.playing);
            }
        });

        d.cont.pane(p -> buildPanel(p, executor));

        d.addCloseButton();
        d.buttons.button("@logic.globals", Icon.list, () -> showGlobals(dialog)).size(210f, 64f);

        d.show();
    }

    private static void showGlobals(LogicDialog dialog){
        try{
            if(globalsDialogField != null){
                BaseDialog globals = (BaseDialog)globalsDialogField.get(dialog);
                if(globals != null){
                    globals.show();
                    return;
                }
            }
        }catch(Throwable t){
            Log.err("[LogicAssist] Failed to show globals dialog", t);
        }
    }

    private static LExecutor getExecutor(LogicDialog dialog){
        try{
            return executorField != null ? (LExecutor)executorField.get(dialog) : null;
        }catch(Throwable t){
            Log.err("[LogicAssist] Failed to read LogicDialog.executor", t);
            return null;
        }
    }

    // 复制到剪贴板，并在点击位置下方浮出"已复制"提示（上浮 + 渐隐）
    private static void copyWithFeedback(String text, float stageX, float stageY){
        Core.app.setClipboardText(text);
        showCopyBubble(stageX, stageY);
    }

    // 在场景坐标处显示临时提示：阴影字体（Fonts.outline），向上漂移后淡出，不阻挡点击
    private static void showCopyBubble(float x, float y){
        Label label = new Label(Core.bundle.get("la.var.copied"), Styles.outlineLabel);
        label.touchable = Touchable.disabled;
        label.pack();
        // 居中于点击位置、悬在其下方；夹取在屏幕范围内
        float bx = Mathf.clamp(x - label.getWidth() / 2f, 4f, Core.graphics.getWidth() - label.getWidth() - 4f);
        float by = Math.max(4f, y - label.getHeight() - 6f);
        label.setPosition(bx, by);
        label.setColor(Color.lightGray);
        Core.scene.add(label);
        label.actions(
            Actions.parallel(
                Actions.moveBy(0f, 12f, 0.9f, Interp.fade),
                Actions.sequence(
                    Actions.delay(0.5f),
                    Actions.fadeOut(0.4f),
                    Actions.remove()
                )
            )
        );
    }

    // 面板主体：布局复刻原版（左色条 | 变量名 | 色条 | 值 | 色条 | 类型），
    // 差异点：变量名/值自动换行、行高随内容撑开、列宽随屏幕宽度自适应。
    // 窄屏（手机竖屏）单行放不下时自动切换两行布局：
    // 第一行 = 变量名 + 类型，第二行 = 值占满整行，避免横向滚动挤出类型列
    private static void buildPanel(Table p, LExecutor executor){
        p.margin(10f).marginRight(16f);
        p.table(Tex.button, t -> {
            // 行高仅作最小值：单行时保持原版观感，换行后自动撑开
            t.defaults().fillX().minHeight(45f);

            float screenW = Core.graphics.getWidth();
            float stub = 8f, mul = 0.5f, pad = 4;

            // 单行布局列宽上限：随屏幕宽度收缩
            float nameCol = Math.min(Scl.scl(340f), screenW * 0.28f);
            float valCol = Math.min(Scl.scl(560f), screenW * 0.45f);
            // 估算单行总宽（类型列按 minWidth 估，高 UI 缩放下实际更宽），
            // 超出可视宽度则用两行布局
            float singleRowMin = Scl.scl(50f) + nameCol + valCol + Scl.scl(120f);
            boolean narrow = singleRowMin > screenW - Scl.scl(24f);

            // 窄屏两行布局的列宽：第一行 = 名(55%) + 类型自适应；第二行 = 值占满
            float narrowRowW = Math.min(screenW, Scl.scl(720f)) - Scl.scl(60f);
            float narrowNameCol = narrowRowW * 0.55f;
            float narrowValCol = narrowRowW - Scl.scl(24f);

            for(var s : executor.vars){
                if(s.constant) continue;

                Color varColor = Pal.gray;

                // 色条（变量名左侧）
                t.add(new Image(Tex.whiteui, varColor.cpy().mul(mul))).width(stub).fillY();

                // 变量名：限定宽度 + 自动换行；点击复制变量名
                Label nameLabel = new Label(" " + s.name + " ", Styles.outlineLabel);
                nameLabel.setColor(Pal.accent);
                nameLabel.setWrap(true);
                nameLabel.addListener(new ClickListener(){
                    @Override
                    public void clicked(InputEvent event, float x, float y){
                        copyWithFeedback(s.name, event.stageX, event.stageY);
                    }
                });
                t.stack(new Image(Tex.whiteui, varColor), nameLabel)
                    .width(narrow ? narrowNameCol : nameCol).fillY().padRight(pad);

                if(narrow){
                    // 第一行右侧直接放类型（类型列无固定下限，自适应文本宽度）
                    addTypeCell(t, s, mul);
                    t.row();

                    // 第二行：值占满整行
                    t.add(new Image(Tex.whiteui, Pal.gray.cpy().mul(mul))).width(stub).fillY();
                    addValueCell(t, s, narrowValCol).colspan(3).growX().padRight(pad);
                    t.row();

                    t.add().growX().colspan(4).height(4).row();
                }else{
                    t.add(new Image(Tex.whiteui, Pal.gray.cpy().mul(mul))).width(stub).fillY();

                    addValueCell(t, s, valCol).fillY().padRight(pad);

                    addTypeCell(t, s, mul);

                    t.row();

                    t.add().growX().colspan(6).height(4).row();
                }
            }
        });
    }

    // 值单元格：Pane 背景 + 自动换行 + 周期刷新 + 点击复制
    private static Cell<?> addValueCell(Table t, LVar s, float valCol){
        return t.table(Tex.pane, out -> {
            float period = 15f;
            float[] counter = {-1f};
            Label label = out.add("").style(Styles.outlineLabel)
                .padLeft(4).padRight(4).width(valCol).wrap().get();
            label.update(() -> {
                if(counter[0] < 0 || (counter[0] += Time.delta) >= period){
                    String text = s.isobj ? PrintI.toString(s.objval)
                        : Math.abs(s.numval - Math.round(s.numval)) < 0.00001
                        ? Math.round(s.numval) + "" : s.numval + "";
                    if(!label.textEquals(text)){
                        label.setText(text);
                        if(counter[0] >= 0f){
                            label.actions(Actions.color(Pal.accent), Actions.color(Color.white, 0.2f));
                        }
                    }
                    counter[0] = 0f;
                }
            });
            label.act(1f);
            // 点击复制当前显示的值（刷新周期内文本即最新值）
            label.addListener(new ClickListener(){
                @Override
                public void clicked(InputEvent event, float x, float y){
                    copyWithFeedback(label.getText().toString(), event.stageX, event.stageY);
                }
            });
        });
    }

    // 类型单元格：色条 + 类型文本（宽度自适应，不设固定下限）
    private static void addTypeCell(Table t, LVar s, float mul){
        t.add(new Image(Tex.whiteui, LogicDialog.typeColor(s, new Color()).mul(mul)))
            .update(i -> i.setColor(LogicDialog.typeColor(s, i.color).mul(mul)))
            .width(8f).fillY();

        t.stack(new Image(Tex.whiteui, LogicDialog.typeColor(s, new Color())){{
            update(() -> setColor(LogicDialog.typeColor(s, color)));
        }}, new Label(() -> " " + LogicDialog.typeName(s) + " "){{
            setStyle(Styles.outlineLabel);
        }}).fillY();
    }
}
