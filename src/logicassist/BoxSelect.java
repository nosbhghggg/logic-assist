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
 * 框选功能 - 批量选择、复制、移动积木。
 *
 * 核心设计：
 * - overlay 放在 Stage 上，统一用 stage 坐标系
 * - 选中后直接拖动选中积木 = 移动（用 translation 跟随鼠标，显示真实积木样子）
 * - Ctrl+拖动 = 复制
 * - 插入位置可以是任意位置（包括中间），用 Tex.pane 绘制留位指示器
 *
 * 交互流程：
 *   1. 空白点击拖动 → 框选积木
 *   2. 释放 → 选中积木高亮，显示工具栏
 *   3. 直接拖动选中积木 → 积木"拿在手里"跟随鼠标，显示留位指示器
 *   4. 松手 → 积木移动到新位置
 *   5. Ctrl+拖动 → 复制模式（原积木保留）
 *   6. 右键/Esc → 取消拖动
 *
 * ------------------------------------------------------------
 * 致谢 / Acknowledgements
 * ------------------------------------------------------------
 * 本文件的"拖动移动"和"跳转索引转换"逻辑参考了 MI2-Utilities 项目：
 *   - 项目地址: https://github.com/anomaly-251/MI2-Utilities-Java
 *   - 参考文件: src/mi2u/ui/LogicHelperMindow.java
 *   - 参考方法: doCutPaste() — 直接移动 StatementElem 对象（elem.remove() + addChildAt()）
 *               而非 save/read 重建，从而保留 JumpStatement.dest 引用
 *   - 参考方法: doCutPaste() 的 transJump 部分 — 移动后重新计算 JumpStatement.destIndex
 *
 * 代码中标记 [MI2-Utilities] 的注释处即为参考该项目的实现。
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

    // 鼠标轮询状态
    private static boolean prevMouseDown = false;
    private static boolean mousePressed = false;

    // 框选坐标（stage 坐标系，用于距离判定）
    private static float selStartX, selStartY;
    private static float selCurX, selCurY;
    // 框选坐标（DragLayout 本地坐标系，用于命中判定和绘制）
    // 关键：用本地坐标系后，滚动 pane 时框选框跟随内容移动，而非固定在屏幕上
    private static float selStartLocalX, selStartLocalY;
    private static float selCurLocalX, selCurLocalY;
    private static boolean dragMoved = false;

    // 选中集合（保持插入顺序）
    private static final LinkedHashSet<StatementElem> selected = new LinkedHashSet<>();

    // 拖动状态
    private static float dragStartMouseX, dragStartMouseY;
    // 鼠标在 DragLayout 本地坐标系中的起始位置（用于计算 translation，自动补偿滚动偏移）
    private static float dragStartLocalX, dragStartLocalY;
    private static int dragInsertPos = -1;

    // 插入指示器几何位置（act 阶段计算，draw 阶段使用，避免 toFront 后 children 顺序不一致）
    private static float indicatorX, indicatorY, indicatorW, indicatorH;

    // 复制用剪贴板
    private static String clipboardData = null;
    private static int clipboardSize = 0;
    private static int clipboardMinIndex = -1;
    private static int clipboardMaxIndex = -1;
    // 记录选中积木在 children 中的原始索引（用于复制时正确映射跳转目标）
    private static int[] clipboardSelectedIndices = null;

    // UI 元素
    private static Element overlay;
    private static Table toolbar;
    private static boolean initialized = false;

    // 反射缓存
    private static Field privilegedField;
    private static boolean reflectionChecked = false;
    private static Field draggingField;
    private static boolean draggingFieldChecked = false;

    // 拖动期间保存的原始子元素顺序（防止原版 InputListener 的 toFront 破坏顺序）
    private static List<Element> savedChildrenOrder = null;

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
                setup();
                initialized = true;
                Log.info("[LogicAssist] BoxSelect initialized (stage overlay mode).");
            }

            LogicDialog dialog = Vars.ui.logic;
            if(dialog != null && !dialog.isShown() && state != State.IDLE){
                resetState();
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
            // pollMouse 已移至 overlay.update()（draw 之前执行）

        }catch(Exception e){
            Log.info("[LogicAssist] BoxSelect tick error: " + e);
        }
    }

    private static void setup(){
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
            // pollMouse 在 update（act 阶段，draw 之前）执行，
            // 确保状态转换和 dragging 清理在 layout 之前完成，避免首帧闪烁
            try{
                LCanvas canvas = getCanvas();
                if(canvas != null && initialized){
                    pollMouse(canvas);
                }
            }catch(Exception e){
                Log.info("[LogicAssist] BoxSelect update-poll error: " + e);
            }
        });
    }

    // ==================================================================
    // 鼠标轮询
    // ==================================================================

    private static void pollMouse(LCanvas canvas){
        boolean curMouseDown = Core.input.keyDown(KeyCode.mouseLeft);
        boolean justPressed = curMouseDown && !prevMouseDown;
        boolean justReleased = !curMouseDown && prevMouseDown;
        boolean rightJustPressed = Core.input.keyTap(KeyCode.mouseRight);
        boolean ctrlDown = Core.input.keyDown(KeyCode.controlLeft);

        prevMouseDown = curMouseDown;

        LogicDialog dialog = Vars.ui.logic;
        if(dialog == null || !dialog.isShown()){
            mousePressed = false;
            return;
        }

        // 在 SELECTED 状态下持续保存 children 顺序（跳过 justPressed 帧），
        // 确保拖动开始时保存的是原版 InputListener toFront() 之前的正确顺序
        if(state == State.SELECTED && !justPressed){
            // 检测选中积木是否被删除（parent 为 null 说明已从 children 移除）
            // 使用迭代器安全移除
            selected.removeIf(elem -> elem.parent != canvas.statements);
            if(selected.isEmpty()){
                clearSelection();
            }else{
                saveChildrenOrder(canvas);
            }
        }

        // 鼠标 stage 坐标
        float mx = Core.scene.screenToStageCoordinates(Tmp.v1.set(Core.input.mouseX(), Core.input.mouseY())).x;
        float my = Tmp.v1.y;

        // 右键或 Esc 取消拖动
        if((rightJustPressed || Core.input.keyTap(KeyCode.escape)) &&
           (state == State.DRAGGING_MOVE || state == State.DRAGGING_COPY)){
            cancelDrag(canvas);
            return;
        }

        // 拖动中：更新积木 translation 和插入位置
        if(state == State.DRAGGING_MOVE || state == State.DRAGGING_COPY){
            // 每帧清除原版 dragging（防止 layout 跳过选中积木导致错位）
            clearDraggingField(canvas);
            // 恢复原始 children 顺序（撤销原版 InputListener touchDown 的 toFront）
            restoreChildrenOrder(canvas);
            // 每帧 invalidate + validate 强制重新 layout（重置所有位置，防止 applyInsertShift 累积）
            canvas.statements.invalidate();
            canvas.statements.validate();

            // 移动模式和复制模式都跳过选中积木布局（它们用 translation 跟随鼠标，不占原位）
            // 复制模式与移动模式在拖动期间行为一致，区别仅在释放时：
            //   - 移动：移除原积木并重新插入到新位置
            //   - 复制：保留原积木，在插入位置创建副本
            // 之前复制模式不跳过选中积木且不设 translation，导致原积木留在原位，
            // 插入阴影与原积木重叠时显示错误
            relayoutNonSelected(canvas);

            // 用 DragLayout 本地坐标系计算 dx/dy，这样滚动 pane 时偏移自动补偿
            // 原版 InputListener 用 localToParentCoordinates 增量计算 translation，原理相同：
            // 本地坐标系随 pane 滚动而变化，所以本地坐标的增量就是积木应该移动的距离
            Vec2 localMouse = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(mx, my));
            float dx = localMouse.x - dragStartLocalX;
            float dy = localMouse.y - dragStartLocalY;

            // 选中积木跟随鼠标（translation），移动和复制模式一致
            for(StatementElem elem : selected){
                elem.setTranslation(dx, dy);
            }

            // 计算插入位置（只遍历非选中积木，返回非选中积木列表中的索引）
            int newInsertPos = computeInsertPosition(canvas, my);
            if(newInsertPos != dragInsertPos){
                dragInsertPos = newInsertPos;
                Log.info("[LogicAssist] Insert pos changed to " + dragInsertPos + " (mouseY=" + my + ")");
            }

            // 手动腾位：把插入位置下方的非选中积木下移（模拟原版 DragLayout.layout() 的腾位逻辑）
            applyInsertShift(canvas);
            // 强制更新跳转线位置（JumpCurve.act 在 Stage.act 阶段执行，在 pollMouse 之前，
            // 使用的是上一帧的位置；applyInsertShift 改变了积木位置后需要重新计算跳转线）
            canvas.statements.jumps.act(0f);
            // 计算指示器几何（在腾位之后，基于实际位置）
            updateIndicatorGeometry(canvas);

            // 不调用 toFront()！
            // toFront() → setZIndex() → childrenChanged() → invalidateHierarchy() → invalidate()
            // 会在 draw() 阶段触发 WidgetGroup.draw() → validate() → layout()，重新布局所有积木，
            // 撤销我们手动设置的 relayoutNonSelected + applyInsertShift。
            // 而 jumps.act(0f) 计算的跳转线位置是基于腾位后的位置，与 layout 后的实际位置不匹配，
            // 导致跳转线指向错误坐标 → 产生幽灵跳转线。
            // 选中积木已通过 translation 移开原位，与非选中积木重叠概率低，z-order 问题可接受。
        }

        if(justPressed){
            Log.info("[LogicAssist] Mouse pressed at " + mx + "," + my + " state=" + state);

            boolean onStatement = isMouseOnStatement(canvas);
            boolean onToolbar = isMouseOnToolbar();
            boolean onSelectedStatement = onStatement && isMouseOnSelectedStatement(canvas);

            if(onToolbar){
                mousePressed = false;
                return;
            }

            // SELECTED 状态：点击选中积木 → 开始拖动
            if(state == State.SELECTED && onSelectedStatement){
                dragStartMouseX = mx;
                dragStartMouseY = my;
                // 记录鼠标在 DragLayout 本地坐标系中的起始位置（用于计算 translation，补偿滚动偏移）
                Vec2 startLocal = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(mx, my));
                dragStartLocalX = startLocal.x;
                dragStartLocalY = startLocal.y;
                dragInsertPos = -1;
                dragMoved = false;
                mousePressed = true;

                // 清除原版 InputListener 设置的 dragging 字段（防止 layout 跳过/错位）
                clearDraggingField(canvas);
                // savedChildrenOrder 已在 SELECTED 状态下持续保存，无需在此重复保存

                // 根据 dragMode 决定移动还是复制（Ctrl 键仍然可用，优先级高于 dragMode）
                if(ctrlDown || dragMode == DragMode.COPY){
                    // 复制模式
                    prepareCopyData(canvas);
                    state = State.DRAGGING_COPY;
                }else{
                    // 直接拖动 = 移动
                // 不调用 toFront()：会重排 children 数组导致 layout 把选中积木定位到底部
                state = State.DRAGGING_MOVE;
            }
            // 恢复原始 children 顺序（撤销原版 InputListener touchDown 的 toFront()）
            restoreChildrenOrder(canvas);
            // invalidate + validate + relayoutNonSelected 重置位置（与 DRAGGING 分支一致）
            canvas.statements.invalidate();
            canvas.statements.validate();
            relayoutNonSelected(canvas);
            // 不调用 toFront()：会触发 invalidate，导致 draw 时 layout 撤销手动布局
            // 重置 translation（防止之前操作的残留，也清除 InputListener touchDragged 的增量）
            for(StatementElem elem : selected){
                elem.setTranslation(0, 0);
            }
                hideToolbar();
                Log.info("[LogicAssist] Started dragging " + selected.size() + " blocks, mode=" + state);
                return;
            }

            // SELECTED 状态：点击非选中积木或空白 → 清空选中，开始新框选
            if(state == State.SELECTED){
                clearSelection();
            }

            if(onStatement){
                mousePressed = false;
                return;
            }

            // 开始框选
            selStartX = mx;
            selStartY = my;
            selCurX = mx;
            selCurY = my;
            // 转换为 DragLayout 本地坐标系（解决滚动时框选不跟随的问题）
            // 本地坐标固定在内容上，滚动 pane 时框选框跟随内容移动
            Vec2 startLocal = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(mx, my));
            selStartLocalX = startLocal.x;
            selStartLocalY = startLocal.y;
            selCurLocalX = startLocal.x;
            selCurLocalY = startLocal.y;
            dragMoved = false;
            mousePressed = true;
            state = State.SELECTING;

        }else if(curMouseDown && mousePressed && state == State.SELECTING){
            selCurX = mx;
            selCurY = my;
            // 每帧更新本地坐标（滚动时同一屏幕位置对应不同的本地坐标）
            Vec2 curLocal = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(mx, my));
            selCurLocalX = curLocal.x;
            selCurLocalY = curLocal.y;
            float dx = Math.abs(selCurX - selStartX);
            float dy = Math.abs(selCurY - selStartY);
            if(dx > MIN_DRAG_DIST || dy > MIN_DRAG_DIST){
                dragMoved = true;
                updateSelection(canvas);
            }
            // 自动滚动：鼠标接近屏幕顶部/底部时自动滚动 pane，允许框选视口外的积木
            autoScroll(canvas);

        }else if(curMouseDown && mousePressed && (state == State.DRAGGING_MOVE || state == State.DRAGGING_COPY)){
            // 拖动中，检查是否真的移动了
            float dx = Math.abs(mx - dragStartMouseX);
            float dy = Math.abs(my - dragStartMouseY);
            if(dx > MIN_DRAG_DIST || dy > MIN_DRAG_DIST){
                dragMoved = true;
            }

        }else if(justReleased && mousePressed){
            if(state == State.SELECTING){
                // 框选释放
                if(!dragMoved){
                    if(!selected.isEmpty()){
                        clearSelection();
                    }
                    state = State.IDLE;
                }else{
                    if(!selected.isEmpty()){
                        state = State.SELECTED;
                        showToolbar(canvas);
                        saveChildrenOrder(canvas);
                    }else{
                        state = State.IDLE;
                    }
                }
            }else if(state == State.DRAGGING_MOVE){
                // 移动释放
                if(dragMoved && dragInsertPos >= 0){
                    executeDragMove(canvas, dragInsertPos);
                }else{
                    // 没有真正拖动，取消 translation
                    cancelDrag(canvas);
                }
            }else if(state == State.DRAGGING_COPY){
                // 复制释放
                if(dragMoved && dragInsertPos >= 0){
                    executeDragCopy(canvas, dragInsertPos);
                }else{
                    cancelDrag(canvas);
                }
            }
            mousePressed = false;
        }
    }

    // ==================================================================
    // 鼠标位置工具方法
    // ==================================================================

    private static boolean isMouseOnToolbar(){
        if(toolbar == null) return false;
        Vec2 stage = Core.scene.screenToStageCoordinates(Tmp.v1.set(Core.input.mouseX(), Core.input.mouseY()));
        Element hit = Core.scene.hit(stage.x, stage.y, true);
        if(hit == null) return false;
        Element current = hit;
        while(current != null){
            if(current == toolbar) return true;
            current = current.parent;
        }
        return false;
    }

    private static boolean isMouseOnStatement(LCanvas canvas){
        try{
            Vec2 stage = Core.scene.screenToStageCoordinates(Tmp.v1.set(Core.input.mouseX(), Core.input.mouseY()));
            Vec2 canvasLocal = canvas.stageToLocalCoordinates(Tmp.v2.set(stage.x, stage.y));
            Element hit = canvas.hit(canvasLocal.x, canvasLocal.y, true);
            if(hit == null) return false;
            Element current = hit;
            while(current != null){
                if(current instanceof StatementElem) return true;
                current = current.parent;
            }
            return false;
        }catch(Exception e){
            return false;
        }
    }

    /** 检查鼠标是否在选中的积木上 */
    private static boolean isMouseOnSelectedStatement(LCanvas canvas){
        try{
            Vec2 stage = Core.scene.screenToStageCoordinates(Tmp.v1.set(Core.input.mouseX(), Core.input.mouseY()));
            Vec2 canvasLocal = canvas.stageToLocalCoordinates(Tmp.v2.set(stage.x, stage.y));
            Element hit = canvas.hit(canvasLocal.x, canvasLocal.y, true);
            if(hit == null) return false;
            Element current = hit;
            while(current != null){
                if(current instanceof StatementElem && selected.contains(current)) return true;
                current = current.parent;
            }
            return false;
        }catch(Exception e){
            return false;
        }
    }

    // ==================================================================
    // 框选逻辑
    // ==================================================================

    private static void updateSelection(LCanvas canvas){
        selected.clear();
        // 使用 DragLayout 本地坐标系进行命中判定
        // child.x/y/width/height 都是本地坐标，无需转换
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

    /** 框选时自动滚动：鼠标接近屏幕顶部/底部时自动滚动 pane
     *  参考原版 LCanvas.act() 的自动滚动逻辑，但用屏幕边缘判定而非 LCanvas 边缘，
     *  确保在各种对话框布局下都能正常工作 */
    private static void autoScroll(LCanvas canvas){
        if(canvas.pane == null) return;
        float mouseY = Core.input.mouseY();
        float screenH = Core.graphics.getHeight();
        float margin = Scl.scl(80f);
        float speed = Scl.scl(15f) * Time.delta;

        // mouseY = 0 是屏幕底部，mouseY = screenH 是屏幕顶部
        // 鼠标接近底部 → 向下滚动（scrollY 增大，看到更下方的积木）
        // 鼠标接近顶部 → 向上滚动（scrollY 减小，看到更上方的积木）
        if(mouseY < margin){
            canvas.pane.setScrollY(canvas.pane.getScrollY() + speed);
        }else if(mouseY > screenH - margin){
            canvas.pane.setScrollY(canvas.pane.getScrollY() - speed);
        }
    }

    private static void clearSelection(){
        selected.clear();
        state = State.IDLE;
        hideToolbar();
    }

    private static void resetState(){
        // 取消所有 translation
        LCanvas canvas = getCanvas();
        clearDraggingField(canvas);
        // 恢复原始 children 顺序（撤销拖动期间的 toFront）
        restoreChildrenOrder(canvas);
        savedChildrenOrder = null;
        for(StatementElem elem : selected){
            elem.setTranslation(0, 0);
        }
        selected.clear();
        state = State.IDLE;
        hideToolbar();
        dragInsertPos = -1;
        dragMoved = false;
        mousePressed = false;
        clipboardData = null;
        clipboardSize = 0;
        clipboardSelectedIndices = null;
    }

    // ==================================================================
    // 插入位置计算（用所有积木的原始位置，不排除选中积木）
    // 关键：用 child.y（本地坐标，不含 translation）来比较，
    // 这样选中积木的 translation 偏移不会干扰插入位置计算
    // ==================================================================

    /** 计算插入位置（基于鼠标 y，只遍历非选中积木）
     *  返回值是非选中积木列表中的索引（0=最顶部，非选中积木数=最底部）
     *  参考原版 DragLayout.layout() 的 insertPosition 计算：跳过 dragging 积木 */
    private static int computeInsertPosition(LCanvas canvas, float stageY){
        Seq<Element> children = canvas.statements.getChildren();
        if(children.isEmpty()) return 0;

        Vec2 local = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(0, stageY));
        float localY = local.y;

        // 只遍历非选中积木（模拟原版 dragging 跳过逻辑）
        // 非选中积木从上到下排列，y 从大到小（y 轴向上）
        int insertPos = 0;
        int nonSelectedCount = 0;
        for(Element child : children){
            if(child instanceof StatementElem && selected.contains(child)){
                continue;
            }
            float centerLocalY = child.y + child.getHeight() / 2f;
            if(localY > centerLocalY){
                // 鼠标在当前积木上方（localY 更大），插入到它之前
                return nonSelectedCount;
            }
            nonSelectedCount++;
        }
        // 鼠标在所有非选中积木下方，插入到最后
        return nonSelectedCount;
    }

    /** 把非选中积木列表索引转换为完整 children 索引 */
    private static int nonSelectedToChildIndex(LCanvas canvas, int nonSelectedIndex){
        Seq<Element> children = canvas.statements.getChildren();
        int nonSelectedCount = 0;
        for(int i = 0; i < children.size; i++){
            Element child = children.get(i);
            if(child instanceof StatementElem && selected.contains(child)){
                continue;
            }
            if(nonSelectedCount == nonSelectedIndex){
                return i;
            }
            nonSelectedCount++;
        }
        return children.size;
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
                // 移动模式：先画插入指示器（阴影），再重画选中积木在阴影上方
                drawInsertIndicator(canvas);
                redrawSelectedBlocksOnTop(canvas);
                break;
            case DRAGGING_COPY:
                // 复制模式：与移动模式一致，先画插入指示器（阴影），再重画选中积木在阴影上方
                // 之前不调用 redrawSelectedBlocksOnTop，导致选中积木被阴影覆盖或位置错误
                drawInsertIndicator(canvas);
                redrawSelectedBlocksOnTop(canvas);
                break;
            default:
                break;
        }
    }

    private static void drawSelectionBox(LCanvas canvas){
        // 使用本地坐标计算选区，再转换为 stage 坐标绘制
        // 这样滚动 pane 时选区框跟随内容移动，而非固定在屏幕上
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

        // Mindustry 风格：半透明填充 + 边框
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
        // 只用边框高亮，不要实心填充
        Lines.stroke(Scl.scl(3f), Pal.accent);
        for(StatementElem elem : selected){
            Vec2 v = elem.localToStageCoordinates(Tmp.v1.set(0, 0));
            float pad = Scl.scl(4f);
            Lines.rect(v.x - pad, v.y - pad,
                       elem.getWidth() + pad * 2, elem.getHeight() + pad * 2);
        }
        Draw.reset();
    }

    /** 手动重新布局非选中积木（跳过选中积木，模拟原版 DragLayout.layout() 的 dragging 跳过逻辑）
     *  在 validate() 之后调用，因为 validate 把选中积木也布局了，导致非选中积木的 y 偏高
     *  抄自原版 layout() 第 221-234 行，把 `dragging == e` 改为 `selected.contains(e)` */
    private static void relayoutNonSelected(LCanvas canvas){
        Seq<Element> children = canvas.statements.getChildren();
        float space = Scl.scl(10f);
        float width = canvas.statements.getWidth();

        // 计算总高度（包含选中积木，因为它们要插回去，保持总高度不变）
        float totalHeight = 0;
        for(Element e : children){
            totalHeight += e.getPrefHeight() + space;
        }

        // 布局非选中积木（跳过选中积木），cy 只累加非选中积木的高度
        float cy = 0;
        for(Element e : children){
            if(e instanceof StatementElem && selected.contains(e)) continue;

            e.setSize(width, e.getPrefHeight());
            e.setPosition(0, totalHeight - cy, Align.topLeft);

            cy += e.getPrefHeight() + space;
        }
    }

    /** 手动腾位：把插入位置下方的非选中积木下移（模拟原版 DragLayout.layout() 第 252-257 行）
     *  在 validate() 之后调用，此时所有积木在原位，需要手动为选中积木腾出空间 */
    private static void applyInsertShift(LCanvas canvas){
        if(dragInsertPos < 0) return;
        Seq<Element> children = canvas.statements.getChildren();
        float space = Scl.scl(10f);

        // 计算选中积木总高度（需要腾出的空间）
        float shiftAmount = 0;
        for(StatementElem elem : selected){
            shiftAmount += elem.getHeight() + space;
        }
        shiftAmount -= space; // 最后一个不需要额外 space

        // 把非选中积木列表中 dragInsertPos 及之后的积木下移
        int nonSelectedIndex = 0;
        for(Element child : children){
            if(child instanceof StatementElem && selected.contains(child)){
                continue;
            }
            if(nonSelectedIndex >= dragInsertPos){
                child.y -= shiftAmount;
            }
            nonSelectedIndex++;
        }
    }

    /** 在腾位之后计算并存储插入指示器的位置和尺寸（必须在 toFront 之前调用）
     *  参考原版 DragLayout.draw() 的指示器计算：
     *    insertPosition == 0 → lastY = height + y（内容区顶部）
     *    insertPosition > 0  → lastY = seq.get(insertPosition-1).y + y - space（上方积木底部减间距）
     *  指示器高度 = 选中积木总高度 totalH，从 lastY-totalH 画到 lastY。
     *
     *  之前的 bug：
     *   1. dragInsertPos==0 时用 first.y+first.getHeight()，但 applyInsertShift 已把 first 下移了 totalH，
     *      导致 insertLocalY 偏低 totalH；再减 totalH 后偏低 2*totalH。
     *   2. 中间/底部插入时漏减 space，指示器与上方积木重叠。
     *   3. 空列表时 insertLocalY=0（底部），应在顶部。
     */
    private static void updateIndicatorGeometry(LCanvas canvas){
        if(dragInsertPos < 0) return;

        Seq<Element> children = canvas.statements.getChildren();
        float paneWidth = canvas.statements.getWidth();
        float space = Scl.scl(10f);

        // 计算选中积木总高度（含间距，最后一个不加 space）
        float totalH = 0;
        for(StatementElem elem : selected){
            totalH += elem.getHeight() + space;
        }
        totalH -= space;

        // 构建非选中积木列表（此时已腾位，y 已更新）
        List<Element> nonSelected = new ArrayList<>();
        for(Element child : children){
            if(child instanceof StatementElem && selected.contains(child)){
                continue;
            }
            nonSelected.add(child);
        }

        // 计算指示器顶部在 DragLayout 本地坐标系中的 y
        // 原版：insertPosition==0 → height；insertPosition>0 → seq.get(pos-1).y - space
        float insertLocalY;
        float drawLocalX = 0;

        if(nonSelected.isEmpty() || dragInsertPos == 0){
            // 插入到最顶部：指示器顶部 = 内容区顶部
            insertLocalY = canvas.statements.getHeight();
        }else if(dragInsertPos >= nonSelected.size()){
            // 插入到最后：最后一个非选中积木下方，留 space 间距
            Element last = nonSelected.get(nonSelected.size() - 1);
            insertLocalY = last.y - space;
            drawLocalX = last.x;
        }else{
            // 插入到中间：上方积木底部下方，留 space 间距
            Element before = nonSelected.get(dragInsertPos - 1);
            insertLocalY = before.y - space;
            drawLocalX = before.x;
        }

        // 转换为 stage 坐标：insertLocalY 是指示器顶部，指示器从 top-totalH 画到 top
        Vec2 stagePos = canvas.statements.localToStageCoordinates(Tmp.v1.set(drawLocalX, insertLocalY));
        indicatorX = stagePos.x;
        indicatorY = stagePos.y - totalH; // 指示器底部
        indicatorW = paneWidth;
        indicatorH = totalH;
    }

    /** 绘制插入位置指示器（使用 act 阶段存储的几何位置）
     *  参考原版 DragLayout.draw()：直接用 Tex.pane.draw，不额外设置颜色和透明度 */
    private static void drawInsertIndicator(LCanvas canvas){
        if(dragInsertPos < 0) return;
        Draw.reset();
        Tex.pane.draw(indicatorX, indicatorY, indicatorW, indicatorH);
        Draw.reset();
    }

    /** 在正确的 transform 矩阵内重画选中积木，确保积木画在插入阴影上方。
     *  之前的 redrawSelectedBlocks() 直接调用 elem.draw() 没有经过 DragLayout 的 transform
     *  矩阵（setTransform(true)），导致积木在错误位置绘制。
     *
     *  正确做法：保存当前 batch transform，设置 DragLayout 的 transform（只有平移，无旋转/缩放），
     *  临时把 child.x/y 加上 translation（模拟 Group.drawChildren() 的行为），
     *  调用 child.draw()，然后恢复一切。
     *
     *  DragLayout 的 transform = 它在 stage 中的位置（x, y），
     *  因为 setTransform(true) 且无 rotation/scale，computeTransform() 只是一个平移矩阵。
     */
    private static void redrawSelectedBlocksOnTop(LCanvas canvas){
        if(selected.isEmpty()) return;

        // 保存当前 batch transform 的副本（Draw.trans() 返回的是内部引用，必须 copy！）
        // 之前的 bug：oldTrans = Draw.trans() 只保存了引用，Draw.trans(dragLayoutTrans)
        // 覆盖了内部矩阵后 oldTrans 也跟着变了，导致恢复时设置的是错误的值，
        // 后续所有渲染（包括背景）都用了错误的 transform → 整个编辑器背景歪了
        Mat oldTrans = new Mat().set(Draw.trans());

        // 计算 DragLayout 的 transform 矩阵（只有平移，无旋转/缩放）
        Vec2 origin = canvas.statements.localToStageCoordinates(Tmp.v1.set(0, 0));
        Mat dragLayoutTrans = new Mat();
        dragLayoutTrans.idt();
        dragLayoutTrans.setToTranslation(origin.x, origin.y);
        Draw.trans(dragLayoutTrans);

        // 在 DragLayout transform 内重画选中积木
        // 模拟 Group.drawChildren() 的 transform 分支：
        //   child.x += child.translation.x; child.y += child.translation.y;
        //   child.draw();
        //   child.x -= child.translation.x; child.y -= child.translation.y;
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

        // 恢复 batch transform（用副本，确保恢复的是原始值）
        Draw.trans(oldTrans);
    }

    // ==================================================================
    // 浮动工具栏
    // ==================================================================

    /** 显示选中后的模式切换按钮（替代原取消按钮）
     *  两个按钮：
     *  - 模式切换（移动/复制）：点击切换 dragMode，按钮文字显示当前模式
     *  - 取消选中：清空选中状态 */
    private static void showToolbar(LCanvas canvas){
        hideToolbar();
        toolbar = new Table(Tex.buttonTrans);
        toolbar.margin(6);
        toolbar.defaults().size(90, 34).padRight(4);

        // 模式切换按钮（动态更新文字和图标）
        TextButton modeBtn = new TextButton(dragMode == DragMode.MOVE ? "@la.move" : "@la.copy");
        modeBtn.clicked(() -> {
            dragMode = (dragMode == DragMode.MOVE) ? DragMode.COPY : DragMode.MOVE;
            modeBtn.setText(dragMode == DragMode.MOVE ? "@la.move" : "@la.copy");
        });
        toolbar.add(modeBtn);

        // 取消选中按钮
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
        // 清除原版 dragging（防止 touchUp 的 finishLayout 干扰）
        clearDraggingField(canvas);
        // 恢复原始 children 顺序（撤销拖动期间的 toFront，确保 getSortedSelected 和移除操作正确）
        restoreChildrenOrder(canvas);
        savedChildrenOrder = null;

        // 重置 translation
        for(StatementElem elem : selected){
            elem.setTranslation(0, 0);
        }

        List<StatementElem> sorted = getSortedSelected(canvas);
        Seq<Element> children = canvas.statements.getChildren();
        int count = sorted.size();

        // 直接移动 StatementElem 对象（不使用 save/read 重建）
        // 这样 JumpStatement.dest 引用保持有效，只需调用 saveUI() 更新 destIndex
        // [MI2-Utilities] 参考 LogicHelperMindow.doCutPaste():
        //   dragging.remove() + stats.addChildAt(pasteStart, dragging)
        //   而非 save/read 重建对象，保留 dest 引用

        // 移除所有选中积木（elem.remove() → parent.removeChild() → childrenChanged()）
        for(StatementElem elem : sorted){
            elem.remove();
        }

        // 移除后 children 只剩非选中积木，insertPos 是非选中列表索引，直接对应 children 索引
        int actualInsert = Math.max(0, Math.min(insertPos, children.size));

        // 重新插入到目标位置
        for(int i = 0; i < count; i++){
            canvas.statements.addChildAt(actualInsert + i, sorted.get(i));
        }

        // 强制 layout 更新行号（updateAddress）和跳转高度
        canvas.statements.updateJumpHeights = true;
        canvas.statements.invalidate();
        canvas.statements.validate();

        // 更新所有 JumpStatement 的 destIndex
        // 由于直接移动了对象（非重建），dest 引用仍然有效，
        // saveUI() 会根据 dest 的当前 index 重新计算 destIndex
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
        saveChildrenOrder(canvas);
        Log.info("[LogicAssist] Drag-moved " + count + " blocks to position " + actualInsert);
    }

    // ==================================================================
    // 拖动复制
    // ==================================================================

    private static void prepareCopyData(LCanvas canvas){
        List<StatementElem> sorted = getSortedSelected(canvas);
        Seq<Element> children = canvas.statements.getChildren();
        int minIndex = children.indexOf(sorted.get(0), true);
        int maxIndex = children.indexOf(sorted.get(sorted.size() - 1), true);

        // 记录每个选中积木在 children 中的原始索引（用于复制后正确映射跳转目标）
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
        clipboardMinIndex = minIndex;
        clipboardMaxIndex = maxIndex;
    }

    private static void executeDragCopy(LCanvas canvas, int insertPos){
        // 清除原版 dragging（防止 touchUp 的 finishLayout 干扰）
        clearDraggingField(canvas);
        // 恢复原始 children 顺序（撤销拖动期间的 toFront）
        restoreChildrenOrder(canvas);
        savedChildrenOrder = null;

        // 重置 translation（原积木回到原位）
        for(StatementElem elem : selected){
            elem.setTranslation(0, 0);
        }

        if(clipboardData == null || clipboardData.isEmpty()){
            state = State.SELECTED;
            showToolbar(canvas);
            saveChildrenOrder(canvas);
            return;
        }

        int currentSize = canvas.statements.getChildren().size;
        if(currentSize + clipboardSize > LExecutor.maxInstructions){
            Log.info("[LogicAssist] Copy aborted: would exceed maxInstructions");
            state = State.SELECTED;
            showToolbar(canvas);
            saveChildrenOrder(canvas);
            return;
        }

        boolean privileged = isPrivileged(canvas);
        Seq<LStatement> copies = LAssembler.read(clipboardData, privileged);
        if(copies.isEmpty()){
            state = State.SELECTED;
            showToolbar(canvas);
            saveChildrenOrder(canvas);
            return;
        }

        // insertPos 是非选中积木列表中的索引，转换为完整 children 索引
        int actualInsert = nonSelectedToChildIndex(canvas, insertPos);

        // 调整复制出的 JumpStatement 的 destIndex
        // [MI2-Utilities] 参考 LogicHelperMindow.doCutPaste() 的 transJump 部分:
        //   移动后重新计算 JumpStatement.destIndex: jp.destIndex = se.index + delta
        //   本项目扩展为支持多积木批量复制，分三种情况处理 destIndex
        // 三种情况：
        // 1. 跳转目标在选中范围内（被复制了）→ 指向对应的副本
        // 2. 跳转目标不在选中范围内，且在插入点之后 → 索引偏移 +clipboardSize
        // 3. 跳转目标不在选中范围内，且在插入点之前 → 索引不变
        for(LStatement st : copies){
            if(st instanceof JumpStatement js && js.destIndex != -1){
                int oldDest = js.destIndex;
                // 检查目标是否是选中积木（被复制的积木）
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
                    // 情况1：目标被复制了，指向对应的副本
                    js.destIndex = actualInsert + selectedPos;
                }else if(oldDest >= actualInsert){
                    // 情况2：目标在插入点之后，索引偏移
                    js.destIndex = oldDest + clipboardSize;
                }
                // 情况3：目标在插入点之前，索引不变（else 分支，不处理）
            }
        }

        for(int i = 0; i < copies.size; i++){
            canvas.addAt(actualInsert + i, copies.get(i));
            copies.get(i).setupUI();
        }

        canvas.statements.updateJumpHeights = true;
        // 两次 invalidate + validate 确保布局完全稳定：
        // 第一次 validate → layout() 检测到总高度变化（新增副本），
        //   更新 height 并调用 invalidateHierarchy()，但 validate() 随即清除 invalidated 标志
        // 第二次 invalidate + validate 用更新后的 height 重新布局所有积木，
        //   确保原积木位置正确（之前只 validate 一次时原积木可能基于旧高度计算位置）
        canvas.statements.invalidate();
        canvas.statements.validate();
        canvas.statements.invalidate();
        canvas.statements.validate();

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
        saveChildrenOrder(canvas);
        Log.info("[LogicAssist] Drag-copied " + copies.size + " blocks to position " + insertPos);
    }

    /** 取消拖动 */
    private static void cancelDrag(LCanvas canvas){
        clearDraggingField(canvas);
        // 恢复原始 children 顺序（撤销拖动期间的 toFront）
        restoreChildrenOrder(canvas);
        savedChildrenOrder = null;

        // 重置 translation
        for(StatementElem elem : selected){
            elem.setTranslation(0, 0);
        }
        clipboardData = null;
        clipboardSize = 0;
        clipboardSelectedIndices = null;
        dragInsertPos = -1;
        state = State.SELECTED;
        showToolbar(canvas);
        saveChildrenOrder(canvas);
        Log.info("[LogicAssist] Drag cancelled.");
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

    /** 通过反射清除 LCanvas.dragging 字段，防止原版 InputListener 的拖拽机制干扰多选拖动 */
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

    /** 保存当前 children 顺序（在 SELECTED 状态下每帧调用，复用 list 避免每帧分配） */
    private static void saveChildrenOrder(LCanvas canvas){
        if(savedChildrenOrder == null){
            savedChildrenOrder = new ArrayList<>();
        }
        savedChildrenOrder.clear();
        for(Element e : canvas.statements.getChildren()){
            savedChildrenOrder.add(e);
        }
    }

    /** 恢复原始 children 顺序（每帧调用，撤销原版 InputListener 的 toFront） */
    private static void restoreChildrenOrder(LCanvas canvas){
        if(savedChildrenOrder == null) return;
        Seq<Element> children = canvas.statements.getChildren();
        if(children.size != savedChildrenOrder.size()) return; // 数量变化，跳过
        for(int i = 0; i < savedChildrenOrder.size(); i++){
            Element saved = savedChildrenOrder.get(i);
            if(children.get(i) != saved){
                saved.setZIndex(i);
            }
        }
    }
}
