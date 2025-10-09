package frontend;

import util.Error;
import util.Tool;

import java.io.FileNotFoundException;
import java.util.ArrayList;

public class Visitor {
    private ASTNode root;
    private Error error;
    private int TableCnt = 1;
    private SymbolTable symbolTable = new SymbolTable(1);
    private SymbolTable pt = symbolTable;
    private ASTNode FuncFParams = null;
    private Symbol Func = null;
    private Tool tool = new Tool();
    private int cycle = 0;

    public Visitor(ASTNode root, Error error) {
        this.root = root;
        this.error = error;
    }

    public SymbolTable analyse() {
        visit(root);
        try {
            tool.setOutput("symbol.txt");
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        symbolTable.print();
        return symbolTable;
    }

    public void visit(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (!children.isEmpty() && children.get(0).isType("PRINTFTK")) {
            if (!checkPrintf(node)) error.addError("l", children.get(0).getToken().getLine());
        }
        if (node.isType("LVal") && (node.getFather().isType("ForStmt") || node.getFather().isType("Stmt"))) visitLVal(node);
        if (node.getName().equals("ConstDecl")) visitConstDecl(node);
        else if (node.isType("IDENFR") && !node.getValue().equals("getint")) {
            if (!pt.checkSymbol(node.getValue())) error.addError("c", node.getToken().getLine());
        }
        else if (children.size()>1 && children.get(0).isType("RETURNTK") && !children.get(1).isType("SEMICN")) {
            if (!Func.returnInt()) error.addError("f", children.get(0).getToken().getLine());
        }
        else if (node.isType("BREAKTK") || node.isType("CONTINUETK")) {
            if (cycle == 0) error.addError("m", node.getToken().getLine());
        }
        else if (node.isType("MAINTK")) {
            Func = new Symbol(0,0,"main","func", "int");
        }
        else if (node.isType("UnaryExp") && children.size() > 1 && children.get(0).isType("IDENFR")
        && children.get(1).isType("LPARENT")) {
            visitFuncCall(node);
        }
        else if (node.getName().equals("VarDecl")) visitVarDecl(node);
        else if (node.getName().equals("FuncDef")) visitFuncDef(node);
        else if (node.getName().equals("Block")) {
            SymbolTable s = new SymbolTable(TableCnt+1);
            TableCnt++;
            pt.addChild(s);
            pt = s;
            if (FuncFParams != null) {
                visitFuncFParams();
                FuncFParams = null;
            }
            for (ASTNode child : children) {
                visit(child);
            }
            pt = pt.getFather();
            if (pt.getId() == 1 && Func.returnInt() && !checkReturn(node)) {
                error.addError("g", node.getChildren().get(children.size() - 1).getToken().getLine());
            }
        }
        else {
            if (!children.isEmpty() && children.get(0).isType("FORTK")) cycle++;
            for (ASTNode child : children) {
                visit(child);
            }
            if (!children.isEmpty() && children.get(0).isType("FORTK")) cycle--;
        }
    }

    public void visitLVal(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String s = children.get(0).getValue();
        if (pt.checkSymbol(s) && pt.getSymbol(s).isConst()) error.addError("h", children.get(0).getToken().getLine());
    }

    private boolean checkPrintf(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String strcon = children.get(2).getValue();
        int cnt = 0;
        for (int i=0; i<strcon.length()-1; i++) {
            if (strcon.charAt(i) == '%' && strcon.charAt(i+1) == 'd') {
                cnt++;
            }
        }
        int cnt1 = 0;
        for (int i=3;i<children.size();i++) {
            if (children.get(i).isType("COMMA")) cnt1++;
        }
        return cnt == cnt1;
    }

    private boolean checkReturn(ASTNode block) {
        ArrayList<ASTNode> children = block.getChildren();
        ASTNode BlockItem = children.get(children.size() - 2);
        ArrayList<ASTNode> children2 = BlockItem.getChildren();
        if (children2.isEmpty()) return false;
        ASTNode stmt = children2.get(0);
        ArrayList<ASTNode> children3 = stmt.getChildren();
        if (children3.size()<=2) return false;
        return children3.get(0).isType("RETURNTK") && !children3.get(1).isType("SEMICN");
    }

    public void visitFuncCall(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String funcName = children.get(0).getValue();
        if (!pt.checkSymbol(funcName) && !funcName.equals("getint")) {
            error.addError("c", children.get(0).getToken().getLine());
            return;
        }
        if (children.get(2).isType("FuncRParams")) {
            visitFuncRParams(children.get(0).getToken().getLine(), funcName, children.get(2));
        }
        else {
            if (funcName.equals("getint")) return;
            if (!pt.getSymbol(funcName).noParam()) error.addError("d", children.get(0).getToken().getLine());
        }
    }

    public void visitFuncRParams(int line, String funcName, ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        ArrayList<Integer> types = new ArrayList<>();
        for (ASTNode child : children) {
            visit(child);
            if (child.isType("Exp")) {
                types.add(getVarType(child));
            }
        }
        Symbol s = pt.getSymbol(funcName);
        if (funcName.equals("getint") || types.size() != s.getParamNum()) {
            error.addError("d", line);
            return;
        }
        if (!pt.getSymbol(funcName).checkParams(types)) {
            error.addError("e", line);
        }
    }

    public int getVarType(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.size()==1&&children.get(0).isType("AddExp")) {
            ASTNode addExp = children.get(0);
            ArrayList<ASTNode> children2 = addExp.getChildren();
            if (children2.size()==1&&children2.get(0).isType("MulExp")) {
                ASTNode mulExp = children2.get(0);
                ArrayList<ASTNode> children3 = mulExp.getChildren();
                if (children3.size()==1&&children3.get(0).isType("UnaryExp")) {
                    ASTNode unaryExp = children3.get(0);
                    ArrayList<ASTNode> children4 = unaryExp.getChildren();
                    if (children4.size()==1&&children4.get(0).isType("PrimaryExp")) {
                        ASTNode primaryExp = children4.get(0);
                        ArrayList<ASTNode> children5 = primaryExp.getChildren();
                        if (children5.size() == 1 && children5.get(0).isType("LVal")) {
                            ASTNode lVal = children5.get(0);
                            ArrayList<ASTNode> children6 = lVal.getChildren();
                            if (children6.size()==1&&children6.get(0).isType("IDENFR")) {
                                String name = children6.get(0).getValue();
                                if (pt.checkSymbol(name) && pt.getSymbol(name).isArray()) {
                                    return 1;
                                }
                            }
                        }
                    }
                }
            }
        }
        return 0;
    }

    public void visitFuncFParams() {
        ArrayList<ASTNode> children = FuncFParams.getChildren();
        for (ASTNode child : children) {
            if (child.isType("FuncFParam")) {
                visitFuncFParam(child);
            }
        }
    }

    public void visitFuncFParam(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String btype = visitBType(children.get(0));
        String type = "var";
        if (children.size() > 3 && children.get(2).isType("LBRACK")) {
            type = "array";
        }
        addSymbol(children.get(1).getToken().getLine(), children.get(1).getValue(), type, btype, "var");
        Symbol s = pt.getSymbol(children.get(1).getValue());
        Func.addParam(s);
    }

    public void visitFuncDef(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String returnType = visitFuncType(children.get(0));
        if (children.get(3).isType("FuncFParams")) FuncFParams = children.get(3);
        Func = addSymbol(children.get(1).getToken().getLine(), children.get(1).getValue(), "func", returnType);
        if (children.get(3).isType("FuncFParams")) visit(children.get(5));
        else visit(children.get(4));
    }

    public String visitFuncType(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        return children.get(0).getValue();
    }

    public void visitVarDecl(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String con = "var";
        if (children.get(0).isType("STATICTK")) {
            con = "static";
            String btype = visitBType(children.get(1));
            for (int i = 2; i < children.size(); i++) {
                if (children.get(i).isType("VarDef")) {
                    visitVarDef(children.get(i), con, btype);
                }
            }
        }
        else {
            String btype = visitBType(children.get(0));
            for (int i = 1; i < children.size(); i++) {
                if (children.get(i).isType("VarDef")) {
                    visitVarDef(children.get(i), con, btype);
                }
            }
        }
    }

    public void visitVarDef(ASTNode node, String con, String btype) {
        ArrayList<ASTNode> children = node.getChildren();
        String type = "var";
        if (children.size() > 2 && children.get(1).isType("LBRACK")) {
            type = "array";
        }
        addSymbol(children.get(0).getToken().getLine(), children.get(0).getValue(), type, btype, con);
        for (ASTNode child : children) {
            if (child.isType("ConstExp") || child.isType("InitVal")) {
                visit(child);
            }
        }
    }

    public void visitConstDecl(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String con = "const";
        String btype = visitBType(children.get(1));
        for (int i = 2; i < children.size(); i++) {
            if (children.get(i).getName().equals("ConstDef")) {
                visitConstDef(children.get(i), con, btype);
            }
        }
    }

    public void visitConstDef(ASTNode node, String con, String btype) {
        ArrayList<ASTNode> children = node.getChildren();
        String type = "var";
        if (children.get(1).isType("LBRACK")) type = "array";
        addSymbol(children.get(0).getToken().getLine(), children.get(0).getValue(), type, btype, con);
        for (ASTNode child : children) {
            if (child.isType("ConstExp") || child.isType("ConstInitVal")) {
                visit(child);
            }
        }
    }

    public String visitBType(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        return children.get(0).getValue();
    }

    private void addSymbol(int line, String token, String type, String btype, String con) {
        if (!pt.addSymbol(token, type, btype, con)) {
            error.addError("b", line);
        }
    }

    private Symbol addSymbol(int line, String token, String type, String returnType) {
        if (!pt.addSymbol(token, type, returnType)) {
            error.addError("b", line);
        }
        return pt.last;
    }
}
