package frontend.error;

import frontend.ASTNode;

import java.util.ArrayList;

public class eVisitorStmt {
    private eVisitor eVisitor;

    public eVisitorStmt(eVisitor eVisitor) {
        this.eVisitor = eVisitor;
    }

    public void visit(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (!children.isEmpty() && children.get(0).isType("PRINTFTK")) {
            visitPrintf(node);
        }
        else if (!children.isEmpty() && children.get(0).isType("RETURNTK")) {
            visitReturn(node);
        }
        else if (!children.isEmpty() && children.get(0).isType("BREAKTK") || children.get(0).isType("CONTINUETK")) {
            visitBreakContinue(node);
        }
        else if (!children.isEmpty() && children.get(0).isType("Block")) {
            eVisitorBlock eVisitorBlock = new eVisitorBlock(eVisitor);
            eVisitor.beforeBlock();
            eVisitorBlock.visit(children.get(0));
                eVisitor.afterBlock(children.get(0));
        }
        else if (!children.isEmpty() && children.get(0).isType("LVal")) {
            visitAssign(node);
        }
        else if (!children.isEmpty() && children.get(0).isType("Exp")) {
            visitExpStmt(node);
        }
        else if (!children.isEmpty() && children.get(0).isType("IFTK")) {
            visitIfStmt(node);
        }
        else {
            if (!children.isEmpty() && children.get(0).isType("FORTK")) eVisitor.cycle++;
            for (ASTNode child : children) {
                if (child.isType("Cond")) {
                    visitCond(child);
                }
                else if (child.isType("Stmt")) {
                    visit(child);
                }
                else if (child.isType("ForStmt")) {
                    visitForStmt(child);
                }
            }
            if (!children.isEmpty() && children.get(0).isType("FORTK")) eVisitor.cycle--;
        }
    }

    public void visitForStmt(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        eVisitorExp eVisitorExp = new eVisitorExp(eVisitor);
        for (ASTNode child : children) {
            if (child.isType("LVal")) {
                eVisitor.checkLValc(child);
                eVisitor.checkLValh(child);
            }
            else if (child.isType("Exp")) {
                eVisitorExp.visit(child);
            }
        }
    }

    public void visitIfStmt(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        visitCond(children.get(2));
        for (int i=3;i<children.size();i++) {
            if (children.get(i).isType("Stmt")) visit(children.get(i));
        }
    }

    public void visitCond(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        eVisitorExp eVisitorExp = new eVisitorExp(eVisitor);
        eVisitorExp.visitLOrExp(children.get(0));
    }

    public void visitExpStmt(ASTNode node) {
        eVisitorExp eVisitorExp = new eVisitorExp(eVisitor);
        eVisitorExp.visit(node.getChildren().get(0));
    }

    public void visitAssign(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        eVisitor.checkLValc(children.get(0));
        eVisitor.checkLValh(children.get(0));
        eVisitorExp eVisitorExp = new eVisitorExp(eVisitor);
        eVisitorExp.visit(children.get(2));
    }

    public void visitBreakContinue(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (eVisitor.cycle == 0) eVisitor.error.addError("m", children.get(0).getToken().getLine());
    }

    private void visitReturn(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.size() > 1 && !children.get(1).isType("SEMICN")) {
            if(!eVisitor.Func.returnInt()) eVisitor.error.addError("f", children.get(0).getToken().getLine());
        }
        if (children.size() > 1 && children.get(1).isType("Exp")) {
            eVisitorExp eVisitorExp = new eVisitorExp(eVisitor);
            eVisitorExp.visit(children.get(1));
        }
    }

    private void visitPrintf(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (!checkPrintf(node)) eVisitor.error.addError("l", children.get(0).getToken().getLine());
        for (int i=0;i<children.size();i++) {
            if (children.get(i).isType("Exp")) {
                eVisitorExp eVisitorExp = new eVisitorExp(eVisitor);
                eVisitorExp.visit(children.get(i));
            }
        }
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
}
