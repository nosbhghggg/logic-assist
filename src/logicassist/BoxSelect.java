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
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * 框选功能：批量选择、复制、移动、删除积木（事件驱动架构）。
 *
 * 通过 Core.scene.addCaptureListener 在捕获阶段拦截事件，
 * event.stop() 阻止原版 StatementElem.InputListener 收到事件。
 * 选中积木后接管其功能按钮（删除→批量删除，+→批量复制，复制图标→模式切换）。
 *
 * 致谢：MI2-Utilities (https://github.com/BlackDeluxeCat/MI2-Utilities-Java)
 * 拖动移动和跳转索引转换逻辑参考自 src/mi2u/ui/LogicHelperMindow.java
 */
public class BoxSelect{

    private static final float MIN_DRAG_DIST = 8f;
    private static final float SCROLLBAR_WIDTH = 14f;
    // 滚动条触发区域向左扩展的容差，让点击更容易命中
    private static final float SCROLLBAR_HIT_PADDING = 20f;
    private static final float AUTOSCROLL_MARGIN = 80f;
    private static final float AUTOSCROLL_SPEED = 15f;
    private static final float FILL_ALPHA = 0.15f;
    private static final float BORDER_ALPHA = 0.8f;
    private static final float COPY_PREVIEW_ALPHA = 0.5f;
    private static final float SCROLLBAR_SEG_ALPHA = 0.35f;
    private static final Mat tmpMat = new Mat();
    private static final Mat tmpMat2 = new Mat();

    public static boolean isScrollbarEnabled(){
        return Core.settings.getBool(JumpLineColor.SETTING_SCROLLBAR, true);
    }

    /** 拖动积木时悬浮滚动条快速跳转：与自定义滚动条共用同一开关 */
    public static boolean isScrollbarJumpEnabled(){
        return isScrollbarEnabled();
    }

    private static final Field draggingField;
    private static Field selectingField;
    static Field needsLayoutField;
    static{
        try{
            draggingField = LCanvas.class.getDeclaredField("dragging");
            draggingField.setAccessible(true);
            needsLayoutField = arc.scene.ui.layout.WidgetGroup.class.getDeclaredField("needsLayout");
            needsLayoutField.setAccessible(true);
        }catch(Exception e){
            Log.err("[LogicAssist] Failed to access LCanvas fields", e);
            throw new RuntimeException(e);
        }
        try{
            selectingField = LCanvas.JumpButton.class.getDeclaredField("selecting");
            selectingField.setAccessible(true);
        }catch(Exception e){
            Log.warn("[LogicAssist] Failed to access JumpButton.selecting field", e);
        }
    }

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

    private static float dragStartMouseX, dragStartMouseY;
    private static float dragStartLocalX, dragStartLocalY;
    private static int dragInsertPos = -1;

    // 拖动期间保存的原始 child.y（用于恢复 layout() 的修改）
    private static float[] dragBaseYs = null;

    private static float indicatorX, indicatorY, indicatorW, indicatorH;

    // 复制用剪贴板（用 copy() 保持 ExprStatement 折叠状态，不经过 write+read）
    private static List<LStatement> clipboardCopies = null;
    private static int clipboardSize = 0;
    private static int[] clipboardSelectedIndices = null;

    private static Element overlay;
    private static boolean initialized = false;
    private static InputListener captureListener;

    private static Field vScrollBoundsField;
    private static Field vKnobBoundsField;
    private static Field flingTimerField;

    /** 悬浮跳转提示：0=正常, 1=最粗（直接切换，不做插值动画） */
    private static float hoverJumpAnim = 0f;
    /** 拖动时滚动条向左扩展倍率，最终宽度 = 原宽度 × (1 + 此值) */
    private static final float HOVER_JUMP_EXPAND_RATIO = 0.8f;

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

    private static void setup(LCanvas canvas){
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
            // 不用 visible 控制显示——visible=false 会导致 act() 不执行，update() 不调用
            overlay.setSize(Core.graphics.getWidth(), Core.graphics.getHeight());
            // 仅在需要绘制覆盖层时 toFront，避免干扰 MindustryX 等第三方 UI 层级
            // SELECTED/IDLE 状态由 LogicCanvas.draw() 绘制
            if(state == State.SELECTING || state == State.DRAGGING_MOVE || state == State.DRAGGING_COPY){
                overlay.toFront();
            }

            // 拖拽期间每帧重算插入指示器位置（滚轮滚动时 touchDragged 不触发）
            if(state == State.DRAGGING_MOVE || state == State.DRAGGING_COPY){
                LCanvas c = getCanvas();
                if(c != null){
                    float mx = Core.input.mouseX();
                    float my = Core.input.mouseY();
                    updateDrag(c, mx, my);
                    // 悬浮滚动条时跳过边缘自动滚动，由 updateScrollbarHoverJump 接管
                    if(!(isScrollbarJumpEnabled() && c.pane != null && isScrollbarHit(c.pane, mx, my))){
                        autoScroll(c);
                    }
                }
            }

            // 拖动积木时悬浮滚动条快速跳转（原版拖动和 BoxSelect 拖动均支持）
            updateScrollbarHoverJump();
        });
    }

    private static boolean shouldIntercept(LCanvas canvas){
        LogicDialog dialog = Vars.ui.logic;
        return dialog != null && dialog.isShown() && canvas != null && canvas.statements != null;
    }

    // 点击是否落在按钮图标上（Image = 按钮图标），放行给原版处理
    private static boolean isClickOnButton(Element target){
        return target instanceof Image;
    }

    // 判断元素是否是 canvas（LCanvas）的后代。
    // 返回按钮、变量按钮等在 LogicDialog.buttons 区，不在 canvas 内。
    // 只有 canvas 内的空白区才允许框选。
    private static boolean isDescendantOfCanvas(Element elem, LCanvas canvas){
        Element current = elem;
        while(current != null){
            if(current == canvas) return true;
            current = current.parent;
        }
        return false;
    }

    // 兜底检测：遍历 target 所在 StatementElem 的子树，检查点击是否落在 Button 边界内。
    // 避免 MindustryX 的 JUMP/pencil 等按钮因 hit test 未命中内部元素而被误判为框选/拖动。
    private static boolean isClickWithinButtonBounds(Element target, float stageX, float stageY){
        // 找到 target 所在的 StatementElem
        StatementElem stmt = null;
        Element cur = target;
        while(cur != null){
            if(cur instanceof StatementElem){
                stmt = (StatementElem)cur;
                break;
            }
            cur = cur.parent;
        }
        if(stmt == null) return false;
        return hasButtonAtRecursive(stmt, stageX, stageY);
    }

    private static boolean hasButtonAtRecursive(Element elem, float stageX, float stageY){
        if(elem instanceof Button){
            Vec2 local = elem.stageToLocalCoordinates(Tmp.v2.set(stageX, stageY));
            // 加 2px 容差，避免边缘点击漏判
            if(local.x >= -2f && local.x <= elem.getWidth() + 2f &&
               local.y >= -2f && local.y <= elem.getHeight() + 2f){
                return true;
            }
        }
        if(elem instanceof Group){
            Seq<Element> children = ((Group)elem).getChildren();
            for(int i = 0; i < children.size; i++){
                if(hasButtonAtRecursive(children.get(i), stageX, stageY)) return true;
            }
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

        Vec2 stageCoords = Tmp.v1.set(x, y);
        Element target = event.targetActor;

        // 只处理 canvas 内的点击，MindustryX 左侧面板等非 canvas UI 放行
        if(!isDescendantOfCanvas(target, canvas)){
            return false;
        }

        if(isClickOnButton(target)){
            if(tryHijackButton(canvas, event, target)){
                return true;
            }
            return false;
        }
        // MindustryX 兼容：TextButton（JUMP、注释切换）内部是 Label 而非 Image，
        // 检查祖先链中是否有 Button，有则放行
        Element btnCheck = target;
        while(btnCheck != null && !(btnCheck instanceof Button)) btnCheck = btnCheck.parent;
        if(btnCheck != null){
            return false;
        }
        // 兜底：手动检测 Button 边界（防 hit test 未命中）
        if(isClickWithinButtonBounds(target, stageCoords.x, stageCoords.y)){
            return false;
        }

        // 滚动条点击跳转：使用实际 vScrollBounds 检测命中，加容差让点击更容易
        if(canvas.pane != null && canvas.pane.hasScroll() && isScrollbarHit(canvas.pane, stageCoords.x, stageCoords.y)){
            if(isScrollbarEnabled()){
                boolean handled = handleScrollbarClick(canvas.pane, stageCoords.x, stageCoords.y);
                if(handled) event.stop();
                return handled;
            }
            // 自定义滚动条关闭时，放行给原版 ScrollPane 处理
            return false;
        }

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
                    // 更新选中积木的按钮图标（显示 copy/move 模式）
                    updateSelectedButtonIcons(canvas);
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

    // 尝试接管选中积木上的按钮点击。
    // 如果点击的是选中积木上的按钮（删除/+号/复制），执行批量操作并拦截事件。
    // @return true 如果已拦截，false 如果应放行
    private static boolean tryHijackButton(LCanvas canvas, InputEvent event, Element target){
        if(selected.isEmpty()) return false;

        Element btn = target.parent;
        while(btn != null && !(btn instanceof ImageButton)) btn = btn.parent;
        if(btn == null) return false;
        ImageButton button = (ImageButton)btn;

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

        // 识别按钮：通过 style.imageUp（包括被接管后改过的图标）
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

    private static void updateSelectedButtonIcons(LCanvas canvas){
        Drawable modeIcon = (dragMode == DragMode.MOVE) ? Icon.move : Icon.copy;
        for(StatementElem elem : selected){
            findAndSetIcon(elem, Icon.copy, modeIcon);
            findAndSetIcon(elem, Icon.move, modeIcon);
        }
    }

    private static void findAndSetIcon(StatementElem elem, Drawable oldIcon, Drawable newIcon){
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

    private static void restoreButtonIcons(LCanvas canvas){
        if(canvas == null || canvas.statements == null) return;
        for(Element child : canvas.statements.getChildren()){
            if(child instanceof StatementElem){
                findAndSetIcon((StatementElem)child, Icon.move, Icon.copy);
            }
        }
    }

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
            Log.debug("[LogicAssist] duplicateSelectedBelow: st=@ copy=@", sorted.get(i).st.getClass().getSimpleName(), copy == null ? "null" : copy.getClass().getSimpleName());
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
        // MI2 模式：layout 后调用 setupUI() 解析副本中 JumpStatement 的 dest
        // JumpStatement.setupUI() 会从 destIndex 查找 elem.parent.getChildren() 解析 dest
        for(LStatement st : copies){
            if(st instanceof JumpStatement) st.setupUI();
        }
        // 更新所有 Jump 的 destIndex（反映插入后的新位置）并刷新跳转线
        saveAllJumpUI(canvas);
        canvas.statements.jumps.act(0f);
        // 先恢复所有积木的按钮图标（旧选中积木的 Icon.move 改回 Icon.copy）
        restoreButtonIcons(canvas);
        selected.clear();
        reselectRange(canvas, insertPos, copies.size);
        enterSelectedState(canvas);
        Log.debug("[LogicAssist] Duplicated " + copies.size + " blocks below selection.");
    }

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
        LCanvas canvas = getCanvas();
        if(canvas != null) restoreButtonIcons(canvas);
        selected.clear();
        state = State.IDLE;
    }

    // 重置所有积木的 translation（移动模式下选中积木设了 translation 跟随鼠标）
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
        dragBaseYs = null;
        clipboardCopies = null;
        clipboardSize = 0;
        clipboardSelectedIndices = null;
    }

    private static void startDrag(LCanvas canvas, float mx, float my, KeyCode button){
        dragStartMouseX = mx;
        dragStartMouseY = my;
        Vec2 startLocal = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(mx, my));
        dragStartLocalX = startLocal.x;
        dragStartLocalY = startLocal.y;
        dragInsertPos = -1;
        dragMoved = false;

        // 保存所有积木的原始 y 坐标（layout() 会修改，拖动期间需要恢复）
        Seq<Element> children = canvas.statements.getChildren();
        dragBaseYs = new float[children.size];
        for(int i = 0; i < children.size; i++){
            dragBaseYs[i] = children.get(i).y;
        }

        // 拖动模式：dragMode 持久模式 + Ctrl/中键临时覆盖。
        // 框选框/高亮框颜色由 getModeColor() 实时反映此判断，保证与松手后拖动模式一致。
        boolean ctrlDown = Core.input.keyDown(KeyCode.controlLeft);
        boolean isCopy = ctrlDown || dragMode == DragMode.COPY || button == KeyCode.mouseMiddle;

        // 清除原版 dragging 字段，防止原版 layout 跳过错误积木
        clearDraggingField(canvas);

        if(isCopy){
            prepareCopyData(canvas);
            state = State.DRAGGING_COPY;
        }else{
            state = State.DRAGGING_MOVE;
        }
    }

    // 拖动期间每帧更新 translation 和插入位置。
    // 不触发 layout()，用 translation 做所有偏移，JumpCurve 能跟随。
    private static void updateDrag(LCanvas canvas, float mx, float my){
        clearDraggingField(canvas);

        Seq<Element> children = canvas.statements.getChildren();

        // 恢复原始 y 坐标（防止 layout() 修改累积）
        if(dragBaseYs != null && dragBaseYs.length == children.size){
            for(int i = 0; i < children.size; i++){
                children.get(i).y = dragBaseYs[i];
            }
        }

        // 重置 needsLayout，防止 draw() 中 validate() → layout() 覆盖
        try{
            needsLayoutField.setBoolean(canvas.statements, false);
        }catch(Exception ignored){}

        resetAllTranslations(canvas);

        if(state == State.DRAGGING_MOVE){
            relayoutNonSelected(canvas);
            Vec2 localMouse = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(mx, my));
            // DragLayout 本地坐标系 y 向上为正（与 stage 一致），translation.y 也是 y 向上为正。
            // 所以 dy 与 dx 同向：localMouse - dragStart。
            float dx = localMouse.x - dragStartLocalX;
            float dy = localMouse.y - dragStartLocalY;
            for(StatementElem elem : selected){
                elem.setTranslation(dx, dy);
            }
        }
        // 复制模式：原积木保持原位，预览由 drawCopyPreview() 绘制

        int newInsertPos = computeInsertPosition(canvas, my);
        if(newInsertPos != dragInsertPos){
            dragInsertPos = newInsertPos;
        }

        // 腾位：用 translation 撑开插入位置空间，JumpCurve 能跟随
        applyInsertShift(canvas);

        canvas.statements.jumps.act(0f);
        updateIndicatorGeometry(canvas);

        if(Core.input.keyTap(KeyCode.mouseRight) || Core.input.keyTap(KeyCode.escape)){
            cancelDrag(canvas);
        }
    }

    private static int computeInsertPosition(LCanvas canvas, float stageY){
        Seq<Element> children = canvas.statements.getChildren();
        if(children.isEmpty()) return 0;

        Vec2 local = canvas.statements.stageToLocalCoordinates(Tmp.v2.set(0, stageY));
        float localY = local.y;

        if(state == State.DRAGGING_COPY){
            // 复制模式：遍历所有 children，用视觉位置（y + translation.y）计算
            for(int i = 0; i < children.size; i++){
                Element child = children.get(i);
                float visualY = child.y + child.translation.y;
                float centerLocalY = visualY + child.getHeight() / 2f;
                if(localY > centerLocalY){
                    return i;
                }
            }
            return children.size;
        }

        // 移动模式：跳过选中积木（它们已从原位移走），用视觉位置计算
        int nonSelectedCount = 0;
        for(Element child : children){
            if(child instanceof StatementElem && selected.contains(child)) continue;
            float visualY = child.y + child.translation.y;
            float centerLocalY = visualY + child.getHeight() / 2f;
            if(localY > centerLocalY){
                return nonSelectedCount;
            }
            nonSelectedCount++;
        }
        return nonSelectedCount;
    }

    // 移动模式：紧凑排列非选中积木，用 translation 设置偏移消除选中积木的原始占位
    private static void relayoutNonSelected(LCanvas canvas){
        Seq<Element> children = canvas.statements.getChildren();
        float space = Scl.scl(10f);

        float compactY = 0; // 从顶部开始的累积 y
        for(int i = 0; i < children.size; i++){
            Element e = children.get(i);
            if(e instanceof StatementElem && selected.contains(e)) continue;
            float totalHeight = canvas.statements.getHeight();
            float originalY = dragBaseYs != null && i < dragBaseYs.length ? dragBaseYs[i] : e.y;
            // 底对齐坐标转换：newY = totalHeight - compactY - height
            float newY = totalHeight - compactY - e.getPrefHeight();
            e.setTranslation(0, newY - originalY);
            compactY += e.getPrefHeight() + space;
        }
    }

    // 腾位：将插入点下方的非选中积木向下移，用 translation 撑开空间
    private static void applyInsertShift(LCanvas canvas){
        if(dragInsertPos < 0 || selected.isEmpty()) return;

        Seq<Element> children = canvas.statements.getChildren();
        float space = Scl.scl(10f);

        float shiftAmount = 0;
        for(StatementElem elem : selected){
            shiftAmount += elem.getHeight() + space;
        }
        shiftAmount -= space;
        if(shiftAmount <= 0) return;

        if(state == State.DRAGGING_COPY){
            // 复制模式：dragInsertPos 是绝对索引，移该索引及以下的积木
            for(int i = dragInsertPos; i < children.size; i++){
                Element child = children.get(i);
                child.setTranslation(child.translation.x, child.translation.y - shiftAmount);
            }
        }else{
            // 移动模式：dragInsertPos 是非选中积木的相对索引，
            // 需跳过选中积木找到对应绝对位置
            int nonSelectedSeen = 0;
            for(Element child : children){
                if(child instanceof StatementElem && selected.contains(child)) continue;
                if(nonSelectedSeen >= dragInsertPos){
                    child.setTranslation(child.translation.x, child.translation.y - shiftAmount);
                }
                nonSelectedSeen++;
            }
        }
    }

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
            // 复制模式：dragInsertPos 是真实 child 索引，用视觉位置计算
            if(children.isEmpty() || dragInsertPos == 0){
                insertLocalY = canvas.statements.getHeight();
            }else if(dragInsertPos >= children.size){
                Element last = children.get(children.size - 1);
                insertLocalY = last.y + last.translation.y - space;
                drawLocalX = last.x;
            }else{
                Element before = children.get(dragInsertPos - 1);
                insertLocalY = before.y + before.translation.y - space;
                drawLocalX = before.x;
            }
        }else{
            // 移动模式：用非选中 children 的视觉位置计算
            List<Element> nonSelected = new ArrayList<>();
            for(Element child : children){
                if(child instanceof StatementElem && selected.contains(child)) continue;
                nonSelected.add(child);
            }

            if(nonSelected.isEmpty() || dragInsertPos == 0){
                insertLocalY = canvas.statements.getHeight();
            }else if(dragInsertPos >= nonSelected.size()){
                Element last = nonSelected.get(nonSelected.size() - 1);
                insertLocalY = last.y + last.translation.y - space;
                drawLocalX = last.x;
            }else{
                Element before = nonSelected.get(dragInsertPos - 1);
                insertLocalY = before.y + before.translation.y - space;
                drawLocalX = before.x;
            }
        }

        Vec2 stagePos = canvas.statements.localToStageCoordinates(Tmp.v1.set(drawLocalX, insertLocalY));
        indicatorX = stagePos.x;
        indicatorY = stagePos.y - totalH;
        indicatorW = paneWidth;
        indicatorH = totalH;
    }

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

    // 检测点击是否命中滚动条区域。
    // 优先使用实际 vScrollBounds（含容差扩展），回退到右侧 SCROLLBAR_WIDTH 启发式。
    // 注意：内部用局部 Vec2 而非 Tmp.v1，避免污染调用方的 stageCoords（Tmp.v1）。
    private static boolean isScrollbarHit(ScrollPane pane, float stageX, float stageY){
        try{
            ensureScrollbarFields();
            Rect vScrollBounds = (Rect)vScrollBoundsField.get(pane);
            if(vScrollBounds != null && vScrollBounds.width > 0 && vScrollBounds.height > 0){
                Vec2 local = pane.stageToLocalCoordinates(new Vec2(stageX, stageY));
                // 向左扩展容差，让点击更容易命中
                float left = vScrollBounds.x - SCROLLBAR_HIT_PADDING;
                float right = vScrollBounds.x + vScrollBounds.width;
                return local.x >= left && local.x <= right;
            }
        }catch(Exception ignored){}
        // 回退：pane 右侧 SCROLLBAR_WIDTH 区域
        Vec2 paneStage = pane.localToStageCoordinates(new Vec2(0, 0));
        float paneRightX = paneStage.x + pane.getWidth();
        return stageX > paneRightX - SCROLLBAR_WIDTH;
    }

    // 处理滚动条点击：点击滑块放行给原版拖拽，点击轨道立即跳转到对应位置
    private static boolean handleScrollbarClick(ScrollPane pane, float stageX, float stageY){
        try{
            ensureScrollbarFields();
            Rect vKnobBounds = (Rect)vKnobBoundsField.get(pane);

            Vec2 local = pane.stageToLocalCoordinates(Tmp.v1.set(stageX, stageY));

            // 点击在滑块上 → 放行给原版拖拽
            if(vKnobBounds != null && vKnobBounds.width > 0 && vKnobBounds.height > 0 &&
               local.x >= vKnobBounds.x && local.x <= vKnobBounds.x + vKnobBounds.width &&
               local.y >= vKnobBounds.y && local.y <= vKnobBounds.y + vKnobBounds.height){
                return false;
            }

            if(pane.getMaxY() <= 0) return false;

            // cancel() 重置 draggingPointer/touchScroll/gestureDetector，消除残留手势状态
            pane.cancel();
            scrollToStageY(pane, stageY);
            return true;
        }catch(Exception e){
            Log.err("[LogicAssist] handleScrollbarClick failed", e);
            return false;
        }
    }

    /** 初始化滚动条反射字段（vScrollBounds/vKnobBounds/flingTimer） */
    private static void ensureScrollbarFields(){
        try{
            if(vScrollBoundsField == null){
                vScrollBoundsField = ScrollPane.class.getDeclaredField("vScrollBounds");
                vScrollBoundsField.setAccessible(true);
            }
            if(vKnobBoundsField == null){
                vKnobBoundsField = ScrollPane.class.getDeclaredField("vKnobBounds");
                vKnobBoundsField.setAccessible(true);
            }
            if(flingTimerField == null){
                flingTimerField = ScrollPane.class.getDeclaredField("flingTimer");
                flingTimerField.setAccessible(true);
            }
        }catch(Exception e){
            Log.warn("[LogicAssist] Failed to access scrollbar fields", e);
        }
    }

    /**
     * 根据 stage Y 坐标计算滚动比例并设置滚动条位置。
     * 不调用 pane.cancel()，适用于拖动期间的持续跳转（悬浮跳转）。
     * 点击跳转由调用方在调用前自行 cancel()。
     */
    private static void scrollToStageY(ScrollPane pane, float stageY){
        try{
            ensureScrollbarFields();
            Rect vScrollBounds = (Rect)vScrollBoundsField.get(pane);
            Rect vKnobBounds = (Rect)vKnobBoundsField.get(pane);

            Vec2 local = pane.stageToLocalCoordinates(Tmp.v1.set(0, stageY));

            float maxScroll = pane.getMaxY();
            if(maxScroll <= 0) return;

            // ScrollPane 中 knob.y = scrollBounds.y + (scrollBounds.h - knob.h) * (1 - percent)
            // 反推 percent 时，trackHeight = scrollBounds.h - knob.h
            float ratio;
            if(vScrollBounds != null && vScrollBounds.height > 0){
                float knobH = (vKnobBounds != null) ? vKnobBounds.height : 0;
                float trackHeight = vScrollBounds.height - knobH;
                if(trackHeight > 0){
                    float offset = vScrollBounds.y + vScrollBounds.height - knobH / 2f - local.y;
                    ratio = offset / trackHeight;
                }else{
                    ratio = (vScrollBounds.y + vScrollBounds.height - local.y) / vScrollBounds.height;
                }
            }else{
                ratio = 1f - (local.y / pane.getHeight());
            }
            ratio = Mathf.clamp(ratio, 0f, 1f);

            pane.setScrollYForce(ratio * maxScroll);
            pane.setVelocityY(0);
            flingTimerField.setFloat(pane, 0);
        }catch(Exception e){
            Log.warn("[LogicAssist] scrollToStageY failed", e);
        }
    }

    // 鼠标按住且位于滚动条区域时，抑制 LCanvas.act() 的边缘自动滚动
    public static boolean shouldSuppressEdgeScroll(){
        if(!isScrollbarEnabled() || !Core.input.isTouched()) return false;
        LCanvas canvas = getCanvas();
        if(canvas == null || canvas.pane == null || !canvas.pane.hasScroll()) return false;
        return isScrollbarHit(canvas.pane, Core.input.mouseX(), Core.input.mouseY());
    }

    /** 检测玩家是否正在拖动积木：BoxSelect 拖动、原版 LCanvas 拖动、或 Jump 箭头拖动选择目标 */
    private static boolean isAnyDragging(LCanvas canvas){
        if(state == State.DRAGGING_MOVE || state == State.DRAGGING_COPY) return true;
        try{
            if(draggingField.get(canvas) != null) return true;
        }catch(Exception ignored){}
        return isJumpArrowDragging(canvas);
    }

    /** 检测是否有 JumpButton 正在选择跳转目标（拖动箭头） */
    private static boolean isJumpArrowDragging(LCanvas canvas){
        if(selectingField == null || canvas == null || canvas.statements == null) return false;
        Group jumps = canvas.statements.jumps;
        if(jumps == null) return false;
        for(Element child : jumps.getChildren()){
            if(child instanceof LCanvas.JumpCurve){
                LCanvas.JumpButton button = ((LCanvas.JumpCurve)child).button;
                if(button == null) continue;
                try{
                    if(selectingField.getBoolean(button)) return true;
                }catch(Exception ignored){}
            }
        }
        return false;
    }

    /**
     * 拖动积木时悬浮滚动条快速跳转：
     * 当玩家手中有积木（拖动中）且鼠标接近滚动条区域时，
     * 滚动条位置直接跳转到鼠标所悬浮的位置。
     * 鼠标离开滚动条区域后功能自动停止。
     * 同时管理滚动条变粗提示动画：拖动中且功能开启时滚动条变粗。
     */
    public static void updateScrollbarHoverJump(){
        if(!isScrollbarEnabled() || !isScrollbarJumpEnabled()){
            hoverJumpAnim = 0f;
            return;
        }
        LCanvas canvas = getCanvas();
        if(canvas == null || canvas.pane == null || !canvas.pane.hasScroll()){
            hoverJumpAnim = 0f;
            return;
        }

        LogicDialog dialog = Vars.ui.logic;
        if(dialog == null || !dialog.isShown()){
            hoverJumpAnim = 0f;
            return;
        }

        boolean dragging = isAnyDragging(canvas);

        // 直接切换：拖动中 → 变粗，否则 → 恢复（不做插值动画，避免闪烁）
        hoverJumpAnim = dragging ? 1f : 0f;

        if(!dragging) return;

        float mx = Core.input.mouseX();
        float my = Core.input.mouseY();

        if(!isScrollbarHit(canvas.pane, mx, my)) return;

        scrollToStageY(canvas.pane, my);
    }

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
            case DRAGGING_MOVE:
                // 插入指示器在 LogicCanvas.draw() 中绘制（在积木下方）
                redrawSelectedBlocksOnTop(canvas);
                break;
            case DRAGGING_COPY:
                // 复制模式：原积木在原位由 DragLayout.draw 正常绘制
                // 插入指示器在 LogicCanvas.draw() 中绘制，半透明预览跟随鼠标
                drawCopyPreview(canvas);
                break;
            case SELECTED:
                // SELECTED 状态的高亮由 LogicCanvas.draw() 绘制，避免 toFront 干扰 MindustryX
                break;
            default:
                break;
        }

        // 彩色滚动条：overlay 在前时（SELECTING/DRAGGING）由此绘制；
        // IDLE/SELECTED 时由 LogicCanvas.draw() 绘制
        if(isScrollbarEnabled() && (state == State.SELECTING || state == State.DRAGGING_MOVE || state == State.DRAGGING_COPY)){
            drawColorScrollbar(canvas);
        }
    }

    // 返回框选框/高亮框颜色，实时反映"松手后会进入的拖动模式"。
    // 拖动中按 state 判断；框选/选中态按 dragMode + Ctrl 实时判断，保证与按钮图标和拖动模式一致。
    private static Color getModeColor(){
        boolean isCopy;
        if(state == State.DRAGGING_MOVE){
            isCopy = false;
        }else if(state == State.DRAGGING_COPY){
            isCopy = true;
        }else{
            // 框选/选中态：与 startDrag 的判断保持一致
            boolean ctrlDown = Core.input.keyDown(KeyCode.controlLeft);
            isCopy = ctrlDown || dragMode == DragMode.COPY;
        }
        return isCopy ? Pal.heal : Pal.place;
    }

    private static void drawSelectionBox(LCanvas canvas){
        // 直接用 stage 坐标绘制：selStartX/Y 和 selCurX/Y 已经是 stage 坐标。
        // 不做 round-trip 转换（stage→local→stage），因为 act 阶段 super.act() 中的
        // 边缘滚动会修改 pane.scrollY，导致 draw 阶段 statements.stageY 与 startBoxSelect
        // 时不同，round-trip 会失败，框选起点偏移到错误位置。
        float minX = Math.min(selStartX, selCurX);
        float minY = Math.min(selStartY, selCurY);
        float maxX = Math.max(selStartX, selCurX);
        float maxY = Math.max(selStartY, selCurY);

        float sx = minX;
        float sy = minY;
        float w = maxX - minX;
        float h = maxY - minY;

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

    public static void drawHighlights(LCanvas canvas){
        if(selected.isEmpty()) return;

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

    // 绘制彩色滚动条：在 ScrollPane 的垂直滚动条轨道上，按每个积木的比例绘制对应类别颜色。
    // 拖动积木时（hoverJumpAnim > 0）滚动条向左扩展变粗，颜色段同步变宽。
    public static void drawColorScrollbar(LCanvas canvas){
        ScrollPane pane = canvas.pane;
        if(pane == null || !pane.hasScroll()) return;

        // 用反射读取 vScrollBounds（ScrollPane 内部的滚动条轨道 Rect，本地坐标）
        Rect vScrollBounds = null;
        try{
            ensureScrollbarFields();
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

        // 悬浮跳转提示：拖动中向左扩展滚动条宽度，颜色段同步变宽
        float expand = scrollbarW * HOVER_JUMP_EXPAND_RATIO * hoverJumpAnim;
        float drawX = scrollbarX - expand;
        float drawW = scrollbarW + expand;

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

        // 动画激活时提高颜色段不透明度，让变粗的滚动条更醒目
        float segAlpha = SCROLLBAR_SEG_ALPHA + 0.25f * hoverJumpAnim;

        // 绘制每个积木对应的颜色段，手动裁剪到滚动条可视区域内（不使用 ScissorStack，
        // 避免与 MindustryX 面板的 ScrollPane scissor 产生交集导致裁剪异常）
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

            // 手动裁剪到 [scrollbarBottom, scrollbarTop] 范围内
            float segTop = Math.min(scrollbarTop, elemTop);
            float segBottom = Math.max(scrollbarBottom, elemTop - elemColorH);
            if(segTop > segBottom){
                Draw.color(c);
                Draw.alpha(segAlpha);
                Fill.crect(drawX, segBottom, drawW, segTop - segBottom);
            }

            cy += elemH + space;
        }

        // 拖动扩展时绘制扩展后的滑块，覆盖原生窄滑块，保持视觉一致
        if(hoverJumpAnim > 0){
            try{
                Rect vKnobBounds = (Rect)vKnobBoundsField.get(pane);
                if(vKnobBounds != null && vKnobBounds.height > 0){
                    Vec2 knobBl = pane.localToStageCoordinates(Tmp.v2.set(vKnobBounds.x, vKnobBounds.y));
                    Draw.color(Color.lightGray);
                    Draw.alpha(0.85f * hoverJumpAnim);
                    Fill.crect(drawX, knobBl.y, drawW, vKnobBounds.height);
                }
            }catch(Exception ignored){}
        }

        Draw.flush();
        Draw.reset();
    }

    // 是否正在拖动（供 LogicCanvas.draw() 查询）
    public static boolean isDragging(){
        return state == State.DRAGGING_MOVE || state == State.DRAGGING_COPY;
    }

    // 是否正在框选（供 LogicCanvas.draw() 查询）
    public static boolean isSelecting(){
        return state == State.SELECTING;
    }

    // 在 stage 坐标系绘制插入指示器（供 LogicCanvas.draw() 在 super.draw() 前调用）
    public static void drawInsertIndicatorUnder(LCanvas canvas){
        if(dragInsertPos < 0) return;
        Mat oldTrans = tmpMat.set(Draw.trans());
        Draw.trans(tmpMat2.idt());
        Draw.reset();
        Tex.pane.draw(indicatorX, indicatorY, indicatorW, indicatorH);
        Draw.reset();
        Draw.trans(oldTrans);
    }

    // 在正确的 transform 矩阵内重画选中积木，确保积木画在插入阴影上方。
    // DRAGGING_MOVE 时用选中积木的 translation（鼠标偏移）作为绘制偏移。
    private static void redrawSelectedBlocksOnTop(LCanvas canvas){
        if(selected.isEmpty()) return;
        // 所有选中积木共享相同的 translation（在 updateDrag 中统一设置）
        StatementElem first = selected.iterator().next();
        drawElementsWithOffset(canvas, first.translation.x, first.translation.y, 1f);
    }

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

    // 统一的绘制方法：保存矩阵 → 设置 translation → 临时修改 x/y → draw → finally 恢复
    // @param alpha 1f = 不透明（重画在顶层），0.5f = 半透明（复制预览）
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

    private static void executeDragMove(LCanvas canvas, int insertPos){
        clearDraggingField(canvas);

        resetAllTranslations(canvas);
        dragBaseYs = null;

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

        // 双重 invalidate + validate 处理高度变化，jumps.act 同步跳转线位置
        // saveAllJumpUI 通过 dest.parent.getChildren().indexOf(dest) 反查更新 destIndex，
        // 覆盖 jump 自身被移动 / dest 被移动 / 两者都被移动 / 都未被移动 所有情况
        finalizeLayout(canvas);
        saveAllJumpUI(canvas);
        // saveAllJumpUI 改变了 destIndex，需再次 act 让 JumpCurve 重新连接目标
        canvas.statements.jumps.act(0f);
        reselectRange(canvas, actualInsert, count);
        enterSelectedState(canvas);
        Log.debug("[LogicAssist] Drag-moved " + count + " blocks to position " + actualInsert);
    }

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
            Log.debug("[LogicAssist] prepareCopyData: st=@ copy=@", elem.st.getClass().getSimpleName(), copy == null ? "null" : copy.getClass().getSimpleName());
            if(copy != null) clipboardCopies.add(copy);
        }
        clipboardSize = clipboardCopies.size();
    }

    private static void executeDragCopy(LCanvas canvas, int insertPos){
        clearDraggingField(canvas);

        resetAllTranslations(canvas);
        dragBaseYs = null;

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
            Log.debug("[LogicAssist] executeDragCopy: st=@ copy=@", st.getClass().getSimpleName(), copy == null ? "null" : copy.getClass().getSimpleName());
            copies.add(copy);
        }
        if(copies.isEmpty()){
            enterSelectedState(canvas);
            return;
        }

        int actualInsert = Math.max(0, Math.min(insertPos, canvas.statements.getChildren().size));

        adjustJumpDestIndices(copies, clipboardSelectedIndices, actualInsert, copies.size);
        // 先全部插入，再统一 setupUI
        for(int i = 0; i < copies.size; i++){
            canvas.addAt(actualInsert + i, copies.get(i));
        }
        for(LStatement st : copies){
            st.setupUI();
        }

        finalizeLayout(canvas);
        // MI2 模式：layout 后调用 setupUI() 解析副本中 JumpStatement 的 dest
        // JumpStatement.setupUI() 会从 destIndex 查找 elem.parent.getChildren() 解析 dest
        for(LStatement st : copies){
            if(st instanceof JumpStatement) st.setupUI();
        }
        // 更新所有 Jump 的 destIndex（反映插入后的新位置）并刷新跳转线
        saveAllJumpUI(canvas);
        canvas.statements.jumps.act(0f);
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
        dragBaseYs = null;
        clipboardCopies = null;
        clipboardSize = 0;
        clipboardSelectedIndices = null;
        dragInsertPos = -1;
        state = State.SELECTED;
        Log.debug("[LogicAssist] Drag cancelled.");
    }

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

    // 调整副本中 JumpStatement 的 destIndex（executeDragCopy 和 duplicateSelectedBelow 共用）
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

    private static void reselectRange(LCanvas canvas, int start, int count){
        selected.clear();
        Seq<Element> newChildren = canvas.statements.getChildren();
        for(int i = start; i < start + count && i < newChildren.size; i++){
            if(newChildren.get(i) instanceof StatementElem){
                selected.add((StatementElem)newChildren.get(i));
            }
        }
        updateSelectedButtonIcons(canvas);
    }

    // 双重 invalidate + validate 处理高度变化后的布局稳定
    private static void finalizeLayout(LCanvas canvas){
        canvas.statements.updateJumpHeights = true;
        canvas.statements.invalidate();
        canvas.statements.validate();
        // layout() 发现 height 变化后 invalidateHierarchy 标记父节点，
        // 但自身 layout() 已用旧 height 完成，需第二次 validate 用新 height 重新布局
        canvas.statements.invalidate();
        canvas.statements.validate();
        canvas.statements.jumps.act(0f);
    }

    // 更新所有积木的 JumpStatement destIndex（移动/删除后调用）
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

    private static void clearDraggingField(LCanvas canvas){
        try{
            draggingField.set(canvas, null);
        }catch(Exception e){
            Log.warn("[LogicAssist] Failed to clear dragging field", e);
        }
    }
}
