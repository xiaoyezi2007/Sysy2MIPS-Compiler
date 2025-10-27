package frontend.error;

import frontend.ASTNode;
import frontend.error.eVisitor;
import frontend.error.eVisitorDecl;
import frontend.error.eVisitorStmt;

import java.util.ArrayList;

public class eVisitorBlock {
    eVisitor eVisitor;

    public eVisitorBlock(eVisitor eVisitor) {
        this.eVisitor = eVisitor;
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
            eVisitorStmt eVisitorStmt = new eVisitorStmt(eVisitor);
            eVisitorStmt.visit(children.get(0));
        }
        else if (children.get(0).isType("Decl")) {
            eVisitorDecl eVisitorDecl = new eVisitorDecl(eVisitor);
            eVisitorDecl.visit(children.get(0));
        }
    }
}
