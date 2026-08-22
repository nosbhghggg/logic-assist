package logicassist;

import arc.*;
import arc.graphics.g2d.TextureRegion;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.logic.*;
import mindustry.logic.LCanvas.*;
import mindustry.logic.LStatements.*;
import mindustry.mod.Mods;
import mindustry.ui.*;

import java.lang.reflect.*;

/**
 * 标签复制：在逻辑编辑器"编辑"菜单注入按钮，把代码以标签形式复制到剪贴板。
 *
 * 原版"复制到剪贴板"用 canvas.save()，jump 目标写为行号（积木索引），
 * 对人不友好且编辑后行号即失效；mlog 原生支持 "name:" 标签行与
 * "jump name ..." 标签跳转（LParser 两遍解析），本类按此格式导出：
 * 标签顶格、语句缩进 4 空格，可直接被"从剪贴板加载"原样读回。
 *
 * 按钮注入走双通道（MARKER 防重，任一通道先到即生效）：
 * ① 点击监听：挂在"编辑"按钮上，原版回调 show 菜单后 post 一帧注入；
 * ② 每帧扫描：afterAct 轮询 scene root，新出现的对话框深扫一次，
 *    发现含"复制到剪贴板"的导出菜单立即注入——不依赖点击事件能否送达，
 *    兜底"监听器被其他改逻辑编辑器的模组（MindustryX/MDTX/TextureMatrix）拦截"。
 */
public class LabelCopy{

    private static final String MARKER = "la_label_copy";

    // Cell.row/endRow 为包私有，布局按 cell 存储的行列号定位（与 cells Seq 顺序无关），
    // 移动 cell 后须反射修补行号；字段名自 libgdx 时代起稳定
    private static Field rowField, endRowField;

    // 已深扫过的 root 对话框（对象身份去重）：每帧扫描时跳过，避免重复遍历大 UI 树；
    // 对话框隐藏（离开 root，parent=null）后清除记录，允许 GC 且重开时重扫
    private static final Seq<Dialog> scannedDialogs = new Seq<>();

    // 上次见到的"编辑"按钮对象：setup() 每次编辑器打开都重建按钮表（clearChildren），
    // 对象更换即一次新的打开（或 resize 重布局），用于诊断注入链路是否存活
    private static Element lastEditButton;

    static{
        try{
            rowField = Cell.class.getDeclaredField("row");
            rowField.setAccessible(true);
            endRowField = Cell.class.getDeclaredField("endRow");
            endRowField.setAccessible(true);
        }catch(Throwable t){
            rowField = null;
            endRowField = null;
        }
    }

    public static void inject(LCanvas canvas){
        if(canvas == null || Vars.ui == null || Vars.ui.logic == null) return;

        attachEditListener(canvas);
        scanRootDialogs(canvas);
    }

    // LogicDialog.shown → setup() 每次重建 buttons（编辑按钮是新对象），
    // 须持续轮询重挂监听；buttons 表只有几个元素，无需节流
    private static void attachEditListener(LCanvas canvas){
        Element edit = Vars.ui.logic.buttons.find("edit");
        if(!(edit instanceof Button button)){
            if(lastEditButton != null){
                lastEditButton = null;
                Log.infoTag("LogicAssist", "LabelCopy: edit button disappeared");
            }
            return;
        }

        if(edit != lastEditButton){
            lastEditButton = edit;
            Log.infoTag("LogicAssist", "LabelCopy: edit button ready (setup rebuilt), listener attached");
        }

        for(var listener : button.getListeners()){
            if(listener instanceof EditMenuListener) return;
        }
        button.addListener(new EditMenuListener(canvas));
    }

    // 原版回调先执行（监听器按添加顺序触发），导出菜单已创建并 show，
    // post 一帧等布局稳定后再注入，避免在事件分发中改 UI 树
    private static class EditMenuListener extends ClickListener{
        private final LCanvas canvas;

        EditMenuListener(LCanvas canvas){
            this.canvas = canvas;
        }

        @Override
        public void clicked(InputEvent event, float x, float y){
            Core.app.post(() -> injectFromClick(canvas));
        }
    }

    // 通道①的注入入口：点击后全量扫（无视身份去重——post 时机在帧首，
    // 常规帧扫描可能尚未跑过；一次点击只执行一次，全量开销可接受）
    private static void injectFromClick(LCanvas canvas){
        if(Core.scene == null) return;

        String copyText = copyButtonText();

        // scene root 上的对话框（原版导出菜单 new BaseDialog 后 show 到 root）
        if(scanElements(Core.scene.getElements(), canvas, copyText)) return;

        // 兜底：个别改版可能把导出菜单嵌在逻辑编辑器内部而非 root，
        // 一次性深扫编辑器自身（含积木子树，仅点击时执行，开销可接受）
        TextButton nested = findButton(Vars.ui.logic, copyText);
        if(nested != null && injectButton(canvas, nested)) return;

        Log.infoTag("LogicAssist", "LabelCopy: export dialog not found after edit click");
    }

    // 通道②：每帧常规扫描。只深扫新出现的对话框，
    // 发现含"复制到剪贴板"按钮的菜单立即注入，与点击通道幂等互备
    private static void scanRootDialogs(LCanvas canvas){
        if(Core.scene == null) return;

        // 清除已隐藏对话框的记录（parent==null 即已离开 root），允许 GC、重开重扫
        for(int i = scannedDialogs.size - 1; i >= 0; i--){
            if(scannedDialogs.get(i).parent == null) scannedDialogs.remove(i);
        }

        String copyText = copyButtonText();
        Seq<Element> elements = Core.scene.getElements();
        for(int i = 0; i < elements.size; i++){
            if(!(elements.get(i) instanceof Dialog dialog) || dialog == Vars.ui.logic) continue;
            if(scannedDialogs.contains(dialog)) continue;
            scannedDialogs.add(dialog);

            TextButton copyBtn = findButton(dialog, copyText);
            if(copyBtn != null) injectButton(canvas, copyBtn);
        }
    }

    private static boolean scanElements(Seq<Element> elements, LCanvas canvas, String copyText){
        for(int i = 0; i < elements.size; i++){
            if(!(elements.get(i) instanceof Dialog dialog) || dialog == Vars.ui.logic) continue;

            TextButton copyBtn = findButton(dialog, copyText);
            if(copyBtn == null) continue;
            if(injectButton(canvas, copyBtn)) return true;
        }
        return false;
    }

    // 原版按钮文本经 Label.setText 本地化（"@" 前缀解析），
    // 用 bundle 键取当前语言文本匹配；回退原始键名防 bundle 缺失
    private static String copyButtonText(){
        return Core.bundle != null && Core.bundle.has("copy.clipboard")
            ? Core.bundle.get("copy.clipboard") : "@copy.clipboard";
    }

    // 模组图标：Mods 加载时把 mod 根的 icon.png 载入 iconTexture（线性过滤），
    // 与设置分类图标同源（JumpLineColor.setupSettings）；异常或缺失时回退 Icon.copy
    private static Drawable modIcon(){
        try{
            Mods.LoadedMod mod = Vars.mods.getMod(LogicAssistMod.class);
            if(mod != null && mod.iconTexture != null){
                return new TextureRegionDrawable(new TextureRegion(mod.iconTexture));
            }
        }catch(Throwable ignored){
        }
        return Icon.copy;
    }

    private static boolean injectButton(LCanvas canvas, TextButton copyBtn){
        if(!(copyBtn.parent instanceof Table table)){
            Log.infoTag("LogicAssist", "LabelCopy: copy button parent is not a Table, skip");
            return false;
        }
        if(table.find(MARKER) != null) return true;

        // 先结束当前行（原版最后一个按钮 restart 未调 row()），
        // 让新按钮落在独立的新行而非 restart 右侧
        table.row();

        // cell.get() 在 lambda 里延迟取值（lambda 创建时 cell 尚未赋值）
        TextButton[] ref = new TextButton[1];
        // 模组图标原始 256px，须显式传显示尺寸（TextureRegionDrawable.imageSize()
        // 返回贴图原始宽，4 参重载会据此撑大按钮）——对齐 Icon.copy 的实际渲染
        // 大小，与相邻原版按钮保持一致
        Cell<TextButton> cell = table.button("@la.copy.labels", modIcon(), Styles.flatt,
            Icon.copy.imageSize() / Scl.scl(1f), () -> onCopy(canvas, ref[0])).marginLeft(12f);
        ref[0] = cell.get();
        cell.get().name = MARKER;

        // 再结束新按钮的行：把表格行计数加到位，否则重布局时
        // 行高数组按旧行数分配，下面修补的行号会越界
        table.row();

        // 移到"复制到剪贴板"之后：pop+insert 只改 cells Seq 顺序，
        // 布局仍按各 cell 存储的 row 定位，须从插入点起依次补写行号；
        // 反射不可用时回退为留在末尾（追加位置本身是合法布局）
        Seq<Cell> cells = table.getCells();
        Cell moved = cells.pop();
        int copyIdx = indexOfCell(table, copyBtn);
        if(copyIdx >= 0){
            cells.insert(copyIdx + 1, moved);
            if(!relabelRows(cells, copyIdx + 1)){
                cells.remove(copyIdx + 1);
                cells.add(moved);
            }
        }else{
            cells.add(moved);
        }
        table.invalidateHierarchy();
        Log.infoTag("LogicAssist", "LabelCopy: label copy button injected into export dialog");
        return true;
    }

    // 从 Seq 的 from 位置起按 endRow 标记重排行号（插入点前一 cell 已结束其行）。
    // 该菜单每行一个按钮，各 cell endRow 均为 true，逐行 +1 即可
    private static boolean relabelRows(Seq<Cell> cells, int from){
        if(rowField == null || endRowField == null || from <= 0) return false;
        try{
            int row = rowField.getInt(cells.get(from - 1)) + 1;
            for(int i = from; i < cells.size; i++){
                Cell c = cells.get(i);
                rowField.setInt(c, row);
                if(endRowField.getBoolean(c)) row++;
            }
            return true;
        }catch(Throwable t){
            return false;
        }
    }

    private static TextButton findButton(Element e, String text){
        if(e instanceof TextButton button){
            String label = button.getText().toString();
            if(label.equals(text) || label.equals("@copy.clipboard")) return button;
        }
        if(e instanceof Group group){
            for(Element child : group.getChildren()){
                TextButton found = findButton(child, text);
                if(found != null) return found;
            }
        }
        return null;
    }

    private static int indexOfCell(Table table, Element element){
        Seq<Cell> cells = table.getCells();
        for(int i = 0; i < cells.size; i++){
            if(cells.get(i).get() == element) return i;
        }
        return -1;
    }

    private static void onCopy(LCanvas canvas, Element source){
        // 与原版复制按钮一致：先关菜单再写剪贴板
        Element p = source;
        while(p != null && !(p instanceof Dialog)){
            p = p.parent;
        }
        if(p instanceof Dialog dialog) dialog.hide();

        try{
            Core.app.setClipboardText(buildLabeled(canvas));
            if(Vars.ui != null) Vars.ui.showInfoToast(Core.bundle.get("la.copy.labels.done"), 4f);
        }catch(Throwable t){
            Log.err("[LogicAssist] Label copy failed", t);
        }
    }

    // 标签名 l<积木索引>。jump.destIndex 本就是积木索引（saveUI 从 dest 积木
    // 反查），标签按位置插在目标积木之前，与各积木输出行数无关——因此
    // 无需像 save() 那样先 unfoldAll 再折叠，ExprStatement 多行输出不影响正确性
    public static String buildLabeled(LCanvas canvas){
        Seq<Element> children = canvas.statements.getChildren();
        int size = children.size;

        // 刷新 UI 状态：jump 的 destIndex 从曲线 dest 积木重新反查
        for(Element child : children){
            if(child instanceof StatementElem) ((StatementElem)child).st.saveUI();
        }

        // 收集有效跳转目标（积木索引；size 表示跳到程序末尾）
        IntSet targets = new IntSet();
        for(Element child : children){
            if(!(child instanceof StatementElem)) continue;
            LStatement st = ((StatementElem)child).st;
            if(st instanceof JumpStatement jump && jump.destIndex >= 0 && jump.destIndex <= size){
                targets.add(jump.destIndex);
            }
        }

        StringBuilder out = new StringBuilder();
        for(int i = 0; i < size; i++){
            if(targets.contains(i)){
                out.append("l").append(i).append(":\n");
            }

            Element child = children.get(i);
            if(!(child instanceof StatementElem)) continue;
            LStatement st = ((StatementElem)child).st;

            StringBuilder sb = new StringBuilder();
            if(st instanceof JumpStatement jump){
                writeJump(sb, jump, size);
            }else{
                st.write(sb);
            }
            if(sb.length() > 0) appendIndented(out, sb);
        }
        // 尾部标签：LParser 中标签行不占语句行号，尾标签解析为语句总数，
        // 与原版 jump <size>（跳到程序外即停机）语义一致
        if(targets.contains(size)){
            out.append("l").append(size).append(":\n");
        }
        return out.toString();
    }

    // JumpStatement 未覆写 write()，LogicIO.write 固定输出
    // "jump <destIndex> <op.name> <value> <compare>"；此处仅把可标注的
    // destIndex 替换为标签，其余字段与原版逐字一致（含 -1 等无效值保持原样）
    private static void writeJump(StringBuilder sb, JumpStatement jump, int size){
        sb.append("jump ");
        if(jump.destIndex >= 0 && jump.destIndex <= size){
            sb.append("l").append(jump.destIndex);
        }else{
            sb.append(jump.destIndex);
        }
        sb.append(' ').append(jump.op.name());
        sb.append(' ').append(jump.value);
        sb.append(' ').append(jump.compare);
    }

    // 逐行加 4 空格缩进（语句可能多行输出，如 ExprStatement 的 op 链）
    private static void appendIndented(StringBuilder out, StringBuilder sb){
        int start = 0;
        for(int i = 0; i <= sb.length(); i++){
            if(i == sb.length() || sb.charAt(i) == '\n'){
                out.append("    ");
                out.append(sb, start, i);
                out.append('\n');
                start = i + 1;
            }
        }
    }
}
