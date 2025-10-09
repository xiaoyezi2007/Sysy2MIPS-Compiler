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
    private Tool tool = new Tool();

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
        if (node.isLeaf()) return;
        ArrayList<ASTNode> children = node.getChildren();
        if (node.getName().equals("ConstDecl")) visitConstDecl(node);
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
        }
        else {
            for (ASTNode child : children) {
                visit(child);
            }
        }
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
        pt.addSymbol(children.get(1).getValue(), type, btype, "var");
    }

    public void visitFuncDef(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String returnType = visitFuncType(children.get(0));
        if (children.get(3).isType("FuncFParams")) FuncFParams = children.get(3);
        pt.addSymbol(children.get(1).getValue(), "func", returnType);
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
        pt.addSymbol(children.get(0).getValue(), type, btype, con);
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
        pt.addSymbol(children.get(0).getValue(), type, btype, con);
    }

    public String visitBType(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        return children.get(0).getValue();
    }
}
