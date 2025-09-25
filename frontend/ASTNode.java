package frontend;

import java.util.ArrayList;

public class ASTNode {
    public ArrayList<ASTNode> children = new ArrayList<>();
    public String name = "";
    public Token token = null;

    public ASTNode(String name) {
        this.name = name;
    }

    public ASTNode(Token token) {
        this.token = token;
    }

    public void addChild(ASTNode child) {
        children.add(child);
    }

    public void clearChild() {
        children.clear();
    }

    public void delChild() {
        children.remove(children.size() - 1);
    }

    public boolean isLeaf() {
        return token != null;
    }

    public void printASTNode() {
        for (ASTNode child : children) {
            child.printASTNode();
        }
        if (isLeaf()) {
            token.lexerPrint();
        }
        else if (!name.equals("Decl") && !name.equals("BType") && !name.equals("BlockItem")) {
            System.out.println("<"+name+">");
        }
    }

    @Override
    public ASTNode clone() {
        ASTNode newNode = new ASTNode(name);
        newNode.token = this.token;
        newNode.children = new ArrayList<ASTNode>(children);
        return newNode;
    }
}
