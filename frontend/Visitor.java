package frontend;

import llvm.BasicBlock;
import llvm.Builder;
import llvm.Function;
import llvm.IRType;
import llvm.Parameter;
import llvm.Value;
import llvm.instr.AllocaInstr;
import llvm.instr.GepInstr;
import llvm.instr.LoadInstr;
import llvm.instr.StoreInstr;
import util.Error;
import util.Tool;

import java.io.FileNotFoundException;
import java.util.ArrayList;

public class Visitor {
    public ASTNode root;
    public Error error;
    public int TableCnt = 1;
    public SymbolTable symbolTable = new SymbolTable(1);
    public SymbolTable pt = symbolTable;
    public ASTNode FuncFParams = null;
    public Symbol Func = null;
    public Tool tool = new Tool();
    public int cycle = 0;

    public Visitor(ASTNode root, Error error) {
        this.root = root;
        this.error = error;
    }

    public SymbolTable analyse() {
        visit(root);
        return symbolTable;
    }

    public void visit(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (node.isType("Stmt")) {
            VisitorStmt visitorStmt = new VisitorStmt(this);
            visitorStmt.visit(node);
        }
        else if (node.isType("Decl")) {
            VisitorDecl visitorDecl = new VisitorDecl(this);
            visitorDecl.visit(node);
        }
        else if (node.isType("FuncDef")) {
            VisitorFuncDef visitorFuncDef = new VisitorFuncDef(this);
            visitorFuncDef.visit(node);
        }
        else if (node.isType("MainFuncDef")) {
            Function function = new Function("int", "main");
            Builder.addFunction(function);
            Func = new Symbol(0,0,"main","func", "int", function);
            Builder.addBasicBlock(new BasicBlock());
            beforeBlock();
            VisitorBlock visitorBlock = new VisitorBlock(this);
            visitorBlock.visit(children.get(4));
            afterBlock(children.get(4));
        }
        else {
            if (!children.isEmpty() && children.get(0).isType("FORTK")) cycle++;
            for (ASTNode child : children) {
                visit(child);
            }
            if (!children.isEmpty() && children.get(0).isType("FORTK")) cycle--;
        }
    }





    public boolean checkReturn(ASTNode block) {
        ArrayList<ASTNode> children = block.getChildren();
        ASTNode BlockItem = children.get(children.size() - 2);
        ArrayList<ASTNode> children2 = BlockItem.getChildren();
        if (children2.isEmpty()) return false;
        ASTNode stmt = children2.get(0);
        ArrayList<ASTNode> children3 = stmt.getChildren();
        if (children3.size()<=2) return false;
        return children3.get(0).isType("RETURNTK") && !children3.get(1).isType("SEMICN");
    }



    public boolean checkLValc(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String s = children.get(0).getValue();
        if (!pt.checkSymbol(s)) {
            error.addError("c", children.get(0).getToken().getLine());
            return false;
        }
        else {
            return true;
        }
    }

    public boolean checkLValh(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String s = children.get(0).getValue();
        if (pt.checkSymbol(s) && pt.getSymbol(s).isConst()) {
            error.addError("h", children.get(0).getToken().getLine());
            return false;
        }
        return true;
    }



    public ArrayList<Symbol> visitFuncFParams() {
        ArrayList<ASTNode> children = FuncFParams.getChildren();
        ArrayList<Symbol> symbols = new ArrayList<>();
        for (ASTNode child : children) {
            if (child.isType("FuncFParam")) {
                symbols.add(visitFuncFParam(child));
            }
        }
        return symbols;
    }

    public Symbol visitFuncFParam(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String btype = visitBType(children.get(0));
        String type = "var";
        Value value;
        if (children.size() > 3 && children.get(2).isType("LBRACK")) {
            type = "array";
            value = new Parameter(new IRType("ptr", new IRType("i32")));
        }
        else {
            value = new Parameter(new IRType("i32"));
        }
        addSymbol(children.get(1).getToken().getLine(), children.get(1).getValue(), type, btype, "var", value);
        Symbol s = pt.getSymbol(children.get(1).getValue());
        Func.addParam(s);

        return s;
    }

    public String visitBType(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        return children.get(0).getValue();
    }

    public void addSymbol(int line, String token, String type, String btype, String con, Value value) {
        if (!pt.addSymbol(token, type, btype, con, value)) {
            error.addError("b", line);
        }
        value.setIsConst(con.equals("const"));
    }

    public Symbol addSymbol(int line, String token, String type, String returnType, Value value) {
        if (!pt.addSymbol(token, type, returnType, value)) {
            error.addError("b", line);
        }
        return pt.last;
    }

    public ArrayList<Symbol> beforeBlock() {
        ArrayList<Symbol> symbols = new ArrayList<>();
        SymbolTable s = new SymbolTable(TableCnt+1);
        TableCnt++;
        pt.addChild(s);
        pt = s;
        if (FuncFParams != null) {
            symbols = visitFuncFParams();
            FuncFParams = null;
        }
        return symbols;
    }

    public void afterBlock(ASTNode BlockNode) {
        ArrayList<ASTNode> children = BlockNode.getChildren();
        pt = pt.getFather();
        if (pt.getId() == 1 && Func.returnInt() && !checkReturn(BlockNode)) {
            error.addError("g", children.get(children.size() - 1).getToken().getLine());
        }
    }

    public Value visitLVal(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        Symbol s = pt.getSymbol(children.get(0).getValue());
        if (children.size() == 1) {
            return s.getValue();
        }
        else {
            VisitorExp visitorExp = new VisitorExp(this);
            Value base = s.getValue();
            LoadInstr loadInstr = null;
            if (base.getType().toString().equals("i32**")) {
                loadInstr = new LoadInstr(base);
            }
            Value index = visitorExp.visit(children.get(2));
            GepInstr gepInstr = null;
            if (loadInstr != null) {
                gepInstr = new GepInstr(loadInstr, index);
            }
            else {
                gepInstr = new GepInstr(base, index);
            }
            return gepInstr;
        }
    }
}
