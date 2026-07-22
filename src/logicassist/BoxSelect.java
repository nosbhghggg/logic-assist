package logicassist;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
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
import mindustry.ui.dialogs.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * 框选功能 - 批量选择、复制、移动积木（事件驱动重构版）。
 *
 * 架构（方案 A+B）：
 * - 输入层：CaptureListener 从事件源头拦截，原版 StatementElem 的 InputListener 收不到事件，
 *   不再需要 clearDraggingField / restoreChildrenOrder 等对抗代码。
 * - 布局层：拖动期间把选中集合的第一个元素设为原版 dragging，让原版 DragLayout.layout()
 *   跳过它；其余选中积木通过 translation 跟随。释放时直接操作 children 顺序。
 *
 * 交互流程：
 *   1. 空白点击拖动 → 框选积木
 *   2. 释放 → 选中积木高亮，显示工具栏
 *   3. 直接拖动选中积木 → 积木跟随鼠标，显示插入指示器
 *   4. 松手 → 积木移动到新位置
 *   5. Ctrl+拖动 → 复制模式（原积木保留）
 *   6. 右键/Esc → 取消拖动
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
    // 复制模式：保存选中积木在 validate() 后的原始 Y（applyInsertShift 会修改 Y，预览需要用原始值）
    private static final ObjectMap<StatementElem, Float> copyPreviewOrigY = new ObjectMap<>();

    // 插入指示器几何位置
    private static float indicatorX, indicatorY, indicatorW, indicatorH;

    // 复制用剪贴板
    private static String clipboardData = null;
    private static int clipboardSize = 0;
    private static int[] clipboardSelectedIndices = null;

    // UI 元素
    private static Table toolbar;
    private static Element overlay;
    private static boolean initialized = false;
    private static InputListener captureListener;

    // 反射缓存
    private static Field draggingField;
    private static boolean draggingFieldChecked = false;
    private static Field privilegedField;
    private static boolean reflectionChecked = false;

    // ==================================================================
    // 初始化
    // ==================================================================

    public static void init(){
        Core.app.post(() -> tick());
    }

    private static void tick(){
        Core.app.post(BoxSelect::tick);

        try{
            LCanvas canvas = getCanvas();
            if(canvas == null) return;

            if(!initialized){
                setup(canvas);
                initialized = true;
                Log.info("[LogicAssist] BoxSelect initialized (capture listener mode).");
            }

            LogicDialog dialog = Vars.ui.logic;
            if(dialog != null && !dialog.isShown() && state != State.IDLE){
                resetState(canvas);
            }

            // Delete 键快速删除选中积木
            if(dialog != null && dialog.isShown() && state == State.SELECTED && !selected.isEmpty()){
                if(Core.input.keyTap(KeyCode.del) || Core.input.keyTap(KeyCode.backspace)){
                    deleteSelected(canvas);
                }
            }

            // 更新工具栏位置
            if(toolbar != null && toolbar.parent != null && dialog != null && dialog.isShown()){
                Vec2 dialogPos = Tmp.v1.set(0, 0);
                dialog.localToStageCoordinates(dialogPos);
                toolbar.setPosition(
                    dialogPos.x + canvas.getWidth() / 2f - toolbar.getWidth() / 2f,
                    dialogPos.y + canvas.getHeight() - toolbar.getHeight() - Scl.scl(10f)
                );
                toolbar.toFront();
            }
        }catch(Exception e){
            Log.info("[LogicAssist] BoxSelect tick error: " + e);
        }
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
            overlay.setSize(Core.graphics.getWidth(), Core.graphics.getHeight());
            overlay.toFront();
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

        // 如果点在按钮上（Image），放行给原版
        if(isClickOnButton(target)){
            return false;
        }

        // 关键：只有点击在 canvas（LCanvas）内部时才介入
        // 返回按钮、变量按钮等在 LogicDialog.buttons 区，不在 canvas 内，放行给原版
        if(!isDescendantOfCanvas(target, canvas)){
            return false;
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

        // 检测是否在工具栏上
        if(isMouseOnToolbar(stageCoords.x, stageCoords.y)){
            return false; // 放行给工具栏按钮
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
                    showToolbar(canvas);
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
    // 右键/Esc 取消（在 tick 中轮询，因为右键不经过 capture listener 的 touchDown）
    // ==================================================================

    // 注意：右键和 Esc 在 capture listener 的 touchDown 中不会被拦截（button != mouseLeft），
    // 所以需要在 overlay.update 或 tick 中轮询。这里在 overlay.update 中处理。
    // 但为了简单，我们在 handleTouchDragged 中检查右键状态。

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
        selected.clear();
        state = State.IDLE;
        hideToolbar();
    }

    private static void resetState(LCanvas canvas){
        clearDraggingField(canvas);
        for(StatementElem elem : selected){
            elem.setTranslation(0, 0);
        }
        selected.clear();
        state = State.IDLE;
        hideToolbar();
        dragInsertPos = -1;
        dragMoved = false;
        clipboardData = null;
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
        copyPreviewOrigY.clear();

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

        hideToolbar();
    }

    /** 拖动期间每帧更新 translation 和插入位置 */
    private static void updateDrag(LCanvas canvas, float mx, float my){
        // 清除原版 dragging（防止原版 InputListener 残留干扰）
        clearDraggingField(canvas);

        // 每帧 invalidate + validate 强制重新 layout
        canvas.statements.invalidate();
        canvas.statements.validate();

        // 复制模式：validate 后保存选中积木的原始 Y（applyInsertShift 会修改 Y）
        // 只需保存一次，因为 validate() 每帧重置位置到相同值
        if(state == State.DRAGGING_COPY && copyPreviewOrigY.isEmpty()){
            for(StatementElem elem : selected){
                copyPreviewOrigY.put(elem, elem.y);
            }
        }

        if(state == State.DRAGGING_MOVE){
            // 移动模式：跳过选中积木布局（它们用 translation 跟随鼠标，不占原位）
            relayoutNonSelected(canvas);

            // 用 DragLayout 本地坐标系计算 dx/dy
            Vec2 localMouse = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(mx, my));
            float dx = localMouse.x - dragStartLocalX;
            float dy = localMouse.y - dragStartLocalY;

            // 选中积木跟随鼠标
            for(StatementElem elem : selected){
                elem.setTranslation(dx, dy);
            }
        }
        // 复制模式：原积木保持原位（validate 已正确布局），不设置 translation
        // 预览跟随鼠标由 drawCopyPreview() 绘制半透明积木

        // 计算插入位置（两种模式都需要）
        int newInsertPos = computeInsertPosition(canvas, my);
        if(newInsertPos != dragInsertPos){
            dragInsertPos = newInsertPos;
        }

        // 两种模式都需要腾位：在插入位置下方的积木下移，给即将插入的积木腾出空间
        // 移动模式：选中积木已从原位移走（relayoutNonSelected 跳过），腾位后显示空隙
        // 复制模式：选中积木在原位，腾位在原积木之外显示插入空隙
        applyInsertShift(canvas);

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

    private static int nonSelectedToChildIndex(LCanvas canvas, int nonSelectedIndex){
        Seq<Element> children = canvas.statements.getChildren();
        int nonSelectedCount = 0;
        for(int i = 0; i < children.size; i++){
            Element child = children.get(i);
            if(child instanceof StatementElem && selected.contains(child)) continue;
            if(nonSelectedCount == nonSelectedIndex) return i;
            nonSelectedCount++;
        }
        return children.size;
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

    /** 把插入位置下方的积木下移腾出空隙 */
    private static void applyInsertShift(LCanvas canvas){
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
                children.get(i).y -= shiftAmount;
            }
        }else{
            // 移动模式：dragInsertPos 是非选中索引，只移动非选中积木
            int nonSelectedIndex = 0;
            for(Element child : children){
                if(child instanceof StatementElem && selected.contains(child)) continue;
                if(nonSelectedIndex >= dragInsertPos){
                    child.y -= shiftAmount;
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
        float margin = Scl.scl(80f);
        float speed = Scl.scl(15f) * Time.delta;

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

        Draw.color(Pal.place);
        Draw.alpha(0.15f);
        Fill.crect(sx, sy, w, h);

        Draw.color(Pal.place);
        Draw.alpha(0.8f);
        Lines.stroke(Scl.scl(1.5f), Pal.place);
        Lines.rect(sx, sy, w, h);
        Draw.reset();
    }

    private static void drawHighlights(LCanvas canvas){
        Lines.stroke(Scl.scl(3f), Pal.accent);
        for(StatementElem elem : selected){
            Vec2 v = elem.localToStageCoordinates(Tmp.v1.set(0, 0));
            float pad = Scl.scl(4f);
            Lines.rect(v.x - pad, v.y - pad,
                       elem.getWidth() + pad * 2, elem.getHeight() + pad * 2);
        }
        Draw.reset();
    }

    private static void drawInsertIndicator(LCanvas canvas){
        if(dragInsertPos < 0) return;
        Draw.reset();
        Tex.pane.draw(indicatorX, indicatorY, indicatorW, indicatorH);
        Draw.reset();
    }

    /** 在正确的 transform 矩阵内重画选中积木，确保积木画在插入阴影上方 */
    private static void redrawSelectedBlocksOnTop(LCanvas canvas){
        if(selected.isEmpty()) return;

        Mat oldTrans = new Mat().set(Draw.trans());

        Vec2 origin = canvas.statements.localToStageCoordinates(Tmp.v1.set(0, 0));
        Mat dragLayoutTrans = new Mat();
        dragLayoutTrans.idt();
        dragLayoutTrans.setToTranslation(origin.x, origin.y);
        Draw.trans(dragLayoutTrans);

        Draw.reset();
        for(StatementElem elem : selected){
            boolean oldCullable = elem.cullable;
            elem.cullable = false;
            elem.x += elem.translation.x;
            elem.y += elem.translation.y;
            elem.draw();
            elem.x -= elem.translation.x;
            elem.y -= elem.translation.y;
            elem.cullable = oldCullable;
        }
        Draw.reset();

        Draw.trans(oldTrans);
    }

    /** 复制模式：绘制半透明的积木预览跟随鼠标。
     *  原积木保持原位不动，预览只是视觉提示"副本会放在这里"。
     *  用选中积木的样式 + 半透明，在鼠标偏移位置绘制。 */
    private static void drawCopyPreview(LCanvas canvas){
        if(selected.isEmpty()) return;

        // 计算鼠标偏移量（本地坐标系）
        float mx = Core.input.mouseX();
        float my = Core.input.mouseY();
        Vec2 stageMouse = Core.scene.screenToStageCoordinates(Tmp.v3.set(mx, my));
        Vec2 localMouse = canvas.statements.stageToLocalCoordinates(Tmp.v3.set(stageMouse.x, stageMouse.y));
        float dx = localMouse.x - dragStartLocalX;
        float dy = localMouse.y - dragStartLocalY;

        // 在 DragLayout transform 内绘制半透明预览
        Mat oldTrans = new Mat().set(Draw.trans());

        Vec2 origin = canvas.statements.localToStageCoordinates(Tmp.v1.set(0, 0));
        Mat dragLayoutTrans = new Mat();
        dragLayoutTrans.idt();
        dragLayoutTrans.setToTranslation(origin.x, origin.y);
        Draw.trans(dragLayoutTrans);

        Draw.reset();
        // 半透明绘制选中积木的预览（用保存的原始 Y，因为 applyInsertShift 可能修改了 elem.y）
        Draw.alpha(0.5f);
        for(StatementElem elem : selected){
            boolean oldCullable = elem.cullable;
            elem.cullable = false;
            float origY = copyPreviewOrigY.get(elem, elem.y);
            float saveY = elem.y;
            elem.x += dx;
            elem.y = origY + dy;
            elem.draw();
            elem.x -= dx;
            elem.y = saveY;
            elem.cullable = oldCullable;
        }
        Draw.reset();

        Draw.trans(oldTrans);
    }
    // ==================================================================

    private static void showToolbar(LCanvas canvas){
        hideToolbar();
        toolbar = new Table(Tex.buttonTrans);
        toolbar.margin(6);
        toolbar.defaults().size(90, 34).padRight(4);

        TextButton modeBtn = new TextButton(dragMode == DragMode.MOVE ? "@la.move" : "@la.copy");
        modeBtn.clicked(() -> {
            dragMode = (dragMode == DragMode.MOVE) ? DragMode.COPY : DragMode.MOVE;
            modeBtn.setText(dragMode == DragMode.MOVE ? "@la.move" : "@la.copy");
        });
        toolbar.add(modeBtn);

        toolbar.button("@la.cancel", Icon.cancel, () -> clearSelection());

        toolbar.pack();
        canvas.addChild(toolbar);
        toolbar.toFront();
    }

    private static void hideToolbar(){
        if(toolbar != null){
            toolbar.remove();
            toolbar = null;
        }
    }

    // ==================================================================
    // 拖动移动
    // ==================================================================

    private static void executeDragMove(LCanvas canvas, int insertPos){
        clearDraggingField(canvas);

        for(StatementElem elem : selected){
            elem.setTranslation(0, 0);
        }

        List<StatementElem> sorted = getSortedSelected(canvas);
        Seq<Element> children = canvas.statements.getChildren();
        int count = sorted.size();

        // 移除所有选中积木
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

        // 更新所有 JumpStatement 的 destIndex
        for(Element child : canvas.statements.getChildren()){
            if(child instanceof StatementElem se && se.st instanceof JumpStatement){
                ((JumpStatement)se.st).saveUI();
            }
        }

        // 重新选中移动后的积木
        selected.clear();
        Seq<Element> newChildren = canvas.statements.getChildren();
        for(int i = actualInsert; i < actualInsert + count && i < newChildren.size; i++){
            if(newChildren.get(i) instanceof StatementElem){
                selected.add((StatementElem)newChildren.get(i));
            }
        }

        state = State.SELECTED;
        showToolbar(canvas);
        Log.info("[LogicAssist] Drag-moved " + count + " blocks to position " + actualInsert);
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

        StringBuilder sb = new StringBuilder();
        for(StatementElem elem : sorted){
            elem.st.saveUI();
            elem.st.write(sb);
            sb.append("\n");
        }
        clipboardData = sb.toString();
        clipboardSize = sorted.size();
    }

    private static void executeDragCopy(LCanvas canvas, int insertPos){
        clearDraggingField(canvas);

        // 重置 translation（原积木回到原位）
        for(StatementElem elem : selected){
            elem.setTranslation(0, 0);
        }

        if(clipboardData == null || clipboardData.isEmpty()){
            state = State.SELECTED;
            showToolbar(canvas);
            return;
        }

        int currentSize = canvas.statements.getChildren().size;
        if(currentSize + clipboardSize > LExecutor.maxInstructions){
            Log.info("[LogicAssist] Copy aborted: would exceed maxInstructions");
            state = State.SELECTED;
            showToolbar(canvas);
            return;
        }

        boolean privileged = isPrivileged(canvas);
        Seq<LStatement> copies = LAssembler.read(clipboardData, privileged);
        if(copies.isEmpty()){
            state = State.SELECTED;
            showToolbar(canvas);
            return;
        }

        // 复制模式：insertPos 已经是真实 child 索引（computeInsertPosition 遍历了所有 children）
        int actualInsert = Math.max(0, Math.min(insertPos, canvas.statements.getChildren().size));

        // 记录原始 children 数量（插入前），用于后续 destIndex 调整
        int origCount = canvas.statements.getChildren().size;

        // 先调整复制出的 JumpStatement 的 destIndex
        // 三种情况：
        //   1. jump 目标在选中范围内 → 指向对应的副本
        //   2. jump 目标在 actualInsert 或之后 → 目标被副本挤后移
        //   3. jump 目标在 actualInsert 之前 → 不变
        for(LStatement st : copies){
            if(st instanceof JumpStatement js && js.destIndex != -1){
                int oldDest = js.destIndex;
                int selectedPos = -1;
                if(clipboardSelectedIndices != null){
                    for(int i = 0; i < clipboardSelectedIndices.length; i++){
                        if(clipboardSelectedIndices[i] == oldDest){
                            selectedPos = i;
                            break;
                        }
                    }
                }
                if(selectedPos >= 0){
                    // 目标在选中范围内 → 指向副本中对应位置的积木
                    js.destIndex = actualInsert + selectedPos;
                }else if(oldDest >= actualInsert){
                    // 目标在插入点或之后 → 目标被副本挤后移
                    js.destIndex = oldDest + clipboardSize;
                }
                // else: 目标在插入点之前 → 不变
            }
        }

        // 先全部插入，再统一 setupUI
        // 之前逐个 addAt + setupUI，导致后面的副本还没插入时前面的 jump.setupUI() 找不到目标
        for(int i = 0; i < copies.size; i++){
            canvas.addAt(actualInsert + i, copies.get(i));
        }
        // 全部插入完成后，统一调用 setupUI 连接 jump 目标
        for(LStatement st : copies){
            st.setupUI();
        }

        // 清除选中集合，让 validate 中的 layout() 正确布局所有积木（包括原积木）
        // 之前 selected 仍包含原积木，如果 layout 依赖 selected 状态可能出问题
        // 原版 layout() 只跳过 dragging，不跳过 selected，所以这里不需要清空 selected
        // 但需要确保 dragging 已清除（已在方法开头 clearDraggingField）
        canvas.statements.updateJumpHeights = true;
        canvas.statements.invalidate();
        canvas.statements.validate();

        // 二次 invalidate + validate，处理高度变化后的布局
        // （新增副本导致 totalHeight 变化，layout() 第一次更新 height 并 invalidateHierarchy，
        //   但 validate() 清除了 invalidated 标志，需要第二次才能用新 height 布局）
        canvas.statements.invalidate();
        canvas.statements.validate();

        Log.info("[LogicAssist] Copy done. Children count: " + canvas.statements.getChildren().size);

        // 重新选中复制出来的积木
        selected.clear();
        Seq<Element> newChildren = canvas.statements.getChildren();
        for(int i = actualInsert; i < actualInsert + copies.size && i < newChildren.size; i++){
            if(newChildren.get(i) instanceof StatementElem){
                selected.add((StatementElem)newChildren.get(i));
            }
        }

        clipboardData = null;
        clipboardSize = 0;
        clipboardSelectedIndices = null;

        state = State.SELECTED;
        showToolbar(canvas);
        Log.info("[LogicAssist] Drag-copied " + copies.size + " blocks to position " + insertPos);
    }

    private static void cancelDrag(LCanvas canvas){
        clearDraggingField(canvas);

        for(StatementElem elem : selected){
            elem.setTranslation(0, 0);
        }
        clipboardData = null;
        clipboardSize = 0;
        clipboardSelectedIndices = null;
        dragInsertPos = -1;
        state = State.SELECTED;
        showToolbar(canvas);
        Log.info("[LogicAssist] Drag cancelled.");
    }

    /** Delete 键快速删除选中积木 */
    private static void deleteSelected(LCanvas canvas){
        clearDraggingField(canvas);

        List<StatementElem> sorted = getSortedSelected(canvas);
        int count = sorted.size();

        for(StatementElem elem : sorted){
            elem.remove();
        }

        // 更新剩余积木的 JumpStatement destIndex
        for(Element child : canvas.statements.getChildren()){
            if(child instanceof StatementElem se && se.st instanceof JumpStatement){
                ((JumpStatement)se.st).saveUI();
            }
        }

        canvas.statements.updateJumpHeights = true;
        canvas.statements.invalidate();
        canvas.statements.validate();

        selected.clear();
        state = State.IDLE;
        hideToolbar();
        Log.info("[LogicAssist] Deleted " + count + " blocks.");
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

    private static LCanvas getCanvas(){
        try{
            if(Vars.ui.logic == null) return null;
            return Vars.ui.logic.canvas;
        }catch(Exception e){
            return null;
        }
    }

    private static boolean isMouseOnToolbar(float stageX, float stageY){
        if(toolbar == null) return false;
        Element hit = Core.scene.hit(stageX, stageY, true);
        if(hit == null) return false;
        Element current = hit;
        while(current != null){
            if(current == toolbar) return true;
            current = current.parent;
        }
        return false;
    }

    private static boolean isPrivileged(LCanvas canvas){
        if(!reflectionChecked){
            reflectionChecked = true;
            try{
                privilegedField = LCanvas.class.getDeclaredField("privileged");
                privilegedField.setAccessible(true);
            }catch(NoSuchFieldException e){
                Log.info("[LogicAssist] Failed to access privileged field: " + e);
            }
        }
        if(privilegedField != null){
            try{
                return privilegedField.getBoolean(canvas);
            }catch(Exception e){
                // 忽略
            }
        }
        return false;
    }

    private static void clearDraggingField(LCanvas canvas){
        if(canvas == null) return;
        if(!draggingFieldChecked){
            draggingFieldChecked = true;
            try{
                draggingField = LCanvas.class.getDeclaredField("dragging");
                draggingField.setAccessible(true);
            }catch(NoSuchFieldException e){
                Log.info("[LogicAssist] Failed to access dragging field: " + e);
            }
        }
        if(draggingField != null){
            try{
                draggingField.set(canvas, null);
            }catch(Exception e){
                // 忽略
            }
        }
    }
}
