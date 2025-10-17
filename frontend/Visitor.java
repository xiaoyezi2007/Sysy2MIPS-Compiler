package frontend;

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
            Func = new Symbol(0,0,"main","func", "int");
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



    public void checkLValc(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String s = children.get(0).getValue();
        if (!pt.checkSymbol(s)) error.addError("c", children.get(0).getToken().getLine());
    }

    public void checkLValh(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String s = children.get(0).getValue();
        if (pt.checkSymbol(s) && pt.getSymbol(s).isConst()) error.addError("h", children.get(0).getToken().getLine());
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

    public String visitBType(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        return children.get(0).getValue();
    }

    public void addSymbol(int line, String token, String type, String btype, String con) {
        if (!pt.addSymbol(token, type, btype, con)) {
            error.addError("b", line);
        }
    }

    public Symbol addSymbol(int line, String token, String type, String returnType) {
        if (!pt.addSymbol(token, type, returnType)) {
            error.addError("b", line);
        }
        return pt.last;
    }

    public void beforeBlock() {
        SymbolTable s = new SymbolTable(TableCnt+1);
        TableCnt++;
        pt.addChild(s);
        pt = s;
        if (FuncFParams != null) {
            visitFuncFParams();
            FuncFParams = null;
        }
    }

    public void afterBlock(ASTNode BlockNode) {
        ArrayList<ASTNode> children = BlockNode.getChildren();
        pt = pt.getFather();
        if (pt.getId() == 1 && Func.returnInt() && !checkReturn(BlockNode)) {
            error.addError("g", children.get(children.size() - 1).getToken().getLine());
        }
    }
}
