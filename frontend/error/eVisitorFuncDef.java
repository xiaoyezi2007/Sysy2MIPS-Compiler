package frontend.error;

import frontend.ASTNode;
import frontend.error.eVisitor;
import frontend.error.eVisitorBlock;

import java.util.ArrayList;

public class eVisitorFuncDef {
    eVisitor eVisitor;
    public eVisitorFuncDef(eVisitor eVisitor) {
        this.eVisitor = eVisitor;
    }

    public void visit(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String returnType = visitFuncType(children.get(0));
        if (children.get(3).isType("FuncFParams")) eVisitor.FuncFParams = children.get(3);
        eVisitor.Func = eVisitor.addSymbol(children.get(1).getToken().getLine(), children.get(1).getValue(), "func", returnType);

        eVisitorBlock eVisitorBlock = new eVisitorBlock(eVisitor);
        eVisitor.beforeBlock();
        if (children.get(3).isType("FuncFParams")) {
            eVisitorBlock.visit(children.get(5));
            eVisitor.afterBlock(children.get(5));
        }
        else {
            eVisitorBlock.visit(children.get(4));
            eVisitor.afterBlock(children.get(4));
        }
    }

    public String visitFuncType(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        return children.get(0).getValue();
    }
}
