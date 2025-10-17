package frontend;

import java.util.ArrayList;

public class VisitorBlock {
    Visitor visitor;

    public VisitorBlock(Visitor visitor) {
        this.visitor = visitor;
    }

    public void visit(ASTNode node) {
        for (ASTNode child : node.getChildren()) {
            if (child.isType("BlockItem")) {
                visitBlockItem(child);
            }
        }
    }

    public void visitBlockItem(ASTNode node) {
        ArrayList<ASTNode> children = node.getChildren();
        if (children.get(0).isType("Stmt")) {
            VisitorStmt visitorStmt = new VisitorStmt(visitor);
            visitorStmt.visit(children.get(0));
        }
        else if (children.get(0).isType("Decl")) {
            VisitorDecl visitorDecl = new VisitorDecl(visitor);
            visitorDecl.visit(children.get(0));
        }
    }
}
