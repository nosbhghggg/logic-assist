package logicassist;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.geom.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import arc.util.Align;
import mindustry.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.logic.*;
import mindustry.logic.LCanvas.*;
import mindustry.logic.LStatements.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * 框选功能 - 批量选择、复制、移动、删除积木（事件驱动架构）。
 *
 * 架构：
 * - 输入层：Core.scene.addCaptureListener 从事件源头拦截，event.stop() 阻止原版 StatementElem
 *   的 InputListener 收到事件。不再需要 restoreChildrenOrder 等对抗代码。
 * - 布局层：拖动期间 relayoutNonSelected 跳过选中积木 + applyInsertShiftViaTranslation
 *   用 translation 腾位（不修改 child.y，避免 layout() 重置导致闪烁）。
 *   clearDraggingField 防止原版 layout() 跳过错误积木。
 * - 夺舍按钮：选中积木后劫持其功能按钮（删除→批量删除，+→批量复制，复制→模式切换）。
 *
 * 交互流程：
 *   1. 空白点击拖动 → 框选积木（蓝=移动模式，绿=复制模式）
 *   2. 释放 → 选中积木高亮，显示工具栏，积木按钮被夺舍
 *   3. 拖动选中积木 → 积木/半透明预览跟随鼠标，显示插入指示器
 *   4. 松手 → 积木移动/复制到新位置
 *   5. Ctrl+点击单积木 → 选中并复制拖动
 *   6. Delete/Backspace → 快速删除选中积木
 *   7. 右键/Esc → 取消拖动
 *
 * ------------------------------------------------------------
 * 致谢 / Acknowledgements
 * ------------------------------------------------------------
 * 拖动移动和跳转索引转换逻辑参考了 MI2-Utilities 项目：
 *   - 项目地址: https://github.com/anomaly-251/MI2-Utilities-Java
 *   - 参考文件: src/mi2u/ui/LogicHelperMindow.java
 */
public class BoxSelect{

    // ===== 常量 =====
    private static final float MIN_DRAG_DIST = 8f;
    private static final float SCROLLBAR_WIDTH = 14f;
    private static final float AUTOSCROLL_MARGIN = 80f;
    private static final float AUTOSCROLL_SPEED = 15f;
    private static final float FILL_ALPHA = 0.15f;
    private static final float BORDER_ALPHA = 0.8f;
    private static final float COPY_PREVIEW_ALPHA = 0.5f;
    private static final float SCROLLBAR_SEG_ALPHA = 0.35f;
    private static final Mat tmpMat = new Mat();
    private static final Mat tmpMat2 = new Mat();

    // ===== 反射字段（包级私有，缓存 Field）=====
    private static final Field draggingField;
    private static final Field privilegedField;
    static{
        try{
            draggingField = LCanvas.class.getDeclaredField("dragging");
            draggingField.setAccessible(true);
            privilegedField = LCanvas.class.getDeclaredField("privileged");
            privilegedField.setAccessible(true);
        }catch(Exception e){
            Log.err("[LogicAssist] Failed to access LCanvas fields", e);
            throw new RuntimeException(e);
        }
    }

    // ===== 状态 =====
    private enum State{
        IDLE, SELECTING, SELECTED, DRAGGING_MOVE, DRAGGING_COPY
    }
    private static State state = State.IDLE;

    // 拖拽模式：移动或复制（替代 Ctrl 键，支持移动端）
    private enum DragMode{ MOVE, COPY }
    private static DragMode dragMode = DragMode.MOVE;

    // 框选坐标（stage 坐标系，用于距离判定）
    private static float selStartX, selStartY;
    private static float selCurX, selCurY;
    // 框选坐标（DragLayout 本地坐标系，用于命中判定和绘制）
    private static float selStartLocalX, selStartLocalY;
    private static float selCurLocalX, selCurLocalY;
    private static boolean dragMoved = false;

    // 选中集合（保持插入顺序）
    private static final LinkedHashSet<StatementElem> selected = new LinkedHashSet<>();

    // 拖动状态
    private static float dragStartMouseX, dragStartMouseY;
    private static float dragStartLocalX, dragStartLocalY;
    private static int dragInsertPos = -1;

    // 插入指示器几何位置
    private static float indicatorX, indicatorY, indicatorW, indicatorH;

    // 复制用剪贴板（用 copy() 保持 ExprStatement 折叠状态，不经过 write+read）
    private static List<LStatement> clipboardCopies = null;
    private static int clipboardSize = 0;
    private static int[] clipboardSelectedIndices = null;

    // UI 元素
    private static Element overlay;
    private static boolean initialized = false;
    private static InputListener captureListener;

    // 反射缓存（仅 vScrollBounds 仍需反射，ScrollPane 内部字段）
    private static Field vScrollBoundsField;

    // ==================================================================
    // 初始化
    // ==================================================================

    public static void init(){
        Core.app.post(() -> {
            LCanvas canvas = getCanvas();
            if(canvas == null){
                // canvas 还没准备好，下一帧重试
                Core.app.post(() -> init());
                return;
            }
            setup(canvas);
            initialized = true;
            Log.info("[LogicAssist] BoxSelect initialized (event-driven mode).");

            // 对话框关闭时重置状态（替代原 tick 轮询检测）
            LogicDialog dialog = Vars.ui.logic;
            if(dialog != null){
                dialog.hidden(() -> {
                    if(state != State.IDLE){
                        resetState(canvas);
                    }
                });
            }
        });

        // Delete/Backspace 键：事件驱动，不再轮询
        Core.scene.addListener(new InputListener(){
            @Override
            public boolean keyDown(InputEvent event, KeyCode key){
                if(key != KeyCode.del && key != KeyCode.backspace) return false;
                LogicDialog dialog = Vars.ui.logic;
                if(dialog == null || !dialog.isShown()) return false;
                if(state != State.SELECTED || selected.isEmpty()) return false;
                LCanvas canvas = getCanvas();
                if(canvas != null){
                    deleteSelected(canvas);
                }
                return false;
            }
        });
    }

    /** 注册 capture listener 和 overlay。
     *  capture listener 加在 Core.scene 的 root 上（通过 addCaptureListener），
     *  在事件捕获阶段（target 之前）执行，用 event.stop() 阻止原版 StatementElem 的 InputListener 收到事件。 */
    private static void setup(LCanvas canvas){
        // 注册 capture listener（只注册一次，跨对话框开关复用）
        if(captureListener == null){
            captureListener = new InputListener(){
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                    return handleTouchDown(event, x, y, pointer, button);
                }

                @Override
                public void touchDragged(InputEvent event, float x, float y, int pointer){
                    handleTouchDragged(event, x, y, pointer);
                }

                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                    handleTouchUp(event, x, y, pointer, button);
                }
            };
            Core.scene.addCaptureListener(captureListener);
            Log.info("[LogicAssist] Capture listener registered.");
        }

        // overlay 用于绘制框选框、高亮、插入指示器
        overlay = new Element(){
            @Override
            public void draw(){
                drawOverlay();
            }
        };
        overlay.touchable = Touchable.disabled;
        overlay.cullable = false;
        overlay.visible = true;
        Core.scene.add(overlay);
        overlay.update(() -> {
            // 不使用 visible 控制显示——visible=false 会导致 act() 不执行，
            // update() 不会被调用，形成死锁。直接执行 setSize + toFront 即可。
            overlay.setSize(Core.graphics.getWidth(), Core.graphics.getHeight());
            overlay.toFront();

            // 拖拽期间每帧重新计算插入指示器位置（滚轮滚动时 touchDragged 不触发）
            if(state == State.DRAGGING_MOVE || state == State.DRAGGING_COPY){
                LCanvas c = getCanvas();
                if(c != null){
                    float mx = Core.input.mouseX();
                    float my = Core.input.mouseY();
                    updateDrag(c, mx, my);
                    autoScroll(c);
                }
            }
        });
    }

    // ==================================================================
    // 事件处理（Capture 阶段，在 target 之前执行）
    // ==================================================================

    /** 判断当前事件是否应该由我们处理。
     *  只在 LogicDialog 显示且 canvas 可用时才介入。 */
    private static boolean shouldIntercept(LCanvas canvas){
        LogicDialog dialog = Vars.ui.logic;
        return dialog != null && dialog.isShown() && canvas != null && canvas.statements != null;
    }

    /** 判断点击是否在积木的拖动区（header 条）上，而非按钮上。
     *  原版 InputListener 用 event.targetActor instanceof Image 来排除按钮点击。
     *  我们用类似逻辑：如果 target 是 Image（按钮图标），放行给原版处理。 */
    private static boolean isClickOnButton(Element target){
        return target instanceof Image;
    }

    /** 判断元素是否是 canvas（LCanvas）的后代。
     *  返回按钮、变量按钮等在 LogicDialog.buttons 区，不在 canvas 内。
     *  只有 canvas 内的空白区才允许框选。 */
    private static boolean isDescendantOfCanvas(Element elem, LCanvas canvas){
        Element current = elem;
        while(current != null){
            if(current == canvas) return true;
            current = current.parent;
        }
        return false;
    }

    private static boolean handleTouchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
        LCanvas canvas = getCanvas();
        if(canvas == null || !shouldIntercept(canvas)) return false;

        // 只处理鼠标左键和中键（中键原版用于复制单个积木）
        if(button != KeyCode.mouseLeft && button != KeyCode.mouseMiddle){
            return false;
        }

        // 右键在 touchDown 不会被触发（mouseRight），但以防万一
        if(button == KeyCode.mouseRight) return false;

        // 检测点击目标
        Vec2 stageCoords = Tmp.v1.set(x, y);
        Element target = event.targetActor;

        // 如果点在按钮上（Image），检查是否是选中积木的按钮
        if(isClickOnButton(target)){
            // 尝试夺舍：如果点击的是选中积木上的按钮，执行批量操作
            if(tryHijackButton(canvas, event, target)){
                return true; // 已拦截
            }
            return false; // 非选中积木的按钮，放行给原版
        }

        // 关键：只有点击在 canvas（LCanvas）内部时才介入
        // 返回按钮、变量按钮等在 LogicDialog.buttons 区，不在 canvas 内，放行给原版
        if(!isDescendantOfCanvas(target, canvas)){
            return false;
        }

        // 点击在滚动条区域时放行给原版处理（滚动条在 ScrollPane 右侧）
        if(canvas.pane != null && canvas.pane.hasScroll()){
            float paneX = canvas.pane.x;
            float paneW = canvas.pane.getWidth();
            // 滚动条区域：右侧 14px
            if(stageCoords.x > paneX + paneW - SCROLLBAR_WIDTH){
                return false;
            }
        }

        // 沿祖先链查找 StatementElem
        StatementElem clickedStmt = null;
        Element current = target;
        while(current != null){
            if(current instanceof StatementElem){
                clickedStmt = (StatementElem)current;
                break;
            }
            current = current.parent;
        }

        boolean onStatement = clickedStmt != null;
        boolean onSelectedStatement = onStatement && selected.contains(clickedStmt);

        if(onStatement && !onSelectedStatement){
            // Ctrl+点击非选中积木 → 选中该积木并开始复制拖动
            boolean ctrlDown = Core.input.keyDown(KeyCode.controlLeft);
            if(ctrlDown){
                selected.clear();
                selected.add(clickedStmt);
                startDrag(canvas, stageCoords.x, stageCoords.y, button);
                event.stop();
                return true;
            }
            // 普通点击非选中积木 → 放行给原版（让原版处理单积木拖拽）
            // 但如果当前有选中，先清空选中
            if(!selected.isEmpty()){
                clearSelection();
            }
            return false;
        }

        if(onSelectedStatement){
            // 点击选中积木 → 开始拖动，拦截事件
            startDrag(canvas, stageCoords.x, stageCoords.y, button);
            event.stop(); // 阻止原版 InputListener 收到事件
            return true;  // 注册 touchFocus，接收后续 drag/up
        }

        // canvas 内的空白区点击 → 开始框选，拦截事件
        startBoxSelect(canvas, stageCoords.x, stageCoords.y);
        event.stop();
        return true;
    }

    private static void handleTouchDragged(InputEvent event, float x, float y, int pointer){
        LCanvas canvas = getCanvas();
        if(canvas == null || state == State.IDLE) return;

        float mx = x;
        float my = y;

        if(state == State.SELECTING){
            selCurX = mx;
            selCurY = my;
            Vec2 curLocal = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(mx, my));
            selCurLocalX = curLocal.x;
            selCurLocalY = curLocal.y;
            float dx = Math.abs(selCurX - selStartX);
            float dy = Math.abs(selCurY - selStartY);
            if(dx > MIN_DRAG_DIST || dy > MIN_DRAG_DIST){
                dragMoved = true;
                updateSelection(canvas);
            }
            autoScroll(canvas);
        }else if(state == State.DRAGGING_MOVE || state == State.DRAGGING_COPY){
            updateDrag(canvas, mx, my);
            float dx = Math.abs(mx - dragStartMouseX);
            float dy = Math.abs(my - dragStartMouseY);
            if(dx > MIN_DRAG_DIST || dy > MIN_DRAG_DIST){
                dragMoved = true;
            }
            // 拖动时也支持自动滚动
            autoScroll(canvas);
        }
    }

    private static void handleTouchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
        LCanvas canvas = getCanvas();
        if(canvas == null) return;

        if(state == State.SELECTING){
            if(!dragMoved){
                if(!selected.isEmpty()) clearSelection();
                state = State.IDLE;
            }else{
                if(!selected.isEmpty()){
                    state = State.SELECTED;
                }else{
                    state = State.IDLE;
                }
            }
        }else if(state == State.DRAGGING_MOVE){
            if(dragMoved && dragInsertPos >= 0){
                executeDragMove(canvas, dragInsertPos);
            }else{
                cancelDrag(canvas);
            }
        }else if(state == State.DRAGGING_COPY){
            if(dragMoved && dragInsertPos >= 0){
                executeDragCopy(canvas, dragInsertPos);
            }else{
                cancelDrag(canvas);
            }
        }
    }

    // ==================================================================
    // 夺舍按钮（方向1：劫持选中积木上的功能按钮）
    // ==================================================================

    /** 尝试夺舍选中积木上的按钮点击。
     *  如果点击的是选中积木上的按钮（删除/+号/复制），执行批量操作并拦截事件。
     *  @return true 如果已拦截，false 如果应放行 */
    private static boolean tryHijackButton(LCanvas canvas, InputEvent event, Element target){
        if(selected.isEmpty()) return false;

        // 从 Image 向上找 ImageButton
        Element btn = target.parent;
        while(btn != null && !(btn instanceof ImageButton)) btn = btn.parent;
        if(btn == null) return false;
        ImageButton button = (ImageButton)btn;

        // 从 ImageButton 向上找 StatementElem
        StatementElem stmtElem = null;
        Element p = btn.parent;
        while(p != null){
            if(p instanceof StatementElem){
                stmtElem = (StatementElem)p;
                break;
            }
            p = p.parent;
        }
        if(stmtElem == null || !selected.contains(stmtElem)) return false;

        // 识别按钮：通过 style.imageUp（包括被夺舍后改过的图标）
        Drawable icon = button.getStyle().imageUp;
        // 删除按钮：Icon.cancel → 批量删除
        if(icon == Icon.cancel){
            event.stop();
            Core.app.post(() -> deleteSelected(canvas));
            return true;
        }
        // +按钮：Icon.add → 在选中积木下方批量复制一份
        if(icon == Icon.add){
            event.stop();
            Core.app.post(() -> duplicateSelectedBelow(canvas));
            return true;
        }
        // 复制/移动切换按钮：Icon.copy 或 Icon.move → 切换模式
        if(icon == Icon.copy || icon == Icon.move){
            event.stop();
            dragMode = (dragMode == DragMode.MOVE) ? DragMode.COPY : DragMode.MOVE;
            updateSelectedButtonIcons(canvas);
            return true;
        }
        // 其他按钮放行（MindustryX 的额外按钮等）
        return false;
    }

    /** 更新所有选中积木的复制/移动按钮图标 */
    private static void updateSelectedButtonIcons(LCanvas canvas){
        Drawable modeIcon = (dragMode == DragMode.MOVE) ? Icon.move : Icon.copy;
        for(StatementElem elem : selected){
            findAndSetIcon(elem, Icon.copy, modeIcon);
            findAndSetIcon(elem, Icon.move, modeIcon);
        }
    }

    /** 在 StatementElem 中查找指定图标的 ImageButton 并替换图标 */
    private static void findAndSetIcon(StatementElem elem, Drawable oldIcon, Drawable newIcon){
        // 递归查找 ImageButton
        findAndSetIconRecursive(elem, oldIcon, newIcon);
    }

    private static boolean findAndSetIconRecursive(Element e, Drawable oldIcon, Drawable newIcon){
        if(e instanceof ImageButton){
            ImageButton btn = (ImageButton)e;
            if(btn.getStyle().imageUp == oldIcon){
                // 创建新样式副本，避免修改全局样式
                ImageButton.ImageButtonStyle newStyle = new ImageButton.ImageButtonStyle(btn.getStyle());
                newStyle.imageUp = newIcon;
                btn.setStyle(newStyle);
                return true;
            }
        }
        if(e instanceof Group){
            for(Element child : ((Group)e).getChildren()){
                if(findAndSetIconRecursive(child, oldIcon, newIcon)) return true;
            }
        }
        return false;
    }

    /** 恢复所有积木的按钮图标（取消选中时调用） */
    private static void restoreButtonIcons(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return;
        for(Element child : canvas.statements.getChildren()){
            if(child instanceof StatementElem){
                // 把 Icon.move 改回 Icon.copy
                findAndSetIcon((StatementElem)child, Icon.move, Icon.copy);
            }
        }
    }

    /** 在选中积木最后一块的下方批量复制一份 */
    private static void duplicateSelectedBelow(LCanvas canvas){
        clearDraggingField(canvas);

        List<StatementElem> sorted = getSortedSelected(canvas);
        Seq<Element> children = canvas.statements.getChildren();
        int lastIdx = children.indexOf(sorted.get(sorted.size() - 1), true);
        int insertPos = lastIdx + 1;

        int[] selectedIndices = new int[sorted.size()];
        Seq<LStatement> copies = new Seq<>();
        for(int i = 0; i < sorted.size(); i++){
            selectedIndices[i] = children.indexOf(sorted.get(i), true);
            sorted.get(i).st.saveUI();
            LStatement copy = sorted.get(i).st.copy();
            Log.info("[LogicAssist] duplicateSelectedBelow: st=@ copy=@", sorted.get(i).st.getClass().getSimpleName(), copy == null ? "null" : copy.getClass().getSimpleName());
            if(copy != null) copies.add(copy);
        }
        int copySize = copies.size;

        if(children.size + copySize > LExecutor.maxInstructions){
            Log.debug("[LogicAssist] Duplicate aborted: would exceed maxInstructions");
            return;
        }

        if(copies.isEmpty()) return;

        adjustJumpDestIndices(copies, selectedIndices, insertPos, copySize);

        for(int i = 0; i < copies.size; i++){
            canvas.addAt(insertPos + i, copies.get(i));
        }
        for(LStatement st : copies){
            st.setupUI();
        }

        finalizeLayout(canvas);
        // 先恢复所有积木的按钮图标（旧选中积木的 Icon.move 改回 Icon.copy）
        restoreButtonIcons(canvas);
        selected.clear();
        reselectRange(canvas, insertPos, copies.size);
        enterSelectedState(canvas);
        Log.debug("[LogicAssist] Duplicated " + copies.size + " blocks below selection.");
    }

    // ==================================================================
    // 框选
    // ==================================================================

    private static void startBoxSelect(LCanvas canvas, float mx, float my){
        selStartX = mx;
        selStartY = my;
        selCurX = mx;
        selCurY = my;
        Vec2 startLocal = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(mx, my));
        selStartLocalX = startLocal.x;
        selStartLocalY = startLocal.y;
        selCurLocalX = startLocal.x;
        selCurLocalY = startLocal.y;
        dragMoved = false;
        state = State.SELECTING;
    }

    private static void updateSelection(LCanvas canvas){
        selected.clear();
        float minX = Math.min(selStartLocalX, selCurLocalX);
        float minY = Math.min(selStartLocalY, selCurLocalY);
        float maxX = Math.max(selStartLocalX, selCurLocalX);
        float maxY = Math.max(selStartLocalY, selCurLocalY);

        for(Element child : canvas.statements.getChildren()){
            if(!(child instanceof StatementElem)) continue;
            float cx = child.x;
            float cy = child.y;
            float cw = child.getWidth();
            float ch = child.getHeight();
            if(minX < cx + cw && maxX > cx && minY < cy + ch && maxY > cy){
                selected.add((StatementElem)child);
            }
        }
    }

    private static void clearSelection(){
        // 恢复按钮图标
        LCanvas canvas = getCanvas();
        if(canvas != null) restoreButtonIcons(canvas);
        selected.clear();
        state = State.IDLE;
    }

    /** 重置所有积木的 translation（applyInsertShiftViaTranslation 可能给非选中积木设了 translation） */
    private static void resetAllTranslations(LCanvas canvas){
        for(Element child : canvas.statements.getChildren()){
            child.setTranslation(0, 0);
        }
    }

    private static void resetState(LCanvas canvas){
        clearDraggingField(canvas);
        restoreButtonIcons(canvas);
        resetAllTranslations(canvas);
        selected.clear();
        state = State.IDLE;
        dragInsertPos = -1;
        dragMoved = false;
        clipboardCopies = null;
        clipboardSize = 0;
        clipboardSelectedIndices = null;
    }

    // ==================================================================
    // 拖动
    // ==================================================================

    private static void startDrag(LCanvas canvas, float mx, float my, KeyCode button){
        dragStartMouseX = mx;
        dragStartMouseY = my;
        Vec2 startLocal = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(mx, my));
        dragStartLocalX = startLocal.x;
        dragStartLocalY = startLocal.y;
        dragInsertPos = -1;
        dragMoved = false;

        boolean ctrlDown = Core.input.keyDown(KeyCode.controlLeft);
        boolean isCopy = ctrlDown || dragMode == DragMode.COPY || button == KeyCode.mouseMiddle;

        // 关键：清除原版 dragging 字段，防止原版 layout 跳过错误积木
        clearDraggingField(canvas);

        if(isCopy){
            prepareCopyData(canvas);
            state = State.DRAGGING_COPY;
        }else{
            state = State.DRAGGING_MOVE;
        }
    }

    /** 拖动期间每帧更新 translation 和插入位置 */
    private static void updateDrag(LCanvas canvas, float mx, float my){
        // 清除原版 dragging（防止原版 InputListener 残留干扰）
        clearDraggingField(canvas);

        // invalidate + validate 获取基础布局（所有积木在原始位置）
        canvas.statements.invalidate();
        canvas.statements.validate();

        // 干净起点，避免累积
        resetAllTranslations(canvas);

        if(state == State.DRAGGING_MOVE){
            // 移动模式：跳过选中积木布局（它们用 translation 跟随鼠标，不占原位）
            relayoutNonSelected(canvas);

            // 用 DragLayout 本地坐标系计算 dx/dy
            Vec2 localMouse = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(mx, my));
            float dx = localMouse.x - dragStartLocalX;
            float dy = localMouse.y - dragStartLocalY;

            // 选中积木跟随鼠标（translation）
            for(StatementElem elem : selected){
                elem.setTranslation(dx, dy);
            }
        }
        // 复制模式：原积木保持原位（validate 已正确布局），不设置 translation
        // 预览跟随鼠标由 drawCopyPreview() 绘制半透明积木

        // 计算插入位置（用原始 child.y，translation 已重置为0）
        int newInsertPos = computeInsertPosition(canvas, my);
        if(newInsertPos != dragInsertPos){
            dragInsertPos = newInsertPos;
        }

        // 用 translation 腾位（不修改 child.y，避免 layout() 重置导致闪烁）
        applyInsertShiftViaTranslation(canvas);

        // 强制更新跳转线位置
        canvas.statements.jumps.act(0f);
        // 计算指示器几何
        updateIndicatorGeometry(canvas);

        // 检查右键/Esc 取消
        if(Core.input.keyTap(KeyCode.mouseRight) || Core.input.keyTap(KeyCode.escape)){
            cancelDrag(canvas);
        }
    }

    // ==================================================================
    // 插入位置计算
    // ==================================================================

    private static int computeInsertPosition(LCanvas canvas, float stageY){
        Seq<Element> children = canvas.statements.getChildren();
        if(children.isEmpty()) return 0;

        Vec2 local = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(0, stageY));
        float localY = local.y;

        if(state == State.DRAGGING_COPY){
            // 复制模式：遍历所有 children（原积木在原位，插入位置可以在它们之间）
            for(int i = 0; i < children.size; i++){
                Element child = children.get(i);
                float centerLocalY = child.y + child.getHeight() / 2f;
                if(localY > centerLocalY){
                    return i;
                }
            }
            return children.size;
        }

        // 移动模式：跳过选中积木（它们已从原位移走）
        int insertPos = 0;
        int nonSelectedCount = 0;
        for(Element child : children){
            if(child instanceof StatementElem && selected.contains(child)) continue;
            float centerLocalY = child.y + child.getHeight() / 2f;
            if(localY > centerLocalY){
                return nonSelectedCount;
            }
            nonSelectedCount++;
        }
        return nonSelectedCount;
    }

    // ==================================================================
    // 手动布局（跳过选中积木 + 腾位）
    // ==================================================================

    /** 跳过选中积木布局，模拟原版 DragLayout.layout() 的 dragging 跳过逻辑 */
    private static void relayoutNonSelected(LCanvas canvas){
        Seq<Element> children = canvas.statements.getChildren();
        float space = Scl.scl(10f);
        float width = canvas.statements.getWidth();

        float totalHeight = 0;
        for(Element e : children){
            totalHeight += e.getPrefHeight() + space;
        }

        float cy = 0;
        for(Element e : children){
            if(e instanceof StatementElem && selected.contains(e)) continue;
            e.setSize(width, e.getPrefHeight());
            e.setPosition(0, totalHeight - cy, Align.topLeft);
            cy += e.getPrefHeight() + space;
        }
    }

    /** 用 translation 腾位（不修改 child.y，避免 layout() 重置导致闪烁）。
     *  translation 在 Element.draw() 中应用，layout() 不会重置它。 */
    private static void applyInsertShiftViaTranslation(LCanvas canvas){
        if(dragInsertPos < 0) return;
        Seq<Element> children = canvas.statements.getChildren();
        float space = Scl.scl(10f);

        float shiftAmount = 0;
        for(StatementElem elem : selected){
            shiftAmount += elem.getHeight() + space;
        }
        shiftAmount -= space;

        if(state == State.DRAGGING_COPY){
            // 复制模式：dragInsertPos 是真实 child 索引，移动所有该位置及之后的 children
            for(int i = dragInsertPos; i < children.size; i++){
                Element child = children.get(i);
                child.setTranslation(child.translation.x, child.translation.y - shiftAmount);
            }
        }else{
            // 移动模式：dragInsertPos 是非选中索引，只移动非选中积木
            int nonSelectedIndex = 0;
            for(Element child : children){
                if(child instanceof StatementElem && selected.contains(child)) continue;
                if(nonSelectedIndex >= dragInsertPos){
                    child.setTranslation(child.translation.x, child.translation.y - shiftAmount);
                }
                nonSelectedIndex++;
            }
        }
    }

    // ==================================================================
    // 指示器几何
    // ==================================================================

    private static void updateIndicatorGeometry(LCanvas canvas){
        if(dragInsertPos < 0) return;

        Seq<Element> children = canvas.statements.getChildren();
        float paneWidth = canvas.statements.getWidth();
        float space = Scl.scl(10f);

        float totalH = 0;
        for(StatementElem elem : selected){
            totalH += elem.getHeight() + space;
        }
        totalH -= space;

        float insertLocalY;
        float drawLocalX = 0;

        if(state == State.DRAGGING_COPY){
            // 复制模式：dragInsertPos 是真实 child 索引，用所有 children 计算
            if(children.isEmpty() || dragInsertPos == 0){
                insertLocalY = canvas.statements.getHeight();
            }else if(dragInsertPos >= children.size){
                Element last = children.get(children.size - 1);
                insertLocalY = last.y - space;
                drawLocalX = last.x;
            }else{
                Element before = children.get(dragInsertPos - 1);
                insertLocalY = before.y - space;
                drawLocalX = before.x;
            }
        }else{
            // 移动模式：用非选中 children 计算
            List<Element> nonSelected = new ArrayList<>();
            for(Element child : children){
                if(child instanceof StatementElem && selected.contains(child)) continue;
                nonSelected.add(child);
            }

            if(nonSelected.isEmpty() || dragInsertPos == 0){
                insertLocalY = canvas.statements.getHeight();
            }else if(dragInsertPos >= nonSelected.size()){
                Element last = nonSelected.get(nonSelected.size() - 1);
                insertLocalY = last.y - space;
                drawLocalX = last.x;
            }else{
                Element before = nonSelected.get(dragInsertPos - 1);
                insertLocalY = before.y - space;
                drawLocalX = before.x;
            }
        }

        Vec2 stagePos = canvas.statements.localToStageCoordinates(Tmp.v1.set(drawLocalX, insertLocalY));
        indicatorX = stagePos.x;
        indicatorY = stagePos.y - totalH;
        indicatorW = paneWidth;
        indicatorH = totalH;
    }

    // ==================================================================
    // 自动滚动
    // ==================================================================

    private static void autoScroll(LCanvas canvas){
        if(canvas.pane == null) return;
        float mouseY = Core.input.mouseY();
        float screenH = Core.graphics.getHeight();
        float margin = Scl.scl(AUTOSCROLL_MARGIN);
        float speed = Scl.scl(AUTOSCROLL_SPEED) * Time.delta;

        if(mouseY < margin){
            canvas.pane.setScrollY(canvas.pane.getScrollY() + speed);
        }else if(mouseY > screenH - margin){
            canvas.pane.setScrollY(canvas.pane.getScrollY() - speed);
        }
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    private static void drawOverlay(){
        LCanvas canvas = getCanvas();
        if(canvas == null || canvas.statements == null) return;
        LogicDialog dialog = Vars.ui.logic;
        if(dialog == null || !dialog.isShown()) return;

        switch(state){
            case SELECTING:
                drawSelectionBox(canvas);
                drawHighlights(canvas);
                break;
            case SELECTED:
                drawHighlights(canvas);
                break;
            case DRAGGING_MOVE:
                drawInsertIndicator(canvas);
                redrawSelectedBlocksOnTop(canvas);
                break;
            case DRAGGING_COPY:
                // 复制模式：原积木在原位由 DragLayout.draw 正常绘制
                // 画插入指示器 + 半透明预览跟随鼠标
                drawInsertIndicator(canvas);
                drawCopyPreview(canvas);
                break;
            default:
                break;
        }

        // 始终绘制彩色滚动条（无论什么状态）
        drawColorScrollbar(canvas);
    }

    /** 根据当前模式返回框选颜色 */
    private static Color getModeColor(){
        return dragMode == DragMode.COPY ? Pal.heal : Pal.place;
    }

    private static void drawSelectionBox(LCanvas canvas){
        float minX = Math.min(selStartLocalX, selCurLocalX);
        float minY = Math.min(selStartLocalY, selCurLocalY);
        float maxX = Math.max(selStartLocalX, selCurLocalX);
        float maxY = Math.max(selStartLocalY, selCurLocalY);

        Vec2 bottomLeft = canvas.statements.localToStageCoordinates(Tmp.v1.set(minX, minY));
        Vec2 topRight = canvas.statements.localToStageCoordinates(Tmp.v2.set(maxX, maxY));

        float sx = bottomLeft.x;
        float sy = bottomLeft.y;
        float w = topRight.x - bottomLeft.x;
        float h = topRight.y - bottomLeft.y;

        Color modeColor = getModeColor();
        Draw.color(modeColor);
        Draw.alpha(FILL_ALPHA);
        Fill.crect(sx, sy, w, h);

        Draw.color(modeColor);
        Draw.alpha(BORDER_ALPHA);
        Lines.stroke(Scl.scl(1.5f), modeColor);
        Lines.rect(sx, sy, w, h);
        Draw.reset();
    }

    private static void drawHighlights(LCanvas canvas){
        if(selected.isEmpty()) return;

        // 计算所有选中积木的总包围框
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE, maxY = Float.MIN_VALUE;
        for(StatementElem elem : selected){
            Vec2 v = elem.localToStageCoordinates(Tmp.v1.set(0, 0));
            minX = Math.min(minX, v.x);
            minY = Math.min(minY, v.y);
            maxX = Math.max(maxX, v.x + elem.getWidth());
            maxY = Math.max(maxY, v.y + elem.getHeight());
        }

        float pad = Scl.scl(4f);
        float x = minX - pad;
        float y = minY - pad;
        float w = maxX - minX + pad * 2;
        float h = maxY - minY + pad * 2;

        // 选中框：半透明填充 + 边框
        Color modeColor = getModeColor();
        Draw.color(modeColor);
        Draw.alpha(FILL_ALPHA);
        Fill.crect(x, y, w, h);

        Draw.color(modeColor);
        Draw.alpha(BORDER_ALPHA);
        Lines.stroke(Scl.scl(1.5f), modeColor);
        Lines.rect(x, y, w, h);
        Draw.reset();
    }

    /** 绘制彩色滚动条：在 ScrollPane 的垂直滚动条轨道上，按每个积木的比例绘制对应类别颜色。 */
    private static void drawColorScrollbar(LCanvas canvas){
        ScrollPane pane = canvas.pane;
        if(pane == null || !pane.hasScroll()) return;

        // 用反射读取 vScrollBounds（ScrollPane 内部的滚动条轨道 Rect，本地坐标）
        Rect vScrollBounds = null;
        try{
            if(vScrollBoundsField == null){
                vScrollBoundsField = ScrollPane.class.getDeclaredField("vScrollBounds");
                vScrollBoundsField.setAccessible(true);
            }
            vScrollBounds = (Rect)vScrollBoundsField.get(pane);
        }catch(Exception e){
            return;
        }
        if(vScrollBounds == null || vScrollBounds.width <= 0 || vScrollBounds.height <= 0) return;

        // 将 vScrollBounds（本地坐标）转换为 stage 坐标
        Vec2 bl = pane.localToStageCoordinates(Tmp.v1.set(vScrollBounds.x, vScrollBounds.y));
        float scrollbarX = bl.x;
        float scrollbarBottom = bl.y;
        float scrollbarW = vScrollBounds.width;
        float scrollbarH = vScrollBounds.height;
        float scrollbarTop = scrollbarBottom + scrollbarH;

        // 计算总高度和每个积木的位置
        Seq<Element> children = canvas.statements.getChildren();
        if(children.isEmpty()) return;

        float space = Scl.scl(10f);
        float totalHeight = 0;
        for(Element child : children){
            totalHeight += child.getPrefHeight() + space;
        }
        totalHeight -= space;
        if(totalHeight <= 0) return;

        // 积木总高度不超过可视区域时不绘制彩色滚动条
        if(totalHeight <= pane.getHeight()) return;

        // 绘制每个积木对应的颜色段（用 ScissorStack 裁剪到滚动条可视区域内）
        Rect clipRect = Tmp.r1.set(scrollbarX, scrollbarBottom, scrollbarW, scrollbarH);
        ScissorStack.push(clipRect);
        float cy = 0;
        for(Element child : children){
            float elemH = child.getPrefHeight();
            float elemColorH = (elemH + space) / totalHeight * scrollbarH;
            float elemTop = scrollbarTop - (cy / totalHeight) * scrollbarH;

            Color c = Color.white;
            if(child instanceof StatementElem se && se.st != null){
                LCategory cat = se.st.category();
                if(cat != null && cat.color != null){
                    c = cat.color;
                }
            }

            Draw.color(c);
            Draw.alpha(SCROLLBAR_SEG_ALPHA);
            Fill.crect(scrollbarX, elemTop - elemColorH, scrollbarW, elemColorH);

            cy += elemH + space;
        }
        Draw.flush();
        ScissorStack.pop();
        Draw.reset();
    }

    private static void drawInsertIndicator(LCanvas canvas){
        if(dragInsertPos < 0) return;
        Draw.reset();
        Tex.pane.draw(indicatorX, indicatorY, indicatorW, indicatorH);
        Draw.reset();
    }

    /** 在正确的 transform 矩阵内重画选中积木，确保积木画在插入阴影上方。
     *  DRAGGING_MOVE 时用选中积木的 translation（鼠标偏移）作为绘制偏移。 */
    private static void redrawSelectedBlocksOnTop(LCanvas canvas){
        if(selected.isEmpty()) return;
        // 所有选中积木共享相同的 translation（在 updateDrag 中统一设置）
        StatementElem first = selected.iterator().next();
        drawElementsWithOffset(canvas, first.translation.x, first.translation.y, 1f);
    }

    /** 复制模式：绘制半透明的积木预览跟随鼠标。 */
    private static void drawCopyPreview(LCanvas canvas){
        if(selected.isEmpty()) return;

        float mx = Core.input.mouseX();
        float my = Core.input.mouseY();
        Vec2 stageMouse = new Vec2();
        Core.scene.screenToStageCoordinates(stageMouse.set(mx, my));
        Vec2 localMouse = new Vec2();
        canvas.statements.stageToLocalCoordinates(localMouse.set(stageMouse));
        float dx = localMouse.x - dragStartLocalX;
        float dy = localMouse.y - dragStartLocalY;

        drawElementsWithOffset(canvas, dx, dy, COPY_PREVIEW_ALPHA);
    }

    /** 统一的绘制方法：保存矩阵 → 设置 translation → 临时修改 x/y → draw → finally 恢复
     *  @param alpha 1f = 不透明（重画在顶层），0.5f = 半透明（复制预览） */
    private static void drawElementsWithOffset(LCanvas canvas, float dx, float dy, float alpha){
        if(selected.isEmpty()) return;

        Mat oldTrans = tmpMat.set(Draw.trans());

        Vec2 origin = canvas.statements.localToStageCoordinates(Tmp.v1.set(0, 0));
        tmpMat2.idt().setToTranslation(origin.x, origin.y);
        Draw.trans(tmpMat2);

        Draw.reset();
        Draw.alpha(alpha);
        for(StatementElem elem : selected){
            boolean oldCullable = elem.cullable;
            elem.cullable = false;
            elem.x += dx;
            elem.y += dy;
            try{
                elem.draw();
            }finally{
                elem.x -= dx;
                elem.y -= dy;
                elem.cullable = oldCullable;
            }
        }
        Draw.reset();

        Draw.trans(oldTrans);
    }
    // ==================================================================
    // 拖动移动
    // ==================================================================

    private static void executeDragMove(LCanvas canvas, int insertPos){
        clearDraggingField(canvas);

        // updateDrag 中 applyInsertShiftViaTranslation 给非选中积木也设了 translation
        resetAllTranslations(canvas);

        List<StatementElem> sorted = getSortedSelected(canvas);
        Seq<Element> children = canvas.statements.getChildren();
        int count = sorted.size();

        for(StatementElem elem : sorted){
            elem.remove();
        }

        int actualInsert = Math.max(0, Math.min(insertPos, children.size));

        for(int i = 0; i < count; i++){
            canvas.statements.addChildAt(actualInsert + i, sorted.get(i));
        }

        canvas.statements.updateJumpHeights = true;
        canvas.statements.invalidate();
        canvas.statements.validate();
        saveAllJumpUI(canvas);
        reselectRange(canvas, actualInsert, count);
        enterSelectedState(canvas);
        Log.debug("[LogicAssist] Drag-moved " + count + " blocks to position " + actualInsert);
    }

    // ==================================================================
    // 拖动复制
    // ==================================================================

    private static void prepareCopyData(LCanvas canvas){
        List<StatementElem> sorted = getSortedSelected(canvas);
        Seq<Element> children = canvas.statements.getChildren();

        clipboardSelectedIndices = new int[sorted.size()];
        for(int i = 0; i < sorted.size(); i++){
            clipboardSelectedIndices[i] = children.indexOf(sorted.get(i), true);
        }

        // 用 copy() 保持 ExprStatement 折叠状态（write+read 会展开表达式为 op 链）
        clipboardCopies = new ArrayList<>();
        for(StatementElem elem : sorted){
            elem.st.saveUI();
            LStatement copy = elem.st.copy();
            Log.info("[LogicAssist] prepareCopyData: st=@ copy=@", elem.st.getClass().getSimpleName(), copy == null ? "null" : copy.getClass().getSimpleName());
            if(copy != null) clipboardCopies.add(copy);
        }
        clipboardSize = clipboardCopies.size();
    }

    private static void executeDragCopy(LCanvas canvas, int insertPos){
        clearDraggingField(canvas);

        resetAllTranslations(canvas);

        if(clipboardCopies == null || clipboardCopies.isEmpty()){
            enterSelectedState(canvas);
            return;
        }

        int currentSize = canvas.statements.getChildren().size;
        if(currentSize + clipboardSize > LExecutor.maxInstructions){
            Log.debug("[LogicAssist] Copy aborted: would exceed maxInstructions");
            enterSelectedState(canvas);
            return;
        }

        // 从 clipboardCopies 创建新副本（每次复制都需要独立对象）
        Seq<LStatement> copies = new Seq<>();
        for(LStatement st : clipboardCopies){
            LStatement copy = st.copy();
            Log.info("[LogicAssist] executeDragCopy: st=@ copy=@", st.getClass().getSimpleName(), copy == null ? "null" : copy.getClass().getSimpleName());
            copies.add(copy);
        }
        if(copies.isEmpty()){
            enterSelectedState(canvas);
            return;
        }

        int actualInsert = Math.max(0, Math.min(insertPos, canvas.statements.getChildren().size));

        adjustJumpDestIndices(copies, clipboardSelectedIndices, actualInsert, copies.size);

        // 先全部插入，再统一 setupUI（避免 jump.setupUI() 找不到未插入的副本）
        for(int i = 0; i < copies.size; i++){
            canvas.addAt(actualInsert + i, copies.get(i));
        }
        for(LStatement st : copies){
            st.setupUI();
        }

        finalizeLayout(canvas);
        reselectRange(canvas, actualInsert, copies.size);

        clipboardCopies = null;
        clipboardSize = 0;
        clipboardSelectedIndices = null;

        enterSelectedState(canvas);
        Log.debug("[LogicAssist] Drag-copied " + copies.size + " blocks to position " + insertPos);
    }

    private static void cancelDrag(LCanvas canvas){
        clearDraggingField(canvas);

        resetAllTranslations(canvas);
        clipboardCopies = null;
        clipboardSize = 0;
        clipboardSelectedIndices = null;
        dragInsertPos = -1;
        state = State.SELECTED;
        Log.info("[LogicAssist] Drag cancelled.");
    }

    /** Delete 键快速删除选中积木 */
    private static void deleteSelected(LCanvas canvas){
        clearDraggingField(canvas);

        resetAllTranslations(canvas);

        List<StatementElem> sorted = getSortedSelected(canvas);
        int count = sorted.size();

        for(StatementElem elem : sorted){
            elem.remove();
        }

        saveAllJumpUI(canvas);
        finalizeLayout(canvas);

        selected.clear();
        state = State.IDLE;
        Log.debug("[LogicAssist] Deleted " + count + " blocks.");
    }

    // ==================================================================
    // 辅助方法
    // ==================================================================

    private static List<StatementElem> getSortedSelected(LCanvas canvas){
        List<StatementElem> sorted = new ArrayList<>(selected);
        Seq<Element> children = canvas.statements.getChildren();
        sorted.sort((a, b) -> {
            int ia = children.indexOf(a, true);
            int ib = children.indexOf(b, true);
            return Integer.compare(ia, ib);
        });
        return sorted;
    }

    /** 调整副本中 JumpStatement 的 destIndex（executeDragCopy 和 duplicateSelectedBelow 共用） */
    private static void adjustJumpDestIndices(Seq<LStatement> copies, int[] selectedIndices, int insertPos, int copySize){
        for(LStatement st : copies){
            if(st instanceof JumpStatement js && js.destIndex != -1){
                int oldDest = js.destIndex;
                int selectedPos = -1;
                if(selectedIndices != null){
                    for(int i = 0; i < selectedIndices.length; i++){
                        if(selectedIndices[i] == oldDest){
                            selectedPos = i;
                            break;
                        }
                    }
                }
                if(selectedPos >= 0){
                    js.destIndex = insertPos + selectedPos;
                }else if(oldDest >= insertPos){
                    js.destIndex = oldDest + copySize;
                }
            }
        }
    }

    /** 从 children 中重新选中指定范围的积木 */
    private static void reselectRange(LCanvas canvas, int start, int count){
        selected.clear();
        Seq<Element> newChildren = canvas.statements.getChildren();
        for(int i = start; i < start + count && i < newChildren.size; i++){
            if(newChildren.get(i) instanceof StatementElem){
                selected.add((StatementElem)newChildren.get(i));
            }
        }
        // 夺舍新选中积木的 copy/move 按钮图标
        updateSelectedButtonIcons(canvas);
    }

    /** 双重 invalidate + validate，处理高度变化后的布局稳定 */
    private static void finalizeLayout(LCanvas canvas){
        canvas.statements.updateJumpHeights = true;
        canvas.statements.invalidate();
        canvas.statements.validate();
        // layout() 发现 height 变化后调用 invalidateHierarchy() 标记父节点，
        // 但自身 layout() 已执行完毕（用的是旧 height）。需要第二次 validate 用新 height 重新布局。
        canvas.statements.invalidate();
        canvas.statements.validate();
        // 更新跳转线位置（基于最终布局）
        canvas.statements.jumps.act(0f);
    }

    /** 更新所有积木的 JumpStatement destIndex（移动/删除后调用） */
    private static void saveAllJumpUI(LCanvas canvas){
        for(Element child : canvas.statements.getChildren()){
            if(child instanceof StatementElem se && se.st instanceof JumpStatement js){
                // dest 可能指向已删除的积木（dest.parent == null），需要安全检查
                if(js.dest == null || js.dest.parent == null){
                    js.destIndex = -1;
                }else{
                    js.saveUI();
                }
            }
        }
    }

    /** 进入 SELECTED 状态 */
    private static void enterSelectedState(LCanvas canvas){
        state = State.SELECTED;
    }

    private static LCanvas getCanvas(){
        try{
            if(Vars.ui.logic == null) return null;
            return Vars.ui.logic.canvas;
        }catch(Exception e){
            return null;
        }
    }

    private static boolean isPrivileged(LCanvas canvas){
        try{
            return privilegedField.getBoolean(canvas);
        }catch(Exception e){
            return false;
        }
    }

    private static void clearDraggingField(LCanvas canvas){
        try{
            draggingField.set(canvas, null);
        }catch(Exception e){
            Log.warn("[LogicAssist] Failed to clear dragging field", e);
        }
    }
}
