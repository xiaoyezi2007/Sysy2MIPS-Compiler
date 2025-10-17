package frontend;

import java.util.ArrayList;

public class VisitorExp {
    Visitor visitor;

    public VisitorExp(Visitor visitor) {
        this.visitor = visitor;
    }

    public void visit(ASTNode node) {
        visitAddExp(node.getChildren().get(0));
    }

    public void visitLOrExp(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.size() == 1) {
            visitLAndExp(children.get(0));
        }
        else {
            visitLOrExp(children.get(0));
            visitLAndExp(children.get(2));
        }
    }

    public void visitLAndExp(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.size() == 1) {
            visitEqExp(children.get(0));
        }
        else {
            visitLAndExp(children.get(0));
            visitEqExp(children.get(2));
        }
    }

    public void visitEqExp(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.size() == 1) {
            visitRelExp(children.get(0));
        }
        else {
            visitEqExp(children.get(0));
            visitRelExp(children.get(0));
        }
    }

    public void visitRelExp(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.size() == 1) {
            visitAddExp(children.get(0));
        }
        else {
            visitRelExp(children.get(0));
            visitAddExp(children.get(2));
        }
    }

    public void visitAddExp(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.size() == 1) {
            visitMulExp(children.get(0));
        }
        else {
            visitAddExp(children.get(0));
            visitMulExp(children.get(2));
        }
    }

    public void visitMulExp(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.size() == 1) {
            visitUnaryExp(children.get(0));
        }
        else {
            visitMulExp(children.get(0));
            visitUnaryExp(children.get(2));
        }
    }

    public void visitUnaryExp(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.get(0).isType("PrimaryExp")) {
            visitPrimaryExp(children.get(0));
        }
        else if (children.get(0).isType("IDENFR")) {
            visitFuncCall(node);
        }
        else if (children.get(0).isType("UnaryOp")) {
            visitUnaryExp(children.get(1));
        }
    }

    public void visitFuncCall(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String funcName = children.get(0).getValue();
        if (!visitor.pt.checkSymbol(funcName) && !funcName.equals("getint")) {
            visitor.error.addError("c", children.get(0).getToken().getLine());
            return;
        }
        if (children.get(2).isType("FuncRParams")) {
            visitFuncRParams(children.get(0).getToken().getLine(), funcName, children.get(2));
        }
        else {
            if (funcName.equals("getint")) return;
            if (!visitor.pt.getSymbol(funcName).noParam()) visitor.error.addError("d", children.get(0).getToken().getLine());
        }
    }

    public void visitFuncRParams(int line, String funcName, ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        ArrayList<Integer> types = new ArrayList<>();
        for (ASTNode child : children) {
            if (child.isType("Exp")) {
                visit(child);
                types.add(getVarType(child));
            }
        }
        Symbol s = visitor.pt.getSymbol(funcName);
        if (funcName.equals("getint") || types.size() != s.getParamNum()) {
            visitor.error.addError("d", line);
            return;
        }
        if (!visitor.pt.getSymbol(funcName).checkParams(types)) {
            visitor.error.addError("e", line);
        }
    }

    public int getVarType(ASTNode node) {  //so ugly!!!!!!!!
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
                                if (visitor.pt.checkSymbol(name) && visitor.pt.getSymbol(name).isArray()) {
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

    public void visitPrimaryExp(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.get(0).isType("LVal")) {
            visitor.checkLValc(children.get(0));
        }
        else if (children.get(0).isType("LPARENT")) {
            visit(children.get(1));
        }
    }
}
