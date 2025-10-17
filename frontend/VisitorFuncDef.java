package frontend;

import java.util.ArrayList;

public class VisitorFuncDef {
    Visitor visitor;
    public VisitorFuncDef(Visitor visitor) {
        this.visitor = visitor;
    }

    public void visit(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        String returnType = visitFuncType(children.get(0));
        if (children.get(3).isType("FuncFParams")) visitor.FuncFParams = children.get(3);
        visitor.Func = visitor.addSymbol(children.get(1).getToken().getLine(), children.get(1).getValue(), "func", returnType);

        VisitorBlock visitorBlock = new VisitorBlock(visitor);
        visitor.beforeBlock();
        if (children.get(3).isType("FuncFParams")) {
            visitorBlock.visit(children.get(5));
            visitor.afterBlock(children.get(5));
        }
        else {
            visitorBlock.visit(children.get(4));
            visitor.afterBlock(children.get(4));
        }
    }

    public String visitFuncType(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        return children.get(0).getValue();
    }
}
