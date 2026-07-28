package logicassist.expr;

import arc.Core;
import java.util.*;

/**
 * 表达式编译器：表达式字符串 ↔ op 语句链双向转换。
 *
 * 正向：x = cos(a) * 10 + x → op cos/mul/add 链
 * 逆向：op 链 → cos(a) * 10 + x
 *
 * 临时变量统一使用 _0, _1, ... 编号，栈式分配复用，支持逆向重建。
 *
 * 致谢：mindcode (https://github.com/cardillan/mindcode)
 * 反编译、运算符分类、优化规则（常量折叠、CSE、临时变量消除）参考自该项目。
 */
public class ExprCompiler{

    public static final String TMP = "_";
    // 优化开关：常量折叠、代数化简等优化需通过 print "expr-opt:true" 开启（CSE 始终开启）
    public static boolean optimizationEnabled = false;

    // 一元运算符（Mindustry LogicOp.unary=true），op 格式：op <name> <dest> <a> 0
    static final Set<String> UNARY_OPS = Set.of(
        "not", "abs", "sign", "log", "log10", "floor", "ceil", "round",
        "sqrt", "rand", "sin", "cos", "tan", "asin", "acos", "atan"
    );

    // 函数型二元运算符（LogicOp.func=true），表达式使用 func(a, b) 语法
    static final Set<String> FUNC_BINARY_OPS = Set.of(
        "max", "min", "angle", "angleDiff", "len", "noise", "logn"
    );

    static final Set<String> KNOWN_FUNCS = new HashSet<>();
    static{
        KNOWN_FUNCS.addAll(UNARY_OPS);
        KNOWN_FUNCS.addAll(FUNC_BINARY_OPS);
    }

    static final int PREC_OR = 1, PREC_AND = 2, PREC_EQ = 3, PREC_REL = 4;
    static final int PREC_XOR = 5, PREC_BAND = 6, PREC_SHIFT = 7;
    static final int PREC_ADD = 8, PREC_MUL = 9, PREC_UNARY = 10, PREC_POW = 11;
    static final int PREC_ATOM = 12;

    static final Map<String, String> OP_TO_SYMBOL = new HashMap<>();
    static final Map<String, Integer> OP_PRECEDENCE = new HashMap<>();
    static{
        put2("or", "||", PREC_OR);
        put2("land", "&&", PREC_AND);
        put2("equal", "==", PREC_EQ);
        put2("notEqual", "!=", PREC_EQ);
        put2("strictEqual", "===", PREC_EQ);
        put2("lessThan", "<", PREC_REL);
        put2("greaterThan", ">", PREC_REL);
        put2("lessThanEq", "<=", PREC_REL);
        put2("greaterThanEq", ">=", PREC_REL);
        put2("xor", " xor ", PREC_XOR);
        put2("and", "&", PREC_BAND);
        put2("shl", "<<", PREC_SHIFT);
        put2("shr", ">>", PREC_SHIFT);
        put2("ushr", ">>>", PREC_SHIFT);
        put2("add", "+", PREC_ADD);
        put2("sub", "-", PREC_ADD);
        put2("mul", "*", PREC_MUL);
        put2("div", "/", PREC_MUL);
        put2("idiv", "//", PREC_MUL);
        put2("mod", "%", PREC_MUL);
        put2("emod", "%%", PREC_MUL);
        put2("pow", "^", PREC_POW);
    }
    static void put2(String op, String sym, int prec){
        OP_TO_SYMBOL.put(op, sym);
        OP_PRECEDENCE.put(op, prec);
    }

    abstract static class Node{}
    static class Num extends Node{ final double val; Num(double v){val=v;} }
    static class Var extends Node{ final String name; Var(String n){name=n;} }
    static class Unary extends Node{ final String op; final Node operand; Unary(String o,Node n){op=o;operand=n;} }
    static class Binary extends Node{ final String op; final Node l,r; Binary(String o,Node a,Node b){op=o;l=a;r=b;} }

    public static class OpLine{
        public final String op, dest, a, b;
        public OpLine(String op, String dest, String a, String b){
            this.op=op; this.dest=dest; this.a=a; this.b=b;
        }
        public String toText(){
            return "op " + op + " " + dest + " " + a + " " + b;
        }
        public static OpLine fromText(String line){
            String[] parts = line.trim().split("\\s+");
            if(parts.length < 5 || !parts[0].equals("op")) return null;
            return new OpLine(parts[1], parts[2], parts[3], parts[4]);
        }
        @Override public String toString(){ return toText(); }
    }

    public static class ParseException extends RuntimeException{
        public ParseException(String msg){ super(msg); }
    }

    enum TokType{ NUM, IDENT, OP, LPAREN, RPAREN, COMMA, EOF }
    static class Token{
        final TokType type;
        final String text;
        // token 在原始字符串中的起始位置（用于高亮保留原始空白）
        final int start;
        Token(TokType t, String s, int start){ this.type = t; this.text = s; this.start = start; }
    }

    static final String[] MULTI_OPS = {"===", ">>>", "<=", ">=", "==", "!=", "<<", ">>", "%%", "//", "&&", "||"};
    static final String[] SINGLE_OPS = {"+", "-", "*", "/", "%", "^", "<", ">", "&", "|", "~", "!", "(", ")", ","};

    static List<Token> tokenize(String expr){
        List<Token> tokens = new ArrayList<>();
        int i = 0, len = expr.length();
        while(i < len){
            char c = expr.charAt(i);
            if(Character.isWhitespace(c)){ i++; continue; }
            if(Character.isDigit(c) || (c == '.' && i+1 < len && Character.isDigit(expr.charAt(i+1)))){
                int start = i;
                while(i < len && (Character.isDigit(expr.charAt(i)) || expr.charAt(i) == '.')) i++;
                tokens.add(new Token(TokType.NUM, expr.substring(start, i), start));
                continue;
            }
            if(Character.isLetter(c) || c == '_' || c == '@'){
                int start = i;
                if(c == '@') i++;
                while(i < len && (Character.isLetterOrDigit(expr.charAt(i)) || expr.charAt(i) == '_')) i++;
                tokens.add(new Token(TokType.IDENT, expr.substring(start, i), start));
                continue;
            }
            if(c == '('){ tokens.add(new Token(TokType.LPAREN, "(", i)); i++; continue; }
            if(c == ')'){ tokens.add(new Token(TokType.RPAREN, ")", i)); i++; continue; }
            if(c == ','){ tokens.add(new Token(TokType.COMMA, ",", i)); i++; continue; }
            boolean matched = false;
            for(String op : MULTI_OPS){
                if(i + op.length() <= len && expr.substring(i, i + op.length()).equals(op)){
                    tokens.add(new Token(TokType.OP, op, i));
                    i += op.length();
                    matched = true;
                    break;
                }
            }
            if(matched) continue;
            for(String op : SINGLE_OPS){
                if(c == op.charAt(0)){
                    tokens.add(new Token(TokType.OP, String.valueOf(c), i));
                    i++;
                    matched = true;
                    break;
                }
            }
            if(matched) continue;
            throw new ParseException(msg("la.err.unrecognized_char", c, i));
        }
        tokens.add(new Token(TokType.EOF, "", i));
        return tokens;
    }

    static class Parser{
        final List<Token> tokens;
        int pos = 0;

        Parser(List<Token> tokens){ this.tokens = tokens; }

        Token peek(){ return tokens.get(pos); }
        Token next(){ return tokens.get(pos++); }
        boolean isOp(String text){ return peek().type == TokType.OP && peek().text.equals(text); }
        boolean isIdent(String text){ return peek().type == TokType.IDENT && peek().text.equalsIgnoreCase(text); }

        Node parse(){
            Node node = parseExpr();
            if(peek().type != TokType.EOF)
                throw new ParseException(msg("la.err.unexpected_token", peek().text));
            return node;
        }

        Node parseExpr(){ return parseOr(); }

        Node parseOr(){
            Node left = parseAnd();
            while(isOp("||")){ next(); left = new Binary("or", left, parseAnd()); }
            return left;
        }

        Node parseAnd(){
            Node left = parseEq();
            while(isOp("&&")){ next(); left = new Binary("land", left, parseEq()); }
            return left;
        }

        Node parseEq(){
            Node left = parseRel();
            while(isOp("==") || isOp("!=") || isOp("===")){
                String sym = next().text;
                String op = sym.equals("==") ? "equal" : sym.equals("!=") ? "notEqual" : "strictEqual";
                left = new Binary(op, left, parseRel());
            }
            return left;
        }

        Node parseRel(){
            Node left = parseXor();
            while(isOp("<") || isOp(">") || isOp("<=") || isOp(">=")){
                String sym = next().text;
                String op = sym.equals("<") ? "lessThan" : sym.equals(">") ? "greaterThan"
                    : sym.equals("<=") ? "lessThanEq" : "greaterThanEq";
                left = new Binary(op, left, parseXor());
            }
            return left;
        }

        Node parseXor(){
            Node left = parseBand();
            while(isIdent("xor")){ next(); left = new Binary("xor", left, parseBand()); }
            return left;
        }

        Node parseBand(){
            Node left = parseShift();
            while(isOp("&")){ next(); left = new Binary("and", left, parseShift()); }
            return left;
        }

        Node parseShift(){
            Node left = parseAdd();
            while(isOp("<<") || isOp(">>") || isOp(">>>")){
                String sym = next().text;
                String op = sym.equals("<<") ? "shl" : sym.equals(">>") ? "shr" : "ushr";
                left = new Binary(op, left, parseAdd());
            }
            return left;
        }

        Node parseAdd(){
            Node left = parseMul();
            while(isOp("+") || isOp("-")){
                String sym = next().text;
                left = new Binary(sym.equals("+") ? "add" : "sub", left, parseMul());
            }
            return left;
        }

        Node parseMul(){
            Node left = parseUnary();
            while(isOp("*") || isOp("/") || isOp("//") || isOp("%") || isOp("%%")){
                String sym = next().text;
                String op;
                switch(sym){
                    case "*": op = "mul"; break;
                    case "/": op = "div"; break;
                    case "//": op = "idiv"; break;
                    case "%": op = "mod"; break;
                    default: op = "emod"; break;
                }
                left = new Binary(op, left, parseUnary());
            }
            return left;
        }

        Node parseUnary(){
            if(isOp("-")){ next(); return new Unary("neg", parseUnary()); }
            if(isOp("!")){ next(); return new Unary("lnot", parseUnary()); }
            if(isOp("~")){ next(); return new Unary("not", parseUnary()); }
            return parsePow();
        }

        // ^ 右结合
        Node parsePow(){
            Node base = parseAtom();
            if(isOp("^")){
                next();
                return new Binary("pow", base, parseUnary());
            }
            return base;
        }

        Node parseAtom(){
            Token tok = peek();
            if(tok.type == TokType.NUM){
                next();
                return new Num(Double.parseDouble(tok.text));
            }
            if(tok.type == TokType.LPAREN){
                next();
                Node inner = parseExpr();
                if(peek().type != TokType.RPAREN)
                    throw new ParseException(msg("la.err.expected_rparen"));
                next();
                return inner;
            }
            if(tok.type == TokType.IDENT){
                next();
                String name = tok.text;
                if(peek().type == TokType.LPAREN){
                    next();
                    List<Node> args = new ArrayList<>();
                    if(peek().type != TokType.RPAREN){
                        args.add(parseExpr());
                        while(peek().type == TokType.COMMA){
                            next();
                            args.add(parseExpr());
                        }
                    }
                    if(peek().type != TokType.RPAREN)
                        throw new ParseException(msg("la.err.expected_rparen_func"));
                    next();
                    String funcName = resolveFuncName(name);
                    if(funcName == null)
                        throw new ParseException(msg("la.err.unknown_func", name));
                    if(UNARY_OPS.contains(funcName)){
                        if(args.size() != 1) throw new ParseException(msg("la.err.requires_1_arg", funcName));
                        return new Unary(funcName, args.get(0));
                    }
                    if(FUNC_BINARY_OPS.contains(funcName)){
                        if(args.size() != 2) throw new ParseException(msg("la.err.requires_2_args", funcName));
                        return new Binary(funcName, args.get(0), args.get(1));
                    }
                    throw new ParseException(msg("la.err.unknown_func", name));
                }
                return new Var(name);
            }
            throw new ParseException(msg("la.err.unexpected_token", tok.text));
        }

        static String resolveFuncName(String name){
            String lower = name.toLowerCase();
            if(UNARY_OPS.contains(lower)) return lower;
            if(FUNC_BINARY_OPS.contains(lower)) return lower;
            return null;
        }
    }

    static class TempStack{
        int counter = 0;
        // CSE 保护的临时变量集合：这些变量被缓存供后续复用，不允许被栈式重用覆盖
        final Set<String> protectedTemps = new HashSet<>();

        // 分配临时变量，优先复用 operand 中的临时变量（但不能是 CSE 保护的）
        String alloc(String... operands){
            for(String op : operands){
                if(isTemp(op) && !protectedTemps.contains(op)) return op;
            }
            return TMP + counter++;
        }

        // 标记临时变量为 CSE 保护，防止被栈式重用
        void protect(String temp){
            if(isTemp(temp)) protectedTemps.add(temp);
        }
    }

    // 编译表达式为 op 语句链。
    // @param dest 目标变量名
    // @param expr 表达式字符串（如 "cos(a) * 10 + x"）
    // @return op 语句列表，最后一条的 dest 为目标变量
    public static List<OpLine> compile(String dest, String expr){
        List<Token> tokens = tokenize(expr);
        Parser parser = new Parser(tokens);
        Node ast = parser.parse();

        // 优化 pass：常量折叠、代数化简（需通过 print "expr-opt:true" 开启）
        if(optimizationEnabled){
            ast = optimize(ast);
        }

        List<OpLine> ops = new ArrayList<>();
        TempStack temps = new TempStack();
        // CSE 缓存：子表达式签名 → 已计算的临时变量名（始终开启）
        Map<String, String> cseCache = new HashMap<>();
        String result = compileNode(ast, ops, temps, cseCache);

        if(isTemp(result)){
            // 优化：将最后一条 op 的 dest 改为目标变量
            OpLine last = ops.get(ops.size() - 1);
            ops.set(ops.size() - 1, new OpLine(last.op, dest, last.a, last.b));
        }else{
            // 结果是简单值，生成赋值 op
            ops.add(new OpLine("add", dest, result, "0"));
        }
        return ops;
    }

    static String compileNode(Node node, List<OpLine> ops, TempStack temps, Map<String, String> cseCache){
        // CSE：检查是否已计算过此子表达式，命中则直接复用临时变量
        if(node instanceof Unary || node instanceof Binary){
            String sig = nodeSignature(node);
            if(sig != null){
                String cached = cseCache.get(sig);
                if(cached != null) return cached;
            }
        }

        String result;
        if(node instanceof Num){
            result = formatNum(((Num)node).val);
        }else if(node instanceof Var){
            result = ((Var)node).name;
        }else if(node instanceof Unary){
            Unary u = (Unary)node;
            String operand = compileNode(u.operand, ops, temps, cseCache);
            String temp = temps.alloc(operand);
            String opName;
            String a, b;
            switch(u.op){
                case "neg": opName = "sub"; a = "0"; b = operand; break;
                case "lnot": opName = "equal"; a = operand; b = "0"; break;
                default: opName = u.op; a = operand; b = "0"; break;
            }
            ops.add(new OpLine(opName, temp, a, b));
            result = temp;
        }else if(node instanceof Binary){
            Binary bn = (Binary)node;
            String left = compileNode(bn.l, ops, temps, cseCache);
            String right = compileNode(bn.r, ops, temps, cseCache);
            String temp = temps.alloc(left, right);
            ops.add(new OpLine(bn.op, temp, left, right));
            result = temp;
        }else{
            throw new ParseException(msg("la.err.unknown_node"));
        }

        // CSE：缓存此子表达式的结果，供后续相同子表达式复用
        if(node instanceof Unary || node instanceof Binary){
            String sig = nodeSignature(node);
            if(sig != null && isTemp(result)){
                cseCache.put(sig, result);
                temps.protect(result);
            }
        }

        return result;
    }

    // 计算 AST 节点的结构签名（用于 CSE 检测相同子表达式）。
    // 签名设计：op + 左子树签名 + 右子树签名，保证结构相同的子表达式签名一致。
    // 交换律未归一化（a+b 和 b+a 签名不同），保守处理避免语义错误。
    // 注意：Num/Var 不单独做 CSE（它们不产生 op），但作为子树参与父节点的签名。
    static String nodeSignature(Node node){
        if(node instanceof Num) return "N" + ((Num)node).val;
        if(node instanceof Var) return "V" + ((Var)node).name;
        if(node instanceof Unary){
            Unary u = (Unary)node;
            return "U(" + u.op + "," + nodeSignature(u.operand) + ")";
        }
        if(node instanceof Binary){
            Binary b = (Binary)node;
            return "B(" + b.op + "," + nodeSignature(b.l) + "," + nodeSignature(b.r) + ")";
        }
        return null;
    }

    // 从 op 语句链重建表达式字符串。
    // @param ops op 语句列表，最后一条的 dest 为目标变量
    // @return 表达式字符串（如 "cos(a) * 10 + x"），无法重建时返回 null
    public static String rebuild(List<OpLine> ops){
        if(ops == null || ops.isEmpty()) return null;

        // 从最后一条 op 开始（dest 为目标变量，非临时变量）
        OpLine root = ops.get(ops.size() - 1);
        Node expr = opToNode(root);
        if(expr == null) return null;

        // 向前遍历，替换临时变量引用
        for(int i = ops.size() - 2; i >= 0; i--){
            OpLine op = ops.get(i);
            if(isTemp(op.dest)){
                Node sub = opToNode(op);
                if(sub == null) return null;
                expr = substituteTemp(expr, op.dest, sub);
            }
        }

        // AST 优化 pass：常量折叠、代数化简（需通过 print "expr-opt:true" 开启）
        if(optimizationEnabled){
            expr = optimize(expr);
        }
        return nodeToString(expr);
    }

    // 将一条 op 转为 AST 节点，包含简化规则
    static Node opToNode(OpLine op){
        // 解码简化：以下规则处理原版编码方式（赋值、取反、逻辑非），属于解码而非优化
        // add x a 0 → a（原版赋值：op add dest src 0）
        if(op.op.equals("add") && op.b.equals("0")) return operandToNode(op.a);
        // add x 0 a → a（交换律）
        if(op.op.equals("add") && op.a.equals("0")) return operandToNode(op.b);

        // sub x 0 a → -a（原版取反）
        if(op.op.equals("sub") && op.a.equals("0")) return new Unary("neg", operandToNode(op.b));

        // mul x a 1 → a（原版乘1赋值）
        if(op.op.equals("mul") && op.b.equals("1")) return operandToNode(op.a);
        // mul x 1 a → a（交换律）
        if(op.op.equals("mul") && op.a.equals("1")) return operandToNode(op.b);
        // mul x a 0 → 0
        if(op.op.equals("mul") && op.b.equals("0")) return new Num(0);
        // mul x 0 a → 0
        if(op.op.equals("mul") && op.a.equals("0")) return new Num(0);

        // div x a 1 → a
        if(op.op.equals("div") && op.b.equals("1")) return operandToNode(op.a);

        // mod x a 1 → 0
        if(op.op.equals("mod") && op.b.equals("1")) return new Num(0);

        // pow x a 0 → 1
        if(op.op.equals("pow") && op.b.equals("0")) return new Num(1);
        // pow x a 1 → a
        if(op.op.equals("pow") && op.b.equals("1")) return operandToNode(op.a);
        // pow x 0 a → 0
        if(op.op.equals("pow") && op.a.equals("0")) return new Num(0);
        // pow x 1 a → 1
        if(op.op.equals("pow") && op.a.equals("1")) return new Num(1);

        // equal x a 0 → !a（原版逻辑非：op equal dest src 0）
        if(op.op.equals("equal") && op.b.equals("0")) return new Unary("lnot", operandToNode(op.a));
        // equal x 0 a → !a（交换律）
        if(op.op.equals("equal") && op.a.equals("0")) return new Unary("lnot", operandToNode(op.b));

        // land x a 0 → false
        if(op.op.equals("land") && op.b.equals("0")) return new Num(0);
        // land x 0 a → false
        if(op.op.equals("land") && op.a.equals("0")) return new Num(0);
        // land x a 1 → a
        if(op.op.equals("land") && op.b.equals("1")) return operandToNode(op.a);
        // land x 1 a → a
        if(op.op.equals("land") && op.a.equals("1")) return operandToNode(op.b);

        // or x a 0 → a
        if(op.op.equals("or") && op.b.equals("0")) return operandToNode(op.a);
        // or x 0 a → a
        if(op.op.equals("or") && op.a.equals("0")) return operandToNode(op.b);
        // or x a 1 → true
        if(op.op.equals("or") && op.b.equals("1")) return new Num(1);
        // or x 1 a → true
        if(op.op.equals("or") && op.a.equals("1")) return new Num(1);

        // and x a 0 → 0
        if(op.op.equals("and") && (op.a.equals("0") || op.b.equals("0"))) return new Num(0);

        // 一元运算符
        if(UNARY_OPS.contains(op.op)) return new Unary(op.op, operandToNode(op.a));

        // 二元运算符（含函数型）
        return new Binary(op.op, operandToNode(op.a), operandToNode(op.b));
    }

    static Node operandToNode(String operand){
        try{
            return new Num(Double.parseDouble(operand));
        }catch(NumberFormatException e){
            return new Var(operand);
        }
    }

    // 递归优化 AST：先优化子节点，再应用常量折叠和代数化简。
    // 参考 mindcode ExpressionOptimizer 的优化策略：
    //   - 常量折叠：两个常量操作数直接计算结果
    //   - 代数化简：a+0=a, a*1=a, a*0=0, a-a=0, a^0=1 等
    //   - 嵌套优化：-(-x)=x, !(!x)=x, a-(-b)=a+b
    //   - 相同操作数：a==a=true, a/a=1
    //   - 幂等函数：abs(abs(x))=abs(x), floor(floor(x))=floor(x)
    //   - 比较取反：!(a<b)=a>=b, !(a==b)=a!=b
    //   - 吸收律：min(a, max(a, b))=a, max(a, min(a, b))=a
    static Node optimize(Node node){
        if(node instanceof Binary){
            Binary b = (Binary)node;
            Node l = optimize(b.l);
            Node r = optimize(b.r);

            // 常量折叠：两个常量操作数直接计算
            if(l instanceof Num && r instanceof Num){
                Double result = computeConstant(b.op, ((Num)l).val, ((Num)r).val);
                if(result != null) return new Num(result);
            }

            // 代数化简
            switch(b.op){
                case "add":
                    if(isZero(r)) return l;
                    if(isZero(l)) return r;
                    // a + a = a * 2
                    if(nodesEqual(l, r)) return optimize(new Binary("mul", l, new Num(2)));
                    // a + (-b) = a - b
                    if(r instanceof Unary && ((Unary)r).op.equals("neg"))
                        return optimize(new Binary("sub", l, ((Unary)r).operand));
                    // (-a) + b = b - a
                    if(l instanceof Unary && ((Unary)l).op.equals("neg"))
                        return optimize(new Binary("sub", r, ((Unary)l).operand));
                    break;
                case "sub":
                    if(isZero(r)) return l;
                    if(nodesEqual(l, r)) return new Num(0);
                    // a - (-b) = a + b
                    if(r instanceof Unary && ((Unary)r).op.equals("neg"))
                        return optimize(new Binary("add", l, ((Unary)r).operand));
                    // (-a) - b = -(a + b)
                    if(l instanceof Unary && ((Unary)l).op.equals("neg"))
                        return optimize(new Unary("neg", new Binary("add", ((Unary)l).operand, r)));
                    break;
                case "mul":
                    if(isZero(l) || isZero(r)) return new Num(0);
                    if(isOne(r)) return l;
                    if(isOne(l)) return r;
                    // a * (-1) = -a, (-1) * a = -a
                    if(isNegOne(r)) return optimize(new Unary("neg", l));
                    if(isNegOne(l)) return optimize(new Unary("neg", r));
                    break;
                case "div":
                    if(isOne(r)) return l;
                    if(isNegOne(r)) return optimize(new Unary("neg", l));
                    if(nodesEqual(l, r) && !isZero(l)) return new Num(1);
                    break;
                case "idiv":
                    if(isOne(r)) return l;
                    if(isNegOne(r)) return optimize(new Unary("neg", l));
                    break;
                case "mod":
                case "emod":
                    if(isOne(r)) return new Num(0);
                    if(nodesEqual(l, r) && !isZero(l)) return new Num(0);
                    break;
                case "pow":
                    if(isZero(r)) return new Num(1);
                    if(isOne(r)) return l;
                    if(isOne(l)) return new Num(1);
                    if(isZero(l)) return new Num(0);
                    break;
                case "equal":
                case "strictEqual":
                    if(nodesEqual(l, r)) return new Num(1);
                    // a == 0 → !a
                    if(isZero(r)) return optimize(new Unary("lnot", l));
                    if(isZero(l)) return optimize(new Unary("lnot", r));
                    break;
                case "notEqual":
                    if(nodesEqual(l, r)) return new Num(0);
                    break;
                case "lessThan":
                    // a < a = false
                    if(nodesEqual(l, r)) return new Num(0);
                    break;
                case "greaterThan":
                    if(nodesEqual(l, r)) return new Num(0);
                    break;
                case "lessThanEq":
                case "greaterThanEq":
                    if(nodesEqual(l, r)) return new Num(1);
                    break;
                case "land":
                    if(isZero(l) || isZero(r)) return new Num(0);
                    if(isOne(r)) return l;
                    if(isOne(l)) return r;
                    break;
                case "or":
                    if(isOne(l) || isOne(r)) return new Num(1);
                    if(isZero(r)) return l;
                    if(isZero(l)) return r;
                    break;
                case "and":
                    if(isZero(l) || isZero(r)) return new Num(0);
                    break;
                case "min":
                case "max":
                    // min(a, a) = a, max(a, a) = a
                    if(nodesEqual(l, r)) return l;
                    // 吸收律：min(a, max(a, b)) = a, max(a, min(a, b)) = a
                    String opposite = b.op.equals("min") ? "max" : "min";
                    if(r instanceof Binary && ((Binary)r).op.equals(opposite) &&
                       (nodesEqual(l, ((Binary)r).l) || nodesEqual(l, ((Binary)r).r)))
                        return l;
                    if(l instanceof Binary && ((Binary)l).op.equals(opposite) &&
                       (nodesEqual(r, ((Binary)l).l) || nodesEqual(r, ((Binary)l).r)))
                        return r;
                    break;
            }

            return new Binary(b.op, l, r);
        }
        if(node instanceof Unary){
            Unary u = (Unary)node;
            Node operand = optimize(u.operand);

            // 常量折叠：一元运算符的常量操作数直接计算
            if(operand instanceof Num){
                Double result = computeUnaryConstant(u.op, ((Num)operand).val);
                if(result != null) return new Num(result);
            }

            // 嵌套优化：-(-x)=x, !(!x)=x, ~~x=x
            if(u.op.equals("neg") && operand instanceof Unary && ((Unary)operand).op.equals("neg"))
                return ((Unary)operand).operand;
            if(u.op.equals("lnot") && operand instanceof Unary && ((Unary)operand).op.equals("lnot"))
                return ((Unary)operand).operand;
            if(u.op.equals("not") && operand instanceof Unary && ((Unary)operand).op.equals("not"))
                return ((Unary)operand).operand;
            // -0 = 0
            if(u.op.equals("neg") && isZero(operand)) return new Num(0);

            // 幂等函数折叠：abs(abs(x))=abs(x), floor(floor(x))=floor(x), ceil(ceil(x))=ceil(x),
            // round(round(x))=round(x), sign(sign(x))=sign(x), sqrt(sqrt(x)) 不折叠（非幂等）
            if(u.operand instanceof Unary){
                Unary inner = (Unary)u.operand;
                if(isIdempotent(u.op) && u.op.equals(inner.op))
                    return operand; // operand 已是 optimize 后的 inner
            }

            // 比较取反：!(a < b) = a >= b, !(a == b) = a != b 等
            if(u.op.equals("lnot") && operand instanceof Binary){
                Binary bin = (Binary)operand;
                String inverted = invertComparison(bin.op);
                if(inverted != null)
                    return optimize(new Binary(inverted, bin.l, bin.r));
            }

            return new Unary(u.op, operand);
        }
        return node;
    }

    static boolean isZero(Node node){
        return node instanceof Num && ((Num)node).val == 0;
    }

    static boolean isOne(Node node){
        return node instanceof Num && ((Num)node).val == 1;
    }

    static boolean isNegOne(Node node){
        return node instanceof Num && ((Num)node).val == -1;
    }

    // 幂等函数：f(f(x)) = f(x)
    static boolean isIdempotent(String op){
        switch(op){
            case "abs": case "floor": case "ceil": case "round":
            case "sign": case "not": case "lnot":
                return true;
            default:
                return false;
        }
    }

    // 比较运算符取反，无法取反时返回 null
    static String invertComparison(String op){
        switch(op){
            case "equal": case "strictEqual": return "notEqual";
            case "notEqual": return "equal";
            case "lessThan": return "greaterThanEq";
            case "greaterThan": return "lessThanEq";
            case "lessThanEq": return "greaterThan";
            case "greaterThanEq": return "lessThan";
            default: return null;
        }
    }

    // 判断两个节点是否结构相等（用于 a==a、a-a=0 等简化，以及 CSE 签名辅助）
    static boolean nodesEqual(Node a, Node b){
        if(a instanceof Num && b instanceof Num) return ((Num)a).val == ((Num)b).val;
        if(a instanceof Var && b instanceof Var) return ((Var)a).name.equals(((Var)b).name);
        if(a instanceof Unary && b instanceof Unary){
            Unary ua = (Unary)a, ub = (Unary)b;
            return ua.op.equals(ub.op) && nodesEqual(ua.operand, ub.operand);
        }
        if(a instanceof Binary && b instanceof Binary){
            Binary ba = (Binary)a, bb = (Binary)b;
            return ba.op.equals(bb.op) && nodesEqual(ba.l, bb.l) && nodesEqual(ba.r, bb.r);
        }
        return false;
    }

    // 二元运算符的常量折叠，无法计算时返回 null
    static Double computeConstant(String op, double a, double b){
        switch(op){
            case "add": return a + b;
            case "sub": return a - b;
            case "mul": return a * b;
            case "div": return b != 0 ? a / b : null;
            case "idiv": return b != 0 ? Math.floor(a / b) : null;
            case "mod": return b != 0 ? a % b : null;
            case "emod": return b != 0 ? a - b * Math.floor(a / b) : null;
            case "pow": return Math.pow(a, b);
            case "min": return Math.min(a, b);
            case "max": return Math.max(a, b);
            case "lessThan": return a < b ? 1.0 : 0.0;
            case "greaterThan": return a > b ? 1.0 : 0.0;
            case "lessThanEq": return a <= b ? 1.0 : 0.0;
            case "greaterThanEq": return a >= b ? 1.0 : 0.0;
            case "equal":
            case "strictEqual": return a == b ? 1.0 : 0.0;
            case "notEqual": return a != b ? 1.0 : 0.0;
            case "land": return (a != 0 && b != 0) ? 1.0 : 0.0;
            case "or": return (a != 0 || b != 0) ? 1.0 : 0.0;
            case "and": return (double)((long)a & (long)b);
            case "xor": return (double)((long)a ^ (long)b);
            case "shl": return (double)((long)a << (int)b);
            case "shr": return (double)((long)a >> (int)b);
            case "ushr": return (double)((long)a >>> (int)b);
            case "angle": return Math.atan2(b, a) * 180.0 / Math.PI;
            case "len": return Math.sqrt(a * a + b * b);
            case "logn": return (a > 0 && b > 0 && b != 1) ? Math.log(a) / Math.log(b) : null;
            default: return null; // noise 等随机函数无法常量折叠
        }
    }

    // 一元运算符的常量折叠，无法计算时返回 null
    static Double computeUnaryConstant(String op, double a){
        switch(op){
            case "neg": return -a;
            case "lnot": return a == 0 ? 1.0 : 0.0;
            case "not": return (double)(~(long)a);
            case "abs": return Math.abs(a);
            case "sign": return Math.signum(a);
            case "log": return a > 0 ? Math.log(a) : null;
            case "log10": return a > 0 ? Math.log10(a) : null;
            case "floor": return Math.floor(a);
            case "ceil": return Math.ceil(a);
            case "round": return (double)Math.round(a);
            case "sqrt": return a >= 0 ? Math.sqrt(a) : null;
            case "sin": return Math.sin(a);
            case "cos": return Math.cos(a);
            case "tan": return Math.tan(a);
            case "asin": return Math.abs(a) <= 1 ? Math.asin(a) : null;
            case "acos": return Math.abs(a) <= 1 ? Math.acos(a) : null;
            case "atan": return Math.atan(a);
            default: return null; // rand 等随机函数无法常量折叠
        }
    }

    // 在 AST 中将指定临时变量名替换为 replacement 子树
    static Node substituteTemp(Node node, String tempName, Node replacement){
        if(node instanceof Var){
            return ((Var)node).name.equals(tempName) ? replacement : node;
        }
        if(node instanceof Num) return node;
        if(node instanceof Unary){
            Unary u = (Unary)node;
            return new Unary(u.op, substituteTemp(u.operand, tempName, replacement));
        }
        if(node instanceof Binary){
            Binary b = (Binary)node;
            return new Binary(b.op,
                substituteTemp(b.l, tempName, replacement),
                substituteTemp(b.r, tempName, replacement));
        }
        return node;
    }

    static String nodeToString(Node node){
        if(node instanceof Num) return formatNum(((Num)node).val);
        if(node instanceof Var) return ((Var)node).name;

        if(node instanceof Unary){
            Unary u = (Unary)node;
            String inner = nodeToString(u.operand);
            if(u.op.equals("neg")){
                if(u.operand instanceof Binary) return "-(" + inner + ")";
                if(u.operand instanceof Num) return "-" + inner;
                if(u.operand instanceof Unary && ((Unary)u.operand).op.equals("neg"))
                    return "-(" + inner + ")";
                return "-" + inner;
            }
            if(u.op.equals("lnot")){
                if(u.operand instanceof Binary) return "!(" + inner + ")";
                return "!" + inner;
            }
            // 函数调用：cos(a), sin(a) 等
            return u.op + "(" + inner + ")";
        }

        if(node instanceof Binary){
            Binary b = (Binary)node;

            // 函数型二元运算符：输出 func(a, b) 形式（max/min/angle 等）
            // 不走 left+sym+right 路径，避免输出 "10max20" 这种无法被解析器读回的形式
            if(FUNC_BINARY_OPS.contains(b.op)){
                return b.op + "(" + nodeToString(b.l) + ", " + nodeToString(b.r) + ")";
            }

            int prec = getPrecedence(b.op);
            String sym = opToSymbol(b.op);
            String left = nodeToString(b.l);
            String right = nodeToString(b.r);

            // 左子节点加括号
            if(b.l instanceof Binary){
                int lp = getPrecedence(((Binary)b.l).op);
                if(lp < prec || (lp == prec && b.op.equals("pow")))
                    left = "(" + left + ")";
            }

            // 右子节点加括号
            if(b.r instanceof Binary){
                int rp = getPrecedence(((Binary)b.r).op);
                if(rp < prec || (rp == prec && !b.op.equals("pow")))
                    right = "(" + right + ")";
            }

            return left + sym + right;
        }

        throw new ParseException(msg("la.err.unknown_node"));
    }

    // 从 bundle 获取本地化消息，找不到时返回 key 本身（开发提醒）
    private static String msg(String key, Object... args){
        if(Core.bundle == null || !Core.bundle.has(key)) return key;
        return args.length == 0 ? Core.bundle.get(key) : Core.bundle.format(key, args);
    }

    // 判断变量名是否为临时变量（_0, _1, _2 ... 格式，_ 后必须跟数字）
    public static boolean isTemp(String name){
        if(name == null || name.length() < 2 || !name.startsWith(TMP)) return false;
        for(int i = 1; i < name.length(); i++){
            if(!Character.isDigit(name.charAt(i))) return false;
        }
        return true;
    }

    static int getPrecedence(String op){
        Integer p = OP_PRECEDENCE.get(op);
        return p != null ? p : PREC_ATOM;
    }

    static String opToSymbol(String op){
        String s = OP_TO_SYMBOL.get(op);
        return s != null ? s : op;
    }

    static String formatNum(double val){
        if(val == (long)val) return String.valueOf((long)val);
        return String.valueOf(val);
    }
}
