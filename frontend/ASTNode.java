package frontend;

import java.util.ArrayList;

public class ASTNode {
    private ArrayList<ASTNode> children = new ArrayList<>();
    private String name = "";
    private Token token = null;
    private ASTNode father = null;

    public ASTNode(String name) {
        this.name = name;
    }

    public ASTNode(Token token) {
        this.token = token;
    }

    public void addChild(ASTNode child) {
        children.add(child);
        child.father = this;
    }

    public ASTNode getFather() {
        return father;
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

    public ArrayList<ASTNode> getChildren() {
        return children;
    }

    public String getName() {
        return name;
    }

    public Token getToken() {
        return token;
    }

    public String getValue() {
        if (token != null) {
            return token.getValue();
        }
        return name;
    }

    public Boolean isType(String kind) {
        if (token != null) {
            return token.isType(kind);
        }
        return name.equals(kind);
    }

    @Override
    public ASTNode clone() {
        ASTNode newNode = new ASTNode(name);
        newNode.token = this.token;
        newNode.children = new ArrayList<ASTNode>(children);
        return newNode;
    }
}
