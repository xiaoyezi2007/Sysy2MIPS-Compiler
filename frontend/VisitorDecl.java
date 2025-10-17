package frontend;

import java.util.ArrayList;

public class VisitorDecl {
    private Visitor visitor;

    public VisitorDecl(Visitor visitor) {
        this.visitor = visitor;
    }

    public void visit(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.get(0).isType("ConstDecl")) visitConstDecl(children.get(0));
        else if (children.get(0).isType("VarDecl")) {
            visitVarDecl(children.get(0));
        }
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
        visitor.addSymbol(children.get(0).getToken().getLine(), children.get(0).getValue(), type, btype, con);
        for (ASTNode child : children) {
            if (child.isType("ConstExp")) {
                visitConstExp(child);
            }
            else if (child.isType("InitVal")) {
                visitInitVal(child);
            }
        }
    }

    public void visitInitVal(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        VisitorExp visitorExp = new VisitorExp(visitor);
        for (ASTNode child : children) {
            if (child.isType("Exp")) {
                visitorExp.visit(child);
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

    public String visitBType(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        return children.get(0).getValue();
    }

    public void visitConstDef(ASTNode node, String con, String btype) {
        ArrayList<ASTNode> children = node.getChildren();
        String type = "var";
        if (children.get(1).isType("LBRACK")) type = "array";
        visitor.addSymbol(children.get(0).getToken().getLine(), children.get(0).getValue(), type, btype, con);
        for (ASTNode child : children) {
            if (child.isType("ConstExp")) {
                visitConstExp(child);
            }
            else if (child.isType("ConstInitVal")) {
                visitConstInitVal(child);
            }
        }
    }

    public void visitConstExp(ASTNode node) {
        VisitorExp visitorExp = new VisitorExp(visitor);
        visitorExp.visitAddExp(node.getChildren().get(0));
    }

    public void visitConstInitVal(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        for (ASTNode child : children) {
            if (child.isType("ConstExp")) {
                visitConstExp(child);
            }
        }
    }
}
